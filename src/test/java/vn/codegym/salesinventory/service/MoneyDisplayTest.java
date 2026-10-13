package vn.codegym.salesinventory.service;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class MoneyDisplayTest {
    @Test void wholeDongHalfUpNeverUsesBinaryFloatingPoint() {
        assertThat(MoneyDisplay.format("2280.0023")).isEqualTo("2.280");
        assertThat(MoneyDisplay.format("2280.4999")).isEqualTo("2.280");
        assertThat(MoneyDisplay.format("2280.5000")).isEqualTo("2.281");
        assertThat(MoneyDisplay.format("999999999999999.5000")).isEqualTo("1.000.000.000.000.000");
        BigDecimal exact=new BigDecimal("0.4999");assertThat(MoneyDisplay.format(exact)).isEqualTo("0");assertThat(exact.toPlainString()).isEqualTo("0.4999");
    }
}
