package vn.codegym.salesinventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import vn.codegym.salesinventory.dao.AuditLogRepository;
import vn.codegym.salesinventory.dao.LoginAttemptRepository;
import vn.codegym.salesinventory.dao.UserRepository;
import vn.codegym.salesinventory.dto.AuthenticationContext;
import vn.codegym.salesinventory.dto.AuthenticationResult;
import vn.codegym.salesinventory.dto.LoginRequest;
import vn.codegym.salesinventory.model.User;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.PasswordHasher;

class AuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");
    private static final AuthenticationContext CONTEXT = new AuthenticationContext("127.0.0.1", "JUnit");

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private UserRepository users;
    @Mock
    private LoginAttemptRepository attempts;
    @Mock
    private AuditLogRepository audits;
    @Mock
    private PasswordHasher passwordHasher;

    private AuthenticationService service;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(dataSource.getConnection()).thenReturn(connection);
        service = new AuthenticationService(
                dataSource,
                users,
                attempts,
                audits,
                passwordHasher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void authenticatesByNormalizedUsername() throws Exception {
        User user = activeUser(0, null);
        when(users.findByIdentityForUpdate(connection, "admin")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("admin123", user.passwordHash())).thenReturn(true);

        AuthenticationResult result = service.authenticate(new LoginRequest("  AdMiN  ", "admin123"), CONTEXT);

        assertThat(result.authenticated()).isTrue();
        assertThat(result.currentUser().username()).isEqualTo("admin");
        verify(users).recordSuccessfulLogin(connection, user.id(), NOW);
        verify(attempts).record(connection, user.id(), sha256("admin"), "SUCCESS", "127.0.0.1", "JUnit", NOW);
        verify(connection).commit();
    }

    @Test
    void authenticatesByNormalizedEmail() throws Exception {
        User user = activeUser(0, null);
        when(users.findByIdentityForUpdate(connection, "admin@local.test")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("admin123", user.passwordHash())).thenReturn(true);

        AuthenticationResult result = service.authenticate(
                new LoginRequest("ADMIN@LOCAL.TEST", "admin123"),
                CONTEXT
        );

        assertThat(result.authenticated()).isTrue();
        verify(users).findByIdentityForUpdate(connection, "admin@local.test");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void incrementsFailuresOneThroughFourWithoutLocking(int existingFailures) throws Exception {
        User user = activeUser(existingFailures, null);
        when(users.findByIdentityForUpdate(connection, "admin")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("wrong", user.passwordHash())).thenReturn(false);

        AuthenticationResult result = service.authenticate(new LoginRequest("admin", "wrong"), CONTEXT);

        assertThat(result.authenticated()).isFalse();
        verify(users).updateFailedLogin(connection, user.id(), existingFailures + 1, null);
    }

    @Test
    void fifthFailureLocksForFifteenMinutes() throws Exception {
        User user = activeUser(4, null);
        when(users.findByIdentityForUpdate(connection, "admin")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("wrong", user.passwordHash())).thenReturn(false);

        service.authenticate(new LoginRequest("admin", "wrong"), CONTEXT);

        ArgumentCaptor<Instant> lockCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(users).updateFailedLogin(eq(connection), eq(user.id()), eq(5), lockCaptor.capture());
        assertThat(lockCaptor.getValue()).isEqualTo(NOW.plusSeconds(15 * 60));
        verify(audits).record(
                eq(connection),
                eq(user.id()),
                eq("TEMPORARY_LOCK"),
                any(),
                eq("127.0.0.1"),
                eq("JUnit"),
                eq(NOW)
        );
    }

    @Test
    void activeTemporaryLockIsNotExtended() throws Exception {
        Instant originalLock = NOW.plusSeconds(300);
        User user = activeUser(5, originalLock);
        when(users.findByIdentityForUpdate(connection, "admin")).thenReturn(Optional.of(user));

        AuthenticationResult result = service.authenticate(new LoginRequest("admin", "admin123"), CONTEXT);

        assertThat(result.authenticated()).isFalse();
        verify(users, never()).updateFailedLogin(any(), any(Long.class), any(Integer.class), any());
        verify(users, never()).recordSuccessfulLogin(any(), any(Long.class), any());
    }

    @Test
    void successfulLoginAfterLockExpiryClearsFailureState() throws Exception {
        User user = activeUser(5, NOW.minusSeconds(1));
        when(users.findByIdentityForUpdate(connection, "admin")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("admin123", user.passwordHash())).thenReturn(true);

        AuthenticationResult result = service.authenticate(new LoginRequest("admin", "admin123"), CONTEXT);

        assertThat(result.authenticated()).isTrue();
        verify(users).recordSuccessfulLogin(connection, user.id(), NOW);
    }

    @Test
    void failedLoginAfterLockExpiryStartsAgainAtOne() throws Exception {
        User user = activeUser(5, NOW.minusSeconds(1));
        when(users.findByIdentityForUpdate(connection, "admin")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("wrong", user.passwordHash())).thenReturn(false);

        service.authenticate(new LoginRequest("admin", "wrong"), CONTEXT);

        verify(users).updateFailedLogin(connection, user.id(), 1, null);
    }

    @Test
    void missingUserRunsDummyBcryptAndReturnsGenericFailure() throws Exception {
        when(users.findByIdentityForUpdate(connection, "missing")).thenReturn(Optional.empty());

        AuthenticationResult result = service.authenticate(new LoginRequest("missing", "secret"), CONTEXT);

        assertThat(result.authenticated()).isFalse();
        verify(passwordHasher).matches("secret", AuthenticationService.DUMMY_BCRYPT_HASH);
        verify(users, never()).updateFailedLogin(any(), any(Long.class), any(Integer.class), any());
    }

    @Test
    void disabledUserIsDeniedWithoutChangingCounter() throws Exception {
        User user = new User(1L, "admin", "admin@local.test", "Admin", "hash", UserStatus.DISABLED, 0, null);
        when(users.findByIdentityForUpdate(connection, "admin")).thenReturn(Optional.of(user));

        AuthenticationResult result = service.authenticate(new LoginRequest("admin", "admin123"), CONTEXT);

        assertThat(result.authenticated()).isFalse();
        verify(passwordHasher).matches("admin123", "hash");
        verify(users, never()).updateFailedLogin(any(), any(Long.class), any(Integer.class), any());
    }

    private static User activeUser(int failureCount, Instant lockedUntil) {
        return new User(
                1L,
                "admin",
                "admin@local.test",
                "Quản trị viên local",
                "bcrypt-hash",
                UserStatus.ACTIVE,
                failureCount,
                lockedUntil
        );
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
