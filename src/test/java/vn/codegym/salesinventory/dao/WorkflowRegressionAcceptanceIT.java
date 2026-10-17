package vn.codegym.salesinventory.dao;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;

class WorkflowRegressionAcceptanceIT extends MvpWorkflowSupport {
    OrderService.Input input(LocalDate date,String creationKey,long version){return new OrderService.Input(dealer,address,date,version,creationKey,List.of(new OrderService.LineInput(product,unit,BigDecimal.ONE)));}
    @Test void pastDeliveryIsRejectedOnNewSaveEditAndSubmitButSuccessfulReplayStillWorks(){
        var today=now.atZone(vn.codegym.salesinventory.config.VietnamTime.ZONE).toLocalDate();String creation=key();
        assertThatThrownBy(()->orders.save(sales,0,input(today.minusDays(1),key(),0))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ngày giao");
        long id=orders.save(sales,0,input(today,creation,0));
        assertThatThrownBy(()->orders.save(sales,id,input(today.minusDays(1),creation,1))).isInstanceOf(IllegalArgumentException.class);
        var later=new OrderService(source,Clock.fixed(now.plus(Duration.ofDays(1)),ZoneOffset.UTC));
        assertThat(later.save(sales,0,input(today,creation,0))).isEqualTo(id);
        assertThat(later.quote(sales,id).errors()).containsKey("delivery");
        assertThat(later.submit(sales,id,1,"",key(),false).submitted()).isFalse();
        assertThat(orders.find(sales,id)).containsEntry("status","DRAFT");
    }
    @Test void holdDisplayTracksExpiryAndProtectedPickingWithoutWaitingForScheduler(){
        long id=accepted("2");var active=orders.find(sales,id);
        assertThat(active).containsEntry("can_renew_holds",false);
        assertThat(((Map<?,?>)active.get("holds")).get("hasActive")).isEqualTo(true);
        var later=new OrderService(source,Clock.fixed(now.plus(Duration.ofHours(25)),ZoneOffset.UTC));
        var expired=later.find(sales,id);assertThat(expired).containsEntry("can_renew_holds",true);
        assertThat(((Map<?,?>)expired.get("holds")).get("status").toString()).contains("hết hạn");
        fulfillment.startPicking(worker,id,2,key());
        assertThat(later.find(sales,id)).containsEntry("can_renew_holds",false);
    }
    @Test void takeoverHistoryProjectsExistingAuditAndDoesNotDuplicateIt(){
        long id=orders.save(sales,0,input(LocalDate.of(2027,1,4),key(),0));orders.takeover(manager,id,1,"Bàn giao phụ trách");
        @SuppressWarnings("unchecked")var history=(List<Map<String,Object>>)orders.find(manager,id).get("history");
        assertThat(history).filteredOn(h->"ORDER_DRAFT_TAKEN_OVER".equals(h.get("event_type"))).singleElement().satisfies(h->assertThat(h).containsEntry("reason","Bàn giao phụ trách").containsEntry("label","Tiếp quản nháp"));
        assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE object_type='ORDER' AND object_id=? AND event_type='ORDER_DRAFT_TAKEN_OVER'",id)).isEqualTo(1);
    }
    @Test void creditApprovalDisplaysExactExcessWithoutPretendingPriceFailed(){
        update("UPDATE dealers SET credit_limit=50000 WHERE id=?",dealer);
        long id=orders.save(sales,0,input(LocalDate.of(2027,1,4),key(),0));var quote=orders.quote(sales,id);
        assertThat(quote.needsApproval()).isTrue();var display=quote.reviewDisplay();assertThat(display).containsEntry("priceException",false);
        @SuppressWarnings("unchecked")var credit=(Map<String,Object>)display.get("creditReview");assertThat(credit).containsEntry("exceeds",true).containsEntry("excess",MoneyDisplay.format(new BigDecimal("50000")));
        orders.submit(sales,id,1,quote.token(),key(),false);orders.returnForEdit(manager,id,2,key(),"Sửa điều kiện",false);
        assertThat(((Map<?,?>)orders.find(sales,id).get("approval")).get("status")).isEqualTo("RETURNED");
    }
}
