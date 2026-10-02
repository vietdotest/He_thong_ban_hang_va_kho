package vn.codegym.salesinventory.config;

import static org.assertj.core.api.Assertions.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.dao.Sql;

class VietnamTimeTest {
    @Test void localDatabaseTimeMeansVietnamNotUtc() {
        assertThat(Sql.instant(LocalDateTime.parse("2026-10-03T01:28:46")))
                .isEqualTo(Instant.parse("2026-10-02T18:28:46Z"));
    }

    @Test void timestampInstantIsNotShiftedAgain() {
        Instant instant = Instant.parse("2026-10-02T18:28:46Z");
        assertThat(Sql.instant(Timestamp.from(instant))).isEqualTo(instant);
    }

    @Test void oldUrlCannotOverrideConnectionZone() {
        assertThat(DatabaseFactory.timeZoneUrl("jdbc:mysql://localhost/test?serverTimezone=UTC&connectionTimeZone=UTC&preserveInstants=false&forceConnectionTimeZoneToSession=true&useSSL=false", VietnamTime.ZONE.getId()))
                .isEqualTo("jdbc:mysql://localhost/test?useSSL=false&connectionTimeZone=Asia/Ho_Chi_Minh&preserveInstants=true");
    }

    @Test void migrationConnectionStaysUtcEvenWithVietnamUrl() {
        assertThat(DatabaseFactory.timeZoneUrl("jdbc:mysql://localhost/test?connectionTimeZone=Asia/Ho_Chi_Minh", "UTC"))
                .isEqualTo("jdbc:mysql://localhost/test?connectionTimeZone=UTC&preserveInstants=true");
    }
}
