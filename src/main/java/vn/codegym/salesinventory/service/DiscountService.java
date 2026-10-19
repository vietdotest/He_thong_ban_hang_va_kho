package vn.codegym.salesinventory.service;

import java.math.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.validation.*;

/**
 * S3-01, S4-01: Quản lý chính sách chiết khấu và chọn chính sách áp dụng cho đơn.
 */
public final class DiscountService {

    private final DataSource source;

    public DiscountService(DataSource source) {
        this.source = source;
    }

    // Chứa dữ liệu một bậc chiết khấu từ biểu mẫu.
    public record TierInput(BigDecimal minimum, BigDecimal value) {}

    // Chứa thông tin chính sách và các bậc chiết khấu cần lưu.
    public record Input(
            String name,
            Long product,
            Long category,
            Long group,
            String mode,
            LocalDate from,
            LocalDate to,
            String status,
            long revision,
            List<TierInput> tiers) {}

    // Kiểm tra dữ liệu đầu vào và gom lỗi theo từng trường.
    public static Map<String, String> errors(Input input) {
        var errors =
                new LinkedHashMap<>(PricingService.versionErrors(input.name, input.from, input.to));
        if ((input.product == null) == (input.category == null)) {
            errors.put("target", "Chọn đúng một SKU hoặc nhóm hàng.");
        }
        if (input.product != null && input.product <= 0
                || input.category != null && input.category <= 0) {
            errors.put("target", "Đối tượng áp dụng không hợp lệ.");
        }
        if (input.group != null && input.group <= 0) {
            errors.put("group", "Nhóm khách không hợp lệ.");
        }
        if (!Set.of("PERCENT", "FIXED").contains(input.mode == null ? "" : input.mode)) {
            errors.put("mode", "Loại chiết khấu không hợp lệ.");
        }
        if (!Set.of("ACTIVE", "DISABLED").contains(input.status == null ? "" : input.status)) {
            errors.put("status", "Trạng thái không hợp lệ.");
        }
        if (input.revision < 0) {
            errors.put("form", "Phiên bản không hợp lệ.");
        }
        if (input.tiers == null || input.tiers.isEmpty() || input.tiers.size() > 100) {
            errors.put("tiers", "Chính sách cần 1–100 bậc.");
        } else {
            var thresholds = new HashSet<BigDecimal>();
            for (int i = 0; i < input.tiers.size(); i++) {
                var tier = input.tiers.get(i);
                try {
                    var q = CatalogValidation.decimal(tier.minimum, 6, false);
                    if (!thresholds.add(q)) {
                        errors.put("minimum" + i, "Mốc sản lượng bị trùng.");
                    }
                } catch (IllegalArgumentException exception) {
                    errors.put("minimum" + i, exception.getMessage());
                }
                try {
                    var amount =
                            CatalogValidation.decimal(
                                    tier.value, "FIXED".equals(input.mode) ? 4 : 6, false);
                    if ("PERCENT".equals(input.mode)
                            && amount.compareTo(new BigDecimal("100")) > 0) {
                        errors.put("value" + i, "Phần trăm không vượt 100.");
                    }
                } catch (IllegalArgumentException e) {
                    errors.put("value" + i, e.getMessage());
                }
            }
        }
        return errors;
    }

