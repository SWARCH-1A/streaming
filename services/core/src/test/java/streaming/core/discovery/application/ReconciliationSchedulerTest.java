package streaming.core.discovery.application;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReconciliationSchedulerTest {
    private static final long SECOND=1_000_000_000L;
    @Mock ProjectionReconciler reconciler;

    private ReconciliationScheduler scheduler(Duration interval) { return new ReconciliationScheduler(reconciler,interval); }

    @Test void theFirstPollStartsACutImmediatelyWhateverTheClockReads() {
        assertThat(scheduler(Duration.ofSeconds(4)).poll(-5*SECOND)).isTrue();
        verify(reconciler).runOnce();
    }

    @Test void cutsStartOneIntervalApartFromStartToStartNotFromCompletion() {
        var scheduler=scheduler(Duration.ofSeconds(4));
        assertThat(scheduler.poll(0)).isTrue();
        // however long the cut itself took, the next one is due 4 s after the previous START
        assertThat(scheduler.poll(3*SECOND+900_000_000L)).isFalse();
        assertThat(scheduler.poll(4*SECOND)).isTrue();
        assertThat(scheduler.poll(4*SECOND+100_000_000L)).isFalse();
        assertThat(scheduler.poll(8*SECOND)).isTrue();
        verify(reconciler,times(3)).runOnce();
    }

    @Test void aLongStallStartsOneCutNotABurstToCatchUp() {
        var scheduler=scheduler(Duration.ofSeconds(4));
        scheduler.poll(0);
        assertThat(scheduler.poll(60*SECOND)).isTrue();
        assertThat(scheduler.poll(60*SECOND+100_000_000L)).isFalse();
        assertThat(scheduler.poll(61*SECOND)).isFalse();
        assertThat(scheduler.poll(64*SECOND)).isTrue();
        verify(reconciler,times(3)).runOnce();
    }

    @Test void aCoarsePollingStillKeepsTheSteadyCadenceWithinOnePollPeriod() {
        var scheduler=scheduler(Duration.ofSeconds(4));
        int started=0;
        for(long t=0;t<=40*SECOND;t+=100_000_000L) if(scheduler.poll(t)) started++;
        assertThat(started).as("every 4 s over 40 s, first one at 0").isEqualTo(11);
    }

    @Test void theIntervalMustBePositive() {
        for(Duration bad:new Duration[]{Duration.ZERO,Duration.ofSeconds(-1)})
            assertThatThrownBy(()->scheduler(bad)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(reconciler);
    }
}
