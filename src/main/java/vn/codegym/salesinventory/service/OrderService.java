package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.config.VietnamTime;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.validation.*;

/**
 * S3-09, S4-01/02/03/05/06/07/08/10 (phần đã có): Tạo, tính giá, duyệt và hủy đơn; kiểm giá và phạm vi tại server.
 */
public final class OrderService {

    private final DataSource source;

    private final Clock clock;

    public OrderService(DataSource source) {
        this(source, Clock.systemUTC());
    }

    public OrderService(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    // Chứa sản phẩm, đơn vị và số lượng của dòng đơn nhập vào.
    public record LineInput(long product, long unit, BigDecimal quantity) {}

    // Chứa dữ liệu đầu vào khi tạo hoặc sửa đơn hàng.
    public record Input(
            long dealer,
            long address,
            LocalDate delivery,
            long version,
            String creationKey,
            List<LineInput> lines) {}

    // Chứa kết quả quy đổi, áp giá và chiết khấu của một dòng đơn.
    public record LineQuote(
            int position,
            long product,
            String sku,
            String name,
            UnitService.Conversion conversion,
            PricingService.Quote price,
            DiscountMath.Selection policy,
            BigDecimal gross,
            BigDecimal discount,
            BigDecimal net) {}

    // Chứa báo giá và các điều kiện cần xác nhận trước khi tiếp nhận đơn.
    public record Quote(
            LocalDate date,
            List<LineQuote> lines,
            BigDecimal gross,
            BigDecimal discount,
            BigDecimal net,
            Map<String, String> errors,
            String warning,
            String digest,
            String token,
            boolean confirmation,
            Map<String, Object> controls) {

        public Quote(
                LocalDate date,
                List<LineQuote> lines,
                BigDecimal gross,
                BigDecimal discount,
                BigDecimal net,
                Map<String, String> errors,
                String warning,
                String digest,
                String token,
                boolean confirmation) {
            this(
                    date,
                    lines,
                    gross,
                    discount,
                    net,
                    errors,
                    warning,
                    digest,
                    token,
                    confirmation,
                    Map.of());
        }

        public boolean valid() {
            return errors.isEmpty();
        }

        public boolean needsApproval() {
            return lines.stream().anyMatch(OrderService::belowFloor)
                    || Boolean.TRUE.equals(controls.get("creditApproval"));
        }

        // Chuẩn bị báo giá công khai, loại giá sàn, giá vốn và thông tin người thao tác nội bộ.
        public Map<String, Object> reviewDisplay() {
            var result = new LinkedHashMap<>(display());
            @SuppressWarnings("unchecked")
            var credit = (Map<String, Object>) controls.get("credit");
            if (credit != null) {
                BigDecimal netDebt =
                        new BigDecimal(credit.get("outstanding").toString())
                                .subtract(new BigDecimal(credit.get("credit").toString()));
                BigDecimal exposure =
                        netDebt.add(new BigDecimal(credit.get("commitment").toString())).add(net);
                BigDecimal excess =
                        exposure.subtract(new BigDecimal(credit.get("limit").toString()))
                                .max(BigDecimal.ZERO);
                result.put(
                        "creditReview",
                        Map.of(
                                "netDebt",
                                MoneyDisplay.format(netDebt),
                                "exposure",
                                MoneyDisplay.format(exposure),
                                "excess",
                                MoneyDisplay.format(excess),
                                "exceeds",
                                excess.signum() > 0));
            }
            result.put("priceException", lines.stream().anyMatch(OrderService::belowFloor));
            @SuppressWarnings("unchecked")
            var rows = (List<Map<String, Object>>) result.get("lines");
            for (int i = 0; i < rows.size(); i++) {
                rows.get(i)
                        .put(
                                "floorFormatted",
                                MoneyDisplay.format(lines.get(i).price.floorPrice()));
                rows.get(i).put("floorExact", lines.get(i).price.floorPrice().toPlainString());
            }
            return result;
        }

        // Chuẩn bị dữ liệu đã định dạng để hiển thị.
        public Map<String, Object> display() {
            var rows = new ArrayList<Map<String, Object>>();
            for (var orderLine : lines) {
                var row = new LinkedHashMap<String, Object>();
                row.put("position", orderLine.position);
                row.put("sku", orderLine.sku);
                row.put("name", orderLine.name);
                row.put("unit", orderLine.conversion.unitName());
                row.put("factor", orderLine.conversion.factor().toPlainString());
                row.put("baseQuantity", orderLine.conversion.baseQuantity().toPlainString());
                if (controls.get("available") instanceof Map<?, ?> available
                        && available.get(Long.toString(orderLine.product)) != null) {
                    BigDecimal quantity =
                            new BigDecimal(
                                    available.get(Long.toString(orderLine.product)).toString());
                    row.put("availableBase", quantity.stripTrailingZeros().toPlainString());
                    row.put(
                            "availableUnits",
                            quantity.max(BigDecimal.ZERO)
                                    .divide(orderLine.conversion.factor(), 6, RoundingMode.DOWN)
                                    .stripTrailingZeros()
                                    .toPlainString());
                }
                row.put("selling", orderLine.price.sellingPrice().toPlainString());
                row.put("gross", orderLine.gross.toPlainString());
                row.put("discount", orderLine.discount.toPlainString());
                row.put("net", orderLine.net.toPlainString());
                row.put(
                        "formatted",
                        Map.of(
                                "baseQuantity",
                                QuantityDisplay.format(orderLine.conversion.baseQuantity()),
                                "factor",
                                QuantityDisplay.format(orderLine.conversion.factor()),
                                "availableBase",
                                QuantityDisplay.format(row.get("availableBase")),
                                "availableUnits",
                                QuantityDisplay.format(row.get("availableUnits")),
                                "selling",
                                MoneyDisplay.format(orderLine.price.sellingPrice()),
                                "gross",
                                MoneyDisplay.format(orderLine.gross),
                                "discount",
                                MoneyDisplay.format(orderLine.discount),
                                "net",
                                MoneyDisplay.format(orderLine.net)));
                row.put("needsApproval", belowFloor(orderLine));
                row.put("policy", orderLine.policy.applied() ? orderLine.policy.tier().name() : "");
                rows.add(row);
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("date", date.toString());
            result.put("lines", rows);
            result.put("gross", gross.toPlainString());
            result.put("discount", discount.toPlainString());
            result.put("net", net.toPlainString());
            result.put("errors", errors);
            result.put("warning", warning);
            result.put("token", token);
            result.put("confirmation", confirmation);
            result.put("needsApproval", needsApproval());
            result.put("controls", controls);
            result.put(
                    "formatted",
                    Map.of(
                            "gross",
                            MoneyDisplay.format(gross),
                            "discount",
                            MoneyDisplay.format(discount),
                            "net",
                            MoneyDisplay.format(net)));
            return result;
        }
    }

    // Chứa kết quả gửi đơn, báo giá và trạng thái xử lý.
    public record Submission(boolean submitted, long order, Quote quote, String status) {

        public Submission(boolean submitted, long order, Quote quote) {
            this(submitted, order, quote, submitted ? "SUBMITTED" : "DRAFT");
        }

        public boolean accepted() {
            return !"DRAFT".equals(status);
        }
    }

    // Gom quyền truy cập, đại lý và đơn đang được xử lý.
    private record Context(Access access, Map<String, Object> dealer, Map<String, Object> order) {}

    private static boolean all(Access access) {
        return access.allows("ORDER_READ_ALL") && DealerService.all(access);
    }

    private static Long nullableId(Object value) {
        return value == null ? null : Sql.id(value);
    }

    // Kiểm tra mã chống ghi lặp và gắn lỗi với đúng trường biểu mẫu.
    private static String uuid(String value, String field) {
        try {
            return UUID.fromString(value).toString();
        } catch (Exception exception) {
            throw FieldValidationException.field(
                    field, "Mã chống lưu/gửi lặp không hợp lệ. Hãy tải lại form.");
        }
    }

    // Băm dữ liệu để so sánh nội dung giữa các lần xác nhận đơn.
    private static String digest(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // Kiểm tra dữ liệu đầu vào và gom lỗi theo từng trường.
    public static Map<String, String> errors(Input input) {
        var errors = new LinkedHashMap<String, String>();
        if (input == null) {
            return Map.of("form", "Thiếu dữ liệu đơn.");
        }
        if (input.dealer <= 0) {
            errors.put("dealer", "Chọn đại lý.");
        }
        if (input.address <= 0) {
            errors.put("address", "Chọn điểm giao.");
        }
        if (input.delivery == null
                || input.delivery.getYear() < 1000
                || input.delivery.getYear() > 9999) {
            errors.put("delivery", "Ngày giao mong muốn không hợp lệ.");
        }
        if (input.version < 0) {
            errors.put("form", "Phiên bản không hợp lệ.");
        }
        if (input.lines == null || input.lines.isEmpty() || input.lines.size() > 100) {
            errors.put("lines", "Đơn cần 1–100 dòng hàng.");
        } else {
            for (int i = 0; i < input.lines.size(); i++) {
                var orderLine = input.lines.get(i);
                if (orderLine == null || orderLine.product <= 0 || orderLine.unit <= 0) {
                    errors.put("line" + i, "Chọn sản phẩm và đơn vị.");
                    continue;
                }
                try {
                    CatalogValidation.decimal(orderLine.quantity, 6, true);
                } catch (IllegalArgumentException exception) {
                    errors.put("quantity" + i, exception.getMessage());
                }
            }
        }
        return errors;
    }

    // Kiểm tra dữ liệu trước khi tiếp tục xử lý.
    private static void validate(Input input) {
        var errors = errors(input);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    // Kiểm tra quyền đọc trước khi truy vấn dữ liệu.
    private static Access reader(Connection connection, long actor) throws SQLException {
        if (PortalAccountService.portal(connection, actor)) {
            return PortalAccountService.access(connection, actor, "PORTAL_ORDER_READ");
        }
        var access = DealerService.access(connection, actor, "ORDER_READ");
        if (access.roles().contains("DEALER")) {
            throw new SecurityException();
        }
        return access;
    }

    private static boolean portal(Access access) {
        return access.roles().equals(Set.of("DEALER"));
    }

    // Kiểm tra quyền ghi trước khi thay đổi dữ liệu.
    private static void writer(Access access) {
        if (portal(access)) {
            access.require("PORTAL_ORDER_WRITE");
            return;
        }
        access.require("ORDER_WRITE");
        if (!access.roles().stream().anyMatch(Set.of("SALES_MANAGER", "SALES")::contains)) {
            throw new SecurityException();
        }
    }

    private static Map<String, Object> scoped(
            Connection connection, long actor, Access access, long dealer, boolean lock)
            throws SQLException {
        return portal(access)
                ? PortalAccountService.scoped(connection, actor, access, dealer, lock)
                : DealerService.scoped(connection, actor, access, dealer, lock);
    }

    private static void owner(Map<String, Object> row, long actor) {
        if (Sql.id(row.get("owner_id")) != actor) {
            throw new SecurityException(
                    "Nháp thuộc người khác; Quản lý kinh doanh có thể tiếp quản với lý do.");
        }
    }

    // Kiểm tra đơn còn ở trạng thái nháp và đúng phiên bản trước khi sửa.
    private static void editable(Map<String, Object> row, long version) {
        MvpCommands.version(row, version);
        if (!"DRAFT".equals(row.get("status"))) {
            throw FieldValidationException.field("form", "Đơn đã gửi chỉ được xem.");
        }
    }

    private static Context context(Connection connection, long actor, long id, boolean lock)
            throws SQLException {
        return context(connection, actor, id, lock, false);
    }

    // Lấy dữ liệu và quyền cần thiết để xử lý đơn.
    private static Context context(
            Connection connection, long actor, long id, boolean lock, boolean finalAcceptance)
            throws SQLException {
        var access = reader(connection, actor);
        var known =
                Sql.query(
                        connection,
                        "SELECT dealer_id,created_by,source FROM orders WHERE id=?",
                        id);
        if (known.isEmpty()) {
            throw new SecurityException("Đơn không thuộc phạm vi được phép.");
        }
        if (lock && finalAcceptance && "PORTAL".equals(known.get(0).get("source"))) {
            long creator = Sql.id(known.get(0).get("created_by"));
            var creatorAccess =
                    PortalAccountService.access(connection, creator, "PORTAL_ORDER_READ");
            PortalAccountService.scoped(
                    connection,
                    creator,
                    creatorAccess,
                    Sql.id(known.get(0).get("dealer_id")),
                    false);
        }
        var dealer = scoped(connection, actor, access, Sql.id(known.get(0).get("dealer_id")), lock);
        var order =
                Sql.one(
                        connection,
                        "SELECT * FROM orders WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                        id);
        return new Context(access, dealer, order);
    }

    // Kiểm tra địa chỉ giao thuộc đại lý và còn dùng được cho đơn.
    private static Map<String, Object> address(
            Connection connection, Map<String, Object> dealer, long id) throws SQLException {
        var rows =
                Sql.query(
                        connection,
                        "SELECT * FROM dealer_addresses WHERE id=? AND dealer_id=? FOR SHARE",
                        id,
                        dealer.get("id"));
        if (rows.isEmpty() || !"ACTIVE".equals(rows.get(0).get("status"))) {
            throw FieldValidationException.field(
                    "address", "Điểm giao không thuộc đại lý hoặc đã ngừng sử dụng.");
        }
        return rows.get(0);
    }

    // Khóa các sản phẩm của đơn theo thứ tự mã để thống nhất thứ tự khóa.
    private static void productLocks(Connection connection, List<LineInput> lines)
            throws SQLException {
        Sql.one(connection, "SELECT name FROM catalog_locks WHERE name='CATEGORY_TREE' FOR SHARE");
        for (long product : lines.stream().map(LineInput::product).distinct().sorted().toList()) {
            if (Sql.query(connection, "SELECT id FROM products WHERE id=? FOR SHARE", product)
                    .isEmpty()) {
                throw FieldValidationException.field("lines", "Sản phẩm không còn tồn tại.");
            }
        }
    }

    private static List<LineInput> inputs(Connection connection, long order) throws SQLException {
        return Sql.query(
                        connection,
                        "SELECT product_id,unit_id,quantity FROM order_lines WHERE order_id=? ORDER"
                                + " BY position",
                        order)
                .stream()
                .map(
                        line ->
                                new LineInput(
                                        Sql.id(line.get("product_id")),
                                        Sql.id(line.get("unit_id")),
                                        (BigDecimal) line.get("quantity")))
                .toList();
    }

    // Tạo dấu đối chiếu từ dữ liệu đầu vào của đơn hàng.
    private static String inputHash(Input input) {
        var normalized = new ArrayList<Object>();
        normalized.add(input.dealer);
        normalized.add(input.address);
        normalized.add(input.delivery.toString());
        for (var orderLine : input.lines) {
            normalized.add(
                    List.of(
                            orderLine.product,
                            orderLine.unit,
                            CatalogValidation.decimal(orderLine.quantity, 6, true)
                                    .toPlainString()));
        }
        return digest(normalized);
    }

    private void validateDelivery(LocalDate delivery) {
        if (delivery.isBefore(clock.instant().atZone(VietnamTime.ZONE).toLocalDate())) {
            throw FieldValidationException.field(
                    "delivery", "Ngày giao không được trước ngày hiện tại tại Việt Nam.");
        }
    }

    // Lưu thông tin đơn hàng sau khi kiểm tra dữ liệu.
    public long save(long actor, long id, Input input) {
        validate(input);
        String key = id == 0 ? uuid(input.creationKey, "creationKey") : "", hash = inputHash(input);
        return Sql.transaction(
                source,
                connection -> {
                    var access = reader(connection, actor);
                    writer(access);
                    var dealer = scoped(connection, actor, access, input.dealer, true);
                    if (id == 0) {
                        var old =
                                Sql.query(
                                        connection,
                                        "SELECT id,creation_hash FROM orders WHERE created_by=? AND"
                                                + " creation_key=?",
                                        actor,
                                        key);
                        if (!old.isEmpty()) {
                            if (!hash.equals(old.get(0).get("creation_hash"))) {
                                throw FieldValidationException.field(
                                        "form",
                                        "Mã lưu đã dùng cho dữ liệu khác. Hãy mở lại đơn đã lưu.");
                            }
                            return Sql.id(old.get(0).get("id"));
                        }
                        DealerStatusService.requireNewOrderAllowed(dealer);
                        if (CreditService.snapshot(connection, input.dealer, clock.instant())
                                .overdue()) {
                            throw FieldValidationException.field(
                                    "dealer",
                                    "Đại lý có khoản quá hạn chưa thanh toán; không tạo đơn mới.");
                        }
                    }
                    Map<String, Object> before = null;
                    if (id != 0) {
                        before =
                                Sql.one(
                                        connection,
                                        "SELECT * FROM orders WHERE id=? FOR UPDATE",
                                        id);
                        if (Sql.id(before.get("dealer_id")) != input.dealer) {
                            throw new SecurityException();
                        }
                        owner(before, actor);
                        editable(before, input.version);
                    }
                    validateDelivery(input.delivery);
                    address(connection, dealer, input.address);
                    productLocks(connection, input.lines);
                    var errors = new LinkedHashMap<String, String>();
                    for (int i = 0; i < input.lines.size(); i++) {
                        var orderLine = input.lines.get(i);
                        try {
                            UnitService.orderConversion(
                                    connection,
                                    orderLine.product,
                                    orderLine.unit,
                                    orderLine.quantity,
                                    nullableId(dealer.get("warehouse_id")));
                        } catch (IllegalArgumentException exception) {
                            errors.put("line" + i, exception.getMessage());
                        }
                    }
                    if (!errors.isEmpty()) {
                        throw new FieldValidationException(errors);
                    }
                    long saved = id;
                    if (id == 0) {
                        try {
                            saved =
                                    Sql.insert(
                                            connection,
                                            "INSERT INTO"
                                                + " orders(dealer_id,address_id,desired_delivery,created_by,owner_id,creation_key,creation_hash,source)"
                                                + " VALUES(?,?,?,?,?,?,?,?)",
                                            input.dealer,
                                            input.address,
                                            input.delivery,
                                            actor,
                                            actor,
                                            key,
                                            hash,
                                            portal(access) ? "PORTAL" : "INTERNAL");
                        } catch (SQLException e) {
                            if (e.getErrorCode() == 1062) {
                                throw FieldValidationException.field(
                                        "form",
                                        "Mã lưu đã được dùng đồng thời. Hãy mở lại đơn đã lưu.");
                            }
                            throw e;
                        }
                    } else {
                        Sql.update(
                                connection,
                                "UPDATE orders SET"
                                        + " address_id=?,desired_delivery=?,version=version+1 WHERE"
                                        + " id=?",
                                input.address,
                                input.delivery,
                                id);
                        Sql.update(connection, "DELETE FROM order_lines WHERE order_id=?", id);
                        Sql.update(
                                connection,
                                "DELETE FROM product_transaction_references WHERE"
                                        + " reference_type='ORDER' AND reference_id=?",
                                Long.toString(id));
                        Sql.update(
                                connection, "DELETE FROM order_quote_tickets WHERE order_id=?", id);
                    }
                    int position = 0;
                    for (var l : input.lines) {
                        Sql.insert(
                                connection,
                                "INSERT INTO"
                                    + " order_lines(order_id,position,product_id,unit_id,quantity)"
                                    + " VALUES(?,?,?,?,?)",
                                saved,
                                position++,
                                l.product,
                                l.unit,
                                CatalogValidation.decimal(l.quantity, 6, true));
                    }
                    Sql.update(
                            connection,
                            "INSERT IGNORE INTO"
                                + " product_transaction_references(product_id,reference_type,reference_id)"
                                + " SELECT product_id,'ORDER',? FROM order_lines WHERE order_id=?",
                            Long.toString(saved),
                            saved);
                    Sql.update(
                            connection,
                            "INSERT IGNORE INTO"
                                + " dealer_transaction_references(dealer_id,reference_type,reference_id)"
                                + " VALUES(?,'ORDER',?)",
                            input.dealer,
                            Long.toString(saved));
                    AuditService.record(
                            connection,
                            actor,
                            "ORDER_DRAFT_SAVED",
                            "ORDER",
                            saved,
                            before,
                            Sql.one(connection, "SELECT * FROM orders WHERE id=?", saved));
                    return saved;
                });
    }

    // Tìm đơn hàng theo mã hoặc điều kiện được cung cấp.
    public Map<String, Object> find(long actor, long id) {
        return Sql.transaction(
                source,
                connection -> {
                    var context = context(connection, actor, id, false);
                    var row = new LinkedHashMap<>(context.order);
                    row.put("dealer_name", context.dealer.get("name"));
                    row.put("dealer_code", context.dealer.get("code"));
                    row.put("transaction_locked", DealerStatusService.locked(context.dealer));
                    row.put(
                            "can_edit",
                            "DRAFT".equals(row.get("status"))
                                    && Sql.id(row.get("owner_id")) == actor
                                    && context.access.allows(
                                            portal(context.access)
                                                    ? "PORTAL_ORDER_WRITE"
                                                    : "ORDER_WRITE"));
                    row.put(
                            "can_takeover",
                            "DRAFT".equals(row.get("status"))
                                    && Sql.id(row.get("owner_id")) != actor
                                    && context.access.roles().contains("SALES_MANAGER")
                                    && context.access.allows("ORDER_WRITE"));
                    row.put(
                            "delivery_address",
                            Sql.one(
                                    connection,
                                    "SELECT address,recipient,phone,directions,status FROM"
                                            + " dealer_addresses WHERE id=? AND dealer_id=?",
                                    row.get("address_id"),
                                    row.get("dealer_id")));
                    row.put(
                            "lines",
                            Sql.query(
                                    connection,
                                    "SELECT l.*,p.sku,p.name,u.name unit_name FROM order_lines l"
                                        + " JOIN products p ON p.id=l.product_id JOIN product_units"
                                        + " u ON u.id=l.unit_id WHERE l.order_id=? ORDER BY"
                                        + " l.position",
                                    id));
                    row.put(
                            "formatted",
                            Map.of(
                                    "gross",
                                    MoneyDisplay.format(row.get("gross_total")),
                                    "discount",
                                    MoneyDisplay.format(row.get("discount_total")),
                                    "net",
                                    MoneyDisplay.format(row.get("net_total"))));
                    @SuppressWarnings("unchecked")
                    var detailLines = (List<Map<String, Object>>) row.get("lines");
                    for (var line : detailLines) {
                        line.put(
                                "formatted",
                                Map.of(
                                        "selling",
                                        MoneyDisplay.format(line.get("selling_price")),
                                        "gross",
                                        MoneyDisplay.format(line.get("gross")),
                                        "discount",
                                        MoneyDisplay.format(line.get("discount")),
                                        "net",
                                        MoneyDisplay.format(line.get("net"))));
                    }
                    var approvals =
                            Sql.query(
                                    connection,
                                    "SELECT"
                                        + " ar.status,ar.request_reason,ar.requested_at,ar.decision_reason,ar.decided_at,requester.full_name"
                                        + " requested_name,decider.full_name"
                                        + " decided_name,JSON_UNQUOTE(JSON_EXTRACT(request_snapshot,'$.formatted.gross'))"
                                        + " requested_gross,JSON_UNQUOTE(JSON_EXTRACT(request_snapshot,'$.formatted.discount'))"
                                        + " requested_discount,JSON_UNQUOTE(JSON_EXTRACT(request_snapshot,'$.formatted.net'))"
                                        + " requested_net FROM order_approval_requests ar JOIN"
                                        + " users requester ON requester.id=ar.requested_by LEFT"
                                        + " JOIN users decider ON decider.id=ar.decided_by WHERE"
                                        + " ar.order_id=?",
                                    id);
                    row.put("can_review", false);
                    if (!approvals.isEmpty()) {
                        row.put("approval", approvals.get(0));
                        row.put(
                                "can_review",
                                "PENDING_APPROVAL".equals(row.get("status"))
                                        && !"STAFF_CONFIRMATION".equals(row.get("pending_reason"))
                                        && canReview(context, actor, request(connection, id)));
                    }
                    row.put(
                            "can_confirm_portal",
                            "PENDING_APPROVAL".equals(row.get("status"))
                                    && "STAFF_CONFIRMATION".equals(row.get("pending_reason"))
                                    && canConfirmPortal(context, actor));
                    row.put(
                            "can_cancel",
                            canCancel(context)
                                    && !"CANCELLED".equals(row.get("status"))
                                    && Sql.query(
                                                    connection,
                                                    "SELECT id FROM mvp_shipments WHERE order_id=?"
                                                            + " LIMIT 1",
                                                    id)
                                            .isEmpty());
                    var holds = OrderHoldDisplay.load(connection, id, clock.instant());
                    row.put("holds", holds);
                    row.put(
                            "can_renew_holds",
                            Boolean.TRUE.equals(holds.get("needsRenewal"))
                                    && "SUBMITTED".equals(row.get("status"))
                                    && !"COMPLETE".equals(row.get("fulfillment_state"))
                                    && canRenew(context, actor));
                    row.put(
                            "can_approve_renewal",
                            Boolean.TRUE.equals(holds.get("needsRenewal"))
                                    && "SUBMITTED".equals(row.get("status"))
                                    && !"COMPLETE".equals(row.get("fulfillment_state"))
                                    && canReview(
                                            context,
                                            actor,
                                            Map.of("requested_by", context.order.get("owner_id"))));
                    row.put(
                            "history",
                            Sql.query(
                                    connection,
                                    "SELECT h.event_type,h.reason,h.occurred_at,u.full_name"
                                        + " actor_name FROM mvp_order_history h JOIN users u ON"
                                        + " u.id=h.actor_id WHERE h.order_id=? UNION ALL SELECT"
                                        + " a.event_type,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.after_values,'$.reason')),''),a.occurred_at,u.full_name"
                                        + " FROM audit_logs a JOIN users u ON u.id=a.actor_user_id"
                                        + " WHERE a.object_type='ORDER' AND a.object_id=? AND"
                                        + " a.event_type='ORDER_DRAFT_TAKEN_OVER' ORDER BY"
                                        + " occurred_at",
                                    id,
                                    Long.toString(id)));
                    row.put(
                            "invoices",
                            !portal(context.access) && context.access.allows("INVOICE_READ")
                                    ? Sql.query(
                                            connection,
                                            "SELECT i.id,i.number,i.invoice_date FROM mvp_invoices"
                                                + " i JOIN mvp_shipments s ON s.id=i.shipment_id"
                                                + " WHERE s.order_id=? ORDER BY i.id",
                                            id)
                                    : List.of());
                    @SuppressWarnings("unchecked")
                    var history = (List<Map<String, Object>>) row.get("history");
                    for (var event : history) {
                        event.put(
                                "label",
                                switch (Sql.text(event.get("event_type"))) {
                                    case "ORDER_DRAFT_TAKEN_OVER" -> "Tiếp quản nháp";
                                    case "REQUESTED" -> "Gửi chờ tiếp nhận";
                                    case "RETURNED", "LEGACY_RETURNED" -> "Trả lại sửa";
                                    case "APPROVED", "LEGACY_APPROVED" -> "Duyệt ngoại lệ";
                                    case "REJECTED", "LEGACY_REJECTED" -> "Từ chối";
                                    case "CANCELLED", "LEGACY_CANCELLED" -> "Hủy đơn";
                                    case "STAFF_CONFIRMED" -> "NVKD xác nhận";
                                    case "ESCALATED" -> "Chuyển quản lý duyệt";
                                    default -> "Yêu cầu tiếp nhận trước đây";
                                });
                    }
                    if (portal(context.access)) {
                        for (var event : history) {
                            event.remove("actor_name");
                        }
                        if (row.get("approval") instanceof Map<?, ?> approval) {
                            approval.remove("requested_name");
                            approval.remove("decided_name");
                        }
                        row.keySet()
                                .removeAll(
                                        Set.of(
                                                "created_by",
                                                "owner_id",
                                                "creation_key",
                                                "creation_hash",
                                                "submit_key",
                                                "user_version"));
                        row.put("can_takeover", false);
                    }
                    return row;
                });
    }

    // Chứa các bộ lọc dùng khi tìm danh sách đơn hàng.
    public record Filter(
            String query,
            String status,
            Long dealer,
            Long staff,
            Long territory,
            LocalDate from,
            LocalDate to) {

        public Filter {
            if (from != null && (from.getYear() < 1000 || from.getYear() > 9998)
                    || to != null && (to.getYear() < 1000 || to.getYear() > 9998)
                    || from != null && to != null && from.isAfter(to)) {
                throw new IllegalArgumentException("Khoảng ngày tạo đơn không hợp lệ.");
            }
            for (Long id : Arrays.asList(dealer, staff, territory)) {
                if (id != null && id <= 0) {
                    throw new IllegalArgumentException("Bộ lọc không hợp lệ.");
                }
            }
        }
    }

    // Chứa trang đơn hàng và tổng giá trị của kết quả tìm kiếm.
    public record SearchReport(PageResult<Map<String, Object>> page, BigDecimal totalAmount) {}

    // Tìm đơn hàng theo bộ lọc và phân trang.
    public PageResult<Map<String, Object>> search(
            long actor, String query, String status, Long dealer, PageRequest request) {
        return searchReport(
                        actor, new Filter(query, status, dealer, null, null, null, null), request)
                .page();
    }

    // Tìm các đơn theo bộ lọc và tổng hợp giá trị kết quả.
    public SearchReport searchReport(long actor, Filter selected, PageRequest request) {
        String query = selected.query() == null ? "" : selected.query().trim();
        if (query.length() > 150) {
            throw new IllegalArgumentException("Tìm kiếm tối đa 150 ký tự.");
        }
        String term = "%" + query + "%";
        String state = selected.status() == null ? "" : selected.status();
        if (!state.isEmpty()
                && !Set.of("DRAFT", "PENDING_APPROVAL", "SUBMITTED", "REJECTED", "CANCELLED")
                        .contains(state)) {
            throw new IllegalArgumentException("Trạng thái đơn không hợp lệ.");
        }
        return Sql.snapshot(
                source,
                connection -> {
                    var access = reader(connection, actor);
                    boolean portal = portal(access);
                    // List filters remain an intersection with the authorized scope: an outside
                    // dealer yields zero rows.
                    // Detail/command endpoints still reject outside-scope identifiers explicitly.
                    String scope = portal ? " d.id=? " : " (? OR d.primary_staff_id=?) ";
                    String filter =
                            " FROM orders o JOIN dealers d ON d.id=o.dealer_id WHERE "
                                    + scope
                                    + " AND (?='' OR o.status=?) AND (? IS NULL OR o.dealer_id=?)"
                                    + " AND (d.code LIKE ? OR d.name LIKE ? OR o.number LIKE ? OR"
                                    + " CAST(o.id AS CHAR) LIKE ?) AND (? IS NULL OR"
                                    + " d.primary_staff_id=?) AND (? IS NULL OR d.territory_id=?)"
                                    + " AND (? IS NULL OR o.created_at>=?) AND (? IS NULL OR"
                                    + " o.created_at<?)";
                    var args = new ArrayList<Object>();
                    if (portal) {
                        args.add(PortalAccountService.linked(connection, actor));
                    } else {
                        args.add(all(access));
                        args.add(actor);
                    }
                    Object from =
                            selected.from() == null
                                    ? null
                                    : Timestamp.from(
                                            selected.from()
                                                    .atStartOfDay(VietnamTime.ZONE)
                                                    .toInstant());
                    Object until =
                            selected.to() == null
                                    ? null
                                    : Timestamp.from(
                                            selected.to()
                                                    .plusDays(1)
                                                    .atStartOfDay(VietnamTime.ZONE)
                                                    .toInstant());
                    args.addAll(
                            Arrays.asList(
                                    state,
                                    state,
                                    selected.dealer(),
                                    selected.dealer(),
                                    term,
                                    term,
                                    term,
                                    term,
                                    selected.staff(),
                                    selected.staff(),
                                    selected.territory(),
                                    selected.territory(),
                                    from,
                                    from,
                                    until,
                                    until));
                    var summary =
                            Sql.one(
                                    connection,
                                    "SELECT COUNT(*) total,COALESCE(SUM(CASE WHEN o.status IN"
                                        + " ('SUBMITTED','PENDING_APPROVAL') THEN o.net_total ELSE"
                                        + " 0 END),0) total_amount"
                                            + filter,
                                    args.toArray());
                    long total = Sql.id(summary.get("total"));
                    var paging = request.clamp(total);
                    var parameters = new ArrayList<Object>(args);
                    parameters.add(paging.pageSize());
                    parameters.add(paging.offset());
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT"
                                        + " o.id,o.number,o.status,o.source,o.pending_reason,o.fulfillment_state,o.desired_delivery,o.net_total,o.version,o.created_at,d.code"
                                        + " dealer_code,d.name dealer_name,d.transaction_locked"
                                            + filter
                                            + " ORDER BY o.id DESC LIMIT ? OFFSET ?",
                                    parameters.toArray());
                    for (var row : rows) {
                        row.put("net_formatted", MoneyDisplay.format(row.get("net_total")));
                    }
                    return new SearchReport(
                            new PageResult<>(rows, total, paging.page(), paging.pageSize()),
                            (BigDecimal) summary.get("total_amount"));
                });
    }

    // Lấy các lựa chọn cho bộ lọc đơn hàng.
    public Map<String, List<Map<String, Object>>> filterOptions(long actor) {
        return Sql.snapshot(
                source,
                connection -> {
                    var access = reader(connection, actor);
                    var parameters = new ArrayList<Object>();
                    String scope;
                    if (portal(access)) {
                        scope = "d.id=?";
                        parameters.add(PortalAccountService.linked(connection, actor));
                    } else {
                        scope = "(? OR d.primary_staff_id=?)";
                        parameters.add(all(access));
                        parameters.add(actor);
                    }
                    var dealers =
                            Sql.query(
                                    connection,
                                    "SELECT d.id,d.code,d.name,d.primary_staff_id"
                                        + " staff_id,u.full_name staff_name,d.territory_id,t.name"
                                        + " territory_name FROM dealers d JOIN users u ON"
                                        + " u.id=d.primary_staff_id JOIN territories t ON"
                                        + " t.id=d.territory_id WHERE "
                                            + scope
                                            + " ORDER BY d.name,d.id",
                                    parameters.toArray());
                    var staff = new LinkedHashMap<Long, Map<String, Object>>();
                    var territories = new LinkedHashMap<Long, Map<String, Object>>();
                    for (var d : dealers) {
                        long user = Sql.id(d.get("staff_id")), area = Sql.id(d.get("territory_id"));
                        staff.putIfAbsent(user, Map.of("id", user, "name", d.get("staff_name")));
                        territories.putIfAbsent(
                                area, Map.of("id", area, "name", d.get("territory_name")));
                    }
                    return Map.of(
                            "dealers",
                            dealers,
                            "staff",
                            List.copyOf(staff.values()),
                            "territories",
                            List.copyOf(territories.values()));
                });
    }

    // Lấy các địa chỉ giao hàng của đại lý được phép tạo đơn.
    public List<Map<String, Object>> addresses(long actor, long dealer) {
        return Sql.transaction(
                source,
                connection -> {
                    var access = reader(connection, actor);
                    scoped(connection, actor, access, dealer, false);
                    return Sql.query(
                            connection,
                            "SELECT id,address,recipient,phone,is_default FROM dealer_addresses"
                                    + " WHERE dealer_id=? AND status='ACTIVE' ORDER BY is_default"
                                    + " DESC,id",
                            dealer);
                });
    }

    // Lấy các đơn vị tính của sản phẩm.
    public List<Map<String, Object>> units(long actor, long dealer, long product) {
        return Sql.transaction(
                source,
                connection -> {
                    var access = reader(connection, actor);
                    var d = scoped(connection, actor, access, dealer, false);
                    if (portal(access)) {
                        PortalCatalogService.requireOffer(connection, actor, product);
                    }
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT id,name,factor,is_base FROM product_units WHERE"
                                            + " product_id=? AND (warehouse_id IS NULL OR"
                                            + " warehouse_id=?) ORDER BY is_base DESC,name,id",
                                    product,
                                    d.get("warehouse_id"));
                    for (var row : rows) {
                        row.put("factorFormatted", QuantityDisplay.format(row.get("factor")));
                        row.put("factor", ((BigDecimal) row.get("factor")).toPlainString());
                    }
                    return rows;
                });
    }

    // Tiếp quản đơn nháp theo quyền của người thao tác.
    public void takeover(long actor, long id, long version, String reason) {
        String why = CatalogValidation.text(reason, 1000, "Lý do tiếp quản");
        Sql.transaction(
                source,
                connection -> {
                    var context = context(connection, actor, id, true);
                    writer(context.access);
                    if (!context.access.roles().contains("SALES_MANAGER")) {
                        throw new SecurityException();
                    }
                    editable(context.order, version);
                    if (Sql.id(context.order.get("owner_id")) == actor) {
                        throw FieldValidationException.field(
                                "form", "Bạn đang phụ trách nháp này.");
                    }
                    Sql.update(
                            connection,
                            "UPDATE orders SET owner_id=?,version=version+1 WHERE id=?",
                            actor,
                            id);
                    Sql.update(connection, "DELETE FROM order_quote_tickets WHERE order_id=?", id);
                    var after =
                            new LinkedHashMap<>(
                                    Sql.one(connection, "SELECT * FROM orders WHERE id=?", id));
                    after.put("reason", why);
                    AuditService.record(
                            connection,
                            actor,
                            "ORDER_DRAFT_TAKEN_OVER",
                            "ORDER",
                            id,
                            context.order,
                            after);
                    return null;
                });
    }

    // Kiểm tra dữ liệu và tạo bản xem trước để người dùng xác nhận.
    public Quote preview(long actor, Input input) {
        validate(input);
        return Sql.transaction(
                source,
                connection -> {
                    var access = reader(connection, actor);
                    writer(access);
                    var dealer = scoped(connection, actor, access, input.dealer, true);
                    return calculate(
                            connection, dealer, input.address, input.delivery, input.lines);
                });
    }

    // Tính hoặc lấy giá áp dụng cho dữ liệu đầu vào.
    public Quote quote(long actor, long id) {
        return Sql.transaction(
                source,
                connection -> {
                    var context = context(connection, actor, id, true);
                    writer(context.access);
                    owner(context.order, actor);
                    editable(context.order, Sql.id(context.order.get("version")));
                    var quote =
                            calculate(
                                    connection,
                                    context.dealer,
                                    Sql.id(context.order.get("address_id")),
                                    LocalDate.parse(
                                            context.order.get("desired_delivery").toString()),
                                    inputs(connection, id));
                    return quote.valid()
                            ? ticket(
                                    connection,
                                    actor,
                                    id,
                                    Sql.id(context.order.get("version")),
                                    quote,
                                    false)
                            : quote;
                });
    }

    // Tính báo giá dự kiến cho đơn trong phạm vi người thao tác.
    public Quote estimate(long actor, long id) {
        return Sql.transaction(
                source,
                connection -> {
                    var context = context(connection, actor, id, true);
                    if (!"DRAFT".equals(context.order.get("status"))) {
                        throw new IllegalArgumentException("Đơn đã gửi dùng số tiền snapshot.");
                    }
                    return calculate(
                            connection,
                            context.dealer,
                            Sql.id(context.order.get("address_id")),
                            LocalDate.parse(context.order.get("desired_delivery").toString()),
                            inputs(connection, id));
                });
    }

    private Quote ticket(
            Connection connection,
            long actor,
            long id,
            long version,
            Quote quote,
            boolean confirmation)
            throws SQLException {
        return ticket(connection, actor, id, version, quote, confirmation, "SUBMIT");
    }

    // Lưu mã xác nhận gắn với người thao tác, phiên bản đơn và báo giá hiện tại.
    private Quote ticket(
            Connection connection,
            long actor,
            long id,
            long version,
            Quote quote,
            boolean confirmation,
            String purpose)
            throws SQLException {
        String token = UUID.randomUUID().toString();
        Sql.update(
                connection,
                "DELETE FROM order_quote_tickets WHERE order_id=? AND expires_at<?",
                id,
                Timestamp.from(clock.instant()));
        Sql.update(
                connection,
                "INSERT INTO"
                    + " order_quote_tickets(token,order_id,actor_id,order_version,digest,requires_confirmation,expires_at,purpose)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                token,
                id,
                actor,
                version,
                quote.digest,
                confirmation,
                Timestamp.from(clock.instant().plusSeconds(900)),
                purpose);
        return new Quote(
                quote.date,
                quote.lines,
                quote.gross,
                quote.discount,
                quote.net,
                quote.errors,
                quote.warning,
                quote.digest,
                token,
                confirmation,
                quote.controls);
    }

    // Tính giá, chiết khấu và các điều kiện cần xác nhận của đơn.
    private Quote calculate(
            Connection connection,
            Map<String, Object> dealer,
            long address,
            LocalDate delivery,
            List<LineInput> input)
            throws SQLException {
        LocalDate date = clock.instant().atZone(VietnamTime.ZONE).toLocalDate();
        var errors = new LinkedHashMap<String, String>();
        if (delivery.isBefore(date)) {
            errors.put("delivery", "Ngày giao đã qua. Trả lại sửa ngày giao trước khi tiếp nhận.");
        }
        var signature = new ArrayList<Object>();
        signature.add(date);
        signature.add(dealer);
        signature.add(delivery);
        try {
            signature.add(address(connection, dealer, address));
        } catch (IllegalArgumentException exception) {
            errors.put("address", exception.getMessage());
        }
        Long warehouse = nullableId(dealer.get("warehouse_id"));
        long group = Sql.id(dealer.get("group_id"));
        if (warehouse == null) {
            errors.put("warehouse", "Cần kho phục vụ trước khi gửi. Nháp vẫn được giữ.");
        }
        signature.add(
                Sql.one(
                        connection,
                        "SELECT id,name FROM customer_groups WHERE id=? FOR SHARE",
                        group));
        if (warehouse != null) {
            signature.add(
                    Sql.one(
                            connection,
                            "SELECT id,name FROM warehouses WHERE id=? FOR SHARE",
                            warehouse));
        }
        productLocks(connection, input);
        var conversions = new TreeMap<Integer, UnitService.Conversion>();
        var products = new HashMap<Long, Map<String, Object>>();
        var prices = new HashMap<Long, PricingService.Quote>();
        var quantities = new TreeMap<Long, List<BigDecimal>>();
        for (int i = 0; i < input.size(); i++) {
            var orderLine = input.get(i);
            try {
                var p =
                        Sql.one(
                                connection,
                                "SELECT id,sku,name,status,version FROM products WHERE id=?",
                                orderLine.product);
                if (!"ACTIVE".equals(p.get("status"))) {
                    throw new IllegalArgumentException("Sản phẩm đã ngừng bán.");
                }
                products.put(orderLine.product, p);
                signature.add(p);
                var conversion =
                        UnitService.orderConversion(
                                connection,
                                orderLine.product,
                                orderLine.unit,
                                orderLine.quantity,
                                warehouse);
                conversions.put(i, conversion);
                quantities
                        .computeIfAbsent(orderLine.product, k -> new ArrayList<>())
                        .add(conversion.baseQuantity());
                signature.add(conversion);
                if (!prices.containsKey(orderLine.product)) {
                    PricingService.Quote price;
                    try {
                        price =
                                PricingService.orderQuote(
                                        connection, group, orderLine.product, date);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(
                                "Chưa có giá áp dụng cho SKU trong ngày gửi tại Việt Nam.");
                    }
                    prices.put(orderLine.product, price);
                    signature.add(price);
                }
            } catch (IllegalArgumentException e) {
                errors.put("line" + i, e.getMessage());
            }
        }
        var policies = new HashMap<Long, DiscountMath.Selection>();
        for (var item : quantities.entrySet()) {
            if (prices.containsKey(item.getKey())) {
                try {
                    var choice =
                            DiscountService.select(
                                    connection,
                                    item.getKey(),
                                    group,
                                    date,
                                    item.getValue(),
                                    prices.get(item.getKey()).sellingPrice());
                    policies.put(item.getKey(), choice);
                    signature.add(choice);
                } catch (IllegalArgumentException e) {
                    for (int i = 0; i < input.size(); i++) {
                        if (input.get(i).product == item.getKey()) {
                            errors.put("line" + i, e.getMessage());
                        }
                    }
                }
            }
        }
        var lines = new ArrayList<LineQuote>();
        BigDecimal gross = BigDecimal.ZERO.setScale(4), discount = gross, net = gross;
        for (int i = 0; i < input.size(); i++) {
            if (errors.containsKey("line" + i)) {
                continue;
            }
            var l = input.get(i);
            var conv = conversions.get(i);
            var price = prices.get(l.product);
            var choice = policies.get(l.product);
            if (conv == null || price == null || choice == null) {
                continue;
            }
            try {
                var g = DiscountMath.lineGross(conv.baseQuantity(), price.sellingPrice());
                var d =
                        DiscountMath.lineDiscount(
                                conv.baseQuantity(), price.sellingPrice(), choice.tier());
                var n = DiscountMath.money(g.subtract(d));
                var p = products.get(l.product);
                lines.add(
                        new LineQuote(
                                i,
                                l.product,
                                Sql.text(p.get("sku")),
                                Sql.text(p.get("name")),
                                conv,
                                price,
                                choice,
                                g,
                                d,
                                n));
                gross = DiscountMath.money(gross.add(g));
                discount = DiscountMath.money(discount.add(d));
                net = DiscountMath.money(net.add(n));
            } catch (IllegalArgumentException e) {
                errors.put("line" + i, e.getMessage());
            }
        }
        String warning =
                DealerStatusService.locked(dealer)
                        ? "Đại lý hiện bị khóa: chỉ xử lý đơn đã tồn tại, không tạo đơn mới."
                        : !"ACTIVE".equals(dealer.get("status"))
                                ? "Đại lý đã ngừng giao dịch; đây là đơn đã tồn tại."
                                : "";
        var controls = new LinkedHashMap<String, Object>();
        var available = new LinkedHashMap<String, Object>();
        if (warehouse != null) {
            for (var entry : quantities.entrySet()) {
                var stock =
                        StockService.snapshot(
                                connection, warehouse, entry.getKey(), clock.instant());
                available.put(Long.toString(entry.getKey()), stock.available().toPlainString());
                BigDecimal requested =
                        entry.getValue().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                if (requested.compareTo(stock.available()) > 0) {
                    for (int i = 0; i < input.size(); i++) {
                        if (input.get(i).product == entry.getKey()) {
                            errors.put(
                                    "line" + i,
                                    "Vượt tồn khả dụng; tối đa "
                                            + stock.available().stripTrailingZeros().toPlainString()
                                            + " đơn vị cơ sở.");
                        }
                    }
                }
            }
        }
        var credit = CreditService.snapshot(connection, Sql.id(dealer.get("id")), clock.instant());
        controls.put("available", available);
        controls.put("credit", credit.display());
        controls.put(
                "creditApproval",
                credit.netReceivable().add(credit.commitment()).add(net).compareTo(credit.limit())
                        > 0);
        if (credit.overdue()) {
            errors.put("dealer", "Đại lý có khoản quá hạn chưa thanh toán.");
        }
        signature.add(lines);
        signature.add(controls);
        signature.add(errors);
        return new Quote(
                date,
                List.copyOf(lines),
                gross,
                discount,
                net,
                Collections.unmodifiableMap(errors),
                warning,
                digest(signature),
                "",
                false,
                Collections.unmodifiableMap(controls));
    }

    // Kiểm tra giá, tồn và công nợ trước khi gửi hoặc tiếp nhận đơn.
    public Submission submit(
            long actor, long id, long version, String token, String submitKey, boolean confirmed) {
        String key = uuid(submitKey, "submitKey");
        return Sql.transaction(
                source,
                connection -> {
                    var context = context(connection, actor, id, true);
                    writer(context.access);
                    owner(context.order, actor);
                    if (!Sql.query(
                                    connection,
                                    "SELECT id FROM orders WHERE owner_id=? AND submit_key=? AND"
                                            + " id<>?",
                                    actor,
                                    key,
                                    id)
                            .isEmpty()) {
                        throw FieldValidationException.field(
                                "submitKey", "Mã gửi đã dùng cho đơn khác. Hãy tải lại đơn.");
                    }
                    var command =
                            MvpCommands.begin(
                                    connection, actor, "ORDER_SUBMIT", key, id + ":" + version);
                    if (command.replayId() != null) {
                        String status = MvpCommands.resultStatus(connection, command);
                        return new Submission("SUBMITTED".equals(status), id, null, status);
                    }
                    editable(context.order, version);
                    var quote =
                            calculate(
                                    connection,
                                    context.dealer,
                                    Sql.id(context.order.get("address_id")),
                                    LocalDate.parse(
                                            context.order.get("desired_delivery").toString()),
                                    inputs(connection, id));
                    if (!quote.valid()) {
                        return new Submission(false, id, quote);
                    }
                    var tickets =
                            Sql.query(
                                    connection,
                                    "SELECT * FROM order_quote_tickets WHERE token=? AND actor_id=?"
                                            + " AND order_id=? AND order_version=? AND"
                                            + " purpose='SUBMIT'",
                                    uuid(token, "quote"),
                                    actor,
                                    id,
                                    version);
                    if (tickets.isEmpty()
                            || !quote.digest.equals(tickets.get(0).get("digest"))
                            || !Sql.instant(tickets.get(0).get("expires_at"))
                                    .isAfter(clock.instant())) {
                        return new Submission(
                                false, id, ticket(connection, actor, id, version, quote, true));
                    }
                    if (DealerAddressService.isDefault(tickets.get(0).get("requires_confirmation"))
                            && !confirmed) {
                        return new Submission(
                                false, id, ticket(connection, actor, id, version, quote, true));
                    }
                    if (quote.needsApproval() || portal(context.access)) {
                        snapshot(
                                connection,
                                actor,
                                id,
                                context,
                                quote,
                                "PENDING_APPROVAL",
                                key,
                                false);
                        String reason = quote.needsApproval() ? "EXCEPTION" : "STAFF_CONFIRMATION";
                        Sql.update(
                                connection,
                                "UPDATE orders SET pending_reason=? WHERE id=?",
                                reason,
                                id);
                        String requestReason =
                                quote.needsApproval()
                                        ? ((quote.lines.stream().anyMatch(OrderService::belowFloor)
                                                        ? "Giá dưới sàn"
                                                        : "")
                                                + (quote.lines.stream()
                                                                        .anyMatch(
                                                                                OrderService
                                                                                        ::belowFloor)
                                                                && Boolean.TRUE.equals(
                                                                        quote.controls.get(
                                                                                "creditApproval"))
                                                        ? " và "
                                                        : "")
                                                + (Boolean.TRUE.equals(
                                                                quote.controls.get(
                                                                        "creditApproval"))
                                                        ? "Vượt hạn mức"
                                                        : "")
                                                + " cần phê duyệt.")
                                        : "Đơn cổng chờ nhân viên phụ trách xác nhận.";
                        if (Sql.query(
                                        connection,
                                        "SELECT id FROM order_approval_requests WHERE order_id=?"
                                                + " FOR UPDATE",
                                        id)
                                .isEmpty()) {
                            Sql.insert(
                                    connection,
                                    "INSERT INTO"
                                        + " order_approval_requests(order_id,requested_by,request_key,request_digest,request_snapshot,request_reason,requested_at)"
                                        + " VALUES(?,?,?,?,?,?,?)",
                                    id,
                                    actor,
                                    key,
                                    quote.digest,
                                    quoteSnapshot(quote),
                                    requestReason,
                                    Timestamp.from(clock.instant()));
                        } else {
                            Sql.update(
                                    connection,
                                    "UPDATE order_approval_requests SET"
                                        + " requested_by=?,request_key=?,request_digest=?,request_snapshot=?,request_reason=?,requested_at=?,status='PENDING',decision_key=NULL,decided_by=NULL,decision_reason=NULL,decision_snapshot=NULL,decided_at=NULL"
                                        + " WHERE order_id=?",
                                    actor,
                                    key,
                                    quote.digest,
                                    quoteSnapshot(quote),
                                    requestReason,
                                    Timestamp.from(clock.instant()),
                                    id);
                        }
                        history(
                                connection,
                                actor,
                                id,
                                "REQUESTED",
                                Sql.text(request(connection, id).get("request_reason")));
                        documentNumber(connection, id);
                        AuditService.record(
                                connection,
                                actor,
                                "ORDER_APPROVAL_REQUESTED",
                                "ORDER",
                                id,
                                context.order,
                                Sql.one(connection, "SELECT * FROM orders WHERE id=?", id));
                        Sql.update(
                                connection, "DELETE FROM order_quote_tickets WHERE order_id=?", id);
                        MvpCommands.finish(connection, command, id, "PENDING_APPROVAL");
                        return new Submission(false, id, null, "PENDING_APPROVAL");
                    }
                    snapshot(connection, actor, id, context, quote, "SUBMITTED", key, true);
                    StockService.reserve(connection, actor, id, clock.instant());
                    documentNumber(connection, id);
                    AuditService.record(
                            connection,
                            actor,
                            "ORDER_SUBMITTED",
                            "ORDER",
                            id,
                            context.order,
                            Sql.one(connection, "SELECT * FROM orders WHERE id=?", id));
                    Sql.update(connection, "DELETE FROM order_quote_tickets WHERE order_id=?", id);
                    MvpCommands.finish(connection, command, id, "SUBMITTED");
                    return new Submission(true, id, null);
                });
    }

    // Kiểm tra dòng hàng có giá bán thấp hơn giá sàn.
    private static boolean belowFloor(LineQuote line) {
        BigDecimal effective = line.price.sellingPrice();
        if (line.policy.applied()) {
            var tier = line.policy.tier();
            effective =
                    "FIXED".equals(tier.mode())
                            ? effective.subtract(tier.value())
                            : effective.multiply(
                                    BigDecimal.ONE.subtract(
                                            tier.value().divide(new BigDecimal("100"))));
        }
        return effective.compareTo(line.price.floorPrice()) < 0
                || line.net.compareTo(
                                DiscountMath.money(
                                        line.conversion
                                                .baseQuantity()
                                                .multiply(line.price.floorPrice())))
                        < 0;
    }

    private static boolean canReview(Context context, long actor, Map<String, Object> request) {
        return !portal(context.access)
                && context.access.allows("ORDER_APPROVE")
                && context.access.roles().stream()
                        .anyMatch(Set.of("SALES_MANAGER", "DIRECTOR")::contains)
                && Sql.id(context.order.get("created_by")) != actor
                && Sql.id(context.order.get("owner_id")) != actor
                && Sql.id(request.get("requested_by")) != actor
                && ("PORTAL".equals(context.order.get("source"))
                        || Sql.id(context.dealer.get("primary_staff_id")) != actor);
    }

    private static Map<String, Object> request(Connection connection, long id) throws SQLException {
        return Sql.one(
                connection,
                "SELECT * FROM order_approval_requests WHERE order_id=? FOR UPDATE",
                id);
    }

    private void history(Connection connection, long actor, long id, String event, String reason)
            throws SQLException {
        Sql.insert(
                connection,
                "INSERT INTO"
                    + " mvp_order_history(order_id,event_type,actor_id,reason,snapshot,occurred_at)"
                    + " VALUES(?,?,?,?,?,?)",
                id,
                event,
                actor,
                reason,
                AuditService.snapshot(request(connection, id), false),
                Timestamp.from(clock.instant()));
    }

    // Trả đơn về bước sửa và ghi nhận lý do xử lý.
    public void returnForEdit(
            long actor,
            long id,
            long version,
            String requestKey,
            String reason,
            boolean staffConfirmation) {
        String why = MvpCommands.text(reason, 1000, "Ý kiến trả lại");
        Sql.transaction(
                source,
                connection -> {
                    var context = context(connection, actor, id, true);
                    var req = request(connection, id);
                    if (staffConfirmation) {
                        portalConfirmer(connection, context, actor);
                    } else {
                        reviewer(context, actor, req);
                    }
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "ORDER_RETURN_EDIT",
                                    requestKey,
                                    id + ":" + version + ":" + staffConfirmation + ":" + why);
                    if (command.replayId() != null) {
                        return null;
                    }
                    MvpCommands.version(context.order, version);
                    if (!"PENDING_APPROVAL".equals(context.order.get("status"))
                            || staffConfirmation
                                    != "STAFF_CONFIRMATION"
                                            .equals(context.order.get("pending_reason"))) {
                        throw new IllegalArgumentException("Đơn không chờ quyết định này.");
                    }
                    Sql.update(
                            connection,
                            "UPDATE order_approval_requests SET"
                                + " status='RETURNED',decided_by=?,decision_reason=?,decided_at=?"
                                + " WHERE order_id=?",
                            actor,
                            why,
                            Timestamp.from(clock.instant()),
                            id);
                    history(connection, actor, id, "RETURNED", why);
                    Sql.update(
                            connection,
                            "UPDATE orders SET"
                                + " status='DRAFT',pending_reason=NULL,submit_key=NULL,version=version+1"
                                + " WHERE id=?",
                            id);
                    Sql.update(connection, "DELETE FROM order_quote_tickets WHERE order_id=?", id);
                    AuditService.record(
                            connection,
                            actor,
                            "ORDER_RETURNED_FOR_EDIT",
                            "ORDER",
                            id,
                            context.order,
                            Sql.one(connection, "SELECT * FROM orders WHERE id=?", id));
                    MvpCommands.finish(connection, command, id);
                    return null;
                });
    }

    private static void reviewer(Context context, long actor, Map<String, Object> request) {
        if (!canReview(context, actor, request)) {
            throw new SecurityException("Không được tự duyệt hoặc duyệt ngoài quyền của mình.");
        }
    }

    private static boolean canCancel(Context context) {
        return !portal(context.access)
                && context.access.allows("ORDER_CANCEL")
                && context.access.roles().stream()
                        .anyMatch(Set.of("SALES", "SALES_MANAGER")::contains);
    }

    private static boolean canRenew(Context context, long actor) {
        return "PORTAL".equals(context.order.get("source"))
                ? canConfirmPortal(context, actor)
                : !portal(context.access)
                        && context.access.allows("ORDER_WRITE")
                        && context.access.roles().stream()
                                .anyMatch(Set.of("SALES", "SALES_MANAGER")::contains);
    }

    // Kiểm tra quyền và gia hạn hoặc giữ lại hàng cho đơn.
    public long renewReservation(
            long actor,
            long id,
            long version,
            String requestKey,
            boolean approveException,
            String reason) {
        String why =
                approveException
                        ? MvpCommands.text(reason, 1000, "Lý do duyệt giữ hàng lại")
                        : "Giữ lại phần đơn chưa giao.";
        return Sql.transaction(
                source,
                connection -> {
                    var context = context(connection, actor, id, true);
                    if (approveException) {
                        reviewer(
                                context,
                                actor,
                                Map.of("requested_by", context.order.get("owner_id")));
                    } else {
                        if (!canRenew(context, actor)) {
                            throw new SecurityException(
                                    "Không được giữ hàng lại cho đơn ngoài quyền.");
                        }
                        if ("PORTAL".equals(context.order.get("source"))) {
                            portalConfirmer(connection, context, actor);
                        }
                    }
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "ORDER_RENEW_HOLDS",
                                    requestKey,
                                    id + ":" + version + ":" + approveException + ":" + why);
                    if (command.replayId() != null) {
                        return command.replayId();
                    }
                    MvpCommands.version(context.order, version);
                    if (!"SUBMITTED".equals(context.order.get("status"))) {
                        throw new IllegalArgumentException(
                                "Chỉ giữ lại hàng cho đơn đã tiếp nhận.");
                    }
                    BigDecimal amount = BigDecimal.ZERO, quantity = BigDecimal.ZERO;
                    var now = clock.instant();
                    for (var line :
                            Sql.query(
                                    connection,
                                    "SELECT id,base_quantity,net FROM order_lines WHERE order_id=?"
                                            + " ORDER BY product_id,position",
                                    id)) {
                        long lineId = Sql.id(line.get("id"));
                        BigDecimal shipped =
                                CreditService.sum(
                                        connection,
                                        "SELECT COALESCE(SUM(quantity-returned_quantity),0) amount"
                                                + " FROM mvp_shipment_lines WHERE order_line_id=?",
                                        lineId);
                        BigDecimal held =
                                CreditService.sum(
                                        connection,
                                        "SELECT COALESCE(SUM(quantity-consumed-released),0) amount"
                                                + " FROM mvp_holds WHERE order_line_id=? AND"
                                                + " (protected_at IS NOT NULL OR expires_at>?)",
                                        lineId,
                                        Timestamp.from(now));
                        BigDecimal remaining =
                                MvpCommands.decimal(line.get("base_quantity"))
                                        .subtract(shipped)
                                        .subtract(held);
                        if (remaining.signum() < 0) {
                            throw new IllegalStateException("Lượng xử lý vượt đơn.");
                        }
                        quantity = quantity.add(remaining);
                        amount =
                                amount.add(
                                        MvpCommands.share(
                                                MvpCommands.decimal(line.get("net")),
                                                remaining,
                                                MvpCommands.decimal(line.get("base_quantity"))));
                    }
                    if (quantity.signum() == 0) {
                        throw new IllegalArgumentException("Phần đơn chưa giao đã được giữ đủ.");
                    }
                    var credit =
                            CreditService.snapshot(
                                    connection, Sql.id(context.dealer.get("id")), now);
                    if (credit.overdue()) {
                        throw new IllegalArgumentException(
                                "Đại lý có nợ quá hạn; không giữ hàng lại.");
                    }
                    if (!approveException
                            && credit.netReceivable()
                                            .add(credit.commitment())
                                            .add(amount)
                                            .compareTo(credit.limit())
                                    > 0) {
                        throw new IllegalArgumentException(
                                "Giữ hàng lại vượt hạn mức; cần quản lý duyệt ngoại lệ.");
                    }
                    StockService.reserve(connection, actor, id, now);
                    Sql.update(
                            connection,
                            "UPDATE orders SET fulfillment_state='RESERVED',version=version+1 WHERE"
                                    + " id=?",
                            id);
                    AuditService.record(
                            connection,
                            actor,
                            approveException
                                    ? "ORDER_HOLDS_RENEWAL_APPROVED"
                                    : "ORDER_HOLDS_RENEWED",
                            "ORDER",
                            id,
                            context.order,
                            Map.of("reason", why, "quantity", quantity, "commitment", amount));
                    return MvpCommands.finish(connection, command, id);
                });
    }

    // Kiểm tra trạng thái, hủy đơn và giải phóng phần giữ hàng được phép.
    public void cancel(long actor, long id, long version, String requestKey, String reason) {
        String why = MvpCommands.text(reason, 1000, "Lý do hủy");
        Sql.transaction(
                source,
                connection -> {
                    var context = context(connection, actor, id, true);
                    if (!canCancel(context)) {
                        throw new SecurityException();
                    }
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "ORDER_CANCEL",
                                    requestKey,
                                    id + ":" + version + ":" + why);
                    if (command.replayId() != null) {
                        return null;
                    }
                    MvpCommands.version(context.order, version);
                    if ("CANCELLED".equals(context.order.get("status"))) {
                        throw new IllegalArgumentException("Đơn đã hủy.");
                    }
                    if (!Sql.query(
                                    connection,
                                    "SELECT id FROM mvp_shipments WHERE order_id=? LIMIT 1",
                                    id)
                            .isEmpty()) {
                        throw new IllegalArgumentException(
                                "Đơn đã có xuất kho; không được hủy toàn đơn.");
                    }
                    Sql.query(
                            connection,
                            "SELECT l.id FROM mvp_lots l WHERE l.id IN (SELECT h.lot_id FROM"
                                    + " mvp_holds h WHERE h.order_id=?) ORDER BY"
                                    + " l.product_id,l.received_date,l.id FOR UPDATE",
                            id);
                    Sql.query(
                            connection,
                            "SELECT id FROM mvp_holds WHERE order_id=? ORDER BY id FOR UPDATE",
                            id);
                    Sql.update(
                            connection,
                            "UPDATE mvp_holds SET released=quantity-consumed WHERE order_id=?",
                            id);
                    if (!Sql.query(
                                    connection,
                                    "SELECT id FROM order_approval_requests WHERE order_id=? FOR"
                                            + " UPDATE",
                                    id)
                            .isEmpty()) {
                        Sql.update(
                                connection,
                                "UPDATE order_approval_requests SET"
                                    + " status='CANCELLED',decided_by=?,decision_reason=?,decided_at=?"
                                    + " WHERE order_id=?",
                                actor,
                                why,
                                Timestamp.from(clock.instant()),
                                id);
                        history(connection, actor, id, "CANCELLED", why);
                    }
                    Sql.update(
                            connection,
                            "UPDATE orders SET"
                                + " status='CANCELLED',fulfillment_state='CANCELLED',pending_reason=NULL,cancel_reason=?,version=version+1"
                                + " WHERE id=?",
                            why,
                            id);
                    Sql.update(connection, "DELETE FROM order_quote_tickets WHERE order_id=?", id);
                    var after =
                            new LinkedHashMap<>(
                                    Sql.one(connection, "SELECT * FROM orders WHERE id=?", id));
                    after.put("reason", why);
                    AuditService.record(
                            connection,
                            actor,
                            "ORDER_CANCELLED",
                            "ORDER",
                            id,
                            context.order,
                            after);
                    MvpCommands.finish(connection, command, id);
                    return null;
                });
    }

