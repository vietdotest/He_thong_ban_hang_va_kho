package vn.codegym.salesinventory.service;

import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;

/**
 * Dùng chung: Tìm dữ liệu gợi ý theo quyền, chỉ trả các trường cần cho ô tìm kiếm.
 */
public final class LookupService {

    private final DataSource source;

    public LookupService(DataSource source) {
        this.source = source;
    }

    // Tìm dữ liệu gợi ý tìm kiếm theo bộ lọc và phân trang.
    public List<Map<String, Object>> search(long actor, String type, String query) {
        if ("dealers".equals(type)) {
            return new DealerService(source).suggest(actor, query);
        }
        if ("auditusers".equals(type)) {
            return new AuditReadService(source).suggestActors(actor, query);
        }
        if ("suppliers".equals(type)) {
            return new SupplierService(source).suggest(actor, query);
        }
        if ("priceversions".equals(type)) {
            return new PricingService(source).suggestVersions(actor, query);
        }
        if ("unitwarehouses".equals(type)) {
            return new UnitService(source).suggestWarehouses(actor, query);
        }
        String q = query == null ? "" : query.trim();
        if (q.length() > 150) {
            q = q.substring(0, 150);
        }
        String exact = q, prefix = q + "%", contains = "%" + q + "%";
        String permission =
                switch (type) {
                    case "products", "categories" -> "CATALOG_READ";
                    case "users", "warehouses", "territories", "scopes" -> "USER_MANAGE";
                    default -> throw new IllegalArgumentException("Loại tìm kiếm không hợp lệ.");
                };
        return Sql.snapshot(
                source,
                connection -> {
                    DealerService.access(connection, actor, permission);
                    if (PortalAccountService.portal(connection, actor)) {
                        throw new SecurityException();
                    }
                    if (exact.length() < 2) {
                        return List.of();
                    }
                    return switch (type) {
                        case "products" ->
                                Sql.query(
                                        connection,
                                        "SELECT id,sku code,name,base_unit detail FROM products"
                                            + " WHERE sku LIKE ? OR name LIKE ? ORDER BY (sku=?)"
                                            + " DESC,(sku LIKE ?) DESC,sku,id LIMIT 10",
                                        contains,
                                        contains,
                                        exact,
                                        prefix);
                        case "categories" ->
                                Sql.query(
                                        connection,
                                        "SELECT id,code,name FROM categories WHERE code LIKE ? OR"
                                            + " name LIKE ? ORDER BY (code=?) DESC,(code LIKE ?)"
                                            + " DESC,name,id LIMIT 10",
                                        contains,
                                        contains,
                                        exact,
                                        prefix);
                        case "users" ->
                                Sql.query(
                                        connection,
                                        "SELECT id,username code,full_name name FROM users WHERE"
                                            + " account_kind='INTERNAL' AND (username_normalized"
                                            + " LIKE ? OR full_name LIKE ? OR phone_normalized LIKE"
                                            + " ?) ORDER BY (username_normalized=?)"
                                            + " DESC,(username_normalized LIKE ?) DESC,full_name,id"
                                            + " LIMIT 10",
                                        contains,
                                        contains,
                                        contains,
                                        exact,
                                        prefix);
                        case "warehouses" ->
                                Sql.query(
                                        connection,
                                        "SELECT id,code,name,address detail FROM warehouses WHERE"
                                                + " code LIKE ? OR name LIKE ? ORDER BY (code=?)"
                                                + " DESC,(code LIKE ?) DESC,name,id LIMIT 10",
                                        contains,
                                        contains,
                                        exact,
                                        prefix);
                        case "territories" ->
                                Sql.query(
                                        connection,
                                        "SELECT id,code,name,address detail FROM territories WHERE"
                                                + " code LIKE ? OR name LIKE ? ORDER BY (code=?)"
                                                + " DESC,(code LIKE ?) DESC,name,id LIMIT 10",
                                        contains,
                                        contains,
                                        exact,
                                        prefix);
                        case "scopes" ->
                                Sql.query(
                                        connection,
                                        "SELECT id,code,name,detail FROM (SELECT"
                                            + " id,code,name,CONCAT('Kho · ',COALESCE(address,''))"
                                            + " detail,'warehouse' kind FROM warehouses UNION ALL"
                                            + " SELECT id,code,name,CONCAT('Địa bàn ·"
                                            + " ',COALESCE(address,'')) detail,'territory' kind"
                                            + " FROM territories) s WHERE code LIKE ? OR name LIKE"
                                            + " ? OR detail LIKE ? ORDER BY (code=?) DESC,(code"
                                            + " LIKE ?) DESC,name,kind,id LIMIT 10",
                                        contains,
                                        contains,
                                        contains,
                                        exact,
                                        prefix);
                        default ->
                                throw new IllegalArgumentException("Loại tìm kiếm không hợp lệ.");
                    };
                });
    }
}
