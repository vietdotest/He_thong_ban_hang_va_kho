package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

public final class JdbcAuditLogRepository implements AuditLogRepository {
    @Override
    public void record(
            Connection connection,
            Long actorUserId,
            String eventType,
            String details,
            String ipAddress,
            String userAgent,
            Instant occurredAt
    ) throws SQLException {
        String sql = """
                INSERT INTO audit_logs
                    (actor_user_id, event_type, details, ip_address, user_agent, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (actorUserId == null) {
                statement.setNull(1, Types.BIGINT);
            } else {
                statement.setLong(1, actorUserId);
            }
            statement.setString(2, eventType);
            statement.setString(3, details);
            statement.setString(4, ipAddress);
            statement.setString(5, userAgent);
            statement.setTimestamp(6, Timestamp.from(occurredAt));
            statement.executeUpdate();
        }
    }
}
