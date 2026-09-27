package vn.codegym.salesinventory.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.AuditLogRepository;
import vn.codegym.salesinventory.dao.SessionRepository;
import vn.codegym.salesinventory.dao.UserRepository;
import vn.codegym.salesinventory.dto.AuthenticationContext;
import vn.codegym.salesinventory.exception.AuthenticationException;
import vn.codegym.salesinventory.model.User;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.PasswordHasher;

public final class PasswordChangeService {
    public enum Result {
        SUCCESS,
        CURRENT_PASSWORD_INVALID,
        USER_UNAVAILABLE,
        NEW_PASSWORD_UNCHANGED
    }

    private final DataSource dataSource;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final AuditLogRepository audits;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public PasswordChangeService(
            DataSource dataSource,
            UserRepository users,
            SessionRepository sessions,
            AuditLogRepository audits,
            PasswordHasher passwordHasher,
            Clock clock
    ) {
        this.dataSource = dataSource;
        this.users = users;
        this.sessions = sessions;
        this.audits = audits;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    public Result change(
            long userId,
            String currentSessionId,
            String currentPassword,
            String newPassword,
            AuthenticationContext context
    ) {
        Instant now = clock.instant();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<User> found = users.findByIdForUpdate(connection, userId);
                if (found.isEmpty() || found.get().status() != UserStatus.ACTIVE) {
                    connection.commit();
                    return Result.USER_UNAVAILABLE;
                }
                User user = found.get();
                if (!passwordHasher.matches(currentPassword, user.passwordHash())) {
                    audits.record(connection, userId, "PASSWORD_CHANGE_REJECTED", "reason=CURRENT_PASSWORD_INVALID",
                            context.ipAddress(), context.userAgent(), now);
                    connection.commit();
                    return Result.CURRENT_PASSWORD_INVALID;
                }
                if (passwordHasher.matches(newPassword, user.passwordHash())) {
                    connection.commit();
                    return Result.NEW_PASSWORD_UNCHANGED;
                }
                users.updatePassword(connection, userId, passwordHasher.hash(newPassword));
                sessions.revokeOtherForUser(connection, userId, currentSessionId, now, "PASSWORD_CHANGED");
                audits.record(connection, userId, "PASSWORD_CHANGED", "otherSessionsRevoked=true",
                        context.ipAddress(), context.userAgent(), now);
                connection.commit();
                return Result.SUCCESS;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new AuthenticationException("Password change transaction failed", exception);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original exception.
        }
    }
}
