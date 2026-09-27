package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

public interface AuditLogRepository {
    void record(
            Connection connection,
            Long actorUserId,
            String eventType,
            String details,
            String ipAddress,
            String userAgent,
            Instant occurredAt
    ) throws SQLException;
}
