package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.CommandConflictException;

/**
 * S6-03, S6-05, S6-08 (MVP): Bắt đầu soạn, xuất hàng, xác nhận giao và nhận hàng quay về; khóa đại lý, đơn, lô rồi giữ chỗ.
 */
public final class FulfillmentService {

    private final DataSource source;

    private final Clock clock;

    public FulfillmentService(DataSource source) {
        this(source, Clock.systemUTC());
    }

    public FulfillmentService(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    // Chứa dòng đơn và số lượng cần xuất kho.
    public record DispatchLine(long orderLine, BigDecimal quantity) {}

    // Chứa dòng xuất và lượng khách thực nhận.
    public record DeliveryLine(long shipmentLine, BigDecimal received) {}

    // Chứa lượng hàng quay về và tình trạng hàng nhận lại.
    public record RecoveryLine(long shipmentLine, BigDecimal quantity, String condition) {}

    static Map<String, Object> warehouseShipment(
            Connection connection, long actor, long id, String permission) throws SQLException {
        var known = Sql.one(connection, "SELECT order_id FROM mvp_shipments WHERE id=?", id);
        warehouseOrder(connection, actor, Sql.id(known.get("order_id")), permission);
        return Sql.one(connection, "SELECT * FROM mvp_shipments WHERE id=? FOR UPDATE", id);
    }

    // Kiểm quyền kho rồi khóa đại lý và đơn trước khi xử lý.
    static Map<String, Object> warehouseOrder(
            Connection connection, long actor, long id, String permission) throws SQLException {
        var known = Sql.one(connection, "SELECT dealer_id,warehouse_id FROM orders WHERE id=?", id);
        if (known.get("warehouse_id") == null) {
            throw new IllegalArgumentException("Đơn chưa có kho phục vụ.");
        }
        long warehouse = Sql.id(known.get("warehouse_id"));
        MvpAccess.warehouse(connection, actor, permission, warehouse);
        Sql.one(connection, "SELECT id FROM dealers WHERE id=? FOR UPDATE", known.get("dealer_id"));
        var order = Sql.one(connection, "SELECT * FROM orders WHERE id=? FOR UPDATE", id);
        if (order.get("warehouse_id") == null || Sql.id(order.get("warehouse_id")) != warehouse) {
            throw new CommandConflictException("Kho phục vụ đã thay đổi. Hãy tải lại.");
        }
        return order;
    }

    static List<Map<String, Object>> lockedHolds(Connection connection, long order)
            throws SQLException {
        Sql.query(
                connection,
                "SELECT l.id FROM mvp_lots l WHERE l.id IN (SELECT h.lot_id FROM mvp_holds h WHERE"
                        + " h.order_id=?) ORDER BY l.product_id,l.received_date,l.id FOR UPDATE",
                order);
        return Sql.query(
                connection,
                "SELECT h.*,l.on_hand,l.product_id,l.received_date FROM mvp_holds h JOIN mvp_lots l"
                        + " ON l.id=h.lot_id WHERE h.order_id=? ORDER BY"
                        + " l.product_id,l.received_date,l.id,h.id FOR UPDATE",
                order);
    }

    // Tính phần giữ chỗ còn hiệu lực sau lượng đã xuất hoặc giải phóng.
    static BigDecimal remainder(Map<String, Object> hold, Instant now) {
        if (hold.get("protected_at") == null && !Sql.instant(hold.get("expires_at")).isAfter(now)) {
            return BigDecimal.ZERO;
        }
        return MvpCommands.decimal(hold.get("quantity"))
                .subtract(MvpCommands.decimal(hold.get("consumed")))
                .subtract(MvpCommands.decimal(hold.get("released")));
    }

    // Bắt đầu soạn đơn và bảo vệ phần giữ hàng còn hiệu lực.
    public long startPicking(long actor, long order, long version, String requestKey) {
        return Sql.transaction(
                source,
                connection -> {
                    var header = warehouseOrder(connection, actor, order, "FULFILLMENT_WRITE");
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "PICKING_START",
                                    requestKey,
                                    order + ":" + version);
                    if (command.replayId() != null) {
                        return command.replayId();
                    }
                    MvpCommands.version(header, version);
                    if (!"SUBMITTED".equals(header.get("status"))) {
                        throw new IllegalArgumentException("Chỉ soạn đơn đã tiếp nhận và giữ tồn.");
                    }
                    var now = clock.instant();
                    var holds = lockedHolds(connection, order);
                    boolean available = false;
                    for (var hold : holds) {
                        if (remainder(hold, now).signum() > 0) {
                            available = true;
                            if (hold.get("protected_at") == null) {
                                Sql.update(
                                        connection,
                                        "UPDATE mvp_holds SET protected_at=? WHERE id=?",
                                        MvpCommands.timestamp(now),
                                        hold.get("id"));
                            }
                        }
                    }
                    if (!available) {
                        throw new CommandConflictException(
                                "Không còn giữ hàng hợp lệ. Yêu cầu NVKD giữ hàng lại trước khi"
                                        + " soạn.");
                    }
                    Sql.update(
                            connection,
                            "UPDATE orders SET fulfillment_state='IN_PROGRESS',version=version+1"
                                    + " WHERE id=?",
                            order);
                    AuditService.record(
                            connection,
                            actor,
                            "PICKING_STARTED",
                            "ORDER",
                            order,
                            header,
                            Sql.one(connection, "SELECT * FROM orders WHERE id=?", order));
                    return MvpCommands.finish(connection, command, order);
                });
    }

