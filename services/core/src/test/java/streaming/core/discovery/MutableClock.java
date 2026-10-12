package streaming.core.discovery;

import java.time.Duration;
import java.time.Instant;
import streaming.core.discovery.application.DiscoveryClock;

/** Test clock: freshness windows are exercised by moving time, never by sleeping. */
public final class MutableClock implements DiscoveryClock {
    private volatile Instant now;

    public MutableClock(Instant start) { this.now=start; }

    @Override public Instant now() { return now; }
    public void set(Instant instant) { this.now=instant; }
    public void advance(Duration duration) { this.now=now.plus(duration); }
}
