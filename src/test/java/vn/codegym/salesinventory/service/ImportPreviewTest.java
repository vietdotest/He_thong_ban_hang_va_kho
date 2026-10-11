package vn.codegym.salesinventory.service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ImportPreviewTest {
    private final Instant now = Instant.parse("2026-10-02T00:00:00Z");
    private ImportPreview preview() { return new ImportPreview(12, List.of(new ImportPreview.Line(2,List.of("data"),"Tạo mới","")), now); }

    @Test void rejectsWrongOwnerOrTokenWithoutConsumingValidPreview() {
        var value = preview();
        assertThatThrownBy(() -> value.claim(13,value.getToken(),now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value.claim(12,"forged",now)).isInstanceOf(IllegalArgumentException.class);
        value.claim(12,value.getToken(),now);
        assertThatThrownBy(() -> value.claim(12,value.getToken(),now)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void expiryIsExclusiveAtThirtyMinutes() {
        var value = preview(); value.claim(12,value.getToken(),now.plusSeconds(1799));
        var expired = preview();
        assertThatThrownBy(() -> expired.claim(12,expired.getToken(),now.plusSeconds(1800))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concurrentConfirmationCanClaimOnlyOnce() throws Exception {
        var value = preview(); var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        Callable<Boolean> claim = () -> { barrier.await(); try { value.claim(12,value.getToken(),now); return true; } catch (IllegalArgumentException e) { return false; } };
        try { var a = pool.submit(claim); var b = pool.submit(claim); assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false); }
        finally { pool.shutdownNow(); }
    }
}
