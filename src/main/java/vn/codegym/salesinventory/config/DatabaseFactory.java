package vn.codegym.salesinventory.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;

public final class DatabaseFactory {
    private DatabaseFactory() {
    }

    public static HikariDataSource create(AppConfig.DatabaseSettings settings) {
        return create(settings, VietnamTime.ZONE.getId(), VietnamTime.OFFSET);
    }

    public static HikariDataSource createMigrationSource(AppConfig.DatabaseSettings settings) {
        // Historical migrations created UTC DATETIME values; V016 converts them once.
        return create(settings, "UTC", "+00:00");
    }

    private static HikariDataSource create(AppConfig.DatabaseSettings settings, String zone, String offset) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("sales-inventory-pool");
        hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikari.setJdbcUrl(timeZoneUrl(settings.jdbcUrl(), zone));
        hikari.setUsername(settings.username());
        hikari.setPassword(settings.password());
        hikari.setMaximumPoolSize(settings.maximumPoolSize());
        hikari.setMinimumIdle(settings.minimumIdle());
        hikari.setConnectionTimeout(settings.connectionTimeoutMs());
        hikari.setAutoCommit(true);
        hikari.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        hikari.setConnectionInitSql("SET time_zone = '" + offset + "'");
        return new HikariDataSource(hikari);
    }

    static String timeZoneUrl(String url, String zone) {
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        var parameters = new ArrayList<String>();
        var overridden = Set.of("servertimezone", "connectiontimezone", "forceconnectiontimezonetosession", "preserveinstants");
        if (query >= 0) {
            for (String parameter : url.substring(query + 1).split("&")) {
                String key = URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8);
                if (!parameter.isBlank() && !overridden.contains(key.toLowerCase(java.util.Locale.ROOT))) parameters.add(parameter);
            }
        }
        parameters.add("connectionTimeZone=" + zone);
        parameters.add("preserveInstants=true");
        return base + "?" + String.join("&", parameters);
    }
}
