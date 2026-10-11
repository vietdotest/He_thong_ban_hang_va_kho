package vn.codegym.salesinventory.dao;

import java.util.*;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;

class AuditScopeAcceptanceIT extends StoryDatabaseSupport {
    long admin,manager,sales,next,dealer,other,address,order,portal;String marker;AuditReadService audits;
    @BeforeEach void fixture(){
        admin=user("ADMIN");manager=user("ADMIN","SALES_MANAGER");sales=user("ADMIN","SALES");next=user("ADMIN","SALES");marker="audit-scope-"+UUID.randomUUID();update("UPDATE users SET full_name=? WHERE id=?",marker,manager);
        long territory=insert("INSERT INTO territories(code,name) VALUES(?,'Địa bàn audit QA')","A-"+UUID.randomUUID()),group=Sql.id(one("SELECT id FROM customer_groups LIMIT 1").get("id"));
        dealer=insert("INSERT INTO dealers(code,name,group_id,territory_id,primary_staff_id) VALUES(?,'Đại lý trong phạm vi',?,?,?)","A-"+UUID.randomUUID(),group,territory,sales);
        other=insert("INSERT INTO dealers(code,name,group_id,territory_id,primary_staff_id) VALUES(?,'Đại lý khác',?,?,?)","A-"+UUID.randomUUID(),group,territory,next);
        address=insert("INSERT INTO dealer_addresses(dealer_id,address,recipient,phone) VALUES(?,'Địa chỉ QA','Người nhận QA','0912345678')",dealer);
        order=insert("INSERT INTO orders(dealer_id,address_id,desired_delivery,created_by,owner_id,creation_key,creation_hash) VALUES(?,?,'2030-01-01',?,?,?,?)",dealer,address,sales,sales,UUID.randomUUID().toString(),"a".repeat(64));
        String username="audit-portal-"+UUID.randomUUID();portal=insert("INSERT INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status,account_kind) SELECT ?,?,?,?,'Portal audit QA',password_hash,'ACTIVE','DEALER' FROM users WHERE id=1",username,username,username+"@qa.local",username+"@qa.local");
        update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='DEALER'",portal);
        for(var item:List.of(new Object[]{"DEALER",dealer},new Object[]{"DEALER",other},new Object[]{"DEALER_ADDRESS",address},new Object[]{"ORDER",order},new Object[]{"DISCOUNT_POLICY",991L},new Object[]{"PORTAL_ACCOUNT",portal},new Object[]{"PRODUCT",992L}))event(item[0].toString(),((Number)item[1]).longValue());
        audits=new AuditReadService(source);
    }
    void event(String type,long id){Sql.transaction(source,c->{AuditService.record(c,manager,"QA_CHANGED",type,id,null,Map.of("name",marker,"private",type,"cost_price",123.4567));return null;});}
    PageResult<Map<String,Object>> search(long actor,String type){return audits.search(actor,type.isEmpty()?"":" WHERE a.object_type=?",type.isEmpty()?new Object[0]:new Object[]{type},marker,new PageRequest(1,20));}
    @Test void administratorAuditReadDoesNotGrantNewBusinessReadsOrCounts(){var page=search(admin,"");assertThat(page.totalItems()).isEqualTo(1);assertThat(page.items().get(0)).containsEntry("object_type","PRODUCT").doesNotContainKeys("before_cost","after_cost");
        for(String type:List.of("DEALER","DEALER_ADDRESS","ORDER","DISCOUNT_POLICY","PORTAL_ACCOUNT"))assertThat(search(admin,type).totalItems()).isZero();
        var types=audits.options(admin,"").get("types").stream().map(row->row.get("object_type")).toList();assertThat(types).doesNotContain("DEALER","DEALER_ADDRESS","ORDER","DISCOUNT_POLICY","PORTAL_ACCOUNT");
    }
    @Test void assignedScopeAndHandoverApplyToSnapshotsCountAndOptions(){assertThat(search(sales,"DEALER").totalItems()).isEqualTo(1);assertThat(search(sales,"ORDER").items()).hasSize(1);assertThat(search(sales,"DEALER_ADDRESS").items()).hasSize(1);
        update("UPDATE dealers SET primary_staff_id=?,version=version+1 WHERE id=?",next,dealer);
        for(String type:List.of("DEALER","ORDER","DEALER_ADDRESS"))assertThat(search(sales,type).totalItems()).isZero();assertThat(search(next,"DEALER").totalItems()).isEqualTo(2);assertThat(search(next,"ORDER").totalItems()).isEqualTo(1);
    }
    @Test void fullScopeRequiresActualAuthorizedRoleAndPermissionUnion(){assertThat(search(manager,"DEALER").totalItems()).isEqualTo(2);assertThat(search(manager,"PORTAL_ACCOUNT").totalItems()).isEqualTo(1);assertThat(search(manager,"ORDER").items().get(0)).containsKeys("after_cost");
        for(String role:List.of("DIRECTOR","ACCOUNTANT")){long actor=user("ADMIN",role);assertThat(search(actor,"DEALER").totalItems()).isEqualTo(2);assertThat(search(actor,"ORDER").totalItems()).isEqualTo(1);assertThat(search(actor,"ORDER").items().get(0)).doesNotContainKeys("after_cost");assertThat(search(actor,"PORTAL_ACCOUNT").totalItems()).isZero();}
        long fake=insert("INSERT INTO roles(code,name) VALUES(?,'Giả quyền toàn bộ')","F-"+UUID.randomUUID());update("INSERT INTO role_permissions(role_id,permission_id) SELECT ?,id FROM permissions WHERE code IN ('DEALER_READ_ALL','ORDER_READ_ALL')",fake);update("INSERT INTO user_roles(user_id,role_id) VALUES(?,?)",sales,fake);assertThat(search(sales,"DEALER").totalItems()).isEqualTo(1);
    }
    @Test void totalAndPagingUseSameScopeAndStableOrdering(){for(int i=0;i<20;i++)event("DEALER",dealer);String filter=" WHERE a.object_type='DEALER' AND a.object_id=?";var first=audits.search(sales,filter,new Object[]{dealer},marker,new PageRequest(1,20));var last=audits.search(sales,filter,new Object[]{dealer},marker,new PageRequest(Integer.MAX_VALUE,20));assertThat(first.totalItems()).isEqualTo(21);assertThat(first.items()).hasSize(20);assertThat(last.page()).isEqualTo(2);assertThat(last.items()).hasSize(1);assertThat(first.items().stream().map(x->x.get("id")).toList()).doesNotContain(last.items().get(0).get("id"));
    }
    @Test void recentAndLegacyAdaptersCannotRevealProtectedBusiness(){update("UPDATE audit_logs SET occurred_at='2040-01-01' WHERE actor_user_id=?",manager);for(var row:audits.recent(admin))assertThat(row.get("object_type")).isNotIn("DEALER","DEALER_ADDRESS","ORDER","DISCOUNT_POLICY","PORTAL_ACCOUNT");
        var legacy=Sql.transaction(source,c->AuditService.read(c,new AccessService(source).load(manager)," WHERE a.actor_user_id=?",new Object[]{manager},0));assertThat(legacy).hasSize(1);assertThat(legacy.get(0)).containsEntry("object_type","PRODUCT");
    }
    @Test void optionsAndSuggestionsCannotNameAnActorFromOnlyForbiddenEvents(){long hidden=user("SALES");String username=Sql.text(one("SELECT username FROM users WHERE id=?",hidden).get("username"));Sql.transaction(source,c->{AuditService.record(c,hidden,"PRIVATE_EVENT","DEALER",other,null,Map.of("name","Secret"));return null;});assertThat(audits.options(admin,Long.toString(hidden)).get("users")).isEmpty();assertThat(audits.suggestActors(admin,username)).isEmpty();assertThat(audits.suggestActors(next,username)).hasSize(1);assertThat(new LookupService(source).search(admin,"auditusers",username)).isEmpty();
    }
    @Test void importAuditIsOwnerScopedAndMissingLegacyMetadataDoesNotBreakQuery(){Sql.transaction(source,c->{AuditService.record(c,manager,"IMPORT_JOB_CONFIRMED","IMPORT_JOB",0,null,Map.of("kind","PRODUCT","job_id",UUID.randomUUID().toString()));return null;});assertThat(search(manager,"IMPORT_JOB").totalItems()).isEqualTo(1);assertThat(search(admin,"IMPORT_JOB").totalItems()).isZero();update("INSERT INTO audit_logs(actor_user_id,event_type,object_type,object_id,after_values,occurred_at) VALUES(?,'IMPORT_JOB_CONFIRMED','IMPORT_JOB',0,JSON_QUOTE('old text without metadata'),NOW())",manager);assertThat(search(manager,"IMPORT_JOB").totalItems()).isEqualTo(1);
    }
    @Test void freshRevocationDeniesListsCountsSuggestionsAndRecent(){update("DELETE FROM user_roles WHERE user_id=?",admin);assertThatThrownBy(()->search(admin,"")).isInstanceOf(SecurityException.class);assertThatThrownBy(()->audits.recent(admin)).isInstanceOf(SecurityException.class);assertThatThrownBy(()->audits.suggestActors(admin,"qa")).isInstanceOf(SecurityException.class);assertThatThrownBy(()->audits.options(admin,"")).isInstanceOf(SecurityException.class);}
}
