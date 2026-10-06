package streaming.core.discovery.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * An observation is fresh only while its producer-side timestamp is at most 5 s old. Reception time never
 * rejuvenates it. A timestamp more than 5 s in the future (clock skew) is not trusted either.
 */
public final class FreshnessRules {
    public static final Duration MAX_AGE=Duration.ofSeconds(5);
    private FreshnessRules() { }

    public static boolean fresh(Instant observedAt,Instant now) {
        return observedAt!=null && !observedAt.isBefore(oldestFresh(now)) && !observedAt.isAfter(newestFresh(now));
    }
    /** Inclusive lower bound used by SQL filters. */
    public static Instant oldestFresh(Instant now) { return now.minus(MAX_AGE); }
    /** Inclusive upper bound used by SQL filters. */
    public static Instant newestFresh(Instant now) { return now.plus(MAX_AGE); }
}
