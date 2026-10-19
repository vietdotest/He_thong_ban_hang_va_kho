package vn.codegym.salesinventory.service;

import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.security.Access;

/**
 * S1-05, S1-06: Nạp quyền, vai trò và phạm vi kho, địa bàn của người dùng.
 */
public final class AccessService {

    private final DataSource source;

    public AccessService(DataSource source) {
        this.source = source;
    }

    // Nạp dữ liệu quyền truy cập cho màn hình.
    public Access load(long userId) {
        return Sql.transaction(
                source,
                connection -> {
                    var roles =
                            Sql.query(
                                    connection,
                                    "SELECT r.id,r.code,r.name FROM roles r JOIN user_roles ur ON"
                                            + " ur.role_id=r.id WHERE ur.user_id=?",
                                    userId);
                    Set<String> roleCodes = new HashSet<>();
                    for (var row : roles) {
                        roleCodes.add(Sql.text(row.get("code")));
                    }
                    Set<String> permissions = new HashSet<>();
                    for (var p :
                            Sql.query(
                                    connection,
                                    "SELECT DISTINCT p.code FROM permissions p JOIN"
                                            + " role_permissions rp ON rp.permission_id=p.id JOIN"
                                            + " user_roles ur ON ur.role_id=rp.role_id WHERE"
                                            + " ur.user_id=?",
                                    userId)) {
                        permissions.add(Sql.text(p.get("code")));
                    }
                    return new Access(
                            roleCodes,
                            permissions,
                            roles,
                            Sql.query(
                                    connection,
                                    "SELECT w.id,w.code,w.name,w.address FROM warehouses w JOIN"
                                            + " user_warehouses uw ON uw.warehouse_id=w.id WHERE"
                                            + " uw.user_id=? ORDER BY w.name,w.id",
                                    userId),
                            Sql.query(
                                    connection,
                                    "SELECT t.id,t.code,t.name,t.address FROM territories t JOIN"
                                            + " user_territories ut ON ut.territory_id=t.id WHERE"
                                            + " ut.user_id=? ORDER BY t.name,t.id",
                                    userId),
                            Sql.text(
                                    Sql.one(
                                                    connection,
                                                    "SELECT avatar_key FROM users WHERE id=?",
                                                    userId)
                                            .get("avatar_key")));
                });
    }
}
