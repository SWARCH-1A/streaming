package streaming.core.discovery.application;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounded housekeeping: delivered-event identities past the retry window and expired ranking snapshots. */
@Service
public class DiscoveryMaintenance {
    private static final Logger log=LoggerFactory.getLogger(DiscoveryMaintenance.class);
    private static final int BATCH=5_000;
    private static final int MAX_BATCHES=20;
    private final ProjectionStore store;
    private final RankingSnapshots snapshots;
    private final DiscoveryClock clock;
    private final Duration inboxRetention;

    public DiscoveryMaintenance(ProjectionStore store,RankingSnapshots snapshots,DiscoveryClock clock,
            @Value("${discovery.inbox-retention:PT1H}") Duration inboxRetention) {
        this.store=store; this.snapshots=snapshots; this.clock=clock; this.inboxRetention=inboxRetention;
    }

    @Scheduled(fixedDelayString="PT1M",initialDelayString="PT1M")
    public void purge() {
        try {
            Instant now=clock.now();
            int inbox=0, expired=0, removed;
            int batches=0;
            do { removed=store.purgeInbox(now.minus(inboxRetention),BATCH); inbox+=removed; } while(removed==BATCH && ++batches<MAX_BATCHES);
            batches=0;
            do { removed=snapshots.purgeExpired(now,BATCH); expired+=removed; } while(removed==BATCH && ++batches<MAX_BATCHES);
            if(inbox>0 || expired>0) log.info("event=discovery_purged component=core module=discovery inboxEvents={} rankingSnapshots={}",inbox,expired);
        } catch(RuntimeException e) {
            log.warn("event=discovery_purge_failed component=core module=discovery reason={}",e.getClass().getSimpleName());
        }
    }
}
