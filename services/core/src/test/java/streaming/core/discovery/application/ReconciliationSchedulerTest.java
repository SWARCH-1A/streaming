package streaming.core.discovery.application;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconciliationSchedulerTest {
    private static final long SECOND=1_000_000_000L;

    @Test void fixedRateTicksAreNotDroppedWhenThePreviousTickWasSlightlyLate() {
        var clock=new AtomicLong();var reconciler=mock(ProjectionReconciler.class);
        var scheduler=new ReconciliationScheduler(reconciler,Duration.ofSeconds(1),clock::get);
        // Spring's scheduled phase: first execution was 10 ms late; the next is on time.
        // A second start-to-start gate would skip the second tick and create a 2 s gap.
        assertThat(scheduler.poll(10_000_000)).isTrue();
        assertThat(scheduler.poll(SECOND)).isTrue();
        assertThat(scheduler.poll(2*SECOND)).isTrue();
        verify(reconciler,times(3)).runOnce();
    }
    @Test void anOverrunSuppressesCatchUpUntilOneIntervalAfterCompletion() {
        var clock=new AtomicLong();var reconciler=mock(ProjectionReconciler.class);
        when(reconciler.runOnce()).thenAnswer(c->{clock.addAndGet(5*SECOND);return ProjectionReconciler.Result.SUCCESS;});
        var scheduler=new ReconciliationScheduler(reconciler,Duration.ofSeconds(4),clock::get);
        assertThat(scheduler.poll(0)).isTrue();
        assertThat(scheduler.poll(5*SECOND)).isFalse();
        assertThat(scheduler.poll(8*SECOND)).isFalse();
        assertThat(scheduler.poll(9*SECOND)).isTrue();
        verify(reconciler,times(2)).runOnce();
    }
    @Test void negativeMonotonicClockAndExceptionsDoNotPermanentlyDisableScheduling() {
        var reconciler=mock(ProjectionReconciler.class);
        when(reconciler.runOnce()).thenThrow(new IllegalStateException("fixture")).thenReturn(ProjectionReconciler.Result.SUCCESS);
        var scheduler=new ReconciliationScheduler(reconciler,Duration.ofSeconds(4),()->-5*SECOND);
        assertThatThrownBy(()->scheduler.poll(-5*SECOND)).isInstanceOf(IllegalStateException.class);
        assertThat(scheduler.poll(-SECOND)).isTrue();
    }
    @Test void theIntervalMustBePositive() {
        for(Duration bad:new Duration[]{Duration.ZERO,Duration.ofSeconds(-1)})
            assertThatThrownBy(()->new ReconciliationScheduler(mock(ProjectionReconciler.class),bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
