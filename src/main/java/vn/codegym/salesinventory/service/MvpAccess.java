package vn.codegym.salesinventory.service;

import java.sql.*;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.security.Access;

/**
 * S1-05, dùng chung: Kiểm quyền và phạm vi ngay trên kết nối đang xử lý giao dịch nghiệp vụ.
 */
public final class MvpAccess {

    private MvpAccess() {}

    // Kiểm tra quyền nghiệp vụ và từ chối tài khoản chỉ dành cho cổng đại lý.
    public static Access internal(Connection connection, long actor, String permission)
            throws SQLException {
        var access = DealerService.access(connection, actor, permission);
        if (access.roles().contains("DEALER")) {
            throw new SecurityException("Tài khoản đại lý không được thao tác nội bộ.");
        }
        return access;
    }

    // Kiểm tra quyền và phạm vi kho trước khi thao tác.
    public static Access warehouse(
            Connection connection, long actor, String permission, long warehouse)
            throws SQLException {
        var access = stockOperator(connection, actor, permission);
        if (Sql.query(
                        connection,
                        "SELECT warehouse_id FROM user_warehouses WHERE user_id=? AND"
                                + " warehouse_id=? FOR SHARE",
                        actor,
                        warehouse)
                .isEmpty()) {
            throw new SecurityException("Kho không thuộc phạm vi được giao.");
        }
        return access;
    }

    // Kiểm tra quyền của người thực hiện nghiệp vụ kho.
    public static Access stockOperator(Connection connection, long actor, String permission)
            throws SQLException {
        var access = internal(connection, actor, permission);
        if (Collections.disjoint(access.roles(), Set.of("WAREHOUSE", "WAREHOUSE_MANAGER"))) {
            throw new SecurityException();
        }
        return access;
    }

    // Kiểm tra quyền quản lý và phạm vi kho được yêu cầu.
    public static Access warehouseManager(
            Connection connection, long actor, String permission, long warehouse)
            throws SQLException {
        var access = warehouse(connection, actor, permission, warehouse);
        if (!access.roles().contains("WAREHOUSE_MANAGER")) {
            throw new SecurityException();
        }
        return access;
    }

    // Kiểm tra quyền và vai trò kế toán cho nghiệp vụ tài chính.
    public static Access accountant(Connection connection, long actor, String permission)
            throws SQLException {
        var access = internal(connection, actor, permission);
        if (!access.roles().contains("ACCOUNTANT")) {
            throw new SecurityException();
        }
        return access;
    }

    // Lấy đại lý theo quyền và phạm vi, có thể khóa khi xử lý giao dịch.
    public static Map<String, Object> dealer(
            Connection connection, long actor, String permission, long dealer, boolean lock)
            throws SQLException {
        var access = internal(connection, actor, permission);
        return DealerService.scoped(connection, actor, access, dealer, lock);
    }

    public static boolean allDealers(Access access) {
        return DealerService.all(access);
    }

    // Kiểm tra người dùng có được đọc số liệu của kho.
    public static boolean canReadWarehouse(
            Connection connection, long actor, Access access, long warehouse) throws SQLException {
        if (!Collections.disjoint(
                access.roles(), Set.of("SALES_MANAGER", "DIRECTOR", "ACCOUNTANT"))) {
            return true;
        }
        if (!Sql.query(
                                connection,
                                "SELECT warehouse_id FROM user_warehouses WHERE user_id=? AND"
                                        + " warehouse_id=?",
                                actor,
                                warehouse)
                        .isEmpty()
                && !Collections.disjoint(
                        access.roles(), Set.of("WAREHOUSE", "WAREHOUSE_MANAGER"))) {
            return true;
        }
        return access.roles().contains("SALES")
                && !Sql.query(
                                connection,
                                "SELECT id FROM dealers WHERE primary_staff_id=? AND"
                                        + " warehouse_id=?",
                                actor,
                                warehouse)
                        .isEmpty();
    }
}
