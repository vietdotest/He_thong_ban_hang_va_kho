package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;

public final class PaymentService {
    private final DataSource source;private final Clock clock;
    public PaymentService(DataSource source){this(source,Clock.systemUTC());}
    public PaymentService(DataSource source,Clock clock){this.source=source;this.clock=clock;}
    public record Input(BigDecimal amount,LocalDate date,String method,String note){}
    public long record(long actor,long dealer,long expectedVersion,String requestKey,Input input) {
        if(input==null||input.amount==null||input.amount.signum()<=0||input.amount.stripTrailingZeros().scale()>4||input.amount.precision()-input.amount.scale()>15)throw new IllegalArgumentException("Số tiền thu phải dương, tối đa 4 chữ số thập phân.");
        BigDecimal amount=MvpCommands.money(input.amount);if(input.date==null||input.date.getYear()<1000||input.date.getYear()>9999||input.date.isAfter(MvpCommands.date(clock.instant())))throw new IllegalArgumentException("Ngày thu không hợp lệ hoặc ở tương lai.");
        if(!Set.of("CASH","BANK_TRANSFER").contains(input.method==null?"":input.method))throw new IllegalArgumentException("Chọn tiền mặt hoặc chuyển khoản.");String note=input.note==null?"":input.note.trim();if(note.length()>1000)throw new IllegalArgumentException("Ghi chú tối đa 1000 ký tự.");
        return Sql.transaction(source,c->{MvpAccess.accountant(c,actor,"PAYMENT_WRITE");var customer=MvpAccess.dealer(c,actor,"PAYMENT_WRITE",dealer,true);
            var command=MvpCommands.begin(c,actor,"PAYMENT_RECORD",requestKey,dealer+":"+expectedVersion+":"+amount+":"+input.date+":"+input.method+":"+note);if(command.replayId()!=null)return command.replayId();MvpCommands.version(customer,expectedVersion);
            var now=clock.instant();FinancialAllocations.lock(c,dealer);var credit=CreditService.snapshot(c,dealer,now);if(amount.compareTo(credit.outstanding())>0)throw new IllegalArgumentException("Thu vượt số còn phải thu "+credit.outstanding().stripTrailingZeros().toPlainString()+" đ.");
            var addresses=Sql.query(c,"SELECT address FROM dealer_addresses WHERE dealer_id=? AND status='ACTIVE' ORDER BY is_default DESC,id LIMIT 1",dealer);String address=addresses.isEmpty()?"":Sql.text(addresses.get(0).get("address"));
            String number=MvpCommands.nextNumber(c,"PT",MvpCommands.date(now));long payment=Sql.insert(c,"INSERT INTO mvp_payments(dealer_id,number,amount,paid_date,method,note,recorded_by,recorded_at,dealer_code_snapshot,dealer_name_snapshot,address_snapshot) VALUES(?,?,?,?,?,?,?,?,?,?,?)",dealer,number,amount,input.date,input.method,note,actor,MvpCommands.timestamp(now),customer.get("code"),customer.get("name"),address);
            FinancialAllocations.reallocate(c,dealer,"PAYMENT_RECORD",payment,now);AuditService.record(c,actor,"PAYMENT_RECORDED","PAYMENT",payment,null,Sql.one(c,"SELECT * FROM mvp_payments WHERE id=?",payment));return MvpCommands.finish(c,command,payment);
        });
    }
    public List<Map<String,Object>> debtors(long actor) {return Sql.snapshot(source,c->{MvpAccess.accountant(c,actor,"PAYMENT_WRITE");var result=new ArrayList<Map<String,Object>>();
        for(var dealer:Sql.query(c,"SELECT id,code,name,version FROM dealers ORDER BY name,id")){var snapshot=CreditService.snapshot(c,Sql.id(dealer.get("id")),clock.instant());if(snapshot.outstanding().signum()>0){dealer.putAll(snapshot.display());result.add(dealer);}}return result;
    });}
    public long reverse(long actor,long payment,long version,String requestKey,String reason) {
        String why=MvpCommands.text(reason,1000,"Lý do đảo toàn phiếu");
        return Sql.transaction(source,c->{MvpAccess.accountant(c,actor,"PAYMENT_WRITE");var known=Sql.one(c,"SELECT dealer_id FROM mvp_payments WHERE id=?",payment);long dealer=Sql.id(known.get("dealer_id"));MvpAccess.dealer(c,actor,"PAYMENT_WRITE",dealer,true);FinancialAllocations.lock(c,dealer);
            var original=Sql.one(c,"SELECT * FROM mvp_payments WHERE id=? FOR UPDATE",payment);var command=MvpCommands.begin(c,actor,"PAYMENT_REVERSE",requestKey,payment+":"+version+":"+why);if(command.replayId()!=null)return command.replayId();MvpCommands.version(original,version);
            if(!Sql.query(c,"SELECT id FROM mvp_payment_reversals WHERE payment_id=?",payment).isEmpty())throw new IllegalArgumentException("Phiếu thu đã được đảo toàn bộ.");
            var now=clock.instant();String number=MvpCommands.nextNumber(c,"DT",MvpCommands.date(now));long reversal=Sql.insert(c,"INSERT INTO mvp_payment_reversals(payment_id,reason,recorded_by,recorded_at,number) VALUES(?,?,?,?,?)",payment,why,actor,MvpCommands.timestamp(now),number);
            Sql.update(c,"UPDATE mvp_payments SET version=version+1 WHERE id=?",payment);FinancialAllocations.reallocate(c,dealer,"PAYMENT_REVERSE",reversal,now);
            var after=new LinkedHashMap<>(Sql.one(c,"SELECT * FROM mvp_payment_reversals WHERE id=?",reversal));after.put("original_amount",original.get("amount"));AuditService.record(c,actor,"PAYMENT_REVERSED","PAYMENT",payment,original,after);return MvpCommands.finish(c,command,reversal);
        });
    }
    public List<Map<String,Object>> payments(long actor) {return Sql.snapshot(source,c->{var a=MvpAccess.internal(c,actor,"INVOICE_READ");return Sql.query(c,"SELECT p.*,d.code dealer_code,d.name dealer_name,r.id reversal_id,u.full_name collector_name FROM mvp_payments p JOIN users u ON u.id=p.recorded_by JOIN dealers d ON d.id=p.dealer_id LEFT JOIN mvp_payment_reversals r ON r.payment_id=p.id WHERE (? OR d.primary_staff_id=?) ORDER BY p.paid_date DESC,p.id DESC LIMIT 100",MvpAccess.allDealers(a),actor);});}
    public Map<String,Object> payment(long actor,long id) {return Sql.snapshot(source,c->{MvpAccess.internal(c,actor,"INVOICE_READ");var row=new LinkedHashMap<>(Sql.one(c,"SELECT p.*,d.code dealer_code,d.name dealer_name,u.full_name collector_name FROM mvp_payments p JOIN users u ON u.id=p.recorded_by JOIN dealers d ON d.id=p.dealer_id WHERE p.id=?",id));MvpAccess.dealer(c,actor,"INVOICE_READ",Sql.id(row.get("dealer_id")),false);
        var reversal=Sql.query(c,"SELECT * FROM mvp_payment_reversals WHERE payment_id=?",id);if(!reversal.isEmpty())row.put("reversal",reversal.get(0));row.put("allocations",Sql.query(c,"SELECT a.*,i.number invoice_number FROM mvp_payment_allocations a JOIN mvp_invoices i ON i.id=a.invoice_id WHERE a.payment_id=? ORDER BY a.id",id));return row;});}
}
