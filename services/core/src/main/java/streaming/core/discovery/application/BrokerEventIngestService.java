package streaming.core.discovery.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.discovery.domain.ApplyOutcome;
import tools.jackson.databind.JsonNode;

/** P2 inbox and local domain effects share a transaction. No broker call occurs in that transaction. */
@Service
public class BrokerEventIngestService {
    private static final Set<String> ENVELOPE=Set.of("eventId","eventType","schemaVersion","producer","aggregateId","sequence","occurredAtUtc","correlationId","payload");
    private static final Set<String> LIFECYCLE=Set.of("streamId","channelId","sessionId","streamGeneration","sessionVersion","status","availability","firstLiveAtUtc","initialLive");
    private static final Set<String> PRIVATE=Set.of("email","password","passwordHash","sessionCredential","providerToken","streamKey","ingestKey","serviceToken","presignedUrl","recoveryToken","token");
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ProjectionParser parser;
    private final ProjectionStore projections;
    private final DiscoveryClock clock;

    public BrokerEventIngestService(JdbcClient jdbc,TransactionTemplate tx,ProjectionParser parser,ProjectionStore projections,DiscoveryClock clock) {
        this.jdbc=jdbc; this.tx=tx; this.parser=parser; this.projections=projections; this.clock=clock;
    }

    public String ingest(String consumer,JsonNode body) {
        fields(body,ENVELOPE);
        rejectPrivate(body);
        String id=text(body,"eventId"), type=text(body,"eventType");
        if(!UUID.fromString(id).toString().equalsIgnoreCase(id) || integer(body,"schemaVersion")!=1
                || !"streaming".equals(text(body,"producer")) || !text(body,"correlationId").matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("INVALID_EVENT");
        Instant occurred=instant(body,"occurredAtUtc");
        JsonNode payload=body.get("payload");
        if(payload==null || !payload.isObject()) throw new IllegalArgumentException("INVALID_EVENT");
        long sequence=integer(body,"sequence");
        boolean lifecycle="core.lifecycle".equals(consumer);
        if(!(lifecycle?"stream.session.changed.v1":"stream.discovery.snapshot.v1").equals(type)) throw new IllegalArgumentException("INVALID_EVENT");
        if(lifecycle) validateLifecycle(payload);
        var projection=lifecycle?null:parser.parsePayload(payload);
        String stream=text(payload,"streamId");
        String aggregate=(lifecycle?"session:"+text(payload,"sessionId"):"stream:"+stream);
        if(!aggregate.equals(text(body,"aggregateId")) || sequence!=integer(payload,lifecycle?"sessionVersion":"projectionVersion"))
            throw new IllegalArgumentException("INVALID_EVENT");
        String fingerprint=CanonicalJson.hash(body);
        return Objects.requireNonNull(tx.execute(status-> {
            int inserted=jdbc.sql("INSERT INTO discovery.broker_inbox(consumer,event_id,fingerprint,outcome) VALUES (:consumer,:id,:hash,'RECEIVED') ON CONFLICT DO NOTHING")
                    .param("consumer",consumer).param("id",id).param("hash",fingerprint).update();
            if(inserted==0) {
                String previous=jdbc.sql("SELECT fingerprint FROM discovery.broker_inbox WHERE consumer=:consumer AND event_id=:id")
                        .param("consumer",consumer).param("id",id).query(String.class).single();
                if(previous.equals(fingerprint)) return "DUPLICATE";
                projections.recordConflict(id,stream,sequence,"EVENT_ID_REUSED",previous,fingerprint,payload.toString(),clock.now());
                return "EVENT_ID_CONFLICT";
            }
            String outcome;
            if(lifecycle) outcome=applyLifecycle(payload,occurred);
            else {
                ApplyOutcome applied=projections.apply(projection,clock.now(),clock.now());
                outcome=applied.name();
                if(applied==ApplyOutcome.CONFLICT_CHANNEL || applied==ApplyOutcome.CONFLICT_SAME_VERSION) {
                    projections.recordConflict(id,stream,sequence,outcome,projections.projectionHash(stream).orElse(null),
                            projection.contentHash(),payload.toString(),clock.now());
                    // A failed effect must remain a conflict on every redrive.
                    jdbc.sql("DELETE FROM discovery.broker_inbox WHERE consumer=:consumer AND event_id=:id")
                            .param("consumer",consumer).param("id",id).update();
                    return "PROJECTION_CONFLICT";
                }
            }
            if(outcome.equals("LIFECYCLE_CONFLICT")) {
                projections.recordConflict(id,stream,sequence,outcome,null,CanonicalJson.hash(payload),payload.toString(),clock.now());
                jdbc.sql("DELETE FROM discovery.broker_inbox WHERE consumer=:consumer AND event_id=:id")
                        .param("consumer",consumer).param("id",id).update();
                return outcome;
            }
            jdbc.sql("UPDATE discovery.broker_inbox SET outcome=:outcome WHERE consumer=:consumer AND event_id=:id")
                    .param("outcome",outcome).param("consumer",consumer).param("id",id).update();
            return outcome;
        }));
    }

    private String applyLifecycle(JsonNode p,Instant occurred) {
        String stream=text(p,"streamId");
        // Lock the per-stream row, including the first event, before comparing versions.
        int inserted=jdbc.sql("INSERT INTO discovery.session_lifecycle(stream_id,channel_id,session_id,stream_generation,session_version,status,availability,first_live_at_utc,occurred_at_utc,state_fingerprint) "
                +"VALUES (:stream,:channel,:session,:generation,:version,:status,:availability,:first,:occurred,:hash) ON CONFLICT DO NOTHING")
                .param("stream",stream).param("channel",text(p,"channelId")).param("session",text(p,"sessionId"))
                .param("generation",integer(p,"streamGeneration")).param("version",integer(p,"sessionVersion"))
                .param("status",text(p,"status")).param("availability",text(p,"availability"))
                .param("first",timestamp(nullableInstant(p,"firstLiveAtUtc"))).param("occurred",timestamp(occurred))
                .param("hash",CanonicalJson.hash(p)).update();
        var current=jdbc.sql("SELECT channel_id,stream_generation,session_version,state_fingerprint,status FROM discovery.session_lifecycle WHERE stream_id=:stream FOR UPDATE")
                .param("stream",stream).query((rs,n)->new Current(rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5))).single();
        long generation=integer(p,"streamGeneration"), version=integer(p,"sessionVersion");
        if(!current.channel.equals(text(p,"channelId"))) return "LIFECYCLE_CONFLICT";
        if(generation<current.generation || generation==current.generation && version<current.version) return "IGNORED_OLDER";
        if(generation==current.generation && version==current.version)
            return current.hash.equals(CanonicalJson.hash(p))?(inserted==1?"APPLIED":"IGNORED_SAME"):"LIFECYCLE_CONFLICT";
        if(generation==current.generation && "ENDED".equals(current.status)) return "LIFECYCLE_CONFLICT";
        jdbc.sql("UPDATE discovery.session_lifecycle SET session_id=:session,stream_generation=:generation,session_version=:version,status=:status,availability=:availability,"
                +"first_live_at_utc=:first,occurred_at_utc=:occurred,state_fingerprint=:hash WHERE stream_id=:stream")
                .param("session",text(p,"sessionId")).param("generation",generation).param("version",version)
                .param("status",text(p,"status")).param("availability",text(p,"availability"))
                .param("first",timestamp(nullableInstant(p,"firstLiveAtUtc"))).param("occurred",timestamp(occurred))
                .param("hash",CanonicalJson.hash(p)).param("stream",stream).update();
        return "APPLIED";
    }

