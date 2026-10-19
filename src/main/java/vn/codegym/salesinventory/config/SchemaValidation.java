package vn.codegym.salesinventory.config;

/**
 * Hạ tầng: Kiểm tra schema bằng tài khoản chạy ứng dụng, không sửa dữ liệu.
 */
public final class SchemaValidation {

    private SchemaValidation() {}

    // Chạy bước kiểm tra hoặc nâng cấp schema từ dòng lệnh.
    public static void main(String[] args) {
        var config = AppConfig.load();
        if (config.migration().enabled()) {
            throw new IllegalStateException("Kiểm tra runtime yêu cầu MIGRATION_ENABLED=false.");
        }
        SchemaMigration.initialize(
                config.database(),
                new AppConfig.MigrationSettings(false, config.database()),
                config.flywayLocations());
        System.out.println("Runtime xác thực schema thành công; không chạy migration.");
    }
}
