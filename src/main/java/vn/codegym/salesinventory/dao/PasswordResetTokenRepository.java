package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import vn.codegym.salesinventory.model.PasswordResetToken;

/**
 * S1-03: Quy định các thao tác đọc, ghi mã đặt lại mật khẩu trên kết nối do service cung cấp.
 */
public interface PasswordResetTokenRepository {

    // Tạo mã đặt lại mật khẩu từ dữ liệu đầu vào.
    void create(
            Connection connection,
            long userId,
            String tokenHash,
            Instant requestedAt,
            Instant expiresAt,
            String requestedIp)
            throws SQLException;

    // Tìm và khóa mã xác nhận đã băm để kiểm tra, sử dụng.
    Optional<PasswordResetToken> findForUpdateByHash(Connection connection, String tokenHash)
            throws SQLException;

    // Đánh dấu mã xác nhận đã được sử dụng.
    void markUsed(Connection connection, long id, Instant usedAt) throws SQLException;

    // Vô hiệu hóa các mã xác nhận chưa dùng của người dùng.
    void invalidateUnusedForUser(Connection connection, long userId, Instant usedAt)
            throws SQLException;
}
