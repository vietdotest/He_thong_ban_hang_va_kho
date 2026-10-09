package vn.codegym.salesinventory.config;

import org.flywaydb.core.Flyway;

/** Explicit privileged migration, or validate-only startup for a DML-only runtime. */
public final class SchemaMigration {
    private SchemaMigration() { }
    public static void initialize(AppConfig.DatabaseSettings runtime,AppConfig.MigrationSettings migration,String locations){
        try(var source=DatabaseFactory.createMigrationSource(migration.enabled()?migration.database():runtime)){
            var flyway=Flyway.configure().dataSource(source).locations(locations).load();
            if(migration.enabled())flyway.migrate();
            else {flyway.validate();if(flyway.info().pending().length!=0)throw new IllegalStateException("Database còn migration chưa áp dụng. Chạy migration bằng tài khoản phát hành trước khi bật web.");}
        }
    }
    public static void main(String[] args){
        var config=AppConfig.load();var migration=config.migration();
        if(!migration.enabled())throw new IllegalStateException("Tác vụ migration một lần yêu cầu MIGRATION_ENABLED=true.");
        if(migration.database().username().equals(config.database().username()))throw new IllegalStateException("Tác vụ phát hành phải dùng tài khoản migration riêng, không dùng tài khoản runtime.");
        initialize(config.database(),migration,config.flywayLocations());
    }
}
