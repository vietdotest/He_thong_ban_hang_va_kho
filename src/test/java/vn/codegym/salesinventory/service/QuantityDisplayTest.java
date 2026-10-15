package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QuantityDisplayTest {
    @Test void preservesSmallDecimalsAndLargeIntegerPrecision() {
        assertThat(QuantityDisplay.format(new BigDecimal("12345678901234567890.000000000001"))).isEqualTo("12.345.678.901.234.567.890,000000000001");
        assertThat(QuantityDisplay.format("1.000000000000")).isEqualTo("1");
        assertThat(QuantityDisplay.format("0.000000000001")).isEqualTo("0,000000000001");
        assertThat(QuantityDisplay.plain("1.250000")).isEqualTo("1.25");
        assertThat(QuantityDisplay.plain("invalid")).isEqualTo("invalid");
    }
}
