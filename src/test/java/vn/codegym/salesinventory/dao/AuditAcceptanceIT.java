package vn.codegym.salesinventory.dao;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.security.Access;
import static org.assertj.core.api.Assertions.*;

class AuditAcceptanceIT extends StoryDatabaseSupport {
    @Test void actorBeforeAfterAndTimeAreRecordedWithCostIsolated() {
        long actor=user("ADMIN","SALES_MANAGER");
        long id=Sql.transaction(source,c->{AuditService.record(c,actor,"TEST_UPDATED","TEST",42,Map.of("name","Cũ","password","secret","cost_price",BigDecimal.ONE),Map.of("name","<script>","token","secret","cost_price",BigDecimal.TEN));return Sql.id(Sql.one(c,"SELECT MAX(id) n FROM audit_logs WHERE actor_user_id=?",actor).get("n"));});
        var row=one("SELECT * FROM audit_logs WHERE id=?",id);assertThat(Sql.id(row.get("actor_user_id"))).isEqualTo(actor);
        assertThat(row.get("before_values").toString()).contains("Cũ").doesNotContain("secret","cost_price");assertThat(row.get("after_values").toString()).contains("<script>").doesNotContain("secret","cost_price");
        assertThat(row.get("after_cost").toString()).contains("10");assertThat(Sql.instant(row.get("occurred_at"))).isCloseTo(Instant.now(),within(5,java.time.temporal.ChronoUnit.SECONDS));
        var admin=new AccessService(source).load(user("ADMIN"));var manager=new AccessService(source).load(actor);
        var hidden=Sql.transaction(source,c->AuditService.read(c,admin," WHERE a.id=?",new Object[]{id},0));assertThat(hidden.get(0)).doesNotContainKeys("before_cost","after_cost");
        assertThat(Sql.transaction(source,c->AuditService.read(c,manager," WHERE a.id=?",new Object[]{id},0)).get(0)).containsKeys("before_cost","after_cost");
    }
    @Test void stablePaginationAndCombinedBoundFiltersRespectVietnamDay() {
        long actor=user("ADMIN");var access=new AccessService(source).load(actor);
        Sql.transaction(source,c->{for(int i=0;i<105;i++)Sql.insert(c,"INSERT INTO audit_logs(actor_user_id,event_type,object_type,object_id,occurred_at) VALUES(?,'PAGE','TEST',?,?)",actor,i,Timestamp.from(Instant.parse("2026-10-01T17:00:00Z")));return null;});
        String filter=" WHERE a.actor_user_id=? AND a.object_type=? AND a.occurred_at>=? AND a.occurred_at<?";Object[] args={actor,"TEST",Timestamp.from(Instant.parse("2026-10-01T17:00:00Z")),Timestamp.from(Instant.parse("2026-10-02T17:00:00Z"))};
        var first=Sql.transaction(source,c->AuditService.read(c,access,filter,args,0));var second=Sql.transaction(source,c->AuditService.read(c,access,filter,args,100));
        assertThat(first).hasSize(100);assertThat(second).hasSize(5);assertThat(first.get(0).get("display_time")).isEqualTo("02/10/2026 00:00:00");
        assertThat(first.stream().map(r->r.get("id")).toList()).doesNotContainAnyElementsOf(second.stream().map(r->r.get("id")).toList());
    }
    @Test void missingReadPermissionCannotAccessAnyAuditColumns() {
        for(String role:new String[]{"SALES","WAREHOUSE","WAREHOUSE_MANAGER","ACCOUNTANT","DIRECTOR"}) {
            Access access=new AccessService(source).load(user(role));assertThatThrownBy(()->Sql.transaction(source,c->AuditService.read(c,access,"",new Object[0],0))).isInstanceOf(SecurityException.class);
        }
    }
    @Test void auditFailureRollsBackDataAndSuccessfulAuditIsNotCreated() {
        long actor=user("SALES_MANAGER");long product=product(actor);var before=one("SELECT * FROM products WHERE id=?",product);long audits=count("SELECT COUNT(*) n FROM audit_logs WHERE object_type='PRODUCT' AND object_id=?",product);failAudit();
        assertThatThrownBy(()->new ProductService(source).save(actor,product,new ProductService.Input(Sql.text(before.get("sku")),"Đã đổi",Sql.id(before.get("category_id")),"Lon","",null,null,"ACTIVE",Sql.id(before.get("version"))))).isInstanceOf(IllegalStateException.class);
        assertThat(one("SELECT name,version FROM products WHERE id=?",product)).containsEntry("name",before.get("name")).containsEntry("version",before.get("version"));assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE object_type='PRODUCT' AND object_id=?",product)).isEqualTo(audits);
    }
}
