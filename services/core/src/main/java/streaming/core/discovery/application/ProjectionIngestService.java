package streaming.core.discovery.application;

import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.discovery.application.ProjectionParser.ParsedEvent;
import streaming.core.discovery.domain.ApplyOutcome;
import tools.jackson.databind.JsonNode;

/**
 * Receives Streaming's {@code StreamDiscoverySnapshot} events. The inbox record and the projection change commit
 * together, so an acknowledged event is never half applied and a redelivery is recognized by its event ID.
 */
@Service
public class ProjectionIngestService {
    private static final Logger log=LoggerFactory.getLogger(ProjectionIngestService.class);
    private final ProjectionParser parser;
    private final ProjectionStore store;
    private final DiscoveryClock clock;
    private final TransactionTemplate transactions;

    public ProjectionIngestService(ProjectionParser parser,ProjectionStore store,DiscoveryClock clock,TransactionTemplate transactions) {
        this.parser=parser; this.store=store; this.clock=clock; this.transactions=transactions;
    }

    public enum Kind { ACCEPTED, DUPLICATE, CONFLICT }

    /** @param outcome how the projection reacted (ACCEPTED), or the conflict code (CONFLICT). */
    public record IngestResult(Kind kind,String eventId,String outcome) { }

    /** @throws DiscoveryException (422) when the event does not meet the contract. */
    public IngestResult ingest(JsonNode body) {
        ParsedEvent event=parser.parseEvent(body);
        Instant now=clock.now();
        String streamId=event.projection().streamId();
        long version=event.projection().projectionVersion();
        IngestResult result=Objects.requireNonNull(transactions.execute(status-> {
            if(!store.recordInbox(event.eventId(),event.eventHash(),streamId,version,now)) {
                String existing=store.inboxHash(event.eventId()).orElse(null);
                if(event.eventHash().equals(existing)) return new IngestResult(Kind.DUPLICATE,event.eventId(),"DUPLICATE");
                store.recordConflict(event.eventId(),streamId,version,"EVENT_ID_REUSED",existing,event.eventHash(),
                        body.get("payload").toString(),now);
                return new IngestResult(Kind.CONFLICT,event.eventId(),"EVENT_ID_CONFLICT");
            }
            ApplyOutcome outcome=store.apply(event.projection(),now,now);
            switch(outcome) {
                case APPLIED -> { return new IngestResult(Kind.ACCEPTED,event.eventId(),"APPLIED"); }
                case IGNORED_OLDER -> { store.setInboxOutcome(event.eventId(),"IGNORED_OLDER"); return new IngestResult(Kind.ACCEPTED,event.eventId(),"IGNORED_OLDER"); }
                case IGNORED_SAME -> { store.setInboxOutcome(event.eventId(),"IGNORED_SAME"); return new IngestResult(Kind.ACCEPTED,event.eventId(),"IGNORED_SAME"); }
                default -> {
                    store.setInboxOutcome(event.eventId(),"CONFLICT");
                    String reason=outcome==ApplyOutcome.CONFLICT_CHANNEL?"CHANNEL_MISMATCH":"SAME_VERSION_DIFFERENT_CONTENT";
                    store.recordConflict(event.eventId(),streamId,version,reason,store.projectionHash(streamId).orElse(null),
                            event.projection().contentHash(),body.get("payload").toString(),now);
                    return new IngestResult(Kind.CONFLICT,event.eventId(),"PROJECTION_CONFLICT");
                }
            }
        }));
        if(result.kind()==Kind.CONFLICT) log.warn("event=discovery_event_conflict component=core module=discovery eventId={} streamId={} version={} code={}",
                result.eventId(),streamId,version,result.outcome());
        return result;
    }
}
