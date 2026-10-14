package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.security.Access;

/** Totals are built only from authorized dealers; current debt never uses a sales date range. */
public final class SalesDebtReportService {
    private final DataSource source;private final Clock clock;
    public SalesDebtReportService(DataSource source){this(source,Clock.systemUTC());}
    public SalesDebtReportService(DataSource source,Clock clock){this.source=source;this.clock=clock;}
    public record Report(List<Map<String,Object>> rows,Map<String,BigDecimal> totals){}
    public record SalesReport(List<Map<String,Object>> rows,Map<String,BigDecimal> totals,List<Map<String,Object>> documents){}
    private static Access reader(Connection c,long actor)throws SQLException {
        var a=MvpAccess.internal(c,actor,"SALES_DEBT_REPORT_READ");a.require("DEALER_READ");if(Collections.disjoint(a.roles(),Set.of("SALES","SALES_MANAGER","ACCOUNTANT","DIRECTOR")))throw new SecurityException();return a;
    }
    static void add(Map<String,BigDecimal> totals,String key,BigDecimal amount){totals.merge(key,amount,BigDecimal::add);}
    private List<Map<String,Object>> scopedDealers(Connection c,long actor,Access a,Long dealer,String query)throws SQLException {
        if(dealer!=null)MvpAccess.dealer(c,actor,"SALES_DEBT_REPORT_READ",dealer,false);String q=query==null?"":query.trim();if(q.length()>150)throw new IllegalArgumentException("Tìm kiếm tối đa150 ký tự.");
        return Sql.query(c,"SELECT id,code,name,credit_limit FROM dealers WHERE (? OR primary_staff_id=?) AND (? IS NULL OR id=?) AND (code LIKE ? OR name LIKE ?) ORDER BY name,id",MvpAccess.allDealers(a),actor,dealer,dealer,"%"+q+"%","%"+q+"%");
    }
    private Map<String,Object> debtRow(Connection c,Map<String,Object> dealer)throws SQLException {
        long id=Sql.id(dealer.get("id"));var row=new LinkedHashMap<>(dealer);var credit=CreditService.snapshot(c,id,clock.instant());row.putAll(credit.display());
        BigDecimal invoiced=CreditService.sum(c,"SELECT COALESCE(SUM(net_amount),0) amount FROM mvp_invoices WHERE dealer_id=?",id);
        BigDecimal collected=CreditService.sum(c,"SELECT COALESCE(SUM(p.amount),0) amount FROM mvp_payments p WHERE p.dealer_id=? AND NOT EXISTS(SELECT 1 FROM mvp_payment_reversals r WHERE r.payment_id=p.id)",id);
        BigDecimal credited=CreditService.sum(c,"SELECT COALESCE(SUM(amount),0) amount FROM mvp_credits WHERE dealer_id=?",id);BigDecimal overdue=BigDecimal.ZERO;
        for(var invoice:Sql.query(c,"SELECT * FROM mvp_invoices WHERE dealer_id=? AND due_date<? ORDER BY due_date,id",id,MvpCommands.date(clock.instant())))overdue=overdue.add(CreditService.invoiceOutstanding(c,invoice));
        row.put("invoiced",invoiced);row.put("collected",collected);row.put("credited",credited);row.put("net_sales",invoiced.subtract(credited));row.put("overdue_amount",overdue);row.put("outstanding",credit.outstanding());row.put("credit",credit.credit());row.put("commitment",credit.commitment());return row;
    }
    public Report debt(long actor,Long dealer,String query){return Sql.snapshot(source,c->{var a=reader(c,actor);var rows=new ArrayList<Map<String,Object>>();var totals=new LinkedHashMap<String,BigDecimal>();for(String key:List.of("invoiced","collected","credited","outstanding","credit","commitment","overdue_amount"))totals.put(key,BigDecimal.ZERO);
        for(var customer:scopedDealers(c,actor,a,dealer,query)){var row=debtRow(c,customer);rows.add(row);for(String key:totals.keySet())add(totals,key,(BigDecimal)row.get(key));}return new Report(List.copyOf(rows),Collections.unmodifiableMap(totals));});}
    public Map<String,Object> dealerDebt(long actor,long id){return Sql.snapshot(source,c->{reader(c,actor);var dealer=MvpAccess.dealer(c,actor,"SALES_DEBT_REPORT_READ",id,false);var result=debtRow(c,dealer);
        var invoices=Sql.query(c,"SELECT * FROM mvp_invoices WHERE dealer_id=? ORDER BY invoice_date,id",id);for(var invoice:invoices){InvoiceService.decorate(c,invoice);invoice.put("overdue",invoice.get("due_date")!=null&&LocalDate.parse(invoice.get("due_date").toString()).isBefore(MvpCommands.date(clock.instant()))&&((BigDecimal)invoice.get("outstanding")).signum()>0);}result.put("invoices",invoices);
        result.put("payments",Sql.query(c,"SELECT p.id,p.number,p.amount,p.paid_date,r.id reversal_id,r.number reversal_number FROM mvp_payments p LEFT JOIN mvp_payment_reversals r ON r.payment_id=p.id WHERE p.dealer_id=? ORDER BY p.paid_date,p.id",id));result.put("credits",Sql.query(c,"SELECT id,return_id,number,amount,credit_date FROM mvp_credits WHERE dealer_id=? ORDER BY credit_date,id",id));return result;});}
    public SalesReport sales(long actor,LocalDate from,LocalDate to,Long dealer,String query) {
        if(from==null||to==null||from.isAfter(to)||from.getYear()<1000||to.getYear()>9999)throw new IllegalArgumentException("Khoảng ngày doanh số không hợp lệ.");
        return Sql.snapshot(source,c->{var a=reader(c,actor);var rows=new ArrayList<Map<String,Object>>();var documents=new ArrayList<Map<String,Object>>();var totals=new LinkedHashMap<String,BigDecimal>();for(String key:List.of("invoiced","credited","net_sales"))totals.put(key,BigDecimal.ZERO);
            for(var customer:scopedDealers(c,actor,a,dealer,query)){long id=Sql.id(customer.get("id"));BigDecimal invoiced=CreditService.sum(c,"SELECT COALESCE(SUM(net_amount),0) amount FROM mvp_invoices WHERE dealer_id=? AND invoice_date BETWEEN ? AND ?",id,from,to);BigDecimal credited=CreditService.sum(c,"SELECT COALESCE(SUM(amount),0) amount FROM mvp_credits WHERE dealer_id=? AND credit_date BETWEEN ? AND ?",id,from,to);
                var row=new LinkedHashMap<>(customer);row.put("invoiced",invoiced);row.put("credited",credited);row.put("net_sales",invoiced.subtract(credited));rows.add(row);add(totals,"invoiced",invoiced);add(totals,"credited",credited);add(totals,"net_sales",invoiced.subtract(credited));
                for(var invoice:Sql.query(c,"SELECT id,number,invoice_date document_date,net_amount amount FROM mvp_invoices WHERE dealer_id=? AND invoice_date BETWEEN ? AND ? ORDER BY invoice_date,id",id,from,to)){invoice.put("type","INVOICE");invoice.put("dealer_name",customer.get("name"));documents.add(invoice);}
                for(var credit:Sql.query(c,"SELECT id,return_id,number,credit_date document_date,amount FROM mvp_credits WHERE dealer_id=? AND credit_date BETWEEN ? AND ? ORDER BY credit_date,id",id,from,to)){credit.put("type","CREDIT");credit.put("dealer_name",customer.get("name"));documents.add(credit);}
            }
            documents.sort(Comparator.comparing((Map<String,Object> row)->row.get("document_date").toString()).thenComparing(row->Sql.text(row.get("type"))).thenComparingLong(row->Sql.id(row.get("id"))));return new SalesReport(List.copyOf(rows),Collections.unmodifiableMap(totals),List.copyOf(documents));
        });
    }
}
