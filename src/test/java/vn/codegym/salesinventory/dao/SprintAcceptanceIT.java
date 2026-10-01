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
}
