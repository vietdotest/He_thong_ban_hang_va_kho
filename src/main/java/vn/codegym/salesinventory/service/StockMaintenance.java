package vn.codegym.salesinventory.service;

import java.time.Clock;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S5-06: Định kỳ giải phóng giữ hàng hết hạn; dừng tác vụ trước khi đóng kết nối dữ liệu.
 */
public final class StockMaintenance implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StockMaintenance.class);

    private final StockService stock;

    private ScheduledExecutorService executor;

    public StockMaintenance(DataSource source, Clock clock) {
        stock = new StockService(source, clock);
    }

    // Khởi chạy tác vụ nền theo lịch.
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        stock.expireHolds();
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            var thread = new Thread(r, "stock-hold-expiration");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.scheduleWithFixedDelay(
                () -> {
                    try {
                        stock.expireHolds();
                    } catch (RuntimeException exception) {
                        LOG.error(
                                "Không thể ghi nhận hết hạn giữ hàng; sẽ thử lại sau một phút",
                                exception);
                    }
                },
                1,
                1,
                TimeUnit.MINUTES);
    }

    // Dừng tác vụ và đóng tài nguyên đang sử dụng.
    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }
}
