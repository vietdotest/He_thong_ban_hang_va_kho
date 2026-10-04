package vn.codegym.salesinventory.dao;

import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.config.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;
import static org.assertj.core.api.Assertions.*;

class ScopeAcceptanceIT extends StoryDatabaseSupport {
    ScopeService scopes() { return new ScopeService(source); }
    String code() { return "S-"+UUID.randomUUID(); }
    long save(String kind) { return scopes().save(1,kind,0,code(),"Phạm vi thử","12 đường thử, phường thử, tỉnh thử",0); }
    @Test void savesBothKindsWithAddressAndAudit() {
        for(String kind:List.of("warehouse","territory")) {
            long id=scopes().save(1,kind,0,code()," Tên thử "," Địa chỉ cụ thể ",0);
            assertThat(scopes().find(1,kind,id)).containsEntry("name","Tên thử").containsEntry("address","Địa chỉ cụ thể");
            assertThat(scopes().list(1,kind)).anyMatch(r->Sql.id(r.get("id"))==id);
            assertThat(one("SELECT after_values FROM audit_logs WHERE event_type='SCOPE_CREATED' AND object_type=? AND object_id=?",kind.equals("warehouse")?"WAREHOUSE":"TERRITORY",id).get("after_values").toString()).contains("Địa chỉ cụ thể");
        }
    }
    @Test void editingKeepsAssignmentsAndRejectsStaleVersion() {
        long warehouse=save("warehouse"),territory=save("territory"),staff=user("SALES");
        var assignments=new AssignmentService(source);
        assertThatThrownBy(()->assignments.assign(1,staff,Set.of("WAREHOUSE","SALES"),Set.of(),Set.of(territory))).hasMessageContaining("ít nhất một kho");
        assignments.assign(1,staff,Set.of("WAREHOUSE","SALES"),Set.of(warehouse),Set.of(territory));
        var before=scopes().find(1,"warehouse",warehouse);
        scopes().save(1,"warehouse",warehouse,Sql.text(before.get("code")),"Kho đã sửa","Địa chỉ mới",1);
        var access=new AccessService(source).load(staff);
        assertThat(access.roles()).containsExactlyInAnyOrder("WAREHOUSE","SALES");
        assertThat(Sql.id(access.warehouses().get(0).get("id"))).isEqualTo(warehouse);
        assertThat(access.warehouses().get(0)).containsEntry("address","Địa chỉ mới");
        assertThat(Sql.id(access.territories().get(0).get("id"))).isEqualTo(territory);
        assertThat(access.territories().get(0)).containsEntry("address","12 đường thử, phường thử, tỉnh thử");
        assertThatThrownBy(()->scopes().save(1,"warehouse",warehouse,Sql.text(before.get("code")),"Ghi đè","Sai",1)).hasMessageContaining("đã thay đổi");
        assertThatThrownBy(()->assignments.assign(1,1,Set.of("SALES"),Set.of(),Set.of())).hasMessageContaining("tự thu hồi");
    }
    @Test void invalidAddressDuplicateAndForgedKindDoNotWrite() {
        long id=save("territory");String code=Sql.text(scopes().find(1,"territory",id).get("code"));
        assertThatThrownBy(()->scopes().save(1,"territory",0,code,"Tên","Địa chỉ",0)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(()->scopes().save(1,"warehouse",0,code(),"Tên"," ",0)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(()->scopes().save(1,"warehouse",0,code(),"Tên","x".repeat(501),0)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(()->scopes().save(1,"users",0,code(),"Tên","Địa chỉ",0)).isInstanceOf(FieldValidationException.class);
    }
    @Test void onlyUserManagerMayReadOrEditScopeDirectory() {
        long id=save("warehouse");
        for(String role:List.of("SALES_MANAGER","SALES","WAREHOUSE_MANAGER","WAREHOUSE","ACCOUNTANT","DIRECTOR")) {
            long actor=user(role);
            assertThatThrownBy(()->scopes().list(actor,"warehouse")).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->scopes().find(actor,"warehouse",id)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->scopes().save(actor,"warehouse",id,code(),"Tên","Địa chỉ",1)).isInstanceOf(SecurityException.class);
        }
    }
    @Test void failedAuditRollsBackAddressAndNewScope() {
        long id=save("warehouse");var before=scopes().find(1,"warehouse",id);failAudit();
        assertThatThrownBy(()->scopes().save(1,"warehouse",id,Sql.text(before.get("code")),"Sửa","Địa chỉ khác",1)).isInstanceOf(IllegalStateException.class);
        assertThat(scopes().find(1,"warehouse",id)).isEqualTo(before);
        String code=code();assertThatThrownBy(()->scopes().save(1,"territory",0,code,"Thêm","Địa chỉ",0)).isInstanceOf(IllegalStateException.class);
        assertThat(query("SELECT id FROM territories WHERE code=?",code)).isEmpty();
    }
    @Test void concurrentEditsCannotOverwriteEachOther() throws Exception {
        long id=save("territory");String code=Sql.text(scopes().find(1,"territory",id).get("code"));var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> edit=()->{gate.await();try{scopes().save(1,"territory",id,code,"Sửa","Địa chỉ mới",1);return true;}catch(FieldValidationException stale){return false;}};
            var a=pool.submit(edit);var b=pool.submit(edit);gate.countDown();assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally {pool.shutdownNow();}
    }
    @Test void migrationPreservesOldDirectoryAndAssignments() throws Exception {
        String database="scope_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try(var root=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var statement=root.createStatement()){statement.execute("CREATE DATABASE "+database);}
        String url=MYSQL.getJdbcUrl().replace("/story_acceptance","/"+database);
        try(var upgraded=DatabaseFactory.create(new AppConfig.DatabaseSettings(url,"root",MYSQL.getPassword(),2,1,10000))) {
            Flyway.configure().dataSource(upgraded).target("17").load().migrate();
            Sql.transaction(upgraded,c->{Sql.update(c,"INSERT INTO warehouses(id,code,name) VALUES(40,'OLD-W','Kho cũ')");Sql.update(c,"INSERT INTO territories(id,code,name) VALUES(50,'OLD-T','Địa bàn cũ')");Sql.update(c,"INSERT INTO user_warehouses VALUES(1,40)");Sql.update(c,"INSERT INTO user_territories VALUES(1,50)");return null;});
            Flyway.configure().dataSource(upgraded).load().migrate();
            var access=new AccessService(upgraded).load(1);
            assertThat(Sql.id(access.warehouses().get(0).get("id"))).isEqualTo(40);assertThat(access.warehouses().get(0)).containsEntry("address",null);
            assertThat(Sql.id(access.territories().get(0).get("id"))).isEqualTo(50);assertThat(access.territories().get(0)).containsEntry("address",null);
            assertThat(new ScopeService(upgraded).find(1,"warehouse",40)).containsEntry("name","Kho cũ").containsEntry("version",1L);
        }
    }
}
