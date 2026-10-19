package vn.codegym.salesinventory.service;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.validation.*;

/**
 * S3-03, S3-08: Quản lý hồ sơ và tìm đại lý trong phạm vi người dùng được phép xem.
 */
public final class DealerService {

    private final DataSource source;

    public DealerService(DataSource source) {
        this.source = source;
    }

    // Chứa thông tin đầu vào khi tạo hoặc sửa đại lý.
    public record Input(
            String code,
            String name,
            String taxCode,
            String phone,
            long group,
            long territory,
            long staff,
            Long warehouse,
            String status,
            long version) {}

    // Kiểm tra dữ liệu đầu vào và gom lỗi theo từng trường.
    public static Map<String, String> errors(Input input) {
        var errors = new LinkedHashMap<String, String>();
        try {
            CatalogValidation.code(clean(input.code), 50);
        } catch (IllegalArgumentException exception) {
            errors.put("code", exception.getMessage());
        }
        try {
            CatalogValidation.text(clean(input.name), 200, "Tên đại lý");
        } catch (IllegalArgumentException e) {
            errors.put("name", e.getMessage());
        }
        try {
            TaxCode.normalize(input.taxCode);
        } catch (IllegalArgumentException e) {
            errors.put("taxCode", e.getMessage());
        }
        try {
            PhoneNumber.normalize(input.phone);
        } catch (IllegalArgumentException e) {
            errors.put("phone", e.getMessage());
        }
        if (input.group <= 0) {
            errors.put("group", "Hãy chọn nhóm bảng giá.");
        }
        if (input.territory <= 0) {
            errors.put("territory", "Hãy chọn khu vực.");
        }
        if (input.staff <= 0) {
            errors.put("staff", "Hãy chọn người phụ trách chính.");
        }
        if (input.warehouse != null && input.warehouse <= 0) {
            errors.put("warehouse", "Kho không hợp lệ.");
        }
        if (!Set.of("ACTIVE", "DISCONTINUED").contains(clean(input.status))) {
            errors.put("status", "Trạng thái hồ sơ không hợp lệ.");
        }
        if (input.version < 0) {
            errors.put("form", "Phiên bản không hợp lệ.");
        }
        return errors;
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }

    // Nạp quyền và yêu cầu quyền nghiệp vụ trước khi xử lý đại lý.
    public static Access access(Connection connection, long actor, String permission)
            throws SQLException {
        // The same connection sees permission revocation and writes atomically. Lock actor first.
        var user = Sql.one(connection, "SELECT status FROM users WHERE id=? FOR SHARE", actor);
        if (!"ACTIVE".equals(user.get("status"))) {
            throw new SecurityException("Tài khoản không còn hoạt động.");
        }
        Set<String> roles = new HashSet<>(), permissions = new HashSet<>();
        for (var row :
                Sql.query(
                        connection,
                        "SELECT r.code FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE"
                                + " ur.user_id=? FOR SHARE",
                        actor)) {
            roles.add(Sql.text(row.get("code")));
        }
        for (var row :
                Sql.query(
                        connection,
                        "SELECT p.code FROM permissions p JOIN role_permissions rp ON"
                            + " rp.permission_id=p.id JOIN user_roles ur ON ur.role_id=rp.role_id"
                            + " WHERE ur.user_id=? FOR SHARE",
                        actor)) {
            permissions.add(Sql.text(row.get("code")));
        }
        var result = new Access(roles, permissions, List.of(), List.of(), List.of());
        result.require(permission);
        return result;
    }

    public static boolean all(Access access) {
        return access.allows("DEALER_READ_ALL")
                && access.roles().stream()
                        .anyMatch(Set.of("SALES_MANAGER", "ACCOUNTANT", "DIRECTOR")::contains);
    }

    // Lấy đại lý trong phạm vi được phép và khóa bản ghi khi có yêu cầu.
    public static Map<String, Object> scoped(
            Connection connection, long actor, Access access, long id, boolean lock)
            throws SQLException {
        access.require("DEALER_READ");
        var rows =
                Sql.query(
                        connection,
                        "SELECT d.* FROM dealers d WHERE d.id=? AND (? OR d.primary_staff_id=?)"
                                + (lock ? " FOR UPDATE" : ""),
                        id,
                        all(access),
                        actor);
        if (rows.isEmpty()) {
            throw new SecurityException("Đại lý không thuộc phạm vi được phép.");
        }
        return rows.get(0);
    }

