package streaming.core.discovery;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Snapshots shaped exactly like Streaming's {@code publish_stream_discovery} SQL function: PostgreSQL timestamps
 * with a {@code +00:00} offset, nulls for absent session data, ENDED/OFFLINE rows with zero viewers.
 */
public final class TestPayloads {
    public static final JsonMapper JSON=JsonMapper.builder().build();
    public static final String CATEGORY="cat_00000000000000000000000000000001";
    public static final String TAG="tag_00000000000000000000000000000001";
    private static final DateTimeFormatter PG=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");
    private TestPayloads() { }

    public static String pg(Instant instant) { return PG.format(OffsetDateTime.ofInstant(instant,ZoneOffset.UTC)); }

    /** LIVE + PLAYABLE with a session. */
    public static ObjectNode live(String streamId,String channelId,long version,int viewers,Instant observed) {
        ObjectNode p=base(streamId,channelId,version,observed);
        p.put("sessionId","ses_"+streamId).put("sessionVersion",4).put("status","LIVE").put("availability","PLAYABLE")
                .put("startedAtUtc",pg(observed.minusSeconds(600))).put("viewerCount",viewers).put("countVersion",5)
                .put("viewerCountObservedAtUtc",pg(observed));
        return p;
    }

    /** RECONNECT_GRACE: Streaming publishes it as LIVE/RECONNECTING. */
    public static ObjectNode reconnecting(String streamId,String channelId,long version,Instant observed) {
        ObjectNode p=live(streamId,channelId,version,3,observed);
        p.put("availability","RECONNECTING");
        return p;
    }

    /** A configured stream that has never broadcast. */
    public static ObjectNode neverStarted(String streamId,String channelId,long version,Instant observed) {
        ObjectNode p=base(streamId,channelId,version,observed);
        p.putNull("sessionId").putNull("sessionVersion").put("status","OFFLINE").put("availability","OFFLINE")
                .putNull("startedAtUtc").put("viewerCount",0).put("countVersion",0).put("viewerCountObservedAtUtc",pg(observed));
        p.put("streamGeneration",0);
        return p;
    }

    /** ENDED keeps its session identity but is OFFLINE with zero viewers. */
    public static ObjectNode ended(String streamId,String channelId,long version,Instant observed) {
        ObjectNode p=live(streamId,channelId,version,0,observed);
        p.put("status","ENDED").put("availability","OFFLINE").put("viewerCount",0);
        return p;
    }

    public static ObjectNode envelope(String eventId,ObjectNode payload) {
        ObjectNode e=JSON.createObjectNode();
        e.put("eventId",eventId).put("eventType","StreamDiscoverySnapshot").put("schemaVersion",1)
                .put("aggregateId","stream:"+payload.get("streamId").textValue()).put("sequence",payload.get("projectionVersion").longValue())
                .put("occurredAtUtc",payload.get("stateObservedAtUtc").textValue()).put("producer","streaming");
        e.set("payload",payload);
        return e;
    }

    private static ObjectNode base(String streamId,String channelId,long version,Instant observed) {
        ObjectNode p=JSON.createObjectNode();
        p.put("streamId",streamId).put("channelId",channelId).put("projectionVersion",version).put("discoveryPosition",version*10)
                .put("metadataVersion",3).put("title","Conversación en vivo");
        p.putObject("category").put("id",CATEGORY).put("name","Conversación");
        p.putArray("tags").addObject().put("id",TAG).put("name","Español");
        p.put("streamGeneration",2).put("stateObservedAtUtc",pg(observed));
        return p;
    }
}
