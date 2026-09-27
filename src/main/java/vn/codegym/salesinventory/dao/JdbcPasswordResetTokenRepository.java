package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import vn.codegym.salesinventory.model.PasswordResetToken;

public final class JdbcPasswordResetTokenRepository implements PasswordResetTokenRepository {
    @Override
    public void create(
            Connection connection,
            long userId,
            String tokenHash,
            Instant requestedAt,
            Instant expiresAt,
            String requestedIp
    ) throws SQLException {
        String sql = """
                INSERT INTO password_reset_tokens
                    (user_id, token_hash, requested_at, expires_at, requested_ip)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, tokenHash);
            statement.setTimestamp(3, Timestamp.from(requestedAt));
            statement.setTimestamp(4, Timestamp.from(expiresAt));
            statement.setString(5, requestedIp);
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<PasswordResetToken> findForUpdateByHash(Connection connection, String tokenHash)
            throws SQLException {
        String sql = """
                SELECT id, user_id, expires_at, used_at
                FROM password_reset_tokens
                WHERE token_hash = ?
                LIMIT 1
                FOR UPDATE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                Timestamp usedAt = resultSet.getTimestamp("used_at");
                return Optional.of(new PasswordResetToken(
                        resultSet.getLong("id"),
                        resultSet.getLong("user_id"),
                        resultSet.getTimestamp("expires_at").toInstant(),
                        usedAt == null ? null : usedAt.toInstant()
                ));
            }
        }
    }

    @Override
    public void markUsed(Connection connection, long id, Instant usedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE password_reset_tokens SET used_at = ? WHERE id = ? AND used_at IS NULL")) {
            statement.setTimestamp(1, Timestamp.from(usedAt));
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    @Override
    public void invalidateUnusedForUser(Connection connection, long userId, Instant usedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE password_reset_tokens SET used_at = ? WHERE user_id = ? AND used_at IS NULL")) {
            statement.setTimestamp(1, Timestamp.from(usedAt));
            statement.setLong(2, userId);
            statement.executeUpdate();
        }
    }
}
