package vn.codegym.salesinventory.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.validation.CatalogValidation;
import vn.codegym.salesinventory.validation.ProductValidationException;

/**
 * S2-05: Quản lý sản phẩm, kiểm tra dữ liệu và giới hạn truy cập giá vốn.
 */
public final class ProductService {

    private final DataSource source;

    private final ImageStorage images;

    public ProductService(DataSource source) {
        this(source, ImageStorage.configured());
    }

    public ProductService(DataSource source, ImageStorage images) {
        this.source = source;
        this.images = images;
    }

    // Chứa dữ liệu biểu mẫu sản phẩm cần kiểm tra và lưu.
    public record Input(
            String sku,
            String name,
            long category,
            String baseUnit,
            String packaging,
            BigDecimal cost,
            String imageKey,
            String status,
            long version) {}

    // Chứa kết quả lưu sản phẩm và thông tin ảnh liên quan.
    private record Saved(long id, String previousImage) {}

    // Kiểm tra dữ liệu đầu vào và gom lỗi theo từng trường.
    public static Map<String, String> errors(Input input) {
        Map<String, String> errors = new LinkedHashMap<>();
        check(errors, "sku", () -> CatalogValidation.code(trim(input.sku), 64));
        check(errors, "name", () -> CatalogValidation.text(trim(input.name), 200, "Tên sản phẩm"));
        check(
                errors,
                "baseUnit",
                () -> CatalogValidation.text(trim(input.baseUnit), 50, "Đơn vị cơ sở"));
        if (input.category <= 0) {
            errors.put("category", "Hãy chọn nhóm hàng hợp lệ.");
        }
        if (trim(input.packaging).length() > 250) {
            errors.put("packaging", "Quy cách tối đa 250 ký tự.");
        }
        if (input.cost != null) {
            if (input.cost.scale() < -15
                    || input.cost.scale() > 64
                    || input.cost.precision() > 64) {
                errors.put("cost", "Giá vốn vượt giới hạn số thập phân.");
            } else {
                check(
                        errors,
                        "cost",
                        () -> CatalogValidation.decimal(input.cost.toPlainString(), 4, false));
            }
        }
        if (!"ACTIVE".equals(input.status) && !"DISCONTINUED".equals(input.status)) {
            errors.put("status", "Trạng thái sản phẩm không hợp lệ.");
        }
        if (input.imageKey != null && !input.imageKey.matches("[a-f0-9-]{36}")) {
            errors.put("image", "Ảnh không hợp lệ.");
        }
        if (input.version < 0) {
            errors.put("form", "Phiên bản sản phẩm không hợp lệ.");
        }
        return errors;
    }

