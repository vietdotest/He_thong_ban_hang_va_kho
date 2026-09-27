package vn.codegym.salesinventory.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class AppConfig {
    private final Properties properties;

    private AppConfig(Properties properties) {
        this.properties = properties;
    }

    public static AppConfig load() {
        Properties properties = new Properties();
        try (InputStream input = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                throw new IllegalStateException("application.properties was not found on the classpath");
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load application configuration", exception);
        }
        return new AppConfig(properties);
    }

    public DatabaseSettings database() {
        return new DatabaseSettings(
                value("db.url", "DB_URL"),
                value("db.username", "DB_USERNAME"),
                value("db.password", "DB_PASSWORD"),
                integerValue("db.pool.maximumSize", "DB_POOL_MAXIMUM_SIZE"),
                integerValue("db.pool.minimumIdle", "DB_POOL_MINIMUM_IDLE"),
                longValue("db.pool.connectionTimeoutMs", "DB_POOL_CONNECTION_TIMEOUT_MS")
        );
    }

    public String flywayLocations() {
        return value("flyway.locations", "FLYWAY_LOCATIONS");
    }

    public SessionSettings session() {
        return new SessionSettings(
                integerValue("session.idleTimeoutMinutes", "SESSION_IDLE_TIMEOUT_MINUTES"),
                integerValue("session.absoluteTimeoutHours", "SESSION_ABSOLUTE_TIMEOUT_HOURS")
        );
    }

    public PasswordResetSettings passwordReset() {
        return new PasswordResetSettings(
                integerValue("passwordReset.expiryMinutes", "PASSWORD_RESET_EXPIRY_MINUTES"),
                value("app.baseUrl", "APP_BASE_URL"),
                value("mail.host", "MAIL_HOST"),
                integerValue("mail.port", "MAIL_PORT"),
                value("mail.from", "MAIL_FROM")
        );
    }

    private String value(String propertyName, String environmentName) {
        String systemValue = System.getProperty(propertyName);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue.trim();
        }
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }
        String configuredValue = properties.getProperty(propertyName);
        if (configuredValue == null || configuredValue.isBlank()) {
            throw new IllegalStateException("Missing required configuration: " + propertyName);
        }
        return configuredValue.trim();
    }

    private int integerValue(String propertyName, String environmentName) {
        try {
            return Integer.parseInt(value(propertyName, environmentName));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Configuration must be an integer: " + propertyName, exception);
        }
    }

    private long longValue(String propertyName, String environmentName) {
        try {
            return Long.parseLong(value(propertyName, environmentName));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Configuration must be a number: " + propertyName, exception);
        }
    }

    public record DatabaseSettings(
            String jdbcUrl,
            String username,
            String password,
            int maximumPoolSize,
            int minimumIdle,
            long connectionTimeoutMs
    ) {
    }

    public record SessionSettings(int idleTimeoutMinutes, int absoluteTimeoutHours) {
    }

    public record PasswordResetSettings(
            int expiryMinutes,
            String appBaseUrl,
            String mailHost,
            int mailPort,
            String mailFrom
    ) {
    }
}
