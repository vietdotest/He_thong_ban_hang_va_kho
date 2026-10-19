package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.validation.CommandConflictException;

/**
 * S8-01, S8-02 (MVP): Nhận trả hàng và giảm công nợ; hàng hỏng không cộng vào tồn bán được.
 */
public final class ReturnService {

    private final DataSource source;

    private final Clock clock;

    public ReturnService(DataSource source) {
        this(source, Clock.systemUTC());
    }

    public ReturnService(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    // Chứa dòng hóa đơn, số lượng và tình trạng hàng trả.
    public record Line(long invoiceLine, BigDecimal quantity, String condition) {}

    // Chứa dữ liệu đầu vào để ghi nhận trả hàng.
    public record Input(long invoice, String reason, List<Line> lines) {}

    static Map<String, Object> invoiceHeader(Connection connection, long invoice)
            throws SQLException {
        return Sql.one(
                connection,
                "SELECT i.*,s.warehouse_id,o.dealer_name_snapshot,o.address_snapshot FROM"
                    + " mvp_invoices i JOIN mvp_shipments s ON s.id=i.shipment_id JOIN orders o ON"
                    + " o.id=s.order_id WHERE i.id=?",
                invoice);
    }

    // Kiểm tra quyền ghi trước khi thay đổi dữ liệu.
    private static Access writer(Connection connection, long actor) throws SQLException {
        var access = MvpAccess.internal(connection, actor, "RETURN_WRITE");
        if (Collections.disjoint(access.roles(), Set.of("SALES", "SALES_MANAGER"))) {
            throw new SecurityException();
        }
        return access;
    }

    // Chuẩn hóa dữ liệu đầu vào trước khi kiểm tra hoặc lưu.
    private static List<Line> normalize(Input input) {
        if (input == null
                || input.invoice <= 0
                || input.lines == null
                || input.lines.isEmpty()
                || input.lines.size() > 1000) {
            throw new IllegalArgumentException("Chọn hóa đơn gốc và lượng trả.");
        }
        var result = new TreeMap<String, Line>();
        for (var line : input.lines) {
            if (line == null
                    || line.invoiceLine <= 0
                    || !Set.of("SELLABLE", "DAMAGED")
                            .contains(line.condition == null ? "" : line.condition)) {
                throw new IllegalArgumentException("Dòng hoặc tình trạng trả không hợp lệ.");
            }
            var normalized =
                    new Line(line.invoiceLine, MvpCommands.quantity(line.quantity), line.condition);
            if (result.putIfAbsent(line.invoiceLine + ":" + line.condition, normalized) != null) {
                throw new IllegalArgumentException("Dòng trả bị lặp.");
            }
        }
        return List.copyOf(result.values());
    }

    // Kiểm tra lượng trả không vượt lượng hóa đơn còn được trả.
    private static void quantityCap(
            Connection connection, long invoice, long excludedReturn, List<Line> requested)
            throws SQLException {
        var available = new HashMap<Long, BigDecimal>();
        for (var line :
                Sql.query(
                        connection,
                        "SELECT id,quantity FROM mvp_invoice_lines WHERE invoice_id=? ORDER BY id"
                                + " FOR UPDATE",
                        invoice)) {
            available.put(Sql.id(line.get("id")), MvpCommands.decimal(line.get("quantity")));
        }
        var totals = new HashMap<Long, BigDecimal>();
        for (var line : requested) {
            if (!available.containsKey(line.invoiceLine)) {
                throw new SecurityException("Dòng trả không thuộc hóa đơn gốc.");
            }
            totals.merge(line.invoiceLine, line.quantity, BigDecimal::add);
        }
        for (var entry : totals.entrySet()) {
            BigDecimal other =
                    CreditService.sum(
                            connection,
                            "SELECT COALESCE(SUM(rl.quantity),0) amount FROM mvp_return_lines rl"
                                    + " JOIN mvp_returns r ON r.id=rl.return_id WHERE"
                                    + " rl.invoice_line_id=? AND r.id<>? AND r.status<>'CANCELLED'",
                            entry.getKey(),
                            excludedReturn);
            if (other.add(entry.getValue()).compareTo(available.get(entry.getKey())) > 0) {
                throw new CommandConflictException(
                        "Tổng hàng tốt, hỏng và phiếu đang chờ vượt lượng hóa đơn chưa trả.");
            }
        }
    }

    // Lưu thông tin trả hàng và giảm trừ công nợ sau khi kiểm tra dữ liệu.
    public long save(long actor, long id, long version, String requestKey, Input input) {
        var lines = normalize(input);
        String why = MvpCommands.text(input.reason, 1000, "Lý do trả");
        return Sql.transaction(
                source,
                connection -> {
                    writer(connection, actor);
                    var known = invoiceHeader(connection, input.invoice);
                    MvpAccess.dealer(
                            connection,
                            actor,
                            "RETURN_WRITE",
                            Sql.id(known.get("dealer_id")),
                            true);
                    Sql.one(
                            connection,
                            "SELECT id FROM mvp_invoices WHERE id=? FOR UPDATE",
                            input.invoice);
                    Map<String, Object> before =
                            id == 0
                                    ? null
                                    : Sql.one(
                                            connection,
                                            "SELECT * FROM mvp_returns WHERE id=? FOR UPDATE",
                                            id);
                    if (before != null && Sql.id(before.get("invoice_id")) != input.invoice) {
                        throw new SecurityException("Không được đổi hóa đơn gốc của phiếu trả.");
                    }
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "RETURN_SAVE",
                                    requestKey,
                                    id
                                            + ":"
                                            + version
                                            + ":"
                                            + input.invoice
                                            + ":"
                                            + why
                                            + ":"
                                            + lines);
                    if (command.replayId() != null) {
                        return command.replayId();
                    }
                    if (before == null) {
                        if (version != 0) {
                            throw new CommandConflictException(
                                    "Phiếu trả mới phải có phiên bản 0.");
                        }
                    } else {
                        MvpCommands.version(before, version);
                        if (!"DRAFT".equals(before.get("status"))) {
                            throw new IllegalArgumentException("Phiếu đã nhận chỉ đọc.");
                        }
                    }
                    quantityCap(connection, input.invoice, id, lines);
                    var now = clock.instant();
                    long saved = id;
                    if (id == 0) {
                        String number =
                                MvpCommands.nextNumber(connection, "TH", MvpCommands.date(now));
                        saved =
                                Sql.insert(
                                        connection,
                                        "INSERT INTO"
                                            + " mvp_returns(invoice_id,warehouse_id,reason,created_by,created_at,number)"
                                            + " VALUES(?,?,?,?,?,?)",
                                        input.invoice,
                                        known.get("warehouse_id"),
                                        why,
                                        actor,
                                        MvpCommands.timestamp(now),
                                        number);
                    } else {
                        Sql.update(
                                connection, "DELETE FROM mvp_return_lines WHERE return_id=?", id);
                        Sql.update(
                                connection,
                                "UPDATE mvp_returns SET reason=?,version=version+1 WHERE id=?",
                                why,
                                id);
                    }
                    for (var line : lines) {
                        Sql.insert(
                                connection,
                                "INSERT INTO"
                                    + " mvp_return_lines(return_id,invoice_line_id,condition_code,quantity)"
                                    + " VALUES(?,?,?,?)",
                                saved,
                                line.invoiceLine,
                                line.condition,
                                line.quantity);
                    }
                    AuditService.record(
                            connection,
                            actor,
                            "RETURN_DRAFT_SAVED",
                            "RETURN",
                            saved,
                            before,
                            Map.of("invoice", input.invoice, "reason", why, "lines", lines));
                    return MvpCommands.finish(connection, command, saved);
                });
    }

