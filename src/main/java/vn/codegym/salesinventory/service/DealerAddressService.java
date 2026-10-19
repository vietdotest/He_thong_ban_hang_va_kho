package vn.codegym.salesinventory.service;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.validation.*;

/**
 * S3-04: Quản lý các địa chỉ giao hàng của từng đại lý.
 */
public final class DealerAddressService {

    private final DataSource source;

    public DealerAddressService(DataSource source) {
        this.source = source;
    }

    // Chứa thông tin đầu vào của địa chỉ giao hàng.
    public record Input(
            String address,
            String recipient,
            String phone,
            String directions,
            boolean defaultPoint,
            String status,
            long version,
            long dealerVersion) {}

    // Kiểm tra dữ liệu đầu vào và gom lỗi theo từng trường.
    public static Map<String, String> errors(Input input) {
        var errors = new LinkedHashMap<String, String>();
        for (var field :
                List.of(
                        new Object[] {"address", input.address, 500, "Địa chỉ"},
                        new Object[] {"recipient", input.recipient, 150, "Người nhận"})) {
            try {
                CatalogValidation.text(
                        clean((String) field[1]), (int) field[2], field[3].toString());
            } catch (IllegalArgumentException exception) {
                errors.put(field[0].toString(), exception.getMessage());
            }
        }
        try {
            PhoneNumber.normalize(input.phone);
        } catch (IllegalArgumentException e) {
            errors.put("phone", e.getMessage());
        }
        if (clean(input.directions).length() > 1000) {
            errors.put("directions", "Ghi chú đường đi tối đa 1.000 ký tự.");
        }
        if (!Set.of("ACTIVE", "DISCONTINUED").contains(clean(input.status))) {
            errors.put("status", "Trạng thái không hợp lệ.");
        }
        if (input.defaultPoint && "DISCONTINUED".equals(input.status)) {
            errors.put("defaultPoint", "Điểm mặc định phải đang sử dụng.");
        }
        if (input.version < 0 || input.dealerVersion < 1) {
            errors.put("form", "Phiên bản không hợp lệ.");
        }
        return errors;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    // Kiểm tra địa chỉ thuộc đúng đại lý, khóa bản ghi khi cần cập nhật.
    public static Map<String, Object> belonging(
            Connection connection, long dealer, long id, boolean lock) throws SQLException {
        var rows =
                Sql.query(
                        connection,
                        "SELECT * FROM dealer_addresses WHERE dealer_id=? AND id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        dealer,
                        id);
        if (rows.isEmpty()) {
            throw new SecurityException("Điểm giao không thuộc đúng đại lý.");
        }
        return rows.get(0);
    }

    // Lấy danh sách địa chỉ giao hàng của đại lý theo điều kiện đầu vào.
    public PageResult<Map<String, Object>> list(
            long actor, long dealer, String query, PageRequest request) {
        String search = clean(query);
        if (search.length() > 150) {
            search = search.substring(0, 150);
        }
        String term = "%" + search + "%";
        return Sql.transaction(
                source,
                connection -> {
                    var access = DealerService.access(connection, actor, "DEALER_READ");
                    DealerService.scoped(connection, actor, access, dealer, false);
                    String filter =
                            " WHERE dealer_id=? AND (address LIKE ? OR recipient LIKE ? OR phone"
                                    + " LIKE ?)";
                    Object[] parameters = {dealer, term, term, term};
                    long total =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total FROM dealer_addresses"
                                                            + filter,
                                                    parameters)
                                            .get("total"));
                    var p = request.clamp(total);
                    var params = new ArrayList<Object>(Arrays.asList(parameters));
                    params.add(p.pageSize());
                    params.add(p.offset());
                    return new PageResult<>(
                            Sql.query(
                                    connection,
                                    "SELECT * FROM dealer_addresses"
                                            + filter
                                            + " ORDER BY is_default DESC,id LIMIT ? OFFSET ?",
                                    params.toArray()),
                            total,
                            p.page(),
                            p.pageSize());
                });
    }

    // Tìm địa chỉ giao hàng của đại lý theo mã hoặc điều kiện được cung cấp.
    public Map<String, Object> find(long actor, long dealer, long id) {
        return Sql.transaction(
                source,
                connection -> {
                    var access = DealerService.access(connection, actor, "DEALER_READ");
                    DealerService.scoped(connection, actor, access, dealer, false);
                    return belonging(connection, dealer, id, false);
                });
    }

    // Lưu thông tin địa chỉ giao hàng của đại lý sau khi kiểm tra dữ liệu.
    public long save(long actor, long dealer, long id, Input input) {
        var errors = errors(input);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        return Sql.transaction(
                source,
                connection -> {
                    var access = DealerService.access(connection, actor, "DEALER_ADDRESS_MANAGE");
                    if (!access.roles().stream()
                            .anyMatch(Set.of("SALES_MANAGER", "SALES")::contains)) {
                        throw new SecurityException("Không có quyền quản lý điểm giao.");
                    }
                    var parent = DealerService.scoped(connection, actor, access, dealer, true);
                    if (Sql.id(parent.get("version")) != input.dealerVersion) {
                        throw FieldValidationException.field(
                                "form", "Hồ sơ hoặc điểm mặc định đã thay đổi. Hãy tải lại.");
                    }
                    var before = id == 0 ? null : belonging(connection, dealer, id, true);
                    if (before != null && Sql.id(before.get("version")) != input.version) {
                        throw FieldValidationException.field(
                                "form", "Điểm giao đã thay đổi. Hãy tải lại.");
                    }
                    var defaults =
                            Sql.query(
                                    connection,
                                    "SELECT * FROM dealer_addresses WHERE dealer_id=? AND"
                                            + " is_default=1 FOR UPDATE",
                                    dealer);
                    boolean defaultPoint =
                            input.defaultPoint
                                    || (defaults.isEmpty() && "ACTIVE".equals(input.status));
                    if (before != null && isDefault(before.get("is_default")) && !defaultPoint) {
                        throw FieldValidationException.field(
                                "defaultPoint",
                                "Hãy đặt một điểm khác làm mặc định trước khi ngừng hoặc bỏ mặc"
                                        + " định điểm này.");
                    }
                    if (defaultPoint) {
                        for (var old : defaults) {
                            if (Sql.id(old.get("id")) != id) {
                                long oldId = Sql.id(old.get("id"));
                                Sql.update(
                                        connection,
                                        "UPDATE dealer_addresses SET is_default=0,version=version+1"
                                                + " WHERE id=?",
                                        oldId);
                                AuditService.record(
                                        connection,
                                        actor,
                                        "DEALER_ADDRESS_DEFAULT_CHANGED",
                                        "DEALER_ADDRESS",
                                        oldId,
                                        old,
                                        Sql.one(
                                                connection,
                                                "SELECT * FROM dealer_addresses WHERE id=?",
                                                oldId));
                            }
                        }
                    }
                    long saved = id;
                    Object[] parameters = {
                        clean(input.address),
                        clean(input.recipient),
                        PhoneNumber.normalize(input.phone),
                        clean(input.directions),
                        defaultPoint,
                        input.status
                    };
                    if (id == 0) {
                        var params = new ArrayList<Object>();
                        params.add(dealer);
                        params.addAll(Arrays.asList(parameters));
                        saved =
                                Sql.insert(
                                        connection,
                                        "INSERT INTO"
                                            + " dealer_addresses(dealer_id,address,recipient,phone,directions,is_default,status)"
                                            + " VALUES(?,?,?,?,?,?,?)",
                                        params.toArray());
                    } else {
                        var params = new ArrayList<Object>(Arrays.asList(parameters));
                        params.add(id);
                        Sql.update(
                                connection,
                                "UPDATE dealer_addresses SET"
                                    + " address=?,recipient=?,phone=?,directions=?,is_default=?,status=?,version=version+1"
                                    + " WHERE id=?",
                                params.toArray());
                    }
                    Sql.update(
                            connection, "UPDATE dealers SET version=version+1 WHERE id=?", dealer);
                    AuditService.record(
                            connection,
                            actor,
                            "DEALER_ADDRESS_SAVED",
                            "DEALER_ADDRESS",
                            saved,
                            before,
                            Sql.one(
                                    connection,
                                    "SELECT * FROM dealer_addresses WHERE id=?",
                                    saved));
                    return saved;
                });
    }

    public static boolean isDefault(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue() == 1);
    }
}
