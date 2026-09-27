package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

public final class JdbcLoginAttemptRepository implements LoginAttemptRepository {
    @Override
    public void record(
            Connection connection,
            Long userId,
            String identityHash,
            String outcome,
            String ipAddress,
            String userAgent,
            Instant occurredAt
    ) throws SQLException {
        String sql = """
                INSERT INTO login_attempts
                    (user_id, identity_hash, outcome, ip_address, user_agent, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (userId == null) {
                statement.setNull(1, Types.BIGINT);
            } else {
                statement.setLong(1, userId);
            }
            statement.setString(2, identityHash);
            statement.setString(3, outcome);
            statement.setString(4, ipAddress);
            statement.setString(5, userAgent);
            statement.setTimestamp(6, Timestamp.from(occurredAt));
            statement.executeUpdate();
        }
    }
}
