package vn.codegym.salesinventory.dao;

import com.zaxxer.hikari.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import vn.codegym.salesinventory.service.ProfileService;
import vn.codegym.salesinventory.validation.ProfileValidationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfileAcceptanceIT {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("profile_acceptance").withUsername("test").withPassword("test");
    HikariDataSource source;
    ProfileService service;
    @BeforeAll void prepare() {
        HikariConfig config=new HikariConfig();config.setJdbcUrl(MYSQL.getJdbcUrl()+"?serverTimezone=UTC");
        config.setUsername(MYSQL.getUsername());config.setPassword(MYSQL.getPassword());
        source=new HikariDataSource(config);Flyway.configure().dataSource(source).load().migrate();service=new ProfileService(source);
    }
    @AfterAll void close() { if(source!=null) source.close(); }
    long user(String role) {
        return Sql.transaction(source,c->{
            String name="profile-"+UUID.randomUUID();
            long id=Sql.insert(c,"INSERT INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status) VALUES(?,?,?,?,?,'test-hash','ACTIVE')",name,name,name+"@test.local",name+"@test.local","Tên ban đầu");
            if(role!=null) Sql.update(c,"INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",id,role);
            return id;
        });
    }
    Map<String,Object> state(long id) {
        return Sql.transaction(source,c->Sql.one(c,"SELECT * FROM users WHERE id=?",id));
    }
    List<Map<String,Object>> audits(long id) {
        return Sql.transaction(source,c->Sql.query(c,"SELECT * FROM audit_logs WHERE event_type='PROFILE_UPDATED' AND object_id=?",id));
    }

    @Test void allSevenRolesCanUpdateTheirOwnContactAndAudit() {
        for(String role:List.of("ADMIN","SALES_MANAGER","SALES","WAREHOUSE_MANAGER","WAREHOUSE","ACCOUNTANT","DIRECTOR")) {
            long id=user(role);String phone=String.format("09%08d",id);
            service.update(id,"  Nguyễn O'Đô  ","+84"+phone.substring(1));
            assertThat(service.find(id)).containsEntry("full_name","Nguyễn O'Đô").containsEntry("phone",phone);
            assertThat(state(id)).containsEntry("phone_normalized",phone);
            var records=audits(id);assertThat(records).hasSize(1);
            assertThat(Sql.id(records.get(0).get("actor_user_id"))).isEqualTo(id);
            assertThat(records.get(0).get("before_values").toString()).contains("Tên ban đầu");
            assertThat(records.get(0).get("after_values").toString()).contains("Nguyễn O'Đô",phone);
            assertThat(records.get(0).get("occurred_at")).isNotNull();
        }
    }
    @Test void updateLeavesAccountOtherUserAndAssignedScopesUnchanged() {
        long id=user("SALES"),other=user("WAREHOUSE");
        Sql.transaction(source,c->{
            long warehouse=Sql.insert(c,"INSERT INTO warehouses(code,name) VALUES(?,?)","PROFILE-K-"+id,"Kho hồ sơ");
            long territory=Sql.insert(c,"INSERT INTO territories(code,name) VALUES(?,?)","PROFILE-T-"+id,"Địa bàn hồ sơ");
            Sql.update(c,"INSERT INTO user_warehouses VALUES(?,?)",id,warehouse);
            Sql.update(c,"INSERT INTO user_territories VALUES(?,?)",id,territory);return null;
        });
        var before=state(id);var otherBefore=state(other);
        var roles=Sql.transaction(source,c->Sql.query(c,"SELECT * FROM user_roles WHERE user_id=?",id));
        var warehouses=Sql.transaction(source,c->Sql.query(c,"SELECT * FROM user_warehouses WHERE user_id=?",id));
        var territories=Sql.transaction(source,c->Sql.query(c,"SELECT * FROM user_territories WHERE user_id=?",id));
        service.update(id,"Tên mới",String.format("08%08d",id));
        var after=state(id);
        for(String key:before.keySet()) if(!Set.of("full_name","phone","phone_normalized","version","updated_at").contains(key)) assertThat(after.get(key)).as(key).isEqualTo(before.get(key));
        assertThat(state(other)).isEqualTo(otherBefore);
        var rolesAfter=Sql.transaction(source,c->Sql.query(c,"SELECT * FROM user_roles WHERE user_id=?",id));
        var warehousesAfter=Sql.transaction(source,c->Sql.query(c,"SELECT * FROM user_warehouses WHERE user_id=?",id));
        var territoriesAfter=Sql.transaction(source,c->Sql.query(c,"SELECT * FROM user_territories WHERE user_id=?",id));
        assertThat(rolesAfter).isEqualTo(roles);assertThat(warehousesAfter).isEqualTo(warehouses);assertThat(territoriesAfter).isEqualTo(territories);
    }
    @Test void validationRejectionWritesNeitherProfileNorAudit() {
        long id=user("SALES");var before=state(id);
        assertThatThrownBy(()->service.update(id," ","bad")).isInstanceOf(ProfileValidationException.class);
        assertThat(state(id)).isEqualTo(before);assertThat(audits(id)).isEmpty();
    }
    @Test void currentPhoneIsAllowedButAnotherUsersNormalizedPhoneIsRejected() {
        long id=user("SALES"),other=user("SALES");String phone=String.format("07%08d",id);
        service.update(id,"Tên mới",phone);service.update(id,"Tên tiếp", "+84"+phone.substring(1));
        var before=state(other);
        assertThatThrownBy(()->service.update(other,"Không lưu",phone)).isInstanceOfSatisfying(ProfileValidationException.class,e->assertThat(e.errors()).containsEntry("phone","Số điện thoại đã được sử dụng."));
        assertThat(state(other)).isEqualTo(before);assertThat(audits(other)).isEmpty();
    }
    @Test void serviceDeniesReadsAndUpdatesWithoutProfilePermission() {
        long id=user(null);var before=state(id);
        assertThatThrownBy(()->service.find(id)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.update(id,"Tên mới","0909999999")).isInstanceOf(SecurityException.class);
        assertThat(state(id)).isEqualTo(before);assertThat(audits(id)).isEmpty();
    }
    @Test void auditInsertFailureRollsBackContactAndVersion() throws Exception {
        long id=user("SALES");var before=state(id);
        DataSource failing=mock(DataSource.class);
        when(failing.getConnection()).thenAnswer(invocation->{
            Connection connection=source.getConnection();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
                if(m.getName().equals("prepareStatement") && args[0].toString().startsWith("INSERT INTO audit_logs")) {
                    throw new SQLException("Test audit failure","45000");
                }
                return invoke(connection,m,args);
            });
        });
        assertThatThrownBy(()->new ProfileService(failing).update(id,"Không được lưu",String.format("05%08d",id))).isInstanceOf(IllegalStateException.class);
        assertThat(state(id)).isEqualTo(before);assertThat(audits(id)).isEmpty();
    }
    @Test void simultaneousClaimsOfSamePhoneReturnOneFieldErrorAndOneSuccess() throws Exception {
        long a=user("SALES"),b=user("SALES");String phone=String.format("03%08d",a);
        CyclicBarrier barrier=new CyclicBarrier(2);
        DataSource concurrent=mock(DataSource.class);
        when(concurrent.getConnection()).thenAnswer(invocation->wrapConnection(source.getConnection(),barrier));
        ProfileService racing=new ProfileService(concurrent);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->attempt(racing,a,phone));var second=executor.submit(()->attempt(racing,b,phone));
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder("saved","phone-error");
            var claimants=Sql.transaction(source,c->Sql.query(c,"SELECT id FROM users WHERE phone_normalized=?",phone));
            assertThat(claimants).hasSize(1);
            assertThat(audits(a).size()+audits(b).size()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    String attempt(ProfileService target,long id,String phone) {
        try { target.update(id,"Tên đồng thời",phone);return "saved"; }
        catch(ProfileValidationException exception) { assertThat(exception.errors()).containsKey("phone");return "phone-error"; }
    }
    // Synchronize immediately before the real UPDATE so both pre-checks see an unused phone.
    Connection wrapConnection(Connection connection,CyclicBarrier barrier) {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            Object result=invoke(connection,method,args);
            if(method.getName().equals("prepareStatement") && args[0].toString().startsWith("UPDATE users SET full_name=")) {
                PreparedStatement statement=(PreparedStatement)result;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                    if(m.getName().equals("executeUpdate")) barrier.await(15,TimeUnit.SECONDS);
                    return invoke(statement,m,a);
                });
            }
            return result;
        });
    }
    Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try { return method.invoke(target,args); } catch(InvocationTargetException exception) { throw exception.getCause(); }
    }
}
