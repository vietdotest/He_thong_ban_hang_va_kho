package vn.codegym.salesinventory.service;

import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.*;
import vn.codegym.salesinventory.security.Access;

/**
 * S1-09: Gán nhiều vai trò, kho và địa bàn cho người dùng.
 */
public final class AssignmentService {

    private final DataSource source;

    public AssignmentService(DataSource source) {
        this.source = source;
    }

    // Kiểm tra dữ liệu trước khi tiếp tục xử lý.
    public static void validate(long actor, long target, Set<String> roles, Set<Long> warehouses) {
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("Phải chọn ít nhất một vai trò.");
        }
        if (roles.contains("DEALER")) {
            throw new IllegalArgumentException(
                    "Tài khoản đại lý được cấp riêng qua cổng, không gán vai trò nội bộ.");
        }
        if (actor == target && !roles.contains("ADMIN")) {
            throw new IllegalArgumentException("Không thể tự thu hồi vai trò quản trị.");
        }
        if ((roles.contains("WAREHOUSE") || roles.contains("WAREHOUSE_MANAGER"))
                && warehouses.isEmpty()) {
            throw new IllegalArgumentException("Vai trò kho phải gắn với ít nhất một kho.");
        }
    }

    // Thay danh sách phân công bằng các giá trị đã được kiểm tra.
    public static void replace(
            java.sql.Connection connection,
            long target,
            Set<String> roles,
            Set<Long> warehouses,
            Set<Long> territories)
            throws java.sql.SQLException {
        if (roles.contains("DEALER")
                || "DEALER"
                        .equals(
                                Sql.one(
                                                connection,
                                                "SELECT account_kind FROM users WHERE id=? FOR"
                                                        + " UPDATE",
                                                target)
                                        .get("account_kind"))) {
            throw new SecurityException("Không thay vai trò tài khoản cổng qua quản trị nội bộ.");
        }
        for (String role : roles) {
            Sql.one(connection, "SELECT id FROM roles WHERE code=?", role);
        }
        for (long id : warehouses) {
            Sql.one(connection, "SELECT id FROM warehouses WHERE id=?", id);
        }
        for (long id : territories) {
            Sql.one(connection, "SELECT id FROM territories WHERE id=?", id);
        }
        Sql.update(connection, "DELETE FROM user_roles WHERE user_id=?", target);
        for (String role : roles) {
            Sql.update(
                    connection,
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",
                    target,
                    role);
        }
        Sql.update(connection, "DELETE FROM user_warehouses WHERE user_id=?", target);
        for (long id : warehouses) {
            Sql.update(connection, "INSERT INTO user_warehouses VALUES(?,?)", target, id);
        }
        Sql.update(connection, "DELETE FROM user_territories WHERE user_id=?", target);
        for (long id : territories) {
            Sql.update(connection, "INSERT INTO user_territories VALUES(?,?)", target, id);
        }
    }

    // Kiểm tra và lưu vai trò, kho, địa bàn được gán cho tài khoản.
    public void assign(
            long actor,
            long target,
            Set<String> roles,
            Set<Long> warehouses,
            Set<Long> territories) {
        new AccessService(source).load(actor).require("USER_MANAGE");
        validate(actor, target, roles, warehouses);
        Sql.transaction(
                source,
                connection -> {
                    Sql.one(connection, "SELECT id FROM users WHERE id=? FOR UPDATE", target);
                    var before =
                            java.util.Map.of(
                                    "roles",
                                    Sql.query(
                                                    connection,
                                                    "SELECT r.code FROM roles r JOIN user_roles ur"
                                                        + " ON ur.role_id=r.id WHERE ur.user_id=?",
                                                    target)
                                            .toString(),
                                    "warehouses",
                                    Sql.query(
                                                    connection,
                                                    "SELECT warehouse_id FROM user_warehouses WHERE"
                                                            + " user_id=?",
                                                    target)
                                            .toString(),
                                    "territories",
                                    Sql.query(
                                                    connection,
                                                    "SELECT territory_id FROM user_territories"
                                                            + " WHERE user_id=?",
                                                    target)
                                            .toString());
                    replace(connection, target, roles, warehouses, territories);
                    AuditService.record(
                            connection,
                            actor,
                            "USER_ASSIGNMENTS_UPDATED",
                            "USER",
                            target,
                            before,
                            java.util.Map.of(
                                    "roles",
                                    roles.toString(),
                                    "warehouses",
                                    warehouses.toString(),
                                    "territories",
                                    territories.toString()));
                    return null;
                });
    }
}
