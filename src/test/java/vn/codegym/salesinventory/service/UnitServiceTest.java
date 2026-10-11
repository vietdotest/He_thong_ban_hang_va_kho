package vn.codegym.salesinventory.service;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.validation.CatalogValidation;
import static org.assertj.core.api.Assertions.*;
class UnitServiceTest {
    @Test void nameFactorAndPrecisionBoundariesAreValidatedByField() {
        assertThat(UnitService.errors(" Thùng ",new BigDecimal("0.000001"),1,0)).isEmpty();
        assertThat(UnitService.errors("N".repeat(50),new BigDecimal("9999999999999.999999"),1,1)).isEmpty();
        assertThat(UnitService.errors("N".repeat(51),new BigDecimal("10000000000000"),1,0)).containsKeys("name","factor");
        assertThat(UnitService.errors("",BigDecimal.ZERO,0,-1)).containsKeys("name","factor","form");
    }
    @Test void rejectsNegativeExcessFractionNullAndHugeExponentWithoutExpansion() {
        for(BigDecimal factor:new BigDecimal[]{null,new BigDecimal("-1"),new BigDecimal("0.0000001"),new BigDecimal("1E+2147483647")})assertThat(UnitService.errors("Thùng",factor,1,0)).containsKey("factor");
        assertThatThrownBy(()->CatalogValidation.decimal("1e2147483647",6,true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyConversionConstructorDoesNotInventHistoricalWarehouse() {
        var value=new UnitService.Conversion(1,2,"Thùng",BigDecimal.ONE,BigDecimal.TEN,1,BigDecimal.TEN);assertThat(value.warehouseId()).isNull();
    }
}
