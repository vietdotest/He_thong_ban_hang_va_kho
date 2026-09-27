package vn.codegym.salesinventory.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.codegym.salesinventory.dao.AuditLogRepository;
import vn.codegym.salesinventory.dao.SessionRepository;
import vn.codegym.salesinventory.dao.UserRepository;
import vn.codegym.salesinventory.dto.AuthenticationContext;
import vn.codegym.salesinventory.model.User;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.PasswordHasher;

@ExtendWith(MockitoExtension.class)
class PasswordChangeServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");
    private static final AuthenticationContext CONTEXT = new AuthenticationContext("127.0.0.1", "JUnit");
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private UserRepository users;
    @Mock private SessionRepository sessions;
    @Mock private AuditLogRepository audits;
    @Mock private PasswordHasher hasher;
    private PasswordChangeService service;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        service = new PasswordChangeService(dataSource, users, sessions, audits, hasher,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void rejectsWrongCurrentPassword() throws Exception {
        when(users.findByIdForUpdate(connection, 1L)).thenReturn(Optional.of(user()));
        when(hasher.matches("wrong", "old-hash")).thenReturn(false);

        assertThat(service.change(1L, "current-session", "wrong", "Newpass1", CONTEXT))
                .isEqualTo(PasswordChangeService.Result.CURRENT_PASSWORD_INVALID);
        verify(users, never()).updatePassword(connection, 1L, "new-hash");
        verify(sessions, never()).revokeOtherForUser(connection, 1L, "current-session", NOW, "PASSWORD_CHANGED");
    }

    @Test
    void changesPasswordAndRevokesOnlyOtherSessions() throws Exception {
        when(users.findByIdForUpdate(connection, 1L)).thenReturn(Optional.of(user()));
        when(hasher.matches("old-password", "old-hash")).thenReturn(true);
        when(hasher.matches("Newpass1", "old-hash")).thenReturn(false);
        when(hasher.hash("Newpass1")).thenReturn("new-hash");

        assertThat(service.change(1L, "current-session", "old-password", "Newpass1", CONTEXT))
                .isEqualTo(PasswordChangeService.Result.SUCCESS);
        verify(users).updatePassword(connection, 1L, "new-hash");
        verify(sessions).revokeOtherForUser(connection, 1L, "current-session", NOW, "PASSWORD_CHANGED");
        verify(connection).commit();
    }

    private static User user() {
        return new User(1L, "admin", "admin@local.test", "Quản trị viên", "old-hash",
                UserStatus.ACTIVE, 0, null);
    }
}
