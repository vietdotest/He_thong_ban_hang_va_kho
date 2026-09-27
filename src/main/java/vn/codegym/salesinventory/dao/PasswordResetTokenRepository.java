package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import vn.codegym.salesinventory.model.PasswordResetToken;

public interface PasswordResetTokenRepository {
    void create(
            Connection connection,
            long userId,
            String tokenHash,
            Instant requestedAt,
            Instant expiresAt,
            String requestedIp
    ) throws SQLException;

    Optional<PasswordResetToken> findForUpdateByHash(Connection connection, String tokenHash) throws SQLException;

    void markUsed(Connection connection, long id, Instant usedAt) throws SQLException;

    void invalidateUnusedForUser(Connection connection, long userId, Instant usedAt) throws SQLException;
}
