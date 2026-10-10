package streaming.core.discovery.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starts a rebuild cut every {@code discovery.reconcile-interval} counted from the start of the previous one.
 * A fixed delay would add the duration of each cut to the cadence and eat the 5 s validity of the absence proof
 * (a cut proves absence only for 5 s after it was captured); a fixed rate would instead fire back to back to catch
 * up after a slow run. Polling a cheap clock check gives a steady start-to-start cadence without bursts.
 * With the default 4 s the proof stays continuous while a cut takes under about one second.
 */
@Component
public class ReconciliationScheduler {
    private final ProjectionReconciler reconciler;
    private final long intervalNanos;
    private boolean started;
    private long lastStartNanos;

    public ReconciliationScheduler(ProjectionReconciler reconciler,@Value("${discovery.reconcile-interval:PT4S}") Duration interval) {
        if(interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("discovery.reconcile-interval must be positive");
        this.reconciler=reconciler; this.intervalNanos=interval.toNanos();
    }

    @Scheduled(fixedDelayString="${discovery.reconcile-poll:PT0.1S}",initialDelayString="${discovery.reconcile-initial-delay:PT1S}")
    void poll() { poll(System.nanoTime()); }

    /** @return whether a cut was started on this poll. */
    synchronized boolean poll(long nowNanos) {
        if(started && nowNanos-lastStartNanos<intervalNanos) return false;
        started=true; lastStartNanos=nowNanos;
        reconciler.runOnce();
        return true;
    }
}
