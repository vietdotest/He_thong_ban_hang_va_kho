package vn.codegym.salesinventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import vn.codegym.salesinventory.dao.SessionRepository;
import vn.codegym.salesinventory.dto.AuthenticationContext;
import vn.codegym.salesinventory.dto.SessionValidationResult;
import vn.codegym.salesinventory.model.ServerSession;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.TokenHashing;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private SessionRepository sessions;
    @Mock private AuditLogRepository audits;
    private SessionService service;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        service = new SessionService(dataSource, sessions, audits, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(30), Duration.ofHours(8));
    }

    @Test
    void createsHashedServerSessionWithAbsoluteExpiry() throws Exception {
        CurrentUser user = new CurrentUser(1L, "admin", "admin@local.test", "Quản trị viên");

        String id = service.create(user, "raw-http-session-id", new AuthenticationContext("127.0.0.1", "JUnit"));

        verify(sessions).create(eq(connection), any(ServerSession.class), eq("127.0.0.1"), eq("JUnit"));
        verify(audits).record(eq(connection), eq(1L), eq("SESSION_CREATED"), eq("sessionId=" + id),
                eq("127.0.0.1"), eq("JUnit"), eq(NOW));
        verify(connection).commit();
    }

    @Test
    void validSessionIsTouchedAndReturnsFreshUser() throws Exception {
        ServerSession stored = session(NOW.minusSeconds(60), NOW.plusSeconds(3600), null, UserStatus.ACTIVE);
        when(sessions.findByTokenHash(connection, TokenHashing.sha256("cookie-id")))
                .thenReturn(Optional.of(stored));

        SessionValidationResult result = service.validate("cookie-id");

        assertThat(result.valid()).isTrue();
        assertThat(result.currentUser().username()).isEqualTo("admin");
        verify(sessions).touch(connection, stored.id(), NOW);
        verify(sessions, never()).revokeByTokenHash(any(), any(), any(), any());
    }

    @Test
    void idleExpiredSessionIsRevoked() throws Exception {
        ServerSession stored = session(NOW.minus(Duration.ofMinutes(31)), NOW.plusSeconds(3600), null, UserStatus.ACTIVE);
        String hash = TokenHashing.sha256("cookie-id");
        when(sessions.findByTokenHash(connection, hash)).thenReturn(Optional.of(stored));

        assertThat(service.validate("cookie-id").valid()).isFalse();

        verify(sessions).revokeByTokenHash(connection, hash, NOW, "IDLE_TIMEOUT");
        verify(sessions, never()).touch(any(), any(), any());
    }

    @Test
    void revokedOrDisabledSessionIsRejected() throws Exception {
        ServerSession stored = session(NOW, NOW.plusSeconds(3600), NOW.minusSeconds(1), UserStatus.DISABLED);
        when(sessions.findByTokenHash(connection, TokenHashing.sha256("cookie-id")))
                .thenReturn(Optional.of(stored));

        assertThat(service.validate("cookie-id").valid()).isFalse();
        verify(sessions, never()).touch(any(), any(), any());
    }

    @Test
    void temporarilyLockedAccountRevokesExistingSession() throws Exception {
        ServerSession stored = new ServerSession("server-id", 1L, TokenHashing.sha256("cookie-id"), "admin",
                "admin@local.test", "Quản trị viên", UserStatus.ACTIVE, NOW.plusSeconds(300),
                NOW.minusSeconds(600), NOW, NOW.plusSeconds(3600), null);
        String hash = TokenHashing.sha256("cookie-id");
        when(sessions.findByTokenHash(connection, hash)).thenReturn(Optional.of(stored));

        assertThat(service.validate("cookie-id").valid()).isFalse();

        verify(sessions).revokeByTokenHash(connection, hash, NOW, "ACCOUNT_LOCKED");
    }

    private static ServerSession session(
            Instant lastActivity,
            Instant expiresAt,
            Instant revokedAt,
            UserStatus status
    ) {
        return new ServerSession("server-id", 1L, TokenHashing.sha256("cookie-id"), "admin",
                "admin@local.test", "Quản trị viên", status, null, NOW.minusSeconds(600), lastActivity,
                expiresAt, revokedAt);
    }
}
