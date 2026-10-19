package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import vn.codegym.salesinventory.model.ServerSession;

/**
 * S1-02: Quy định các thao tác đọc, ghi phiên đăng nhập trên kết nối do service cung cấp.
 */
public interface SessionRepository {

    // Tạo phiên đăng nhập từ dữ liệu đầu vào.
    void create(Connection connection, ServerSession session, String ipAddress, String userAgent)
            throws SQLException;

    // Tìm phiên theo mã đã băm.
    Optional<ServerSession> findByTokenHash(Connection connection, String tokenHash)
            throws SQLException;

    // Cập nhật thời điểm hoạt động của phiên đăng nhập.
    void touch(Connection connection, String sessionId, Instant lastActivityAt) throws SQLException;

    // Thu hồi phiên theo mã phiên đã băm.
    void revokeByTokenHash(
            Connection connection, String tokenHash, Instant revokedAt, String reason)
            throws SQLException;

    // Thu hồi toàn bộ phiên của một người dùng.
    void revokeAllForUser(Connection connection, long userId, Instant revokedAt, String reason)
            throws SQLException;

    // Thu hồi các phiên khác, giữ lại phiên hiện tại.
    void revokeOtherForUser(
            Connection connection,
            long userId,
            String currentSessionId,
            Instant revokedAt,
            String reason)
            throws SQLException;
}
