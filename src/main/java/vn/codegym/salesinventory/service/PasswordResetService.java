package vn.codegym.salesinventory.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.AuditLogRepository;
import vn.codegym.salesinventory.dao.PasswordResetTokenRepository;
import vn.codegym.salesinventory.dao.SessionRepository;
import vn.codegym.salesinventory.dao.UserRepository;
import vn.codegym.salesinventory.dto.AuthenticationContext;
import vn.codegym.salesinventory.exception.AuthenticationException;
import vn.codegym.salesinventory.model.PasswordResetToken;
import vn.codegym.salesinventory.model.User;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.PasswordHasher;
import vn.codegym.salesinventory.security.SecureTokenGenerator;
import vn.codegym.salesinventory.security.TokenHashing;

/**
 * S1-03: Gửi liên kết đặt lại mật khẩu và xác nhận mã dùng một lần có thời hạn.
 */
public final class PasswordResetService {

    private final DataSource dataSource;

    private final UserRepository users;

    private final PasswordResetTokenRepository resetTokens;

    private final SessionRepository sessions;

    private final AuditLogRepository audits;

    private final PasswordHasher passwordHasher;

    private final MailService mailService;

    private final SecureTokenGenerator tokenGenerator;

    private final Clock clock;

    private final Duration expiry;

    private final String appBaseUrl;

    public PasswordResetService(
            DataSource dataSource,
            UserRepository users,
            PasswordResetTokenRepository resetTokens,
            SessionRepository sessions,
            AuditLogRepository audits,
            PasswordHasher passwordHasher,
            MailService mailService,
            SecureTokenGenerator tokenGenerator,
            Clock clock,
            Duration expiry,
            String appBaseUrl) {
        this.dataSource = dataSource;
        this.users = users;
        this.resetTokens = resetTokens;
        this.sessions = sessions;
        this.audits = audits;
        this.passwordHasher = passwordHasher;
        this.mailService = mailService;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
        this.expiry = expiry;
        this.appBaseUrl = appBaseUrl.replaceAll("/+$", "");
    }

    // Tạo mã đặt lại mật khẩu và gửi email hướng dẫn cho người dùng.
    public void requestReset(String identity, AuthenticationContext context) {
        String normalizedIdentity =
                identity == null ? "" : identity.trim().toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        PendingEmail pendingEmail =
                inTransaction(
                        connection -> {
                            Optional<User> found =
                                    users.findByIdentityForUpdate(connection, normalizedIdentity);
                            if (found.isEmpty() || found.get().status() != UserStatus.ACTIVE) {
                                audits.record(
                                        connection,
                                        found.map(User::id).orElse(null),
                                        "PASSWORD_RESET_REQUEST_REJECTED",
                                        "identityNotEligible=true",
                                        context.ipAddress(),
                                        context.userAgent(),
                                        now);
                                return null;
                            }
                            User user = found.get();
                            String rawToken = tokenGenerator.generate();
                            resetTokens.invalidateUnusedForUser(connection, user.id(), now);
                            resetTokens.create(
                                    connection,
                                    user.id(),
                                    TokenHashing.sha256(rawToken),
                                    now,
                                    now.plus(expiry),
                                    context.ipAddress());
                            audits.record(
                                    connection,
                                    user.id(),
                                    "PASSWORD_RESET_REQUESTED",
                                    "delivery=EMAIL",
                                    context.ipAddress(),
                                    context.userAgent(),
                                    now);
                            return new PendingEmail(
                                    user.id(), user.email(), user.fullName(), rawToken);
                        });
        if (pendingEmail != null) {
            String resetUrl =
                    appBaseUrl
                            + "/reset-password?token="
                            + URLEncoder.encode(pendingEmail.rawToken(), StandardCharsets.UTF_8);
            try {
                mailService.sendPasswordReset(
                        pendingEmail.email(),
                        pendingEmail.fullName(),
                        resetUrl,
                        Math.toIntExact(expiry.toMinutes()));
            } catch (RuntimeException exception) {
                inTransaction(
                        connection -> {
                            Instant failedAt = clock.instant();
                            resetTokens.invalidateUnusedForUser(
                                    connection, pendingEmail.userId(), failedAt);
                            audits.record(
                                    connection,
                                    pendingEmail.userId(),
                                    "PASSWORD_RESET_DELIVERY_FAILED",
                                    "channel=EMAIL",
                                    context.ipAddress(),
                                    context.userAgent(),
                                    failedAt);
                            return null;
                        });
                throw exception;
            }
        }
    }

    // Kiểm tra mã đặt lại mật khẩu còn hạn và chưa sử dụng.
    public boolean isUsable(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 128) {
            return false;
        }
        Instant now = clock.instant();
        return inTransaction(
                connection ->
                        resetTokens
                                .findForUpdateByHash(connection, TokenHashing.sha256(rawToken))
                                .filter(
                                        token ->
                                                token.usedAt() == null
                                                        && now.isBefore(token.expiresAt()))
                                .isPresent());
    }

    // Xác nhận mã dùng một lần và cập nhật mật khẩu mới.
    public boolean resetPassword(
            String rawToken, String newPassword, AuthenticationContext context) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 128) {
            return false;
        }
        Instant now = clock.instant();
        return inTransaction(
                connection -> {
                    Optional<PasswordResetToken> found =
                            resetTokens.findForUpdateByHash(
                                    connection, TokenHashing.sha256(rawToken));
                    if (found.isEmpty()
                            || found.get().usedAt() != null
                            || !now.isBefore(found.get().expiresAt())) {
                        return false;
                    }
                    PasswordResetToken token = found.get();
                    Optional<User> user = users.findByIdForUpdate(connection, token.userId());
                    if (user.isEmpty() || user.get().status() != UserStatus.ACTIVE) {
                        resetTokens.markUsed(connection, token.id(), now);
                        return false;
                    }
                    users.updatePassword(
                            connection, token.userId(), passwordHasher.hash(newPassword));
                    resetTokens.markUsed(connection, token.id(), now);
                    sessions.revokeAllForUser(connection, token.userId(), now, "PASSWORD_RESET");
                    audits.record(
                            connection,
                            token.userId(),
                            "PASSWORD_RESET_COMPLETED",
                            "allSessionsRevoked=true",
                            context.ipAddress(),
                            context.userAgent(),
                            now);
                    return true;
                });
    }

    // Thực hiện cập nhật mã và mật khẩu trong cùng một giao dịch.
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
            throw new AuthenticationException("Password reset transaction failed", exception);
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

    // Chứa thông tin thư đặt lại mật khẩu đang chờ gửi.
    private record PendingEmail(long userId, String email, String fullName, String rawToken) {}

    // Quy định công việc chạy trong giao dịch đặt lại mật khẩu.
    @FunctionalInterface
    private interface SqlWork<T> {

        // Thực hiện công việc trên kết nối của giao dịch hiện tại.
        T execute(Connection connection) throws SQLException;
    }
}
