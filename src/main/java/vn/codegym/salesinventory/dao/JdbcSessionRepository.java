package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import vn.codegym.salesinventory.model.ServerSession;
import vn.codegym.salesinventory.model.UserStatus;

/**
 * S1-02: Thực hiện truy vấn SQL cho phiên đăng nhập, dùng tham số để truyền dữ liệu.
 */
public final class JdbcSessionRepository implements SessionRepository {

    // Tạo phiên đăng nhập từ dữ liệu đầu vào.
    @Override
    public void create(
            Connection connection, ServerSession session, String ipAddress, String userAgent)
            throws SQLException {
        String sql =
                """
INSERT INTO user_sessions
    (id, user_id, token_hash, created_at, last_activity_at, expires_at, ip_address, user_agent)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)
""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, session.id());
            statement.setLong(2, session.userId());
            statement.setString(3, session.tokenHash());
            statement.setTimestamp(4, Timestamp.from(session.createdAt()));
            statement.setTimestamp(5, Timestamp.from(session.lastActivityAt()));
            statement.setTimestamp(6, Timestamp.from(session.expiresAt()));
            statement.setString(7, ipAddress);
            statement.setString(8, userAgent);
            statement.executeUpdate();
        }
    }

    // Tìm phiên theo mã đã băm.
    @Override
    public Optional<ServerSession> findByTokenHash(Connection connection, String tokenHash)
            throws SQLException {
        String sql =
                """
                SELECT s.id, s.user_id, s.token_hash, s.created_at, s.last_activity_at,
                       s.expires_at, s.revoked_at,
                       u.username, u.email, u.full_name, u.must_change_password,
                       u.status, u.locked_until,
                       (SELECT GROUP_CONCAT(r.code ORDER BY r.code SEPARATOR ',')
                        FROM user_roles ur
                        JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = u.id) AS role_codes
                FROM user_sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ?
                LIMIT 1
                FOR UPDATE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapSession(resultSet)) : Optional.empty();
            }
        }
    }

    // Cập nhật thời điểm hoạt động của phiên đăng nhập.
    @Override
    public void touch(Connection connection, String sessionId, Instant lastActivityAt)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "UPDATE user_sessions SET last_activity_at = ? WHERE id = ? AND revoked_at"
                                + " IS NULL")) {
            statement.setTimestamp(1, Timestamp.from(lastActivityAt));
            statement.setString(2, sessionId);
            statement.executeUpdate();
        }
    }

    // Thu hồi phiên theo mã phiên đã băm.
    @Override
    public void revokeByTokenHash(
            Connection connection, String tokenHash, Instant revokedAt, String reason)
            throws SQLException {
        String sql =
                """
                UPDATE user_sessions
                SET revoked_at = ?, revoke_reason = ?
                WHERE token_hash = ? AND revoked_at IS NULL
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(revokedAt));
            statement.setString(2, reason);
            statement.setString(3, tokenHash);
            statement.executeUpdate();
        }
    }

    @Override
    public void revokeAllForUser(
            Connection connection, long userId, Instant revokedAt, String reason)
            throws SQLException {
        revokeForUser(connection, userId, null, revokedAt, reason);
    }

    @Override
    public void revokeOtherForUser(
            Connection connection,
            long userId,
            String currentSessionId,
            Instant revokedAt,
            String reason)
            throws SQLException {
        revokeForUser(connection, userId, currentSessionId, revokedAt, reason);
    }

    // Thu hồi phiên của người dùng theo điều kiện cung cấp.
    private static void revokeForUser(
            Connection connection,
            long userId,
            String excludedSessionId,
            Instant revokedAt,
            String reason)
            throws SQLException {
        String sql =
                excludedSessionId == null
                        ? "UPDATE user_sessions SET revoked_at = ?, revoke_reason = ? WHERE user_id"
                                + " = ? AND revoked_at IS NULL"
                        : "UPDATE user_sessions SET revoked_at = ?, revoke_reason = ? WHERE user_id"
                                + " = ? AND id <> ? AND revoked_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(revokedAt));
            statement.setString(2, reason);
            statement.setLong(3, userId);
            if (excludedSessionId != null) {
                statement.setString(4, excludedSessionId);
            }
            statement.executeUpdate();
        }
    }

    private static ServerSession mapSession(ResultSet resultSet) throws SQLException {
        Timestamp revokedAt = resultSet.getTimestamp("revoked_at");
        Timestamp lockedUntil = resultSet.getTimestamp("locked_until");
        return new ServerSession(
                resultSet.getString("id"),
                resultSet.getLong("user_id"),
                resultSet.getString("token_hash"),
                resultSet.getString("username"),
                resultSet.getString("email"),
                resultSet.getString("full_name"),
                parseRoleCodes(resultSet.getString("role_codes")),
                resultSet.getBoolean("must_change_password"),
                UserStatus.valueOf(resultSet.getString("status")),
                lockedUntil == null ? null : lockedUntil.toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("last_activity_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    }

    // Tách các mã vai trò đã lưu thành tập vai trò của phiên.
    private static Set<String> parseRoleCodes(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(value.split(",")));
    }
}
