package vn.codegym.salesinventory.service;

import java.math.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;

/** Internal accounting snapshots; never return these maps to warehouse or sales views. */
final class CostSnapshots {
    private CostSnapshots() { }
    static Map<Long,BigDecimal> capture(Connection c,long order)throws SQLException {
        var values=new HashMap<Long,BigDecimal>();
        for(var row:Sql.query(c,"SELECT p.id,p.cost_price FROM products p WHERE p.id IN (SELECT product_id FROM order_lines WHERE order_id=?) ORDER BY p.id FOR SHARE",order))values.put(Sql.id(row.get("id")),(BigDecimal)row.get("cost_price"));
        return values;
    }
    static BigDecimal amount(BigDecimal unit,BigDecimal quantity) {
        return unit==null?null:unit.multiply(quantity).setScale(4,RoundingMode.HALF_UP);
    }
    static BigDecimal share(Object total,BigDecimal quantity,BigDecimal sourceQuantity) {
        return total==null?null:MvpCommands.share((BigDecimal)total,quantity,sourceQuantity);
    }
    static void hide(List<Map<String,Object>> lines) {
        for(var line:lines){line.remove("unit_cost_snapshot");line.remove("cost_amount");}
    }
    static void decorate(Connection c,long dealer,LocalDate from,LocalDate to,Map<String,Object> row)throws SQLException {
        var invoiced=Sql.one(c,"SELECT COALESCE(SUM(il.cost_amount),0) known_cost,COALESCE(SUM(CASE WHEN il.cost_amount IS NULL THEN ABS(il.net_amount) ELSE 0 END),0) missing_amount,COALESCE(SUM(il.cost_amount IS NULL),0) missing_lines FROM mvp_invoice_lines il JOIN mvp_invoices i ON i.id=il.invoice_id WHERE i.dealer_id=? AND i.invoice_date BETWEEN ? AND ?",dealer,from,to);
        var credited=Sql.one(c,"SELECT COALESCE(SUM(cl.cost_amount),0) known_cost,COALESCE(SUM(CASE WHEN cl.cost_amount IS NULL THEN ABS(cl.amount) ELSE 0 END),0) missing_amount,COALESCE(SUM(cl.cost_amount IS NULL),0) missing_lines FROM mvp_credit_lines cl JOIN mvp_credits cr ON cr.id=cl.credit_id WHERE cr.dealer_id=? AND cr.credit_date BETWEEN ? AND ?",dealer,from,to);
        row.put("known_cost",MvpCommands.decimal(invoiced.get("known_cost")).subtract(MvpCommands.decimal(credited.get("known_cost"))));
        row.put("cost_missing_amount",MvpCommands.decimal(invoiced.get("missing_amount")).add(MvpCommands.decimal(credited.get("missing_amount"))));
        row.put("cost_missing_lines",MvpCommands.decimal(invoiced.get("missing_lines")).add(MvpCommands.decimal(credited.get("missing_lines"))));
        profit(row);
    }
    static void profit(Map<String,Object> row) {
        boolean complete=MvpCommands.decimal(row.get("cost_missing_lines")).signum()==0;
        BigDecimal net=MvpCommands.decimal(row.get("net_sales"));
        BigDecimal profit=complete?net.subtract(MvpCommands.decimal(row.get("known_cost"))):null;
        row.put("cost_complete",complete);row.put("cost",complete?row.get("known_cost"):null);row.put("gross_profit",profit);
        row.put("margin_percent",!complete||net.signum()==0?null:profit.multiply(new BigDecimal("100")).divide(net,2,RoundingMode.HALF_UP));
    }
}