    private static final String JOIN =
            " FROM dealers d JOIN customer_groups g ON g.id=d.group_id JOIN territories t ON"
                    + " t.id=d.territory_id JOIN users u ON u.id=d.primary_staff_id LEFT JOIN"
                    + " warehouses w ON w.id=d.warehouse_id LEFT JOIN (SELECT dealer_id,COUNT(*)"
                    + " total_addresses,SUM(status='ACTIVE') active_addresses FROM dealer_addresses"
                    + " GROUP BY dealer_id) adr ON adr.dealer_id=d.id";

    private static final String COLUMNS =
            "d.*,g.name group_name,t.name territory_name,u.full_name staff_name,w.name"
                    + " warehouse_name,COALESCE(adr.total_addresses,0)"
                    + " total_addresses,COALESCE(adr.active_addresses,0) active_addresses";

    // Chứa các điều kiện tìm kiếm đại lý.
    public record Filter(String query, Long territory, Long group, Long staff, String status) {}

    // Tìm hồ sơ đại lý theo bộ lọc và phân trang.
    public PageResult<Map<String, Object>> search(long actor, String query, PageRequest requested) {
        return search(actor, new Filter(query, null, null, null, ""), requested);
    }

    // Tìm hồ sơ đại lý theo bộ lọc và phân trang.
    public PageResult<Map<String, Object>> search(
            long actor, Filter filter, PageRequest requested) {
        String query = clean(filter.query);
        if (query.length() > 150) {
            query = query.substring(0, 150);
        }
        String pattern = "%" + query + "%";
        return Sql.snapshot(
                source,
                connection -> {
                    var access = access(connection, actor, "DEALER_READ");
                    String condition =
                            " WHERE (? OR d.primary_staff_id=?) AND (d.code LIKE ? OR d.name LIKE ?"
                                    + " OR d.tax_code LIKE ? OR d.phone LIKE ?) AND (? IS NULL OR"
                                    + " d.territory_id=?) AND (? IS NULL OR d.group_id=?) AND (? IS"
                                    + " NULL OR d.primary_staff_id=?) AND (?='' OR (?='LOCKED' AND"
                                    + " d.transaction_locked=1) OR (?<>'LOCKED' AND d.status=? AND"
                                    + " (?<>'ACTIVE' OR d.transaction_locked=0)))";
                    Object[] parameters = {
                        all(access),
                        actor,
                        pattern,
                        pattern,
                        pattern,
                        pattern,
                        filter.territory,
                        filter.territory,
                        filter.group,
                        filter.group,
                        filter.staff,
                        filter.staff,
                        clean(filter.status),
                        clean(filter.status),
                        clean(filter.status),
                        clean(filter.status),
                        clean(filter.status)
                    };
                    long count =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total FROM dealers d"
                                                            + condition,
                                                    parameters)
                                            .get("total"));
                    var paging = requested.clamp(count);
                    var params = new ArrayList<Object>(Arrays.asList(parameters));
                    params.add(paging.pageSize());
                    params.add(paging.offset());
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT "
                                            + COLUMNS
                                            + JOIN
                                            + condition
                                            + " ORDER BY d.code,d.id LIMIT ? OFFSET ?",
                                    params.toArray());
                    for (var row : rows) {
                        deletionState(connection, access, row);
                    }
                    return new PageResult<>(rows, count, paging.page(), paging.pageSize());
                });
    }

    // Tìm các lựa chọn hồ sơ đại lý phù hợp với từ khóa.
    public List<Map<String, Object>> suggest(long actor, String query) {
        String q = clean(query);
        if (q.length() > 150) {
            q = q.substring(0, 150);
        }
        final String term = q;
        return Sql.transaction(
                source,
                connection -> {
                    var access = access(connection, actor, "DEALER_READ");
                    if (term.length() < 2) {
                        return List.of();
                    }
                    return Sql.query(
                            connection,
                            "SELECT d.id,d.code,d.name FROM dealers d WHERE (? OR"
                                + " d.primary_staff_id=?) AND (d.code LIKE ? OR d.name LIKE ? OR"
                                + " d.phone LIKE ?) ORDER BY (d.code=?) DESC,(d.code LIKE ?)"
                                + " DESC,d.name,d.id LIMIT 10",
                            all(access),
                            actor,
                            "%" + term + "%",
                            "%" + term + "%",
                            "%" + term + "%",
                            term,
                            term + "%");
                });
    }

    // Lấy các lựa chọn cho bộ lọc hồ sơ đại lý.
    public Map<String, List<Map<String, Object>>> filterOptions(long actor) {
        return Sql.transaction(
                source,
                connection -> {
                    var access = access(connection, actor, "DEALER_READ");
                    Object[] parameters = {all(access), actor};
                    String condition = " WHERE (? OR d.primary_staff_id=?)";
                    return Map.of(
                            "filterGroups",
                            Sql.query(
                                    connection,
                                    "SELECT DISTINCT g.id,g.name FROM customer_groups g JOIN"
                                            + " dealers d ON d.group_id=g.id"
                                            + condition
                                            + " ORDER BY g.name,g.id",
                                    parameters),
                            "filterTerritories",
                            Sql.query(
                                    connection,
                                    "SELECT DISTINCT t.id,t.name FROM territories t JOIN dealers d"
                                            + " ON d.territory_id=t.id"
                                            + condition
                                            + " ORDER BY t.name,t.id",
                                    parameters),
                            "filterStaff",
                            Sql.query(
                                    connection,
                                    "SELECT DISTINCT u.id,u.full_name name FROM users u JOIN"
                                            + " dealers d ON d.primary_staff_id=u.id"
                                            + condition
                                            + " ORDER BY u.full_name,u.id",
                                    parameters));
                });
    }

    // Tìm hồ sơ đại lý theo mã hoặc điều kiện được cung cấp.
    public Map<String, Object> find(long actor, long id) {
        return Sql.transaction(
                source,
                connection -> {
                    var access = access(connection, actor, "DEALER_READ");
                    scoped(connection, actor, access, id, false);
                    return Sql.one(connection, "SELECT " + COLUMNS + JOIN + " WHERE d.id=?", id);
                });
    }

    // Lấy các lựa chọn cần dùng trên biểu mẫu hồ sơ đại lý.
    public Map<String, List<Map<String, Object>>> options(long actor) {
        return Sql.transaction(
                source,
                connection -> {
                    access(connection, actor, "DEALER_MANAGE");
                    return Map.of(
                            "groups",
                            Sql.query(
                                    connection,
                                    "SELECT id,code,name FROM customer_groups ORDER BY name,id"),
                            "territories",
                            Sql.query(
                                    connection,
                                    "SELECT id,code,name,address FROM territories ORDER BY"
                                            + " name,id"),
                            "staff",
                            Sql.query(
                                    connection,
                                    "SELECT DISTINCT u.id,u.username code,u.full_name name FROM"
                                        + " users u JOIN user_roles ur ON ur.user_id=u.id JOIN"
                                        + " roles r ON r.id=ur.role_id WHERE u.status='ACTIVE' AND"
                                        + " r.code IN ('SALES','SALES_MANAGER') ORDER BY"
                                        + " u.full_name,u.id"),
                            "warehouses",
                            Sql.query(
                                    connection,
                                    "SELECT id,code,name,address FROM warehouses ORDER BY"
                                            + " name,id"));
                });
    }

    // Lưu thông tin hồ sơ đại lý sau khi kiểm tra dữ liệu.
    public long save(long actor, long id, Input input) {
        var errors = errors(input);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        return Sql.transaction(
                source,
                connection -> {
                    var access = access(connection, actor, "DEALER_MANAGE");
                    Sql.one(
                            connection,
                            "SELECT name FROM catalog_locks WHERE name='DEALER_ASSIGNMENTS' FOR"
                                    + " UPDATE");
                    if (!access.roles().stream()
                            .anyMatch(Set.of("SALES_MANAGER", "ACCOUNTANT")::contains)) {
                        throw new SecurityException(
                                "Chỉ Quản lý kinh doanh hoặc Kế toán được sửa hồ sơ.");
                    }
                    Map<String, Object> before =
                            id == 0 ? null : scoped(connection, actor, access, id, true);
                    if (before != null && Sql.id(before.get("version")) != input.version) {
                        throw FieldValidationException.field(
                                "form", "Hồ sơ đã được người khác sửa. Hãy tải lại.");
                    }
                    if (!Sql.query(
                                    connection,
                                    "SELECT id FROM dealers WHERE code=? AND id<>?",
                                    clean(input.code),
                                    id)
                            .isEmpty()) {
                        throw FieldValidationException.field("code", "Mã đại lý đã tồn tại.");
                    }
                    String tax = TaxCode.normalize(input.taxCode);
                    if (!tax.isEmpty()
                            && !Sql.query(
                                            connection,
                                            "SELECT id FROM dealers WHERE"
                                                + " REPLACE(TRIM(tax_code),'-','')=? AND id<>? FOR"
                                                + " UPDATE",
                                            tax.replace("-", ""),
                                            id)
                                    .isEmpty()) {
                        throw FieldValidationException.field("taxCode", "Mã số thuế đã tồn tại.");
                    }
                    for (var check :
                            List.of(
                                    new Object[] {"customer_groups", input.group, "group"},
                                    new Object[] {"territories", input.territory, "territory"})) {
                        if (Sql.query(
                                        connection,
                                        "SELECT id FROM " + check[0] + " WHERE id=? FOR SHARE",
                                        check[1])
                                .isEmpty()) {
                            throw FieldValidationException.field(
                                    check[2].toString(), "Lựa chọn không còn tồn tại.");
                        }
                    }
                    if (Sql.query(
                                    connection,
                                    "SELECT u.id FROM users u JOIN user_roles ur ON ur.user_id=u.id"
                                            + " JOIN roles r ON r.id=ur.role_id WHERE u.id=? AND"
                                            + " u.status='ACTIVE' AND r.code IN"
                                            + " ('SALES','SALES_MANAGER') FOR SHARE",
                                    input.staff)
                            .isEmpty()) {
                        throw FieldValidationException.field(
                                "staff",
                                "Người phụ trách phải là nhân viên kinh doanh đang hoạt động.");
                    }
                    if (input.warehouse != null
                            && Sql.query(
                                            connection,
                                            "SELECT id FROM warehouses WHERE id=? FOR SHARE",
                                            input.warehouse)
                                    .isEmpty()) {
                        throw FieldValidationException.field("warehouse", "Kho không còn tồn tại.");
                    }
                    // Handover changes get a dedicated audited operation in SCRUM-42, not a silent
                    // profile edit.
                    if (before != null && Sql.id(before.get("primary_staff_id")) != input.staff) {
                        throw FieldValidationException.field(
                                "staff", "Dùng chức năng bàn giao để đổi người phụ trách.");
                    }
                    Object[] values = {
                        clean(input.code),
                        clean(input.name),
                        TaxCode.normalize(input.taxCode),
                        PhoneNumber.normalize(input.phone),
                        input.group,
                        input.territory,
                        input.staff,
                        input.warehouse,
                        input.status
                    };
                    long saved = id;
                    try {
                        if (id == 0) {
                            saved =
                                    Sql.insert(
                                            connection,
                                            "INSERT INTO"
                                                + " dealers(code,name,tax_code,phone,group_id,territory_id,primary_staff_id,warehouse_id,status)"
                                                + " VALUES(?,?,?,?,?,?,?,?,?)",
                                            values);
                        } else {
                            var params = new ArrayList<Object>(Arrays.asList(values));
                            params.add(id);
                            Sql.update(
                                    connection,
                                    "UPDATE dealers SET"
                                        + " code=?,name=?,tax_code=?,phone=?,group_id=?,territory_id=?,primary_staff_id=?,warehouse_id=?,status=?,version=version+1"
                                        + " WHERE id=?",
                                    params.toArray());
                        }
                    } catch (SQLException exception) {
                        if (exception.getErrorCode() == 1062) {
                            throw FieldValidationException.field(
                                    exception.getMessage().contains("uq_dealer_tax_code")
                                            ? "taxCode"
                                            : "code",
                                    exception.getMessage().contains("uq_dealer_tax_code")
                                            ? "Mã số thuế đã tồn tại."
                                            : "Mã đại lý đã tồn tại.");
                        }
                        throw exception;
                    }
                    syncReference(connection, saved, input.staff);
                    AuditService.record(
                            connection,
                            actor,
                            "DEALER_SAVED",
                            "DEALER",
                            saved,
                            before,
                            Sql.one(connection, "SELECT * FROM dealers WHERE id=?", saved));
                    return saved;
                });
    }

    // Đồng bộ liên kết nhân viên phụ trách sau khi lưu đại lý.
    public static void syncReference(Connection connection, long dealer, long staff)
            throws SQLException {
        String ref = "DEALER:" + dealer;
        Sql.update(connection, "DELETE FROM dealer_staff_references WHERE dealer_reference=?", ref);
        Sql.update(
                connection,
                "INSERT INTO dealer_staff_references(user_id,dealer_reference) VALUES(?,?)",
                staff,
                ref);
    }

    // Xác định lý do bản ghi chưa được phép xóa.
    private static String deleteBlockReason(Connection connection, Access access, long id)
            throws SQLException {
        if (!access.allows("DEALER_MANAGE")
                || Collections.disjoint(access.roles(), Set.of("SALES_MANAGER", "ACCOUNTANT"))) {
            return "Không có quyền xóa hồ sơ.";
        }
        if (!Sql.query(
                        connection,
                        "SELECT dealer_id FROM dealer_transaction_references WHERE dealer_id=?"
                                + " LIMIT 1",
                        id)
                .isEmpty()) {
            return "Đại lý đã có giao dịch. Chỉ được ngừng giao dịch.";
        }
        if (!Sql.query(connection, "SELECT id FROM dealer_addresses WHERE dealer_id=? LIMIT 1", id)
                .isEmpty()) {
            return "Đại lý đã có điểm giao hàng. Hãy ngừng giao dịch để bảo toàn lịch sử địa chỉ.";
        }
        if (!Sql.query(
                        connection,
                        "SELECT dealer_id FROM dealer_handover_items WHERE dealer_id=? LIMIT 1",
                        id)
                .isEmpty()) {
            return "Đại lý đã có bản bàn giao. Hãy ngừng giao dịch để giữ lịch sử.";
        }
        if (!Sql.query(
                        connection,
                        "SELECT id FROM dealer_credit_history WHERE dealer_id=? LIMIT 1",
                        id)
                .isEmpty()) {
            return "Đại lý đã có lịch sử tín dụng. Hãy ngừng giao dịch để giữ lịch sử.";
        }
        if (!Sql.query(
                        connection,
                        "SELECT id FROM dealer_status_history WHERE dealer_id=? LIMIT 1",
                        id)
                .isEmpty()) {
            return "Đại lý đã có lịch sử khóa/mở. Hãy ngừng giao dịch để giữ lịch sử.";
        }
        if (!Sql.query(
                                connection,
                                "SELECT user_id FROM dealer_accounts WHERE dealer_id=? LIMIT 1",
                                id)
                        .isEmpty()
                || !Sql.query(
                                connection,
                                "SELECT id FROM dealer_account_link_history WHERE old_dealer_id=?"
                                        + " OR new_dealer_id=? LIMIT 1",
                                id,
                                id)
                        .isEmpty()) {
            return "Đại lý đã có tài khoản cổng hoặc lịch sử liên kết. Hãy ngừng giao dịch để giữ"
                    + " lịch sử.";
        }
        return "";
    }

    private static void deletionState(Connection connection, Access access, Map<String, Object> row)
            throws SQLException {
        String reason = deleteBlockReason(connection, access, Sql.id(row.get("id")));
        row.put("canDelete", reason.isEmpty());
        row.put("deleteBlockReason", reason);
    }

    // Xóa hồ sơ đại lý sau khi kiểm tra điều kiện cho phép.
    public void delete(long actor, long id, long version) {
        Sql.transaction(
                source,
                connection -> {
                    var access = access(connection, actor, "DEALER_MANAGE");
                    Sql.one(
                            connection,
                            "SELECT name FROM catalog_locks WHERE name='DEALER_ASSIGNMENTS' FOR"
                                    + " UPDATE");
                    var before = scoped(connection, actor, access, id, true);
                    if (!access.roles().stream()
                            .anyMatch(Set.of("SALES_MANAGER", "ACCOUNTANT")::contains)) {
                        throw new SecurityException("Không có quyền xóa hồ sơ.");
                    }
                    if (Sql.id(before.get("version")) != version) {
                        throw new IllegalArgumentException("Hồ sơ đã thay đổi, hãy tải lại.");
                    }
                    String reason = deleteBlockReason(connection, access, id);
                    if (!reason.isEmpty()) {
                        throw new IllegalArgumentException(reason);
                    }
                    Sql.update(
                            connection,
                            "DELETE FROM dealer_staff_references WHERE dealer_reference=?",
                            "DEALER:" + id);
                    Sql.update(connection, "DELETE FROM dealers WHERE id=?", id);
                    AuditService.record(
                            connection, actor, "DEALER_DELETED", "DEALER", id, before, null);
                    return null;
                });
    }
}
