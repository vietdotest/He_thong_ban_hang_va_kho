package vn.codegym.salesinventory.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.AuditLogRepository;
import vn.codegym.salesinventory.dao.SessionRepository;
import vn.codegym.salesinventory.dto.AuthenticationContext;
import vn.codegym.salesinventory.dto.SessionValidationResult;
import vn.codegym.salesinventory.exception.AuthenticationException;
import vn.codegym.salesinventory.model.ServerSession;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.TokenHashing;

/**
 * S1-02: Tạo, kiểm tra, gia hạn và thu hồi phiên đăng nhập lưu ở server.
 */
public final class SessionService {

    private final DataSource dataSource;

    private final SessionRepository sessions;

    private final AuditLogRepository audits;

    private final Clock clock;

    private final Duration idleTimeout;

    private final Duration absoluteTimeout;

    public SessionService(
            DataSource dataSource,
            SessionRepository sessions,
            AuditLogRepository audits,
            Clock clock,
            Duration idleTimeout,
            Duration absoluteTimeout) {
        this.dataSource = dataSource;
        this.sessions = sessions;
        this.audits = audits;
        this.clock = clock;
        this.idleTimeout = idleTimeout;
        this.absoluteTimeout = absoluteTimeout;
    }

    // Tạo phiên đăng nhập và lưu thông tin xác thực phía server.
    public String create(CurrentUser user, String httpSessionId, AuthenticationContext context) {
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        ServerSession serverSession =
                new ServerSession(
                        id,
                        user.id(),
                        TokenHashing.sha256(httpSessionId),
                        user.username(),
                        user.email(),
                        user.fullName(),
                        user.roleCodes(),
                        user.mustChangePassword(),
                        UserStatus.ACTIVE,
                        null,
                        now,
                        now,
                        now.plus(absoluteTimeout),
                        null);
        inTransaction(
                connection -> {
                    sessions.create(
                            connection, serverSession, context.ipAddress(), context.userAgent());
                    audits.record(
                            connection,
                            user.id(),
                            "SESSION_CREATED",
                            "sessionId=" + id,
                            context.ipAddress(),
                            context.userAgent(),
                            now);
                    return null;
                });
        return id;
    }

    // Kiểm tra phiên còn hiệu lực và gia hạn hoạt động khi hợp lệ.
    public SessionValidationResult validate(String httpSessionId) {
        if (httpSessionId == null || httpSessionId.isBlank()) {
            return SessionValidationResult.invalid();
        }
        Instant now = clock.instant();
        String tokenHash = TokenHashing.sha256(httpSessionId);
        return inTransaction(
                connection -> {
                    Optional<ServerSession> found = sessions.findByTokenHash(connection, tokenHash);
                    if (found.isEmpty()) {
                        return SessionValidationResult.invalid();
                    }
                    ServerSession session = found.get();
                    if (session.revokedAt() != null) {
                        return SessionValidationResult.invalid();
                    }
                    if (session.userStatus() != UserStatus.ACTIVE) {
                        sessions.revokeByTokenHash(
                                connection, tokenHash, now, "ACCOUNT_NOT_ACTIVE");
                        return SessionValidationResult.invalid();
                    }
                    if (session.userLockedUntil() != null
                            && now.isBefore(session.userLockedUntil())) {
                        sessions.revokeByTokenHash(connection, tokenHash, now, "ACCOUNT_LOCKED");
                        return SessionValidationResult.invalid();
                    }
                    boolean absoluteExpired = !now.isBefore(session.expiresAt());
                    boolean idleExpired = !now.isBefore(session.lastActivityAt().plus(idleTimeout));
                    if (absoluteExpired || idleExpired) {
                        sessions.revokeByTokenHash(
                                connection,
                                tokenHash,
                                now,
                                absoluteExpired ? "ABSOLUTE_TIMEOUT" : "IDLE_TIMEOUT");
                        return SessionValidationResult.invalid();
                    }
                    sessions.touch(connection, session.id(), now);
                    CurrentUser user =
                            new CurrentUser(
                                    session.userId(),
                                    session.username(),
                                    session.email(),
                                    session.fullName(),
                                    session.roleCodes(),
                                    session.mustChangePassword());
                    return SessionValidationResult.valid(user, session.id());
                });
    }

    // Thu hồi phiên hoặc mã xác nhận theo yêu cầu.
    public void revoke(String httpSessionId, String reason, AuthenticationContext context) {
        if (httpSessionId == null || httpSessionId.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        String tokenHash = TokenHashing.sha256(httpSessionId);
        inTransaction(
                connection -> {
                    Optional<ServerSession> session =
                            sessions.findByTokenHash(connection, tokenHash);
                    sessions.revokeByTokenHash(connection, tokenHash, now, reason);
                    if (session.isPresent()) {
                        audits.record(
                                connection,
                                session.get().userId(),
                                "LOGOUT",
                                "reason=" + reason,
                                context.ipAddress(),
                                context.userAgent(),
                                now);
                    }
                    return null;
                });
    }

    // Thực hiện công việc quản lý phiên trong một giao dịch cơ sở dữ liệu.
    private <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new AuthenticationException("Session transaction failed", exception);
        }
    }

    // Hoàn tác giao dịch và giữ lại lỗi gốc nếu hoàn tác gặp lỗi.
    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original exception.
        }
    }

    // Quy định công việc chạy trong giao dịch quản lý phiên.
    @FunctionalInterface
    private interface SqlWork<T> {

        // Thực hiện công việc trên kết nối của giao dịch hiện tại.
        T execute(Connection connection) throws SQLException;
    }
}
