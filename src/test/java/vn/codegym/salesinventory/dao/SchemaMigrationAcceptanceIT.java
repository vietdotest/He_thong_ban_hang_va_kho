package vn.codegym.salesinventory.dao;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.config.*;
import static org.assertj.core.api.Assertions.*;

class SchemaMigrationAcceptanceIT extends StoryDatabaseSupport {
    @Test void freshAndV018UpgradeUsePrivilegedRunnerAndDmlOnlyRuntime()throws Exception{
        for(boolean upgrade:new boolean[]{false,true}){
            String suffix=UUID.randomUUID().toString().replace("-","").substring(0,16),database="release_"+suffix,username="runtime_"+suffix;
            String url=MYSQL.getJdbcUrl().replace("/story_acceptance","/"+database);
            try(var root=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var sql=root.createStatement()){
                sql.execute("CREATE DATABASE "+database);sql.execute("CREATE USER '"+username+"'@'%' IDENTIFIED BY 'qa-runtime-only'");sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+database+".* TO '"+username+"'@'%'");
                // Disposable Testcontainers only: prove trigger migrations do not need runtime SUPER.
                sql.execute("SET GLOBAL log_bin_trust_function_creators=0");
                var privileged=new AppConfig.DatabaseSettings(url,"root",MYSQL.getPassword(),2,1,10000);
                var runtime=new AppConfig.DatabaseSettings(url,username,"qa-runtime-only",2,1,10000);
                try{
                    if(upgrade)try(var migrations=DatabaseFactory.createMigrationSource(privileged)){
                        Flyway.configure().dataSource(migrations).target("18").load().migrate();
                        long previousChecksum=Sql.transaction(migrations,c->Sql.id(Sql.one(c,"SELECT checksum FROM flyway_schema_history WHERE CAST(version AS UNSIGNED)=16").get("checksum")));assertThat(previousChecksum).isEqualTo(1601);
                        Sql.transaction(migrations,c->{Sql.update(c,"INSERT INTO warehouses(id,code,name,address) VALUES(40,'PRESERVED','Kho trước Sprint 3','Địa chỉ còn nguyên')");Sql.update(c,"INSERT INTO user_warehouses(user_id,warehouse_id) VALUES(1,40)");return null;});
                    }
                    assertThatThrownBy(()->SchemaMigration.initialize(runtime,new AppConfig.MigrationSettings(false,privileged),"classpath:db/migration")).isInstanceOf(RuntimeException.class);
                    SchemaMigration.initialize(runtime,new AppConfig.MigrationSettings(true,privileged),"classpath:db/migration");
                    SchemaMigration.initialize(runtime,new AppConfig.MigrationSettings(false,privileged),"classpath:db/migration");
                    try(var web=DatabaseFactory.create(runtime)){
                        var grants=Sql.transaction(web,c->Sql.query(c,"SHOW GRANTS"));assertThat(grants.toString()).doesNotContain("SUPER","CREATE","ALTER","DROP","TRIGGER");
                        long applied=Sql.transaction(web,c->Sql.id(Sql.one(c,"SELECT COUNT(*) n FROM flyway_schema_history WHERE success=1 AND CAST(version AS UNSIGNED)=28").get("n")));assertThat(applied).isEqualTo(1);
                        long preservedChecksum=Sql.transaction(web,c->Sql.id(Sql.one(c,"SELECT checksum FROM flyway_schema_history WHERE CAST(version AS UNSIGNED)=16").get("checksum")));assertThat(preservedChecksum).isEqualTo(1601);
                        var jobs=Sql.transaction(web,c->Sql.query(c,"SELECT id FROM import_jobs"));assertThat(jobs).isEmpty();
                        assertThatThrownBy(()->Sql.transaction(web,c->{Sql.update(c,"CREATE TABLE forbidden_runtime_ddl(id BIGINT)");return null;})).isInstanceOf(IllegalStateException.class);
                        if(upgrade){var preserved=Sql.transaction(web,c->Sql.one(c,"SELECT name,address FROM warehouses WHERE id=40"));assertThat(preserved).containsEntry("name","Kho trước Sprint 3").containsEntry("address","Địa chỉ còn nguyên");long links=Sql.transaction(web,c->Sql.id(Sql.one(c,"SELECT COUNT(*) n FROM user_warehouses WHERE user_id=1 AND warehouse_id=40").get("n")));assertThat(links).isEqualTo(1);}
                    }
                }finally{sql.execute("SET GLOBAL log_bin_trust_function_creators=1");}
            }
        }
    }
}
