package streaming.core.discovery.application;

import java.time.Instant;

/** Injectable wall clock so freshness windows can be tested without sleeping. */
public interface DiscoveryClock {
    Instant now();
}
