package vn.codegym.salesinventory.service;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;

/**
 * S4-02, S7-04: Tính nợ và cam kết giao hàng để kiểm tra hạn mức trước khi nhận đơn.
 */
public final class CreditService {

    private final DataSource source;

    private final Clock clock;

    public CreditService(DataSource source) {
        this(source, Clock.systemUTC());
    }

    public CreditService(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    // Chứa số nợ, cam kết và giới hạn dùng để kiểm tra tiếp nhận đơn.
    public record CreditSnapshot(
            BigDecimal netReceivable,
            BigDecimal outstanding,
            BigDecimal credit,
            BigDecimal commitment,
            BigDecimal limit,
            BigDecimal remaining,
            boolean overdue) {

        // Chuẩn bị dữ liệu đã định dạng để hiển thị.
        public Map<String, Object> display() {
            var formatted = new LinkedHashMap<String, String>();
            formatted.put("outstanding", MoneyDisplay.format(outstanding));
            formatted.put("credit", MoneyDisplay.format(credit));
            formatted.put("commitment", MoneyDisplay.format(commitment));
            formatted.put("limit", MoneyDisplay.format(limit));
            formatted.put("remaining", MoneyDisplay.format(remaining));
            return Map.of(
                    "formatted",
                    formatted,
                    "outstanding",
                    outstanding.toPlainString(),
                    "credit",
                    credit.toPlainString(),
                    "commitment",
                    commitment.toPlainString(),
                    "limit",
                    limit.toPlainString(),
                    "remaining",
                    remaining.toPlainString(),
                    "overdue",
                    overdue);
        }
    }

    static BigDecimal sum(Connection connection, String sql, Object... parameters)
            throws SQLException {
        return MvpCommands.decimal(Sql.one(connection, sql, parameters).get("amount"));
    }

    public static BigDecimal invoiceCredits(Connection connection, long invoice)
            throws SQLException {
        return sum(
                connection,
                "SELECT COALESCE(SUM(amount),0) amount FROM mvp_credits WHERE invoice_id=?",
                invoice);
    }

    public static BigDecimal invoiceAllocated(Connection connection, long invoice)
            throws SQLException {
        return sum(
                connection,
                "SELECT COALESCE(SUM(signed_amount),0) amount FROM mvp_payment_allocations WHERE"
                        + " invoice_id=?",
                invoice);
    }

    // Tính số tiền còn phải thu của hóa đơn.
    public static BigDecimal invoiceOutstanding(Connection connection, Map<String, Object> invoice)
            throws SQLException {
        long id = Sql.id(invoice.get("id"));
        return MvpCommands.money(
                MvpCommands.decimal(invoice.get("net_amount"))
                        .subtract(invoiceCredits(connection, id))
                        .subtract(invoiceAllocated(connection, id))
                        .max(BigDecimal.ZERO));
    }

    // Lưu lại dữ liệu tại thời điểm nghiệp vụ phát sinh.
    public static CreditSnapshot snapshot(Connection connection, long dealer, Instant now)
            throws SQLException {
        var d = Sql.one(connection, "SELECT credit_limit FROM dealers WHERE id=?", dealer);
        BigDecimal invoices =
                sum(
                        connection,
                        "SELECT COALESCE(SUM(net_amount),0) amount FROM mvp_invoices WHERE"
                                + " dealer_id=?",
                        dealer);
        BigDecimal payments =
                sum(
                        connection,
                        "SELECT COALESCE(SUM(p.amount),0) amount FROM mvp_payments p WHERE"
                            + " p.dealer_id=? AND NOT EXISTS(SELECT 1 FROM mvp_payment_reversals r"
                            + " WHERE r.payment_id=p.id)",
                        dealer);
        BigDecimal credits =
                sum(
                        connection,
                        "SELECT COALESCE(SUM(amount),0) amount FROM mvp_credits WHERE dealer_id=?",
                        dealer);
        BigDecimal net = MvpCommands.money(invoices.subtract(payments).subtract(credits));
        BigDecimal holds = BigDecimal.ZERO;
        for (var row :
                Sql.query(
                        connection,
                        "SELECT l.net,l.base_quantity,SUM(h.quantity-h.consumed-h.released)"
                            + " quantity FROM mvp_holds h JOIN order_lines l ON"
                            + " l.id=h.order_line_id JOIN orders o ON o.id=h.order_id WHERE"
                            + " o.dealer_id=? AND (h.protected_at IS NOT NULL OR h.expires_at>?)"
                            + " GROUP BY l.id,l.net,l.base_quantity",
                        dealer,
                        MvpCommands.timestamp(now))) {
            var qty = MvpCommands.decimal(row.get("quantity"));
            if (qty.signum() > 0) {
                holds =
                        holds.add(
                                MvpCommands.share(
                                        MvpCommands.decimal(row.get("net")),
                                        qty,
                                        MvpCommands.decimal(row.get("base_quantity"))));
            }
        }
        BigDecimal shipped =
                sum(
                        connection,
                        "SELECT COALESCE(SUM(CASE WHEN i.id IS NULL THEN"
                            + " sl.net_amount-sl.returned_amount ELSE"
                            + " sl.net_amount-COALESCE(sl.received_amount,0)-sl.returned_amount"
                            + " END),0) amount FROM mvp_shipment_lines sl JOIN mvp_shipments s ON"
                            + " s.id=sl.shipment_id JOIN orders o ON o.id=s.order_id LEFT JOIN"
                            + " mvp_invoices i ON i.shipment_id=s.id WHERE o.dealer_id=?",
                        dealer);
        BigDecimal commitment = MvpCommands.money(holds.add(shipped));
        boolean overdue = false;
        for (var inv :
                Sql.query(
                        connection,
                        "SELECT id,net_amount FROM mvp_invoices WHERE dealer_id=? AND due_date<?",
                        dealer,
                        MvpCommands.date(now))) {
            if (invoiceOutstanding(connection, inv).signum() > 0) {
                overdue = true;
                break;
            }
        }
        BigDecimal limit = MvpCommands.money(MvpCommands.decimal(d.get("credit_limit")));
        return new CreditSnapshot(
                net,
                net.max(BigDecimal.ZERO),
                net.negate().max(BigDecimal.ZERO),
                commitment,
                limit,
                MvpCommands.money(limit.subtract(net).subtract(commitment).max(BigDecimal.ZERO)),
                overdue);
    }

    // Lưu lại dữ liệu tại thời điểm nghiệp vụ phát sinh.
    public CreditSnapshot snapshot(long actor, long dealer) {
        return Sql.snapshot(
                source,
                connection -> {
                    MvpAccess.dealer(connection, actor, "INVOICE_READ", dealer, false);
                    return snapshot(connection, dealer, clock.instant());
                });
    }
}
