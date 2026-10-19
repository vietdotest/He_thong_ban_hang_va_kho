package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.security.Access;

/**
 * S7-03, S8-06 (một phần), S8-09: Tổng hợp doanh số, lãi gộp và tuổi nợ trong phạm vi đại lý được phép xem.
 */
public final class SalesDebtReportService {

    private final DataSource source;

    private final Clock clock;

    public SalesDebtReportService(DataSource source) {
        this(source, Clock.systemUTC());
    }

    public SalesDebtReportService(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    // Chứa các dòng và tổng số liệu của báo cáo công nợ.
    public record Report(List<Map<String, Object>> rows, Map<String, BigDecimal> totals) {}

    // Chứa các dòng, tổng doanh số và chứng từ nguồn của báo cáo.
    public record SalesReport(
            List<Map<String, Object>> rows,
            Map<String, BigDecimal> totals,
            List<Map<String, Object>> documents) {}

    // Kiểm tra quyền đọc trước khi truy vấn dữ liệu.
    private static Access reader(Connection connection, long actor) throws SQLException {
        var access = MvpAccess.internal(connection, actor, "SALES_DEBT_REPORT_READ");
        access.require("DEALER_READ");
        if (Collections.disjoint(
                access.roles(), Set.of("SALES", "SALES_MANAGER", "ACCOUNTANT", "DIRECTOR"))) {
            throw new SecurityException();
        }
        return access;
    }

    static void add(Map<String, BigDecimal> totals, String key, BigDecimal amount) {
        totals.merge(key, amount, BigDecimal::add);
    }

    // Lọc đại lý theo quyền, phạm vi và từ khóa của người xem báo cáo.
    private List<Map<String, Object>> scopedDealers(
            Connection connection, long actor, Access access, Long dealer, String query)
            throws SQLException {
        if (dealer != null) {
            MvpAccess.dealer(connection, actor, "SALES_DEBT_REPORT_READ", dealer, false);
        }
        String q = query == null ? "" : query.trim();
        if (q.length() > 150) {
            throw new IllegalArgumentException("Tìm kiếm tối đa150 ký tự.");
        }
        return Sql.query(
                connection,
                "SELECT id,code,name,credit_limit FROM dealers WHERE (? OR primary_staff_id=?) AND"
                        + " (? IS NULL OR id=?) AND (code LIKE ? OR name LIKE ?) ORDER BY name,id",
                MvpAccess.allDealers(access),
                actor,
                dealer,
                dealer,
                "%" + q + "%",
                "%" + q + "%");
    }

    // Tổng hợp nợ, dư có, cam kết và tuổi nợ của một đại lý.
    private Map<String, Object> debtRow(Connection connection, Map<String, Object> dealer)
            throws SQLException {
        long id = Sql.id(dealer.get("id"));
        var row = new LinkedHashMap<>(dealer);
        var credit = CreditService.snapshot(connection, id, clock.instant());
        row.putAll(credit.display());
        BigDecimal invoiced =
                CreditService.sum(
                        connection,
                        "SELECT COALESCE(SUM(net_amount),0) amount FROM mvp_invoices WHERE"
                                + " dealer_id=?",
                        id);
        BigDecimal collected =
                CreditService.sum(
                        connection,
                        "SELECT COALESCE(SUM(p.amount),0) amount FROM mvp_payments p WHERE"
                            + " p.dealer_id=? AND NOT EXISTS(SELECT 1 FROM mvp_payment_reversals r"
                            + " WHERE r.payment_id=p.id)",
                        id);
        BigDecimal credited =
                CreditService.sum(
                        connection,
                        "SELECT COALESCE(SUM(amount),0) amount FROM mvp_credits WHERE dealer_id=?",
                        id);
        BigDecimal overdue = BigDecimal.ZERO;
        var aging = DebtAging.empty();
        var today = MvpCommands.date(clock.instant());
        for (var invoice :
                Sql.query(
                        connection,
                        "SELECT id,net_amount,due_date FROM mvp_invoices WHERE dealer_id=? ORDER BY"
                                + " due_date,id",
                        id)) {
            BigDecimal balance = CreditService.invoiceOutstanding(connection, invoice);
            String bucket =
                    DebtAging.bucket(LocalDate.parse(invoice.get("due_date").toString()), today);
            aging.merge(bucket, balance, BigDecimal::add);
            if (bucket.startsWith("days_")) {
                overdue = overdue.add(balance);
            }
        }
        row.putAll(aging);
        row.put("invoiced", invoiced);
        row.put("collected", collected);
        row.put("credited", credited);
        row.put("net_sales", invoiced.subtract(credited));
        row.put("overdue_amount", overdue);
        row.put("outstanding", credit.outstanding());
        row.put("credit", credit.credit());
        row.put("commitment", credit.commitment());
        return row;
    }

    // Tổng hợp công nợ và tuổi nợ của các đại lý được phép xem.
    public Report debt(long actor, Long dealer, String query) {
        return Sql.snapshot(
                source,
                connection -> {
                    var access = reader(connection, actor);
                    var rows = new ArrayList<Map<String, Object>>();
                    var totals = new LinkedHashMap<String, BigDecimal>();
                    for (String key :
                            List.of(
                                    "invoiced",
                                    "collected",
                                    "credited",
                                    "outstanding",
                                    "credit",
                                    "commitment",
                                    "overdue_amount")) {
                        totals.put(key, BigDecimal.ZERO);
                    }
                    totals.putAll(DebtAging.empty());
                    for (var customer : scopedDealers(connection, actor, access, dealer, query)) {
                        var row = debtRow(connection, customer);
                        rows.add(row);
                        for (String key : totals.keySet()) {
                            add(totals, key, (BigDecimal) row.get(key));
                        }
                    }
                    return new Report(List.copyOf(rows), Collections.unmodifiableMap(totals));
                });
    }

    // Xuất báo cáo công nợ trong phạm vi được phép xem ra Excel.
    public byte[] exportDebt(long actor, Long dealer, String query) {
        var report = debt(actor, dealer, query);
        var rows = new ArrayList<List<?>>();
        rows.add(
                List.of(
                        "Sổ công nợ hiện tại",
                        java.time.ZonedDateTime.now(
                                        clock.withZone(
                                                vn.codegym.salesinventory.config.VietnamTime.ZONE))
                                .toString()));
        rows.add(
                List.of(
                        "Bộ lọc đại lý",
                        dealer == null ? "Tất cả trong phạm vi" : dealer.toString(),
                        "Tìm kiếm",
                        query == null ? "" : query));
        var heading =
                new ArrayList<Object>(
                        List.of(
                                "Mã đại lý",
                                "Tên đại lý",
                                "Hóa đơn",
                                "Đã thu",
                                "Giảm trừ",
                                "Còn phải thu",
                                "Dư có",
                                "Cam kết",
                                "Quá hạn"));
        heading.addAll(DebtAging.LABELS.values());
        rows.add(heading);
        var keys =
                new ArrayList<String>(
                        List.of(
                                "invoiced",
                                "collected",
                                "credited",
                                "outstanding",
                                "credit",
                                "commitment",
                                "overdue_amount"));
        keys.addAll(DebtAging.LABELS.keySet());
        for (var row : report.rows()) {
            var cells = new ArrayList<Object>(List.of(row.get("code"), row.get("name")));
            for (String key : keys) {
                cells.add(row.get(key));
            }
            rows.add(cells);
        }
        var totals = new ArrayList<Object>(List.of("TỔNG", ""));
        for (String key : keys) {
            totals.add(report.totals().get(key));
        }
        rows.add(totals);
        return Xlsx.writeCells(rows);
    }

    // Lấy sổ công nợ và các chứng từ của một đại lý.
    public Map<String, Object> dealerDebt(long actor, long id) {
        return Sql.snapshot(
                source,
                connection -> {
                    reader(connection, actor);
                    var dealer =
                            MvpAccess.dealer(
                                    connection, actor, "SALES_DEBT_REPORT_READ", id, false);
                    var result = debtRow(connection, dealer);
                    var invoices =
                            Sql.query(
                                    connection,
                                    "SELECT * FROM mvp_invoices WHERE dealer_id=? ORDER BY"
                                            + " invoice_date,id",
                                    id);
                    for (var invoice : invoices) {
                        InvoiceService.decorate(connection, invoice);
                        String bucket =
                                DebtAging.bucket(
                                        LocalDate.parse(invoice.get("due_date").toString()),
                                        MvpCommands.date(clock.instant()));
                        invoice.put("age_key", bucket);
                        invoice.put("age_label", DebtAging.LABELS.get(bucket));
                        invoice.put(
                                "overdue",
                                invoice.get("due_date") != null
                                        && LocalDate.parse(invoice.get("due_date").toString())
                                                .isBefore(MvpCommands.date(clock.instant()))
                                        && ((BigDecimal) invoice.get("outstanding")).signum() > 0);
                    }
                    result.put("invoices", invoices);
                    result.put(
                            "payments",
                            Sql.query(
                                    connection,
                                    "SELECT p.id,p.number,p.amount,p.paid_date,r.id"
                                        + " reversal_id,r.number reversal_number FROM mvp_payments"
                                        + " p LEFT JOIN mvp_payment_reversals r ON"
                                        + " r.payment_id=p.id WHERE p.dealer_id=? ORDER BY"
                                        + " p.paid_date,p.id",
                                    id));
                    result.put(
                            "credits",
                            Sql.query(
                                    connection,
                                    "SELECT id,return_id,number,amount,credit_date FROM mvp_credits"
                                            + " WHERE dealer_id=? ORDER BY credit_date,id",
                                    id));
                    return result;
                });
    }

    // Tổng hợp doanh số và lãi gộp trong khoảng ngày được chọn.
    public SalesReport sales(long actor, LocalDate from, LocalDate to, Long dealer, String query) {
        if (from == null
                || to == null
                || from.isAfter(to)
                || from.getYear() < 1000
                || to.getYear() > 9999) {
            throw new IllegalArgumentException("Khoảng ngày doanh số không hợp lệ.");
        }
        return Sql.snapshot(
                source,
                connection -> {
                    var access = reader(connection, actor);
                    boolean costs = access.allows("COST_READ");
                    var rows = new ArrayList<Map<String, Object>>();
                    var documents = new ArrayList<Map<String, Object>>();
                    var totals = new LinkedHashMap<String, BigDecimal>();
                    for (String key : List.of("invoiced", "credited", "net_sales")) {
                        totals.put(key, BigDecimal.ZERO);
                    }
                    if (costs) {
                        for (String key :
                                List.of(
                                        "known_cost",
                                        "cost_missing_amount",
                                        "cost_missing_lines")) {
                            totals.put(key, BigDecimal.ZERO);
                        }
                    }
                    for (var customer : scopedDealers(connection, actor, access, dealer, query)) {
                        long id = Sql.id(customer.get("id"));
                        BigDecimal invoiced =
                                CreditService.sum(
                                        connection,
                                        "SELECT COALESCE(SUM(net_amount),0) amount FROM"
                                                + " mvp_invoices WHERE dealer_id=? AND invoice_date"
                                                + " BETWEEN ? AND ?",
                                        id,
                                        from,
                                        to);
                        BigDecimal credited =
                                CreditService.sum(
                                        connection,
                                        "SELECT COALESCE(SUM(amount),0) amount FROM mvp_credits"
                                            + " WHERE dealer_id=? AND credit_date BETWEEN ? AND ?",
                                        id,
                                        from,
                                        to);
                        var row = new LinkedHashMap<>(customer);
                        row.put("invoiced", invoiced);
                        row.put("credited", credited);
                        row.put("net_sales", invoiced.subtract(credited));
                        if (costs) {
                            CostSnapshots.decorate(connection, id, from, to, row);
                            for (String key :
                                    List.of(
                                            "known_cost",
                                            "cost_missing_amount",
                                            "cost_missing_lines")) {
                                add(totals, key, (BigDecimal) row.get(key));
                            }
                        }
                        rows.add(row);
                        add(totals, "invoiced", invoiced);
                        add(totals, "credited", credited);
                        add(totals, "net_sales", invoiced.subtract(credited));
                        for (var invoice :
                                Sql.query(
                                        connection,
                                        "SELECT id,number,invoice_date document_date,net_amount"
                                                + " amount FROM mvp_invoices WHERE dealer_id=? AND"
                                                + " invoice_date BETWEEN ? AND ? ORDER BY"
                                                + " invoice_date,id",
                                        id,
                                        from,
                                        to)) {
                            invoice.put("type", "INVOICE");
                            invoice.put("dealer_name", customer.get("name"));
                            documents.add(invoice);
                        }
                        for (var credit :
                                Sql.query(
                                        connection,
                                        "SELECT id,return_id,number,credit_date"
                                            + " document_date,amount FROM mvp_credits WHERE"
                                            + " dealer_id=? AND credit_date BETWEEN ? AND ? ORDER"
                                            + " BY credit_date,id",
                                        id,
                                        from,
                                        to)) {
                            credit.put("type", "CREDIT");
                            credit.put("dealer_name", customer.get("name"));
                            documents.add(credit);
                        }
                    }
                    if (costs) {
                        var overall = new LinkedHashMap<String, Object>(totals);
                        CostSnapshots.profit(overall);
                        for (String key : List.of("cost", "gross_profit", "margin_percent")) {
                            totals.put(key, (BigDecimal) overall.get(key));
                        }
                    }
                    documents.sort(
                            Comparator.comparing(
                                            (Map<String, Object> row) ->
                                                    row.get("document_date").toString())
                                    .thenComparing(row -> Sql.text(row.get("type")))
                                    .thenComparingLong(row -> Sql.id(row.get("id"))));
                    return new SalesReport(
                            List.copyOf(rows),
                            Collections.unmodifiableMap(totals),
                            List.copyOf(documents));
                });
    }
}
