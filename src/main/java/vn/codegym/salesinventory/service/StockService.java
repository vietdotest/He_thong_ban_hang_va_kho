package vn.codegym.salesinventory.service;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.CommandConflictException;

/** FIFO stock. Holds are effective by deadline, so a late maintenance loop cannot oversell. */
public final class StockService {
    private final DataSource source;private final Clock clock;
    public StockService(DataSource source) { this(source,Clock.systemUTC()); }
    public StockService(DataSource source,Clock clock) { this.source=source;this.clock=clock; }
    public record ReceiptLine(long product,long unit,BigDecimal quantity,String lot,LocalDate expiry) { }
    public record ReceiptInput(long warehouse,long supplier,String reference,LocalDate date,List<ReceiptLine> lines) { }
    public record StockSnapshot(BigDecimal onHand,BigDecimal reserved,BigDecimal available) {
        public Map<String,Object> display() { return Map.of("onHand",onHand.toPlainString(),"reserved",reserved.toPlainString(),"available",available.toPlainString()); }
    }
    static BigDecimal activeHeld(Connection c,long lot,Instant now) throws SQLException {
        return CreditService.sum(c,"SELECT COALESCE(SUM(quantity-consumed-released),0) amount FROM mvp_holds WHERE lot_id=? AND (protected_at IS NOT NULL OR expires_at>?)",lot,MvpCommands.timestamp(now));
    }
    public static StockSnapshot snapshot(Connection c,long warehouse,long product,Instant now) throws SQLException {
        BigDecimal on=BigDecimal.ZERO,held=BigDecimal.ZERO;
        for(var lot:Sql.query(c,"SELECT id,on_hand FROM mvp_lots WHERE warehouse_id=? AND product_id=? ORDER BY received_date,id",warehouse,product)) {
            on=on.add(MvpCommands.decimal(lot.get("on_hand")));held=held.add(activeHeld(c,Sql.id(lot.get("id")),now));
        }
        return new StockSnapshot(on,held,on.subtract(held));
    }
    public long saveReceipt(long actor,long id,long version,String key,ReceiptInput input) {
        if(input==null||input.date==null||input.date.getYear()<1000||input.date.getYear()>9999||input.lines==null||input.lines.isEmpty()||input.lines.size()>100)
            throw new IllegalArgumentException("Phiếu nhập cần ngày hợp lệ và 1–100 dòng.");
        String reference=MvpCommands.text(input.reference,100,"Số chứng từ");
        var clean=new ArrayList<ReceiptLine>();
        for(var l:input.lines) {
            if(l==null||l.product<=0||l.unit<=0||l.quantity==null||l.quantity.stripTrailingZeros().scale()>6) throw new IllegalArgumentException("Dòng nhập không hợp lệ.");
            clean.add(new ReceiptLine(l.product,l.unit,MvpCommands.quantity(l.quantity),MvpCommands.text(l.lot,100,"Số lô"),l.expiry));
        }
        return Sql.transaction(source,c->{
            MvpAccess.warehouse(c,actor,"STOCK_RECEIPT_WRITE",input.warehouse);
            if(id>0) { var old=Sql.one(c,"SELECT * FROM mvp_receipts WHERE id=?",id);if(Sql.id(old.get("warehouse_id"))!=input.warehouse) throw new SecurityException(); }
            var ticket=MvpCommands.begin(c,actor,"RECEIPT_SAVE",key,List.of(id,version,input.warehouse,input.supplier,reference,input.date,clean).toString());
            if(ticket.replayId()!=null) return ticket.replayId();
            var supplier=Sql.one(c,"SELECT status,warehouse_id FROM suppliers WHERE id=? FOR SHARE",input.supplier);
            if(Sql.id(supplier.get("warehouse_id"))!=input.warehouse) throw new SecurityException("Nhà cung cấp không thuộc kho nhập được giao.");
            if(!"ACTIVE".equals(supplier.get("status"))) throw new IllegalArgumentException("Nhà cung cấp đã ngừng giao dịch.");
            long saved=id;
            if(id==0) saved=Sql.insert(c,"INSERT INTO mvp_receipts(warehouse_id,supplier_id,document_ref,receipt_date,created_by) VALUES(?,?,?,?,?)",input.warehouse,input.supplier,reference,input.date,actor);
            else {
                var old=Sql.one(c,"SELECT * FROM mvp_receipts WHERE id=? FOR UPDATE",id);MvpCommands.version(old,version);
                if(!"DRAFT".equals(old.get("status"))) throw new CommandConflictException("Phiếu đã xác nhận chỉ được xem.");
                Sql.update(c,"UPDATE mvp_receipts SET supplier_id=?,document_ref=?,receipt_date=?,version=version+1 WHERE id=?",input.supplier,reference,input.date,id);
                Sql.update(c,"DELETE FROM mvp_receipt_lines WHERE receipt_id=?",id);
            }
            int position=0;
            for(var l:clean) {
                var conversion=UnitService.orderConversion(c,l.product,l.unit,l.quantity,input.warehouse);
                Sql.insert(c,"INSERT INTO mvp_receipt_lines(receipt_id,position,product_id,unit_id,quantity,factor,unit_version,base_quantity,lot_code,expires_on) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        saved,position++,l.product,l.unit,l.quantity,conversion.factor(),conversion.version(),conversion.baseQuantity(),l.lot,l.expiry);
            }
            AuditService.record(c,actor,"STOCK_RECEIPT_SAVED","STOCK_RECEIPT",saved,null,Map.of("warehouse",input.warehouse,"lines",clean.size()));
            return MvpCommands.finish(c,ticket,saved);
        });
    }
    public long confirmReceipt(long actor,long id,long version,String key) {
        return Sql.transaction(source,c->{
            var known=Sql.one(c,"SELECT warehouse_id FROM mvp_receipts WHERE id=?",id);MvpAccess.warehouse(c,actor,"STOCK_RECEIPT_WRITE",Sql.id(known.get("warehouse_id")));
            var ticket=MvpCommands.begin(c,actor,"RECEIPT_CONFIRM",key,List.of(id,version).toString());if(ticket.replayId()!=null) return ticket.replayId();
            var row=Sql.one(c,"SELECT * FROM mvp_receipts WHERE id=? FOR UPDATE",id);MvpCommands.version(row,version);
            if(!"DRAFT".equals(row.get("status"))) throw new CommandConflictException("Phiếu đã xác nhận.");
            var lines=Sql.query(c,"SELECT * FROM mvp_receipt_lines WHERE receipt_id=? ORDER BY product_id,position",id);
            if(lines.isEmpty()) throw new IllegalArgumentException("Phiếu nhập không có dòng hàng.");
            for(var line:lines) {
                long lineId=Sql.id(line.get("id"));BigDecimal qty=MvpCommands.decimal(line.get("base_quantity"));
                long lot=Sql.insert(c,"INSERT INTO mvp_lots(warehouse_id,product_id,receipt_line_id,lot_code,received_date,expires_on,on_hand) VALUES(?,?,?,?,?,?,?)",
                        row.get("warehouse_id"),line.get("product_id"),lineId,line.get("lot_code"),row.get("receipt_date"),line.get("expires_on"),qty);
                movement(c,actor,lot,"RECEIPT","RECEIPT_LINE",lineId,qty,qty,clock.instant());
                var unit=Sql.one(c,"SELECT name FROM product_units WHERE id=?",line.get("unit_id"));
                UnitService.snapshot(c,"RECEIPT:"+lineId,new UnitService.Conversion(Sql.id(line.get("product_id")),Sql.id(line.get("unit_id")),Sql.text(unit.get("name")),MvpCommands.decimal(line.get("quantity")),MvpCommands.decimal(line.get("factor")),Sql.id(line.get("unit_version")),qty,Sql.id(row.get("warehouse_id"))));
                Sql.update(c,"INSERT IGNORE INTO product_transaction_references(product_id,reference_type,reference_id) VALUES(?,'RECEIPT',?)",line.get("product_id"),Long.toString(id));
            }
            Sql.update(c,"UPDATE mvp_receipts SET status='CONFIRMED',version=version+1,confirmed_by=?,confirmed_at=? WHERE id=?",actor,MvpCommands.timestamp(clock.instant()),id);
            Sql.update(c,"INSERT IGNORE INTO supplier_receipt_references(supplier_id,receipt_reference) VALUES(?,?)",row.get("supplier_id"),"RECEIPT:"+id);
            AuditService.record(c,actor,"STOCK_RECEIPT_CONFIRMED","STOCK_RECEIPT",id,row,Map.of("status","CONFIRMED","warehouse",row.get("warehouse_id")));
            return MvpCommands.finish(c,ticket,id);
        });
    }
    static void movement(Connection c,long actor,long lot,String type,String sourceType,long sourceId,BigDecimal delta,BigDecimal after,Instant now) throws SQLException {
        Sql.insert(c,"INSERT INTO mvp_stock_events(lot_id,event_type,source_type,source_id,quantity_delta,on_hand_after,actor_id,occurred_at) VALUES(?,?,?,?,?,?,?,?)",lot,type,sourceType,sourceId,delta,after,actor,MvpCommands.timestamp(now));
    }
    /** Caller owns dealer/order locks and has already checked credit and acceptance permissions. */
    public static void reserve(Connection c,long actor,long order,Instant now) throws SQLException {
        var header=Sql.one(c,"SELECT warehouse_id FROM orders WHERE id=? FOR UPDATE",order);
        if(header.get("warehouse_id")==null) throw new IllegalArgumentException("Đơn chưa có kho phục vụ.");
        long warehouse=Sql.id(header.get("warehouse_id"));
        var lines=Sql.query(c,"SELECT id,product_id,base_quantity FROM order_lines WHERE order_id=? ORDER BY product_id,position",order);
        var needed=new LinkedHashMap<Long,BigDecimal>();var remaining=new HashMap<Long,BigDecimal>();
        for(var line:lines) {
            long id=Sql.id(line.get("id"));BigDecimal shipped=CreditService.sum(c,"SELECT COALESCE(SUM(quantity-returned_quantity),0) amount FROM mvp_shipment_lines WHERE order_line_id=?",id);
            BigDecimal held=CreditService.sum(c,"SELECT COALESCE(SUM(quantity-consumed-released),0) amount FROM mvp_holds WHERE order_line_id=? AND (protected_at IS NOT NULL OR expires_at>?)",id,MvpCommands.timestamp(now));
            BigDecimal qty=MvpCommands.decimal(line.get("base_quantity")).subtract(shipped).subtract(held);
            if(qty.signum()<0) throw new IllegalStateException("Số lượng xử lý vượt đơn.");remaining.put(id,qty);
            if(qty.signum()>0) needed.merge(Sql.id(line.get("product_id")),qty,BigDecimal::add);
        }
        var lotLists=new HashMap<Long,List<Map<String,Object>>>();var lotAvailable=new HashMap<Long,BigDecimal>();
        for(var entry:needed.entrySet()) {
            var lots=Sql.query(c,"SELECT id,on_hand FROM mvp_lots WHERE warehouse_id=? AND product_id=? ORDER BY received_date,id FOR UPDATE",warehouse,entry.getKey());
            BigDecimal total=BigDecimal.ZERO;
            for(var lot:lots) { long id=Sql.id(lot.get("id"));BigDecimal free=MvpCommands.decimal(lot.get("on_hand")).subtract(activeHeld(c,id,now));lotAvailable.put(id,free);total=total.add(free); }
            if(total.compareTo(entry.getValue())<0) throw new CommandConflictException("Không đủ tồn khả dụng cho SKU "+entry.getKey()+". Tối đa còn "+total.stripTrailingZeros().toPlainString()+" đơn vị cơ sở.");
            lotLists.put(entry.getKey(),lots);
        }
        for(var line:lines) {
            long lineId=Sql.id(line.get("id"));BigDecimal qty=remaining.get(lineId);if(qty.signum()==0) continue;
            for(var lot:lotLists.get(Sql.id(line.get("product_id")))) {
                long lotId=Sql.id(lot.get("id"));BigDecimal take=qty.min(lotAvailable.get(lotId));if(take.signum()<=0) continue;
                Sql.insert(c,"INSERT INTO mvp_holds(order_id,order_line_id,lot_id,quantity,expires_at,created_at) VALUES(?,?,?,?,?,?)",order,lineId,lotId,take,MvpCommands.timestamp(now.plus(Duration.ofHours(24))),MvpCommands.timestamp(now));
                lotAvailable.put(lotId,lotAvailable.get(lotId).subtract(take));qty=qty.subtract(take);if(qty.signum()==0) break;
            }
        }
        Sql.update(c,"UPDATE orders SET fulfillment_state='RESERVED' WHERE id=? AND fulfillment_state IN ('NONE','RESERVED')",order);
        AuditService.record(c,actor,"STOCK_RESERVED","ORDER",order,null,Map.of("expires_at",now.plus(Duration.ofHours(24)).toString()));
    }
    public List<Map<String,Object>> stock(long actor,Long warehouse,String query) {
        String q=query==null?"":query.trim();if(q.length()>150) throw new IllegalArgumentException("Tìm kiếm tối đa 150 ký tự.");
        return Sql.snapshot(source,c->{var access=MvpAccess.internal(c,actor,"STOCK_READ");var result=new ArrayList<Map<String,Object>>();
            for(var row:Sql.query(c,"SELECT DISTINCT l.warehouse_id,l.product_id,w.code warehouse_code,w.name warehouse_name,w.address,p.sku,p.name,p.base_unit FROM mvp_lots l JOIN warehouses w ON w.id=l.warehouse_id JOIN products p ON p.id=l.product_id WHERE (? IS NULL OR l.warehouse_id=?) AND (p.sku LIKE ? OR p.name LIKE ?) ORDER BY w.name,p.sku",warehouse,warehouse,"%"+q+"%","%"+q+"%")) {
                long w=Sql.id(row.get("warehouse_id"));if(!MvpAccess.canReadWarehouse(c,actor,access,w)) continue;
                var values=new LinkedHashMap<>(row);values.putAll(snapshot(c,w,Sql.id(row.get("product_id")),clock.instant()).display());result.add(values);
            }return result;
        });
    }
    public List<Map<String,Object>> warehouses(long actor,String permission) { return Sql.snapshot(source,c->{var a=MvpAccess.internal(c,actor,permission);var out=new ArrayList<Map<String,Object>>();for(var w:Sql.query(c,"SELECT id,code,name,address FROM warehouses ORDER BY name,id")) if(MvpAccess.canReadWarehouse(c,actor,a,Sql.id(w.get("id")))) out.add(w);return out;}); }
    public List<Map<String,Object>> receipts(long actor) { return Sql.snapshot(source,c->{MvpAccess.internal(c,actor,"STOCK_RECEIPT_WRITE");return Sql.query(c,"SELECT r.*,w.name warehouse_name,s.name supplier_name FROM mvp_receipts r JOIN warehouses w ON w.id=r.warehouse_id JOIN suppliers s ON s.id=r.supplier_id JOIN user_warehouses uw ON uw.warehouse_id=r.warehouse_id AND uw.user_id=? ORDER BY r.id DESC LIMIT 100",actor);}); }
    public Map<String,Object> receipt(long actor,long id) { return Sql.snapshot(source,c->{var row=new LinkedHashMap<>(Sql.one(c,"SELECT r.*,w.name warehouse_name,w.address warehouse_address,s.name supplier_name FROM mvp_receipts r JOIN warehouses w ON w.id=r.warehouse_id JOIN suppliers s ON s.id=r.supplier_id WHERE r.id=?",id));MvpAccess.warehouse(c,actor,"STOCK_RECEIPT_WRITE",Sql.id(row.get("warehouse_id")));row.put("lines",Sql.query(c,"SELECT l.*,p.sku,p.name,u.name unit_name FROM mvp_receipt_lines l JOIN products p ON p.id=l.product_id JOIN product_units u ON u.id=l.unit_id WHERE l.receipt_id=? ORDER BY l.position",id));return row;}); }
    public List<Map<String,Object>> card(long actor,long warehouse,long product) { return Sql.snapshot(source,c->{var a=MvpAccess.internal(c,actor,"STOCK_READ");if(!MvpAccess.canReadWarehouse(c,actor,a,warehouse)) throw new SecurityException();return Sql.query(c,"SELECT e.*,l.lot_code,l.product_id FROM mvp_stock_events e JOIN mvp_lots l ON l.id=e.lot_id WHERE l.warehouse_id=? AND l.product_id=? ORDER BY e.occurred_at,e.id",warehouse,product);}); }
}
