package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;

/**
 * S7-01, S7-02: Phân bổ tiền và dư có vào hóa đơn cũ trước, giữ lại lịch sử đối trừ.
 */
final class FinancialAllocations {

    private FinancialAllocations() {}

    // Chứa liên kết phân bổ tiền hoặc dư có vào một hóa đơn.
    private record Link(long payment, long invoice) {}

    static void lock(Connection connection, long dealer) throws SQLException {
        Sql.query(
                connection,
                "SELECT id FROM mvp_invoices WHERE dealer_id=? ORDER BY invoice_date,id FOR UPDATE",
                dealer);
        Sql.query(
                connection,
                "SELECT id FROM mvp_payments WHERE dealer_id=? ORDER BY paid_date,id FOR UPDATE",
                dealer);
    }

    // Phân bổ lại giữ chỗ theo các lô còn khả dụng.
    static void reallocate(
            Connection connection, long dealer, String trigger, long triggerId, Instant now)
            throws SQLException {
        var invoices =
                Sql.query(
                        connection,
                        "SELECT * FROM mvp_invoices WHERE dealer_id=? ORDER BY invoice_date,id FOR"
                                + " UPDATE",
                        dealer);
        var payments =
                Sql.query(
                        connection,
                        "SELECT p.* FROM mvp_payments p WHERE p.dealer_id=? AND NOT EXISTS(SELECT 1"
                                + " FROM mvp_payment_reversals r WHERE r.payment_id=p.id) ORDER BY"
                                + " p.paid_date,p.id FOR UPDATE",
                        dealer);
        var capacity = new LinkedHashMap<Long, BigDecimal>();
        for (var invoice : invoices) {
            long id = Sql.id(invoice.get("id"));
            capacity.put(
                    id,
                    MvpCommands.money(
                            MvpCommands.decimal(invoice.get("net_amount"))
                                    .subtract(CreditService.invoiceCredits(connection, id))
                                    .max(BigDecimal.ZERO)));
        }
        var desired = new LinkedHashMap<Link, BigDecimal>();
        for (var payment : payments) {
            long id = Sql.id(payment.get("id"));
            BigDecimal remaining = MvpCommands.decimal(payment.get("amount"));
            for (var entry : capacity.entrySet()) {
                BigDecimal take = remaining.min(entry.getValue());
                if (take.signum() == 0) {
                    continue;
                }
                desired.put(new Link(id, entry.getKey()), take);
                entry.setValue(entry.getValue().subtract(take));
                remaining = remaining.subtract(take);
                if (remaining.signum() == 0) {
                    break;
                }
            }
        }
        var current = new LinkedHashMap<Link, BigDecimal>();
        for (var allocation :
                Sql.query(
                        connection,
                        "SELECT a.payment_id,a.invoice_id,SUM(a.signed_amount) amount FROM"
                            + " mvp_payment_allocations a JOIN mvp_invoices i ON i.id=a.invoice_id"
                            + " WHERE i.dealer_id=? GROUP BY a.payment_id,a.invoice_id ORDER BY"
                            + " a.payment_id,a.invoice_id",
                        dealer)) {
            current.put(
                    new Link(
                            Sql.id(allocation.get("payment_id")),
                            Sql.id(allocation.get("invoice_id"))),
                    MvpCommands.decimal(allocation.get("amount")));
        }
        var links = new LinkedHashSet<Link>(current.keySet());
        links.addAll(desired.keySet());
        for (var link : links) {
            BigDecimal delta =
                    desired.getOrDefault(link, BigDecimal.ZERO)
                            .subtract(current.getOrDefault(link, BigDecimal.ZERO));
            if (delta.signum() == 0) {
                continue;
            }
            Sql.insert(
                    connection,
                    "INSERT INTO"
                        + " mvp_payment_allocations(payment_id,invoice_id,signed_amount,trigger_type,trigger_id,occurred_at)"
                        + " VALUES(?,?,?,?,?,?)",
                    link.payment,
                    link.invoice,
                    delta,
                    trigger,
                    triggerId,
                    MvpCommands.timestamp(now));
        }
    }
}
