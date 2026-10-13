package vn.codegym.salesinventory.service;

import java.sql.*;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.security.Access;

/** All authorization uses the same connection as the business transaction. */
public final class MvpAccess {
    private MvpAccess() { }
    public static Access internal(Connection c,long actor,String permission) throws SQLException {
        var access=DealerService.access(c,actor,permission);
        if(access.roles().contains("DEALER")) throw new SecurityException("Tài khoản đại lý không được thao tác nội bộ.");return access;
    }
    public static Access warehouse(Connection c,long actor,String permission,long warehouse) throws SQLException {
        var a=stockOperator(c,actor,permission);
        if(Sql.query(c,"SELECT warehouse_id FROM user_warehouses WHERE user_id=? AND warehouse_id=? FOR SHARE",actor,warehouse).isEmpty())
            throw new SecurityException("Kho không thuộc phạm vi được giao.");return a;
    }
    public static Access stockOperator(Connection c,long actor,String permission) throws SQLException {
        var a=internal(c,actor,permission);
        if(Collections.disjoint(a.roles(),Set.of("WAREHOUSE","WAREHOUSE_MANAGER"))) throw new SecurityException();return a;
    }
    public static Access warehouseManager(Connection c,long actor,String permission,long warehouse) throws SQLException {
        var a=warehouse(c,actor,permission,warehouse);if(!a.roles().contains("WAREHOUSE_MANAGER")) throw new SecurityException();return a;
    }
    public static Access accountant(Connection c,long actor,String permission) throws SQLException {
        var a=internal(c,actor,permission);if(!a.roles().contains("ACCOUNTANT")) throw new SecurityException();return a;
    }
    public static Map<String,Object> dealer(Connection c,long actor,String permission,long dealer,boolean lock) throws SQLException {
        var a=internal(c,actor,permission);return DealerService.scoped(c,actor,a,dealer,lock);
    }
    public static boolean allDealers(Access a) { return DealerService.all(a); }
    public static boolean canReadWarehouse(Connection c,long actor,Access a,long warehouse) throws SQLException {
        if(!Collections.disjoint(a.roles(),Set.of("SALES_MANAGER","DIRECTOR","ACCOUNTANT"))) return true;
        if(!Sql.query(c,"SELECT warehouse_id FROM user_warehouses WHERE user_id=? AND warehouse_id=?",actor,warehouse).isEmpty()
                &&!Collections.disjoint(a.roles(),Set.of("WAREHOUSE","WAREHOUSE_MANAGER"))) return true;
        return a.roles().contains("SALES")&&!Sql.query(c,"SELECT id FROM dealers WHERE primary_staff_id=? AND warehouse_id=?",actor,warehouse).isEmpty();
    }
}
