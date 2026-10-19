package vn.codegym.salesinventory.service;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.CommandConflictException;

/**
 * S4-03, S5-04, S5-05, S5-06, S6-01 (MVP): Nhập và giữ tồn theo lô nhập trước; bỏ giữ chỗ hết hạn khi tính tồn khả dụng.
 */
public final class StockService {

    private final DataSource source;

    private final Clock clock;

    public StockService(DataSource source) {
        this(source, Clock.systemUTC());
    }

    public StockService(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    // Chứa sản phẩm, đơn vị, lượng nhập và thông tin lô.
    public record ReceiptLine(
            long product, long unit, BigDecimal quantity, String lot, LocalDate expiry) {}

    // Chứa thông tin đầu phiếu và các dòng nhập kho.
    public record ReceiptInput(
            long warehouse,
            long supplier,
            String reference,
            LocalDate date,
            List<ReceiptLine> lines) {}

    // Chứa tồn thực tế, lượng giữ chỗ và tồn khả dụng tại thời điểm tính.
    public record StockSnapshot(BigDecimal onHand, BigDecimal reserved, BigDecimal available) {

        public Map<String, Object> display() {
            return Map.of(
                    "onHand",
                    onHand.toPlainString(),
                    "reserved",
                    reserved.toPlainString(),
                    "available",
                    available.toPlainString());
        }
    }

    static BigDecimal activeHeld(Connection connection, long lot, Instant now) throws SQLException {
        return CreditService.sum(
                connection,
                "SELECT COALESCE(SUM(quantity-consumed-released),0) amount FROM mvp_holds WHERE"
                        + " lot_id=? AND (protected_at IS NOT NULL OR expires_at>?)",
                lot,
                MvpCommands.timestamp(now));
    }

    // Tính tồn thực tế, giữ chỗ còn hiệu lực và tồn khả dụng.
    public static StockSnapshot snapshot(
            Connection connection, long warehouse, long product, Instant now) throws SQLException {
        BigDecimal on = BigDecimal.ZERO, held = BigDecimal.ZERO;
        for (var lot :
                Sql.query(
                        connection,
                        "SELECT id,on_hand FROM mvp_lots WHERE warehouse_id=? AND product_id=?"
                                + " ORDER BY received_date,id",
                        warehouse,
                        product)) {
            on = on.add(MvpCommands.decimal(lot.get("on_hand")));
            held = held.add(activeHeld(connection, Sql.id(lot.get("id")), now));
        }
        return new StockSnapshot(on, held, on.subtract(held));
    }

    // Kiểm tra và lưu phiếu nhập nháp cùng các dòng hàng.
    public long saveReceipt(long actor, long id, long version, String key, ReceiptInput input) {
        if (input == null
                || input.date == null
                || input.date.getYear() < 1000
                || input.date.getYear() > 9999
                || input.lines == null
                || input.lines.isEmpty()
                || input.lines.size() > 100) {
            throw new IllegalArgumentException("Phiếu nhập cần ngày hợp lệ và 1–100 dòng.");
        }
        if (input.date.isAfter(MvpCommands.date(clock.instant()))) {
            throw new IllegalArgumentException("Ngày nhập không được ở tương lai.");
        }
        String reference = MvpCommands.text(input.reference, 100, "Số chứng từ");
        var clean = new ArrayList<ReceiptLine>();
        for (var l : input.lines) {
            if (l == null
                    || l.product <= 0
                    || l.unit <= 0
                    || l.quantity == null
                    || l.quantity.stripTrailingZeros().scale() > 6) {
                throw new IllegalArgumentException("Dòng nhập không hợp lệ.");
            }
            clean.add(
                    new ReceiptLine(
                            l.product,
                            l.unit,
                            MvpCommands.quantity(l.quantity),
                            MvpCommands.text(l.lot, 100, "Số lô"),
                            l.expiry));
        }
        return Sql.transaction(
                source,
                connection -> {
                    MvpAccess.warehouse(connection, actor, "STOCK_RECEIPT_WRITE", input.warehouse);
                    if (id > 0) {
                        var old = Sql.one(connection, "SELECT * FROM mvp_receipts WHERE id=?", id);
                        if (Sql.id(old.get("warehouse_id")) != input.warehouse) {
                            throw new SecurityException();
                        }
                    }
                    var ticket =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "RECEIPT_SAVE",
                                    key,
                                    List.of(
                                                    id,
                                                    version,
                                                    input.warehouse,
                                                    input.supplier,
                                                    reference,
                                                    input.date,
                                                    clean)
                                            .toString());
                    if (ticket.replayId() != null) {
                        return ticket.replayId();
                    }
                    var supplier =
                            Sql.one(
                                    connection,
                                    "SELECT status,warehouse_id FROM suppliers WHERE id=? FOR"
                                            + " SHARE",
                                    input.supplier);
                    if (Sql.id(supplier.get("warehouse_id")) != input.warehouse) {
                        throw new SecurityException("Nhà cung cấp không thuộc kho nhập được giao.");
                    }
                    if (!"ACTIVE".equals(supplier.get("status"))) {
                        throw new IllegalArgumentException("Nhà cung cấp đã ngừng giao dịch.");
                    }
                    long saved = id;
                    if (id == 0) {
                        saved =
                                Sql.insert(
                                        connection,
                                        "INSERT INTO"
                                            + " mvp_receipts(warehouse_id,supplier_id,document_ref,receipt_date,created_by)"
                                            + " VALUES(?,?,?,?,?)",
                                        input.warehouse,
                                        input.supplier,
                                        reference,
                                        input.date,
                                        actor);
                    } else {
                        var old =
                                Sql.one(
                                        connection,
                                        "SELECT * FROM mvp_receipts WHERE id=? FOR UPDATE",
                                        id);
                        MvpCommands.version(old, version);
                        if (!"DRAFT".equals(old.get("status"))) {
                            throw new CommandConflictException("Phiếu đã xác nhận chỉ được xem.");
                        }
                        Sql.update(
                                connection,
                                "UPDATE mvp_receipts SET"
                                    + " supplier_id=?,document_ref=?,receipt_date=?,version=version+1"
                                    + " WHERE id=?",
                                input.supplier,
                                reference,
                                input.date,
                                id);
                        Sql.update(
                                connection, "DELETE FROM mvp_receipt_lines WHERE receipt_id=?", id);
                    }
                    int position = 0;
                    for (var l : clean) {
                        var conversion =
                                UnitService.orderConversion(
                                        connection, l.product, l.unit, l.quantity, input.warehouse);
                        Sql.insert(
                                connection,
                                "INSERT INTO"
                                    + " mvp_receipt_lines(receipt_id,position,product_id,unit_id,quantity,unit_name_snapshot,factor,unit_version,base_quantity,lot_code,expires_on)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                saved,
                                position++,
                                l.product,
                                l.unit,
                                l.quantity,
                                conversion.unitName(),
                                conversion.factor(),
                                conversion.version(),
                                conversion.baseQuantity(),
                                l.lot,
                                l.expiry);
                    }
                    AuditService.record(
                            connection,
                            actor,
                            "STOCK_RECEIPT_SAVED",
                            "STOCK_RECEIPT",
                            saved,
                            null,
                            Map.of("warehouse", input.warehouse, "lines", clean.size()));
                    return MvpCommands.finish(connection, ticket, saved);
                });
    }

