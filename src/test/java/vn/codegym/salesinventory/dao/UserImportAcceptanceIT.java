package vn.codegym.salesinventory.dao;

import com.zaxxer.hikari.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserImportAcceptanceIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("user_import").withUsername("test").withPassword("test")
            .withCommand("--log-bin-trust-function-creators=1", "--innodb-flush-log-at-trx-commit=2", "--sync-binlog=0");
    HikariDataSource source;
    final AtomicInteger sequence = new AtomicInteger(200000);
    final Instant now = Instant.parse("2026-10-02T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final AuthenticationContext context = new AuthenticationContext("127.0.0.1", "JUnit S2-01");
    final List<String> activationLinks = new CopyOnWriteArrayList<>();
    final MailService mail = new MailService() {
        public void sendPasswordReset(String email,String name,String url,int minutes) { }
        public void sendActivation(String email,String name,String username,String password,String url) { activationLinks.add(url); }
    };

    @BeforeAll void prepare() {
        var config = new HikariConfig(); config.setJdbcUrl(MYSQL.getJdbcUrl()+"?serverTimezone=UTC");
        config.setUsername(MYSQL.getUsername()); config.setPassword(MYSQL.getPassword()); source=new HikariDataSource(config);
        Flyway.configure().dataSource(source).load().migrate();
    }
    @AfterAll void close() { source.close(); }

    UserManagementService management(MailService delivery) {
        return new UserManagementService(source,new JdbcUserManagementRepository(),new JdbcSessionRepository(),new JdbcAuditLogRepository(),
                new BCryptPasswordHasher(),delivery,new TemporaryPasswordGenerator(),clock,new ActivationService(source,clock,"http://localhost",delivery));
    }
    UserImportService imports() { return new UserImportService(source,management(mail),clock); }
    List<String> row() {
        int value=sequence.incrementAndGet(); String name="import-"+value;
        return List.of(name,name+"@test.local","Nguyễn O'An",String.format("09%08d",value),"SALES","","");
    }
    List<String> changed(List<String> row,int column,String value) { var copy=new ArrayList<>(row);copy.set(column,value);return copy; }
    byte[] workbook(List<List<String>> rows) { var all=new ArrayList<List<String>>();all.add(UserImportService.HEADERS);all.addAll(rows);return Xlsx.write(all); }
    ImportPreview preview(List<List<String>> rows) { return imports().preview(1,workbook(rows)); }
    List<ImportPreview.Line> confirm(UserImportService service,ImportPreview value) { return service.confirm(1,value,value.getToken(),context); }
    List<Map<String,Object>> find(String name) { return Sql.transaction(source,c->Sql.query(c,"SELECT id,status,phone_normalized,must_change_password FROM users WHERE username=?",name)); }
    long actor(String role) {
        var input=row(); long id=Sql.transaction(source,c->Sql.insert(c,"INSERT INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status) SELECT ?,?,?,?,'Người thử',password_hash,'ACTIVE' FROM users WHERE id=1",input.get(0),input.get(0),input.get(1),input.get(1)));
        Sql.transaction(source,c->{Sql.update(c,"INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",id,role);return null;});return id;
    }

    @Test void importsMixedRowsAndCreatesActivationRolesScopesAndCorrectAudit() {
        long warehouse=Sql.transaction(source,c->Sql.insert(c,"INSERT INTO warehouses(code,name) VALUES('IMP-K1','Kho nhập thử')"));
        long territory=Sql.transaction(source,c->Sql.insert(c,"INSERT INTO territories(code,name) VALUES('IMP-T1','Địa bàn thử')"));
        var valid=changed(changed(changed(row(),4,"WAREHOUSE,SALES"),5,"IMP-K1"),6,"IMP-T1"); var invalid=changed(row(),3,"abc");
        var service=imports(); var value=service.preview(1,workbook(List.of(valid,invalid)));
        assertThat(value.getLines()).hasSize(2);assertThat(value.getLines().get(0).isValid()).isTrue();assertThat(value.getLines().get(1).number()).isEqualTo(3);
        var report=confirm(service,value);assertThat(report.stream().filter(ImportPreview.Line::isValid)).hasSize(1);
        var saved=find(valid.get(0)).get(0);long id=Sql.id(saved.get("id"));
        assertThat(saved.get("status")).isEqualTo("PENDING_ACTIVATION");assertThat(saved.get("must_change_password")).isEqualTo(true);
        var access=new AccessService(source).load(id);assertThat(access.roles()).containsExactlyInAnyOrder("WAREHOUSE","SALES");assertThat(access.managesWarehouse(warehouse)).isTrue();assertThat(access.territories()).anySatisfy(t->assertThat(Sql.id(t.get("id"))).isEqualTo(territory));
        assertThat(find(invalid.get(0))).isEmpty();
        var audit=Sql.transaction(source,c->Sql.one(c,"SELECT actor_user_id,before_values,after_values FROM audit_logs WHERE event_type='USER_CREATED' AND object_id=?",id));
        assertThat(Sql.id(audit.get("actor_user_id"))).isEqualTo(1);assertThat(audit.get("before_values")).isNull();assertThat(audit.get("after_values").toString()).contains(valid.get(0)).doesNotContain("password","token");
        assertThatThrownBy(()->confirm(service,value)).isInstanceOf(IllegalArgumentException.class);
        String link=activationLinks.get(activationLinks.size()-1),token=link.substring(link.indexOf("token=")+6);
        var activation=new ActivationService(source,clock,"http://localhost",mail);assertThat(activation.activate(token)).isTrue();assertThat(activation.activate(token)).isFalse();
    }

    @Test void erroneousDuplicateRowDoesNotReserveUnusedUsernameOrPhone() {
        var first=row();var second=changed(row(),1,first.get(1));var third=changed(second,1,"fresh-"+sequence.incrementAndGet()+"@test.local");
        var value=preview(List.of(first,second,third));
        assertThat(value.getLines().stream().map(ImportPreview.Line::isValid)).containsExactly(true,false,true);
        assertThat(confirm(imports(),value).stream().filter(ImportPreview.Line::isValid)).hasSize(2);
    }

    @Test void detectsAllDuplicateKeysAndNormalizesInternationalPhones() {
        var first=row();var username=changed(row(),0,first.get(0).toUpperCase(Locale.ROOT));var email=changed(row(),1,first.get(1).toUpperCase(Locale.ROOT));var phone=changed(row(),3,"+84"+first.get(3).substring(1));
        var value=preview(List.of(first,username,email,phone));assertThat(value.getLines().stream().map(ImportPreview.Line::isValid)).containsExactly(true,false,false,false);
    }

    @Test void rejectsBadScopesRolesExtraColumnsAndRetainsOneValidRow() {
        var valid=row();var extra=new ArrayList<>(row());extra.add("ADMIN");
        var value=preview(List.of(changed(row(),4,"UNKNOWN"),changed(row(),4,"WAREHOUSE"),changed(row(),5,"NO-KHO"),changed(row(),6,"NO-DIA-BAN"),extra,valid));
        assertThat(value.getLines().stream().map(ImportPreview.Line::isValid)).containsExactly(false,false,false,false,false,true);
        assertThat(value.getLines().get(0).error()).contains("Vai trò");assertThat(value.getLines().get(2).error()).contains("Kho");assertThat(value.getLines().get(3).error()).contains("Địa bàn");
        assertThat(confirm(imports(),value).stream().filter(ImportPreview.Line::isValid)).hasSize(1);
    }

    @Test void rejectsEmptyWrongHeaderAndBrokenWorkbook() {
        assertThatThrownBy(()->preview(List.of())).hasMessageContaining("không có dòng");
        assertThatThrownBy(()->preview(List.of(List.of("","","")))).hasMessageContaining("không có dòng");
        assertThatThrownBy(()->imports().preview(1,Xlsx.write(List.of(List.of("Sai mẫu"),row())))).hasMessageContaining("Tiêu đề");
        assertThatThrownBy(()->imports().preview(1,new byte[]{1,2,3})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void acceptsLandlineAndRejectsExistingDatabaseAccount() {
        var landline=changed(row(),3,"+84 (24) 1234-5678");var value=preview(List.of(landline,changed(row(),0,"ADMIN"),changed(row(),1,"ADMIN@LOCAL.TEST")));
        assertThat(value.getLines().stream().map(ImportPreview.Line::isValid)).containsExactly(true,false,false);confirm(imports(),value);
        assertThat(find(landline.get(0)).get(0).get("phone_normalized")).isEqualTo("02412345678");
    }

    @Test void confirmationRechecksDatabaseChangesAfterPreview() {
        var input=row();var value=preview(List.of(input));var second=preview(List.of(input));confirm(imports(),second);
        assertThat(confirm(imports(),value).get(0).error()).contains("đã được dùng");assertThat(find(input.get(0))).hasSize(1);
    }

    @Test void confirmationRechecksDeletedWarehouse() {
        long warehouse=Sql.transaction(source,c->Sql.insert(c,"INSERT INTO warehouses(code,name) VALUES('IMP-DELETE','Kho bị xóa')"));
        var input=changed(changed(row(),4,"WAREHOUSE"),5,"IMP-DELETE");var value=preview(List.of(input));
        Sql.transaction(source,c->{Sql.update(c,"DELETE FROM warehouses WHERE id=?",warehouse);return null;});
        assertThat(confirm(imports(),value).get(0).error()).contains("Kho không tồn tại");assertThat(find(input.get(0))).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"SALES_MANAGER","SALES","WAREHOUSE_MANAGER","WAREHOUSE","ACCOUNTANT","DIRECTOR"})
    void sixNonAdminRolesCannotPreviewOrConfirm(String role) {
        long id=actor(role);var service=imports();var bytes=workbook(List.of(row()));var value=preview(List.of(row()));
        assertThatThrownBy(()->service.preview(id,bytes)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.confirm(id,value,value.getToken(),context)).isInstanceOf(SecurityException.class);
    }

    @Test void ownerTokenExpiryAndRevokedPermissionsAreChecked() {
        var value=preview(List.of(row()));long other=actor("ADMIN");
        assertThatThrownBy(()->imports().confirm(other,value,value.getToken(),context)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->imports().confirm(1,value,"forged",context)).isInstanceOf(IllegalArgumentException.class);
        var later=new UserImportService(source,management(mail),Clock.fixed(now.plusSeconds(1800),ZoneOffset.UTC));
        assertThatThrownBy(()->confirm(later,value)).isInstanceOf(IllegalArgumentException.class);
        var own=new ImportPreview(other,value.getLines(),now);Sql.transaction(source,c->{Sql.update(c,"DELETE FROM user_roles WHERE user_id=?",other);return null;});
        assertThatThrownBy(()->imports().confirm(other,own,own.getToken(),context)).isInstanceOf(SecurityException.class);
    }

    @Test void failedEmailRollsBackOnlyItsRowAndImportsFollowingValidRow() {
        var bad=row();var good=row();MailService failing=new MailService(){public void sendPasswordReset(String a,String b,String c,int d){}public void sendActivation(String a,String b,String username,String password,String url){if(username.equals(bad.get(0)))throw new IllegalStateException("SMTP failure");}};
        var service=new UserImportService(source,management(failing),clock);var value=service.preview(1,workbook(List.of(bad,good)));var report=confirm(service,value);
        assertThat(report.stream().map(ImportPreview.Line::isValid)).containsExactly(false,true);assertThat(report.get(0).error()).contains("email");assertThat(find(bad.get(0))).isEmpty();assertThat(find(good.get(0))).hasSize(1);
        var audits=Sql.transaction(source,c->Sql.query(c,"SELECT id FROM audit_logs WHERE after_values LIKE ?","%"+bad.get(0)+"%"));assertThat(audits).isEmpty();
    }

    @Test void auditFailureRollsBackAccountAndIsNotDisguisedAsValidationError() {
        var input=row();var value=preview(List.of(input));
        Sql.transaction(source,c->{Sql.update(c,"CREATE TRIGGER fail_import_audit BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit failure'");return null;});
        try { assertThatThrownBy(()->confirm(imports(),value)).isInstanceOf(RuntimeException.class).isNotInstanceOf(IllegalArgumentException.class);assertThat(find(input.get(0))).isEmpty(); }
        finally {Sql.transaction(source,c->{Sql.update(c,"DROP TRIGGER fail_import_audit");return null;});}
    }

    @Test void permissionRevokedBetweenRowsStopsBeforeNextAccount() {
        long admin=actor("ADMIN");var first=row();var second=row();var users=spy(management(mail));
        doAnswer(call->{var result=call.callRealMethod();Sql.transaction(source,c->{Sql.update(c,"DELETE FROM user_roles WHERE user_id=?",admin);return null;});return result;})
                .when(users).createAssigned(any(),eq(admin),any(),anySet(),anySet(),anySet());
        var service=new UserImportService(source,users,clock);var value=service.preview(admin,workbook(List.of(first,second)));
        assertThatThrownBy(()->service.confirm(admin,value,value.getToken(),context)).isInstanceOf(SecurityException.class);
        assertThat(find(first.get(0))).hasSize(1);assertThat(find(second.get(0))).isEmpty();
    }

    @Test void twoConcurrentImportsCannotCreateDuplicateAccount() throws Exception {
        var input=row();var value=preview(List.of(input));var second=preview(List.of(input));var users=spy(management(mail));var barrier=new CyclicBarrier(2);
        doAnswer(call->{barrier.await(20,TimeUnit.SECONDS);return call.callRealMethod();}).when(users).createAssigned(any(),eq(1L),any(),anySet(),anySet(),anySet());
        var service=new UserImportService(source,users,clock);var pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(()->confirm(service,value));var b=pool.submit(()->confirm(service,second));var results=List.of(a.get(40,TimeUnit.SECONDS).get(0),b.get(40,TimeUnit.SECONDS).get(0));assertThat(results.stream().filter(ImportPreview.Line::isValid)).hasSize(1);assertThat(find(input.get(0))).hasSize(1);}
        finally {pool.shutdownNow();}
    }
}
