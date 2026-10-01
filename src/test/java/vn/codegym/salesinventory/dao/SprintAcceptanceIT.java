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
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11").withDatabaseName("acceptance").withUsername("test").withPassword("test");
    HikariDataSource source;
    final Instant now=Instant.parse("2026-10-01T00:00:00Z");
    @BeforeAll void prepare() {
        HikariConfig config=new HikariConfig();config.setJdbcUrl(MYSQL.getJdbcUrl()+"?serverTimezone=UTC");config.setUsername(MYSQL.getUsername());config.setPassword(MYSQL.getPassword());source=new HikariDataSource(config);
        // Nâng cấp database cũ, thay vì chỉ thử database trống.
        Flyway.configure().dataSource(source).target("4").load().migrate();
        Flyway.configure().dataSource(source).load().migrate();
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
}
