package vn.codegym.salesinventory.service;

import java.math.*;
import java.time.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.*;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.dto.*;

/**
 * S2-10, S4-01: Quản lý bảng giá theo thời gian và chốt phiên bản giá dùng cho đơn.
 */
public final class PricingService {

    private final DataSource source;

    public PricingService(DataSource source) {
        this.source = source;
    }

    // Chứa giá bán, giá sàn và phiên bản bảng giá được áp dụng.
    public record Quote(
            long itemId,
            long versionId,
            long productId,
            BigDecimal sellingPrice,
            BigDecimal floorPrice,
            LocalDate date) {

        public boolean requiresApproval(BigDecimal proposed) {
            return CatalogValidation.decimal(proposed, 4, false).compareTo(floorPrice) < 0;
        }
    }

    // Kiểm tra tên và khoảng thời gian hiệu lực của phiên bản bảng giá.
    public static Map<String, String> versionErrors(String name, LocalDate from, LocalDate to) {
        var errors = new LinkedHashMap<String, String>();
        if (name == null || name.trim().isEmpty() || name.trim().length() > 150) {
            errors.put("name", "Tên bảng giá phải có từ 1 đến 150 ký tự.");
        }
        if (from == null || from.getYear() < 1000 || from.getYear() > 9999) {
            errors.put("from", "Ngày bắt đầu không hợp lệ.");
        }
        if (to == null || to.getYear() < 1000 || to.getYear() > 9999) {
            errors.put("to", "Ngày kết thúc không hợp lệ.");
        }
        if (from != null && to != null && to.isBefore(from)) {
            errors.put("to", "Ngày kết thúc phải từ ngày bắt đầu trở đi.");
        }
        return errors;
    }

    // Kiểm tra giá bán và giá sàn trước khi lưu dòng giá.
    public static Map<String, String> priceErrors(BigDecimal selling, BigDecimal floor) {
        var errors = new LinkedHashMap<String, String>();
        try {
            CatalogValidation.decimal(selling, 4, false);
        } catch (IllegalArgumentException invalid) {
            errors.put("selling", invalid.getMessage());
        }
        try {
            CatalogValidation.decimal(floor, 4, false);
        } catch (IllegalArgumentException invalid) {
            errors.put("floor", invalid.getMessage());
        }
        if (errors.isEmpty() && selling.compareTo(floor) < 0) {
            errors.put("selling", "Giá bán bảng giá phải từ giá sàn trở lên.");
        }
        return errors;
    }

    // Báo lỗi nếu dữ liệu phiên bản bảng giá không hợp lệ.
    private static void validVersion(String name, LocalDate from, LocalDate to) {
        var errors = versionErrors(name, from, to);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    public List<Map<String, Object>> groups() {
        return Sql.transaction(
                source,
                connection ->
                        Sql.query(
                                connection,
                                "SELECT id,code,name FROM customer_groups ORDER BY id"));
    }

    // Lấy các phiên bản bảng giá mà người dùng được phép xem.
    public List<Map<String, Object>> versions(long actor) {
        var rows =
                Sql.snapshot(
                        source,
                        connection -> {
                            DealerService.access(connection, actor, "PRICE_READ");
                            return Sql.query(
                                    connection,
                                    "SELECT"
                                        + " v.id,v.group_id,v.name,v.valid_from,v.valid_to,v.parent_id,v.revision,g.name"
                                        + " group_name,EXISTS(SELECT 1 FROM price_order_references"
                                        + " r WHERE r.version_id=v.id) used FROM price_versions v"
                                        + " JOIN customer_groups g ON g.id=v.group_id ORDER BY"
                                        + " v.valid_from DESC,v.id DESC");
                        });
        decorateVersions(rows);
        return rows;
    }

    // Bổ sung thông tin hiển thị cho các phiên bản bảng giá.
    private static void decorateVersions(List<Map<String, Object>> rows) {
        LocalDate today = LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE);
        var format = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        for (var row : rows) {
            LocalDate from = LocalDate.parse(row.get("valid_from").toString()),
                    to = LocalDate.parse(row.get("valid_to").toString());
            row.put("display_from", from.format(format));
            row.put("display_to", to.format(format));
            row.put(
                    "effective_state",
                    today.isBefore(from) ? "UPCOMING" : today.isAfter(to) ? "EXPIRED" : "ACTIVE");
            row.put(
                    "effective_label",
                    today.isBefore(from)
                            ? "Sắp có hiệu lực"
                            : today.isAfter(to) ? "Hết hiệu lực" : "Đang hiệu lực");
        }
    }

