package vn.codegym.salesinventory.dao;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import vn.codegym.salesinventory.service.*;

class MvpStockAcceptanceIT extends StoryDatabaseSupport {
    long manager,sales,worker,warehouse,supplier,product,unit,dealer,address;
    final Instant now=Instant.parse("2027-01-01T03:00:00Z");final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    StockService stock;OrderService orders;
    @BeforeEach void setup() {
        manager=user("SALES_MANAGER");sales=user("SALES");worker=user("WAREHOUSE");warehouse=warehouse(worker);
        supplier=insert("INSERT INTO suppliers(code,name,tax_code,contact_name,contact_phone,payment_terms,warehouse_id) VALUES(?,'Nhà cung cấp','','Người giao','0912345678','',?)","S-"+UUID.randomUUID(),warehouse);
        product=product(manager);unit=Sql.id(one("SELECT id FROM product_units WHERE product_id=? AND is_base=1",product).get("id"));
        long group=insert("INSERT INTO customer_groups(code,name) VALUES(?,'Nhóm bán')","G-"+UUID.randomUUID().toString().substring(0,12));
        long territory=insert("INSERT INTO territories(code,name,address) VALUES(?,'Khu vực','Địa chỉ')","T-"+UUID.randomUUID());
        update("INSERT INTO user_territories(user_id,territory_id) VALUES(?,?)",sales,territory);
        dealer=new DealerService(source).save(manager,0,new DealerService.Input("D-"+UUID.randomUUID(),"Đại lý","","0912345678",group,territory,sales,warehouse,"ACTIVE",0));
        address=new DealerAddressService(source).save(manager,dealer,0,new DealerAddressService.Input("12 Đường giao","Người nhận","0912345678","",true,"ACTIVE",0,1));
        update("UPDATE dealers SET credit_limit=1000,debt_days=30 WHERE id=?",dealer);
        var pricing=new PricingService(source);long v=pricing.create(manager,group,"Giá",LocalDate.of(2027,1,1),LocalDate.of(2027,1,31),null);
        pricing.saveItem(manager,v,product,new BigDecimal("100"),new BigDecimal("50"));
        stock=new StockService(source,clock);orders=new OrderService(source,clock);
    }
    String key() { return UUID.randomUUID().toString(); }
    StockService.ReceiptInput input(String qty) { return new StockService.ReceiptInput(warehouse,supplier,"PN-01",LocalDate.of(2027,1,1),List.of(new StockService.ReceiptLine(product,unit,new BigDecimal(qty),"L-01",null))); }
    long receive(String qty) { long r=stock.saveReceipt(worker,0,0,key(),input(qty));return stock.confirmReceipt(worker,r,1,key()); }
    long order(String qty) {
        long id=orders.save(sales,0,new OrderService.Input(dealer,address,LocalDate.of(2027,1,2),0,key(),List.of(new OrderService.LineInput(product,unit,new BigDecimal(qty)))));
        return id;
    }
    void reserve(long id) { var q=orders.quote(sales,id);if(!q.valid()||!orders.submit(sales,id,1,q.token(),key(),false).submitted()) throw new IllegalArgumentException("Không tiếp nhận được đơn."); }
    StockService.StockSnapshot balance() { return Sql.snapshot(source,c->StockService.snapshot(c,warehouse,product,now)); }
    @Test void confirmedReceiptSnapshotsAndNumberRemainImmutableAfterMasterChanges(){
        long id=receive("2");var original=stock.receipt(worker,id);String number=original.get("number").toString();assertThat(number).matches("PN-2027-[0-9]{6}");
        var line=((List<Map<String,Object>>)original.get("lines")).get(0);
        update("UPDATE warehouses SET name='Kho đổi tên',address='Địa chỉ mới' WHERE id=?",warehouse);
        update("UPDATE suppliers SET name='Nhà cung cấp mới' WHERE id=?",supplier);
        update("UPDATE products SET sku=?,name='Sản phẩm đổi tên' WHERE id=?","NEW-"+UUID.randomUUID(),product);
        var actual=stock.receipt(worker,id);assertThat(actual).containsEntry("number",number).containsEntry("warehouse_name",original.get("warehouse_name")).containsEntry("warehouse_address",original.get("warehouse_address")).containsEntry("supplier_name",original.get("supplier_name"));
        var actualLine=((List<Map<String,Object>>)actual.get("lines")).get(0);assertThat(actualLine).containsEntry("sku",line.get("sku")).containsEntry("name",line.get("name"));
    }
    @Test void confirmationRechecksSupplierAndFutureDatesAreRejected(){
        long id=stock.saveReceipt(worker,0,0,key(),input("2"));update("UPDATE suppliers SET status='DISCONTINUED' WHERE id=?",supplier);
        assertThatThrownBy(()->stock.confirmReceipt(worker,id,1,key())).isInstanceOf(IllegalArgumentException.class);assertThat(balance().onHand()).isEqualByComparingTo("0");
        long other=warehouse(user("WAREHOUSE"));update("UPDATE suppliers SET status='ACTIVE',warehouse_id=? WHERE id=?",other,supplier);
        assertThatThrownBy(()->stock.confirmReceipt(worker,id,1,key())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->stock.saveReceipt(worker,0,0,key(),new StockService.ReceiptInput(warehouse,supplier,"PN",LocalDate.of(2027,1,2),input("1").lines()))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tương lai");
    }
    @Test void receiptNumberRollsBackWithAuditAndReplayDoesNotConsumeAnotherNumber(){
        long id=stock.saveReceipt(worker,0,0,key(),input("2"));String k=key();var before=Sql.snapshot(source,c->Sql.query(c,"SELECT next_value FROM mvp_sequences WHERE kind='PN' AND document_year=2027"));
        update("CREATE TRIGGER fail_receipt_number BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit failed'");
        try{assertThatThrownBy(()->stock.confirmReceipt(worker,id,1,k)).isInstanceOf(IllegalStateException.class);}finally{update("DROP TRIGGER fail_receipt_number");}
        var rolledBack=Sql.snapshot(source,c->Sql.query(c,"SELECT next_value FROM mvp_sequences WHERE kind='PN' AND document_year=2027"));assertThat(rolledBack).isEqualTo(before);assertThat(stock.receipt(worker,id).get("number")).isNull();
        stock.confirmReceipt(worker,id,1,k);var after=one("SELECT next_value FROM mvp_sequences WHERE kind='PN' AND document_year=2027");stock.confirmReceipt(worker,id,1,k);
        assertThat(one("SELECT next_value FROM mvp_sequences WHERE kind='PN' AND document_year=2027")).isEqualTo(after);assertThat(balance().onHand()).isEqualByComparingTo("2");
    }
    @Test void stockCardTracksRunningSkuBalanceAndSourceReceiptIdsWithoutRequiringWritePrivilege(){long first=receive("10"),second=receive("10");var card=stock.card(worker,warehouse,product);assertThat(card).hasSize(2);assertThat((BigDecimal)card.get(0).get("warehouse_balance")).isEqualByComparingTo("10");assertThat((BigDecimal)card.get(1).get("warehouse_balance")).isEqualByComparingTo("20");assertThat(Sql.id(card.get(0).get("receipt_id"))).isEqualTo(first);assertThat(Sql.id(card.get(1).get("receipt_id"))).isEqualTo(second);
        update("DELETE rp FROM role_permissions rp JOIN roles r ON r.id=rp.role_id JOIN permissions p ON p.id=rp.permission_id WHERE r.code='WAREHOUSE' AND p.code='STOCK_RECEIPT_WRITE'");
        try{assertThat(stock.receipt(worker,first)).containsEntry("status","CONFIRMED");assertThat(stock.card(worker,warehouse,product)).hasSize(2);assertThatThrownBy(()->stock.saveReceipt(worker,0,0,key(),input("1"))).isInstanceOf(SecurityException.class);}finally{update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.code='WAREHOUSE' AND p.code='STOCK_RECEIPT_WRITE'");}
    }
    @Test void stockReadShowsSalesOnlyAvailabilityAndDeniesForeignWarehouseAndCards(){receive("20");long id=order("4");reserve(id);var salesRows=stock.stock(sales,warehouse,"");assertThat(salesRows).hasSize(1);assertThat(salesRows.get(0)).doesNotContainKeys("onHand","reserved","cost_price").containsEntry("physical",false).containsEntry("can_card",false);assertThat(new BigDecimal(salesRows.get(0).get("available").toString())).isEqualByComparingTo("16");
        var warehouseRows=stock.stock(worker,warehouse,"");assertThat(warehouseRows.get(0)).containsKeys("onHand","reserved").containsEntry("can_card",true);assertThat(stock.stock(manager,warehouse,"").get(0)).containsEntry("physical",true).containsEntry("can_card",false);
        assertThatThrownBy(()->stock.stock(user("WAREHOUSE"),warehouse,"")).isInstanceOf(SecurityException.class);assertThatThrownBy(()->stock.card(sales,warehouse,product)).isInstanceOf(SecurityException.class);assertThatThrownBy(()->stock.card(manager,warehouse,product)).isInstanceOf(SecurityException.class);
        update("UPDATE dealers SET primary_staff_id=? WHERE id=?",user("SALES"),dealer);assertThat(stock.stock(sales,null,"")).isEmpty();assertThatThrownBy(()->stock.stock(sales,warehouse,"")).isInstanceOf(SecurityException.class);
    }
    @Test void cancellationRequiresScopeAndReasonReleasesStockCreditAndReplaysOnce(){receive("10");long id=order("4");reserve(id);String k=key();
        assertThatThrownBy(()->orders.cancel(worker,id,2,k,"Hủy")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->orders.cancel(sales,id,2,k,"")).isInstanceOf(IllegalArgumentException.class);
        orders.cancel(sales,id,2,k,"Đại lý đổi nhu cầu");orders.cancel(sales,id,2,k,"Đại lý đổi nhu cầu");assertThat(balance().reserved()).isEqualByComparingTo("0");assertThat(balance().onHand()).isEqualByComparingTo("10");
        assertThat(Sql.snapshot(source,c->CreditService.snapshot(c,dealer,now)).commitment()).isEqualByComparingTo("0");assertThat(orders.find(sales,id)).containsEntry("status","CANCELLED").containsEntry("can_cancel",false);
        assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE event_type='ORDER_CANCELLED' AND object_id=?",id)).isEqualTo(1);
        assertThatThrownBy(()->orders.cancel(sales,id,2,k,"Lý do khác")).isInstanceOf(vn.codegym.salesinventory.validation.CommandConflictException.class);
        long replacement=user("SALES");update("UPDATE dealers SET primary_staff_id=? WHERE id=?",replacement,dealer);assertThatThrownBy(()->orders.cancel(sales,id,2,k,"Đại lý đổi nhu cầu")).isInstanceOf(SecurityException.class);
    }
    @Test void cancellationAuditFailureRollsBackHoldsAndOrder(){receive("10");long id=order("4");reserve(id);
        update("CREATE TRIGGER fail_cancel BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit failed'");
        try{assertThatThrownBy(()->orders.cancel(sales,id,2,key(),"Hủy theo yêu cầu")).isInstanceOf(IllegalStateException.class);}finally{update("DROP TRIGGER fail_cancel");}
        assertThat(balance().reserved()).isEqualByComparingTo("4");assertThat(orders.find(sales,id)).containsEntry("status","SUBMITTED");
    }
    @Test void expirationReleasesOnlyUnprotectedRemainderAndAuditsSystemActor(){new StockService(source,Clock.fixed(now.plus(Duration.ofHours(24)),ZoneOffset.UTC)).expireHolds();receive("20");long first=order("4");reserve(first);long protectedOrder=order("4");reserve(protectedOrder);
        update("UPDATE mvp_holds SET protected_at=? WHERE order_id=?",java.sql.Timestamp.from(now),protectedOrder);
        var later=Clock.fixed(now.plus(Duration.ofHours(24)),ZoneOffset.UTC);var laterStock=new StockService(source,later);
        assertThat(Sql.snapshot(source,c->StockService.snapshot(c,warehouse,product,later.instant())).reserved()).isEqualByComparingTo("4");
        assertThat(laterStock.expireHolds()).isEqualTo(1);assertThat(laterStock.expireHolds()).isZero();
        assertThat(one("SELECT fulfillment_state FROM orders WHERE id=?",first)).containsEntry("fulfillment_state","EXPIRED");
        assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE event_type='STOCK_HOLDS_EXPIRED' AND object_id=? AND actor_user_id IS NULL",first)).isEqualTo(1);
        assertThat(Sql.snapshot(source,c->CreditService.snapshot(c,dealer,later.instant())).commitment()).isEqualByComparingTo("400");
    }
    @Test void expirationAuditFailureRollsBackReleaseAndCanRetry(){var laterStock=new StockService(source,Clock.fixed(now.plus(Duration.ofHours(25)),ZoneOffset.UTC));laterStock.expireHolds();receive("10");long id=order("4");reserve(id);
        update("CREATE TRIGGER fail_expiration BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit failed'");
        try{assertThatThrownBy(laterStock::expireHolds).isInstanceOf(IllegalStateException.class);}finally{update("DROP TRIGGER fail_expiration");}
        assertThat((BigDecimal)one("SELECT released FROM mvp_holds WHERE order_id=?",id).get("released")).isEqualByComparingTo("0");
        assertThat(laterStock.expireHolds()).isEqualTo(1);
    }
    @Test void draftDoesNotAddStockAndConfirmationReplayAddsExactlyOnce() {
        long r=stock.saveReceipt(worker,0,0,key(),input("20"));assertThat(balance().onHand()).isEqualByComparingTo("0");
        String k=key();stock.confirmReceipt(worker,r,1,k);stock.confirmReceipt(worker,r,1,k);
        assertThat(balance().onHand()).isEqualByComparingTo("20");assertThat(balance().available()).isEqualByComparingTo("20");
        assertThat(count("SELECT COUNT(*) n FROM mvp_lots WHERE receipt_line_id IN(SELECT id FROM mvp_receipt_lines WHERE receipt_id=?)",r)).isEqualTo(1);
        assertThatThrownBy(()->stock.saveReceipt(worker,r,2,key(),input("30"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void receiptFailureRollsBackLotsStockReferencesAndReplayRecord() {
        long r=stock.saveReceipt(worker,0,0,key(),input("20"));String k=key();failAudit();
        assertThatThrownBy(()->stock.confirmReceipt(worker,r,1,k)).isInstanceOf(IllegalStateException.class);
        assertThat(balance().onHand()).isEqualByComparingTo("0");assertThat(stock.receipt(worker,r)).containsEntry("status","DRAFT");
        assertThat(count("SELECT COUNT(*) n FROM mvp_commands WHERE actor_id=? AND operation='RECEIPT_CONFIRM' AND request_key=?",worker,k)).isZero();
    }
    @Test void usersCannotWriteOutsideAssignedWarehouseOrUseAnotherWarehouseSupplier() {
        long otherWorker=user("WAREHOUSE"),otherWarehouse=warehouse(otherWorker);
        assertThatThrownBy(()->stock.saveReceipt(otherWorker,0,0,key(),input("2"))).isInstanceOf(SecurityException.class);
        var wrongSupplier=new StockService.ReceiptInput(otherWarehouse,supplier,"PN",LocalDate.of(2027,1,1),input("2").lines());
        assertThatThrownBy(()->stock.saveReceipt(otherWorker,0,0,key(),wrongSupplier)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->stock.saveReceipt(manager,0,0,key(),input("2"))).isInstanceOf(SecurityException.class);
    }
    @Test void twoOrdersCannotReserveMoreThanRealStock() throws Exception {
        receive("5");long first=order("4"),second=order("4");var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try { var tasks=new ArrayList<Future<Boolean>>();for(long id:List.of(first,second)) tasks.add(pool.submit(()->{start.await();try{reserve(id);return true;}catch(IllegalArgumentException e){return false;}}));
            start.countDown();int ok=0;for(var t:tasks) if(t.get(30,TimeUnit.SECONDS)) ok++;
            assertThat(ok).isEqualTo(1);assertThat(balance().reserved()).isEqualByComparingTo("4");assertThat(balance().available()).isEqualByComparingTo("1");
            var credit=Sql.snapshot(source,c->CreditService.snapshot(c,dealer,now));assertThat(credit.commitment()).isEqualByComparingTo("400");assertThat(credit.outstanding()).isEqualByComparingTo("0");
        } finally {pool.shutdownNow();}
    }
    @Test void failedReservationAuditRollsBackAllHolds() {
        receive("5");long id=order("4");failAudit();assertThatThrownBy(()->reserve(id)).isInstanceOf(IllegalStateException.class);
        assertThat(balance().reserved()).isEqualByComparingTo("0");assertThat(balance().available()).isEqualByComparingTo("5");
    }
    @Test void receiptKeepsUnitNameAndFactorAndMultipleRolesDoNotWidenWriteWarehouseScope() {
        long r=stock.saveReceipt(worker,0,0,key(),input("2"));
        var old=one("SELECT name,factor FROM product_units WHERE id=?",unit);
        update("UPDATE product_units SET name='Tên mới',version=version+1 WHERE id=?",unit);
        stock.confirmReceipt(worker,r,1,key());
        assertThat(one("SELECT unit_name,factor FROM conversion_snapshots WHERE reference_id=?","RECEIPT:"+Sql.id(one("SELECT id FROM mvp_receipt_lines WHERE receipt_id=?",r).get("id")))).containsEntry("unit_name",old.get("name")).containsEntry("factor",old.get("factor"));
        update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='SALES_MANAGER'",worker);
        long other=warehouse(user("WAREHOUSE"));
        assertThat(stock.warehouses(worker,"STOCK_RECEIPT_WRITE")).extracting(w->Sql.id(w.get("id"))).containsExactly(warehouse);
        assertThatThrownBy(()->stock.saveReceipt(worker,0,0,key(),new StockService.ReceiptInput(other,supplier,"PN",LocalDate.of(2027,1,1),input("1").lines()))).isInstanceOf(SecurityException.class);
    }
}
