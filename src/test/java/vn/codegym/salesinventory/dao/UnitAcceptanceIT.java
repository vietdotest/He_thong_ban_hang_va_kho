package vn.codegym.salesinventory.dao;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.config.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;
import static org.assertj.core.api.Assertions.*;

class UnitAcceptanceIT extends StoryDatabaseSupport {
    long manager,staff,first,second,product;UnitService units;
    @BeforeEach void fixture(){manager=user("SALES_MANAGER");staff=user("WAREHOUSE");first=warehouse(staff);second=warehouse(staff);product=product(manager);units=new UnitService(source);}
    long save(long warehouse,String factor){return units.save(staff,0,product,"Thùng",new BigDecimal(factor),warehouse,0);}
    @Test void sameNameAcrossWarehousesHasIndependentFactorsButSameWarehouseDuplicateFails() {
        long a=save(first,"24"),b=save(second,"30");assertThat(units.convert(staff,a,BigDecimal.ONE).baseQuantity()).isEqualByComparingTo("24");assertThat(units.convert(staff,b,BigDecimal.ONE).baseQuantity()).isEqualByComparingTo("30");
        assertThatThrownBy(()->units.save(staff,0,product," thùng ",BigDecimal.ONE,first,0)).isInstanceOf(FieldValidationException.class);
        assertThat(units.list(staff,product)).hasSize(3);
    }
    @Test void snapshotsIncludeWarehouseAndNeverChangeAfterFactorUpdate() {
        long unit=save(first,"24");var access=new AccessService(source).load(staff);
        long snapshot=Sql.transaction(source,c->UnitService.snapshot(c,"SNAP-"+UUID.randomUUID(),UnitService.convert(c,access,unit,new BigDecimal("2.125001"))));
        var before=one("SELECT * FROM conversion_snapshots WHERE id=?",snapshot);assertThat(Sql.id(before.get("warehouse_id"))).isEqualTo(first);
        units.save(staff,unit,product,"Thùng",new BigDecimal("30"),second,1);
        assertThat(one("SELECT * FROM conversion_snapshots WHERE id=?",snapshot)).isEqualTo(before);
        assertThat(units.convert(staff,unit,BigDecimal.ONE).warehouseId()).isEqualTo(second);assertThatThrownBy(()->units.delete(staff,unit)).isInstanceOf(FieldValidationException.class);
    }
    @Test void baseUnitIsUniqueSharedAndCannotBeChangedOrDeleted() {
        long base=Sql.id(one("SELECT id FROM product_units WHERE product_id=? AND is_base=1",product).get("id"));assertThat(units.convert(staff,base,BigDecimal.ONE).warehouseId()).isNull();
        assertThatThrownBy(()->units.save(staff,base,product,"Khác",BigDecimal.TEN,first,1)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(()->units.delete(staff,base)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(()->insert("INSERT INTO product_units(product_id,name,factor,is_base) VALUES(?,'Khác',1,1)",product)).isInstanceOf(IllegalStateException.class);
    }
    @Test void unauthorizedWarehouseAndForgedProductAreRejected() {
        long unit=save(first,"24"),other=user("WAREHOUSE"),own=warehouse(other),different=product(manager);
        assertThat(units.list(other,product)).hasSize(1);assertThatThrownBy(()->units.convert(other,unit,BigDecimal.ONE)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->units.save(other,unit,product,"Thùng",BigDecimal.TEN,own,1)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->units.save(staff,unit,different,"Thùng",BigDecimal.TEN,first,1)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(()->units.delete(staff,unit,different)).isInstanceOf(FieldValidationException.class);
    }
    @Test void decimalPrecisionAndStaleVersionAreEnforced() {
        long unit=save(first,"1.000001");var result=units.convert(staff,unit,new BigDecimal("2.000003"));assertThat(result.baseQuantity()).isEqualByComparingTo("2.000005000003");
        units.save(staff,unit,product,"Thùng",new BigDecimal("2"),first,1);assertThatThrownBy(()->units.save(staff,unit,product,"Thùng",BigDecimal.TEN,first,1)).hasMessageContaining("thay đổi");
        assertThatThrownBy(()->units.convert(staff,unit,new BigDecimal("0.0000001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->Sql.transaction(source,c->UnitService.convert(c,new AccessService(source).load(staff),unit,BigDecimal.ONE.negate()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void concurrentDuplicateCreatesHaveOneWinner()throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();try{save(first,"24");return true;}catch(FieldValidationException duplicate){return false;}});var b=pool.submit(()->{gate.await();try{save(first,"30");return true;}catch(FieldValidationException duplicate){return false;}});gate.countDown();assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}finally{pool.shutdownNow();}
    }
    @Test void auditFailureRollsBackCreateUpdateAndDelete() {
        long unit=save(first,"24");failAudit();assertThatThrownBy(()->units.save(staff,unit,product,"Thùng",BigDecimal.TEN,first,1)).isInstanceOf(IllegalStateException.class);
        assertThat((BigDecimal)one("SELECT factor FROM product_units WHERE id=?",unit).get("factor")).isEqualByComparingTo("24");assertThatThrownBy(()->units.delete(staff,unit)).isInstanceOf(IllegalStateException.class);
        assertThat(query("SELECT id FROM product_units WHERE id=?",unit)).hasSize(1);assertThatThrownBy(()->units.save(staff,0,product,"Lốc",BigDecimal.TEN,first,0)).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT COUNT(*) n FROM product_units WHERE product_id=?",product)).isEqualTo(2);
    }
    @Test void migrationPreservesOldSnapshotWithoutInventingWarehouse()throws Exception {
        String database="unit_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try(var root=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var statement=root.createStatement()){statement.execute("CREATE DATABASE "+database);}
        String url=MYSQL.getJdbcUrl().replace("/story_acceptance","/"+database);
        try(var upgraded=DatabaseFactory.create(new AppConfig.DatabaseSettings(url,"root",MYSQL.getPassword(),2,1,10000))) {
            Flyway.configure().dataSource(upgraded).target("16").load().migrate();
            Sql.transaction(upgraded,c->{long category=Sql.insert(c,"INSERT INTO categories(code,name) VALUES('OLD','Nhóm cũ')");long product=Sql.insert(c,"INSERT INTO products(sku,name,category_id,base_unit,status) VALUES('OLD','Hàng cũ',?,'Lon','ACTIVE')",category);long warehouse=Sql.insert(c,"INSERT INTO warehouses(code,name) VALUES('OLD','Kho cũ')");long unit=Sql.insert(c,"INSERT INTO product_units(product_id,name,factor,warehouse_id) VALUES(?,'Thùng',24,?)",product,warehouse);Sql.insert(c,"INSERT INTO conversion_snapshots(reference_id,unit_id,product_id,unit_name,factor,unit_version,quantity,base_quantity) VALUES('OLD',?,?,'Thùng',24,1,2,48)",unit,product);return null;});
            var before=Sql.transaction(upgraded,c->Sql.one(c,"SELECT * FROM conversion_snapshots WHERE reference_id='OLD'"));Flyway.configure().dataSource(upgraded).load().migrate();
            var after=Sql.transaction(upgraded,c->Sql.one(c,"SELECT * FROM conversion_snapshots WHERE reference_id='OLD'"));assertThat(after.get("warehouse_id")).isNull();after.remove("warehouse_id");assertThat(after).isEqualTo(before);
            Sql.transaction(upgraded,c->{long product=Sql.id(Sql.one(c,"SELECT id FROM products WHERE sku='OLD'").get("id"));long warehouse=Sql.insert(c,"INSERT INTO warehouses(code,name) VALUES('NEW','Kho mới')");Sql.insert(c,"INSERT INTO product_units(product_id,name,factor,warehouse_id) VALUES(?,'Thùng',30,?)",product,warehouse);return null;});
        }
    }
}
