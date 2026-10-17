package vn.codegym.salesinventory.service;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TimeDisplayTest {
    @Test void displaysVietnamSecondsWithoutFraction(){
        assertThat(TimeDisplay.format(Instant.parse("2026-10-16T17:00:01.123456Z"))).isEqualTo("17/10/2026 00:00:01");
        assertThat(TimeDisplay.format(LocalDateTime.of(2026,10,17,12,30,15,123456000))).isEqualTo("17/10/2026 12:30:15");
        assertThat(TimeDisplay.format(null)).isEmpty();
    }
}