    // Xác nhận nhận hàng trả; chỉ hàng tốt được cộng lại vào tồn bán được.
    public long receive(
            long actor, long id, long version, String requestKey, boolean physicallyConfirmed) {
        return Sql.transaction(
                source,
                connection -> {
                    var known =
                            Sql.one(
                                    connection,
                                    "SELECT r.invoice_id,r.warehouse_id,i.dealer_id FROM"
                                        + " mvp_returns r JOIN mvp_invoices i ON i.id=r.invoice_id"
                                        + " WHERE r.id=?",
                                    id);
                    MvpAccess.warehouseManager(
                            connection,
                            actor,
                            "STOCK_RETURN_RECEIVE",
                            Sql.id(known.get("warehouse_id")));
                    Sql.one(
                            connection,
                            "SELECT id FROM dealers WHERE id=? FOR UPDATE",
                            known.get("dealer_id"));
                    Sql.one(
                            connection,
                            "SELECT id FROM mvp_invoices WHERE id=? FOR UPDATE",
                            known.get("invoice_id"));
                    var header =
                            Sql.one(
                                    connection,
                                    "SELECT * FROM mvp_returns WHERE id=? FOR UPDATE",
                                    id);
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "RETURN_RECEIVE",
                                    requestKey,
                                    id + ":" + version + ":" + physicallyConfirmed);
                    if (command.replayId() != null) {
                        return command.replayId();
                    }
                    MvpCommands.version(header, version);
                    if (!"DRAFT".equals(header.get("status"))) {
                        throw new IllegalArgumentException("Phiếu không còn chờ nhận hàng.");
                    }
                    if (!physicallyConfirmed) {
                        throw new IllegalArgumentException(
                                "Xác nhận đã thực nhận đúng lượng và tình trạng. Nếu khác, NVKD cần"
                                        + " sửa phiếu nháp trước.");
                    }
                    Sql.query(
                            connection,
                            "SELECT l.id FROM mvp_lots l WHERE l.id IN (SELECT il.lot_id FROM"
                                    + " mvp_return_lines rl JOIN mvp_invoice_lines il ON"
                                    + " il.id=rl.invoice_line_id WHERE rl.return_id=?) ORDER BY"
                                    + " l.product_id,l.received_date,l.id FOR UPDATE",
                            id);
                    var lines =
                            Sql.query(
                                    connection,
                                    "SELECT rl.*,il.lot_id FROM mvp_return_lines rl JOIN"
                                        + " mvp_invoice_lines il ON il.id=rl.invoice_line_id WHERE"
                                        + " rl.return_id=? ORDER BY rl.id FOR UPDATE",
                                    id);
                    if (lines.isEmpty()) {
                        throw new IllegalArgumentException("Phiếu thiếu dòng hàng trả.");
                    }
                    var now = clock.instant();
                    for (var line : lines) {
                        if ("SELLABLE".equals(line.get("condition_code"))) {
                            long lot = Sql.id(line.get("lot_id"));
                            BigDecimal quantity = MvpCommands.decimal(line.get("quantity")),
                                    onHand =
                                            MvpCommands.decimal(
                                                    Sql.one(
                                                                    connection,
                                                                    "SELECT on_hand FROM mvp_lots"
                                                                            + " WHERE id=?",
                                                                    lot)
                                                            .get("on_hand"));
                            Sql.update(
                                    connection,
                                    "UPDATE mvp_lots SET on_hand=on_hand+? WHERE id=?",
                                    quantity,
                                    lot);
                            StockService.movement(
                                    connection,
                                    actor,
                                    lot,
                                    "COMMERCIAL_RETURN",
                                    "RETURN_LINE",
                                    Sql.id(line.get("id")),
                                    quantity,
                                    onHand.add(quantity),
                                    now);
                        }
                    }
                    Sql.update(
                            connection,
                            "UPDATE mvp_returns SET"
                                + " status='RECEIVED',version=version+1,received_by=?,received_at=?"
                                + " WHERE id=?",
                            actor,
                            MvpCommands.timestamp(now),
                            id);
                    AuditService.record(
                            connection,
                            actor,
                            "RETURN_RECEIVED",
                            "RETURN",
                            id,
                            header,
                            Sql.one(connection, "SELECT * FROM mvp_returns WHERE id=?", id));
                    return MvpCommands.finish(connection, command, id);
                });
    }

    // Hủy phiếu trả còn ở trạng thái cho phép và ghi nhận lý do hủy.
    public void cancel(long actor, long id, long version, String requestKey, String reason) {
        String why = MvpCommands.text(reason, 1000, "Lý do hủy phiếu trả");
        Sql.transaction(
                source,
                connection -> {
                    writer(connection, actor);
                    var known =
                            Sql.one(
                                    connection,
                                    "SELECT r.invoice_id,i.dealer_id FROM mvp_returns r JOIN"
                                            + " mvp_invoices i ON i.id=r.invoice_id WHERE r.id=?",
                                    id);
                    MvpAccess.dealer(
                            connection,
                            actor,
                            "RETURN_WRITE",
                            Sql.id(known.get("dealer_id")),
                            true);
                    Sql.one(
                            connection,
                            "SELECT id FROM mvp_invoices WHERE id=? FOR UPDATE",
                            known.get("invoice_id"));
                    var header =
                            Sql.one(
                                    connection,
                                    "SELECT * FROM mvp_returns WHERE id=? FOR UPDATE",
                                    id);
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "RETURN_CANCEL",
                                    requestKey,
                                    id + ":" + version + ":" + why);
                    if (command.replayId() != null) {
                        return null;
                    }
                    MvpCommands.version(header, version);
                    if (!"DRAFT".equals(header.get("status"))) {
                        throw new IllegalArgumentException("Chỉ hủy phiếu trả chưa nhận.");
                    }
                    Sql.update(
                            connection,
                            "UPDATE mvp_returns SET status='CANCELLED',version=version+1 WHERE"
                                    + " id=?",
                            id);
                    AuditService.record(
                            connection,
                            actor,
                            "RETURN_CANCELLED",
                            "RETURN",
                            id,
                            header,
                            Map.of("reason", why));
                    MvpCommands.finish(connection, command, id);
                    return null;
                });
    }

    // Lập chứng từ giảm trừ từ phiếu trả hàng đã xác nhận.
    public long issueCredit(long actor, long id, long version, String requestKey) {
        return Sql.transaction(
                source,
                connection -> {
                    MvpAccess.accountant(connection, actor, "CREDIT_ISSUE");
                    var known =
                            Sql.one(
                                    connection,
                                    "SELECT r.invoice_id,i.dealer_id FROM mvp_returns r JOIN"
                                            + " mvp_invoices i ON i.id=r.invoice_id WHERE r.id=?",
                                    id);
                    long dealer = Sql.id(known.get("dealer_id"));
                    MvpAccess.dealer(connection, actor, "CREDIT_ISSUE", dealer, true);
                    Sql.one(
                            connection,
                            "SELECT id FROM mvp_invoices WHERE id=? FOR UPDATE",
                            known.get("invoice_id"));
                    var header =
                            Sql.one(
                                    connection,
                                    "SELECT * FROM mvp_returns WHERE id=? FOR UPDATE",
                                    id);
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "RETURN_CREDIT",
                                    requestKey,
                                    id + ":" + version);
                    if (command.replayId() != null) {
                        return command.replayId();
                    }
                    MvpCommands.version(header, version);
                    if (!"RECEIVED".equals(header.get("status"))) {
                        throw new IllegalArgumentException("Kho chưa xác nhận nhận hàng trả.");
                    }
                    if (!Sql.query(connection, "SELECT id FROM mvp_credits WHERE return_id=?", id)
                            .isEmpty()) {
                        throw new CommandConflictException("Phiếu trả đã có giảm trừ.");
                    }
                    var lines =
                            Sql.query(
                                    connection,
                                    "SELECT rl.*,il.quantity invoice_quantity,il.net_amount"
                                        + " original_amount,il.cost_amount original_cost FROM"
                                        + " mvp_return_lines rl JOIN mvp_invoice_lines il ON"
                                        + " il.id=rl.invoice_line_id WHERE rl.return_id=? ORDER BY"
                                        + " rl.invoice_line_id,rl.id FOR UPDATE",
                                    id);
                    if (lines.isEmpty()) {
                        throw new IllegalArgumentException("Phiếu trả thiếu dòng nguồn.");
                    }
                    var quantities = new HashMap<Long, BigDecimal>();
                    var amounts = new HashMap<Long, BigDecimal>();
                    var allocated = new LinkedHashMap<Long, BigDecimal>();
                    var costAllocated = new LinkedHashMap<Long, BigDecimal>();
                    var costTotals = new HashMap<Long, BigDecimal>();
                    BigDecimal total = BigDecimal.ZERO;
                    for (var line : lines) {
                        long invoiceLine = Sql.id(line.get("invoice_line_id"));
                        if (!quantities.containsKey(invoiceLine)) {
                            quantities.put(
                                    invoiceLine,
                                    CreditService.sum(
                                            connection,
                                            "SELECT COALESCE(SUM(quantity),0) amount FROM"
                                                    + " mvp_credit_lines WHERE invoice_line_id=?",
                                            invoiceLine));
                            costTotals.put(
                                    invoiceLine,
                                    CreditService.sum(
                                            connection,
                                            "SELECT COALESCE(SUM(cost_amount),0) amount FROM"
                                                    + " mvp_credit_lines WHERE invoice_line_id=?",
                                            invoiceLine));
                            amounts.put(
                                    invoiceLine,
                                    CreditService.sum(
                                            connection,
                                            "SELECT COALESCE(SUM(amount),0) amount FROM"
                                                    + " mvp_credit_lines WHERE invoice_line_id=?",
                                            invoiceLine));
                        }
                        BigDecimal sourceQuantity =
                                MvpCommands.decimal(line.get("invoice_quantity"));
                        BigDecimal cumulative =
                                quantities
                                        .get(invoiceLine)
                                        .add(MvpCommands.decimal(line.get("quantity")));
                        if (cumulative.compareTo(sourceQuantity) > 0) {
                            throw new CommandConflictException("Giảm trừ vượt lượng hóa đơn gốc.");
                        }
                        BigDecimal target =
                                cumulative.compareTo(sourceQuantity) == 0
                                        ? MvpCommands.decimal(line.get("original_amount"))
                                        : MvpCommands.share(
                                                MvpCommands.decimal(line.get("original_amount")),
                                                cumulative,
                                                sourceQuantity);
                        BigDecimal amount = target.subtract(amounts.get(invoiceLine));
                        if (amount.signum() < 0) {
                            throw new IllegalStateException("Giảm trừ lũy kế không hợp lệ.");
                        }
                        BigDecimal costTarget =
                                CostSnapshots.share(
                                        line.get("original_cost"), cumulative, sourceQuantity);
                        costAllocated.put(
                                Sql.id(line.get("id")),
                                costTarget == null
                                        ? null
                                        : costTarget.subtract(costTotals.get(invoiceLine)));
                        if (costTarget != null) {
                            costTotals.put(invoiceLine, costTarget);
                        }
                        allocated.put(Sql.id(line.get("id")), amount);
                        total = total.add(amount);
                        quantities.put(invoiceLine, cumulative);
                        amounts.put(invoiceLine, target);
                    }
                    var now = clock.instant();
                    FinancialAllocations.lock(connection, dealer);
                    String number = MvpCommands.nextNumber(connection, "GT", MvpCommands.date(now));
                    long credit =
                            Sql.insert(
                                    connection,
                                    "INSERT INTO"
                                        + " mvp_credits(return_id,invoice_id,dealer_id,number,amount,issued_by,issued_at,credit_date)"
                                        + " VALUES(?,?,?,?,?,?,?,?)",
                                    id,
                                    header.get("invoice_id"),
                                    dealer,
                                    number,
                                    total,
                                    actor,
                                    MvpCommands.timestamp(now),
                                    MvpCommands.date(now));
                    for (var line : lines) {
                        Sql.insert(
                                connection,
                                "INSERT INTO"
                                    + " mvp_credit_lines(credit_id,return_line_id,invoice_line_id,quantity,amount,cost_amount)"
                                    + " VALUES(?,?,?,?,?,?)",
                                credit,
                                line.get("id"),
                                line.get("invoice_line_id"),
                                line.get("quantity"),
                                allocated.get(Sql.id(line.get("id"))),
                                costAllocated.get(Sql.id(line.get("id"))));
                    }
                    FinancialAllocations.reallocate(
                            connection, dealer, "RETURN_CREDIT", credit, now);
                    Sql.update(
                            connection, "UPDATE mvp_returns SET version=version+1 WHERE id=?", id);
                    AuditService.record(
                            connection,
                            actor,
                            "RETURN_CREDIT_ISSUED",
                            "CREDIT",
                            credit,
                            null,
                            Sql.one(connection, "SELECT * FROM mvp_credits WHERE id=?", credit));
                    return MvpCommands.finish(connection, command, credit);
                });
    }

    private static String readScope() {
        return " ((? OR d.primary_staff_id=?) OR (? AND EXISTS(SELECT 1 FROM user_warehouses uw"
                + " WHERE uw.user_id=? AND uw.warehouse_id=r.warehouse_id))) ";
    }

    private static Object[] scope(Access access, long actor) {
        return new Object[] {
            MvpAccess.allDealers(access),
            actor,
            !Collections.disjoint(access.roles(), Set.of("WAREHOUSE", "WAREHOUSE_MANAGER")),
            actor
        };
    }

    // Tìm các hóa đơn có thể chọn để lập phiếu trả hàng.
    public List<Map<String, Object>> invoicesForReturn(long actor) {
        return Sql.snapshot(
                source,
                connection -> {
                    var access = writer(connection, actor);
                    return Sql.query(
                            connection,
                            "SELECT i.id,i.number,i.invoice_date,d.code dealer_code,d.name"
                                + " dealer_name FROM mvp_invoices i JOIN dealers d ON"
                                + " d.id=i.dealer_id WHERE (? OR d.primary_staff_id=?) AND"
                                + " EXISTS(SELECT 1 FROM mvp_invoice_lines il WHERE"
                                + " il.invoice_id=i.id AND il.quantity>COALESCE((SELECT"
                                + " SUM(rl.quantity) FROM mvp_return_lines rl JOIN mvp_returns r ON"
                                + " r.id=rl.return_id WHERE rl.invoice_line_id=il.id AND"
                                + " r.status<>'CANCELLED'),0)) ORDER BY i.id DESC LIMIT 100",
                            MvpAccess.allDealers(access),
                            actor);
                });
    }

    // Lấy hóa đơn nguồn để kiểm tra điều kiện trả hàng.
    public Map<String, Object> invoiceForReturn(long actor, long invoice, long excludedReturn) {
        return Sql.snapshot(
                source,
                connection -> {
                    writer(connection, actor);
                    var sourceInvoice = invoiceHeader(connection, invoice);
                    MvpAccess.dealer(
                            connection,
                            actor,
                            "RETURN_WRITE",
                            Sql.id(sourceInvoice.get("dealer_id")),
                            false);
                    var row = new LinkedHashMap<String, Object>();
                    for (String key :
                            List.of(
                                    "id",
                                    "number",
                                    "invoice_date",
                                    "dealer_name_snapshot",
                                    "address_snapshot",
                                    "warehouse_id")) {
                        row.put(key, sourceInvoice.get(key));
                    }
                    row.put(
                            "lines",
                            Sql.query(
                                    connection,
                                    "SELECT"
                                        + " il.id,il.sku_snapshot,il.name_snapshot,il.unit_snapshot,il.quantity,il.quantity-COALESCE((SELECT"
                                        + " SUM(rl.quantity) FROM mvp_return_lines rl JOIN"
                                        + " mvp_returns r ON r.id=rl.return_id WHERE"
                                        + " rl.invoice_line_id=il.id AND r.id<>? AND"
                                        + " r.status<>'CANCELLED'),0) remaining FROM"
                                        + " mvp_invoice_lines il WHERE il.invoice_id=? ORDER BY"
                                        + " il.id",
                                    excludedReturn,
                                    invoice));
                    return row;
                });
    }

    // Tìm danh sách phiếu trả hàng theo bộ lọc.
    public List<Map<String, Object>> returns(long actor) {
        return Sql.snapshot(
                source,
                connection -> {
                    var access = MvpAccess.internal(connection, actor, "RETURN_READ");
                    return Sql.query(
                            connection,
                            "SELECT r.*,i.number invoice_number,d.name dealer_name,w.name"
                                    + " warehouse_name FROM mvp_returns r JOIN mvp_invoices i ON"
                                    + " i.id=r.invoice_id JOIN dealers d ON d.id=i.dealer_id JOIN"
                                    + " warehouses w ON w.id=r.warehouse_id WHERE "
                                    + readScope()
                                    + " ORDER BY r.id DESC LIMIT 100",
                            scope(access, actor));
                });
    }

    // Lấy chi tiết phiếu trả hàng và giảm trừ trong phạm vi được phép xem.
    public Map<String, Object> document(long actor, long id) {
        return Sql.snapshot(
                source,
                connection -> {
                    var access = MvpAccess.internal(connection, actor, "RETURN_READ");
                    var parameters = new ArrayList<Object>(Arrays.asList(scope(access, actor)));
                    parameters.add(id);
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT r.*,i.number invoice_number,o.dealer_name_snapshot"
                                        + " dealer_name,o.address_snapshot,d.primary_staff_id,w.name"
                                        + " warehouse_name FROM mvp_returns r JOIN mvp_invoices i"
                                        + " ON i.id=r.invoice_id JOIN mvp_shipments s ON"
                                        + " s.id=i.shipment_id JOIN orders o ON o.id=s.order_id"
                                        + " JOIN dealers d ON d.id=i.dealer_id JOIN warehouses w ON"
                                        + " w.id=r.warehouse_id WHERE "
                                            + readScope()
                                            + " AND r.id=?",
                                    parameters.toArray());
                    if (rows.isEmpty()) {
                        throw new SecurityException("Phiếu trả không thuộc phạm vi.");
                    }
                    var row = new LinkedHashMap<>(rows.get(0));
                    boolean businessScope =
                            MvpAccess.allDealers(access)
                                    || Sql.id(row.get("primary_staff_id")) == actor;
                    row.remove("primary_staff_id");
                    row.put(
                            "can_edit",
                            "DRAFT".equals(row.get("status"))
                                    && businessScope
                                    && access.allows("RETURN_WRITE")
                                    && !Collections.disjoint(
                                            access.roles(), Set.of("SALES", "SALES_MANAGER")));
                    row.put(
                            "can_receive",
                            "DRAFT".equals(row.get("status"))
                                    && access.allows("STOCK_RETURN_RECEIVE")
                                    && access.roles().contains("WAREHOUSE_MANAGER")
                                    && !Sql.query(
                                                    connection,
                                                    "SELECT warehouse_id FROM user_warehouses WHERE"
                                                            + " user_id=? AND warehouse_id=?",
                                                    actor,
                                                    row.get("warehouse_id"))
                                            .isEmpty());
                    row.put(
                            "lines",
                            Sql.query(
                                    connection,
                                    "SELECT"
                                        + " rl.*,il.sku_snapshot,il.name_snapshot,il.unit_snapshot,l.lot_code"
                                        + " FROM mvp_return_lines rl JOIN mvp_invoice_lines il ON"
                                        + " il.id=rl.invoice_line_id JOIN mvp_lots l ON"
                                        + " l.id=il.lot_id WHERE rl.return_id=? ORDER BY rl.id",
                                    id));
                    var credits =
                            Sql.query(
                                    connection,
                                    "SELECT id,number,amount,credit_date FROM mvp_credits WHERE"
                                            + " return_id=?",
                                    id);
                    row.put("credited", !credits.isEmpty());
                    row.put(
                            "can_issue_credit",
                            "RECEIVED".equals(row.get("status"))
                                    && credits.isEmpty()
                                    && businessScope
                                    && access.allows("CREDIT_ISSUE")
                                    && access.roles().contains("ACCOUNTANT"));
                    if (businessScope && access.allows("INVOICE_READ") && !credits.isEmpty()) {
                        row.put("credit", credits.get(0));
                    }
                    return row;
                });
    }
}
