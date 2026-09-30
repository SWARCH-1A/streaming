package streaming.channels.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import streaming.channels.application.ChannelStore.StreamProjection;
import streaming.channels.domain.ChannelRules;

/**
 * Keeps the read projection of the channel's live state. Streaming stays the source of truth: events are
 * deduplicated by eventId and applied only when newer (streamGeneration, then sessionVersion; metadataVersion;
 * countVersion inside the current session). The transport that delivers the events belongs to Integration.
 */
@Service
public class StreamProjectionService {
    private static final Logger log=LoggerFactory.getLogger(StreamProjectionService.class);
    private static final Set<String> LIFECYCLE=Set.of("StreamSessionStarted","StreamSessionAvailabilityChanged","StreamSessionEnded");
    private static final Set<String> STATUSES=Set.of("PREPARING","LIVE","RECONNECT_GRACE","ENDED");
    private static final Set<String> AVAILABILITIES=Set.of("PLAYABLE","RECONNECTING","OFFLINE");
    private final ChannelStore store;
    public StreamProjectionService(ChannelStore store) { this.store=store; }

    @Transactional
    public String apply(StreamEvent event) {
        if(event==null || event.eventId()==null || !event.eventId().matches("[A-Za-z0-9._:-]{1,80}") || event.eventType()==null || event.payload()==null)
            throw invalid("El evento no cumple el sobre común.");
        String type=event.eventType(); Payload p=event.payload();
        if(!LIFECYCLE.contains(type) && !"StreamMetadataUpdated".equals(type) && !"ViewerCountChanged".equals(type)) return result(event,"IGNORED");
        if(!store.markEventProcessed(event.eventId(),type,Instant.now())) return result(event,"DUPLICATE");
        Instant now=Instant.now();
        if(LIFECYCLE.contains(type)) {
            requireIds(p.channelId(),p.streamId(),p.sessionId());
            if(p.streamGeneration()==null || p.sessionVersion()==null || !STATUSES.contains(p.status()) || !AVAILABILITIES.contains(p.availability()))
                throw invalid("El evento de sesión requiere streamGeneration, sessionVersion, status y availability válidos.");
            store.lockKey("projection:"+p.channelId());
            StreamProjection current=store.findProjection(p.channelId()).orElse(StreamProjection.offline(p.channelId(),now));
            if(!ChannelRules.isNewerLifecycle(current.streamGeneration(),current.sessionId(),current.sessionVersion(),p.streamGeneration(),p.sessionId(),p.sessionVersion()))
                return result(event,"STALE");
            boolean sameSession=p.sessionId().equals(current.sessionId());
            store.saveProjection(new StreamProjection(p.channelId(),p.streamId(),p.sessionId(),p.streamGeneration(),p.sessionVersion(),p.status(),p.availability(),
                    current.title(),current.categoryId(),current.tagIds(),current.metadataVersion(),sameSession?current.viewerCount():0,sameSession?current.countVersion():0,now));
            return result(event,"APPLIED");
        }
        if("StreamMetadataUpdated".equals(type)) {
            requireIds(p.channelId(),p.streamId());
            if(p.metadataVersion()==null || p.title()==null || p.title().codePointCount(0,p.title().length())>100 || (p.categoryId()!=null && !ChannelRules.isExternalId(p.categoryId())))
                throw invalid("El evento de metadata requiere metadataVersion y título válidos.");
            List<String> tags=p.tagIds()==null?List.of():p.tagIds();
            if(tags.size()>5 || !tags.stream().allMatch(ChannelRules::isExternalId)) throw invalid("tagIds admite hasta cinco IDs válidos.");
            store.lockKey("projection:"+p.channelId());
            StreamProjection current=store.findProjection(p.channelId()).orElse(StreamProjection.offline(p.channelId(),now));
            if(p.metadataVersion()<=current.metadataVersion()) return result(event,"STALE");
            store.saveProjection(new StreamProjection(current.channelId(),p.streamId(),current.sessionId(),current.streamGeneration(),current.sessionVersion(),current.status(),
                    current.availability(),p.title(),p.categoryId(),List.copyOf(tags),p.metadataVersion(),current.viewerCount(),current.countVersion(),now));
            return result(event,"APPLIED");
        }
        requireIds(p.sessionId());
        if(p.countVersion()==null || p.viewerCount()==null || p.viewerCount()<0) throw invalid("El conteo requiere countVersion y viewerCount no negativo.");
        Optional<StreamProjection> found=store.findProjectionBySession(p.sessionId());
        if(found.isEmpty()) return result(event,"IGNORED");
        store.lockKey("projection:"+found.get().channelId());
        StreamProjection current=store.findProjection(found.get().channelId()).orElseThrow();
        if(!p.sessionId().equals(current.sessionId()) || p.countVersion()<=current.countVersion()) return result(event,"STALE");
        store.saveProjection(new StreamProjection(current.channelId(),current.streamId(),current.sessionId(),current.streamGeneration(),current.sessionVersion(),current.status(),
                current.availability(),current.title(),current.categoryId(),current.tagIds(),current.metadataVersion(),p.viewerCount(),p.countVersion(),now));
        return result(event,"APPLIED");
    }

    private static String result(StreamEvent event,String outcome) {
        log.info("event=stream_event_projection component=channels eventId={} eventType={} result={}",event.eventId(),event.eventType(),outcome);
        return outcome;
    }
    private static void requireIds(String... ids) { for(String id:ids) if(!ChannelRules.isExternalId(id)) throw invalid("El evento contiene identificadores inválidos."); }
    private static ChannelException invalid(String message) { return new ChannelException(HttpStatus.BAD_REQUEST,"INVALID_EVENT",message); }

    /** Common internal event envelope from the transversal contract. */
    public record StreamEvent(String eventId,String eventType,Integer schemaVersion,String aggregateId,Long sequence,String occurredAtUtc,String producer,Payload payload) { }
    public record Payload(String sessionId,String streamId,Long streamGeneration,String channelId,String status,String availability,Long sessionVersion,
            Long metadataVersion,String title,String categoryId,List<String> tagIds,Long countVersion,Long viewerCount) { }
}
