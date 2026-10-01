package vn.codegym.salesinventory.service;

import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.*;
import vn.codegym.salesinventory.security.Access;

public final class AssignmentService {
    private final DataSource source;
    public AssignmentService(DataSource source) { this.source=source; }
    public static void validate(long actor,long target,Set<String> roles,Set<Long> warehouses) {
        if(roles.isEmpty()) throw new IllegalArgumentException("Phải chọn ít nhất một vai trò.");
        if(actor==target && !roles.contains("ADMIN")) throw new IllegalArgumentException("Không thể tự thu hồi vai trò quản trị.");
        if((roles.contains("WAREHOUSE") || roles.contains("WAREHOUSE_MANAGER")) && warehouses.isEmpty()) throw new IllegalArgumentException("Vai trò kho phải gắn với ít nhất một kho.");
    }
    public void assign(long actor,long target,Set<String> roles,Set<Long> warehouses,Set<Long> territories) {
        new AccessService(source).load(actor).require("USER_MANAGE");
        validate(actor,target,roles,warehouses);
        Sql.transaction(source,c -> {
            Sql.one(c,"SELECT id FROM users WHERE id=? FOR UPDATE",target);
            for(String role:roles) Sql.one(c,"SELECT id FROM roles WHERE code=?",role);
            for(long id:warehouses) Sql.one(c,"SELECT id FROM warehouses WHERE id=?",id);
            for(long id:territories) Sql.one(c,"SELECT id FROM territories WHERE id=?",id);
            Sql.update(c,"DELETE FROM user_roles WHERE user_id=?",target);
            for(String role:roles) Sql.update(c,"INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",target,role);
            Sql.update(c,"DELETE FROM user_warehouses WHERE user_id=?",target);
            for(long id:warehouses) Sql.update(c,"INSERT INTO user_warehouses VALUES(?,?)",target,id);
            Sql.update(c,"DELETE FROM user_territories WHERE user_id=?",target);
            for(long id:territories) Sql.update(c,"INSERT INTO user_territories VALUES(?,?)",target,id);
            new JdbcAuditLogRepository().record(c,actor,"USER_ASSIGNMENTS_UPDATED","targetUserId="+target+";roles="+roles+";warehouses="+warehouses+";territories="+territories,null,null,java.time.Instant.now());
            return null;
        });
    }
}
