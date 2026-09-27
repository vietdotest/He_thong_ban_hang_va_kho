package vn.codegym.salesinventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.codegym.salesinventory.dao.AuditLogRepository;
import vn.codegym.salesinventory.dao.PasswordResetTokenRepository;
import vn.codegym.salesinventory.dao.SessionRepository;
import vn.codegym.salesinventory.dao.UserRepository;
import vn.codegym.salesinventory.dto.AuthenticationContext;
import vn.codegym.salesinventory.model.PasswordResetToken;
import vn.codegym.salesinventory.model.User;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.PasswordHasher;
import vn.codegym.salesinventory.security.SecureTokenGenerator;
import vn.codegym.salesinventory.security.TokenHashing;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");
    private static final AuthenticationContext CONTEXT = new AuthenticationContext("127.0.0.1", "JUnit");
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private UserRepository users;
    @Mock private PasswordResetTokenRepository tokens;
    @Mock private SessionRepository sessions;
    @Mock private AuditLogRepository audits;
    @Mock private PasswordHasher hasher;
    @Mock private MailService mail;
    @Mock private SecureTokenGenerator tokenGenerator;
    private PasswordResetService service;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        service = new PasswordResetService(dataSource, users, tokens, sessions, audits, hasher, mail,
                tokenGenerator, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(30),
                "http://localhost:8080/");
    }

    @Test
    void requestStoresOnlyHashAndSendsRawTokenByEmail() throws Exception {
        when(users.findByIdentityForUpdate(connection, "admin@local.test")).thenReturn(Optional.of(user()));
        when(tokenGenerator.generate()).thenReturn("raw-secret-token");

        service.requestReset(" ADMIN@LOCAL.TEST ", CONTEXT);

        verify(tokens).create(connection, 1L, TokenHashing.sha256("raw-secret-token"), NOW,
                NOW.plus(Duration.ofMinutes(30)), "127.0.0.1");
        verify(mail).sendPasswordReset("admin@local.test", "Quản trị viên",
                "http://localhost:8080/reset-password?token=raw-secret-token", 30);
    }

    @Test
    void unknownIdentityHasSamePublicFlowWithoutSendingEmail() throws Exception {
        when(users.findByIdentityForUpdate(connection, "missing")).thenReturn(Optional.empty());

        service.requestReset("missing", CONTEXT);

        verifyNoInteractions(mail);
        verify(connection).commit();
    }

    @Test
    void emailFailureInvalidatesGeneratedToken() throws Exception {
        when(users.findByIdentityForUpdate(connection, "admin")).thenReturn(Optional.of(user()));
        when(tokenGenerator.generate()).thenReturn("raw-secret-token");
        doThrow(new IllegalStateException("SMTP unavailable")).when(mail).sendPasswordReset(
                "admin@local.test", "Quản trị viên",
                "http://localhost:8080/reset-password?token=raw-secret-token", 30);

        assertThatThrownBy(() -> service.requestReset("admin", CONTEXT))
                .isInstanceOf(IllegalStateException.class);

        verify(tokens, times(2)).invalidateUnusedForUser(connection, 1L, NOW);
        verify(audits).record(connection, 1L, "PASSWORD_RESET_DELIVERY_FAILED", "channel=EMAIL",
                "127.0.0.1", "JUnit", NOW);
    }

    @Test
    void expiredOrUsedTokenCannotBeReplayed() throws Exception {
        String hash = TokenHashing.sha256("expired-token");
        when(tokens.findForUpdateByHash(connection, hash))
                .thenReturn(Optional.of(new PasswordResetToken(7L, 1L, NOW, null)));

        assertThat(service.resetPassword("expired-token", "Newpass1", CONTEXT)).isFalse();

        verify(users, never()).updatePassword(connection, 1L, "new-hash");
        verify(sessions, never()).revokeAllForUser(connection, 1L, NOW, "PASSWORD_RESET");
    }

    @Test
    void validTokenChangesPasswordAndRevokesAllSessions() throws Exception {
        String hash = TokenHashing.sha256("valid-token");
        when(tokens.findForUpdateByHash(connection, hash))
                .thenReturn(Optional.of(new PasswordResetToken(7L, 1L, NOW.plusSeconds(60), null)));
        when(users.findByIdForUpdate(connection, 1L)).thenReturn(Optional.of(user()));
        when(hasher.hash("Newpass1")).thenReturn("new-hash");

        assertThat(service.resetPassword("valid-token", "Newpass1", CONTEXT)).isTrue();

        verify(users).updatePassword(connection, 1L, "new-hash");
        verify(tokens).markUsed(connection, 7L, NOW);
        verify(sessions).revokeAllForUser(connection, 1L, NOW, "PASSWORD_RESET");
    }

    private static User user() {
        return new User(1L, "admin", "admin@local.test", "Quản trị viên", "old-hash",
                UserStatus.ACTIVE, 0, null);
    }
}