    // Kiểm giữ chỗ, chốt giá vốn và xuất từng phần trong cùng giao dịch.
    public long dispatch(
            long actor,
            long order,
            long version,
            String requestKey,
            List<DispatchLine> lines,
            String reason) {
        if (lines == null || lines.isEmpty() || lines.size() > 100) {
            throw new IllegalArgumentException("Chọn 1–100 dòng xuất kho.");
        }
        var requested = new TreeMap<Long, BigDecimal>();
        for (var line : lines) {
            if (line == null
                    || line.orderLine <= 0
                    || requested.putIfAbsent(line.orderLine, MvpCommands.quantity(line.quantity))
                            != null) {
                throw new IllegalArgumentException("Dòng xuất không hợp lệ hoặc bị lặp.");
            }
        }
        String why = reason == null ? "" : reason.trim();
        if (why.length() > 1000) {
            throw new IllegalArgumentException("Lý do tối đa 1000 ký tự.");
        }
        String input = order + ":" + version + ":" + requested + ":" + why;
        return Sql.transaction(
                source,
                connection -> {
                    var header = warehouseOrder(connection, actor, order, "FULFILLMENT_WRITE");
                    var command =
                            MvpCommands.begin(
                                    connection, actor, "SHIPMENT_DISPATCH", requestKey, input);
                    if (command.replayId() != null) {
                        return command.replayId();
                    }
                    MvpCommands.version(header, version);
                    if (!"SUBMITTED".equals(header.get("status"))
                            || !"IN_PROGRESS".equals(header.get("fulfillment_state"))) {
                        throw new IllegalArgumentException("Bắt đầu soạn trước khi xác nhận xuất.");
                    }
                    var now = clock.instant();
                    var sourceLines =
                            Sql.query(
                                    connection,
                                    "SELECT * FROM order_lines WHERE order_id=? ORDER BY"
                                            + " product_id,position",
                                    order);
                    var holds = lockedHolds(connection, order);
                    var valid = new HashSet<Long>();
                    boolean shortage = false;
                    for (var line : sourceLines) {
                        long id = Sql.id(line.get("id"));
                        valid.add(id);
                        BigDecimal available = BigDecimal.ZERO;
                        for (var hold : holds) {
                            if (Sql.id(hold.get("order_line_id")) == id) {
                                available = available.add(remainder(hold, now));
                            }
                        }
                        BigDecimal take = requested.getOrDefault(id, BigDecimal.ZERO);
                        if (take.compareTo(available) > 0) {
                            throw new CommandConflictException(
                                    "Xuất vượt lượng giữ của dòng " + id + ".");
                        }
                        if (take.compareTo(available) < 0) {
                            shortage = true;
                        }
                    }
                    if (!valid.containsAll(requested.keySet())) {
                        throw new SecurityException("Dòng xuất không thuộc đơn.");
                    }
                    if (shortage && why.isEmpty()) {
                        throw new IllegalArgumentException("Xuất thiếu phải có lý do.");
                    }
                    var costSnapshots = CostSnapshots.capture(connection, order);
                    long shipment =
                            Sql.insert(
                                    connection,
                                    "INSERT INTO"
                                        + " mvp_shipments(order_id,warehouse_id,shortage_reason,created_by,dispatched_at)"
                                        + " VALUES(?,?,?,?,?)",
                                    order,
                                    header.get("warehouse_id"),
                                    why,
                                    actor,
                                    MvpCommands.timestamp(now));
                    for (var line : sourceLines) {
                        long lineId = Sql.id(line.get("id"));
                        BigDecimal remaining = requested.getOrDefault(lineId, BigDecimal.ZERO);
                        if (remaining.signum() == 0) {
                            continue;
                        }
                        BigDecimal costQuantity = BigDecimal.ZERO, costAllocated = BigDecimal.ZERO;
                        BigDecimal unitCost = costSnapshots.get(Sql.id(line.get("product_id")));
                        BigDecimal ordered = MvpCommands.decimal(line.get("base_quantity")),
                                total = MvpCommands.decimal(line.get("net"));
                        BigDecimal shipped =
                                CreditService.sum(
                                        connection,
                                        "SELECT COALESCE(SUM(quantity-returned_quantity),0) amount"
                                                + " FROM mvp_shipment_lines WHERE order_line_id=?",
                                        lineId);
                        BigDecimal allocated =
                                CreditService.sum(
                                        connection,
                                        "SELECT COALESCE(SUM(net_amount-returned_amount),0) amount"
                                                + " FROM mvp_shipment_lines WHERE order_line_id=?",
                                        lineId);
                        for (var hold : holds) {
                            if (Sql.id(hold.get("order_line_id")) != lineId) {
                                continue;
                            }
                            BigDecimal take = remaining.min(remainder(hold, now));
                            if (take.signum() == 0) {
                                continue;
                            }
                            if (hold.get("protected_at") == null) {
                                throw new CommandConflictException(
                                        "Giữ hàng chưa được bắt đầu soạn.");
                            }
                            long lot = Sql.id(hold.get("lot_id"));
                            BigDecimal onHand =
                                    MvpCommands.decimal(
                                            Sql.one(
                                                            connection,
                                                            "SELECT on_hand FROM mvp_lots WHERE"
                                                                    + " id=?",
                                                            lot)
                                                    .get("on_hand"));
                            if (onHand.compareTo(take) < 0) {
                                throw new CommandConflictException("Lô không đủ tồn thực.");
                            }
                            BigDecimal cumulative = shipped.add(take);
                            BigDecimal amount =
                                    (cumulative.compareTo(ordered) == 0
                                                    ? total
                                                    : MvpCommands.share(total, cumulative, ordered))
                                            .subtract(allocated);
                            if (amount.signum() < 0 || cumulative.compareTo(ordered) > 0) {
                                throw new IllegalStateException(
                                        "Phân bổ xuất vượt giá trị hoặc lượng đơn.");
                            }
                            costQuantity = costQuantity.add(take);
                            BigDecimal costTarget = CostSnapshots.amount(unitCost, costQuantity);
                            BigDecimal costAmount =
                                    costTarget == null ? null : costTarget.subtract(costAllocated);
                            if (costTarget != null) {
                                costAllocated = costTarget;
                            }
                            long shipmentLine =
                                    Sql.insert(
                                            connection,
                                            "INSERT INTO"
                                                + " mvp_shipment_lines(shipment_id,order_line_id,hold_id,lot_id,quantity,net_amount,unit_cost_snapshot,cost_amount)"
                                                + " VALUES(?,?,?,?,?,?,?,?)",
                                            shipment,
                                            lineId,
                                            hold.get("id"),
                                            lot,
                                            take,
                                            amount,
                                            unitCost,
                                            costAmount);
                            Sql.update(
                                    connection,
                                    "UPDATE mvp_lots SET on_hand=on_hand-? WHERE id=?",
                                    take,
                                    lot);
                            Sql.update(
                                    connection,
                                    "UPDATE mvp_holds SET consumed=consumed+? WHERE id=?",
                                    take,
                                    hold.get("id"));
                            StockService.movement(
                                    connection,
                                    actor,
                                    lot,
                                    "DISPATCH",
                                    "SHIPMENT_LINE",
                                    shipmentLine,
                                    take.negate(),
                                    onHand.subtract(take),
                                    now);
                            shipped = cumulative;
                            allocated = allocated.add(amount);
                            remaining = remaining.subtract(take);
                            if (remaining.signum() == 0) {
                                break;
                            }
                        }
                        if (remaining.signum() != 0) {
                            throw new CommandConflictException(
                                    "Giữ hàng đã thay đổi. Hãy tải lại.");
                        }
                    }
                    Sql.update(connection, "UPDATE orders SET version=version+1 WHERE id=?", order);
                    Sql.update(
                            connection,
                            "UPDATE mvp_shipments SET number=? WHERE id=?",
                            MvpCommands.nextNumber(connection, "PX", MvpCommands.date(now)),
                            shipment);
                    AuditService.record(
                            connection,
                            actor,
                            "SHIPMENT_DISPATCHED",
                            "SHIPMENT",
                            shipment,
                            null,
                            Sql.one(
                                    connection,
                                    "SELECT * FROM mvp_shipments WHERE id=?",
                                    shipment));
                    return MvpCommands.finish(connection, command, shipment);
                });
    }