    private record Current(String channel,long generation,long version,String hash,String status) {}
    private static void validateLifecycle(JsonNode p) {
        fields(p,LIFECYCLE);
        for(String key:Set.of("streamId","channelId","sessionId")) if(!text(p,key).matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("INVALID_EVENT");
        integer(p,"streamGeneration"); integer(p,"sessionVersion");
        String expected=switch(text(p,"status")) {
            case "PREPARING","ENDED" -> "OFFLINE";
            case "LIVE" -> "PLAYABLE";
            case "RECONNECT_GRACE" -> "RECONNECTING";
            default -> throw new IllegalArgumentException("INVALID_EVENT");
        };
        if(!expected.equals(text(p,"availability")) || !p.get("initialLive").isBoolean()) throw new IllegalArgumentException("INVALID_EVENT");
        Instant first=nullableInstant(p,"firstLiveAtUtc");
        if((p.get("initialLive").booleanValue() && (!"LIVE".equals(text(p,"status")) || first==null))
                || (Set.of("LIVE","RECONNECT_GRACE").contains(text(p,"status")) && first==null)) throw new IllegalArgumentException("INVALID_EVENT");
    }
    private static void fields(JsonNode p,Set<String> names) {
        if(p==null || !p.isObject() || !p.propertyNames().equals(names)) throw new IllegalArgumentException("INVALID_EVENT");
    }
    private static void rejectPrivate(JsonNode p) {
        if(p.isObject()) {
            if(p.propertyNames().stream().anyMatch(PRIVATE::contains)) throw new IllegalArgumentException("INVALID_EVENT");
            for(String name:p.propertyNames()) rejectPrivate(p.get(name));
        } else if(p.isArray()) p.forEach(BrokerEventIngestService::rejectPrivate);
    }
    private static String text(JsonNode p,String field) {
        JsonNode v=p.get(field);
        if(v==null || !v.isTextual() || v.textValue().isBlank() || v.textValue().indexOf('\0')>=0) throw new IllegalArgumentException("INVALID_EVENT");
        return v.textValue();
    }
    private static long integer(JsonNode p,String field) {
        JsonNode v=p.get(field);
        if(v==null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue()<1 || v.longValue()>9007199254740991L) throw new IllegalArgumentException("INVALID_EVENT");
        return v.longValue();
    }
    private static Instant instant(JsonNode p,String field) {
        String raw=text(p,field);
        if(!raw.endsWith("Z") && !raw.endsWith("+00:00")) throw new IllegalArgumentException("INVALID_EVENT");
        Instant value=OffsetDateTime.parse(raw).toInstant();
        if(value.isBefore(Instant.EPOCH) || value.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) throw new IllegalArgumentException("INVALID_EVENT");
        return value;
    }
    private static Instant nullableInstant(JsonNode p,String field) { return p.get(field).isNull()?null:instant(p,field); }
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
}
