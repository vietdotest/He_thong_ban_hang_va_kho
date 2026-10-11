package vn.codegym.salesinventory.dao;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;
import static org.assertj.core.api.Assertions.*;

class CategoryAcceptanceIT extends StoryDatabaseSupport {
    long manager;CategoryService categories;
    @BeforeEach void fixture(){manager=user("SALES_MANAGER");categories=new CategoryService(source);}
    long save(Long parent){return categories.save(manager,0,"C-"+UUID.randomUUID(),"Nhóm thử",parent,0);}
    Map<String,Object> row(long id){return one("SELECT * FROM categories WHERE id=?",id);}
    @Test void filteredTreeKeepsAncestorsAndDescendantsWithoutUnrelatedSiblings() {
        long root=categories.save(manager,0,"ROOT-"+UUID.randomUUID(),"Nhóm gốc",null,0);
        long match=categories.save(manager,0,"MATCH-"+UUID.randomUUID(),"Đồ uống",root,0),leaf=save(match),sibling=save(root);
        var branch=categories.tree(manager,"do uong");
        assertThat(branch.stream().map(r->Sql.id(r.get("id"))).toList()).containsExactly(root,match,leaf).doesNotContain(sibling);
        assertThat(branch.get(2)).containsEntry("depth",2);assertThat(categories.tree(manager,"khong-ton-tai-"+UUID.randomUUID())).isEmpty();
        assertThat(categories.tree(manager,Sql.text(row(leaf).get("code"))).stream().map(r->Sql.id(r.get("id"))).toList()).containsExactly(root,match,leaf);
    }
    @Test void lockedActorCannotSearchOrWriteTree() {
        update("UPDATE users SET status='ADMIN_LOCKED' WHERE id=?",manager);
        assertThatThrownBy(()->categories.tree(manager,"")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->categories.save(manager,0,"NEW","Nhóm mới",null,0)).isInstanceOf(SecurityException.class);
    }
    @Test void treeHasThreeLevelsAndCanMoveWholeBranch() {
        long root=save(null),child=save(root),leaf=save(child),other=save(null);
        assertThat(categories.tree().stream().filter(r->Sql.id(r.get("id"))==leaf).findFirst().orElseThrow()).containsEntry("depth",2);
        categories.save(manager,child,Sql.text(row(child).get("code")),"Đã chuyển",other,1);
        assertThat(Sql.id(row(child).get("parent_id"))).isEqualTo(other);assertThat(Sql.id(row(leaf).get("parent_id"))).isEqualTo(child);
    }
    @Test void productsCanMoveButOccupiedGroupsCannotBeDeleted() {
        long product=product(manager);var p=one("SELECT * FROM products WHERE id=?",product);long old=Sql.id(p.get("category_id")),next=save(null);
        assertThatThrownBy(()->categories.delete(manager,old)).isInstanceOf(FieldValidationException.class);
        new ProductService(source).save(manager,product,new ProductService.Input(Sql.text(p.get("sku")),Sql.text(p.get("name")),next,"Lon","",null,null,"ACTIVE",1));
        categories.delete(manager,old);assertThat(query("SELECT id FROM categories WHERE id=?",old)).isEmpty();
        assertThatThrownBy(()->categories.delete(manager,next)).isInstanceOf(FieldValidationException.class);
        long parent=save(null);save(parent);assertThatThrownBy(()->categories.delete(manager,parent)).isInstanceOf(FieldValidationException.class);
    }
    @Test void rejectsSelfParentMultilevelCycleMissingParentAndStaleVersion() {
        long root=save(null),child=save(root),leaf=save(child);String code=Sql.text(row(root).get("code"));
        for(long parent:new long[]{root,leaf,Long.MAX_VALUE})assertThatThrownBy(()->categories.save(manager,root,code,"Tên mới",parent,1)).isInstanceOf(FieldValidationException.class);
        categories.save(manager,root,code,"Tên mới",null,1);assertThatThrownBy(()->categories.save(manager,root,code,"Tên cũ",null,1)).hasMessageContaining("thay đổi");
    }
    @Test void duplicateCodeAndWhitespaceAreValidatedAndNormalized() {
        String code="C-"+UUID.randomUUID();long id=categories.save(manager,0," "+code+" "," Nhóm O'An ",null,0);
        assertThat(row(id)).containsEntry("code",code).containsEntry("name","Nhóm O'An");
        assertThatThrownBy(()->categories.save(manager,0,code.toLowerCase(Locale.ROOT),"Tên mới",null,0)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(()->categories.save(manager,0,"C-"+UUID.randomUUID(),"   ",null,0)).isInstanceOf(FieldValidationException.class);
    }
    @Test void movingSubtreeCannotExceedExistingThirtyLevelLimit() {
        long branch=save(null);save(save(branch));long deep=save(null);for(int i=0;i<29;i++)deep=save(deep);long target=deep;
        assertThatThrownBy(()->categories.save(manager,branch,Sql.text(row(branch).get("code")),"Nhánh",target,1)).hasMessageContaining("30 cấp");
    }
    @Test void concurrentMovesCannotCreateCycle() throws Exception {
        long a=save(null),b=save(null);String codeA=Sql.text(row(a).get("code")),codeB=Sql.text(row(b).get("code"));var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{gate.await();try{categories.save(manager,a,codeA,"Nhánh A",b,1);return true;}catch(FieldValidationException denied){return false;}});
            var second=pool.submit(()->{gate.await();try{categories.save(manager,b,codeB,"Nhánh B",a,1);return true;}catch(FieldValidationException denied){return false;}});gate.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);assertThat(categories.tree()).isNotEmpty();
        }finally{pool.shutdownNow();}
    }
    @Test void simultaneousUpdatesWithSameVersionHaveOneWinner() throws Exception {
        long id=save(null);String code=Sql.text(row(id).get("code"));var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(()->{gate.await();try{categories.save(manager,id,code,"Tên A",null,1);return true;}catch(FieldValidationException stale){return false;}});var b=pool.submit(()->{gate.await();try{categories.save(manager,id,code,"Tên B",null,1);return true;}catch(FieldValidationException stale){return false;}});gate.countDown();assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}finally{pool.shutdownNow();}
    }
    @Test void auditFailureRollsBackAndNonManagersCannotWrite() {
        long id=save(null);String code=Sql.text(row(id).get("code"));failAudit();assertThatThrownBy(()->categories.save(manager,id,code,"Không lưu",null,1)).isInstanceOf(IllegalStateException.class);assertThat(row(id)).containsEntry("name","Nhóm thử");
        for(String role:new String[]{"ADMIN","SALES","WAREHOUSE","WAREHOUSE_MANAGER","ACCOUNTANT","DIRECTOR"})assertThatThrownBy(()->categories.save(user(role),0,"C","Tên",null,0)).isInstanceOf(SecurityException.class);
    }
}