    // Lấy các đơn phù hợp để người dùng thực hiện nghiệp vụ kho.
    public List<Map<String, Object>> orders(long actor) {
        return Sql.snapshot(
                source,
                connection -> {
                    MvpAccess.stockOperator(connection, actor, "FULFILLMENT_READ");
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT"
                                        + " o.id,o.number,o.version,o.fulfillment_state,o.dealer_name_snapshot,o.warehouse_name_snapshot,o.desired_delivery"
                                        + " FROM orders o JOIN user_warehouses uw ON"
                                        + " uw.warehouse_id=o.warehouse_id AND uw.user_id=? WHERE"
                                        + " o.status='SUBMITTED' ORDER BY o.id DESC LIMIT 100",
                                    actor);
                    for (var row : rows) {
                        row.put(
                                "holds",
                                OrderHoldDisplay.load(
                                        connection, Sql.id(row.get("id")), clock.instant()));
                    }
                    return rows;
                });
    }

    // Ghi nhận lượng thực giao và phần hàng còn chờ xử lý.
    public long deliver(
            long actor,
            long shipment,
            long version,
            String requestKey,
            String recipient,
            List<DeliveryLine> lines,
            String reason) {
        String who = MvpCommands.text(recipient, 200, "Người nhận");
        String why = reason == null ? "" : reason.trim();
        if (why.length() > 1000) {
            throw new IllegalArgumentException("Lý do tối đa 1000 ký tự.");
        }
        if (lines == null || lines.isEmpty() || lines.size() > 1000) {
            throw new IllegalArgumentException("Nhập đủ dòng giao hàng.");
        }
        var received = new TreeMap<Long, BigDecimal>();
        for (var line : lines) {
            if (line == null
                    || line.shipmentLine <= 0
                    || line.received == null
                    || line.received.signum() < 0) {
                throw new IllegalArgumentException("Lượng giao không hợp lệ.");
            }
            BigDecimal quantity =
                    line.received.signum() == 0
                            ? BigDecimal.ZERO.setScale(12)
                            : MvpCommands.quantity(line.received);
            if (received.putIfAbsent(line.shipmentLine, quantity) != null) {
                throw new IllegalArgumentException("Dòng giao bị lặp.");
            }
        }
        return Sql.transaction(
                source,
                connection -> {
                    var header =
                            warehouseShipment(connection, actor, shipment, "FULFILLMENT_WRITE");
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "SHIPMENT_DELIVER",
                                    requestKey,
                                    shipment + ":" + version + ":" + who + ":" + received + ":"
                                            + why);
                    if (command.replayId() != null) {
                        return command.replayId();
                    }
                    MvpCommands.version(header, version);
                    if (!"DISPATCHED".equals(header.get("status"))) {
                        throw new IllegalArgumentException("Phiếu đã xác nhận giao chỉ được xem.");
                    }
                    var sourceLines =
                            Sql.query(
                                    connection,
                                    "SELECT * FROM mvp_shipment_lines WHERE shipment_id=? ORDER BY"
                                            + " id FOR UPDATE",
                                    shipment);
                    var valid = new HashSet<Long>();
                    var now = clock.instant();
                    for (var line : sourceLines) {
                        long id = Sql.id(line.get("id"));
                        valid.add(id);
                        BigDecimal quantity = received.get(id);
                        if (quantity == null) {
                            throw new IllegalArgumentException(
                                    "Nhập lượng nhận cho từng dòng, kể cả 0.");
                        }
                        BigDecimal dispatched = MvpCommands.decimal(line.get("quantity"));
                        if (quantity.compareTo(dispatched) > 0) {
                            throw new IllegalArgumentException("Lượng nhận vượt lượng xuất.");
                        }
                        if (quantity.compareTo(dispatched) < 0 && why.isEmpty()) {
                            throw new IllegalArgumentException(
                                    "Giao thiếu hoặc thất bại phải có lý do.");
                        }
                    }
                    if (!valid.equals(received.keySet())) {
                        throw new SecurityException("Dòng giao không thuộc phiếu.");
                    }
                    for (var line : sourceLines) {
                        long id = Sql.id(line.get("id"));
                        BigDecimal amount =
                                MvpCommands.share(
                                        MvpCommands.decimal(line.get("net_amount")),
                                        received.get(id),
                                        MvpCommands.decimal(line.get("quantity")));
                        Sql.update(
                                connection,
                                "UPDATE mvp_shipment_lines SET"
                                        + " received_quantity=?,received_amount=?,delivery_reason=?"
                                        + " WHERE id=?",
                                received.get(id),
                                amount,
                                why,
                                id);
                    }
                    Sql.update(
                            connection,
                            "UPDATE mvp_shipments SET"
                                + " status='DELIVERED',version=version+1,delivered_by=?,recipient=?,delivered_at=?"
                                + " WHERE id=?",
                            actor,
                            who,
                            MvpCommands.timestamp(now),
                            shipment);
                    long order = Sql.id(header.get("order_id"));
                    long unfinished =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) n FROM order_lines ol WHERE"
                                                            + " ol.order_id=? AND"
                                                            + " ol.base_quantity>COALESCE((SELECT"
                                                            + " SUM(sl.received_quantity) FROM"
                                                            + " mvp_shipment_lines sl WHERE"
                                                            + " sl.order_line_id=ol.id),0)",
                                                    order)
                                            .get("n"));
                    Sql.update(
                            connection,
                            "UPDATE orders SET version=version+1,fulfillment_state=CASE WHEN ?=0"
                                    + " THEN 'COMPLETE' ELSE fulfillment_state END WHERE id=?",
                            unfinished,
                            order);
                    AuditService.record(
                            connection,
                            actor,
                            "SHIPMENT_DELIVERED",
                            "SHIPMENT",
                            shipment,
                            header,
                            Sql.one(
                                    connection,
                                    "SELECT * FROM mvp_shipments WHERE id=?",
                                    shipment));
                    return MvpCommands.finish(connection, command, shipment);
                });
    }

    // Nhận hàng quay về và phân biệt hàng tốt với hàng hỏng.
    public long recover(
            long actor,
            long shipment,
            long version,
            String requestKey,
            List<RecoveryLine> lines,
            String reason) {
        String why = MvpCommands.text(reason, 1000, "Lý do nhận hàng quay về");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Chọn lượng hàng thực nhận lại.");
        }
        var requested = new TreeMap<String, RecoveryLine>();
        for (var line : lines) {
            if (line == null
                    || line.shipmentLine <= 0
                    || !Set.of("SELLABLE", "DAMAGED").contains(line.condition)) {
                throw new IllegalArgumentException("Tình trạng hàng không hợp lệ.");
            }
            var normalized =
                    new RecoveryLine(
                            line.shipmentLine, MvpCommands.quantity(line.quantity), line.condition);
            if (requested.putIfAbsent(line.shipmentLine + ":" + line.condition, normalized)
                    != null) {
                throw new IllegalArgumentException("Dòng nhận lại bị lặp.");
            }
        }
        return Sql.transaction(
                source,
                connection -> {
                    var header =
                            warehouseShipment(connection, actor, shipment, "STOCK_RETURN_RECEIVE");
                    MvpAccess.warehouseManager(
                            connection,
                            actor,
                            "STOCK_RETURN_RECEIVE",
                            Sql.id(header.get("warehouse_id")));
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "SHIPMENT_RECOVER",
                                    requestKey,
                                    shipment + ":" + version + ":" + requested + ":" + why);
                    if (command.replayId() != null) {
                        return command.replayId();
                    }
                    MvpCommands.version(header, version);
                    if (!"DELIVERED".equals(header.get("status"))) {
                        throw new IllegalArgumentException(
                                "Xác nhận giao trước khi nhận hàng quay về.");
                    }
                    Sql.query(
                            connection,
                            "SELECT l.id FROM mvp_lots l WHERE l.id IN (SELECT sl.lot_id FROM"
                                    + " mvp_shipment_lines sl WHERE sl.shipment_id=?) ORDER BY"
                                    + " l.product_id,l.received_date,l.id FOR UPDATE",
                            shipment);
                    var sourceLines =
                            Sql.query(
                                    connection,
                                    "SELECT * FROM mvp_shipment_lines WHERE shipment_id=? ORDER BY"
                                            + " id FOR UPDATE",
                                    shipment);
                    var indexed = new HashMap<Long, Map<String, Object>>();
                    for (var line : sourceLines) {
                        indexed.put(Sql.id(line.get("id")), line);
                    }
                    var totals = new HashMap<Long, BigDecimal>();
                    for (var line : requested.values()) {
                        if (!indexed.containsKey(line.shipmentLine)) {
                            throw new SecurityException("Dòng quay về không thuộc phiếu.");
                        }
                        totals.merge(line.shipmentLine, line.quantity, BigDecimal::add);
                    }
                    for (var entry : totals.entrySet()) {
                        var line = indexed.get(entry.getKey());
                        BigDecimal pending =
                                MvpCommands.decimal(line.get("quantity"))
                                        .subtract(
                                                MvpCommands.decimal(line.get("received_quantity")))
                                        .subtract(
                                                MvpCommands.decimal(line.get("returned_quantity")));
                        if (entry.getValue().compareTo(pending) > 0) {
                            throw new CommandConflictException("Nhận lại vượt lượng chờ quay về.");
                        }
                    }
                    var now = clock.instant();
                    for (var entry : requested.values()) {
                        var line = indexed.get(entry.shipmentLine);
                        long recovery =
                                Sql.insert(
                                        connection,
                                        "INSERT INTO"
                                            + " mvp_recovered_goods(shipment_line_id,quantity,condition_code,reason,recorded_by,recorded_at)"
                                            + " VALUES(?,?,?,?,?,?)",
                                        entry.shipmentLine,
                                        entry.quantity,
                                        entry.condition,
                                        why,
                                        actor,
                                        MvpCommands.timestamp(now));
                        if ("SELLABLE".equals(entry.condition)) {
                            long lot = Sql.id(line.get("lot_id"));
                            BigDecimal onHand =
                                    MvpCommands.decimal(
                                            Sql.one(
                                                            connection,
                                                            "SELECT on_hand FROM mvp_lots WHERE"
                                                                    + " id=?",
                                                            lot)
                                                    .get("on_hand"));
                            Sql.update(
                                    connection,
                                    "UPDATE mvp_lots SET on_hand=on_hand+? WHERE id=?",
                                    entry.quantity,
                                    lot);
                            StockService.movement(
                                    connection,
                                    actor,
                                    lot,
                                    "RECOVERY",
                                    "RECOVERED_GOODS",
                                    recovery,
                                    entry.quantity,
                                    onHand.add(entry.quantity),
                                    now);
                        }
                        BigDecimal failed =
                                MvpCommands.decimal(line.get("quantity"))
                                        .subtract(
                                                MvpCommands.decimal(line.get("received_quantity")));
                        BigDecimal returned =
                                MvpCommands.decimal(line.get("returned_quantity"))
                                        .add(entry.quantity);
                        BigDecimal failedAmount =
                                MvpCommands.decimal(line.get("net_amount"))
                                        .subtract(MvpCommands.decimal(line.get("received_amount")));
                        BigDecimal returnedAmount =
                                returned.compareTo(failed) == 0
                                        ? failedAmount
                                        : MvpCommands.share(failedAmount, returned, failed);
                        Sql.update(
                                connection,
                                "UPDATE mvp_shipment_lines SET"
                                        + " returned_quantity=?,returned_amount=? WHERE id=?",
                                returned,
                                returnedAmount,
                                entry.shipmentLine);
                        line.put("returned_quantity", returned);
                        line.put("returned_amount", returnedAmount);
                    }
                    Sql.update(
                            connection,
                            "UPDATE mvp_shipments SET version=version+1 WHERE id=?",
                            shipment);
                    Sql.update(
                            connection,
                            "UPDATE orders SET version=version+1 WHERE id=?",
                            header.get("order_id"));
                    var audit = new LinkedHashMap<String, Object>();
                    audit.put("lines", requested.values());
                    audit.put("reason", why);
                    AuditService.record(
                            connection,
                            actor,
                            "FAILED_GOODS_RECEIVED",
                            "SHIPMENT",
                            shipment,
                            header,
                            audit);
                    return MvpCommands.finish(connection, command, shipment);
                });
    }

    // Lấy danh sách các lần xuất hàng của đơn.
    public List<Map<String, Object>> shipments(long actor) {
        return Sql.snapshot(
                source,
                connection -> {
                    MvpAccess.stockOperator(connection, actor, "FULFILLMENT_READ");
                    return Sql.query(
                            connection,
                            "SELECT s.*,o.number"
                                + " order_number,o.dealer_name_snapshot,o.warehouse_name_snapshot"
                                + " FROM mvp_shipments s JOIN orders o ON o.id=s.order_id JOIN"
                                + " user_warehouses uw ON uw.warehouse_id=s.warehouse_id AND"
                                + " uw.user_id=? ORDER BY s.id DESC LIMIT 100",
                            actor);
                });
    }

    // Lấy chi tiết phiếu xuất trong phạm vi được phép xem.
    public Map<String, Object> shipment(long actor, long id) {
        return Sql.transaction(
                source,
                connection -> {
                    var header =
                            new LinkedHashMap<>(
                                    warehouseShipment(connection, actor, id, "FULFILLMENT_READ"));
                    header.put(
                            "order",
                            Sql.one(
                                    connection,
                                    "SELECT"
                                        + " id,number,dealer_name_snapshot,warehouse_name_snapshot,address_snapshot,recipient_snapshot,phone_snapshot"
                                        + " FROM orders WHERE id=?",
                                    header.get("order_id")));
                    header.put(
                            "lines",
                            Sql.query(
                                    connection,
                                    "SELECT sl.*,ol.sku_snapshot,ol.name_snapshot,l.lot_code FROM"
                                        + " mvp_shipment_lines sl JOIN order_lines ol ON"
                                        + " ol.id=sl.order_line_id JOIN mvp_lots l ON"
                                        + " l.id=sl.lot_id WHERE sl.shipment_id=? ORDER BY sl.id",
                                    id));
                    @SuppressWarnings("unchecked")
                    var visibleLines = (List<Map<String, Object>>) header.get("lines");
                    CostSnapshots.hide(visibleLines);
                    header.put(
                            "pending_total",
                            CreditService.sum(
                                    connection,
                                    "SELECT"
                                        + " COALESCE(SUM(quantity-received_quantity-returned_quantity),0)"
                                        + " amount FROM mvp_shipment_lines WHERE shipment_id=? AND"
                                        + " received_quantity IS NOT NULL",
                                    id));
                    header.put(
                            "recoveries",
                            Sql.query(
                                    connection,
                                    "SELECT r.* FROM mvp_recovered_goods r JOIN mvp_shipment_lines"
                                        + " sl ON sl.id=r.shipment_line_id WHERE sl.shipment_id=?"
                                        + " ORDER BY r.id",
                                    id));
                    return header;
                });
    }

    // Lấy chi tiết đơn trong phạm vi kho người dùng được phép xử lý.
    public Map<String, Object> order(long actor, long id) {
        return Sql.transaction(
                source,
                connection -> {
                    var header =
                            new LinkedHashMap<>(
                                    warehouseOrder(connection, actor, id, "FULFILLMENT_READ"));
                    var lines =
                            Sql.query(
                                    connection,
                                    "SELECT"
                                        + " id,sku_snapshot,name_snapshot,unit_name_snapshot,base_quantity"
                                        + " FROM order_lines WHERE order_id=? ORDER BY position",
                                    id);
                    for (var line : lines) {
                        line.put(
                                "held",
                                CreditService.sum(
                                        connection,
                                        "SELECT COALESCE(SUM(quantity-consumed-released),0) amount"
                                                + " FROM mvp_holds WHERE order_line_id=? AND"
                                                + " (protected_at IS NOT NULL OR expires_at>?)",
                                        line.get("id"),
                                        MvpCommands.timestamp(clock.instant())));
                    }
                    header.put("holds", OrderHoldDisplay.load(connection, id, clock.instant()));
                    header.put("lines", lines);
                    header.put(
                            "shipments",
                            Sql.query(
                                    connection,
                                    "SELECT * FROM mvp_shipments WHERE order_id=? ORDER BY id",
                                    id));
                    return header;
                });
    }
}
