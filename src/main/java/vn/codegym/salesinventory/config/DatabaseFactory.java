package vn.codegym.salesinventory.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class DatabaseFactory {
    private DatabaseFactory() {
    }

    public static HikariDataSource create(AppConfig.DatabaseSettings settings) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("sales-inventory-pool");
        hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikari.setJdbcUrl(settings.jdbcUrl());
        hikari.setUsername(settings.username());
        hikari.setPassword(settings.password());
        hikari.setMaximumPoolSize(settings.maximumPoolSize());
        hikari.setMinimumIdle(settings.minimumIdle());
        hikari.setConnectionTimeout(settings.connectionTimeoutMs());
        hikari.setAutoCommit(true);
        hikari.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        hikari.setConnectionInitSql("SET time_zone = '+00:00'");
        return new HikariDataSource(hikari);
    }
}
