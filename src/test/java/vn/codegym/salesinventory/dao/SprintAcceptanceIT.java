package vn.codegym.salesinventory.dao;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.model.UserStatus;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SprintAcceptanceIT {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11").withDatabaseName("acceptance").withUsername("test").withPassword("test")
            .withCommand("--log-bin-trust-function-creators=1", "--innodb-flush-log-at-trx-commit=2", "--sync-binlog=0");
    HikariDataSource source;
    final Instant now=Instant.parse("2026-10-01T00:00:00Z");
    @BeforeAll void prepare() {
        HikariConfig config=new HikariConfig();config.setJdbcUrl(MYSQL.getJdbcUrl()+"?serverTimezone=UTC");config.setUsername(MYSQL.getUsername());config.setPassword(MYSQL.getPassword());source=new HikariDataSource(config);
        // Nâng cấp database cũ, thay vì chỉ thử database trống.
        Flyway.configure().dataSource(source).target("4").load().migrate();
        // Tài khoản, vai trò và phiên có dữ liệu trước khi nâng cấp lên Sprint 2.
        Sql.transaction(source,c->{long legacy=Sql.insert(c,"INSERT INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status) SELECT 'legacy-preserved','legacy-preserved','legacy@test.local','legacy@test.local','Tài khoản cũ',password_hash,'ACTIVE' FROM users WHERE id=1");Sql.update(c,"INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='SALES'",legacy);Sql.update(c,"INSERT INTO user_sessions(id,user_id,token_hash,expires_at) VALUES(?,?,?,?)","00000000-0000-0000-0000-000000000099",legacy,"9".repeat(64),java.sql.Timestamp.from(now.plusSeconds(86400)));return null;});
        Flyway.configure().dataSource(source).load().migrate();
        source.close();
        source=vn.codegym.salesinventory.config.DatabaseFactory.create(new vn.codegym.salesinventory.config.AppConfig.DatabaseSettings(
                MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword(),10,1,10000));
        var legacy=Sql.transaction(source,c->Sql.one(c,"SELECT u.username,u.status,r.code,s.token_hash FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id JOIN user_sessions s ON s.user_id=u.id WHERE u.username='legacy-preserved'"));assertThat(legacy.get("status")).isEqualTo("ACTIVE");assertThat(legacy.get("code")).isEqualTo("SALES");assertThat(legacy.get("token_hash")).isEqualTo("9".repeat(64));
    }
    @AfterAll void close() { source.close(); }
    long user(String role) {
        return Sql.transaction(source,c -> {
            String unique=UUID.randomUUID().toString();long id=Sql.insert(c,"INSERT INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status) VALUES(?,?,?,?,?,?,'ACTIVE')",unique,unique,unique+"@test.local",unique+"@test.local","Nhân viên thử",new BCryptPasswordHasher().hash("Abcd1234"));
            Sql.update(c,"INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",id,role);return id;
        });
    }
    @Test void permissionsAreLoadedForThreeRolesAndProtectedCostNeverLeaksToAdmin() {
        var service=new AccessService(source);
        assertThat(service.load(1).allows("USER_MANAGE")).isTrue();assertThat(service.load(1).allows("COST_READ")).isFalse();
        assertThat(service.load(user("WAREHOUSE")).allows("PRODUCT_MANAGE")).isFalse();
        assertThat(service.load(user("SALES_MANAGER")).allows("COST_READ")).isTrue();
        assertThat(service.load(user("SALES")).allows("WAREHOUSE_MANAGE")).isFalse();
    }
    @Test void assignmentKeepsSeveralRolesAndScopesAndRejectsMissingWarehouse() {
        long id=user("SALES");long warehouse=Sql.transaction(source,c -> Sql.insert(c,"INSERT INTO warehouses(code,name) VALUES(?,?)","K"+id,"Kho thử"));
        var service=new AssignmentService(source);
        assertThatThrownBy(() -> service.assign(1,id,Set.of("WAREHOUSE"),Set.of(),Set.of())).isInstanceOf(IllegalArgumentException.class);
        service.assign(1,id,Set.of("WAREHOUSE","SALES"),Set.of(warehouse),Set.of());
        var access=new AccessService(source).load(id);assertThat(access.roles()).containsExactlyInAnyOrder("WAREHOUSE","SALES");assertThat(access.managesWarehouse(warehouse)).isTrue();assertThat(access.managesWarehouse(warehouse+100)).isFalse();
        assertThatThrownBy(() -> service.assign(1,1,Set.of("SALES"),Set.of(),Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pendingActivationRequiresSingleUseEmailToken() {
        AtomicReference<String> url=new AtomicReference<>();
        MailService mail=new MailService() {
            public void sendPasswordReset(String e,String n,String u,int t) { }
            public void sendActivation(String e,String n,String u,String p,String link) { url.set(link); }
        };
        var activation=new ActivationService(source,Clock.fixed(now,ZoneOffset.UTC),"http://localhost:8080",mail);
        var management=new UserManagementService(source,new JdbcUserManagementRepository(),new JdbcSessionRepository(),new JdbcAuditLogRepository(),new BCryptPasswordHasher(),mail,new TemporaryPasswordGenerator(),Clock.fixed(now,ZoneOffset.UTC),activation);
        var command=new UserAccountCommand("new-person","new-person@test.local","Người dùng mới","0901234567","SALES",UserStatus.ACTIVE,0);
        var result=management.create(command,1,new AuthenticationContext("127.0.0.1","JUnit"));
        assertThat(result.status()).isEqualTo(UserManagementResult.Status.SUCCESS);
        var saved=Sql.transaction(source,c -> Sql.one(c,"SELECT status,must_change_password FROM users WHERE id=?",result.userId()));
        assertThat(saved.get("status")).isEqualTo("PENDING_ACTIVATION");
        String token=url.get().substring(url.get().indexOf("token=")+6);
        assertThat(activation.activate(token)).isTrue();assertThat(activation.activate(token)).isFalse();
        assertThat(Sql.transaction(source,c -> Sql.one(c,"SELECT status FROM users WHERE id=?",result.userId())).get("status")).isEqualTo("ACTIVE");
    }
    @Test void activationExpiredAtTwentyFourHoursDoesNotActivateUser() {
        long id=user("SALES");Sql.transaction(source,c -> {Sql.update(c,"UPDATE users SET status='PENDING_ACTIVATION' WHERE id=?",id);return null;});
        var service=new ActivationService(source,Clock.fixed(now,ZoneOffset.UTC),"http://localhost",(a,b,d,e) -> { });
        String token=Sql.transaction(source,c -> service.issue(c,id));
        var later=new ActivationService(source,Clock.fixed(now.plus(Duration.ofHours(24)),ZoneOffset.UTC),"http://localhost",(a,b,d,e) -> { });
        assertThat(later.activate(token)).isFalse();
    }
    @Test void accountLockRequiresReasonRevokesOpenSessionAndFlagsRegisteredDealer() {
        long id=user("SALES");String httpSession="http-"+UUID.randomUUID();
        var sessions=new SessionService(source,new JdbcSessionRepository(),new JdbcAuditLogRepository(),Clock.systemUTC(),Duration.ofMinutes(30),Duration.ofHours(8));
        sessions.create(new CurrentUser(id,"staff","staff@test.local","Nhân viên"),httpSession,new AuthenticationContext("127.0.0.1","JUnit"));
        Sql.transaction(source,c -> {Sql.update(c,"INSERT INTO dealer_staff_references VALUES('DAILY-01',?)",id);return null;});
        var status=new AccountStatusService(source);
        assertThatThrownBy(() -> status.change(1,id,true," ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(sessions.validate(httpSession).valid()).isTrue();
        status.change(1,id,true,"Nhân viên nghỉ việc");
        assertThat(sessions.validate(httpSession).valid()).isFalse();
        var warnings=Sql.transaction(source,c -> Sql.query(c,"SELECT id FROM handover_warnings WHERE user_id=? AND resolved_at IS NULL",id));
        assertThat(warnings).hasSize(1);
        status.change(1,id,false,"");
        assertThat(sessions.validate(httpSession).valid()).isFalse();
    }
    @Test void unlockingPendingAccountCannotBypassEmailActivation() {
        long id=user("SALES");Sql.transaction(source,c -> {Sql.update(c,"UPDATE users SET status='PENDING_ACTIVATION' WHERE id=?",id);return null;});
        var status=new AccountStatusService(source);status.change(1,id,true,"Tạm ngưng");status.change(1,id,false,"");
        assertThat(Sql.transaction(source,c -> Sql.one(c,"SELECT status FROM users WHERE id=?",id)).get("status")).isEqualTo("PENDING_ACTIVATION");
    }
    @Test void profileChangesOnlyContactFieldsAndAuditNeverReturnsCostToAdministrator() {
        long id=user("SALES");var before=Sql.transaction(source,c -> Sql.one(c,"SELECT username,status FROM users WHERE id=?",id));
        new ProfileService(source).update(id,"Tên mới",String.format("09%08d",id));
        var after=Sql.transaction(source,c -> Sql.one(c,"SELECT username,status,full_name FROM users WHERE id=?",id));
        assertThat(after.get("username")).isEqualTo(before.get("username"));assertThat(after.get("status")).isEqualTo("ACTIVE");assertThat(after.get("full_name")).isEqualTo("Tên mới");
        Sql.transaction(source,c -> {AuditService.record(c,1,"PRODUCT_UPDATED","PRODUCT",id,Map.of("name","Lon cũ","cost_price",new java.math.BigDecimal("98765.4321")),Map.of("name","Lon mới","cost_price",new java.math.BigDecimal("12345.6789")));return null;});
        var adminAccess=new AccessService(source).load(1);
        var rows=Sql.transaction(source,c -> AuditService.read(c,adminAccess," WHERE a.object_type='PRODUCT' AND a.object_id=?",new Object[]{id},0));
        assertThat(rows).hasSize(1);assertThat(rows.get(0)).doesNotContainKey("before_cost");assertThat(rows.toString()).doesNotContain("98765","12345","cost_price");
        long manager=user("ADMIN");new AssignmentService(source).assign(1,manager,Set.of("ADMIN","SALES_MANAGER"),Set.of(),Set.of());
        var permitted=new AccessService(source).load(manager);
        var allowed=Sql.transaction(source,c -> AuditService.read(c,permitted," WHERE a.object_type='PRODUCT' AND a.object_id=?",new Object[]{id},0));
        assertThat(allowed.get(0).get("before_cost").toString()).contains("98765.4321");
    }
    @Test void businessChangesAndAuditSnapshotsRollbackTogether() {
        long id=user("SALES");
        assertThatThrownBy(() -> Sql.transaction(source,c -> {AuditService.record(c,1,"ROLLBACK_TEST","TEST",id,null,Map.of("name","Không được lưu"));throw new IllegalStateException("Hủy giao dịch");})).isInstanceOf(IllegalStateException.class);
        var records=Sql.transaction(source,c -> Sql.query(c,"SELECT id FROM audit_logs WHERE event_type='ROLLBACK_TEST' AND object_id=?",id));assertThat(records).isEmpty();
    }
    @Test void userExcelImportsOnlyValidRowsAndCannotConfirmTwice() {
        MailService mail=new MailService(){public void sendPasswordReset(String a,String b,String d,int e){} public void sendActivation(String a,String b,String u,String p,String link){}};
        var activation=new ActivationService(source,Clock.systemUTC(),"http://localhost",mail);
        var management=new UserManagementService(source,new JdbcUserManagementRepository(),new JdbcSessionRepository(),new JdbcAuditLogRepository(),new BCryptPasswordHasher(),mail,new TemporaryPasswordGenerator(),Clock.systemUTC(),activation);
        var imports=new UserImportService(source,management);
        String name="excel-"+UUID.randomUUID().toString().substring(0,8);
        var valid=List.of(name,name+"@test.local","Người nhập","0912345678","SALES,ACCOUNTANT","","");
        var preview=imports.preview(1,Xlsx.write(List.of(UserImportService.HEADERS,valid,valid,List.of("bad","email","X","bad","WAREHOUSE","",""))));
        assertThat(preview.getLines().stream().filter(ImportPreview.Line::isValid).count()).isEqualTo(1);
        var report=imports.confirm(1,preview,preview.getToken(),new AuthenticationContext("127.0.0.1","JUnit"));
        assertThat(report.stream().filter(ImportPreview.Line::isValid).count()).isEqualTo(1);
        var account=Sql.transaction(source,c->Sql.one(c,"SELECT id,status FROM users WHERE username=?",name));assertThat(account.get("status")).isEqualTo("PENDING_ACTIVATION");
        assertThat(new AccessService(source).load(Sql.id(account.get("id"))).roles()).containsExactlyInAnyOrder("SALES","ACCOUNTANT");
        assertThatThrownBy(()->imports.confirm(1,preview,preview.getToken(),new AuthenticationContext("127.0.0.1","JUnit"))).isInstanceOf(IllegalArgumentException.class);
    }
 @Test void categoryTreeSupportsThreeLevelsAndRejectsCyclesAndNonemptyDeletion(){long manager=user("SALES_MANAGER");var categories=new CategoryService(source);long a=categories.save(manager,0,"CA","Nhóm gốc",null,0),b=categories.save(manager,0,"CB","Nhóm con",a,0),d=categories.save(manager,0,"CC","Nhóm cấp ba",b,0);assertThat(categories.tree().stream().filter(row->Sql.id(row.get("id"))==d).findFirst().orElseThrow().get("depth")).isEqualTo(2);assertThatThrownBy(()->categories.save(manager,a,"CA","Vòng lặp",d,1)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->categories.delete(manager,a)).isInstanceOf(IllegalArgumentException.class);categories.delete(manager,d);}
 @Test void productEnforcesSkuCostCategoryTransferAndReferenceDeletion(){
  long manager=user("SALES_MANAGER"),reader=user("SALES");var categories=new CategoryService(source);long a=categories.save(manager,0,"PROD-A","Nhóm đầu",null,0),b=categories.save(manager,0,"PROD-B","Nhóm sau",null,0);var service=new ProductService(source);
  var in=new ProductService.Input("SKU-TEST","Mặt hàng",a,"Cái","Thùng",new java.math.BigDecimal("76543.2100"),null,"ACTIVE",0);long id=service.save(manager,0,in);
  assertThat(service.find(reader,id)).doesNotContainKey("cost_price");assertThat(service.find(1,id).toString()).doesNotContain("76543");assertThat(service.find(manager,id).get("cost_price")).isEqualTo(new java.math.BigDecimal("76543.2100"));
  assertThatThrownBy(()->service.save(reader,id,in)).isInstanceOf(SecurityException.class);assertThatThrownBy(()->service.save(manager,0,in)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->categories.delete(manager,a)).isInstanceOf(IllegalArgumentException.class);
  service.save(manager,id,new ProductService.Input("SKU-TEST","Mặt hàng",b,"Cái","Thùng",null,null,"ACTIVE",1));categories.delete(manager,a);
  Sql.transaction(source,c->{Sql.update(c,"INSERT INTO product_transaction_references VALUES(?,'TEST','GD-01')",id);return null;});assertThatThrownBy(()->service.delete(manager,id)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void unitConversionKeepsHistoricalSnapshotAndBlocksOutsideAssignedWarehouse(){
  long manager=user("SALES_MANAGER"),staff=user("WAREHOUSE"),other=user("WAREHOUSE");long warehouse=Sql.transaction(source,c->Sql.insert(c,"INSERT INTO warehouses(code,name) VALUES('UNIT-K','Kho quy đổi')"));new AssignmentService(source).assign(1,staff,Set.of("WAREHOUSE"),Set.of(warehouse),Set.of());long category=new CategoryService(source).save(manager,0,"UNIT-C","Nhóm quy đổi",null,0);long product=new ProductService(source).save(manager,0,new ProductService.Input("UNIT-SP","Hàng quy đổi",category,"Cái","",null,null,"ACTIVE",0));var units=new UnitService(source);
  assertThat(units.list(staff,product)).hasSize(1);assertThatThrownBy(()->units.save(other,0,product,"Thùng",new java.math.BigDecimal("12"),warehouse,0)).isInstanceOf(SecurityException.class);
  long unit=units.save(staff,0,product,"Thùng",new java.math.BigDecimal("12"),warehouse,0);var snapshot=units.convert(staff,unit,new java.math.BigDecimal("2.5"));assertThat(snapshot.baseQuantity()).isEqualByComparingTo("30");Sql.transaction(source,c->UnitService.snapshot(c,"TEST-GD",snapshot));units.save(staff,unit,product,"Thùng",new java.math.BigDecimal("24"),warehouse,1);assertThat(units.convert(staff,unit,new java.math.BigDecimal("2.5")).baseQuantity()).isEqualByComparingTo("60");var old=Sql.transaction(source,c->Sql.one(c,"SELECT factor,unit_version,base_quantity FROM conversion_snapshots WHERE unit_id=?",unit));assertThat((java.math.BigDecimal)old.get("base_quantity")).isEqualByComparingTo("30");assertThat(Sql.id(old.get("unit_version"))).isEqualTo(1);assertThatThrownBy(()->units.delete(staff,unit)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->units.convert(other,unit,java.math.BigDecimal.ONE)).isInstanceOf(SecurityException.class);assertThatThrownBy(()->units.save(staff,0,product,"Lốc",java.math.BigDecimal.ZERO,warehouse,0)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void supplierScopeReferenceAndDiscontinuationAreEnforced(){long staff=user("WAREHOUSE"),other=user("WAREHOUSE");long warehouse=Sql.transaction(source,c->Sql.insert(c,"INSERT INTO warehouses(code,name) VALUES('SUP-K','Kho nhà cung cấp')"));new AssignmentService(source).assign(1,staff,Set.of("WAREHOUSE"),Set.of(warehouse),Set.of());var service=new SupplierService(source);var input=new SupplierService.Input("NCC-01","Nhà cung cấp thử","0123456789","Nguyễn Văn An","0909988776","30 ngày",warehouse,"ACTIVE",0);assertThatThrownBy(()->service.save(other,0,input)).isInstanceOf(SecurityException.class);long id=service.save(staff,0,input);assertThat(service.list(other,"NCC-01")).isEmpty();Sql.transaction(source,c->{Sql.update(c,"INSERT INTO supplier_receipt_references VALUES(?,'TEST-PN')",id);return null;});assertThatThrownBy(()->service.delete(staff,id)).isInstanceOf(IllegalArgumentException.class);service.save(staff,id,new SupplierService.Input(input.code(),input.name(),input.taxCode(),input.contact(),input.phone(),input.terms(),warehouse,"DISCONTINUED",1));assertThat(service.list(staff,"NCC-01").get(0).get("status")).isEqualTo("DISCONTINUED");}
 @Test void productExcelImportsFiveThousandSkusAndUpdatesWithoutRepeatingPreview(){
  long actor=user("SALES_MANAGER");new CategoryService(source).save(actor,0,"EXCEL-P","Nhóm nhập Excel",null,0);var imports=new ProductImportService(source);var rows=new ArrayList<List<String>>();rows.add(ProductImportService.headers(new AccessService(source).load(actor)));for(int i=0;i<5000;i++)rows.add(List.of("EXCEL-P-"+i,"Sản phẩm "+i,"EXCEL-P","Cái","Hộp","ACTIVE","100.1234"));rows.add(List.of("EXCEL-P-0","Trùng","EXCEL-P","Cái","","ACTIVE","1"));rows.add(List.of("ERROR","Lỗi","MISSING","Cái","","ACTIVE","1"));var preview=imports.preview(actor,Xlsx.write(rows));assertThat(preview.getLines().stream().filter(ImportPreview.Line::isValid).count()).isEqualTo(5000);var result=imports.confirm(actor,preview,preview.getToken());assertThat(result.stream().filter(ImportPreview.Line::isValid).count()).isEqualTo(5000);assertThatThrownBy(()->imports.confirm(actor,preview,preview.getToken())).isInstanceOf(IllegalArgumentException.class);
  var update=imports.preview(actor,Xlsx.write(List.of(rows.get(0),List.of("EXCEL-P-0","Tên cập nhật","EXCEL-P","Cái","Hộp","ACTIVE","200"))));assertThat(update.getLines().get(0).operation()).isEqualTo("Cập nhật");assertThat(imports.confirm(actor,update,update.getToken()).get(0).isValid()).isTrue();
  long noCost=user("ADMIN");Sql.transaction(source,c->{Sql.update(c,"INSERT INTO role_permissions(role_id,permission_id) SELECT r.id,p.id FROM roles r,permissions p WHERE r.code='ADMIN' AND p.code='PRODUCT_MANAGE'");return null;});assertThatThrownBy(()->imports.preview(noCost,Xlsx.write(List.of(rows.get(0),rows.get(1))))).isInstanceOf(SecurityException.class);
 }
 @Test void pricesAreInclusiveRejectOverlapAndLockUsedVersionsWhileAllowingInheritance(){
  long actor=user("SALES_MANAGER");long category=new CategoryService(source).save(actor,0,"PRICE-C","Nhóm giá",null,0);long product=new ProductService(source).save(actor,0,new ProductService.Input("PRICE-SP","Hàng có giá",category,"Cái","",null,null,"ACTIVE",0));var pricing=new PricingService(source);long group=Sql.id(pricing.groups().get(0).get("id"));var from=LocalDate.of(2026,10,1);var to=LocalDate.of(2026,10,31);long version=pricing.create(actor,group,"Giá tháng 10",from,to,null);pricing.saveItem(actor,version,product,new java.math.BigDecimal("100"),new java.math.BigDecimal("90"));assertThat(pricing.quote(actor,group,product,from).sellingPrice()).isEqualByComparingTo("100");assertThat(pricing.quote(actor,group,product,to).floorPrice()).isEqualByComparingTo("90");long overlap=pricing.create(actor,group,"Chồng ngày",to,to.plusDays(10),null);assertThatThrownBy(()->pricing.saveItem(actor,overlap,product,java.math.BigDecimal.TEN,java.math.BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
  var access=new AccessService(source).load(actor);Sql.transaction(source,c->{var quote=PricingService.quote(c,access,group,product,from);assertThat(quote.requiresApproval(new java.math.BigDecimal("89"))).isTrue();assertThat(quote.requiresApproval(new java.math.BigDecimal("90"))).isFalse();PricingService.snapshot(c,"TEST-ORDER",quote);return null;});assertThatThrownBy(()->pricing.saveItem(actor,version,product,new java.math.BigDecimal("110"),new java.math.BigDecimal("90"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->pricing.edit(actor,version,2,"Đổi bản dùng",from,to)).isInstanceOf(IllegalArgumentException.class);long next=pricing.create(actor,group,"Giá kế thừa tháng 11",to.plusDays(1),to.plusDays(30),version);pricing.saveItem(actor,next,product,new java.math.BigDecimal("120"),new java.math.BigDecimal("95"));assertThat(pricing.quote(actor,group,product,to).sellingPrice()).isEqualByComparingTo("100");assertThat(pricing.quote(actor,group,product,to.plusDays(1)).sellingPrice()).isEqualByComparingTo("120");
 }
 @Test void concurrentPriceWritersCannotCreateOverlappingSkuPrices()throws Exception{
  long actor=user("SALES_MANAGER"),category=new CategoryService(source).save(actor,0,"PRICE-CON","Nhóm giá đồng thời",null,0);long product=new ProductService(source).save(actor,0,new ProductService.Input("PRICE-CON","Hàng đồng thời",category,"Cái","",null,null,"ACTIVE",0));var pricing=new PricingService(source);long group=Sql.id(pricing.groups().get(1).get("id"));var from=LocalDate.of(2027,1,1);long a=pricing.create(actor,group,"Bản A",from,from.plusDays(30),null),b=pricing.create(actor,group,"Bản B",from,from.plusDays(30),null);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);try{List<java.util.concurrent.Future<Boolean>> futures=new ArrayList<>();for(long version:List.of(a,b))futures.add(pool.submit(()->{gate.await();try{pricing.saveItem(actor,version,product,java.math.BigDecimal.TEN,java.math.BigDecimal.ONE);return true;}catch(IllegalArgumentException rejected){return false;}}));gate.countDown();int successes=0;for(var f:futures)if(f.get(30,java.util.concurrent.TimeUnit.SECONDS))successes++;assertThat(successes).isEqualTo(1);}finally{pool.shutdownNow();}
 }
 @Test void emailFailureRollsBackPendingAccountActivationAndAudit(){MailService fail=new MailService(){public void sendPasswordReset(String a,String b,String d,int e){}public void sendActivation(String a,String b,String u,String p,String link){throw new IllegalStateException("SMTP lỗi giả lập");}};var activation=new ActivationService(source,Clock.systemUTC(),"http://localhost",fail);var management=new UserManagementService(source,new JdbcUserManagementRepository(),new JdbcSessionRepository(),new JdbcAuditLogRepository(),new BCryptPasswordHasher(),fail,new TemporaryPasswordGenerator(),Clock.systemUTC(),activation);var result=management.create(new UserAccountCommand("fail-email","fail-email@test.local","Chưa gửi được","0998899776","SALES",UserStatus.ACTIVE,0),1,new AuthenticationContext("127.0.0.1","JUnit"));assertThat(result.status()).isEqualTo(UserManagementResult.Status.EMAIL_DELIVERY_FAILED);var rows=Sql.transaction(source,c->Sql.query(c,"SELECT id FROM users WHERE username='fail-email'"));assertThat(rows).isEmpty();var audits=Sql.transaction(source,c->Sql.query(c,"SELECT id FROM audit_logs WHERE after_values LIKE '%fail-email%'"));assertThat(audits).isEmpty();}
}