    private static boolean canConfirmPortal(Context context, long actor) {
        return !portal(context.access)
                && context.access.allows("ORDER_CONFIRM_PORTAL")
                && context.access.roles().stream()
                        .anyMatch(Set.of("SALES", "SALES_MANAGER")::contains)
                && "PORTAL".equals(context.order.get("source"))
                && Sql.id(context.dealer.get("primary_staff_id")) == actor;
    }

    // Kiểm tra quyền và người phụ trách trước khi xác nhận đơn cổng.
    private static void portalConfirmer(Connection connection, Context context, long actor)
            throws SQLException {
        if (!canConfirmPortal(context, actor)) {
            throw new SecurityException("Chỉ nhân viên phụ trách hiện tại được xác nhận đơn cổng.");
        }
        long creator = Sql.id(context.order.get("created_by"));
        var access = PortalAccountService.access(connection, creator, "PORTAL_ORDER_READ");
        PortalAccountService.scoped(
                connection, creator, access, Sql.id(context.dealer.get("id")), false);
    }

    // Tính lại giá để nhân viên xác nhận đơn từ cổng đại lý.
    public Quote confirmationQuote(long actor, long id) {
        return Sql.transaction(
                source,
                connection -> {
                    var context = context(connection, actor, id, true, true);
                    portalConfirmer(connection, context, actor);
                    if (!"PENDING_APPROVAL".equals(context.order.get("status"))
                            || !"STAFF_CONFIRMATION".equals(context.order.get("pending_reason"))) {
                        throw new IllegalArgumentException("Đơn không chờ NVKD xác nhận.");
                    }
                    var quote =
                            calculate(
                                    connection,
                                    context.dealer,
                                    Sql.id(context.order.get("address_id")),
                                    LocalDate.parse(
                                            context.order.get("desired_delivery").toString()),
                                    inputs(connection, id));
                    return ticket(
                            connection,
                            actor,
                            id,
                            Sql.id(context.order.get("version")),
                            quote,
                            !quote.digest.equals(request(connection, id).get("request_digest")),
                            "APPROVAL");
                });
    }

