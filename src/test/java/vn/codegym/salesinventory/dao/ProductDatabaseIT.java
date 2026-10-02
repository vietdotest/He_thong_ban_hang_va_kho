package vn.codegym.salesinventory.dao;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import vn.codegym.salesinventory.config.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.ProductValidationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductDatabaseIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("product_acceptance").withUsername("test").withPassword("test")
            .withCommand("--log-bin-trust-function-creators=1");
    HikariDataSource source;
    @TempDir Path uploads;
    ImageStorage images;
    ProductService products;
    long manager, category;

    @BeforeAll void migrate() {
        source = DatabaseFactory.create(new AppConfig.DatabaseSettings(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), 10, 1, 10000));
        Flyway.configure().dataSource(source).load().migrate();
    }
    @BeforeEach void fixture() {
        images = new ImageStorage(uploads);
        products = new ProductService(source, images);
        manager = user("SALES_MANAGER");
        category = new CategoryService(source).save(manager, 0, "C-" + UUID.randomUUID(), "Nhóm sản phẩm", null, 0);
    }
    @AfterEach void removeFailureTriggers() {
        Sql.transaction(source, c -> { Sql.update(c, "DROP TRIGGER IF EXISTS product_audit_fail"); Sql.update(c, "DROP TRIGGER IF EXISTS product_insert_fail"); return null; });
    }
    @AfterAll void close() { if (source != null) source.close(); }
    long user(String role) {
        return Sql.transaction(source, c -> {
            String name = "u-" + UUID.randomUUID();
            long id = Sql.insert(c, "INSERT INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status) SELECT ?,?,?,?,'Người thử',password_hash,'ACTIVE' FROM users WHERE id=1",
                    name, name, name + "@test.local", name + "@test.local");
            Sql.update(c, "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?", id, role);
            return id;
        });
    }
    ProductService.Input input(String sku, String name, String image, long version) {
        return new ProductService.Input(sku, name, category, "Lon", "Thùng 24 lon", new BigDecimal("123.4567"), image, "ACTIVE", version);
    }
    String sku() { return "P-" + UUID.randomUUID(); }
    Map<String, Object> row(long id) { return Sql.transaction(source, c -> Sql.one(c, "SELECT * FROM products WHERE id=?", id)); }
    long auditCount(long id) { return Sql.transaction(source, c -> Sql.id(Sql.one(c, "SELECT COUNT(*) n FROM audit_logs WHERE object_type='PRODUCT' AND object_id=?", id).get("n"))); }
    List<Map<String, Object>> query(String sql, Object... args) { return Sql.transaction(source, c -> Sql.query(c, sql, args)); }
    byte[] png(int color) throws IOException {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 20; y++) for (int x = 0; x < 40; x++) image.setRGB(x, y, color);
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
    }
    void auditFailure() {
        Sql.transaction(source, c -> { Sql.update(c, "CREATE TRIGGER product_audit_fail BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit rejected'"); return null; });
    }
    long fileCount() throws IOException { try (var files = Files.list(uploads)) { return files.count(); } }

    @Test void allFieldsAreNormalizedAndAuditedWithSeparateCost() {
        String sku = sku();
        long id = products.save(manager, 0, new ProductService.Input(" " + sku + " ", "  Cà phê O'Neil  ", category, " Lon ", " Thùng ", new BigDecimal("0"), null, "ACTIVE", 0));
        assertThat(row(id)).containsEntry("sku", sku).containsEntry("name", "Cà phê O'Neil").containsEntry("base_unit", "Lon").containsEntry("packaging", "Thùng");
        var audit = Sql.transaction(source, c -> Sql.one(c, "SELECT actor_user_id,before_values,after_values,after_cost FROM audit_logs WHERE object_type='PRODUCT' AND object_id=?", id));
        assertThat(Sql.id(audit.get("actor_user_id"))).isEqualTo(manager);
        assertThat(audit.get("before_values")).isNull();
        assertThat(audit.get("after_values").toString()).contains(sku, "image_key").doesNotContain("cost_price");
        assertThat(audit.get("after_cost").toString()).contains("cost_price");
        assertThat((BigDecimal) row(id).get("cost_price")).isZero();
        var base = Sql.transaction(source, c -> Sql.one(c, "SELECT name,factor,is_base FROM product_units WHERE product_id=?", id));
        assertThat(base.get("name")).isEqualTo("Lon"); assertThat((BigDecimal) base.get("factor")).isEqualByComparingTo("1");
    }
    @Test void changesGroupStatusPackagingAndUnreferencedBaseUnit() {
        String sku = sku(); long id = products.save(manager, 0, input(sku, "Ban đầu", null, 0));
        long second = new CategoryService(source).save(manager, 0, "C-" + UUID.randomUUID(), "Nhóm khác", null, 0);
        products.save(manager, id, new ProductService.Input(sku, "Đã sửa", second, "Chai", "Hộp 6 chai", null, null, "DISCONTINUED", 1));
        assertThat(row(id)).containsEntry("name", "Đã sửa").containsEntry("status", "DISCONTINUED").containsEntry("packaging", "Hộp 6 chai");
        assertThat(Sql.id(row(id).get("category_id"))).isEqualTo(second);
        assertThat((BigDecimal) row(id).get("cost_price")).isEqualByComparingTo("123.4567");
        assertThat(Sql.transaction(source, c -> Sql.one(c, "SELECT name FROM product_units WHERE product_id=? AND is_base=1", id)).get("name")).isEqualTo("Chai");
        assertThat(auditCount(id)).isEqualTo(2);
    }
    @Test void sevenRolesAndMultipleRolesCannotLeakCost() {
        long id = products.save(manager, 0, input(sku(), "Hàng", null, 0));
        for (String role : List.of("ADMIN", "SALES", "WAREHOUSE", "WAREHOUSE_MANAGER", "ACCOUNTANT", "DIRECTOR")) {
            long reader = user(role);
            assertThat(products.find(reader, id)).doesNotContainKey("cost_price");
            assertThat(products.list(reader, "", category, "", 1).toString()).doesNotContain("123.4567", "cost_price");
            assertThatThrownBy(() -> products.save(reader, id, input(Sql.text(row(id).get("sku")), "Giả mạo", null, 1))).isInstanceOf(SecurityException.class);
        }
        assertThat(products.find(manager, id)).containsKey("cost_price");
        long multi = user("ADMIN");
        new AssignmentService(source).assign(1, multi, Set.of("ADMIN", "SALES_MANAGER"), Set.of(), Set.of());
        assertThat(products.find(multi, id)).containsKey("cost_price");
        var adminAudit = Sql.transaction(source, c -> AuditService.read(c, new AccessService(source).load(1), " WHERE a.object_type='PRODUCT' AND a.object_id=?", new Object[]{id}, 0));
        assertThat(adminAudit.toString()).doesNotContain("123.4567", "cost_price", "after_cost");
    }
    @Test void customProductPermissionCannotWriteOrReadCost() {
        String role = "T-" + UUID.randomUUID().toString().substring(0, 8);
        Sql.transaction(source, c -> {
            long roleId = Sql.insert(c, "INSERT INTO roles(code,name) VALUES(?,'Vai trò thử')", role);
            Sql.update(c, "INSERT INTO role_permissions(role_id,permission_id) SELECT ?,id FROM permissions WHERE code IN ('PRODUCT_MANAGE','CATALOG_READ','COST_READ','COST_WRITE')", roleId);
            return null;
        });
        long actor = user(role), id = products.save(manager, 0, input(sku(), "Hàng", null, 0));
        assertThat(products.find(actor, id)).doesNotContainKey("cost_price");
        assertThatThrownBy(() -> products.save(actor, id, input(Sql.text(row(id).get("sku")), "Thử giá vốn", null, 1))).isInstanceOf(SecurityException.class);
        products.save(actor, id, new ProductService.Input(Sql.text(row(id).get("sku")), "Sửa không giá vốn", category, "Lon", "", null, null, "ACTIVE", 1));
        assertThat((BigDecimal) row(id).get("cost_price")).isEqualByComparingTo("123.4567");
    }
    @Test void unchangedSkuIsAllowedButDuplicateAndCaseVariantAreRejected() {
        String sku = sku(); long first = products.save(manager, 0, input(sku, "Một", null, 0));
        products.save(manager, first, input(sku, "Đổi tên", null, 1));
        assertThatThrownBy(() -> products.save(manager, 0, input(sku.toLowerCase(Locale.ROOT), "Trùng", null, 0)))
                .isInstanceOfSatisfying(ProductValidationException.class, e -> assertThat(e.errors()).containsKey("sku"));
        assertThat(auditCount(first)).isEqualTo(2);
    }
    @Test void databaseUniqueConstraintAlsoBlocksBypassingService() {
        String sku = sku(); products.save(manager, 0, input(sku, "Một", null, 0));
        assertThatThrownBy(() -> Sql.transaction(source, c -> Sql.insert(c, "INSERT INTO products(sku,name,category_id,base_unit) VALUES(?, 'Trùng', ?, 'Lon')", sku, category)))
                .isInstanceOf(IllegalStateException.class).hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class);
    }
    @Test void simultaneousSkuCreationHasOneWinnerAndOneAudit() throws Exception {
        String sku = sku(); var gate = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> { gate.await(); try { products.save(manager, 0, input(sku, "Đồng thời", null, 0)); return true; }
                catch (ProductValidationException e) { assertThat(e.errors()).containsKey("sku"); return false; } }));
            gate.countDown(); int wins = 0; for (var task : tasks) if (task.get(30, TimeUnit.SECONDS)) wins++;
            assertThat(wins).isEqualTo(1);
            var rows = Sql.transaction(source, c -> Sql.query(c, "SELECT id FROM products WHERE sku=?", sku));
            assertThat(rows).hasSize(1); assertThat(auditCount(Sql.id(rows.get(0).get("id")))).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void staleVersionAndDeletedCategoryAreBusinessErrorsWithoutAudit() {
        String sku = sku(); long id = products.save(manager, 0, input(sku, "Một", null, 0));
        assertThatThrownBy(() -> products.save(manager, id, input(sku, "Cũ", null, 0)))
                .isInstanceOfSatisfying(ProductValidationException.class, e -> assertThat(e.errors()).containsKey("form"));
        assertThatThrownBy(() -> products.save(manager, id, new ProductService.Input(sku, "Sai nhóm", Long.MAX_VALUE, "Lon", "", null, null, "ACTIVE", 1)))
                .isInstanceOfSatisfying(ProductValidationException.class, e -> assertThat(e.errors()).containsKey("category"));
        assertThat(auditCount(id)).isEqualTo(1); assertThat(row(id).get("name")).isEqualTo("Một");
    }
    @Test void rejectedValidationCreatesNeitherProductNorAudit() {
        String sku = sku();
        assertThatThrownBy(() -> products.save(manager, 0, new ProductService.Input(sku, " ", category, "Lon", "", null, null, "ACTIVE", 0))).isInstanceOf(ProductValidationException.class);
        assertThat(query("SELECT id FROM products WHERE sku=?", sku)).isEmpty();
    }
    @Test void replacingImageDeletesOldFilesOnlyAfterDatabaseCommit() throws Exception {
        String sku = sku(); long id = products.saveWithImage(manager, 0, input(sku, "Một", null, 0), png(0xff0000));
        String old = Sql.text(row(id).get("image_key"));
        ImageStorage observed = spy(images);
        doAnswer(call -> { assertThat(row(id).get("image_key")).isNotEqualTo(old); assertThat(auditCount(id)).isEqualTo(2); return call.callRealMethod(); }).when(observed).remove(old);
        new ProductService(source, observed).saveWithImage(manager, id, input(sku, "Hai", null, 1), png(0x00ff00));
        String current = Sql.text(row(id).get("image_key"));
        assertThat(images.path(old, false)).doesNotExist(); assertThat(images.path(old, true)).doesNotExist();
        assertThat(images.path(current, false)).exists(); assertThat(images.path(current, true)).exists();
        assertThat(ImageIO.read(images.path(current, false).toFile()).getWidth()).isEqualTo(512);
        assertThat(ImageIO.read(images.path(current, true).toFile()).getWidth()).isEqualTo(128);
        assertThat(fileCount()).isEqualTo(2);
        var audit = Sql.transaction(source, c -> Sql.one(c, "SELECT before_values,after_values FROM audit_logs WHERE object_type='PRODUCT' AND object_id=? ORDER BY id DESC LIMIT 1", id));
        assertThat(audit.get("before_values").toString()).contains(old); assertThat(audit.get("after_values").toString()).contains(current);
    }
    @Test void editingWithoutUploadKeepsCurrentImage() throws Exception {
        String sku = sku(); long id = products.saveWithImage(manager, 0, input(sku, "Một", null, 0), png(0xff0000));
        String old = Sql.text(row(id).get("image_key")); products.save(manager, id, input(sku, "Hai", null, 1));
        assertThat(row(id).get("image_key")).isEqualTo(old); assertThat(images.path(old, false)).exists(); assertThat(fileCount()).isEqualTo(2);
    }
    @Test void auditFailureRollsBackReplacementAndRemovesOnlyNewImage() throws Exception {
        String sku = sku(); long id = products.saveWithImage(manager, 0, input(sku, "Một", null, 0), png(0xff0000));
        String old = Sql.text(row(id).get("image_key")); auditFailure();
        assertThatThrownBy(() -> products.saveWithImage(manager, id, input(sku, "Không lưu", null, 1), png(0x00ff00))).isInstanceOf(IllegalStateException.class);
        assertThat(row(id)).containsEntry("name", "Một").containsEntry("image_key", old);
        assertThat(Sql.id(row(id).get("version"))).isEqualTo(1); assertThat(auditCount(id)).isEqualTo(1);
        assertThat(images.path(old, false)).exists(); assertThat(images.path(old, true)).exists(); assertThat(fileCount()).isEqualTo(2);
    }
    @Test void auditFailureOnCreationRollsBackBaseUnitAndFiles() throws Exception {
        String sku = sku(); auditFailure();
        assertThatThrownBy(() -> products.saveWithImage(manager, 0, input(sku, "Không lưu", null, 0), png(0xff0000))).isInstanceOf(IllegalStateException.class);
        assertThat(query("SELECT id FROM products WHERE sku=?", sku)).isEmpty();
        assertThat(fileCount()).isZero();
    }
    @Test void otherDatabaseFailureIsNotClassifiedAsValidation() throws Exception {
        Sql.transaction(source, c -> { Sql.update(c, "CREATE TRIGGER product_insert_fail BEFORE INSERT ON products FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='database failure'"); return null; });
        assertThatThrownBy(() -> products.saveWithImage(manager, 0, input(sku(), "Không lưu", null, 0), png(0xff0000))).isInstanceOf(IllegalStateException.class);
        assertThat(fileCount()).isZero();
    }
    @Test void concurrentReplacementsCleanLosingUploadAndKeepWinningImage() throws Exception {
        String sku = sku(); long id = products.saveWithImage(manager, 0, input(sku, "Một", null, 0), png(0xff0000));
        String old = Sql.text(row(id).get("image_key")); byte[] bytes = png(0x00ff00);
        var gate = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> { gate.await(); try { products.saveWithImage(manager, id, input(sku, "Đồng thời", null, 1), bytes); return true; }
                catch (ProductValidationException e) { assertThat(e.errors()).containsKey("form"); return false; } }));
            gate.countDown(); int wins = 0; for (var task : tasks) if (task.get(30, TimeUnit.SECONDS)) wins++;
            assertThat(wins).isEqualTo(1); assertThat(Sql.id(row(id).get("version"))).isEqualTo(2); assertThat(auditCount(id)).isEqualTo(2);
            assertThat(images.path(old, false)).doesNotExist(); assertThat(fileCount()).isEqualTo(2);
            assertThat(images.path(Sql.text(row(id).get("image_key")), false)).exists();
        } finally { pool.shutdownNow(); }
    }
    @Test void failedOldFileCleanupDoesNotRemoveCommittedNewImage() throws Exception {
        String sku = sku(); long id = products.saveWithImage(manager, 0, input(sku, "Một", null, 0), png(0xff0000));
        String old = Sql.text(row(id).get("image_key")); ImageStorage failure = spy(images);
        doThrow(new IOException("cleanup failure")).when(failure).remove(old);
        new ProductService(source, failure).saveWithImage(manager, id, input(sku, "Đã lưu", null, 1), png(0x00ff00));
        assertThat(row(id).get("name")).isEqualTo("Đã lưu");
        assertThat(images.path(Sql.text(row(id).get("image_key")), false)).exists(); assertThat(auditCount(id)).isEqualTo(2);
    }
    @Test void referencedProductsCannotBeDeletedOrChangeBaseUnitButCanBeDiscontinued() {
        String sku = sku(); long id = products.save(manager, 0, input(sku, "Một", null, 0));
        Sql.transaction(source, c -> { Sql.update(c, "INSERT INTO product_transaction_references VALUES(?,'TEST','REFERENCE')", id); return null; });
        assertThatThrownBy(() -> products.delete(manager, id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> products.save(manager, id, new ProductService.Input(sku, "Một", category, "Chai", "", null, null, "ACTIVE", 1)))
                .isInstanceOfSatisfying(ProductValidationException.class, e -> assertThat(e.errors()).containsKey("baseUnit"));
        products.save(manager, id, new ProductService.Input(sku, "Một", category, "Lon", "", null, null, "DISCONTINUED", 1));
        assertThat(row(id).get("status")).isEqualTo("DISCONTINUED"); assertThat(auditCount(id)).isEqualTo(2);
    }
    @Test void alternateUnitsAndConversionSnapshotsProtectBaseAndDeletion() {
        String sku = sku(); long id = products.save(manager, 0, input(sku, "Một", null, 0));
        Sql.transaction(source, c -> {
            long warehouse = Sql.insert(c, "INSERT INTO warehouses(code,name) VALUES(?,'Kho thử')", "K-" + UUID.randomUUID());
            long unit = Sql.insert(c, "INSERT INTO product_units(product_id,name,factor,warehouse_id) VALUES(?,'Thùng',12,?)", id, warehouse);
            Sql.insert(c, "INSERT INTO conversion_snapshots(reference_id,unit_id,product_id,unit_name,factor,unit_version,quantity,base_quantity) VALUES('TEST',?,?,'Thùng',12,1,2,24)", unit, id);
            return null;
        });
        assertThatThrownBy(() -> products.save(manager, id, new ProductService.Input(sku, "Một", category, "Chai", "", null, null, "ACTIVE", 1))).isInstanceOf(ProductValidationException.class);
        assertThatThrownBy(() -> products.delete(manager, id)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quy đổi");
        assertThat(row(id).get("name")).isEqualTo("Một"); assertThat(auditCount(id)).isEqualTo(1);
    }
    @Test void priceReferencesBlockDeletion() {
        long id = products.save(manager, 0, input(sku(), "Một", null, 0)); var prices = new PricingService(source);
        long version = prices.create(manager, Sql.id(prices.groups().get(0).get("id")), "Bảng thử", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2), null);
        prices.saveItem(manager, version, id, new BigDecimal("200"), new BigDecimal("150"));
        assertThatThrownBy(() -> products.delete(manager, id)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bảng giá");
        assertThat(auditCount(id)).isEqualTo(1);
    }
    @Test void deletingUnusedProductCleansImagesAfterCommitAndAuditsFullBeforeState() throws Exception {
        long id = products.saveWithImage(manager, 0, input(sku(), "Một", null, 0), png(0xff0000)); String old = Sql.text(row(id).get("image_key"));
        ImageStorage observed = spy(images);
        doAnswer(call -> { assertThat(query("SELECT id FROM products WHERE id=?", id)).isEmpty(); return call.callRealMethod(); }).when(observed).remove(old);
        new ProductService(source, observed).delete(manager, id);
        assertThat(fileCount()).isZero(); assertThat(auditCount(id)).isEqualTo(2);
        var audit = Sql.transaction(source, c -> Sql.one(c, "SELECT before_values,after_values FROM audit_logs WHERE event_type='PRODUCT_DELETED' AND object_id=?", id));
        assertThat(audit.get("before_values").toString()).contains(old, "base_unit", "status"); assertThat(audit.get("after_values")).isNull();
    }
    @Test void failedDeleteAuditPreservesProductBaseUnitAndImage() throws Exception {
        long id = products.saveWithImage(manager, 0, input(sku(), "Một", null, 0), png(0xff0000)); String old = Sql.text(row(id).get("image_key")); auditFailure();
        assertThatThrownBy(() -> products.delete(manager, id)).isInstanceOf(IllegalStateException.class);
        assertThat(row(id).get("image_key")).isEqualTo(old); assertThat(fileCount()).isEqualTo(2);
        assertThat(query("SELECT id FROM product_units WHERE product_id=?", id)).hasSize(1);
        assertThat(auditCount(id)).isEqualTo(1);
    }
}
