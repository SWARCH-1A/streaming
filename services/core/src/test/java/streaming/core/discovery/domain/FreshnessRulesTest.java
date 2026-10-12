package streaming.core.discovery.domain;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FreshnessRulesTest {
    private static final Instant NOW=Instant.parse("2026-10-06T12:00:00Z");

    @Test void anObservationIsFreshUpToExactlyFiveSeconds() {
        assertThat(FreshnessRules.fresh(NOW,NOW)).isTrue();
        assertThat(FreshnessRules.fresh(NOW.minusSeconds(5),NOW)).isTrue();
        assertThat(FreshnessRules.fresh(NOW.minusSeconds(5).minus(Duration.ofNanos(1000)),NOW)).isFalse();
        assertThat(FreshnessRules.fresh(NOW.minusSeconds(60),NOW)).isFalse();
    }

    @Test void aMissingObservationIsNeverFresh() {
        assertThat(FreshnessRules.fresh(null,NOW)).isFalse();
    }

    @Test void aTimestampFromTheFutureBeyondTheSkewToleranceIsNotTrusted() {
        assertThat(FreshnessRules.fresh(NOW.plusSeconds(5),NOW)).isTrue();
        assertThat(FreshnessRules.fresh(NOW.plusSeconds(5).plus(Duration.ofNanos(1000)),NOW)).isFalse();
        assertThat(FreshnessRules.fresh(NOW.plusSeconds(3600),NOW)).isFalse();
    }

    @Test void sqlBoundsAreTheSameInclusiveWindow() {
        assertThat(FreshnessRules.oldestFresh(NOW)).isEqualTo(NOW.minusSeconds(5));
        assertThat(FreshnessRules.newestFresh(NOW)).isEqualTo(NOW.plusSeconds(5));
    }
}
