package streaming.core.discovery.application;

import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Spring owns the fixed-rate phase. Only overruns need a cooldown to suppress catch-up. */
@Component
public class ReconciliationScheduler {
    private final ProjectionReconciler reconciler;
    private final long intervalNanos;
    private final LongSupplier ticker;
    private boolean coolingDown;
    private long resumeAtNanos;

    @Autowired
    public ReconciliationScheduler(ProjectionReconciler reconciler,@Value("${discovery.reconcile-interval:PT4S}") Duration interval) {
        this(reconciler,interval,System::nanoTime);
    }
    ReconciliationScheduler(ProjectionReconciler reconciler,Duration interval,LongSupplier ticker) {
        if(interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("discovery.reconcile-interval must be positive");
        this.reconciler=reconciler; this.intervalNanos=interval.toNanos();this.ticker=ticker;
    }

    @Scheduled(fixedRateString="${discovery.reconcile-interval:PT4S}",initialDelayString="${discovery.reconcile-initial-delay:PT1S}")
    void poll() { poll(ticker.getAsLong()); }

    synchronized boolean poll(long nowNanos) {
        if(coolingDown && nowNanos-resumeAtNanos<0) return false;
        coolingDown=false;
        long started=ticker.getAsLong();
        try { reconciler.runOnce(); }
        finally {
            long elapsed=ticker.getAsLong()-started;
            if(elapsed>=intervalNanos) {
                coolingDown=true;
                resumeAtNanos=nowNanos+elapsed+intervalNanos;
            }
        }
        return true;
    }
}
