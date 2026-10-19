package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import vn.codegym.salesinventory.model.User;

/**
 * S1-01: Quy định các thao tác đọc, ghi tài khoản phục vụ xác thực trên kết nối do service cung cấp.
 */
public interface UserRepository {

    // Tìm và khóa tài khoản theo username hoặc email để cập nhật an toàn.
    Optional<User> findByIdentityForUpdate(Connection connection, String normalizedIdentity)
            throws SQLException;

    // Tìm và khóa tài khoản theo mã trước khi thay đổi.
    Optional<User> findByIdForUpdate(Connection connection, long userId) throws SQLException;

    // Lấy các mã vai trò đang gán cho người dùng.
    Set<String> findRoleCodes(Connection connection, long userId) throws SQLException;

    // Lưu số lần đăng nhập sai và thời điểm kết thúc khóa tạm.
    void updateFailedLogin(
            Connection connection, long userId, int failureCount, Instant lockedUntil)
            throws SQLException;

    // Ghi nhận đăng nhập thành công và xóa bộ đếm nhập sai.
    void recordSuccessfulLogin(Connection connection, long userId, Instant loginTime)
            throws SQLException;

    // Lưu mật khẩu mới đã băm cho tài khoản.
    void updatePassword(Connection connection, long userId, String passwordHash)
            throws SQLException;
}
