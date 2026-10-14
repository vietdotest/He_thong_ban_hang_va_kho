package vn.codegym.salesinventory.dao;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.CommandConflictException;
import static org.assertj.core.api.Assertions.*;

class MvpReturnAcceptanceIT extends MvpWorkflowSupport {
    long accountant,warehouseManager,invoice;ReturnService returns;
    @BeforeEach void invoiceSource(){accountant=user("ACCOUNTANT");warehouseManager=warehouseManager();long shipment=dispatched("10","8");fulfillment.deliver(worker,shipment,1,key(),"Người nhận",received(shipment,"8"),"");invoice=new InvoiceService(source,clock).issue(accountant,shipment,2,key());returns=new ReturnService(source,clock);}
    long invoiceLine(){return Sql.id(one("SELECT id FROM mvp_invoice_lines WHERE invoice_id=?",invoice).get("id"));}
    ReturnService.Input input(String qty,String condition){return new ReturnService.Input(invoice,"Khách trả hàng",List.of(new ReturnService.Line(invoiceLine(),new BigDecimal(qty),condition)));}
    @Test void goodReturnRestoresOriginalLotOnceButDoesNotChangeDebtOrOrderDemandBeforeCredit(){String k=key();long id=returns.save(sales,0,0,k,input("2","SELLABLE"));assertThat(returns.save(sales,0,0,k,input("2","SELLABLE"))).isEqualTo(id);assertThat(balance().onHand()).isEqualByComparingTo("12");String receive=key();returns.receive(warehouseManager,id,1,receive,true);returns.receive(warehouseManager,id,1,receive,true);
        assertThat(balance().onHand()).isEqualByComparingTo("14");assertThat(balance().reserved()).isEqualByComparingTo("2");assertThat(Sql.snapshot(source,c->CreditService.snapshot(c,dealer,now)).outstanding()).isEqualByComparingTo("800000");assertThat(Sql.snapshot(source,c->CreditService.snapshot(c,dealer,now)).commitment()).isEqualByComparingTo("200000");assertThat(returns.document(warehouseManager,id)).containsEntry("status","RECEIVED");assertThatThrownBy(()->returns.save(sales,id,2,key(),input("1","SELLABLE"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(one("SELECT l.received_date FROM mvp_lots l JOIN mvp_invoice_lines il ON il.lot_id=l.id WHERE il.id=?",invoiceLine()).get("received_date").toString()).isEqualTo("2027-01-01");
    }
    @Test void damagedReturnIsConfirmedWithoutIncreasingOnHandReservedOrAvailable(){long id=returns.save(sales,0,0,key(),input("2","DAMAGED"));returns.receive(warehouseManager,id,1,key(),true);assertThat(balance().onHand()).isEqualByComparingTo("12");assertThat(balance().reserved()).isEqualByComparingTo("2");assertThat(balance().available()).isEqualByComparingTo("10");assertThat(count("SELECT COUNT(*) n FROM mvp_stock_events e WHERE e.source_type='RETURN_LINE' AND e.source_id IN (SELECT id FROM mvp_return_lines WHERE return_id=?)",id)).isZero();assertThat(returns.document(warehouseManager,id).toString()).doesNotContain("net_amount","cost_price");
    }
    @Test void goodDamagedAndPendingTotalsCannotExceedInvoiceAndCancelFreesDraftQuantity(){assertThat(returns.invoicesForReturn(sales)).hasSize(1);long id=returns.save(sales,0,0,key(),input("5","SELLABLE"));assertThatThrownBy(()->returns.save(sales,0,0,key(),input("4","DAMAGED"))).isInstanceOf(CommandConflictException.class);returns.save(sales,id,1,key(),new ReturnService.Input(invoice,"Điều chỉnh lượng thực trả",List.of(new ReturnService.Line(invoiceLine(),new BigDecimal("3"),"SELLABLE"),new ReturnService.Line(invoiceLine(),new BigDecimal("2"),"DAMAGED"))));
        assertThatThrownBy(()->returns.receive(warehouseManager,id,1,key(),true)).isInstanceOf(CommandConflictException.class);returns.cancel(sales,id,2,key(),"Khách chưa trả");assertThat(returns.document(sales,id)).containsEntry("status","CANCELLED");returns.save(sales,0,0,key(),input("8","DAMAGED"));assertThat(balance().onHand()).isEqualByComparingTo("12");assertThat(returns.invoicesForReturn(sales)).isEmpty();
    }
    @Test void receivingNeedsPhysicalConfirmationCorrectRoleAndAssignedSourceWarehouse(){long id=returns.save(sales,0,0,key(),input("2","SELLABLE"));assertThatThrownBy(()->returns.receive(warehouseManager,id,1,key(),false)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->returns.receive(sales,id,1,key(),true)).isInstanceOf(SecurityException.class);assertThatThrownBy(()->returns.receive(user("WAREHOUSE_MANAGER"),id,1,key(),true)).isInstanceOf(SecurityException.class);assertThatThrownBy(()->returns.save(user("SALES"),0,0,key(),input("1","SELLABLE"))).isInstanceOf(SecurityException.class);
        long unrelated=user("WAREHOUSE_MANAGER");assertThat(returns.returns(unrelated)).isEmpty();assertThatThrownBy(()->returns.document(unrelated,id)).isInstanceOf(SecurityException.class);String k=key();returns.receive(warehouseManager,id,1,k,true);update("DELETE FROM user_warehouses WHERE user_id=? AND warehouse_id=?",warehouseManager,warehouse);assertThatThrownBy(()->returns.receive(warehouseManager,id,1,k,true)).isInstanceOf(SecurityException.class);
    }
    @Test void returnReceiveAuditFailureRollsBackStockAndState(){long id=returns.save(sales,0,0,key(),input("2","SELLABLE"));update("CREATE TRIGGER fail_return_receipt BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit failed'");
        try{assertThatThrownBy(()->returns.receive(warehouseManager,id,1,key(),true)).isInstanceOf(IllegalStateException.class);}finally{update("DROP TRIGGER fail_return_receipt");}assertThat(balance().onHand()).isEqualByComparingTo("12");assertThat(returns.document(warehouseManager,id)).containsEntry("status","DRAFT");
    }
    @Test void concurrentGoodAndDamagedDraftsHaveOnlyOneSixUnitWinner()throws Exception{var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{var tasks=new ArrayList<Future<Boolean>>();for(String condition:List.of("SELLABLE","DAMAGED"))tasks.add(pool.submit(()->{barrier.await();try{returns.save(sales,0,0,key(),input("6",condition));return true;}catch(CommandConflictException e){return false;}}));int wins=0;for(var task:tasks)if(task.get(30,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);assertThat(count("SELECT COUNT(*) n FROM mvp_returns WHERE invoice_id=?",invoice)).isEqualTo(1);}finally{pool.shutdownNow();}
    }
}
