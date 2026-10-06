package streaming.core.discovery.infrastructure;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import streaming.core.discovery.application.DiscoveryClock;

/** Wall clock at PostgreSQL's microsecond precision, so a value written and read back compares equal. */
@Component
public class SystemDiscoveryClock implements DiscoveryClock {
    @Override public Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