    private static String term(String query) {
        String q = query == null ? "" : query.trim();
        return q.length() > 150 ? q.substring(0, 150) : q;
    }

    public PageResult<Map<String, Object>> searchVersions(
            long actor, String query, PageRequest request) {
        return searchVersions(actor, query, request, null);
    }

    // Tìm các phiên bản bảng giá theo bộ lọc và phân trang.
    public PageResult<Map<String, Object>> searchVersions(
            long actor, String query, PageRequest request, Long group) {
        String pattern = "%" + term(query) + "%";
        return Sql.snapshot(
                source,
                connection -> {
                    DealerService.access(connection, actor, "PRICE_READ");
                    String from =
                            " FROM price_versions v JOIN customer_groups g ON g.id=v.group_id WHERE"
                                + " (? IS NULL OR v.group_id=?) AND (v.name LIKE ? OR g.name LIKE ?"
                                + " OR CAST(v.id AS CHAR) LIKE ?)";
                    long total =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total" + from,
                                                    group,
                                                    group,
                                                    pattern,
                                                    pattern,
                                                    pattern)
                                            .get("total"));
                    var page = request.clamp(total);
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT"
                                        + " v.id,v.group_id,v.name,v.valid_from,v.valid_to,v.parent_id,v.revision,g.name"
                                        + " group_name,EXISTS(SELECT 1 FROM price_order_references"
                                        + " r WHERE r.version_id=v.id) used"
                                            + from
                                            + " ORDER BY v.valid_from DESC,v.id DESC LIMIT ? OFFSET"
                                            + " ?",
                                    group,
                                    group,
                                    pattern,
                                    pattern,
                                    pattern,
                                    page.pageSize(),
                                    page.offset());
                    decorateVersions(rows);
                    return new PageResult<>(rows, total, page.page(), page.pageSize());
                });
    }

    // Tìm phiên bản bảng giá ứng với điều kiện đầu vào.
    public Map<String, Object> findVersion(long actor, long id) {
        return Sql.snapshot(
                source,
                connection -> {
                    DealerService.access(connection, actor, "PRICE_READ");
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT"
                                        + " v.id,v.group_id,v.name,v.valid_from,v.valid_to,v.parent_id,v.revision,g.name"
                                        + " group_name,EXISTS(SELECT 1 FROM price_order_references"
                                        + " r WHERE r.version_id=v.id) used FROM price_versions v"
                                        + " JOIN customer_groups g ON g.id=v.group_id WHERE v.id=?",
                                    id);
                    if (rows.isEmpty()) {
                        throw FieldValidationException.field("form", "Không tìm thấy phiên bản.");
                    }
                    decorateVersions(rows);
                    return rows.get(0);
                });
    }

    // Tìm các dòng sản phẩm trong phiên bản bảng giá.
    public PageResult<Map<String, Object>> searchItems(
            long actor, long version, String query, PageRequest request) {
        String pattern = "%" + term(query) + "%";
        return Sql.snapshot(
                source,
                connection -> {
                    DealerService.access(connection, actor, "PRICE_READ");
                    String from =
                            " FROM price_items i JOIN products p ON p.id=i.product_id WHERE"
                                    + " i.version_id=? AND (p.sku LIKE ? OR p.name LIKE ?)";
                    long total =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total" + from,
                                                    version,
                                                    pattern,
                                                    pattern)
                                            .get("total"));
                    var page = request.clamp(total);
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT"
                                        + " i.id,i.product_id,p.sku,p.name,p.base_unit,i.selling_price,i.floor_price"
                                            + from
                                            + " ORDER BY p.sku,i.id LIMIT ? OFFSET ?",
                                    version,
                                    pattern,
                                    pattern,
                                    page.pageSize(),
                                    page.offset());
                    return new PageResult<>(rows, total, page.page(), page.pageSize());
                });
    }

    // Gợi ý phiên bản bảng giá phù hợp với từ khóa.
    public List<Map<String, Object>> suggestVersions(long actor, String query) {
        String q = term(query);
        return Sql.snapshot(
                source,
                connection -> {
                    DealerService.access(connection, actor, "PRICE_READ");
                    if (q.length() < 2) {
                        return List.of();
                    }
                    return Sql.query(
                            connection,
                            "SELECT v.id,CAST(v.id AS CHAR) code,v.name,g.name detail FROM"
                                + " price_versions v JOIN customer_groups g ON g.id=v.group_id"
                                + " WHERE v.name LIKE ? OR g.name LIKE ? OR CAST(v.id AS CHAR) LIKE"
                                + " ? ORDER BY (CAST(v.id AS CHAR)=?) DESC,(v.name LIKE ?)"
                                + " DESC,v.valid_from DESC,v.id DESC LIMIT 10",
                            "%" + q + "%",
                            "%" + q + "%",
                            "%" + q + "%",
                            q,
                            q + "%");
                });
    }

    // Lấy các dòng giá của phiên bản trong phạm vi được xem.
    public List<Map<String, Object>> items(long actor, long version) {
        return Sql.snapshot(
                source,
                connection -> {
                    DealerService.access(connection, actor, "PRICE_READ");
                    return Sql.query(
                            connection,
                            "SELECT"
                                + " i.id,i.product_id,p.sku,p.name,p.base_unit,i.selling_price,i.floor_price"
                                + " FROM price_items i JOIN products p ON p.id=i.product_id WHERE"
                                + " version_id=? ORDER BY p.sku,i.id",
                            version);
                });
    }

    private static void lockGroup(Connection connection, long group) throws SQLException {
        if (Sql.query(connection, "SELECT id FROM customer_groups WHERE id=? FOR UPDATE", group)
                .isEmpty()) {
            throw FieldValidationException.field("group", "Nhóm khách không còn tồn tại.");
        }
    }

    private static void unused(Connection connection, long version) throws SQLException {
        if (!Sql.query(
                        connection,
                        "SELECT id FROM price_order_references WHERE version_id=? LIMIT 1 FOR"
                                + " UPDATE",
                        version)
                .isEmpty()) {
            throw FieldValidationException.field(
                    "form", "Phiên bản đã được dùng. Hãy kế thừa thành phiên bản mới.");
        }
    }

    private static void overlap(
            Connection connection,
            long group,
            long product,
            LocalDate from,
            LocalDate to,
            long excluded)
            throws SQLException {
        if (!Sql.query(
                        connection,
                        "SELECT v.id FROM price_versions v JOIN price_items i ON i.version_id=v.id"
                                + " WHERE v.group_id=? AND i.product_id=? AND v.id<>? AND"
                                + " v.valid_from<=? AND v.valid_to>=? LIMIT 1 FOR UPDATE",
                        group,
                        product,
                        excluded,
                        to,
                        from)
                .isEmpty()) {
            throw FieldValidationException.field(
                    "form", "Thời gian giá chồng nhau cho cùng nhóm khách và SKU.");
        }
    }

    // Tạo bảng giá từ dữ liệu đầu vào.
    public long create(
            long actor, long group, String name, LocalDate from, LocalDate to, Long parent) {
        new AccessService(source).load(actor).require("PRICE_MANAGE");
        validVersion(name, from, to);
        if (parent != null && parent <= 0) {
            throw FieldValidationException.field("parent", "Phiên bản kế thừa không hợp lệ.");
        }
        return Sql.transaction(
                source,
                connection -> {
                    DealerService.access(connection, actor, "PRICE_MANAGE");
                    lockGroup(connection, group);
                    if (parent != null) {
                        var base =
                                Sql.one(
                                        connection,
                                        "SELECT group_id FROM price_versions WHERE id=?",
                                        parent);
                        if (Sql.id(base.get("group_id")) != group) {
                            throw FieldValidationException.field(
                                    "parent", "Phiên bản kế thừa phải cùng nhóm khách.");
                        }
                        for (var item :
                                Sql.query(
                                        connection,
                                        "SELECT product_id FROM price_items WHERE version_id=? FOR"
                                                + " UPDATE",
                                        parent)) {
                            overlap(connection, group, Sql.id(item.get("product_id")), from, to, 0);
                        }
                    }
                    long id =
                            Sql.insert(
                                    connection,
                                    "INSERT INTO"
                                        + " price_versions(group_id,name,valid_from,valid_to,parent_id)"
                                        + " VALUES(?,?,?,?,?)",
                                    group,
                                    name.trim(),
                                    from,
                                    to,
                                    parent);
                    if (parent != null) {
                        Sql.update(
                                connection,
                                "INSERT INTO"
                                    + " price_items(version_id,product_id,selling_price,floor_price)"
                                    + " SELECT ?,product_id,selling_price,floor_price FROM"
                                    + " price_items WHERE version_id=?",
                                id,
                                parent);
                    }
                    if (parent != null) {
                        var oldVersion =
                                Sql.one(
                                        connection,
                                        "SELECT * FROM price_versions WHERE id=?",
                                        parent);
                        var newVersion =
                                Sql.one(connection, "SELECT * FROM price_versions WHERE id=?", id);
                        for (var item :
                                Sql.query(
                                        connection,
                                        "SELECT * FROM price_items WHERE version_id=?",
                                        id)) {
                            var previous =
                                    Sql.one(
                                            connection,
                                            "SELECT * FROM price_items WHERE version_id=? AND"
                                                    + " product_id=?",
                                            parent,
                                            item.get("product_id"));
                            PriceHistoryService.record(
                                    connection,
                                    actor,
                                    "ITEM_INHERITED",
                                    Sql.id(item.get("product_id")),
                                    id,
                                    Sql.id(item.get("id")),
                                    previous,
                                    item,
                                    oldVersion,
                                    newVersion);
                        }
                    }
                    AuditService.record(
                            connection,
                            actor,
                            "PRICE_VERSION_CREATED",
                            "PRICE_VERSION",
                            id,
                            null,
                            Sql.one(
                                    connection,
                                    "SELECT id,group_id,name,valid_from,valid_to,parent_id,revision"
                                            + " FROM price_versions WHERE id=?",
                                    id));
                    return id;
                });
    }

    // Khóa phiên bản bảng giá và kiểm tra số phiên bản trước khi sửa.
    private static Map<String, Object> lockedVersion(
            Connection connection, long version, Long revision) throws SQLException {
        long group =
                Sql.id(
                        Sql.one(
                                        connection,
                                        "SELECT group_id FROM price_versions WHERE id=?",
                                        version)
                                .get("group_id"));
        lockGroup(connection, group);
        var row =
                Sql.one(
                        connection,
                        "SELECT id,name,group_id,valid_from,valid_to,revision FROM price_versions"
                                + " WHERE id=? FOR UPDATE",
                        version);
        unused(connection, version);
        if (revision != null && (revision < 1 || Sql.id(row.get("revision")) != revision)) {
            throw FieldValidationException.field("form", "Bảng giá đã thay đổi. Hãy tải lại.");
        }
        return row;
    }

    // Cập nhật tên và thời gian hiệu lực của bảng giá theo phiên bản hiện tại.
    public void edit(
            long actor, long id, long revision, String name, LocalDate from, LocalDate to) {
        new AccessService(source).load(actor).require("PRICE_MANAGE");
        validVersion(name, from, to);
        Sql.transaction(
                source,
                connection -> {
                    DealerService.access(connection, actor, "PRICE_MANAGE");
                    var before = lockedVersion(connection, id, revision);
                    long group = Sql.id(before.get("group_id"));
                    for (var item :
                            Sql.query(
                                    connection,
                                    "SELECT product_id FROM price_items WHERE version_id=? FOR"
                                            + " UPDATE",
                                    id)) {
                        overlap(connection, group, Sql.id(item.get("product_id")), from, to, id);
                    }
                    Sql.update(
                            connection,
                            "UPDATE price_versions SET"
                                + " name=?,valid_from=?,valid_to=?,revision=revision+1 WHERE id=?",
                            name.trim(),
                            from,
                            to,
                            id);
                    var afterVersion =
                            Sql.one(connection, "SELECT * FROM price_versions WHERE id=?", id);
                    for (var item :
                            Sql.query(
                                    connection,
                                    "SELECT * FROM price_items WHERE version_id=?",
                                    id)) {
                        PriceHistoryService.record(
                                connection,
                                actor,
                                "VERSION_CHANGED",
                                Sql.id(item.get("product_id")),
                                id,
                                Sql.id(item.get("id")),
                                item,
                                item,
                                before,
                                afterVersion);
                    }
                    AuditService.record(
                            connection,
                            actor,
                            "PRICE_VERSION_UPDATED",
                            "PRICE_VERSION",
                            id,
                            before,
                            Sql.one(
                                    connection,
                                    "SELECT id,name,group_id,valid_from,valid_to,revision FROM"
                                            + " price_versions WHERE id=?",
                                    id));
                    return null;
                });
    }

    public void saveItem(
            long actor, long version, long product, BigDecimal selling, BigDecimal floor) {
        saveItemChecked(actor, version, product, selling, floor, null);
    }

    public void saveItem(
            long actor,
            long version,
            long product,
            BigDecimal selling,
            BigDecimal floor,
            long revision) {
        saveItemChecked(actor, version, product, selling, floor, revision);
    }

    // Kiểm tra dữ liệu trước khi ghi dòng giá của sản phẩm.
    private void saveItemChecked(
            long actor,
            long version,
            long product,
            BigDecimal selling,
            BigDecimal floor,
            Long revision) {
        new AccessService(source).load(actor).require("PRICE_MANAGE");
        var errors = priceErrors(selling, floor);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        BigDecimal cleanSelling = CatalogValidation.decimal(selling, 4, false),
                cleanFloor = CatalogValidation.decimal(floor, 4, false);
        Sql.transaction(
                source,
                connection -> {
                    DealerService.access(connection, actor, "PRICE_MANAGE");
                    var value = lockedVersion(connection, version, revision);
                    long group = Sql.id(value.get("group_id"));
                    if (Sql.query(
                                    connection,
                                    "SELECT id FROM products WHERE id=? FOR SHARE",
                                    product)
                            .isEmpty()) {
                        throw FieldValidationException.field(
                                "product", "Sản phẩm không còn tồn tại.");
                    }
                    overlap(
                            connection,
                            group,
                            product,
                            LocalDate.parse(value.get("valid_from").toString()),
                            LocalDate.parse(value.get("valid_to").toString()),
                            version);
                    var found =
                            Sql.query(
                                    connection,
                                    "SELECT id,version_id,product_id,selling_price,floor_price FROM"
                                            + " price_items WHERE version_id=? AND product_id=? FOR"
                                            + " UPDATE",
                                    version,
                                    product);
                    Map<String, Object> before = found.isEmpty() ? null : found.get(0);
                    long item;
                    if (before == null) {
                        item =
                                Sql.insert(
                                        connection,
                                        "INSERT INTO"
                                            + " price_items(version_id,product_id,selling_price,floor_price)"
                                            + " VALUES(?,?,?,?)",
                                        version,
                                        product,
                                        cleanSelling,
                                        cleanFloor);
                    } else {
                        item = Sql.id(before.get("id"));
                        Sql.update(
                                connection,
                                "UPDATE price_items SET selling_price=?,floor_price=? WHERE id=?",
                                cleanSelling,
                                cleanFloor,
                                item);
                    }
                    Sql.update(
                            connection,
                            "UPDATE price_versions SET revision=revision+1 WHERE id=?",
                            version);
                    PriceHistoryService.record(
                            connection,
                            actor,
                            before == null ? "ITEM_CREATED" : "ITEM_UPDATED",
                            product,
                            version,
                            item,
                            before,
                            Sql.one(connection, "SELECT * FROM price_items WHERE id=?", item),
                            before == null ? null : value,
                            value);
                    AuditService.record(
                            connection,
                            actor,
                            "PRICE_ITEM_SAVED",
                            "PRICE_ITEM",
                            item,
                            before,
                            Sql.one(
                                    connection,
                                    "SELECT id,version_id,product_id,selling_price,floor_price FROM"
                                            + " price_items WHERE id=?",
                                    item));
                    return null;
                });
    }

    public void deleteItem(long actor, long version, long item) {
        deleteItemChecked(actor, version, item, null);
    }

    public void deleteItem(long actor, long version, long item, long revision) {
        deleteItemChecked(actor, version, item, revision);
    }

    // Kiểm tra điều kiện trước khi xóa dòng giá.
    private void deleteItemChecked(long actor, long version, long item, Long revision) {
        new AccessService(source).load(actor).require("PRICE_MANAGE");
        Sql.transaction(
                source,
                connection -> {
                    DealerService.access(connection, actor, "PRICE_MANAGE");
                    var value = lockedVersion(connection, version, revision);
                    var before =
                            Sql.one(
                                    connection,
                                    "SELECT id,version_id,product_id,selling_price,floor_price FROM"
                                            + " price_items WHERE id=? AND version_id=?",
                                    item,
                                    version);
                    Sql.update(connection, "DELETE FROM price_items WHERE id=?", item);
                    Sql.update(
                            connection,
                            "UPDATE price_versions SET revision=revision+1 WHERE id=?",
                            version);
                    PriceHistoryService.record(
                            connection,
                            actor,
                            "ITEM_DELETED",
                            Sql.id(before.get("product_id")),
                            version,
                            item,
                            before,
                            null,
                            value,
                            value);
                    AuditService.record(
                            connection,
                            actor,
                            "PRICE_ITEM_DELETED",
                            "PRICE_ITEM",
                            item,
                            before,
                            null);
                    return null;
                });
    }

    // Tính hoặc lấy giá áp dụng cho dữ liệu đầu vào.
    public Quote quote(long actor, long group, long product, LocalDate date) {
        var access = new AccessService(source).load(actor);
        access.require("PRICE_READ");
        return Sql.transaction(
                source, connection -> quote(connection, access, group, product, date));
    }

    /**
     * Đơn hàng gọi quote và snapshot trên cùng connection để khóa phiên bản đã dùng.
     */
    public static Quote quote(
            Connection connection, Access access, long group, long product, LocalDate date)
            throws SQLException {
        access.require("PRICE_READ");
        return orderQuote(connection, group, product, date);
    }

    // Lấy giá cho đơn sau khi bên gọi đã kiểm quyền và phạm vi đại lý.
    static Quote orderQuote(Connection connection, long group, long product, LocalDate date)
            throws SQLException {
        if (date == null) {
            throw FieldValidationException.field("date", "Ngày tra giá không hợp lệ.");
        }
        Sql.one(connection, "SELECT id FROM customer_groups WHERE id=? FOR SHARE", group);
        var row =
                Sql.one(
                        connection,
                        "SELECT i.id,i.version_id,i.selling_price,i.floor_price FROM price_items i"
                            + " JOIN price_versions v ON v.id=i.version_id WHERE v.group_id=? AND"
                            + " i.product_id=? AND v.valid_from<=? AND v.valid_to>=?",
                        group,
                        product,
                        date,
                        date);
        return new Quote(
                Sql.id(row.get("id")),
                Sql.id(row.get("version_id")),
                product,
                (BigDecimal) row.get("selling_price"),
                (BigDecimal) row.get("floor_price"),
                date);
    }

    // Chốt thông tin phiên bản giá trên cùng kết nối xử lý đơn.
    public static long snapshot(Connection connection, String orderReference, Quote quote)
            throws SQLException {
        return Sql.insert(
                connection,
                "INSERT INTO"
                    + " price_order_references(order_reference,price_item_id,version_id,selling_price,floor_price)"
                    + " VALUES(?,?,?,?,?)",
                CatalogValidation.text(orderReference, 100, "Tham chiếu đơn"),
                quote.itemId,
                quote.versionId,
                quote.sellingPrice,
                quote.floorPrice);
    }
}
