package vn.codegym.salesinventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.codegym.salesinventory.dao.AuditLogRepository;
import vn.codegym.salesinventory.dao.SessionRepository;
import vn.codegym.salesinventory.dao.UserManagementRepository;
import vn.codegym.salesinventory.dto.AuthenticationContext;
import vn.codegym.salesinventory.dto.UserAccountCommand;
import vn.codegym.salesinventory.dto.UserManagementResult;
import vn.codegym.salesinventory.dto.UserPage;
import vn.codegym.salesinventory.dto.UserSearchCriteria;
import vn.codegym.salesinventory.model.ManagedUser;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.PasswordHasher;
import vn.codegym.salesinventory.security.TemporaryPasswordGenerator;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");
    private static final AuthenticationContext CONTEXT = new AuthenticationContext("127.0.0.1", "JUnit");

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private UserManagementRepository users;
    @Mock private SessionRepository sessions;
    @Mock private AuditLogRepository audits;
    @Mock private PasswordHasher passwordHasher;
    @Mock private MailService mailService;

    private UserManagementService service;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        service = new UserManagementService(
                dataSource, users, sessions, audits, passwordHasher, mailService,
                new TemporaryPasswordGenerator(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsUserAssignsRoleAndEmailsTemporaryPassword() throws Exception {
        UserAccountCommand command = command(UserStatus.ACTIVE, 0);
        when(users.userHasRole(connection, 1L, "ADMIN")).thenReturn(true);
        when(users.roleExists(connection, "SALES")).thenReturn(true);
        when(passwordHasher.hash(any())).thenReturn("bcrypt-hash");
        when(users.create(connection, command, "bcrypt-hash", NOW)).thenReturn(8L);

        UserManagementResult result = service.create(command, 1L, CONTEXT);

        assertThat(result.status()).isEqualTo(UserManagementResult.Status.SUCCESS);
        assertThat(result.userId()).isEqualTo(8L);
        verify(users).replaceRole(connection, 8L, "SALES");
        verify(mailService).sendTemporaryPassword(eq("lan@example.com"), eq("Nguyễn Lan"), eq("lan.nguyen"), any());
        verify(audits).record(connection, 1L, "USER_CREATED",
                "targetUserId=8;role=SALES;status=ACTIVE", "127.0.0.1", "JUnit", NOW);
        verify(connection).commit();
    }

    @Test
    void rollsBackWhenTemporaryPasswordEmailFails() throws Exception {
        UserAccountCommand command = command(UserStatus.ACTIVE, 0);
        when(users.userHasRole(connection, 1L, "ADMIN")).thenReturn(true);
        when(users.roleExists(connection, "SALES")).thenReturn(true);
        when(passwordHasher.hash(any())).thenReturn("bcrypt-hash");
        when(users.create(connection, command, "bcrypt-hash", NOW)).thenReturn(8L);
        doThrow(new IllegalStateException("SMTP unavailable")).when(mailService)
                .sendTemporaryPassword(eq("lan@example.com"), eq("Nguyễn Lan"), eq("lan.nguyen"), any());

        UserManagementResult result = service.create(command, 1L, CONTEXT);

        assertThat(result.status()).isEqualTo(UserManagementResult.Status.EMAIL_DELIVERY_FAILED);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void reportsDuplicateEmailFromDatabaseConstraint() throws Exception {
        UserAccountCommand command = command(UserStatus.ACTIVE, 0);
        when(users.userHasRole(connection, 1L, "ADMIN")).thenReturn(true);
        when(users.roleExists(connection, "SALES")).thenReturn(true);
        when(passwordHasher.hash(any())).thenReturn("bcrypt-hash");
        when(users.create(connection, command, "bcrypt-hash", NOW)).thenThrow(
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry for key 'uk_users_email_normalized'", "23000"));

        UserManagementResult result = service.create(command, 1L, CONTEXT);

        assertThat(result.status()).isEqualTo(UserManagementResult.Status.DUPLICATE_EMAIL);
        verify(connection).rollback();
        verify(mailService, never()).sendTemporaryPassword(any(), any(), any(), any());
    }

    @Test
    void preventsAdministratorFromDisablingOwnAccount() throws Exception {
        UserAccountCommand command = command(UserStatus.DISABLED, 4);
        when(users.userHasRole(connection, 1L, "ADMIN")).thenReturn(true);
        when(users.findByIdForUpdate(connection, 1L)).thenReturn(Optional.of(managedUser(1L, 4)));
        when(users.roleExists(connection, "SALES")).thenReturn(true);

        UserManagementResult result = service.update(1L, command, 1L, CONTEXT);

        assertThat(result.status()).isEqualTo(UserManagementResult.Status.SELF_PROTECTION);
        verify(users, never()).update(any(), any(Long.class), any());
        verify(connection).rollback();
    }

    @Test
    void editingProfileCannotBypassDedicatedAccountLock() throws Exception {
        UserAccountCommand command = command(UserStatus.DISABLED, 4);
        when(users.userHasRole(connection, 1L, "ADMIN")).thenReturn(true);
        when(users.findByIdForUpdate(connection, 8L)).thenReturn(Optional.of(managedUser(8L, 4)));
        when(users.roleExists(connection, "SALES")).thenReturn(true);

        UserManagementResult result = service.update(8L, command, 1L, CONTEXT);

        assertThat(result.status()).isEqualTo(UserManagementResult.Status.FORBIDDEN);
        verify(sessions, never()).revokeAllForUser(any(), any(Long.class), any(), any());
        verify(connection).rollback();
    }

    @Test
    void searchClampsPageBeyondLastPage() throws Exception {
        UserSearchCriteria requested = new UserSearchCriteria("lan", "", "", 9);
        when(users.count(connection, requested)).thenReturn(21L);
        when(users.search(eq(connection), any())).thenReturn(List.of());

        UserPage page = service.search(requested);

        assertThat(page.page()).isEqualTo(2);
        ArgumentCaptor<UserSearchCriteria> criteria = ArgumentCaptor.forClass(UserSearchCriteria.class);
        verify(users).search(eq(connection), criteria.capture());
        assertThat(criteria.getValue().offset()).isEqualTo(20);
    }

    private static UserAccountCommand command(UserStatus status, long version) {
        return new UserAccountCommand(
                "lan.nguyen", "lan@example.com", "Nguyễn Lan", "0901234567", "SALES", status, version);
    }

    private static ManagedUser managedUser(long id, long version) {
        return new ManagedUser(id, "lan.nguyen", "lan@example.com", "Nguyễn Lan", "0901234567",
                UserStatus.ACTIVE, "SALES", "Nhân viên bán hàng", true, version, NOW);
    }
}
