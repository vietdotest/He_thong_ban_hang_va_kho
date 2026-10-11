package vn.codegym.salesinventory.dao;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;
import static org.assertj.core.api.Assertions.*;

class DealerProfileAcceptanceIT extends StoryDatabaseSupport {
    long manager,sales,territory,group;DealerService service;
    @BeforeEach void fixture(){manager=user("SALES_MANAGER");sales=user("SALES");territory=insert("INSERT INTO territories(code,name) VALUES(?,'Hà Nội')","T-"+UUID.randomUUID());group=Sql.id(one("SELECT id FROM customer_groups LIMIT 1").get("id"));service=new DealerService(source);}
    DealerService.Input input(String code,long version){return new DealerService.Input(code,"Đại lý Cà phê","0101234567","0912345678",group,territory,sales,null,"ACTIVE",version);}
    long dealer(){return service.save(manager,0,input("DL-"+UUID.randomUUID(),0));}
    @Test void createsProfileUniqueCodeAndReferencesCorrectPriceGroup(){
        String code="DL-"+UUID.randomUUID();long id=service.save(manager,0,input(code,0));
        assertThat(service.find(manager,id)).containsEntry("code",code).containsEntry("name","Đại lý Cà phê");
        assertThat(Sql.id(service.find(manager,id).get("group_id"))).isEqualTo(group);
        assertThat(query("SELECT * FROM dealer_staff_references WHERE dealer_reference=?","DEALER:"+id)).hasSize(1);
        assertThatThrownBy(()->service.save(manager,0,input(code.toLowerCase(Locale.ROOT),0))).isInstanceOfSatisfying(FieldValidationException.class,e->assertThat(e.errors()).containsKey("code"));
    }
    @Test void roleUnionAndAssignedScopeEnforcedForCountsDetailsAndWrites(){
        long id=dealer(),other=user("SALES"),admin=user("ADMIN"),accountant=user("ACCOUNTANT"),director=user("DIRECTOR"),multi=user("ADMIN","SALES");
        assertThat(service.find(sales,id)).containsEntry("name","Đại lý Cà phê");
        assertThat(service.search(other,"",new PageRequest(1,20)).totalItems()).isZero();
        assertThatThrownBy(()->service.find(other,id)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.find(admin,id)).isInstanceOf(SecurityException.class);
        assertThat(service.search(multi,"",new PageRequest(1,20)).totalItems()).isZero();
        assertThat(service.find(director,id)).containsKey("group_name");
        assertThatThrownBy(()->service.save(director,id,input(Sql.text(service.find(manager,id).get("code")),1))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.save(sales,id,input("BAD",1))).isInstanceOf(SecurityException.class);
        service.save(accountant,id,input(Sql.text(service.find(manager,id).get("code")),1));
        update("UPDATE users SET status='ADMIN_LOCKED' WHERE id=?",sales);
        assertThatThrownBy(()->service.find(sales,id)).isInstanceOf(SecurityException.class);
    }
    @Test void staleVersionInvalidStaffAndAuditFailureLeaveProfileUnchanged(){
        long id=dealer();String code=Sql.text(service.find(manager,id).get("code"));
        assertThatThrownBy(()->service.save(manager,id,input(code,0))).isInstanceOf(FieldValidationException.class);
        var bad=new DealerService.Input("NEW","Tên","","0912345678",group,territory,user("ADMIN"),null,"ACTIVE",0);
        assertThatThrownBy(()->service.save(manager,0,bad)).isInstanceOfSatisfying(FieldValidationException.class,e->assertThat(e.errors()).containsKey("staff"));
        failAudit();assertThatThrownBy(()->service.save(manager,id,input(code,1))).isInstanceOf(IllegalStateException.class);
        assertThat(Sql.id(service.find(manager,id).get("version"))).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE object_type='DEALER' AND object_id=?",id)).isEqualTo(1);
    }
    @Test void referencedDealerCanBeDiscontinuedButNotHardDeleted(){
        long id=dealer();update("INSERT INTO dealer_transaction_references VALUES(?,'ORDER','TEST')",id);
        assertThatThrownBy(()->service.delete(manager,id,1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("giao dịch");
        var in=input(Sql.text(service.find(manager,id).get("code")),1);
        service.save(manager,id,new DealerService.Input(in.code(),in.name(),in.taxCode(),in.phone(),group,territory,sales,null,"DISCONTINUED",1));
        assertThat(service.find(manager,id)).containsEntry("status","DISCONTINUED");
        long unused=dealer();service.delete(manager,unused,1);assertThat(query("SELECT id FROM dealers WHERE id=?",unused)).isEmpty();
    }
    @Test void twoEditorsHaveExactlyOneWinnerAndAudit(){
        long id=dealer();String code=Sql.text(service.find(manager,id).get("code"));var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{List<Future<Boolean>> tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();try{service.save(manager,id,input(code,1));return true;}catch(FieldValidationException e){return false;}}));start.countDown();int wins=0;for(var task:tasks)if(task.get(30,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE object_type='DEALER' AND object_id=?",id)).isEqualTo(2);}
        catch(Exception e){throw new AssertionError(e);}finally{pool.shutdownNow();}
    }
}
