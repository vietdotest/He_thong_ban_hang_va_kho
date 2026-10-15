package vn.codegym.salesinventory.dao;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;

class BusinessFiltersAcceptanceIT extends MvpWorkflowSupport {
    @Test void warehouseOrderListUsesThePersistedOrderNumber() {
        long order=accepted("1");
        String number=Sql.text(one("SELECT number FROM orders WHERE id=?",order).get("number"));
        assertThat(number).startsWith("DH-");
        assertThat(fulfillment.orders(worker)).filteredOn(row->Sql.id(row.get("id"))==order)
            .singleElement().satisfies(row->assertThat(row.get("number")).isEqualTo(number));
    }
    @Test void filteredTotalSpansPagesAndUsesVietnamCreationDayAndCurrentAssignment() {
        long area=Sql.id(one("SELECT territory_id FROM dealers WHERE id=?",dealer).get("territory_id"));
        for(int i=0;i<21;i++) {
            long id=orders.save(sales,0,new OrderService.Input(dealer,address,LocalDate.of(2027,1,4),0,key(),List.of(new OrderService.LineInput(product,unit,BigDecimal.ONE))));
            update("UPDATE orders SET status='SUBMITTED',net_total=100.1234,number=?,created_at=? WHERE id=?","CHECK-"+id,java.sql.Timestamp.from(Instant.parse("2027-01-02T17:00:00Z")),id);
        }
        LocalDate day=LocalDate.of(2027,1,3);
        var filter=new OrderService.Filter("CHECK-","SUBMITTED",dealer,sales,area,day,day);
        var first=orders.searchReport(sales,filter,new PageRequest(1,20));
        var last=orders.searchReport(sales,filter,new PageRequest(2,20));
        assertThat(first.page().totalItems()).isEqualTo(21);assertThat(first.page().items()).hasSize(20);assertThat(last.page().items()).hasSize(1);
        assertThat(first.totalAmount()).isEqualByComparingTo("2102.5914");assertThat(last.totalAmount()).isEqualByComparingTo(first.totalAmount());
        assertThat(orders.searchReport(sales,new OrderService.Filter("CHECK-","",dealer,null,null,day.minusDays(1),day.minusDays(1)),new PageRequest(1,20)).page().items()).isEmpty();
        long other=user("SALES");assertThat(orders.searchReport(other,filter,new PageRequest(1,20)).page().items()).isEmpty();assertThat(orders.searchReport(other,filter,new PageRequest(1,20)).totalAmount()).isEqualByComparingTo("0");
        update("UPDATE dealers SET primary_staff_id=? WHERE id=?",other,dealer);
        assertThat(orders.searchReport(manager,filter,new PageRequest(1,20)).page().items()).isEmpty();
        assertThat(orders.filterOptions(sales).get("dealers")).isEmpty();
    }
    @Test void categoryIncludesDescendantsAndAvailabilityHonorsExpiredHoldsWithoutLeakingPhysicalStock() {
        long parent=category(manager),child=new CategoryService(source).save(manager,0,"CH-"+key(),"Con",parent,0);
        update("UPDATE products SET category_id=? WHERE id=?",child,product);
        accepted("20");
        assertThat(stock.stock(sales,warehouse,"",parent,"AVAILABLE")).isEmpty();
        var none=stock.stock(sales,warehouse,"",parent,"UNAVAILABLE");assertThat(none).hasSize(1);assertThat(none.get(0)).doesNotContainKeys("onHand","reserved");
        var later=new StockService(source,Clock.fixed(now.plus(Duration.ofHours(25)),ZoneOffset.UTC));
        assertThat(later.stock(sales,warehouse,"",parent,"AVAILABLE")).hasSize(1);
        assertThatThrownBy(()->stock.stock(user("WAREHOUSE"),warehouse,"",parent,"UNAVAILABLE")).isInstanceOf(SecurityException.class);
        assertThat(stock.stock(sales,warehouse,"",category(manager),"")).isEmpty();
    }
}
