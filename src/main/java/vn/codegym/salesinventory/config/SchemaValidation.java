package vn.codegym.salesinventory.config;

/** Read-only release check using exactly the runtime database credentials. */
public final class SchemaValidation {
    private SchemaValidation() { }
    public static void main(String[] args) {
        var config=AppConfig.load();
        if(config.migration().enabled())throw new IllegalStateException("Kiểm tra runtime yêu cầu MIGRATION_ENABLED=false.");
        SchemaMigration.initialize(config.database(),new AppConfig.MigrationSettings(false,config.database()),config.flywayLocations());
        System.out.println("Runtime xác thực schema thành công; không chạy migration.");
    }
}
