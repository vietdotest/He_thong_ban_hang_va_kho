package vn.codegym.salesinventory.service;

import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.security.Access;

public final class AccessService {
    private final DataSource source;
    public AccessService(DataSource source) { this.source=source; }
    public Access load(long userId) {
        return Sql.transaction(source,c -> {
            var roles=Sql.query(c,"SELECT r.id,r.code,r.name FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=?",userId);
            Set<String> roleCodes=new HashSet<>(); for(var r:roles) roleCodes.add(Sql.text(r.get("code")));
            Set<String> permissions=new HashSet<>();
            for(var p:Sql.query(c,"SELECT DISTINCT p.code FROM permissions p JOIN role_permissions rp ON rp.permission_id=p.id JOIN user_roles ur ON ur.role_id=rp.role_id WHERE ur.user_id=?",userId)) permissions.add(Sql.text(p.get("code")));
            return new Access(roleCodes,permissions,roles,
                Sql.query(c,"SELECT w.id,w.code,w.name FROM warehouses w JOIN user_warehouses uw ON uw.warehouse_id=w.id WHERE uw.user_id=?",userId),
                Sql.query(c,"SELECT t.id,t.code,t.name FROM territories t JOIN user_territories ut ON ut.territory_id=t.id WHERE ut.user_id=?",userId),
                Sql.text(Sql.one(c,"SELECT avatar_key FROM users WHERE id=?",userId).get("avatar_key")));
        });
    }
}
