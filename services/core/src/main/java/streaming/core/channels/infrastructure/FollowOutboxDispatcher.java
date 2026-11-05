package streaming.core.channels.infrastructure;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.channels.application.FollowPublisher;
import streaming.core.channels.application.FollowStore;

/** Drains channels.follow_outbox. Without a broker publisher (HTTP transport) events wait in the table. */
@Component
public class FollowOutboxDispatcher {
    private static final Logger log=LoggerFactory.getLogger(FollowOutboxDispatcher.class);
    private final FollowStore store;
    private final TransactionTemplate transactions;
    private final ObjectProvider<FollowPublisher> publishers;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService publisherThread=Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("follow-outbox").factory());

    public FollowOutboxDispatcher(FollowStore store,TransactionTemplate transactions,ObjectProvider<FollowPublisher> publishers) {
        this.store=store; this.transactions=transactions; this.publishers=publishers;
    }

    @Scheduled(fixedDelayString="${core.follow.publish-interval:PT1S}")
    public void dispatch() {
        FollowPublisher publisher=publishers.getIfAvailable();
        if(publisher==null) return;
        if(!running.compareAndSet(false,true)) return;
        publisherThread.execute(()->{
            try { drain(publisher); }
            finally { running.set(false); }
        });
    }

    // Same contract as the recovery outbox: lease in SQL, wait for the broker outside any transaction.
    public void drain(FollowPublisher publisher) {
        UUID lease=UUID.randomUUID();
        Instant now=Instant.now();
        var publications=transactions.execute(status->store.leasePublications(lease,now,now.plusSeconds(60),20));
        for(var publication:publications) {
            try {
                publisher.publish(publication);
                store.published(publication.eventId(),lease,Instant.now());
                log.info("event=follow_published component=core eventId={} attempt={} result=confirmed",publication.eventId(),publication.attempts());
            } catch(RuntimeException error) {
                long delay=new long[]{1,2,5,10,30}[Math.min(Math.max(publication.attempts()-1,0),4)];
                store.retry(publication.eventId(),lease,Instant.now().plusSeconds(delay));
                log.warn("event=follow_publish component=core eventId={} attempt={} result=retry code={}",
                        publication.eventId(),publication.attempts(),error.getClass().getSimpleName());
            }
        }
    }

    @PreDestroy public void close() { publisherThread.shutdownNow(); }
}