    // Xác nhận đơn cổng đại lý theo người phụ trách và quyền hiện tại.
    public Submission confirmPortalOrder(
            long actor, long id, long version, String token, String requestKey, boolean confirmed) {
        return Sql.transaction(
                source,
                connection -> {
                    var context = context(connection, actor, id, true, true);
                    portalConfirmer(connection, context, actor);
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "PORTAL_CONFIRM",
                                    requestKey,
                                    id + ":" + version);
                    if (command.replayId() != null) {
                        String status = MvpCommands.resultStatus(connection, command);
                        return new Submission("SUBMITTED".equals(status), id, null, status);
                    }
                    MvpCommands.version(context.order, version);
                    if (!"PENDING_APPROVAL".equals(context.order.get("status"))
                            || !"STAFF_CONFIRMATION".equals(context.order.get("pending_reason"))) {
                        throw new IllegalArgumentException("Đơn không chờ NVKD xác nhận.");
                    }
                    var req = request(connection, id);
                    var quote =
                            calculate(
                                    connection,
                                    context.dealer,
                                    Sql.id(context.order.get("address_id")),
                                    LocalDate.parse(
                                            context.order.get("desired_delivery").toString()),
                                    inputs(connection, id));
                    var tickets =
                            Sql.query(
                                    connection,
                                    "SELECT * FROM order_quote_tickets WHERE token=? AND actor_id=?"
                                            + " AND order_id=? AND order_version=? AND"
                                            + " purpose='APPROVAL'",
                                    uuid(token, "quote"),
                                    actor,
                                    id,
                                    version);
                    if (!quote.valid()) {
                        return new Submission(
                                false,
                                id,
                                ticket(connection, actor, id, version, quote, false, "APPROVAL"));
                    }
                    if (tickets.isEmpty()
                            || !quote.digest.equals(tickets.get(0).get("digest"))
                            || !Sql.instant(tickets.get(0).get("expires_at"))
                                    .isAfter(clock.instant())
                            || DealerAddressService.isDefault(
                                            tickets.get(0).get("requires_confirmation"))
                                    && !confirmed) {
                        return new Submission(
                                false,
                                id,
                                ticket(connection, actor, id, version, quote, true, "APPROVAL"));
                    }
                    if (quote.needsApproval()) {
                        snapshot(
                                connection,
                                actor,
                                id,
                                context,
                                quote,
                                "PENDING_APPROVAL",
                                Sql.text(context.order.get("submit_key")),
                                false);
                        Sql.update(
                                connection,
                                "UPDATE orders SET pending_reason='EXCEPTION' WHERE id=?",
                                id);
                        Sql.update(
                                connection,
                                "UPDATE order_approval_requests SET"
                                    + " request_digest=?,request_snapshot=?,request_reason=? WHERE"
                                    + " order_id=?",
                                quote.digest,
                                quoteSnapshot(quote),
                                "Điều kiện thay đổi khi xác nhận; cần quản lý duyệt giá hoặc hạn"
                                        + " mức.",
                                id);
                        AuditService.record(
                                connection,
                                actor,
                                "PORTAL_ORDER_ESCALATED",
                                "ORDER",
                                id,
                                context.order,
                                Sql.one(connection, "SELECT * FROM orders WHERE id=?", id));
                    } else {
                        accept(connection, actor, id, context, quote);
                        Sql.update(
                                connection,
                                "UPDATE order_approval_requests SET"
                                    + " status='APPROVED',decided_by=?,decision_reason=?,decision_snapshot=?,decided_at=?"
                                    + " WHERE order_id=?",
                                actor,
                                "NVKD phụ trách xác nhận đơn cổng.",
                                quoteSnapshot(quote),
                                Timestamp.from(clock.instant()),
                                id);
                        AuditService.record(
                                connection,
                                actor,
                                "PORTAL_ORDER_CONFIRMED",
                                "ORDER",
                                id,
                                context.order,
                                Sql.one(connection, "SELECT * FROM orders WHERE id=?", id));
                    }
                    MvpCommands.finish(
                            connection,
                            command,
                            id,
                            quote.needsApproval() ? "PENDING_APPROVAL" : "SUBMITTED");
                    Sql.update(connection, "DELETE FROM order_quote_tickets WHERE order_id=?", id);
                    history(
                            connection,
                            actor,
                            id,
                            quote.needsApproval() ? "ESCALATED" : "STAFF_CONFIRMED",
                            quote.needsApproval()
                                    ? "Chuyển quản lý duyệt điều kiện mới."
                                    : "NVKD phụ trách xác nhận đơn cổng.");
                    return new Submission(
                            !quote.needsApproval(),
                            id,
                            null,
                            quote.needsApproval() ? "PENDING_APPROVAL" : "SUBMITTED");
                });
    }

    // Tiếp nhận đơn và ghi các dữ liệu liên quan trong cùng giao dịch.
    private void accept(Connection connection, long actor, long id, Context context, Quote quote)
            throws SQLException {
        snapshot(
                connection,
                actor,
                id,
                context,
                quote,
                "SUBMITTED",
                Sql.text(context.order.get("submit_key")),
                true);
        StockService.reserve(connection, actor, id, clock.instant());
        Sql.update(connection, "UPDATE orders SET pending_reason=NULL WHERE id=?", id);
        documentNumber(connection, id);
    }

    private void documentNumber(Connection connection, long id) throws SQLException {
        if (Sql.one(connection, "SELECT number FROM orders WHERE id=?", id).get("number") == null) {
            Sql.update(
                    connection,
                    "UPDATE orders SET number=? WHERE id=?",
                    MvpCommands.nextNumber(connection, "DH", MvpCommands.date(clock.instant())),
                    id);
        }
    }

    // Lưu nội dung báo giá thành dữ liệu dùng để đối chiếu khi xác nhận.
    private static String quoteSnapshot(Quote quote) {
        var result = new LinkedHashMap<>(quote.reviewDisplay());
        result.remove("token");
        result.remove("confirmation");
        return AuditService.snapshot(result, false);
    }

    // Chốt dữ liệu giá và dòng đơn trong cùng giao dịch tiếp nhận.
    private void snapshot(
            Connection connection,
            long actor,
            long id,
            Context context,
            Quote quote,
            String status,
            String key,
            boolean references)
            throws SQLException {
        var dealer = context.dealer;
        var addr = address(connection, dealer, Sql.id(context.order.get("address_id")));
        long group = Sql.id(dealer.get("group_id")), warehouse = Sql.id(dealer.get("warehouse_id"));
        try {
            Sql.update(
                    connection,
                    "UPDATE orders SET"
                        + " status=?,version=version+1,submit_key=?,submitted_at=?,pricing_date=?,group_id=?,warehouse_id=?,dealer_code_snapshot=?,dealer_name_snapshot=?,address_snapshot=?,recipient_snapshot=?,phone_snapshot=?,directions_snapshot=?,group_name_snapshot=?,warehouse_name_snapshot=?,gross_total=?,discount_total=?,net_total=?"
                        + " WHERE id=?",
                    status,
                    key,
                    "SUBMITTED".equals(status) ? Timestamp.from(clock.instant()) : null,
                    quote.date,
                    group,
                    warehouse,
                    dealer.get("code"),
                    dealer.get("name"),
                    addr.get("address"),
                    addr.get("recipient"),
                    addr.get("phone"),
                    addr.get("directions"),
                    Sql.one(connection, "SELECT name FROM customer_groups WHERE id=?", group)
                            .get("name"),
                    Sql.one(connection, "SELECT name FROM warehouses WHERE id=?", warehouse)
                            .get("name"),
                    quote.gross,
                    quote.discount,
                    quote.net,
                    id);
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1062) {
                throw FieldValidationException.field("submitKey", "Mã gửi đã dùng cho đơn khác.");
            }
            throw exception;
        }
        String reference = "ORDER:" + id;
        var seen = new HashSet<Long>();
        for (var line : quote.lines) {
            var conversion = line.conversion;
            Sql.update(
                    connection,
                    "UPDATE order_lines SET"
                        + " sku_snapshot=?,name_snapshot=?,unit_name_snapshot=?,factor_snapshot=?,unit_version=?,base_quantity=?,selling_price=?,gross=?,discount=?,net=?,policy_name_snapshot=?"
                        + " WHERE order_id=? AND position=?",
                    line.sku,
                    line.name,
                    conversion.unitName(),
                    conversion.factor(),
                    conversion.version(),
                    conversion.baseQuantity(),
                    line.price.sellingPrice(),
                    line.gross,
                    line.discount,
                    line.net,
                    line.policy.applied() ? line.policy.tier().name() : null,
                    id,
                    line.position);
            if (references) {
                UnitService.snapshot(connection, reference + ":" + line.position, conversion);
                if (seen.add(line.product)) {
                    PricingService.snapshot(connection, reference, line.price);
                    DiscountService.snapshot(connection, reference, line.product, line.policy);
                }
            }
        }
    }

    // Tính lại dữ liệu đơn phục vụ bước duyệt ngoại lệ.
    public Quote reviewQuote(long actor, long id) {
        return Sql.transaction(
                source,
                connection -> {
                    var context = context(connection, actor, id, true, true);
                    var req = request(connection, id);
                    reviewer(context, actor, req);
                    if (!"PENDING_APPROVAL".equals(context.order.get("status"))
                            || "STAFF_CONFIRMATION".equals(context.order.get("pending_reason"))) {
                        throw FieldValidationException.field(
                                "form", "Đơn không chờ duyệt ngoại lệ.");
                    }
                    var quote =
                            calculate(
                                    connection,
                                    context.dealer,
                                    Sql.id(context.order.get("address_id")),
                                    LocalDate.parse(
                                            context.order.get("desired_delivery").toString()),
                                    inputs(connection, id));
                    return ticket(
                            connection,
                            actor,
                            id,
                            Sql.id(context.order.get("version")),
                            quote,
                            !quote.digest.equals(req.get("request_digest")),
                            "APPROVAL");
                });
    }

    // Ghi nhận quyết định duyệt hoặc từ chối đơn cùng lý do.
    public Submission decide(
            long actor,
            long id,
            long version,
            String token,
            String decisionKey,
            boolean approve,
            String reason,
            boolean confirmed) {
        String key = uuid(decisionKey, "decisionKey"),
                why = CatalogValidation.text(reason, 1000, "Lý do quyết định");
        return Sql.transaction(
                source,
                connection -> {
                    var context = context(connection, actor, id, true, true);
                    var req = request(connection, id);
                    reviewer(context, actor, req);
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "ORDER_DECIDE",
                                    key,
                                    id + ":" + version + ":" + approve + ":" + why);
                    if (command.replayId() != null) {
                        String status = MvpCommands.resultStatus(connection, command);
                        return new Submission("SUBMITTED".equals(status), id, null, status);
                    }
                    String desired = approve ? "APPROVED" : "REJECTED";
                    if (!"PENDING".equals(req.get("status"))) {
                        throw new vn.codegym.salesinventory.validation.CommandConflictException(
                                "Đơn đã có quyết định. Hãy tải lại.");
                    }
                    MvpCommands.version(context.order, version);
                    if (!"PENDING_APPROVAL".equals(context.order.get("status"))
                            || "STAFF_CONFIRMATION".equals(context.order.get("pending_reason"))) {
                        throw new vn.codegym.salesinventory.validation.CommandConflictException(
                                "Đơn đã thay đổi hoặc đang chờ NVKD xác nhận. Hãy tải lại.");
                    }
                    var quote =
                            calculate(
                                    connection,
                                    context.dealer,
                                    Sql.id(context.order.get("address_id")),
                                    LocalDate.parse(
                                            context.order.get("desired_delivery").toString()),
                                    inputs(connection, id));
                    var tickets =
                            Sql.query(
                                    connection,
                                    "SELECT * FROM order_quote_tickets WHERE token=? AND actor_id=?"
                                            + " AND order_id=? AND order_version=? AND"
                                            + " purpose='APPROVAL'",
                                    uuid(token, "quote"),
                                    actor,
                                    id,
                                    version);
                    if (tickets.isEmpty()
                            || !quote.digest.equals(tickets.get(0).get("digest"))
                            || !Sql.instant(tickets.get(0).get("expires_at"))
                                    .isAfter(clock.instant())
                            || DealerAddressService.isDefault(
                                            tickets.get(0).get("requires_confirmation"))
                                    && !confirmed) {
                        return new Submission(
                                false,
                                id,
                                ticket(connection, actor, id, version, quote, true, "APPROVAL"));
                    }
                    if (approve && !quote.valid()) {
                        return new Submission(
                                false,
                                id,
                                ticket(connection, actor, id, version, quote, false, "APPROVAL"));
                    }
                    if (approve) {
                        accept(connection, actor, id, context, quote);
                        AuditService.record(
                                connection,
                                actor,
                                "ORDER_SUBMITTED",
                                "ORDER",
                                id,
                                context.order,
                                Sql.one(connection, "SELECT * FROM orders WHERE id=?", id));
                    } else {
                        Sql.update(
                                connection,
                                "UPDATE orders SET status='REJECTED',version=version+1 WHERE id=?",
                                id);
                    }
                    try {
                        Sql.update(
                                connection,
                                "UPDATE order_approval_requests SET"
                                    + " status=?,decision_key=?,decided_by=?,decision_reason=?,decision_snapshot=?,decided_at=?"
                                    + " WHERE order_id=?",
                                desired,
                                key,
                                actor,
                                why,
                                quoteSnapshot(quote),
                                Timestamp.from(clock.instant()),
                                id);
                    } catch (SQLException exception) {
                        if (exception.getErrorCode() == 1062) {
                            throw FieldValidationException.field(
                                    "decisionKey", "Mã quyết định đã dùng cho đơn khác.");
                        }
                        throw exception;
                    }
                    var audit =
                            new LinkedHashMap<>(
                                    Sql.one(connection, "SELECT * FROM orders WHERE id=?", id));
                    audit.put("reason", why);
                    history(connection, actor, id, desired, why);
                    AuditService.record(
                            connection,
                            actor,
                            approve ? "ORDER_PRICE_APPROVED" : "ORDER_PRICE_REJECTED",
                            "ORDER",
                            id,
                            context.order,
                            audit);
                    Sql.update(connection, "DELETE FROM order_quote_tickets WHERE order_id=?", id);
                    MvpCommands.finish(connection, command, id, approve ? "SUBMITTED" : "REJECTED");
                    return new Submission(approve, id, null, approve ? "SUBMITTED" : "REJECTED");
                });
    }
}
