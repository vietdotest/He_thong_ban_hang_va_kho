package vn.codegym.salesinventory.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.AuditLogRepository;
import vn.codegym.salesinventory.dao.LoginAttemptRepository;
import vn.codegym.salesinventory.dao.UserRepository;
import vn.codegym.salesinventory.dto.AuthenticationContext;
import vn.codegym.salesinventory.dto.AuthenticationResult;
import vn.codegym.salesinventory.dto.LoginRequest;
import vn.codegym.salesinventory.exception.AuthenticationException;
import vn.codegym.salesinventory.model.User;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.PasswordHasher;

public final class AuthenticationService {
    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final String DUMMY_BCRYPT_HASH = "$2a$12$vclBSd7aqwjDAHl9M.mSwO8ND4cgnGAAAbbscqtVMio.tkwwhkirC";

    private final DataSource dataSource;
    private final UserRepository users;
    private final LoginAttemptRepository attempts;
    private final AuditLogRepository audits;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public AuthenticationService(
            DataSource dataSource,
            UserRepository users,
            LoginAttemptRepository attempts,
            AuditLogRepository audits,
            PasswordHasher passwordHasher,
            Clock clock
    ) {
        this.dataSource = dataSource;
        this.users = users;
        this.attempts = attempts;
        this.audits = audits;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    public AuthenticationResult authenticate(LoginRequest request, AuthenticationContext context) {
        String normalizedIdentity = normalizeIdentity(request.identity());
        String identityHash = hashIdentity(normalizedIdentity);
        Instant now = clock.instant();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                Optional<User> foundUser = users.findByIdentityForUpdate(connection, normalizedIdentity);
                if (foundUser.isEmpty()) {
                    passwordHasher.matches(request.password(), DUMMY_BCRYPT_HASH);
                    recordFailure(connection, null, identityHash, "USER_NOT_FOUND", context, now);
                    connection.commit();
                    return AuthenticationResult.failure();
                }

                User user = foundUser.get();
                if (user.status() != UserStatus.ACTIVE) {
                    passwordHasher.matches(request.password(), user.passwordHash());
                    recordFailure(connection, user.id(), identityHash, "ACCOUNT_" + user.status(), context, now);
                    connection.commit();
                    return AuthenticationResult.failure();
                }

                if (user.lockedUntil() != null && now.isBefore(user.lockedUntil())) {
                    recordFailure(connection, user.id(), identityHash, "TEMPORARY_LOCKED", context, now);
                    connection.commit();
                    return AuthenticationResult.failure();
                }

                int currentFailures = user.lockedUntil() != null ? 0 : user.failedLoginCount();
                if (!passwordHasher.matches(request.password(), user.passwordHash())) {
                    int newFailureCount = Math.min(currentFailures + 1, MAX_FAILURES);
                    Instant lockedUntil = newFailureCount == MAX_FAILURES ? now.plus(LOCK_DURATION) : null;
                    users.updateFailedLogin(connection, user.id(), newFailureCount, lockedUntil);
                    recordFailure(
                            connection,
                            user.id(),
                            identityHash,
                            lockedUntil == null ? "INVALID_CREDENTIALS" : "TEMPORARY_LOCK_CREATED",
                            context,
                            now
                    );
                    if (lockedUntil != null) {
                        audits.record(
                                connection,
                                user.id(),
                                "TEMPORARY_LOCK",
                                "failedLoginCount=" + newFailureCount + ";lockedUntil=" + lockedUntil,
                                context.ipAddress(),
                                context.userAgent(),
                                now
                        );
                    }
                    connection.commit();
                    return AuthenticationResult.failure();
                }

                users.recordSuccessfulLogin(connection, user.id(), now);
                attempts.record(
                        connection,
                        user.id(),
                        identityHash,
                        "SUCCESS",
                        context.ipAddress(),
                        context.userAgent(),
                        now
                );
                audits.record(
                        connection,
                        user.id(),
                        "LOGIN_SUCCESS",
                        "method=PASSWORD",
                        context.ipAddress(),
                        context.userAgent(),
                        now
                );
                java.util.Set<String> roleCodes = users.findRoleCodes(connection, user.id());
                connection.commit();
                return AuthenticationResult.success(
                        new CurrentUser(user.id(), user.username(), user.email(), user.fullName(),
                                roleCodes, user.mustChangePassword())
                );
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new AuthenticationException("Authentication transaction failed", exception);
        }
    }

    private void recordFailure(
            Connection connection,
            Long userId,
            String identityHash,
            String outcome,
            AuthenticationContext context,
            Instant now
    ) throws SQLException {
        attempts.record(
                connection,
                userId,
                identityHash,
                outcome,
                context.ipAddress(),
                context.userAgent(),
                now
        );
        audits.record(
                connection,
                userId,
                "LOGIN_FAILURE",
                "reason=" + outcome,
                context.ipAddress(),
                context.userAgent(),
                now
        );
    }

    private static String normalizeIdentity(String identity) {
        return identity == null ? "" : identity.trim().toLowerCase(Locale.ROOT);
    }

    private static String hashIdentity(String normalizedIdentity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizedIdentity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original database error is more useful to the caller.
        }
    }
}
