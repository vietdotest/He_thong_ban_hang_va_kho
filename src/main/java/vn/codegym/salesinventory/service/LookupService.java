package vn.codegym.salesinventory.service;

import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;

/** Small projections only: never send a whole user/product record to an autocomplete. */
public final class LookupService {
    private final DataSource source;
    public LookupService(DataSource source) { this.source = source; }
    public List<Map<String,Object>> search(long actor, String type, String query) {
        var access = new AccessService(source).load(actor);
        String q = query == null ? "" : query.trim();
        if (q.length() > 150) q = q.substring(0,150);
        String exact = q, prefix = q + "%", contains = "%" + q + "%";
        switch (type) {
            case "products", "categories" -> access.require("CATALOG_READ");
            case "users", "warehouses", "territories" -> access.require("USER_MANAGE");
            default -> throw new IllegalArgumentException("Loại tìm kiếm không hợp lệ.");
        }
        if (q.length() < 2) return List.of();
        return Sql.transaction(source, c -> switch(type) {
            case "products" -> Sql.query(c,"SELECT id,sku code,name,base_unit detail FROM products WHERE sku LIKE ? OR name LIKE ? ORDER BY (sku=?) DESC,(sku LIKE ?) DESC,sku,id LIMIT 10",contains,contains,exact,prefix);
            case "categories" -> Sql.query(c,"SELECT id,code,name FROM categories WHERE code LIKE ? OR name LIKE ? ORDER BY (code=?) DESC,(code LIKE ?) DESC,name,id LIMIT 10",contains,contains,exact,prefix);
            case "users" -> Sql.query(c,"SELECT id,username code,full_name name FROM users WHERE username_normalized LIKE ? OR full_name LIKE ? OR phone_normalized LIKE ? ORDER BY (username_normalized=?) DESC,(username_normalized LIKE ?) DESC,full_name,id LIMIT 10",contains,contains,contains,exact,prefix);
            case "warehouses" -> Sql.query(c,"SELECT id,code,name,address detail FROM warehouses WHERE code LIKE ? OR name LIKE ? ORDER BY (code=?) DESC,(code LIKE ?) DESC,name,id LIMIT 10",contains,contains,exact,prefix);
            case "territories" -> Sql.query(c,"SELECT id,code,name,address detail FROM territories WHERE code LIKE ? OR name LIKE ? ORDER BY (code=?) DESC,(code LIKE ?) DESC,name,id LIMIT 10",contains,contains,exact,prefix);
            default -> throw new IllegalArgumentException("Loại tìm kiếm không hợp lệ.");
        });
    }
}