    // Tìm chính sách chiết khấu theo bộ lọc và phân trang.
    public PageResult<Map<String, Object>> search(long actor, String query, PageRequest request) {
        String q = query == null ? "" : query.trim();
        if (q.length() > 150) {
            q = q.substring(0, 150);
        }
        final String term = "%" + q + "%";
        return Sql.snapshot(
                source,
                connection -> {
                    DealerService.access(connection, actor, "DISCOUNT_READ");
                    long total =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total FROM discount_policies"
                                                            + " WHERE name LIKE ?",
                                                    term)
                                            .get("total"));
                    var p = request.clamp(total);
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT d.*,p.sku,p.name product_name,c.name"
                                        + " category_name,g.name group_name FROM discount_policies"
                                        + " d LEFT JOIN products p ON p.id=d.product_id LEFT JOIN"
                                        + " categories c ON c.id=d.category_id LEFT JOIN"
                                        + " customer_groups g ON g.id=d.customer_group_id WHERE"
                                        + " d.name LIKE ? ORDER BY d.valid_from DESC,d.id DESC"
                                        + " LIMIT ? OFFSET ?",
                                    term,
                                    p.pageSize(),
                                    p.offset());
                    return new PageResult<>(rows, total, p.page(), p.pageSize());
                });
    }

    // Tìm chính sách chiết khấu theo mã hoặc điều kiện được cung cấp.
    public Map<String, Object> find(long actor, long id) {
        return Sql.transaction(
                source,
                connection -> {
                    DealerService.access(connection, actor, "DISCOUNT_READ");
                    var row = Sql.one(connection, "SELECT * FROM discount_policies WHERE id=?", id);
                    row.put(
                            "tiers",
                            Sql.query(
                                    connection,
                                    "SELECT minimum_quantity,discount_value FROM discount_tiers"
                                            + " WHERE policy_id=? AND policy_revision=? ORDER BY"
                                            + " minimum_quantity,id",
                                    id,
                                    row.get("revision")));
                    return row;
                });
    }

    // Lưu thông tin chính sách chiết khấu sau khi kiểm tra dữ liệu.
    public long save(long actor, long id, Input input) {
        var errors = errors(input);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        return Sql.transaction(
                source,
                connection -> {
                    var access = DealerService.access(connection, actor, "DISCOUNT_MANAGE");
                    if (!access.roles().contains("SALES_MANAGER")) {
                        throw new SecurityException("Chỉ Quản lý kinh doanh quản lý chiết khấu.");
                    }
                    Sql.one(
                            connection,
                            "SELECT name FROM catalog_locks WHERE name='CATEGORY_TREE' FOR SHARE");
                    Sql.one(
                            connection,
                            "SELECT name FROM catalog_locks WHERE name='DISCOUNT_POLICIES' FOR"
                                    + " UPDATE");
                    Map<String, Object> before =
                            id == 0
                                    ? null
                                    : Sql.one(
                                            connection,
                                            "SELECT * FROM discount_policies WHERE id=? FOR UPDATE",
                                            id);
                    if (before != null && Sql.id(before.get("revision")) != input.revision) {
                        throw FieldValidationException.field(
                                "form", "Chính sách đã thay đổi. Hãy tải lại.");
                    }
                    if (before != null) {
                        before.put(
                                "tiers",
                                Sql.query(
                                        connection,
                                        "SELECT minimum_quantity,discount_value FROM discount_tiers"
                                            + " WHERE policy_id=? AND policy_revision=? ORDER BY"
                                            + " minimum_quantity,id",
                                        id,
                                        before.get("revision")));
                    }
                    if (input.product != null
                                    && Sql.query(
                                                    connection,
                                                    "SELECT id FROM products WHERE id=?",
                                                    input.product)
                                            .isEmpty()
                            || input.category != null
                                    && Sql.query(
                                                    connection,
                                                    "SELECT id FROM categories WHERE id=?",
                                                    input.category)
                                            .isEmpty()) {
                        throw FieldValidationException.field(
                                "target", "Đối tượng không còn tồn tại.");
                    }
                    if (input.group != null
                            && Sql.query(
                                            connection,
                                            "SELECT id FROM customer_groups WHERE id=?",
                                            input.group)
                                    .isEmpty()) {
                        throw FieldValidationException.field(
                                "group", "Nhóm khách không còn tồn tại.");
                    }
                    if ("FIXED".equals(input.mode)) {
                        String sql =
                                input.product != null
                                        ? "SELECT MIN(i.selling_price) cheapest FROM price_items i"
                                              + " JOIN price_versions v ON v.id=i.version_id WHERE"
                                              + " i.product_id=? AND (? IS NULL OR v.group_id=?)"
                                              + " AND v.valid_from<=? AND v.valid_to>=?"
                                        : "WITH RECURSIVE subtree AS (SELECT id FROM categories"
                                              + " WHERE id=? UNION ALL SELECT c.id FROM categories"
                                              + " c JOIN subtree s ON c.parent_id=s.id) SELECT"
                                              + " MIN(i.selling_price) cheapest FROM price_items i"
                                              + " JOIN price_versions v ON v.id=i.version_id JOIN"
                                              + " products p ON p.id=i.product_id JOIN subtree s ON"
                                              + " s.id=p.category_id WHERE (? IS NULL OR"
                                              + " v.group_id=?) AND v.valid_from<=? AND"
                                              + " v.valid_to>=?";
                        var cheapest =
                                (BigDecimal)
                                        Sql.one(
                                                        connection,
                                                        sql,
                                                        input.product != null
                                                                ? input.product
                                                                : input.category,
                                                        input.group,
                                                        input.group,
                                                        input.to,
                                                        input.from)
                                                .get("cheapest");
                        if (cheapest != null) {
                            for (int i = 0; i < input.tiers.size(); i++) {
                                if (input.tiers.get(i).value.compareTo(cheapest) > 0) {
                                    throw FieldValidationException.field(
                                            "value" + i,
                                            "Tiền giảm cố định vượt giá bán đang áp dụng trên đối"
                                                    + " tượng.");
                                }
                            }
                        }
                    }
                    long saved = id,
                            revision = before == null ? 1 : Sql.id(before.get("revision")) + 1;
                    Object[] parameters = {
                        input.name.trim(),
                        input.product,
                        input.category,
                        input.group,
                        input.mode,
                        input.from,
                        input.to,
                        input.status
                    };
                    if (id == 0) {
                        saved =
                                Sql.insert(
                                        connection,
                                        "INSERT INTO"
                                            + " discount_policies(name,product_id,category_id,customer_group_id,mode,valid_from,valid_to,status)"
                                            + " VALUES(?,?,?,?,?,?,?,?)",
                                        parameters);
                    } else {
                        var params = new ArrayList<Object>(Arrays.asList(parameters));
                        params.add(id);
                        Sql.update(
                                connection,
                                "UPDATE discount_policies SET"
                                    + " name=?,product_id=?,category_id=?,customer_group_id=?,mode=?,valid_from=?,valid_to=?,status=?,revision=revision+1"
                                    + " WHERE id=?",
                                params.toArray());
                    }
                    for (var tier : input.tiers) {
                        Sql.insert(
                                connection,
                                "INSERT INTO"
                                    + " discount_tiers(policy_id,policy_revision,minimum_quantity,discount_value)"
                                    + " VALUES(?,?,?,?)",
                                saved,
                                revision,
                                CatalogValidation.decimal(tier.minimum, 6, false),
                                CatalogValidation.decimal(
                                        tier.value, "FIXED".equals(input.mode) ? 4 : 6, false));
                    }
                    var after =
                            Sql.one(
                                    connection,
                                    "SELECT * FROM discount_policies WHERE id=?",
                                    saved);
                    after.put(
                            "tiers",
                            Sql.query(
                                    connection,
                                    "SELECT minimum_quantity,discount_value FROM discount_tiers"
                                            + " WHERE policy_id=? AND policy_revision=?",
                                    saved,
                                    revision));
                    AuditService.record(
                            connection,
                            actor,
                            "DISCOUNT_POLICY_SAVED",
                            "DISCOUNT_POLICY",
                            saved,
                            before,
                            after);
                    return saved;
                });
    }

    // Chọn chiết khấu trên kết nối của đơn; bên gọi phải kiểm quyền và phạm vi trước.
    static DiscountMath.Selection select(
            Connection connection,
            long product,
            long group,
            LocalDate date,
            List<BigDecimal> baseQuantities,
            BigDecimal selling)
            throws SQLException {
        Sql.one(connection, "SELECT name FROM catalog_locks WHERE name='CATEGORY_TREE' FOR SHARE");
        Sql.one(
                connection,
                "SELECT name FROM catalog_locks WHERE name='DISCOUNT_POLICIES' FOR SHARE");
        var rows =
                Sql.query(
                        connection,
                        "WITH RECURSIVE ancestors AS (SELECT c.id,c.parent_id FROM categories c"
                            + " JOIN products p ON p.category_id=c.id WHERE p.id=? UNION ALL SELECT"
                            + " c.id,c.parent_id FROM categories c JOIN ancestors a ON"
                            + " c.id=a.parent_id) SELECT d.id policy_id,d.revision,t.id"
                            + " tier_id,d.name,d.mode,t.minimum_quantity,t.discount_value FROM"
                            + " discount_policies d JOIN discount_tiers t ON t.policy_id=d.id AND"
                            + " t.policy_revision=d.revision WHERE d.status='ACTIVE' AND"
                            + " d.valid_from<=? AND d.valid_to>=? AND (d.customer_group_id IS NULL"
                            + " OR d.customer_group_id=?) AND (d.product_id=? OR d.category_id IN"
                            + " (SELECT id FROM ancestors)) ORDER BY d.id,t.minimum_quantity"
                            + " DESC,t.id",
                        product,
                        date,
                        date,
                        group,
                        product);
        var tiers = new ArrayList<DiscountMath.Tier>();
        for (var row : rows) {
            tiers.add(
                    new DiscountMath.Tier(
                            Sql.id(row.get("policy_id")),
                            Sql.id(row.get("revision")),
                            Sql.id(row.get("tier_id")),
                            Sql.text(row.get("name")),
                            Sql.text(row.get("mode")),
                            (BigDecimal) row.get("minimum_quantity"),
                            (BigDecimal) row.get("discount_value")));
        }
        return DiscountMath.best(baseQuantities, selling, tiers);
    }

    // Kiểm tra dữ liệu và tạo bản xem trước để người dùng xác nhận.
    public DiscountMath.Selection preview(
            long actor, long product, long group, LocalDate date, List<BigDecimal> baseQuantities) {
        return Sql.transaction(
                source,
                connection -> {
                    var access = DealerService.access(connection, actor, "DISCOUNT_READ");
                    var quote = PricingService.quote(connection, access, group, product, date);
                    return select(
                            connection, product, group, date, baseQuantities, quote.sellingPrice());
                });
    }

    // Lưu lại dữ liệu tại thời điểm nghiệp vụ phát sinh.
    static void snapshot(
            Connection connection,
            String orderReference,
            long product,
            DiscountMath.Selection selection)
            throws SQLException {
        if (!selection.applied()) {
            return;
        }
        var t = selection.tier();
        Sql.insert(
                connection,
                "INSERT INTO"
                    + " discount_order_references(order_reference,product_id,policy_id,policy_revision,tier_id,policy_name,mode,discount_value)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                CatalogValidation.text(orderReference, 100, "Tham chiếu đơn"),
                product,
                t.policyId(),
                t.revision(),
                t.tierId(),
                t.name(),
                t.mode(),
                t.value());
    }
}
