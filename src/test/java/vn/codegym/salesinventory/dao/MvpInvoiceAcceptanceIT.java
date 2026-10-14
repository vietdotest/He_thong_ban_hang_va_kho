package vn.codegym.salesinventory.dao;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.CommandConflictException;
import static org.assertj.core.api.Assertions.*;

class MvpInvoiceAcceptanceIT extends MvpWorkflowSupport {
    @Test void orderLinksOnlyItsInvoicesAndCurrentDealerScopeIsRechecked(){long shipment=delivered("8","8","8");long invoice=new InvoiceService(source,clock).issue(user("ACCOUNTANT"),shipment,2,key());long order=Sql.id(one("SELECT order_id FROM mvp_shipments WHERE id=?",shipment).get("order_id"));var links=(List<Map<String,Object>>)orders.find(sales,order).get("invoices");assertThat(links).hasSize(1);assertThat(Sql.id(links.get(0).get("id"))).isEqualTo(invoice);update("UPDATE dealers SET primary_staff_id=? WHERE id=?",user("SALES"),dealer);assertThatThrownBy(()->orders.find(sales,order)).isInstanceOf(SecurityException.class);}
    long delivered(String ordered,String sent,String received){long shipment=dispatched(ordered,sent);fulfillment.deliver(worker,shipment,1,key(),"Người nhận",received(shipment,received),"Giao theo thực nhận");return shipment;}
    CreditService.CreditSnapshot credit(){return Sql.snapshot(source,c->CreditService.snapshot(c,dealer,now));}
    @Test void invoiceForEightTransfersOnlyDeliveredCommitmentAndReplayCannotDuplicate(){long shipment=delivered("10","8","8");var invoices=new InvoiceService(source,clock);long accountant=user("ACCOUNTANT");String k=key();long id=invoices.issue(accountant,shipment,2,k);assertThat(invoices.issue(accountant,shipment,2,k)).isEqualTo(id);
        assertThat(credit().outstanding()).isEqualByComparingTo("800000");assertThat(credit().commitment()).isEqualByComparingTo("200000");assertThat(balance().onHand()).isEqualByComparingTo("12");assertThat(balance().reserved()).isEqualByComparingTo("2");var invoice=invoices.invoice(accountant,id);assertThat((BigDecimal)invoice.get("net_amount")).isEqualByComparingTo("800000");assertThat(invoice.get("due_date").toString()).isEqualTo("2027-02-02");assertThat(invoice.get("number").toString()).startsWith("HD-2027-");assertThat(count("SELECT COUNT(*) n FROM mvp_invoices WHERE shipment_id=?",shipment)).isEqualTo(1);assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE event_type='INVOICE_ISSUED' AND object_id=?",id)).isEqualTo(1);
    }
    @Test void failedTwoRemainCommittedAfterInvoiceSixUntilWarehouseReceivesThem(){long shipment=delivered("8","8","6");long accountant=user("ACCOUNTANT");long invoice=new InvoiceService(source,clock).issue(accountant,shipment,2,key());assertThat(credit().outstanding()).isEqualByComparingTo("600000");assertThat(credit().commitment()).isEqualByComparingTo("200000");assertThat((BigDecimal)one("SELECT quantity FROM mvp_invoice_lines WHERE invoice_id=?",invoice).get("quantity")).isEqualByComparingTo("6");
        var later=Clock.fixed(now.plus(Duration.ofDays(3)),ZoneOffset.UTC);new StockService(source,later).expireHolds();assertThat(Sql.snapshot(source,c->CreditService.snapshot(c,dealer,later.instant())).commitment()).isEqualByComparingTo("200000");fulfillment.recover(warehouseManager(),shipment,2,key(),recovery(shipment,"2","DAMAGED"),"Hàng hỏng");assertThat(credit().outstanding()).isEqualByComparingTo("600000");assertThat(credit().commitment()).isEqualByComparingTo("0");assertThat(balance().onHand()).isEqualByComparingTo("12");
    }
    @Test void invoiceRoleSourceScopeAndReadPermissionsAreEnforced(){long shipment=dispatched("8","8");var invoices=new InvoiceService(source,clock);long accountant=user("ACCOUNTANT");assertThatThrownBy(()->invoices.issue(sales,shipment,1,key())).isInstanceOf(SecurityException.class);assertThatThrownBy(()->invoices.issue(accountant,shipment,1,key())).isInstanceOf(IllegalArgumentException.class);fulfillment.deliver(worker,shipment,1,key(),"Khách",received(shipment,"8"),"");long id=invoices.issue(accountant,shipment,2,key());assertThat(invoices.invoice(sales,id)).containsEntry("shipment_id",one("SELECT shipment_id FROM mvp_invoices WHERE id=?",id).get("shipment_id"));assertThatThrownBy(()->invoices.invoice(user("SALES"),id)).isInstanceOf(SecurityException.class);assertThatThrownBy(()->invoices.issue(accountant,shipment,2,key())).isInstanceOf(CommandConflictException.class);
        long replacement=user("SALES");update("UPDATE dealers SET primary_staff_id=? WHERE id=?",replacement,dealer);assertThatThrownBy(()->invoices.invoice(sales,id)).isInstanceOf(SecurityException.class);assertThat(invoices.invoices(sales)).isEmpty();
    }
    long nextNumber(){return Sql.id(one("SELECT COALESCE((SELECT next_value FROM mvp_sequences WHERE kind='HD' AND document_year=2027),1) n").get("n"));}
    @Test void invoiceAuditFailureRollsBackNumberReceivableCommitmentAndDocument(){long shipment=delivered("8","8","6");long accountant=user("ACCOUNTANT");long before=nextNumber();update("CREATE TRIGGER fail_invoice BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit failed'");
        try{assertThatThrownBy(()->new InvoiceService(source,clock).issue(accountant,shipment,2,key())).isInstanceOf(IllegalStateException.class);}finally{update("DROP TRIGGER fail_invoice");}
        assertThat(nextNumber()).isEqualTo(before);assertThat(count("SELECT COUNT(*) n FROM mvp_invoices WHERE shipment_id=?",shipment)).isZero();assertThat(credit().outstanding()).isEqualByComparingTo("0");assertThat(credit().commitment()).isEqualByComparingTo("800000");new InvoiceService(source,clock).issue(accountant,shipment,2,key());assertThat(nextNumber()).isEqualTo(before+1);
    }
    @Test void concurrentInvoiceIssuanceHasOneDocumentAndOneNumber()throws Exception{long shipment=delivered("8","8","8");long accountant=user("ACCOUNTANT");var invoices=new InvoiceService(source,clock);long before=nextNumber();var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{barrier.await();try{invoices.issue(accountant,shipment,2,key());return true;}catch(CommandConflictException e){return false;}}));int wins=0;for(var task:tasks)if(task.get(30,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);assertThat(nextNumber()).isEqualTo(before+1);assertThat(credit().outstanding()).isEqualByComparingTo("800000");}finally{pool.shutdownNow();}
    }
}
