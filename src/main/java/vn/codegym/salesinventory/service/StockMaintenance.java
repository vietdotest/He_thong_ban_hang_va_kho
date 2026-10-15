package vn.codegym.salesinventory.service;

import java.time.Clock;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One maintenance loop per web application, stopped before its connection pool. */
public final class StockMaintenance implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(StockMaintenance.class);
    private final StockService stock;
    private ScheduledExecutorService executor;
    public StockMaintenance(DataSource source,Clock clock){stock=new StockService(source,clock);}
    public synchronized void start(){
        if(executor!=null)return;
        stock.expireHolds();
        executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"stock-hold-expiration");t.setDaemon(true);return t;});
        executor.scheduleWithFixedDelay(()->{try{stock.expireHolds();}catch(RuntimeException e){LOG.error("Không thể ghi nhận hết hạn giữ hàng; sẽ thử lại sau một phút",e);}},1,1,TimeUnit.MINUTES);
    }
    @Override public synchronized void close(){if(executor!=null){executor.shutdownNow();try{executor.awaitTermination(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}executor=null;}}
}
