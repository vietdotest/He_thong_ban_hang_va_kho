package vn.codegym.salesinventory.listener;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import java.time.Clock;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.codegym.salesinventory.config.AppConfig;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.config.DatabaseFactory;
import vn.codegym.salesinventory.dao.JdbcAuditLogRepository;
import vn.codegym.salesinventory.dao.JdbcLoginAttemptRepository;
import vn.codegym.salesinventory.dao.JdbcPasswordResetTokenRepository;
import vn.codegym.salesinventory.dao.JdbcSessionRepository;
import vn.codegym.salesinventory.dao.JdbcUserRepository;
import vn.codegym.salesinventory.security.BCryptPasswordHasher;
import vn.codegym.salesinventory.security.CsrfTokenManager;
import vn.codegym.salesinventory.security.SecureTokenGenerator;
import vn.codegym.salesinventory.service.AuthenticationService;
import vn.codegym.salesinventory.service.PasswordChangeService;
import vn.codegym.salesinventory.service.PasswordResetService;
import vn.codegym.salesinventory.service.SessionService;
import vn.codegym.salesinventory.service.SmtpMailService;

public final class ApplicationLifecycleListener implements ServletContextListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationLifecycleListener.class);

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext servletContext = event.getServletContext();
        AppConfig config = AppConfig.load();
        HikariDataSource dataSource = DatabaseFactory.create(config.database());
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations(config.flywayLocations())
                    .load()
                    .migrate();

            Clock clock = Clock.systemUTC();
            JdbcUserRepository users = new JdbcUserRepository();
            JdbcAuditLogRepository audits = new JdbcAuditLogRepository();
            JdbcSessionRepository sessions = new JdbcSessionRepository();
            BCryptPasswordHasher passwordHasher = new BCryptPasswordHasher();
            AuthenticationService authenticationService = new AuthenticationService(
                    dataSource,
                    users,
                    new JdbcLoginAttemptRepository(),
                    audits,
                    passwordHasher,
                    clock
            );
            SessionService sessionService = new SessionService(
                    dataSource,
                    sessions,
                    audits,
                    clock,
                    Duration.ofMinutes(config.session().idleTimeoutMinutes()),
                    Duration.ofHours(config.session().absoluteTimeoutHours())
            );
            PasswordResetService passwordResetService = new PasswordResetService(
                    dataSource,
                    users,
                    new JdbcPasswordResetTokenRepository(),
                    sessions,
                    audits,
                    passwordHasher,
                    new SmtpMailService(
                            config.passwordReset().mailHost(),
                            config.passwordReset().mailPort(),
                            config.passwordReset().mailFrom()),
                    new SecureTokenGenerator(),
                    clock,
                    Duration.ofMinutes(config.passwordReset().expiryMinutes()),
                    config.passwordReset().appBaseUrl()
            );
            PasswordChangeService passwordChangeService = new PasswordChangeService(
                    dataSource, users, sessions, audits, passwordHasher, clock);

            servletContext.setAttribute(ApplicationContextKeys.DATA_SOURCE, dataSource);
            servletContext.setAttribute(ApplicationContextKeys.AUTHENTICATION_SERVICE, authenticationService);
            servletContext.setAttribute(ApplicationContextKeys.SESSION_SERVICE, sessionService);
            servletContext.setAttribute(ApplicationContextKeys.PASSWORD_RESET_SERVICE, passwordResetService);
            servletContext.setAttribute(ApplicationContextKeys.PASSWORD_CHANGE_SERVICE, passwordChangeService);
            servletContext.setAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER, new CsrfTokenManager());
            LOGGER.info("Application initialized and database migrations completed");
        } catch (RuntimeException exception) {
            dataSource.close();
            throw exception;
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        Object dataSource = event.getServletContext().getAttribute(ApplicationContextKeys.DATA_SOURCE);
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            hikariDataSource.close();
            LOGGER.info("Database connection pool closed");
        }
    }
}
