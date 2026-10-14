package vn.codegym.salesinventory.dao;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;

class MvpDebtReportAcceptanceIT extends MvpWorkflowSupport {
    long accountant,invoice;SalesDebtReportService reports;
    @BeforeEach void financialSource(){accountant=user("ACCOUNTANT");long shipment=dispatched("10","8");fulfillment.deliver(worker,shipment,1,key(),"Khách",received(shipment,"8"),"");invoice=new InvoiceService(source,clock).issue(accountant,shipment,2,key());reports=new SalesDebtReportService(source,clock);}
    void pay(String amount){long version=Sql.id(one("SELECT version FROM dealers WHERE id=?",dealer).get("version"));new PaymentService(source,clock).record(accountant,dealer,version,key(),new PaymentService.Input(new BigDecimal(amount),MvpCommands.date(now),"CASH","Thu tiền"));}
    void credit(String qty){long line=Sql.id(one("SELECT id FROM mvp_invoice_lines WHERE invoice_id=?",invoice).get("id"));var returns=new ReturnService(source,clock);long id=returns.save(sales,0,0,key(),new ReturnService.Input(invoice,"Hàng hỏng",List.of(new ReturnService.Line(line,new BigDecimal(qty),"DAMAGED"))));returns.receive(warehouseManager(),id,1,key(),true);returns.issueCredit(accountant,id,2,key());}
    @Test void currentDebtMatchesDocumentsAfterPaymentCreditAndKeepsCommitmentSeparate(){pay("300000");credit("2");var report=reports.debt(sales,dealer,"");assertThat(report.rows()).hasSize(1);assertThat(report.totals()).containsEntry("invoiced",new BigDecimal("800000.0000")).containsEntry("collected",new BigDecimal("300000.0000")).containsEntry("credited",new BigDecimal("200000.0000")).containsEntry("outstanding",new BigDecimal("300000.0000")).containsEntry("commitment",new BigDecimal("200000.0000"));
        var sources=reports.dealerDebt(sales,dealer);assertThat((BigDecimal)sources.get("outstanding")).isEqualByComparingTo("300000");@SuppressWarnings("unchecked")var invoices=(List<Map<String,Object>>)sources.get("invoices");assertThat(invoices).hasSize(1);assertThat((BigDecimal)invoices.get(0).get("outstanding")).isEqualByComparingTo("300000");assertThat(sources.toString()).doesNotContain("cost_price","floor_price");
    }
    @Test void reportRoleSourceScopeAndCurrentAssignmentApplyToTotalsAndDirectDocuments(){assertThatThrownBy(()->reports.debt(worker,null,"")).isInstanceOf(SecurityException.class);assertThatThrownBy(()->reports.debt(user("ADMIN"),null,"")).isInstanceOf(SecurityException.class);long other=user("SALES");assertThat(reports.debt(other,null,"").rows()).isEmpty();assertThat(reports.debt(other,null,"").totals().get("outstanding")).isEqualByComparingTo("0");assertThatThrownBy(()->reports.debt(other,dealer,"")).isInstanceOf(SecurityException.class);assertThatThrownBy(()->reports.dealerDebt(other,dealer)).isInstanceOf(SecurityException.class);
        update("UPDATE dealers SET primary_staff_id=? WHERE id=?",other,dealer);assertThat(reports.debt(sales,null,"").rows()).isEmpty();assertThatThrownBy(()->reports.dealerDebt(sales,dealer)).isInstanceOf(SecurityException.class);assertThat(reports.debt(other,dealer,"").totals().get("outstanding")).isEqualByComparingTo("800000");
    }
    @Test void overdueUsesInvoiceSnapshotDateAndOutstandingAfterAllocations(){pay("300000");var later=new SalesDebtReportService(source,Clock.fixed(now.plus(Duration.ofDays(31)),ZoneOffset.UTC));var report=later.debt(sales,dealer,"");assertThat(report.totals().get("overdue_amount")).isEqualByComparingTo("500000");assertThat(report.totals().get("commitment")).isEqualByComparingTo("200000");var sameDue=new SalesDebtReportService(source,Clock.fixed(now.plus(Duration.ofDays(30)),ZoneOffset.UTC));assertThat(sameDue.debt(sales,dealer,"").totals().get("overdue_amount")).isEqualByComparingTo("0");
    }
}
