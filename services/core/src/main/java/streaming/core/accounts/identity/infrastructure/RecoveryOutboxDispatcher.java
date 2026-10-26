package streaming.core.accounts.identity.infrastructure;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.accounts.identity.application.RecoveryPublisher;
import streaming.core.accounts.identity.application.RecoveryStore;

@Component
public class RecoveryOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(RecoveryOutboxDispatcher.class);
    private final RecoveryStore store;
    private final TransactionTemplate transactions;
    private final ObjectProvider<RecoveryPublisher> publishers;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService publisherThread = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("recovery-outbox").factory());

    public RecoveryOutboxDispatcher(RecoveryStore store, TransactionTemplate transactions,
            ObjectProvider<RecoveryPublisher> publishers, @Value("${core.recovery.enabled:false}") boolean enabled) {
        this.store = store; this.transactions = transactions; this.publishers = publishers; this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${core.recovery.publish-interval:PT1S}")
    public void dispatch() {
        if (!enabled) return;
        RecoveryPublisher publisher = publishers.getIfAvailable();
        if (publisher == null) return;
        if (!running.compareAndSet(false, true)) return;
        publisherThread.execute(() -> {
            try { drain(publisher); }
            finally { running.set(false); }
        });
    }

    // Broker I/O uses its own thread and cannot stall Discovery's shared maintenance scheduler.
    void drain(RecoveryPublisher publisher) {
        UUID lease = UUID.randomUUID();
        Instant now = Instant.now();
        var publications = transactions.execute(status -> store.leasePublications(lease, now, now.plusSeconds(60), 10));
        for (var publication : publications) {
            try {
                // No SQL transaction is held while waiting for the broker.
                publisher.publish(publication);
                store.published(publication.eventId(), lease, Instant.now());
                log.info("event=recovery_published component=core eventId={} attempt={} result=confirmed",
                        publication.eventId(), publication.attempts());
            } catch (RuntimeException error) {
                long delay = new long[]{1, 2, 5, 10}[Math.min(Math.max(publication.attempts() - 1, 0), 3)];
                store.retry(publication.eventId(), lease, Instant.now().plusSeconds(delay));
                // Transport exception messages may contain connection credentials.
                log.warn("event=recovery_publish component=core eventId={} attempt={} result=retry code={}",
                        publication.eventId(), publication.attempts(), error.getClass().getSimpleName());
            }
        }
    }

    @PreDestroy public void close() { publisherThread.shutdownNow(); }
}
