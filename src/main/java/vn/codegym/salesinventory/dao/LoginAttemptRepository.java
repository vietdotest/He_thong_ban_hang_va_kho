package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

public interface LoginAttemptRepository {
    void record(
            Connection connection,
            Long userId,
            String identityHash,
            String outcome,
            String ipAddress,
            String userAgent,
            Instant occurredAt
    ) throws SQLException;
}
