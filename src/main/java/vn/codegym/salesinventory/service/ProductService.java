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

public final class ProductService {
    private final DataSource source;
    private final ImageStorage images;
    public ProductService(DataSource source) { this(source, ImageStorage.configured()); }
    public ProductService(DataSource source, ImageStorage images) { this.source = source; this.images = images; }
    public record Input(String sku, String name, long category, String baseUnit, String packaging,
                        BigDecimal cost, String imageKey, String status, long version) { }
    private record Saved(long id, String previousImage) { }

    public static Map<String, String> errors(Input in) {
        Map<String, String> errors = new LinkedHashMap<>();
        check(errors, "sku", () -> CatalogValidation.code(trim(in.sku), 64));
        check(errors, "name", () -> CatalogValidation.text(trim(in.name), 200, "Tên sản phẩm"));
        check(errors, "baseUnit", () -> CatalogValidation.text(trim(in.baseUnit), 50, "Đơn vị cơ sở"));
        if (in.category <= 0) errors.put("category", "Hãy chọn nhóm hàng hợp lệ.");
        if (trim(in.packaging).length() > 250) errors.put("packaging", "Quy cách tối đa 250 ký tự.");
        if (in.cost != null) {
            if (in.cost.scale() < -15 || in.cost.scale() > 64 || in.cost.precision() > 64)
                errors.put("cost", "Giá vốn vượt giới hạn số thập phân.");
            else check(errors, "cost", () -> CatalogValidation.decimal(in.cost.toPlainString(), 4, false));
        }
        if (!"ACTIVE".equals(in.status) && !"DISCONTINUED".equals(in.status))
            errors.put("status", "Trạng thái sản phẩm không hợp lệ.");
        if (in.imageKey != null && !in.imageKey.matches("[a-f0-9-]{36}")) errors.put("image", "Ảnh không hợp lệ.");
        if (in.version < 0) errors.put("form", "Phiên bản sản phẩm không hợp lệ.");
        return errors;
    }
    private static void check(Map<String, String> errors, String field, Runnable work) {
        try { work.run(); } catch (IllegalArgumentException e) { errors.put(field, e.getMessage()); }
    }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    public static void validate(Input in) {
        var errors = errors(in);
        if (!errors.isEmpty()) throw new ProductValidationException(errors);
    }
    private static Input normalized(Input in) {
        validate(in);
        return new Input(trim(in.sku), trim(in.name), in.category, trim(in.baseUnit), trim(in.packaging),
                in.cost == null ? null : in.cost.setScale(4), in.imageKey, in.status, in.version);
    }
    public static String columns(Access a) {
        return "p.id,p.sku,p.name,p.category_id,p.base_unit,p.packaging,p.image_key,p.status,p.version,c.name category_name"
                + (a.allows("COST_READ") ? ",p.cost_price" : "");
    }
    public List<Map<String, Object>> list(long actor, String keyword, Long category, String status, int page) {
        var a = new AccessService(source).load(actor);
        a.require("CATALOG_READ");
        if (page < 1 || page > 100000) throw new IllegalArgumentException("Trang không hợp lệ.");
        return Sql.transaction(source, c -> Sql.query(c, "SELECT " + columns(a)
                + " FROM products p JOIN categories c ON c.id=p.category_id WHERE (p.sku LIKE ? OR p.name LIKE ?)"
                + " AND (? IS NULL OR p.category_id=?) AND (?='' OR p.status=?) ORDER BY p.sku LIMIT 100 OFFSET ?",
                "%" + keyword + "%", "%" + keyword + "%", category, category, status, status, (page - 1) * 100));
    }
    public long count(long actor,String keyword,Long category,String status) {
        new AccessService(source).load(actor).require("CATALOG_READ");
        return Sql.transaction(source,c -> Sql.id(Sql.one(c,"SELECT COUNT(*) total FROM products p WHERE (p.sku LIKE ? OR p.name LIKE ?)"
                +" AND (? IS NULL OR p.category_id=?) AND (?='' OR p.status=?)",
                "%"+keyword+"%","%"+keyword+"%",category,category,status,status).get("total")));
    }
    public vn.codegym.salesinventory.dto.PageResult<Map<String,Object>> search(long actor, String keyword, Long category, String status, vn.codegym.salesinventory.dto.PageRequest requested) {
        var access = new AccessService(source).load(actor);
        access.require("CATALOG_READ");
        String filter = " WHERE (p.sku LIKE ? OR p.name LIKE ?) AND (? IS NULL OR p.category_id=?) AND (?='' OR p.status=?)";
        String clean = keyword == null ? "" : keyword.trim();
        if (clean.length() > 150) clean = clean.substring(0, 150);
        final Object[] filters = {"%" + clean + "%", "%" + clean + "%", category, category, status, status};
        return Sql.transaction(source, c -> {
            long total = Sql.id(Sql.one(c, "SELECT COUNT(*) total FROM products p" + filter, filters).get("total"));
            var effective = requested.clamp(total);
            var args = new ArrayList<Object>(Arrays.asList(filters));
            args.add(effective.pageSize()); args.add(effective.offset());
            var rows = Sql.query(c, "SELECT " + columns(access) + " FROM products p JOIN categories c ON c.id=p.category_id" + filter + " ORDER BY p.sku,p.id LIMIT ? OFFSET ?", args.toArray());
            return new vn.codegym.salesinventory.dto.PageResult<>(rows, total, effective.page(), effective.pageSize());
        });
    }
    public Map<String, Object> find(long actor, long id) {
        var a = new AccessService(source).load(actor);
        a.require("CATALOG_READ");
        return Sql.transaction(source, c -> Sql.one(c, "SELECT " + columns(a)
                + " FROM products p JOIN categories c ON c.id=p.category_id WHERE p.id=?", id));
    }
    public long save(long actor, long id, Input in) {
        var a = new AccessService(source).load(actor);
        a.require("PRODUCT_MANAGE");
        if (in.cost != null) a.require("COST_WRITE");
        Input clean = normalized(in);
        Saved saved = Sql.transaction(source, c -> saveRecord(c, a, actor, id, clean));
        // Capture the old key under the row lock; retire files only after commit.
        if (clean.imageKey != null && !clean.imageKey.equals(saved.previousImage)) removeAfterCommit(saved.previousImage);
        return saved.id;
    }
    public long saveWithImage(long actor, long id, Input in, byte[] bytes) {
        var a = new AccessService(source).load(actor);
        a.require("PRODUCT_MANAGE");
        if (in.cost != null) a.require("COST_WRITE");
        validate(in);
        String key;
        try { key = images.save(bytes); }
        catch (IllegalArgumentException e) { throw ProductValidationException.field("image", e.getMessage()); }
        catch (IOException e) { throw new IllegalStateException("Không thể lưu ảnh sản phẩm.", e); }
        try {
            return save(actor, id, new Input(in.sku, in.name, in.category, in.baseUnit, in.packaging,
                    in.cost, key, in.status, in.version));
        } catch (RuntimeException e) {
            try { images.remove(key); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    public static long save(Connection c, Access a, long actor, long id, Input in) throws SQLException {
        return saveRecord(c, a, actor, id, normalized(in)).id;
    }
    private static Saved saveRecord(Connection c, Access a, long actor, long id, Input in) throws SQLException {
        a.require("PRODUCT_MANAGE");
        if (in.cost != null) a.require("COST_WRITE");
        if (id < 0) throw ProductValidationException.field("form", "Mã sản phẩm không hợp lệ.");
        Sql.one(c, "SELECT name FROM catalog_locks WHERE name='CATEGORY_TREE' FOR UPDATE");
        if (Sql.query(c, "SELECT id FROM categories WHERE id=?", in.category).isEmpty())
            throw ProductValidationException.field("category", "Nhóm hàng không còn tồn tại.");
        Map<String, Object> before = null;
        BigDecimal cost = in.cost;
        String image = in.imageKey;
        if (id != 0) {
            var rows = Sql.query(c, "SELECT id,sku,name,category_id,base_unit,packaging,cost_price,image_key,status,version"
                    + " FROM products WHERE id=? FOR UPDATE", id);
            if (rows.isEmpty()) throw ProductValidationException.field("form", "Sản phẩm không còn tồn tại.");
            before = rows.get(0);
            if (Sql.id(before.get("version")) != in.version)
                throw ProductValidationException.field("form", "Sản phẩm đã thay đổi. Hãy tải lại trước khi sửa.");
            if (!Sql.text(before.get("base_unit")).equals(in.baseUnit)
                    && (!Sql.query(c, "SELECT product_id FROM product_transaction_references WHERE product_id=? LIMIT 1", id).isEmpty()
                    || hasAlternateUnits(c, id)))
                throw ProductValidationException.field("baseUnit", "Sản phẩm đã có quy đổi hoặc giao dịch, không thể thay đơn vị cơ sở.");
            if (cost == null) cost = (BigDecimal) before.get("cost_price");
            if (image == null) image = Sql.text(before.get("image_key"));
            if (image.isEmpty()) image = null;
        }
        if (!Sql.query(c, "SELECT id FROM products WHERE sku=? AND id<>?", in.sku, id).isEmpty())
            throw ProductValidationException.field("sku", "SKU đã tồn tại.");
        long saved = id;
        try {
            if (id == 0) saved = Sql.insert(c, "INSERT INTO products(sku,name,category_id,base_unit,packaging,cost_price,image_key,status) VALUES(?,?,?,?,?,?,?,?)",
                    in.sku, in.name, in.category, in.baseUnit, in.packaging, cost, image, in.status);
            else Sql.update(c, "UPDATE products SET sku=?,name=?,category_id=?,base_unit=?,packaging=?,cost_price=?,image_key=?,status=?,version=version+1 WHERE id=?",
                    in.sku, in.name, in.category, in.baseUnit, in.packaging, cost, image, in.status, id);
        } catch (SQLException e) {
            if (isDuplicateSku(e)) throw ProductValidationException.field("sku", "SKU đã tồn tại.");
            throw e;
        }
        if (tableExists(c, "product_units")) {
            Sql.update(c, "UPDATE product_units SET name=? WHERE product_id=? AND is_base=1", in.baseUnit, saved);
            Sql.update(c, "INSERT INTO product_units(product_id,name,factor,is_base,warehouse_id) SELECT ?,?,1,1,NULL"
                    + " WHERE NOT EXISTS(SELECT id FROM product_units WHERE product_id=? AND is_base=1)", saved, in.baseUnit, saved);
        }
        AuditService.record(c, actor, "PRODUCT_SAVED", "PRODUCT", saved, before,
                Sql.one(c, "SELECT id,sku,name,category_id,base_unit,packaging,cost_price,image_key,status,version FROM products WHERE id=?", saved));
        return new Saved(saved, before == null ? null : Sql.text(before.get("image_key")));
    }
    static boolean isDuplicateSku(SQLException e) {
        String message = Sql.text(e.getMessage());
        return e.getErrorCode() == 1062 && "23000".equals(e.getSQLState())
                && (message.contains("'sku'") || message.contains("'products.sku'"));
    }
    private static boolean tableExists(Connection c, String table) throws SQLException {
        return !Sql.query(c, "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", table).isEmpty();
    }
    private static boolean hasAlternateUnits(Connection c, long id) throws SQLException {
        return tableExists(c, "product_units") && !Sql.query(c, "SELECT id FROM product_units WHERE product_id=? AND is_base=0 LIMIT 1", id).isEmpty();
    }
    public void delete(long actor, long id) {
        new AccessService(source).load(actor).require("PRODUCT_MANAGE");
        String image = Sql.transaction(source, c -> {
            Sql.one(c, "SELECT name FROM catalog_locks WHERE name='CATEGORY_TREE' FOR UPDATE");
            var before = Sql.one(c, "SELECT id,sku,name,category_id,base_unit,packaging,cost_price,image_key,status,version FROM products WHERE id=? FOR UPDATE", id);
            if (!Sql.query(c, "SELECT product_id FROM product_transaction_references WHERE product_id=? LIMIT 1", id).isEmpty())
                throw new IllegalArgumentException("Sản phẩm đã phát sinh giao dịch. Hãy ngừng kinh doanh.");
            if (tableExists(c, "price_items") && !Sql.query(c, "SELECT id FROM price_items WHERE product_id=? LIMIT 1", id).isEmpty())
                throw new IllegalArgumentException("Sản phẩm đang có bảng giá. Hãy ngừng kinh doanh.");
            if (tableExists(c, "discount_policies") && !Sql.query(c, "SELECT id FROM discount_policies WHERE product_id=? LIMIT 1", id).isEmpty())
                throw new IllegalArgumentException("Sản phẩm đã có chính sách chiết khấu. Hãy ngừng kinh doanh để giữ lịch sử.");
            if (tableExists(c, "conversion_snapshots") && !Sql.query(c, "SELECT id FROM conversion_snapshots WHERE product_id=? LIMIT 1", id).isEmpty())
                throw new IllegalArgumentException("Sản phẩm đã có lịch sử quy đổi. Hãy ngừng kinh doanh.");
            if (tableExists(c, "product_units")) Sql.update(c, "DELETE FROM product_units WHERE product_id=?", id);
            Sql.update(c, "DELETE FROM products WHERE id=?", id);
            AuditService.record(c, actor, "PRODUCT_DELETED", "PRODUCT", id, before, null);
            return Sql.text(before.get("image_key"));
        });
        removeAfterCommit(image);
    }
    private void removeAfterCommit(String key) {
        if (key == null || key.isBlank()) return;
        try { images.remove(key); }
        catch (IOException | RuntimeException e) {
            // Cleanup failure must not report a rollback of an already committed update.
            LoggerFactory.getLogger(ProductService.class).warn("Không thể dọn ảnh sản phẩm cũ: {}", key, e);
        }
    }
}
