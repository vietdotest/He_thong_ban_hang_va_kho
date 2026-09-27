package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import vn.codegym.salesinventory.model.ServerSession;

public interface SessionRepository {
    void create(Connection connection, ServerSession session, String ipAddress, String userAgent) throws SQLException;

    Optional<ServerSession> findByTokenHash(Connection connection, String tokenHash) throws SQLException;

    void touch(Connection connection, String sessionId, Instant lastActivityAt) throws SQLException;

    void revokeByTokenHash(Connection connection, String tokenHash, Instant revokedAt, String reason) throws SQLException;

    void revokeAllForUser(Connection connection, long userId, Instant revokedAt, String reason) throws SQLException;

    void revokeOtherForUser(
            Connection connection,
            long userId,
            String currentSessionId,
            Instant revokedAt,
            String reason
    ) throws SQLException;
}