    // Xác nhận phiếu nhập, tăng tồn theo lô và ghi sổ trong cùng giao dịch.
    public long confirmReceipt(long actor, long id, long version, String key) {
        return Sql.transaction(
                source,
                connection -> {
                    var known =
                            Sql.one(
                                    connection,
                                    "SELECT warehouse_id FROM mvp_receipts WHERE id=?",
                                    id);
                    MvpAccess.warehouse(
                            connection,
                            actor,
                            "STOCK_RECEIPT_WRITE",
                            Sql.id(known.get("warehouse_id")));
                    var ticket =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "RECEIPT_CONFIRM",
                                    key,
                                    List.of(id, version).toString());
                    if (ticket.replayId() != null) {
                        return ticket.replayId();
                    }
                    var row =
                            Sql.one(
                                    connection,
                                    "SELECT * FROM mvp_receipts WHERE id=? FOR UPDATE",
                                    id);
                    MvpCommands.version(row, version);
                    if (!"DRAFT".equals(row.get("status"))) {
                        throw new CommandConflictException("Phiếu đã xác nhận.");
                    }
                    var supplier =
                            Sql.one(
                                    connection,
                                    "SELECT name,status,warehouse_id FROM suppliers WHERE id=? FOR"
                                            + " SHARE",
                                    row.get("supplier_id"));
                    if (Sql.id(supplier.get("warehouse_id")) != Sql.id(row.get("warehouse_id"))) {
                        throw new SecurityException(
                                "Nhà cung cấp đã chuyển sang kho khác; không xác nhận phiếu cũ.");
                    }
                    if (!"ACTIVE".equals(supplier.get("status"))) {
                        throw new IllegalArgumentException("Nhà cung cấp đã ngừng giao dịch.");
                    }
                    var warehouse =
                            Sql.one(
                                    connection,
                                    "SELECT name,address FROM warehouses WHERE id=? FOR SHARE",
                                    row.get("warehouse_id"));
                    var lines =
                            Sql.query(
                                    connection,
                                    "SELECT * FROM mvp_receipt_lines WHERE receipt_id=? ORDER BY"
                                            + " product_id,position",
                                    id);
                    if (lines.isEmpty()) {
                        throw new IllegalArgumentException("Phiếu nhập không có dòng hàng.");
                    }
                    for (var line : lines) {
                        long lineId = Sql.id(line.get("id"));
                        BigDecimal qty = MvpCommands.decimal(line.get("base_quantity"));
                        long lot =
                                Sql.insert(
                                        connection,
                                        "INSERT INTO"
                                            + " mvp_lots(warehouse_id,product_id,receipt_line_id,lot_code,received_date,expires_on,on_hand)"
                                            + " VALUES(?,?,?,?,?,?,?)",
                                        row.get("warehouse_id"),
                                        line.get("product_id"),
                                        lineId,
                                        line.get("lot_code"),
                                        row.get("receipt_date"),
                                        line.get("expires_on"),
                                        qty);
                        movement(
                                connection,
                                actor,
                                lot,
                                "RECEIPT",
                                "RECEIPT_LINE",
                                lineId,
                                qty,
                                qty,
                                clock.instant());
                        UnitService.snapshot(
                                connection,
                                "RECEIPT:" + lineId,
                                new UnitService.Conversion(
                                        Sql.id(line.get("product_id")),
                                        Sql.id(line.get("unit_id")),
                                        Sql.text(line.get("unit_name_snapshot")),
                                        MvpCommands.decimal(line.get("quantity")),
                                        MvpCommands.decimal(line.get("factor")),
                                        Sql.id(line.get("unit_version")),
                                        qty,
                                        Sql.id(row.get("warehouse_id"))));
                        Sql.update(
                                connection,
                                "INSERT IGNORE INTO"
                                    + " product_transaction_references(product_id,reference_type,reference_id)"
                                    + " VALUES(?,'RECEIPT',?)",
                                line.get("product_id"),
                                Long.toString(id));
                        var product =
                                Sql.one(
                                        connection,
                                        "SELECT sku,name FROM products WHERE id=? FOR SHARE",
                                        line.get("product_id"));
                        Sql.update(
                                connection,
                                "UPDATE mvp_receipt_lines SET sku_snapshot=?,name_snapshot=? WHERE"
                                        + " id=?",
                                product.get("sku"),
                                product.get("name"),
                                lineId);
                    }
                    String number =
                            MvpCommands.nextNumber(
                                    connection, "PN", MvpCommands.date(clock.instant()));
                    Sql.update(
                            connection,
                            "UPDATE mvp_receipts SET"
                                + " status='CONFIRMED',version=version+1,confirmed_by=?,confirmed_at=?,number=?,warehouse_name_snapshot=?,warehouse_address_snapshot=?,supplier_name_snapshot=?"
                                + " WHERE id=?",
                            actor,
                            MvpCommands.timestamp(clock.instant()),
                            number,
                            warehouse.get("name"),
                            Sql.text(warehouse.get("address")),
                            supplier.get("name"),
                            id);
                    Sql.update(
                            connection,
                            "INSERT IGNORE INTO"
                                    + " supplier_receipt_references(supplier_id,receipt_reference)"
                                    + " VALUES(?,?)",
                            row.get("supplier_id"),
                            "RECEIPT:" + id);
                    AuditService.record(
                            connection,
                            actor,
                            "STOCK_RECEIPT_CONFIRMED",
                            "STOCK_RECEIPT",
                            id,
                            row,
                            Map.of("status", "CONFIRMED", "warehouse", row.get("warehouse_id")));
                    return MvpCommands.finish(connection, ticket, id);
                });
    }

    static void movement(
            Connection connection,
            long actor,
            long lot,
            String type,
            String sourceType,
            long sourceId,
            BigDecimal delta,
            BigDecimal after,
            Instant now)
            throws SQLException {
        Sql.insert(
                connection,
                "INSERT INTO"
                    + " mvp_stock_events(lot_id,event_type,source_type,source_id,quantity_delta,on_hand_after,actor_id,occurred_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                lot,
                type,
                sourceType,
                sourceId,
                delta,
                after,
                actor,
                MvpCommands.timestamp(now));
    }

    // Giữ tồn theo FIFO; bên gọi đã khóa đại lý, đơn và kiểm hạn mức, quyền tiếp nhận.
    public static void reserve(Connection connection, long actor, long order, Instant now)
            throws SQLException {
        var header =
                Sql.one(connection, "SELECT warehouse_id FROM orders WHERE id=? FOR UPDATE", order);
        if (header.get("warehouse_id") == null) {
            throw new IllegalArgumentException("Đơn chưa có kho phục vụ.");
        }
        long warehouse = Sql.id(header.get("warehouse_id"));
        var lines =
                Sql.query(
                        connection,
                        "SELECT id,product_id,base_quantity FROM order_lines WHERE order_id=? ORDER"
                                + " BY product_id,position",
                        order);
        var needed = new LinkedHashMap<Long, BigDecimal>();
        var remaining = new HashMap<Long, BigDecimal>();
        for (var line : lines) {
            long id = Sql.id(line.get("id"));
            BigDecimal shipped =
                    CreditService.sum(
                            connection,
                            "SELECT COALESCE(SUM(quantity-returned_quantity),0) amount FROM"
                                    + " mvp_shipment_lines WHERE order_line_id=?",
                            id);
            BigDecimal held =
                    CreditService.sum(
                            connection,
                            "SELECT COALESCE(SUM(quantity-consumed-released),0) amount FROM"
                                + " mvp_holds WHERE order_line_id=? AND (protected_at IS NOT NULL"
                                + " OR expires_at>?)",
                            id,
                            MvpCommands.timestamp(now));
            BigDecimal qty =
                    MvpCommands.decimal(line.get("base_quantity")).subtract(shipped).subtract(held);
            if (qty.signum() < 0) {
                throw new IllegalStateException("Số lượng xử lý vượt đơn.");
            }
            remaining.put(id, qty);
            if (qty.signum() > 0) {
                needed.merge(Sql.id(line.get("product_id")), qty, BigDecimal::add);
            }
        }
        var lotLists = new HashMap<Long, List<Map<String, Object>>>();
        var lotAvailable = new HashMap<Long, BigDecimal>();
        for (var entry : needed.entrySet()) {
            var lots =
                    Sql.query(
                            connection,
                            "SELECT id,on_hand FROM mvp_lots WHERE warehouse_id=? AND product_id=?"
                                    + " ORDER BY received_date,id FOR UPDATE",
                            warehouse,
                            entry.getKey());
            BigDecimal total = BigDecimal.ZERO;
            for (var lot : lots) {
                long id = Sql.id(lot.get("id"));
                BigDecimal free =
                        MvpCommands.decimal(lot.get("on_hand"))
                                .subtract(activeHeld(connection, id, now));
                lotAvailable.put(id, free);
                total = total.add(free);
            }
            if (total.compareTo(entry.getValue()) < 0) {
                throw new CommandConflictException(
                        "Không đủ tồn khả dụng cho SKU "
                                + entry.getKey()
                                + ". Tối đa còn "
                                + total.stripTrailingZeros().toPlainString()
                                + " đơn vị cơ sở.");
            }
            lotLists.put(entry.getKey(), lots);
        }
        for (var line : lines) {
            long lineId = Sql.id(line.get("id"));
            BigDecimal qty = remaining.get(lineId);
            if (qty.signum() == 0) {
                continue;
            }
            for (var lot : lotLists.get(Sql.id(line.get("product_id")))) {
                long lotId = Sql.id(lot.get("id"));
                BigDecimal take = qty.min(lotAvailable.get(lotId));
                if (take.signum() <= 0) {
                    continue;
                }
                Sql.insert(
                        connection,
                        "INSERT INTO"
                            + " mvp_holds(order_id,order_line_id,lot_id,quantity,expires_at,created_at)"
                            + " VALUES(?,?,?,?,?,?)",
                        order,
                        lineId,
                        lotId,
                        take,
                        MvpCommands.timestamp(now.plus(Duration.ofHours(24))),
                        MvpCommands.timestamp(now));
                lotAvailable.put(lotId, lotAvailable.get(lotId).subtract(take));
                qty = qty.subtract(take);
                if (qty.signum() == 0) {
                    break;
                }
            }
        }
        Sql.update(
                connection,
                "UPDATE orders SET fulfillment_state='RESERVED' WHERE id=? AND fulfillment_state IN"
                        + " ('NONE','RESERVED','EXPIRED')",
                order);
        AuditService.record(
                connection,
                actor,
                "STOCK_RESERVED",
                "ORDER",
                order,
                null,
                Map.of("expires_at", now.plus(Duration.ofHours(24)).toString()));
    }

    public List<Map<String, Object>> stock(long actor, Long warehouse, String query) {
        return stock(actor, warehouse, query, null, "");
    }

    // Lấy số tồn thực tế, giữ chỗ và khả dụng của sản phẩm.
    public List<Map<String, Object>> stock(
            long actor, Long warehouse, String query, Long category, String state) {
        if (!Set.of("", "AVAILABLE", "UNAVAILABLE").contains(state == null ? "" : state)
                || category != null && category <= 0) {
            throw new IllegalArgumentException("Bộ lọc tồn không hợp lệ.");
        }
        String q = query == null ? "" : query.trim();
        if (q.length() > 150) {
            throw new IllegalArgumentException("Tìm kiếm tối đa 150 ký tự.");
        }
        return Sql.snapshot(
                source,
                connection -> {
                    var access = MvpAccess.internal(connection, actor, "STOCK_READ");
                    if (Collections.disjoint(
                            access.roles(),
                            Set.of(
                                    "WAREHOUSE",
                                    "WAREHOUSE_MANAGER",
                                    "SALES",
                                    "SALES_MANAGER",
                                    "DIRECTOR",
                                    "ACCOUNTANT"))) {
                        throw new SecurityException();
                    }
                    if (warehouse != null
                            && !MvpAccess.canReadWarehouse(connection, actor, access, warehouse)) {
                        throw new SecurityException("Kho không thuộc phạm vi được xem.");
                    }
                    var result = new ArrayList<Map<String, Object>>();
                    var categories = new HashSet<Long>();
                    if (category != null) {
                        for (var item :
                                Sql.query(
                                        connection,
                                        "WITH RECURSIVE branch AS (SELECT id FROM categories WHERE"
                                            + " id=? UNION ALL SELECT c.id FROM categories c JOIN"
                                            + " branch b ON c.parent_id=b.id) SELECT id FROM"
                                            + " branch",
                                        category)) {
                            categories.add(Sql.id(item.get("id")));
                        }
                    }
                    var instant = clock.instant();
                    for (var row :
                            Sql.query(
                                    connection,
                                    "SELECT DISTINCT"
                                        + " p.category_id,l.warehouse_id,l.product_id,w.code"
                                        + " warehouse_code,w.name"
                                        + " warehouse_name,w.address,p.sku,p.name,p.base_unit,p.status"
                                        + " FROM mvp_lots l JOIN warehouses w ON"
                                        + " w.id=l.warehouse_id JOIN products p ON"
                                        + " p.id=l.product_id WHERE (? IS NULL OR l.warehouse_id=?)"
                                        + " AND (p.sku LIKE ? OR p.name LIKE ?) ORDER BY"
                                        + " w.name,p.sku",
                                    warehouse,
                                    warehouse,
                                    "%" + q + "%",
                                    "%" + q + "%")) {
                        if (category != null
                                && !categories.contains(Sql.id(row.get("category_id")))) {
                            continue;
                        }
                        long w = Sql.id(row.get("warehouse_id"));
                        if (!MvpAccess.canReadWarehouse(connection, actor, access, w)) {
                            continue;
                        }
                        boolean warehouseReader =
                                !Collections.disjoint(
                                                access.roles(),
                                                Set.of("WAREHOUSE", "WAREHOUSE_MANAGER"))
                                        && !Sql.query(
                                                        connection,
                                                        "SELECT warehouse_id FROM user_warehouses"
                                                            + " WHERE user_id=? AND warehouse_id=?",
                                                        actor,
                                                        w)
                                                .isEmpty();
                        boolean physical =
                                warehouseReader
                                        || !Collections.disjoint(
                                                access.roles(),
                                                Set.of("SALES_MANAGER", "DIRECTOR", "ACCOUNTANT"));
                        if (!physical && !"ACTIVE".equals(row.get("status"))) {
                            continue;
                        }
                        var values = new LinkedHashMap<>(row);
                        var balance =
                                snapshot(connection, w, Sql.id(row.get("product_id")), instant);
                        if ("AVAILABLE".equals(state) && balance.available().signum() <= 0
                                || "UNAVAILABLE".equals(state)
                                        && balance.available().signum() > 0) {
                            continue;
                        }
                        values.putAll(balance.display());
                        if (!physical) {
                            values.remove("onHand");
                            values.remove("reserved");
                        }
                        values.put("physical", physical);
                        values.put("can_card", warehouseReader);
                        result.add(values);
                    }
                    return result;
                });
    }

    // Ghi giải phóng giữ hàng hết hạn; tồn khả dụng đã loại các giữ chỗ này khi tính.
    public int expireHolds() {
        var now = clock.instant();
        var candidates =
                Sql.snapshot(
                        source,
                        connection ->
                                Sql.query(
                                        connection,
                                        "SELECT DISTINCT order_id FROM mvp_holds WHERE protected_at"
                                                + " IS NULL AND expires_at<=? AND"
                                                + " consumed+released<quantity ORDER BY order_id",
                                        MvpCommands.timestamp(now)));
        int released = 0;
        for (var candidate : candidates) {
            long order = Sql.id(candidate.get("order_id"));
            released +=
                    Sql.transaction(
                            source,
                            c -> {
                                long dealer =
                                        Sql.id(
                                                Sql.one(
                                                                c,
                                                                "SELECT dealer_id FROM orders WHERE"
                                                                        + " id=?",
                                                                order)
                                                        .get("dealer_id"));
                                Sql.one(c, "SELECT id FROM dealers WHERE id=? FOR UPDATE", dealer);
                                var header =
                                        Sql.one(
                                                c,
                                                "SELECT * FROM orders WHERE id=? FOR UPDATE",
                                                order);
                                Sql.query(
                                        c,
                                        "SELECT l.id FROM mvp_lots l WHERE l.id IN (SELECT h.lot_id"
                                                + " FROM mvp_holds h WHERE h.order_id=?) ORDER BY"
                                                + " l.product_id,l.received_date,l.id FOR UPDATE",
                                        order);
                                var holds =
                                        Sql.query(
                                                c,
                                                "SELECT * FROM mvp_holds WHERE order_id=? ORDER BY"
                                                        + " id FOR UPDATE",
                                                order);
                                int count = 0;
                                for (var hold : holds) {
                                    if (hold.get("protected_at") == null
                                            && !Sql.instant(hold.get("expires_at")).isAfter(now)
                                            && MvpCommands.decimal(hold.get("quantity"))
                                                            .compareTo(
                                                                    MvpCommands.decimal(
                                                                                    hold.get(
                                                                                            "consumed"))
                                                                            .add(
                                                                                    MvpCommands
                                                                                            .decimal(
                                                                                                    hold
                                                                                                            .get(
                                                                                                                    "released"))))
                                                    > 0) {
                                        Sql.update(
                                                c,
                                                "UPDATE mvp_holds SET released=quantity-consumed"
                                                        + " WHERE id=?",
                                                hold.get("id"));
                                        count++;
                                    }
                                }
                                if (count > 0) {
                                    BigDecimal active =
                                            CreditService.sum(
                                                    c,
                                                    "SELECT"
                                                        + " COALESCE(SUM(quantity-consumed-released),0)"
                                                        + " amount FROM mvp_holds WHERE order_id=?"
                                                        + " AND (protected_at IS NOT NULL OR"
                                                        + " expires_at>?)",
                                                    order,
                                                    MvpCommands.timestamp(now));
                                    Sql.update(
                                            c,
                                            "UPDATE orders SET"
                                                + " version=version+1,fulfillment_state=CASE WHEN"
                                                + " ?=0 THEN 'EXPIRED' ELSE fulfillment_state END"
                                                + " WHERE id=?",
                                            active,
                                            order);
                                    AuditService.recordSystem(
                                            c,
                                            "STOCK_HOLDS_EXPIRED",
                                            "ORDER",
                                            order,
                                            header,
                                            Sql.one(c, "SELECT * FROM orders WHERE id=?", order));
                                }
                                return count;
                            });
        }
        return released;
    }

    // Lấy danh sách kho trong phạm vi được phép thao tác.
    public List<Map<String, Object>> warehouses(long actor, String permission) {
        return Sql.snapshot(
                source,
                connection -> {
                    if ("STOCK_RECEIPT_WRITE".equals(permission)) {
                        MvpAccess.stockOperator(connection, actor, permission);
                        return Sql.query(
                                connection,
                                "SELECT w.id,w.code,w.name,w.address FROM warehouses w JOIN"
                                        + " user_warehouses uw ON uw.warehouse_id=w.id WHERE"
                                        + " uw.user_id=? ORDER BY w.name,w.id",
                                actor);
                    }
                    var access = MvpAccess.internal(connection, actor, permission);
                    var result = new ArrayList<Map<String, Object>>();
                    for (var w :
                            Sql.query(
                                    connection,
                                    "SELECT id,code,name,address FROM warehouses ORDER BY"
                                            + " name,id")) {
                        if (MvpAccess.canReadWarehouse(
                                connection, actor, access, Sql.id(w.get("id")))) {
                            result.add(w);
                        }
                    }
                    return result;
                });
    }

    // Tìm các phiếu nhập theo bộ lọc và phân trang.
    public List<Map<String, Object>> receipts(long actor) {
        return Sql.snapshot(
                source,
                connection -> {
                    MvpAccess.stockOperator(connection, actor, "STOCK_READ");
                    return Sql.query(
                            connection,
                            "SELECT r.*,COALESCE(r.warehouse_name_snapshot,w.name)"
                                + " warehouse_name,COALESCE(r.supplier_name_snapshot,s.name)"
                                + " supplier_name FROM mvp_receipts r JOIN warehouses w ON"
                                + " w.id=r.warehouse_id JOIN suppliers s ON s.id=r.supplier_id JOIN"
                                + " user_warehouses uw ON uw.warehouse_id=r.warehouse_id AND"
                                + " uw.user_id=? ORDER BY r.id DESC LIMIT 100",
                            actor);
                });
    }

    // Lấy thông tin phiếu nhập để xem chi tiết.
    public Map<String, Object> receipt(long actor, long id) {
        return Sql.snapshot(
                source,
                connection -> {
                    var row =
                            new LinkedHashMap<>(
                                    Sql.one(
                                            connection,
                                            "SELECT r.*,COALESCE(r.warehouse_name_snapshot,w.name)"
                                                + " warehouse_name,COALESCE(r.warehouse_address_snapshot,w.address)"
                                                + " warehouse_address,COALESCE(r.supplier_name_snapshot,s.name)"
                                                + " supplier_name FROM mvp_receipts r JOIN"
                                                + " warehouses w ON w.id=r.warehouse_id JOIN"
                                                + " suppliers s ON s.id=r.supplier_id WHERE r.id=?",
                                            id));
                    MvpAccess.warehouse(
                            connection, actor, "STOCK_READ", Sql.id(row.get("warehouse_id")));
                    row.put(
                            "lines",
                            Sql.query(
                                    connection,
                                    "SELECT l.*,COALESCE(l.sku_snapshot,p.sku)"
                                        + " sku,COALESCE(l.name_snapshot,p.name) name,u.name"
                                        + " unit_name FROM mvp_receipt_lines l JOIN products p ON"
                                        + " p.id=l.product_id JOIN product_units u ON"
                                        + " u.id=l.unit_id WHERE l.receipt_id=? ORDER BY"
                                        + " l.position",
                                    id));
                    return row;
                });
    }

    // Lấy thông tin sản phẩm và kho cho phần đầu thẻ kho.
    public Map<String, Object> cardHeader(long actor, long warehouse, long product) {
        return Sql.snapshot(
                source,
                connection -> {
                    MvpAccess.warehouse(connection, actor, "STOCK_READ", warehouse);
                    return Sql.one(
                            connection,
                            "SELECT w.id warehouse_id,w.name warehouse_name,w.address,p.id"
                                    + " product_id,p.sku,p.name,p.base_unit FROM warehouses w CROSS"
                                    + " JOIN products p WHERE w.id=? AND p.id=?",
                            warehouse,
                            product);
                });
    }

    // Lấy các phát sinh để hiển thị thẻ kho.
    public List<Map<String, Object>> card(
            long actor, long warehouse, long product, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.");
        }
        // Compute balances across the entire ledger before selecting the visible period.
        return card(actor, warehouse, product).stream()
                .filter(
                        row -> {
                            LocalDate day =
                                    Sql.instant(row.get("occurred_at"))
                                            .atZone(
                                                    vn.codegym.salesinventory.config.VietnamTime
                                                            .ZONE)
                                            .toLocalDate();
                            return (from == null || !day.isBefore(from))
                                    && (to == null || !day.isAfter(to));
                        })
                .toList();
    }

    // Lấy các phát sinh để hiển thị thẻ kho.
    public List<Map<String, Object>> card(long actor, long warehouse, long product) {
        return Sql.snapshot(
                source,
                connection -> {
                    MvpAccess.warehouse(connection, actor, "STOCK_READ", warehouse);
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT e.*,l.lot_code,l.product_id,u.full_name"
                                        + " actor_name,rl.receipt_id,sl.shipment_id,rgsl.shipment_id"
                                        + " recovery_shipment_id,rtl.return_id FROM"
                                        + " mvp_stock_events e JOIN mvp_lots l ON l.id=e.lot_id"
                                        + " JOIN users u ON u.id=e.actor_id LEFT JOIN"
                                        + " mvp_receipt_lines rl ON e.source_type='RECEIPT_LINE'"
                                        + " AND rl.id=e.source_id LEFT JOIN mvp_shipment_lines sl"
                                        + " ON e.source_type='SHIPMENT_LINE' AND sl.id=e.source_id"
                                        + " LEFT JOIN mvp_recovered_goods rg ON"
                                        + " e.source_type='RECOVERED_GOODS' AND rg.id=e.source_id"
                                        + " LEFT JOIN mvp_shipment_lines rgsl ON"
                                        + " rgsl.id=rg.shipment_line_id LEFT JOIN mvp_return_lines"
                                        + " rtl ON e.source_type='RETURN_LINE' AND"
                                        + " rtl.id=e.source_id WHERE l.warehouse_id=? AND"
                                        + " l.product_id=? ORDER BY e.occurred_at,e.id",
                                    warehouse,
                                    product);
                    BigDecimal running = BigDecimal.ZERO;
                    for (var row : rows) {
                        running = running.add(MvpCommands.decimal(row.get("quantity_delta")));
                        row.put("warehouse_balance", running);
                        row.put(
                                "event_label",
                                switch (Sql.text(row.get("event_type"))) {
                                    case "RECEIPT" -> "Nhập nhà cung cấp";
                                    case "DISPATCH" -> "Xuất kho";
                                    case "RECOVERY" -> "Nhận hàng giao thất bại quay về";
                                    case "COMMERCIAL_RETURN" -> "Nhận hàng trả còn bán được";
                                    default -> "Phát sinh kho";
                                });
                    }
                    return rows;
                });
    }
}
