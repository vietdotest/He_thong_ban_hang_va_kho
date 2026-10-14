package vn.codegym.salesinventory.dao;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;

class MvpPaymentAcceptanceIT extends MvpWorkflowSupport {
    long invoice(long accountant,String quantity){long shipment=dispatched(quantity,quantity);fulfillment.deliver(worker,shipment,1,key(),"Người nhận",received(shipment,quantity),"");return new InvoiceService(source,clock).issue(accountant,shipment,2,key());}
    PaymentService.Input payment(String amount){return new PaymentService.Input(new BigDecimal(amount),MvpCommands.date(now),"CASH","Thu theo hóa đơn");}
    long dealerVersion(){return Sql.id(one("SELECT version FROM dealers WHERE id=?",dealer).get("version"));}
    CreditService.CreditSnapshot credit(){return Sql.snapshot(source,c->CreditService.snapshot(c,dealer,now));}
    @Test void paymentAllocatesOldestInvoiceFirstAndReplayDoesNotCollectTwice(){long accountant=user("ACCOUNTANT");long first=invoice(accountant,"8"),second=invoice(accountant,"8");var payments=new PaymentService(source,clock);String k=key();long id=payments.record(accountant,dealer,dealerVersion(),k,payment("1000000"));assertThat(payments.record(accountant,dealer,dealerVersion(),k,payment("1000000"))).isEqualTo(id);
        assertThat(Sql.<BigDecimal>snapshot(source,c->CreditService.invoiceAllocated(c,first))).isEqualByComparingTo("800000");assertThat(Sql.<BigDecimal>snapshot(source,c->CreditService.invoiceAllocated(c,second))).isEqualByComparingTo("200000");assertThat(credit().outstanding()).isEqualByComparingTo("600000");assertThat(count("SELECT COUNT(*) n FROM mvp_payments WHERE dealer_id=?",dealer)).isEqualTo(1);assertThat(count("SELECT COUNT(*) n FROM mvp_payment_allocations WHERE payment_id=?",id)).isEqualTo(2);
        assertThatThrownBy(()->payments.record(accountant,dealer,dealerVersion(),key(),payment("600001"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->payments.record(accountant,dealer,dealerVersion(),k,payment("999999"))).isInstanceOf(vn.codegym.salesinventory.validation.CommandConflictException.class);
    }
    @Test void partialPaymentLeavesInvoiceSalesUntouchedAndWrongRoleScopeAndPrecisionAreDenied(){long accountant=user("ACCOUNTANT");long invoice=invoice(accountant,"8");var payments=new PaymentService(source,clock);assertThatThrownBy(()->payments.record(sales,dealer,dealerVersion(),key(),payment("300000"))).isInstanceOf(SecurityException.class);assertThatThrownBy(()->payments.record(accountant,dealer,dealerVersion(),key(),payment("0.00001"))).isInstanceOf(IllegalArgumentException.class);long id=payments.record(accountant,dealer,dealerVersion(),key(),payment("300000"));assertThat(credit().outstanding()).isEqualByComparingTo("500000");assertThat((BigDecimal)one("SELECT net_amount FROM mvp_invoices WHERE id=?",invoice).get("net_amount")).isEqualByComparingTo("800000");assertThat(payments.payment(sales,id)).containsEntry("method","CASH");assertThatThrownBy(()->payments.payment(user("SALES"),id)).isInstanceOf(SecurityException.class);
    }
    @Test void concurrentCollectionsCannotExceedOutstanding()throws Exception{long accountant=user("ACCOUNTANT");invoice(accountant,"6");var payments=new PaymentService(source,clock);var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{barrier.await();try{payments.record(accountant,dealer,dealerVersion(),key(),payment("400000"));return true;}catch(IllegalArgumentException e){return false;}}));int wins=0;for(var task:tasks)if(task.get(30,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);assertThat(credit().outstanding()).isEqualByComparingTo("200000");assertThat(count("SELECT COUNT(*) n FROM mvp_payments WHERE dealer_id=?",dealer)).isEqualTo(1);}finally{pool.shutdownNow();}
    }
    long nextNumber(){return Sql.id(one("SELECT COALESCE((SELECT next_value FROM mvp_sequences WHERE kind='PT' AND document_year=2027),1) n").get("n"));}
    @Test void paymentAuditFailureRollsBackNumberMoneyAndAllocations(){long accountant=user("ACCOUNTANT");long invoice=invoice(accountant,"8");long before=nextNumber();update("CREATE TRIGGER fail_payment BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit failed'");
        try{assertThatThrownBy(()->new PaymentService(source,clock).record(accountant,dealer,dealerVersion(),key(),payment("300000"))).isInstanceOf(IllegalStateException.class);}finally{update("DROP TRIGGER fail_payment");}
        assertThat(nextNumber()).isEqualTo(before);assertThat(credit().outstanding()).isEqualByComparingTo("800000");assertThat(count("SELECT COUNT(*) n FROM mvp_payments WHERE dealer_id=?",dealer)).isZero();assertThat(Sql.<BigDecimal>snapshot(source,c->CreditService.invoiceAllocated(c,invoice))).isEqualByComparingTo("0");
    }
}
