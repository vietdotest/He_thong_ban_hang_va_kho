package vn.codegym.salesinventory.service;

import java.util.*;
import java.sql.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.validation.*;

/**
 * S2-09: Quản lý thông tin nhà cung cấp và kiểm tra dữ liệu liên quan.
 */
public final class SupplierService {

    private final DataSource source;

    private static final String FIELDS =
            "id,code,name,tax_code,contact_name,contact_phone,payment_terms,warehouse_id,status,version";

    public SupplierService(DataSource source) {
        this.source = source;
    }

    // Chứa dữ liệu biểu mẫu nhà cung cấp.
    public record Input(
            String code,
            String name,
            String taxCode,
            String contact,
            String phone,
            String terms,
            long warehouse,
            String status,
            long version) {}

    public List<Map<String, Object>> list(long actor, String query) {
        return search(actor, query, new PageRequest(1, 100)).items();
    }

    // Tìm nhà cung cấp theo bộ lọc và phân trang.
    public PageResult<Map<String, Object>> search(long actor, String query, PageRequest requested) {
        String q = term(query), pattern = "%" + q + "%";
        return Sql.snapshot(
                source,
                connection -> {
                    var access = access(connection, actor, "CATALOG_READ");
                    String condition =
                            " WHERE (s.code LIKE ? OR s.name LIKE ?) AND (? OR EXISTS(SELECT 1 FROM"
                                    + " user_warehouses uw WHERE uw.user_id=? AND"
                                    + " uw.warehouse_id=s.warehouse_id))";
                    var parameters =
                            new ArrayList<Object>(
                                    List.of(pattern, pattern, !access.warehouseScoped(), actor));
                    long total =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total FROM suppliers s JOIN"
                                                            + " warehouses w ON w.id=s.warehouse_id"
                                                            + condition,
                                                    parameters.toArray())
                                            .get("total"));
                    var page = requested.clamp(total);
                    parameters.add(page.pageSize());
                    parameters.add(page.offset());
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT s.*,w.name warehouse_name FROM suppliers s JOIN"
                                            + " warehouses w ON w.id=s.warehouse_id"
                                            + condition
                                            + " ORDER BY s.name,s.id LIMIT ? OFFSET ?",
                                    parameters.toArray());
                    return new PageResult<>(rows, total, page.page(), page.pageSize());
                });
    }

    // Tìm các lựa chọn nhà cung cấp phù hợp với từ khóa.
    public List<Map<String, Object>> suggest(long actor, String query) {
        String q = term(query);
        return Sql.snapshot(
                source,
                connection -> {
                    var access = access(connection, actor, "CATALOG_READ");
                    if (q.length() < 2) {
                        return List.of();
                    }
                    return Sql.query(
                            connection,
                            "SELECT s.id,s.code,s.name,w.name detail FROM suppliers s JOIN"
                                + " warehouses w ON w.id=s.warehouse_id WHERE (s.code LIKE ? OR"
                                + " s.name LIKE ?) AND (? OR EXISTS(SELECT 1 FROM user_warehouses"
                                + " uw WHERE uw.user_id=? AND uw.warehouse_id=s.warehouse_id))"
                                + " ORDER BY (s.code=?) DESC,(s.code LIKE ?) DESC,s.name,s.id LIMIT"
                                + " 10",
                            "%" + q + "%",
                            "%" + q + "%",
                            !access.warehouseScoped(),
                            actor,
                            q,
                            q + "%");
                });
    }

    private static String term(String query) {
        String q = query == null ? "" : query.trim();
        return q.length() > 150 ? q.substring(0, 150) : q;
    }

    private static Access access(Connection connection, long actor, String permission)
            throws SQLException {
        var access = DealerService.access(connection, actor, permission);
        return new Access(
                access.roles(),
                access.permissions(),
                access.roleNames(),
                Sql.query(
                        connection,
                        "SELECT w.id,w.code,w.name,w.address FROM warehouses w JOIN user_warehouses"
                            + " uw ON uw.warehouse_id=w.id WHERE uw.user_id=? ORDER BY w.name,w.id"
                            + " FOR SHARE",
                        actor),
                List.of());
    }

    // Tìm nhà cung cấp theo mã hoặc điều kiện được cung cấp.
    public Map<String, Object> find(long actor, long id) {
        return Sql.transaction(
                source,
                connection -> {
                    var access = access(connection, actor, "CATALOG_READ");
                    var row =
                            Sql.one(
                                    connection,
                                    "SELECT " + FIELDS + " FROM suppliers WHERE id=?",
                                    id);
                    if (access.warehouseScoped()
                            && !access.managesWarehouse(Sql.id(row.get("warehouse_id")))) {
                        throw new SecurityException();
                    }
                    return row;
                });
    }

    // Kiểm tra dữ liệu đầu vào và gom lỗi theo từng trường.
    public static Map<String, String> errors(Input input) {
        var errors = new LinkedHashMap<String, String>();
        check(
                errors,
                "code",
                () -> CatalogValidation.code(input.code == null ? null : input.code.trim(), 50));
        check(errors, "name", () -> CatalogValidation.text(input.name, 200, "Tên nhà cung cấp"));
        check(errors, "contact", () -> CatalogValidation.text(input.contact, 150, "Người liên hệ"));
        check(
                errors,
                "terms",
                () -> CatalogValidation.text(input.terms, 250, "Điều khoản thanh toán"));
        check(errors, "phone", () -> PhoneNumber.normalize(input.phone));
        if (input.taxCode == null || !input.taxCode.trim().matches("[0-9]{10}(-?[0-9]{3})?")) {
            errors.put("taxCode", "Mã số thuế gồm 10 hoặc 13 chữ số.");
        }
        if (input.status == null || !Set.of("ACTIVE", "DISCONTINUED").contains(input.status)) {
            errors.put("status", "Trạng thái không hợp lệ.");
        }
        if (input.warehouse <= 0) {
            errors.put("warehouse", "Kho không hợp lệ.");
        }
        if (input.version < 0) {
            errors.put("form", "Phiên bản không hợp lệ.");
        }
        return errors;
    }

    // Chạy kiểm tra và ghi lỗi vào trường nhà cung cấp tương ứng.
    private static void check(Map<String, String> errors, String field, Runnable check) {
        try {
            check.run();
        } catch (IllegalArgumentException invalid) {
            errors.put(field, invalid.getMessage());
        }
    }

    // Lưu thông tin nhà cung cấp sau khi kiểm tra dữ liệu.
    public long save(long actor, long id, Input input) {
        var errors = errors(input);
        if (id < 0) {
            errors.put("form", "Mã bản ghi không hợp lệ.");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        String code = input.code.trim(),
                name = input.name.trim(),
                tax = input.taxCode.trim(),
                contact = input.contact.trim(),
                terms = input.terms.trim(),
                phone = PhoneNumber.normalize(input.phone);
        return Sql.transaction(
                source,
                connection -> {
                    var access = access(connection, actor, "WAREHOUSE_MANAGE");
                    if (!access.managesWarehouse(input.warehouse)) {
                        throw new SecurityException();
                    }
                    Map<String, Object> before = null;
                    if (id != 0) {
                        before =
                                Sql.one(
                                        connection,
                                        "SELECT "
                                                + FIELDS
                                                + " FROM suppliers WHERE id=? FOR UPDATE",
                                        id);
                        if (!access.managesWarehouse(Sql.id(before.get("warehouse_id")))) {
                            throw new SecurityException();
                        }
                        if (Sql.id(before.get("version")) != input.version) {
                            throw FieldValidationException.field(
                                    "form", "Nhà cung cấp đã thay đổi. Hãy tải lại.");
                        }
                    }
                    if (Sql.query(
                                    connection,
                                    "SELECT id FROM warehouses WHERE id=? FOR SHARE",
                                    input.warehouse)
                            .isEmpty()) {
                        throw FieldValidationException.field("warehouse", "Kho không còn tồn tại.");
                    }
                    if (!Sql.query(
                                    connection,
                                    "SELECT id FROM suppliers WHERE code=? AND id<>?",
                                    code,
                                    id)
                            .isEmpty()) {
                        throw FieldValidationException.field("code", "Mã nhà cung cấp đã tồn tại.");
                    }
                    long saved = id;
                    try {
                        if (id == 0) {
                            saved =
                                    Sql.insert(
                                            connection,
                                            "INSERT INTO"
                                                + " suppliers(code,name,tax_code,contact_name,contact_phone,payment_terms,warehouse_id,status)"
                                                + " VALUES(?,?,?,?,?,?,?,?)",
                                            code,
                                            name,
                                            tax,
                                            contact,
                                            phone,
                                            terms,
                                            input.warehouse,
                                            input.status);
                        } else {
                            Sql.update(
                                    connection,
                                    "UPDATE suppliers SET"
                                        + " code=?,name=?,tax_code=?,contact_name=?,contact_phone=?,payment_terms=?,warehouse_id=?,status=?,version=version+1"
                                        + " WHERE id=?",
                                    code,
                                    name,
                                    tax,
                                    contact,
                                    phone,
                                    terms,
                                    input.warehouse,
                                    input.status,
                                    id);
                        }
                    } catch (java.sql.SQLException failure) {
                        String message =
                                String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
                        if (failure.getErrorCode() == 1062
                                && "23000".equals(failure.getSQLState())
                                && (message.contains("'code'")
                                        || message.contains("'suppliers.code'"))) {
                            throw FieldValidationException.field(
                                    "code", "Mã nhà cung cấp đã tồn tại.");
                        }
                        throw failure;
                    }
                    AuditService.record(
                            connection,
                            actor,
                            "SUPPLIER_SAVED",
                            "SUPPLIER",
                            saved,
                            before,
                            Sql.one(
                                    connection,
                                    "SELECT " + FIELDS + " FROM suppliers WHERE id=?",
                                    saved));
                    return saved;
                });
    }

    // Xóa nhà cung cấp sau khi kiểm tra điều kiện cho phép.
    public void delete(long actor, long id) {
        Sql.transaction(
                source,
                connection -> {
                    var access = access(connection, actor, "WAREHOUSE_MANAGE");
                    var before =
                            Sql.one(
                                    connection,
                                    "SELECT " + FIELDS + " FROM suppliers WHERE id=? FOR UPDATE",
                                    id);
                    if (!access.managesWarehouse(Sql.id(before.get("warehouse_id")))) {
                        throw new SecurityException();
                    }
                    if (!Sql.query(
                                    connection,
                                    "SELECT supplier_id FROM supplier_receipt_references WHERE"
                                            + " supplier_id=? LIMIT 1",
                                    id)
                            .isEmpty()) {
                        throw FieldValidationException.field(
                                "form", "Nhà cung cấp đã có phiếu nhập. Hãy ngừng giao dịch.");
                    }
                    Sql.update(connection, "DELETE FROM suppliers WHERE id=?", id);
                    AuditService.record(
                            connection, actor, "SUPPLIER_DELETED", "SUPPLIER", id, before, null);
                    return null;
                });
    }
}
