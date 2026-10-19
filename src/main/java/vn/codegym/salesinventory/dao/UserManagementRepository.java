package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import vn.codegym.salesinventory.dto.UserAccountCommand;
import vn.codegym.salesinventory.dto.UserSearchCriteria;
import vn.codegym.salesinventory.model.ManagedUser;
import vn.codegym.salesinventory.model.RoleOption;

/**
 * S1-08: Quy định các thao tác đọc, ghi tài khoản trên màn hình quản trị trên kết nối do service cung cấp.
 */
public interface UserManagementRepository {

    // Kiểm tra tài khoản có liên kết với cổng đại lý.
    boolean isPortalAccount(Connection connection, long userId) throws SQLException;

    // Kiểm tra người dùng đang có quyền được yêu cầu.
    default boolean userHasPermission(java.sql.Connection connection, long id, String code)
            throws java.sql.SQLException {
        return !Sql.query(
                        connection,
                        "SELECT 1 FROM user_roles ur JOIN role_permissions rp ON"
                            + " rp.role_id=ur.role_id JOIN permissions p ON p.id=rp.permission_id"
                            + " WHERE ur.user_id=? AND p.code=? LIMIT 1",
                        id,
                        code)
                .isEmpty();
    }

    // Kiểm tra người dùng đang được gán vai trò được yêu cầu.
    boolean userHasRole(Connection connection, long userId, String roleCode) throws SQLException;

    // Kiểm tra mã vai trò đã tồn tại trước khi gán.
    boolean roleExists(Connection connection, String roleCode) throws SQLException;

    // Lấy các vai trò có thể dùng khi quản lý tài khoản.
    List<RoleOption> findRoles(Connection connection) throws SQLException;

    // Tìm tài khoản người dùng theo bộ lọc và phân trang.
    List<ManagedUser> search(Connection connection, UserSearchCriteria criteria)
            throws SQLException;

    // Đếm số bản ghi phù hợp với điều kiện tìm kiếm.
    long count(Connection connection, UserSearchCriteria criteria) throws SQLException;

    // Tìm và khóa tài khoản theo mã trước khi thay đổi.
    Optional<ManagedUser> findByIdForUpdate(Connection connection, long userId) throws SQLException;

    // Tạo tài khoản người dùng từ dữ liệu đầu vào.
    long create(
            Connection connection,
            UserAccountCommand command,
            String passwordHash,
            Instant createdAt)
            throws SQLException;

    // Thay vai trò của tài khoản theo dữ liệu được cung cấp.
    void replaceRole(Connection connection, long userId, String roleCode) throws SQLException;

    // Cập nhật thông tin tài khoản người dùng.
    int update(Connection connection, long userId, UserAccountCommand command) throws SQLException;
}
