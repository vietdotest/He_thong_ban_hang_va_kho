package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.CommandConflictException;

public final class InvoiceService {
    private final DataSource source;private final Clock clock;
    public InvoiceService(DataSource source){this(source,Clock.systemUTC());}
    public InvoiceService(DataSource source,Clock clock){this.source=source;this.clock=clock;}
    public long issue(long actor,long shipment,long version,String requestKey) {
        return Sql.transaction(source,c->{MvpAccess.accountant(c,actor,"INVOICE_ISSUE");var known=Sql.one(c,"SELECT o.dealer_id,s.order_id FROM mvp_shipments s JOIN orders o ON o.id=s.order_id WHERE s.id=?",shipment);
            var dealer=MvpAccess.dealer(c,actor,"INVOICE_ISSUE",Sql.id(known.get("dealer_id")),true);Sql.one(c,"SELECT id FROM orders WHERE id=? FOR UPDATE",known.get("order_id"));var delivery=Sql.one(c,"SELECT * FROM mvp_shipments WHERE id=? FOR UPDATE",shipment);
            var command=MvpCommands.begin(c,actor,"INVOICE_ISSUE",requestKey,shipment+":"+version);if(command.replayId()!=null)return command.replayId();MvpCommands.version(delivery,version);
            if(!"DELIVERED".equals(delivery.get("status")))throw new IllegalArgumentException("Chỉ phát hành hóa đơn cho giao đã xác nhận.");
            if(!Sql.query(c,"SELECT id FROM mvp_invoices WHERE shipment_id=?",shipment).isEmpty())throw new CommandConflictException("Lần giao đã có hóa đơn.");
            var lines=Sql.query(c,"SELECT sl.*,ol.product_id,ol.sku_snapshot,ol.name_snapshot,p.base_unit FROM mvp_shipment_lines sl JOIN order_lines ol ON ol.id=sl.order_line_id JOIN products p ON p.id=ol.product_id WHERE sl.shipment_id=? AND sl.received_quantity>0 ORDER BY sl.id FOR UPDATE",shipment);
            if(lines.isEmpty())throw new IllegalArgumentException("Lần giao chưa có lượng thực nhận để lập hóa đơn.");
            var now=clock.instant();long dealerId=Sql.id(dealer.get("id"));FinancialAllocations.lock(c,dealerId);BigDecimal previousCredit=CreditService.snapshot(c,dealerId,now).credit();BigDecimal total=BigDecimal.ZERO;for(var line:lines)total=total.add(MvpCommands.decimal(line.get("received_amount")));
            var date=MvpCommands.date(now);int debtDays=((Number)dealer.get("debt_days")).intValue();String number=MvpCommands.nextNumber(c,"HD",date);
            long invoice=Sql.insert(c,"INSERT INTO mvp_invoices(shipment_id,dealer_id,number,invoice_date,due_date,net_amount,issued_by,issued_at) VALUES(?,?,?,?,?,?,?,?)",shipment,dealerId,number,date,date.plusDays(debtDays),total,actor,MvpCommands.timestamp(now));
            for(var line:lines)Sql.insert(c,"INSERT INTO mvp_invoice_lines(invoice_id,shipment_line_id,order_line_id,lot_id,product_id,sku_snapshot,name_snapshot,unit_snapshot,quantity,net_amount) VALUES(?,?,?,?,?,?,?,?,?,?)",invoice,line.get("id"),line.get("order_line_id"),line.get("lot_id"),line.get("product_id"),line.get("sku_snapshot"),line.get("name_snapshot"),line.get("base_unit"),line.get("received_quantity"),line.get("received_amount"));
            FinancialAllocations.reallocate(c,dealerId,"INVOICE_ISSUE",invoice,now);BigDecimal applied=CreditService.invoiceAllocated(c,invoice);
            if(applied.compareTo(previousCredit.min(total))!=0)throw new IllegalStateException("Phân bổ dư có không khớp nguồn tiền trước hóa đơn.");
            var audit=new LinkedHashMap<>(Sql.one(c,"SELECT * FROM mvp_invoices WHERE id=?",invoice));audit.put("credit_before",previousCredit);audit.put("credit_applied",applied);AuditService.record(c,actor,"INVOICE_ISSUED","INVOICE",invoice,null,audit);return MvpCommands.finish(c,command,invoice);
        });
    }
    public List<Map<String,Object>> availableDeliveries(long actor) {return Sql.snapshot(source,c->{MvpAccess.accountant(c,actor,"INVOICE_ISSUE");return Sql.query(c,"SELECT s.id,s.version,s.order_id,o.dealer_name_snapshot,s.delivered_at,SUM(sl.received_amount) amount FROM mvp_shipments s JOIN orders o ON o.id=s.order_id JOIN mvp_shipment_lines sl ON sl.shipment_id=s.id WHERE s.status='DELIVERED' AND sl.received_quantity>0 AND NOT EXISTS(SELECT 1 FROM mvp_invoices i WHERE i.shipment_id=s.id) GROUP BY s.id,s.version,s.order_id,o.dealer_name_snapshot,s.delivered_at ORDER BY s.id DESC LIMIT 100");});}
    public List<Map<String,Object>> invoices(long actor) {return Sql.snapshot(source,c->{var access=MvpAccess.internal(c,actor,"INVOICE_READ");var rows=Sql.query(c,"SELECT i.*,d.code dealer_code,d.name dealer_name FROM mvp_invoices i JOIN dealers d ON d.id=i.dealer_id WHERE (? OR d.primary_staff_id=?) ORDER BY i.invoice_date DESC,i.id DESC LIMIT 100",MvpAccess.allDealers(access),actor);for(var row:rows)decorate(c,row);return rows;});}
    static void decorate(Connection c,Map<String,Object> row)throws SQLException {
        long id=Sql.id(row.get("id"));row.put("credited",CreditService.invoiceCredits(c,id));row.put("allocated",CreditService.invoiceAllocated(c,id));row.put("outstanding",CreditService.invoiceOutstanding(c,row));
    }
    public Map<String,Object> invoice(long actor,long id) {return Sql.snapshot(source,c->{MvpAccess.internal(c,actor,"INVOICE_READ");var row=new LinkedHashMap<>(Sql.one(c,"SELECT * FROM mvp_invoices WHERE id=?",id));MvpAccess.dealer(c,actor,"INVOICE_READ",Sql.id(row.get("dealer_id")),false);decorate(c,row);
        row.put("delivery",Sql.one(c,"SELECT s.id,s.number shipment_number,s.order_id,o.number order_number,o.dealer_name_snapshot,o.dealer_code_snapshot,o.address_snapshot,o.recipient_snapshot,o.phone_snapshot,o.warehouse_name_snapshot FROM mvp_shipments s JOIN orders o ON o.id=s.order_id WHERE s.id=?",row.get("shipment_id")));
        row.put("applied_credit_at_issue",CreditService.sum(c,"SELECT COALESCE(SUM(signed_amount),0) amount FROM mvp_payment_allocations WHERE invoice_id=? AND trigger_type='INVOICE_ISSUE' AND trigger_id=?",id,id));
        row.put("lines",Sql.query(c,"SELECT * FROM mvp_invoice_lines WHERE invoice_id=? ORDER BY id",id));var allocations=Sql.query(c,"SELECT a.*,p.number payment_number,cr.number credit_number,cr.return_id credit_return_id FROM mvp_payment_allocations a JOIN mvp_payments p ON p.id=a.payment_id LEFT JOIN mvp_credits cr ON a.trigger_type='RETURN_CREDIT' AND cr.id=a.trigger_id AND cr.dealer_id=p.dealer_id WHERE a.invoice_id=? ORDER BY a.id",id);
        for(var allocation:allocations)allocation.put("reason_label",switch(Sql.text(allocation.get("trigger_type"))){case "INVOICE_ISSUE"->"Áp dụng dư có khi phát hành hóa đơn";case "PAYMENT_RECORD"->"Thu tiền và phân bổ hóa đơn cũ trước";case "PAYMENT_REVERSE"->"Đảo phiếu thu và phân bổ lại";case "RETURN_CREDIT"->"Giảm trừ trả hàng và phân bổ lại";default->"Điều chỉnh phân bổ";});row.put("allocations",allocations);return row;});}
}