    // Chạy một bước kiểm tra và gom lỗi vào trường tương ứng.
    private static void check(Map<String, String> errors, String field, Runnable work) {
        try {
            work.run();
        } catch (IllegalArgumentException exception) {
            errors.put(field, exception.getMessage());
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    // Kiểm tra dữ liệu trước khi tiếp tục xử lý.
    public static void validate(Input input) {
        var errors = errors(input);
        if (!errors.isEmpty()) {
            throw new ProductValidationException(errors);
        }
    }

    private static Input normalized(Input input) {
        validate(input);
        return new Input(
                trim(input.sku),
                trim(input.name),
                input.category,
                trim(input.baseUnit),
                trim(input.packaging),
                input.cost == null ? null : input.cost.setScale(4),
                input.imageKey,
                input.status,
                input.version);
    }

    // Chọn các cột sản phẩm được phép truy vấn, gồm giá vốn khi đủ quyền.
    public static String columns(Access access) {
        return "p.id,p.sku,p.name,p.category_id,p.base_unit,p.packaging,p.image_key,p.status,p.version,c.name"
                   + " category_name"
                + (access.allows("COST_READ") ? ",p.cost_price" : "");
    }

    // Lấy danh sách sản phẩm theo điều kiện đầu vào.
    public List<Map<String, Object>> list(
            long actor, String keyword, Long category, String status, int page) {
        if (page < 1 || page > 100000) {
            throw new IllegalArgumentException("Trang không hợp lệ.");
        }
        return search(
                        actor,
                        keyword,
                        category,
                        status,
                        new vn.codegym.salesinventory.dto.PageRequest(page, 100))
                .items();
    }

    // Đếm số bản ghi phù hợp với điều kiện tìm kiếm.
    public long count(long actor, String keyword, Long category, String status) {
        return Sql.snapshot(
                source,
                connection -> {
                    DealerService.access(connection, actor, "CATALOG_READ");
                    return Sql.id(
                            Sql.one(
                                            connection,
                                            "SELECT COUNT(*) total FROM products p WHERE (p.sku"
                                                    + " LIKE ? OR p.name LIKE ?) AND (? IS NULL OR"
                                                    + " p.category_id=?) AND (?='' OR p.status=?)",
                                            "%" + keyword + "%",
                                            "%" + keyword + "%",
                                            category,
                                            category,
                                            status,
                                            status)
                                    .get("total"));
                });
    }

    // Tìm sản phẩm theo bộ lọc và phân trang.
    public vn.codegym.salesinventory.dto.PageResult<Map<String, Object>> search(
            long actor,
            String keyword,
            Long category,
            String status,
            vn.codegym.salesinventory.dto.PageRequest requested) {
        String filter =
                " WHERE (p.sku LIKE ? OR p.name LIKE ?) AND (? IS NULL OR p.category_id=?) AND"
                        + " (?='' OR p.status=?)";
        String clean = keyword == null ? "" : keyword.trim();
        if (clean.length() > 150) {
            clean = clean.substring(0, 150);
        }
        final Object[] filters = {
            "%" + clean + "%", "%" + clean + "%", category, category, status, status
        };
        return Sql.snapshot(
                source,
                connection -> {
                    var access = DealerService.access(connection, actor, "CATALOG_READ");
                    long total =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total FROM products p"
                                                            + filter,
                                                    filters)
                                            .get("total"));
                    var effective = requested.clamp(total);
                    var parameters = new ArrayList<Object>(Arrays.asList(filters));
                    parameters.add(effective.pageSize());
                    parameters.add(effective.offset());
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT "
                                            + columns(access)
                                            + " FROM products p JOIN categories c ON"
                                            + " c.id=p.category_id"
                                            + filter
                                            + " ORDER BY p.sku,p.id LIMIT ? OFFSET ?",
                                    parameters.toArray());
                    return new vn.codegym.salesinventory.dto.PageResult<>(
                            rows, total, effective.page(), effective.pageSize());
                });
    }

    // Tìm sản phẩm theo mã hoặc điều kiện được cung cấp.
    public Map<String, Object> find(long actor, long id) {
        return Sql.snapshot(
                source,
                connection -> {
                    var access = DealerService.access(connection, actor, "CATALOG_READ");
                    return Sql.one(
                            connection,
                            "SELECT "
                                    + columns(access)
                                    + " FROM products p JOIN categories c ON c.id=p.category_id"
                                    + " WHERE p.id=?",
                            id);
                });
    }

    // Lưu thông tin sản phẩm sau khi kiểm tra dữ liệu.
    public long save(long actor, long id, Input input) {
        var access = new AccessService(source).load(actor);
        access.require("PRODUCT_MANAGE");
        if (input.cost != null) {
            access.require("COST_WRITE");
        }
        Input clean = normalized(input);
        Saved saved =
                Sql.transaction(
                        source, connection -> saveRecord(connection, access, actor, id, clean));
        // Capture the old key under the row lock; retire files only after commit.
        if (clean.imageKey != null && !clean.imageKey.equals(saved.previousImage)) {
            removeAfterCommit(saved.previousImage);
        }
        return saved.id;
    }

    // Lưu dữ liệu sản phẩm cùng ảnh đã kiểm tra.
    public long saveWithImage(long actor, long id, Input input, byte[] bytes) {
        var access = new AccessService(source).load(actor);
        access.require("PRODUCT_MANAGE");
        if (input.cost != null) {
            access.require("COST_WRITE");
        }
        validate(input);
        String key;
        try {
            key = images.save(bytes);
        } catch (IllegalArgumentException exception) {
            throw ProductValidationException.field("image", exception.getMessage());
        } catch (IOException e) {
            throw new IllegalStateException("Không thể lưu ảnh sản phẩm.", e);
        }
        try {
            return save(
                    actor,
                    id,
                    new Input(
                            input.sku,
                            input.name,
                            input.category,
                            input.baseUnit,
                            input.packaging,
                            input.cost,
                            key,
                            input.status,
                            input.version));
        } catch (RuntimeException e) {
            try {
                images.remove(key);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    // Lưu thông tin sản phẩm sau khi kiểm tra dữ liệu.
    public static long save(Connection connection, Access access, long actor, long id, Input input)
            throws SQLException {
        return saveRecord(connection, access, actor, id, normalized(input)).id;
    }

    // Lưu thông tin nhà cung cấp sau khi kiểm tra dữ liệu.
    private static Saved saveRecord(
            Connection connection, Access access, long actor, long id, Input input)
            throws SQLException {
        access = DealerService.access(connection, actor, "PRODUCT_MANAGE");
        access.require("PRODUCT_MANAGE");
        if (input.cost != null) {
            access.require("COST_WRITE");
        }
        if (id < 0) {
            throw ProductValidationException.field("form", "Mã sản phẩm không hợp lệ.");
        }
        Sql.one(connection, "SELECT name FROM catalog_locks WHERE name='CATEGORY_TREE' FOR UPDATE");
        if (Sql.query(connection, "SELECT id FROM categories WHERE id=?", input.category)
                .isEmpty()) {
            throw ProductValidationException.field("category", "Nhóm hàng không còn tồn tại.");
        }
        Map<String, Object> before = null;
        BigDecimal cost = input.cost;
        String image = input.imageKey;
        if (id != 0) {
            var rows =
                    Sql.query(
                            connection,
                            "SELECT"
                                + " id,sku,name,category_id,base_unit,packaging,cost_price,image_key,status,version"
                                + " FROM products WHERE id=? FOR UPDATE",
                            id);
            if (rows.isEmpty()) {
                throw ProductValidationException.field("form", "Sản phẩm không còn tồn tại.");
            }
            before = rows.get(0);
            if (Sql.id(before.get("version")) != input.version) {
                throw ProductValidationException.field(
                        "form", "Sản phẩm đã thay đổi. Hãy tải lại trước khi sửa.");
            }
            if (!Sql.text(before.get("base_unit")).equals(input.baseUnit)
                    && (!Sql.query(
                                            connection,
                                            "SELECT product_id FROM product_transaction_references"
                                                    + " WHERE product_id=? LIMIT 1",
                                            id)
                                    .isEmpty()
                            || hasAlternateUnits(connection, id))) {
                throw ProductValidationException.field(
                        "baseUnit",
                        "Sản phẩm đã có quy đổi hoặc giao dịch, không thể thay đơn vị cơ sở.");
            }
            if (cost == null) {
                cost = (BigDecimal) before.get("cost_price");
            }
            if (image == null) {
                image = Sql.text(before.get("image_key"));
            }
            if (image.isEmpty()) {
                image = null;
            }
        }
        if (!Sql.query(connection, "SELECT id FROM products WHERE sku=? AND id<>?", input.sku, id)
                .isEmpty()) {
            throw ProductValidationException.field("sku", "SKU đã tồn tại.");
        }
        long saved = id;
        try {
            if (id == 0) {
                saved =
                        Sql.insert(
                                connection,
                                "INSERT INTO"
                                    + " products(sku,name,category_id,base_unit,packaging,cost_price,image_key,status)"
                                    + " VALUES(?,?,?,?,?,?,?,?)",
                                input.sku,
                                input.name,
                                input.category,
                                input.baseUnit,
                                input.packaging,
                                cost,
                                image,
                                input.status);
            } else {
                Sql.update(
                        connection,
                        "UPDATE products SET"
                            + " sku=?,name=?,category_id=?,base_unit=?,packaging=?,cost_price=?,image_key=?,status=?,version=version+1"
                            + " WHERE id=?",
                        input.sku,
                        input.name,
                        input.category,
                        input.baseUnit,
                        input.packaging,
                        cost,
                        image,
                        input.status,
                        id);
            }
        } catch (SQLException exception) {
            if (isDuplicateSku(exception)) {
                throw ProductValidationException.field("sku", "SKU đã tồn tại.");
            }
            throw exception;
        }
        if (tableExists(connection, "product_units")) {
            Sql.update(
                    connection,
                    "UPDATE product_units SET name=? WHERE product_id=? AND is_base=1",
                    input.baseUnit,
                    saved);
            Sql.update(
                    connection,
                    "INSERT INTO product_units(product_id,name,factor,is_base,warehouse_id) SELECT"
                            + " ?,?,1,1,NULL WHERE NOT EXISTS(SELECT id FROM product_units WHERE"
                            + " product_id=? AND is_base=1)",
                    saved,
                    input.baseUnit,
                    saved);
        }
        AuditService.record(
                connection,
                actor,
                "PRODUCT_SAVED",
                "PRODUCT",
                saved,
                before,
                Sql.one(
                        connection,
                        "SELECT"
                            + " id,sku,name,category_id,base_unit,packaging,cost_price,image_key,status,version"
                            + " FROM products WHERE id=?",
                        saved));
        return new Saved(saved, before == null ? null : Sql.text(before.get("image_key")));
    }

    static boolean isDuplicateSku(SQLException e) {
        String message = Sql.text(e.getMessage());
        return e.getErrorCode() == 1062
                && "23000".equals(e.getSQLState())
                && (message.contains("'sku'") || message.contains("'products.sku'"));
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        return !Sql.query(
                        connection,
                        "SELECT table_name FROM information_schema.tables WHERE"
                                + " table_schema=DATABASE() AND table_name=?",
                        table)
                .isEmpty();
    }

    private static boolean hasAlternateUnits(Connection connection, long id) throws SQLException {
        return tableExists(connection, "product_units")
                && !Sql.query(
                                connection,
                                "SELECT id FROM product_units WHERE product_id=? AND is_base=0"
                                        + " LIMIT 1",
                                id)
                        .isEmpty();
    }

    // Xóa sản phẩm sau khi kiểm tra điều kiện cho phép.
    public void delete(long actor, long id) {
        new AccessService(source).load(actor).require("PRODUCT_MANAGE");
        String image =
                Sql.transaction(
                        source,
                        connection -> {
                            DealerService.access(connection, actor, "PRODUCT_MANAGE");
                            Sql.one(
                                    connection,
                                    "SELECT name FROM catalog_locks WHERE name='CATEGORY_TREE' FOR"
                                            + " UPDATE");
                            var before =
                                    Sql.one(
                                            connection,
                                            "SELECT"
                                                + " id,sku,name,category_id,base_unit,packaging,cost_price,image_key,status,version"
                                                + " FROM products WHERE id=? FOR UPDATE",
                                            id);
                            if (!Sql.query(
                                            connection,
                                            "SELECT product_id FROM product_transaction_references"
                                                    + " WHERE product_id=? LIMIT 1",
                                            id)
                                    .isEmpty()) {
                                throw new IllegalArgumentException(
                                        "Sản phẩm đã phát sinh giao dịch. Hãy ngừng kinh doanh.");
                            }
                            if (tableExists(connection, "price_items")
                                    && !Sql.query(
                                                    connection,
                                                    "SELECT id FROM price_items WHERE product_id=?"
                                                            + " LIMIT 1",
                                                    id)
                                            .isEmpty()) {
                                throw new IllegalArgumentException(
                                        "Sản phẩm đang có bảng giá. Hãy ngừng kinh doanh.");
                            }
                            if (tableExists(connection, "discount_policies")
                                    && !Sql.query(
                                                    connection,
                                                    "SELECT id FROM discount_policies WHERE"
                                                            + " product_id=? LIMIT 1",
                                                    id)
                                            .isEmpty()) {
                                throw new IllegalArgumentException(
                                        "Sản phẩm đã có chính sách chiết khấu. Hãy ngừng kinh doanh"
                                                + " để giữ lịch sử.");
                            }
                            if (tableExists(connection, "price_history")
                                    && !Sql.query(
                                                    connection,
                                                    "SELECT id FROM price_history WHERE"
                                                            + " product_id=? LIMIT 1",
                                                    id)
                                            .isEmpty()) {
                                throw new IllegalArgumentException(
                                        "Sản phẩm đã có lịch sử giá. Hãy ngừng kinh doanh để giữ"
                                                + " lịch sử.");
                            }
                            if (tableExists(connection, "conversion_snapshots")
                                    && !Sql.query(
                                                    connection,
                                                    "SELECT id FROM conversion_snapshots WHERE"
                                                            + " product_id=? LIMIT 1",
                                                    id)
                                            .isEmpty()) {
                                throw new IllegalArgumentException(
                                        "Sản phẩm đã có lịch sử quy đổi. Hãy ngừng kinh doanh.");
                            }
                            if (tableExists(connection, "product_units")) {
                                Sql.update(
                                        connection,
                                        "DELETE FROM product_units WHERE product_id=?",
                                        id);
                            }
                            Sql.update(connection, "DELETE FROM products WHERE id=?", id);
                            AuditService.record(
                                    connection,
                                    actor,
                                    "PRODUCT_DELETED",
                                    "PRODUCT",
                                    id,
                                    before,
                                    null);
                            return Sql.text(before.get("image_key"));
                        });
        removeAfterCommit(image);
    }

    // Xóa ảnh cũ sau khi dữ liệu mới đã được lưu thành công.
    private void removeAfterCommit(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            images.remove(key);
        } catch (IOException | RuntimeException exception) {
            // Cleanup failure must not report a rollback of an already committed update.
            LoggerFactory.getLogger(ProductService.class)
                    .warn("Không thể dọn ảnh sản phẩm cũ: {}", key, exception);
        }
    }
}
