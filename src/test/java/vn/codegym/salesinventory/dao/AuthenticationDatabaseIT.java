package vn.codegym.salesinventory.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import vn.codegym.salesinventory.dto.AuthenticationContext;
import vn.codegym.salesinventory.dto.AuthenticationResult;
import vn.codegym.salesinventory.dto.LoginRequest;
import vn.codegym.salesinventory.dto.UserAccountCommand;
import vn.codegym.salesinventory.dto.UserSearchCriteria;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.BCryptPasswordHasher;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.SecureTokenGenerator;
import vn.codegym.salesinventory.service.AuthenticationService;
import vn.codegym.salesinventory.service.PasswordResetService;
import vn.codegym.salesinventory.service.SessionService;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationDatabaseIT {
    private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");
    private static final String ADMIN_PASSWORD_HASH =
            "$2a$12$vclBSd7aqwjDAHl9M.mSwO8ND4cgnGAAAbbscqtVMio.tkwwhkirC";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("sales_inventory")
            .withUsername("sales_app")
            .withPassword("sales_app123")
            .withCommand(
                    "--default-time-zone=+00:00",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci"
            );

    private HikariDataSource dataSource;
    private JdbcUserRepository users;

    @BeforeAll
    void startDatabase() {
        HikariConfig hikari = new HikariConfig();
        String jdbcUrl = MYSQL.getJdbcUrl();
        hikari.setJdbcUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "serverTimezone=UTC");
        hikari.setUsername(MYSQL.getUsername());
        hikari.setPassword(MYSQL.getPassword());
        hikari.setMaximumPoolSize(6);
        dataSource = new HikariDataSource(hikari);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        dataSource.close();
        dataSource = vn.codegym.salesinventory.config.DatabaseFactory.create(new vn.codegym.salesinventory.config.AppConfig.DatabaseSettings(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), 6, 1, 10000));
        users = new JdbcUserRepository();
    }

    @BeforeEach
    void resetAdmin() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE users
                     SET failed_login_count = 0, locked_until = NULL, status = 'ACTIVE', password_hash = ?
                     WHERE username_normalized = 'admin'
                     """)) {
            statement.setString(1, ADMIN_PASSWORD_HASH);
            statement.executeUpdate();
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement resetTokens = connection.prepareStatement("DELETE FROM password_reset_tokens");
             PreparedStatement sessions = connection.prepareStatement("DELETE FROM user_sessions");
             PreparedStatement attempts = connection.prepareStatement("DELETE FROM login_attempts");
             PreparedStatement audits = connection.prepareStatement("DELETE FROM audit_logs")) {
            resetTokens.executeUpdate();
            sessions.executeUpdate();
            attempts.executeUpdate();
            audits.executeUpdate();
        }
    }

    @AfterAll
    void closePool() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void migrationSeedsWorkingAdminBcryptHash() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            Optional<vn.codegym.salesinventory.model.User> admin =
                    users.findByIdentityForUpdate(connection, "admin");
            connection.rollback();

            assertThat(admin).isPresent();
            assertThat(new BCryptPasswordHasher().matches("admin123", admin.orElseThrow().passwordHash())).isTrue();
        }
    }

    @Test
    void normalizedUsernameAndEmailAreUnique() {
        assertThatThrownBy(() -> insertUser("another", "admin", "another@local.test", "another@local.test"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertUser("another", "another", "other@example.test", "admin@local.test"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void sqlInjectionIdentityDoesNotMatchAdmin() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            Optional<vn.codegym.salesinventory.model.User> result =
                    users.findByIdentityForUpdate(connection, "admin' or '1'='1");
            connection.rollback();
            assertThat(result).isEmpty();
        }
    }

    @Test
    void concurrentFailuresAreSerializedWithoutLostUpdate() throws Exception {
        AuthenticationService service = service();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AuthenticationResult> first = executor.submit(() -> authenticateAfter(start, service));
            Future<AuthenticationResult> second = executor.submit(() -> authenticateAfter(start, service));
            start.countDown();
            assertThat(first.get().authenticated()).isFalse();
            assertThat(second.get().authenticated()).isFalse();
        } finally {
            executor.shutdownNow();
        }

        assertThat(adminFailureCount()).isEqualTo(2);
        assertThat(loginAttemptCount()).isEqualTo(2);
    }

    @Test
    void persistentSessionCanBeValidatedAndRevoked() {
        JdbcSessionRepository sessionRepository = new JdbcSessionRepository();
        SessionService sessions = new SessionService(dataSource, sessionRepository, new JdbcAuditLogRepository(),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(30), Duration.ofHours(8));
        CurrentUser admin = new CurrentUser(1L, "admin", "admin@local.test", "Quản trị viên local");
        AuthenticationContext context = new AuthenticationContext("127.0.0.1", "Integration test");

        sessions.create(admin, "browser-session-id", context);
        assertThat(sessions.validate("browser-session-id").valid()).isTrue();

        sessions.revoke("browser-session-id", "USER_LOGOUT", context);
        assertThat(sessions.validate("browser-session-id").valid()).isFalse();
    }

    @Test
    void resetTokenIsOneTimeAndRevokesExistingSessions() throws Exception {
        JdbcSessionRepository sessionRepository = new JdbcSessionRepository();
        JdbcAuditLogRepository auditRepository = new JdbcAuditLogRepository();
        SessionService sessionService = new SessionService(dataSource, sessionRepository, auditRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(30), Duration.ofHours(8));
        AuthenticationContext context = new AuthenticationContext("127.0.0.1", "Integration test");
        sessionService.create(
                new CurrentUser(1L, "admin", "admin@local.test", "Quản trị viên local"),
                "existing-session",
                context
        );

        AtomicReference<String> resetUrl = new AtomicReference<>();
        PasswordResetService resetService = new PasswordResetService(
                dataSource,
                users,
                new JdbcPasswordResetTokenRepository(),
                sessionRepository,
                auditRepository,
                new BCryptPasswordHasher(),
                (recipient, fullName, url, expiryMinutes) -> resetUrl.set(url),
                new SecureTokenGenerator(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(30),
                "http://localhost:8080"
        );

        resetService.requestReset("admin@local.test", context);
        String query = URI.create(resetUrl.get()).getRawQuery();
        String rawToken = URLDecoder.decode(query.substring("token=".length()), StandardCharsets.UTF_8);
        assertThat(resetService.resetPassword(rawToken, "Newpass123", context)).isTrue();
        assertThat(resetService.resetPassword(rawToken, "Another123", context)).isFalse();
        assertThat(sessionService.validate("existing-session").valid()).isFalse();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            vn.codegym.salesinventory.model.User admin = users.findByIdentityForUpdate(connection, "admin")
                    .orElseThrow();
            connection.rollback();
            assertThat(new BCryptPasswordHasher().matches("Newpass123", admin.passwordHash())).isTrue();
        }
    }

    @Test
    void userManagementMigrationSupportsCreatePhoneSearchAndUniquePhone() throws Exception {
        JdbcUserManagementRepository managedUsers = new JdbcUserManagementRepository();
        UserAccountCommand first = new UserAccountCommand(
                "lan.nguyen", "lan@example.com", "Nguyễn Lan", "0901234567",
                "SALES", UserStatus.ACTIVE, 0);
        UserAccountCommand duplicatePhone = new UserAccountCommand(
                "minh.tran", "minh@example.com", "Trần Minh", "0901 234 567",
                "WAREHOUSE", UserStatus.ACTIVE, 0);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            long userId = managedUsers.create(connection, first, ADMIN_PASSWORD_HASH, NOW);
            managedUsers.replaceRole(connection, userId, "SALES");

            assertThat(managedUsers.search(connection,
                    new UserSearchCriteria("090-123", "SALES", "ACTIVE", 1)))
                    .singleElement()
                    .satisfies(user -> {
                        assertThat(user.username()).isEqualTo("lan.nguyen");
                        assertThat(user.roleCode()).isEqualTo("SALES");
                        assertThat(user.mustChangePassword()).isTrue();
                    });
            assertThatThrownBy(() -> managedUsers.create(connection, duplicatePhone, ADMIN_PASSWORD_HASH, NOW))
                    .isInstanceOf(SQLException.class);
            connection.rollback();
        }
    }

    private AuthenticationResult authenticateAfter(CountDownLatch start, AuthenticationService service) throws Exception {
        start.await();
        return service.authenticate(
                new LoginRequest("admin", "wrong-password"),
                new AuthenticationContext("127.0.0.1", "Integration test")
        );
    }

    private AuthenticationService service() {
        return new AuthenticationService(
                dataSource,
                users,
                new JdbcLoginAttemptRepository(),
                new JdbcAuditLogRepository(),
                new BCryptPasswordHasher(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private void insertUser(String username, String normalizedUsername, String email, String normalizedEmail)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO users
                         (username, username_normalized, email, email_normalized, full_name, password_hash, status)
                     VALUES (?, ?, ?, ?, 'Test user', '$2a$12$vclBSd7aqwjDAHl9M.mSwO8ND4cgnGAAAbbscqtVMio.tkwwhkirC', 'ACTIVE')
                     """)) {
            statement.setString(1, username);
            statement.setString(2, normalizedUsername);
            statement.setString(3, email);
            statement.setString(4, normalizedEmail);
            statement.executeUpdate();
        }
    }

    private int adminFailureCount() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT failed_login_count FROM users WHERE username_normalized = 'admin'"
             );
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private int loginAttemptCount() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM login_attempts");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }
}
