package vn.codegym.salesinventory.service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class OrderValidationTest {
    OrderService.Input input(BigDecimal quantity){return new OrderService.Input(1,2,LocalDate.of(2030,1,1),0,UUID.randomUUID().toString(),List.of(new OrderService.LineInput(3,4,quantity)));}
    @Test void requiresPositiveQuantityWithinDatabasePrecision(){for(var q:List.of(BigDecimal.ZERO,new BigDecimal("-1"),new BigDecimal("0.0000001"),new BigDecimal("1e1000")))assertThat(OrderService.errors(input(q))).containsKey("quantity0");assertThat(OrderService.errors(input(new BigDecimal("9999999999999.999999")))).isEmpty();}
    @Test void requiresDealerAddressDateAndLines(){var bad=new OrderService.Input(0,0,null,-1,"",List.of());assertThat(OrderService.errors(bad)).containsKeys("dealer","address","delivery","form","lines");assertThat(OrderService.errors(null)).containsKey("form");}
    @Test void capsLineCountAndRejectsInvalidProductUnit(){var many=new OrderService.Input(1,2,LocalDate.of(2030,1,1),0,"",Collections.nCopies(101,new OrderService.LineInput(3,4,BigDecimal.ONE)));assertThat(OrderService.errors(many)).containsKey("lines");assertThat(OrderService.errors(new OrderService.Input(1,2,LocalDate.of(2030,1,1),0,"",List.of(new OrderService.LineInput(0,0,BigDecimal.ONE))))).containsKey("line0");}
}
