package vn.codegym.salesinventory.service;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PricingServiceTest {
    final LocalDate day=LocalDate.of(2026,10,3);
    @Test void dateAndNameBoundaries(){assertThat(PricingService.versionErrors(" N ".repeat(1),day,day)).isEmpty();assertThat(PricingService.versionErrors("N".repeat(150),day,day.plusDays(1))).isEmpty();assertThat(PricingService.versionErrors("N".repeat(151),day,day.minusDays(1))).containsKeys("name","to");assertThat(PricingService.versionErrors(" ",null,null)).containsKeys("name","from","to");assertThat(PricingService.versionErrors("N",LocalDate.of(999,1,1),LocalDate.of(10000,1,1))).containsKeys("from","to");}
    @Test void pricePrecisionAndMaliciousExponent(){assertThat(PricingService.priceErrors(new BigDecimal("999999999999999.9999"),BigDecimal.ZERO)).isEmpty();assertThat(PricingService.priceErrors(new BigDecimal("1000000000000000"),new BigDecimal("-1"))).containsKeys("selling","floor");assertThat(PricingService.priceErrors(new BigDecimal("1.00001"),BigDecimal.ZERO)).containsKey("selling");assertThat(PricingService.priceErrors(new BigDecimal("1E999999999"),BigDecimal.ZERO)).containsKey("selling");assertThat(PricingService.priceErrors(null,null)).containsKeys("selling","floor");}
    @Test void floorComparisonAndApproval(){assertThat(PricingService.priceErrors(BigDecimal.ONE,BigDecimal.TEN)).containsKey("selling");var quote=new PricingService.Quote(1,1,1,BigDecimal.TEN,BigDecimal.ONE,day);assertThat(quote.requiresApproval(new BigDecimal("0.9999"))).isTrue();assertThat(quote.requiresApproval(BigDecimal.ONE)).isFalse();assertThat(quote.requiresApproval(BigDecimal.TEN)).isFalse();assertThatThrownBy(()->quote.requiresApproval(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);}
}
