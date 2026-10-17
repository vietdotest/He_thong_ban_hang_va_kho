package vn.codegym.salesinventory.dao;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;

class CostSnapshotAcceptanceIT extends MvpWorkflowSupport {
    long delivered(String sent,String received) {
        long shipment=dispatched(sent,sent);fulfillment.deliver(worker,shipment,1,key(),"Khách",received(shipment,received),"Khách nhận một phần");return shipment;
    }
    long issue(long actor,long shipment) {return new InvoiceService(source,clock).issue(actor,shipment,2,key());}
    long returned(long accountant,long invoice,String quantity,String condition) {
        long line=Sql.id(one("SELECT id FROM mvp_invoice_lines WHERE invoice_id=? ORDER BY id LIMIT 1",invoice).get("id"));
        var service=new ReturnService(source,clock);long returned=service.save(sales,0,0,key(),new ReturnService.Input(invoice,"Trả hàng",List.of(new ReturnService.Line(line,new BigDecimal(quantity),condition))));
        service.receive(warehouseManager(),returned,1,key(),true);service.issueCredit(accountant,returned,2,key());return returned;
    }
    @Test void costIsFrozenAtDispatchAndOnlyReceivedGoodsReachInvoiceAndManagerReport() {
        update("UPDATE products SET cost_price=60000 WHERE id=?",product);long shipment=delivered("8","6"),accountant=user("ACCOUNTANT");
        update("UPDATE products SET cost_price=90000 WHERE id=?",product);long invoice=issue(accountant,shipment);
        assertThat((BigDecimal)one("SELECT cost_amount FROM mvp_invoice_lines WHERE invoice_id=?",invoice).get("cost_amount")).isEqualByComparingTo("360000");
        var reports=new SalesDebtReportService(source,clock);LocalDate day=MvpCommands.date(now);
        var report=reports.sales(manager,day,day,dealer,"");assertThat(report.totals().get("cost")).isEqualByComparingTo("360000");assertThat(report.totals().get("gross_profit")).isEqualByComparingTo("240000");assertThat(report.totals().get("margin_percent")).isEqualByComparingTo("40");
        assertThat(reports.sales(sales,day,day,dealer,"").toString()).doesNotContain("cost","gross_profit","margin_percent");
        assertThat(reports.sales(accountant,day,day,dealer,"").toString()).doesNotContain("cost","gross_profit","margin_percent");
        assertThat(reports.sales(user("DIRECTOR"),day,day,dealer,"").toString()).doesNotContain("cost","gross_profit","margin_percent");
        assertThat(fulfillment.shipment(worker,shipment).toString()).doesNotContain("unit_cost_snapshot","cost_amount");
        assertThat(new InvoiceService(source,clock).invoice(accountant,invoice).toString()).doesNotContain("cost_amount");
        returned(accountant,invoice,"3","SELLABLE");returned(accountant,invoice,"3","DAMAGED");
        report=reports.sales(manager,day,day,dealer,"");assertThat(report.totals().get("cost")).isEqualByComparingTo("0");assertThat(report.totals().get("gross_profit")).isEqualByComparingTo("0");assertThat(report.totals().get("margin_percent")).isNull();
    }
    @Test void fractionalReturnsAllocateAllOriginalCostWithoutRoundingDrift() {
        update("UPDATE products SET cost_price=0.0001 WHERE id=?",product);long shipment=delivered("1","1"),accountant=user("ACCOUNTANT"),invoice=issue(accountant,shipment);
        for(String quantity:List.of("0.333333","0.333333","0.333334"))returned(accountant,invoice,quantity,"DAMAGED");
        assertThat((BigDecimal)one("SELECT SUM(cost_amount) amount FROM mvp_credit_lines WHERE invoice_line_id IN (SELECT id FROM mvp_invoice_lines WHERE invoice_id=?)",invoice).get("amount")).isEqualByComparingTo("0.0001");
        assertThat(query("SELECT cost_amount FROM mvp_credit_lines WHERE cost_amount<0")).isEmpty();
    }
    @Test void legacyMissingSnapshotIsNotRebuiltFromCurrentCatalog() {
        long shipment=delivered("2","2"),accountant=user("ACCOUNTANT");update("UPDATE mvp_shipment_lines SET unit_cost_snapshot=NULL,cost_amount=NULL WHERE shipment_id=?",shipment);long invoice=issue(accountant,shipment);
        var reports=new SalesDebtReportService(source,clock);LocalDate day=MvpCommands.date(now);var report=reports.sales(manager,day,day,dealer,"");
        assertThat(report.totals().get("cost_missing_amount")).isEqualByComparingTo("200000");assertThat(report.totals().get("gross_profit")).isNull();assertThat(report.totals().get("margin_percent")).isNull();
        assertThatThrownBy(()->reports.sales(worker,day,day,dealer,"")).isInstanceOf(SecurityException.class);
    }
    @Test void returnCostIsRecognizedInCreditPeriodAndZeroCostIsKnownRatherThanMissing() {
        update("UPDATE products SET cost_price=0 WHERE id=?",product);long shipment=delivered("2","2"),accountant=user("ACCOUNTANT"),invoice=issue(accountant,shipment);
        var reports=new SalesDebtReportService(source,clock);LocalDate day=MvpCommands.date(now);
        assertThat(reports.sales(manager,day,day,dealer,"").totals().get("margin_percent")).isEqualByComparingTo("100");
        update("UPDATE mvp_invoice_lines SET cost_amount=100000 WHERE invoice_id=?",invoice);
        long line=Sql.id(one("SELECT id FROM mvp_invoice_lines WHERE invoice_id=?",invoice).get("id"));var service=new ReturnService(source,clock);
        long returned=service.save(sales,0,0,key(),new ReturnService.Input(invoice,"Trả khác kỳ",List.of(new ReturnService.Line(line,BigDecimal.ONE,"DAMAGED"))));service.receive(warehouseManager(),returned,1,key(),true);
        Clock next=Clock.fixed(now.plus(Duration.ofDays(1)),ZoneOffset.UTC);new ReturnService(source,next).issueCredit(accountant,returned,2,key());
        var nextReports=new SalesDebtReportService(source,next);
        assertThat(nextReports.sales(manager,day,day,dealer,"").totals().get("cost")).isEqualByComparingTo("100000");
        var later=nextReports.sales(manager,day.plusDays(1),day.plusDays(1),dealer,"");assertThat(later.totals().get("cost")).isEqualByComparingTo("-50000");assertThat(later.totals().get("gross_profit")).isEqualByComparingTo("-50000");
    }
    @Test void failedAuditRollsBackSnapshotAndReplayDoesNotRefreshCost() {
        long order=accepted("1");fulfillment.startPicking(worker,order,2,key());String command=key();
        failAudit();assertThatThrownBy(()->fulfillment.dispatch(worker,order,3,command,take(order,"1"),"")).isInstanceOf(IllegalStateException.class);
        assertThat(query("SELECT id FROM mvp_shipments WHERE order_id=?",order)).isEmpty();update("DROP TRIGGER story_audit_fail");
        long shipment=fulfillment.dispatch(worker,order,3,command,take(order,"1"),"");update("UPDATE products SET cost_price=999 WHERE id=?",product);
        assertThat(fulfillment.dispatch(worker,order,3,command,take(order,"1"),"")).isEqualTo(shipment);
        assertThat((BigDecimal)one("SELECT unit_cost_snapshot FROM mvp_shipment_lines WHERE shipment_id=?",shipment).get("unit_cost_snapshot")).isEqualByComparingTo("1");
    }
}
