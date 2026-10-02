package vn.codegym.salesinventory.dao;

import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.mysql.MySQLContainer;
import vn.codegym.salesinventory.config.*;
import vn.codegym.salesinventory.service.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class StoryDatabaseSupport {
    // One disposable MySQL per test JVM; Ryuk cleans it up when the JVM exits.
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("story_acceptance").withUsername("test").withPassword("test")
            .withCommand("--log-bin-trust-function-creators=1");
    static { MYSQL.start(); }
    HikariDataSource source;
    @BeforeAll void database() {
        source=DatabaseFactory.create(new AppConfig.DatabaseSettings(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword(),10,1,10000));
        Flyway.configure().dataSource(source).load().migrate();
    }
    @AfterAll void closeDatabase() { if(source!=null)source.close(); }
    @AfterEach void removeFailureTrigger() { update("DROP TRIGGER IF EXISTS story_audit_fail"); }
    List<Map<String,Object>> query(String sql,Object... args) { return Sql.transaction(source,c->Sql.query(c,sql,args)); }
    Map<String,Object> one(String sql,Object... args) { return Sql.transaction(source,c->Sql.one(c,sql,args)); }
    long insert(String sql,Object... args) { return Sql.transaction(source,c->Sql.insert(c,sql,args)); }
    void update(String sql,Object... args) { Sql.transaction(source,c->{Sql.update(c,sql,args);return null;}); }
    long user(String... roles) {
        String name="story-"+UUID.randomUUID();
        long id=insert("INSERT INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status) SELECT ?,?,?,?,'Người thử',password_hash,'ACTIVE' FROM users WHERE id=1",name,name,name+"@test.local",name+"@test.local");
        for(String role:roles)update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",id,role);
        return id;
    }
    long warehouse(long actor) {
        long id=insert("INSERT INTO warehouses(code,name) VALUES(?,'Kho thử')","W-"+UUID.randomUUID());
        update("INSERT INTO user_warehouses(user_id,warehouse_id) VALUES(?,?)",actor,id);return id;
    }
    long category(long manager) { return new CategoryService(source).save(manager,0,"C-"+UUID.randomUUID(),"Nhóm thử",null,0); }
    long product(long manager) {
        return new ProductService(source).save(manager,0,new ProductService.Input("P-"+UUID.randomUUID(),"Sản phẩm thử",category(manager),"Lon","",BigDecimal.ONE,null,"ACTIVE",0));
    }
    void failAudit() { update("CREATE TRIGGER story_audit_fail BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit rejected'"); }
    long count(String sql,Object... args) { return Sql.id(one(sql,args).get("n")); }
}
