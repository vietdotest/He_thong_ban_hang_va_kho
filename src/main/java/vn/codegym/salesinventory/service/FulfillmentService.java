package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.CommandConflictException;

/** Warehouse commands lock the dealer, source order, FIFO lots, then their holds. */
public final class FulfillmentService {
    private final DataSource source;private final Clock clock;
    public FulfillmentService(DataSource source){this(source,Clock.systemUTC());}
    public FulfillmentService(DataSource source,Clock clock){this.source=source;this.clock=clock;}
    public record DispatchLine(long orderLine,BigDecimal quantity){}
    static Map<String,Object> warehouseOrder(Connection c,long actor,long id,String permission)throws SQLException {
        var known=Sql.one(c,"SELECT dealer_id,warehouse_id FROM orders WHERE id=?",id);
        if(known.get("warehouse_id")==null)throw new IllegalArgumentException("Đơn chưa có kho phục vụ.");
        long warehouse=Sql.id(known.get("warehouse_id"));MvpAccess.warehouse(c,actor,permission,warehouse);
        Sql.one(c,"SELECT id FROM dealers WHERE id=? FOR UPDATE",known.get("dealer_id"));
        var order=Sql.one(c,"SELECT * FROM orders WHERE id=? FOR UPDATE",id);
        if(order.get("warehouse_id")==null||Sql.id(order.get("warehouse_id"))!=warehouse)throw new CommandConflictException("Kho phục vụ đã thay đổi. Hãy tải lại.");
        return order;
    }
    static List<Map<String,Object>> lockedHolds(Connection c,long order)throws SQLException {
        Sql.query(c,"SELECT l.id FROM mvp_lots l WHERE l.id IN (SELECT h.lot_id FROM mvp_holds h WHERE h.order_id=?) ORDER BY l.product_id,l.received_date,l.id FOR UPDATE",order);
        return Sql.query(c,"SELECT h.*,l.on_hand,l.product_id,l.received_date FROM mvp_holds h JOIN mvp_lots l ON l.id=h.lot_id WHERE h.order_id=? ORDER BY l.product_id,l.received_date,l.id,h.id FOR UPDATE",order);
    }
    static BigDecimal remainder(Map<String,Object> hold,Instant now) {
        if(hold.get("protected_at")==null&&!Sql.instant(hold.get("expires_at")).isAfter(now))return BigDecimal.ZERO;
        return MvpCommands.decimal(hold.get("quantity")).subtract(MvpCommands.decimal(hold.get("consumed"))).subtract(MvpCommands.decimal(hold.get("released")));
    }
    public long startPicking(long actor,long order,long version,String requestKey) {
        return Sql.transaction(source,c->{var header=warehouseOrder(c,actor,order,"FULFILLMENT_WRITE");
            var command=MvpCommands.begin(c,actor,"PICKING_START",requestKey,order+":"+version);if(command.replayId()!=null)return command.replayId();
            MvpCommands.version(header,version);if(!"SUBMITTED".equals(header.get("status")))throw new IllegalArgumentException("Chỉ soạn đơn đã tiếp nhận và giữ tồn.");
            var now=clock.instant();var holds=lockedHolds(c,order);boolean available=false;
            for(var hold:holds)if(remainder(hold,now).signum()>0){available=true;if(hold.get("protected_at")==null)Sql.update(c,"UPDATE mvp_holds SET protected_at=? WHERE id=?",MvpCommands.timestamp(now),hold.get("id"));}
            if(!available)throw new CommandConflictException("Không còn giữ hàng hợp lệ. Yêu cầu NVKD giữ hàng lại trước khi soạn.");
            Sql.update(c,"UPDATE orders SET fulfillment_state='IN_PROGRESS',version=version+1 WHERE id=?",order);
            AuditService.record(c,actor,"PICKING_STARTED","ORDER",order,header,Sql.one(c,"SELECT * FROM orders WHERE id=?",order));return MvpCommands.finish(c,command,order);
        });
    }
    public long dispatch(long actor,long order,long version,String requestKey,List<DispatchLine> lines,String reason) {
        if(lines==null||lines.isEmpty()||lines.size()>100)throw new IllegalArgumentException("Chọn 1–100 dòng xuất kho.");
        var requested=new TreeMap<Long,BigDecimal>();for(var line:lines){if(line==null||line.orderLine<=0||requested.putIfAbsent(line.orderLine,MvpCommands.quantity(line.quantity))!=null)throw new IllegalArgumentException("Dòng xuất không hợp lệ hoặc bị lặp.");}
        String why=reason==null?"":reason.trim();if(why.length()>1000)throw new IllegalArgumentException("Lý do tối đa 1000 ký tự.");
        String input=order+":"+version+":"+requested+":"+why;
        return Sql.transaction(source,c->{var header=warehouseOrder(c,actor,order,"FULFILLMENT_WRITE");
            var command=MvpCommands.begin(c,actor,"SHIPMENT_DISPATCH",requestKey,input);if(command.replayId()!=null)return command.replayId();MvpCommands.version(header,version);
            if(!"SUBMITTED".equals(header.get("status"))||!"IN_PROGRESS".equals(header.get("fulfillment_state")))throw new IllegalArgumentException("Bắt đầu soạn trước khi xác nhận xuất.");
            var now=clock.instant();var sourceLines=Sql.query(c,"SELECT * FROM order_lines WHERE order_id=? ORDER BY product_id,position",order);var holds=lockedHolds(c,order);
            var valid=new HashSet<Long>();boolean shortage=false;
            for(var line:sourceLines){long id=Sql.id(line.get("id"));valid.add(id);BigDecimal available=BigDecimal.ZERO;
                for(var hold:holds)if(Sql.id(hold.get("order_line_id"))==id)available=available.add(remainder(hold,now));
                BigDecimal take=requested.getOrDefault(id,BigDecimal.ZERO);if(take.compareTo(available)>0)throw new CommandConflictException("Xuất vượt lượng giữ của dòng "+id+".");if(take.compareTo(available)<0)shortage=true;
            }
            if(!valid.containsAll(requested.keySet()))throw new SecurityException("Dòng xuất không thuộc đơn.");
            if(shortage&&why.isEmpty())throw new IllegalArgumentException("Xuất thiếu phải có lý do.");
            long shipment=Sql.insert(c,"INSERT INTO mvp_shipments(order_id,warehouse_id,shortage_reason,created_by,dispatched_at) VALUES(?,?,?,?,?)",order,header.get("warehouse_id"),why,actor,MvpCommands.timestamp(now));
            for(var line:sourceLines){long lineId=Sql.id(line.get("id"));BigDecimal remaining=requested.getOrDefault(lineId,BigDecimal.ZERO);if(remaining.signum()==0)continue;
                BigDecimal ordered=MvpCommands.decimal(line.get("base_quantity")),total=MvpCommands.decimal(line.get("net"));
                BigDecimal shipped=CreditService.sum(c,"SELECT COALESCE(SUM(quantity-returned_quantity),0) amount FROM mvp_shipment_lines WHERE order_line_id=?",lineId);
                BigDecimal allocated=CreditService.sum(c,"SELECT COALESCE(SUM(net_amount-returned_amount),0) amount FROM mvp_shipment_lines WHERE order_line_id=?",lineId);
                for(var hold:holds){if(Sql.id(hold.get("order_line_id"))!=lineId)continue;BigDecimal take=remaining.min(remainder(hold,now));if(take.signum()==0)continue;
                    if(hold.get("protected_at")==null)throw new CommandConflictException("Giữ hàng chưa được bắt đầu soạn.");
                    long lot=Sql.id(hold.get("lot_id"));BigDecimal onHand=MvpCommands.decimal(Sql.one(c,"SELECT on_hand FROM mvp_lots WHERE id=?",lot).get("on_hand"));if(onHand.compareTo(take)<0)throw new CommandConflictException("Lô không đủ tồn thực.");
                    BigDecimal cumulative=shipped.add(take);BigDecimal amount=(cumulative.compareTo(ordered)==0?total:MvpCommands.share(total,cumulative,ordered)).subtract(allocated);
                    if(amount.signum()<0||cumulative.compareTo(ordered)>0)throw new IllegalStateException("Phân bổ xuất vượt giá trị hoặc lượng đơn.");
                    long shipmentLine=Sql.insert(c,"INSERT INTO mvp_shipment_lines(shipment_id,order_line_id,hold_id,lot_id,quantity,net_amount) VALUES(?,?,?,?,?,?)",shipment,lineId,hold.get("id"),lot,take,amount);
                    Sql.update(c,"UPDATE mvp_lots SET on_hand=on_hand-? WHERE id=?",take,lot);Sql.update(c,"UPDATE mvp_holds SET consumed=consumed+? WHERE id=?",take,hold.get("id"));
                    StockService.movement(c,actor,lot,"DISPATCH","SHIPMENT_LINE",shipmentLine,take.negate(),onHand.subtract(take),now);
                    shipped=cumulative;allocated=allocated.add(amount);remaining=remaining.subtract(take);if(remaining.signum()==0)break;
                }
                if(remaining.signum()!=0)throw new CommandConflictException("Giữ hàng đã thay đổi. Hãy tải lại.");
            }
            Sql.update(c,"UPDATE orders SET version=version+1 WHERE id=?",order);
            AuditService.record(c,actor,"SHIPMENT_DISPATCHED","SHIPMENT",shipment,null,Sql.one(c,"SELECT * FROM mvp_shipments WHERE id=?",shipment));return MvpCommands.finish(c,command,shipment);
        });
    }
    public List<Map<String,Object>> orders(long actor) {return Sql.snapshot(source,c->{MvpAccess.stockOperator(c,actor,"FULFILLMENT_WRITE");return Sql.query(c,"SELECT o.id,o.version,o.fulfillment_state,o.dealer_name_snapshot,o.warehouse_name_snapshot,o.desired_delivery FROM orders o JOIN user_warehouses uw ON uw.warehouse_id=o.warehouse_id AND uw.user_id=? WHERE o.status='SUBMITTED' ORDER BY o.id DESC LIMIT 100",actor);});}
    public Map<String,Object> order(long actor,long id) {return Sql.transaction(source,c->{var header=new LinkedHashMap<>(warehouseOrder(c,actor,id,"FULFILLMENT_WRITE"));var lines=Sql.query(c,"SELECT id,sku_snapshot,name_snapshot,unit_name_snapshot,base_quantity FROM order_lines WHERE order_id=? ORDER BY position",id);
        for(var line:lines)line.put("held",CreditService.sum(c,"SELECT COALESCE(SUM(quantity-consumed-released),0) amount FROM mvp_holds WHERE order_line_id=? AND (protected_at IS NOT NULL OR expires_at>?)",line.get("id"),MvpCommands.timestamp(clock.instant())));
        header.put("lines",lines);header.put("shipments",Sql.query(c,"SELECT * FROM mvp_shipments WHERE order_id=? ORDER BY id",id));return header;});}
}
