package vn.codegym.salesinventory.dao;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.CommandConflictException;

class MvpFulfillmentAcceptanceIT extends StoryDatabaseSupport {
    long manager,sales,worker,warehouse,supplier,product,unit,dealer,address,group,priceVersion;
    final Instant now=Instant.parse("2027-01-03T03:00:00Z");final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    StockService stock;OrderService orders;FulfillmentService fulfillment;
    @BeforeEach void setup(){manager=user("SALES_MANAGER");sales=user("SALES");worker=user("WAREHOUSE");warehouse=warehouse(worker);
        supplier=insert("INSERT INTO suppliers(code,name,tax_code,contact_name,contact_phone,payment_terms,warehouse_id) VALUES(?,'Nhà cung cấp','','Người giao','0912345678','',?)","S-"+UUID.randomUUID(),warehouse);
        product=product(manager);unit=Sql.id(one("SELECT id FROM product_units WHERE product_id=? AND is_base=1",product).get("id"));
        group=insert("INSERT INTO customer_groups(code,name) VALUES(?,'Nhóm bán')","G-"+UUID.randomUUID().toString().substring(0,12));long territory=insert("INSERT INTO territories(code,name,address) VALUES(?,'Khu vực','Địa chỉ')","T-"+UUID.randomUUID());
        update("INSERT INTO user_territories(user_id,territory_id) VALUES(?,?)",sales,territory);dealer=new DealerService(source).save(manager,0,new DealerService.Input("D-"+UUID.randomUUID(),"Đại lý","","0912345678",group,territory,sales,warehouse,"ACTIVE",0));
        address=new DealerAddressService(source).save(manager,dealer,0,new DealerAddressService.Input("12 Đường giao","Người nhận","0912345678","",true,"ACTIVE",0,1));update("UPDATE dealers SET credit_limit=2000000,debt_days=30 WHERE id=?",dealer);
        var pricing=new PricingService(source);priceVersion=pricing.create(manager,group,"Giá",LocalDate.of(2027,1,1),LocalDate.of(2027,1,31),null);pricing.saveItem(manager,priceVersion,product,new BigDecimal("100000"),new BigDecimal("50000"));
        stock=new StockService(source,clock);orders=new OrderService(source,clock);fulfillment=new FulfillmentService(source,clock);receive("10",LocalDate.of(2027,1,1));receive("10",LocalDate.of(2027,1,2));
    }
    String key(){return UUID.randomUUID().toString();}
    void receive(String qty,LocalDate date){long id=stock.saveReceipt(worker,0,0,key(),new StockService.ReceiptInput(warehouse,supplier,"PN-"+key(),date,List.of(new StockService.ReceiptLine(product,unit,new BigDecimal(qty),"Lô cùng tên",null))));stock.confirmReceipt(worker,id,1,key());}
    long accepted(String qty){long id=orders.save(sales,0,new OrderService.Input(dealer,address,LocalDate.of(2027,1,4),0,key(),List.of(new OrderService.LineInput(product,unit,new BigDecimal(qty)))));var q=orders.quote(sales,id);assertThat(orders.submit(sales,id,1,q.token(),key(),false).submitted()).isTrue();return id;}
    long line(long order){return Sql.id(one("SELECT id FROM order_lines WHERE order_id=?",order).get("id"));}
    List<FulfillmentService.DispatchLine> take(long order,String qty){return List.of(new FulfillmentService.DispatchLine(line(order),new BigDecimal(qty)));}
    StockService.StockSnapshot balance(){return Sql.snapshot(source,c->StockService.snapshot(c,warehouse,product,now));}
    long dispatched(String ordered,String sent){long id=accepted(ordered);fulfillment.startPicking(worker,id,2,key());return fulfillment.dispatch(worker,id,3,key(),take(id,sent),"Xuất từng phần");}
    List<FulfillmentService.DeliveryLine> received(long shipment,String qty){long id=Sql.id(one("SELECT id FROM mvp_shipment_lines WHERE shipment_id=?",shipment).get("id"));return List.of(new FulfillmentService.DeliveryLine(id,new BigDecimal(qty)));}
    @Test void failedDeliveryKeepsFullCommitmentAndOnlyReceivedQuantityIsBillable(){long shipment=dispatched("8","8");String k=key();
        assertThatThrownBy(()->fulfillment.deliver(worker,shipment,1,k,"Người nhận",received(shipment,"6"),"")).isInstanceOf(IllegalArgumentException.class);
        fulfillment.deliver(worker,shipment,1,k,"Người nhận",received(shipment,"6"),"Khách nhận trước 6");fulfillment.deliver(worker,shipment,1,k,"Người nhận",received(shipment,"6"),"Khách nhận trước 6");
        var line=one("SELECT quantity,received_quantity,received_amount,returned_quantity FROM mvp_shipment_lines WHERE shipment_id=?",shipment);assertThat((BigDecimal)line.get("received_quantity")).isEqualByComparingTo("6");assertThat((BigDecimal)line.get("received_amount")).isEqualByComparingTo("600000");assertThat((BigDecimal)line.get("returned_quantity")).isEqualByComparingTo("0");
        assertThat(Sql.snapshot(source,c->CreditService.snapshot(c,dealer,now)).commitment()).isEqualByComparingTo("800000");var after=Clock.fixed(now.plus(Duration.ofDays(3)),ZoneOffset.UTC);new StockService(source,after).expireHolds();assertThat(Sql.snapshot(source,c->CreditService.snapshot(c,dealer,after.instant())).commitment()).isEqualByComparingTo("800000");assertThat(balance().onHand()).isEqualByComparingTo("12");
        assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE event_type='SHIPMENT_DELIVERED' AND object_id=?",shipment)).isEqualTo(1);
    }
    @Test void deliveryCannotChangeConfirmedDocumentOrExceedSourceOrBypassScope(){long shipment=dispatched("8","8");assertThatThrownBy(()->fulfillment.deliver(worker,shipment,1,key(),"Người nhận",received(shipment,"9"),"")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->fulfillment.deliver(sales,shipment,1,key(),"Người nhận",received(shipment,"8"),"")).isInstanceOf(SecurityException.class);
        String k=key();fulfillment.deliver(worker,shipment,1,k,"Người nhận",received(shipment,"8"),"");assertThatThrownBy(()->fulfillment.deliver(worker,shipment,2,key(),"Người nhận",received(shipment,"6"),"Sửa giao")).isInstanceOf(IllegalArgumentException.class);
        update("DELETE FROM user_warehouses WHERE user_id=? AND warehouse_id=?",worker,warehouse);assertThatThrownBy(()->fulfillment.deliver(worker,shipment,1,k,"Người nhận",received(shipment,"8"),"")).isInstanceOf(SecurityException.class);assertThat(fulfillment.orders(worker)).isEmpty();
    }
    @Test void deliveryAuditFailureRollsBackReceivedQuantityAndDocument(){long shipment=dispatched("8","8");update("CREATE TRIGGER fail_delivery BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit failed'");
        try{assertThatThrownBy(()->fulfillment.deliver(worker,shipment,1,key(),"Người nhận",received(shipment,"6"),"Khách nhận trước")).isInstanceOf(IllegalStateException.class);}finally{update("DROP TRIGGER fail_delivery");}
        assertThat(one("SELECT status FROM mvp_shipments WHERE id=?",shipment)).containsEntry("status","DISPATCHED");assertThat(one("SELECT received_quantity FROM mvp_shipment_lines WHERE shipment_id=?",shipment).get("received_quantity")).isNull();assertThat(Sql.snapshot(source,c->CreditService.snapshot(c,dealer,now)).commitment()).isEqualByComparingTo("800000");
    }
    @Test void partialDispatchConsumesFifoKeepsRemainderCommitmentAndReplayDoesNotDuplicate(){long id=accepted("10");String pick=key();fulfillment.startPicking(worker,id,2,pick);fulfillment.startPicking(worker,id,2,pick);String dispatch=key();
        assertThatThrownBy(()->fulfillment.dispatch(worker,id,3,dispatch,take(id,"8"),"")).isInstanceOf(IllegalArgumentException.class);
        long shipment=fulfillment.dispatch(worker,id,3,dispatch,take(id,"8"),"Giao trước phần sẵn soạn");assertThat(fulfillment.dispatch(worker,id,3,dispatch,take(id,"8"),"Giao trước phần sẵn soạn")).isEqualTo(shipment);
        assertThat(balance().onHand()).isEqualByComparingTo("12");assertThat(balance().reserved()).isEqualByComparingTo("2");assertThat(Sql.snapshot(source,c->CreditService.snapshot(c,dealer,now)).commitment()).isEqualByComparingTo("1000000");
        var lots=query("SELECT on_hand FROM mvp_lots WHERE warehouse_id=? ORDER BY received_date,id",warehouse);assertThat((BigDecimal)lots.get(0).get("on_hand")).isEqualByComparingTo("2");assertThat((BigDecimal)lots.get(1).get("on_hand")).isEqualByComparingTo("10");
        assertThat((BigDecimal)one("SELECT net_amount FROM mvp_shipment_lines WHERE shipment_id=?",shipment).get("net_amount")).isEqualByComparingTo("800000");assertThatThrownBy(()->orders.cancel(sales,id,4,key(),"Hủy toàn đơn")).isInstanceOf(IllegalArgumentException.class);
        fulfillment.dispatch(worker,id,4,key(),take(id,"2"),"");assertThat(balance().onHand()).isEqualByComparingTo("10");assertThat(balance().reserved()).isEqualByComparingTo("0");
    }
    @Test void wrongWarehouseRoleQuantityAndForeignLineAreDenied(){long id=accepted("10");assertThatThrownBy(()->fulfillment.startPicking(sales,id,2,key())).isInstanceOf(SecurityException.class);assertThatThrownBy(()->fulfillment.startPicking(user("WAREHOUSE"),id,2,key())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->fulfillment.dispatch(worker,id,2,key(),take(id,"1"),"Chưa soạn")).isInstanceOf(IllegalArgumentException.class);fulfillment.startPicking(worker,id,2,key());
        assertThatThrownBy(()->fulfillment.dispatch(worker,id,3,key(),take(id,"11"),"Vượt giữ")).isInstanceOf(CommandConflictException.class);
        assertThatThrownBy(()->fulfillment.dispatch(worker,id,3,key(),List.of(new FulfillmentService.DispatchLine(Long.MAX_VALUE,BigDecimal.ONE)),"Sai dòng")).isInstanceOf(SecurityException.class);
        assertThat(balance().onHand()).isEqualByComparingTo("20");
    }
    @Test void dispatchAuditFailureRollsBackLotHoldsDocumentAndVersion(){long id=accepted("10");fulfillment.startPicking(worker,id,2,key());update("CREATE TRIGGER fail_dispatch BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit failed'");
        try{assertThatThrownBy(()->fulfillment.dispatch(worker,id,3,key(),take(id,"8"),"Giao trước")).isInstanceOf(IllegalStateException.class);}finally{update("DROP TRIGGER fail_dispatch");}
        assertThat(balance().onHand()).isEqualByComparingTo("20");assertThat(balance().reserved()).isEqualByComparingTo("10");assertThat(count("SELECT COUNT(*) n FROM mvp_shipments WHERE order_id=?",id)).isZero();assertThat(Sql.id(one("SELECT version FROM orders WHERE id=?",id).get("version"))).isEqualTo(3);
    }
    @Test void concurrentDispatchHasOneWinner()throws Exception{long id=accepted("10");fulfillment.startPicking(worker,id,2,key());var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{barrier.await();try{fulfillment.dispatch(worker,id,3,key(),take(id,"8"),"Giao trước");return true;}catch(CommandConflictException e){return false;}}));int wins=0;for(var task:tasks)if(task.get(30,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);assertThat(balance().onHand()).isEqualByComparingTo("12");}finally{pool.shutdownNow();}
    }
    @Test void pickingAndExpirationHaveOneValidOutcomeAndProtectedRemainderDoesNotExpire()throws Exception{long id=accepted("10");var after=Clock.fixed(now.plus(Duration.ofHours(24)),ZoneOffset.UTC);var expiry=new StockService(source,after);var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{var pick=pool.submit(()->{barrier.await();try{fulfillment.startPicking(worker,id,2,key());return true;}catch(CommandConflictException e){return false;}});var expire=pool.submit(()->{barrier.await();expiry.expireHolds();return true;});boolean picked=pick.get(30,TimeUnit.SECONDS);expire.get(30,TimeUnit.SECONDS);
            BigDecimal active=Sql.snapshot(source,c->StockService.snapshot(c,warehouse,product,after.instant())).reserved();assertThat(active).isEqualByComparingTo(picked?"10":"0");if(picked){expiry.expireHolds();assertThat(Sql.snapshot(source,c->CreditService.snapshot(c,dealer,after.instant())).commitment()).isEqualByComparingTo("1000000");}
        }finally{pool.shutdownNow();}
    }
    @Test void fractionalDispatchesReceiveFinalRemainderWithoutMoneyDrift(){new PricingService(source).saveItem(manager,priceVersion,product,new BigDecimal("0.0001"),BigDecimal.ZERO);long id=accepted("1");fulfillment.startPicking(worker,id,2,key());fulfillment.dispatch(worker,id,3,key(),take(id,"0.333333333333"),"Xuất từng phần");fulfillment.dispatch(worker,id,4,key(),take(id,"0.333333333333"),"Xuất từng phần");fulfillment.dispatch(worker,id,5,key(),take(id,"0.333333333334"),"");
        assertThat((BigDecimal)one("SELECT SUM(net_amount) amount FROM mvp_shipment_lines WHERE order_line_id=?",line(id)).get("amount")).isEqualByComparingTo("0.0001");assertThat(balance().reserved()).isEqualByComparingTo("0");
    }
}
