package streaming.core.discovery.application;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import streaming.core.discovery.domain.StreamProjection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static streaming.core.discovery.TestPayloads.JSON;
import static streaming.core.discovery.TestPayloads.envelope;
import static streaming.core.discovery.TestPayloads.live;
import static streaming.core.discovery.TestPayloads.neverStarted;
import static streaming.core.discovery.TestPayloads.reconnecting;

class ProjectionParserTest {
    private static final Instant T=Instant.parse("2026-10-06T12:00:00.123456Z");
    private final ProjectionParser parser=new ProjectionParser();

    /** Verbatim shape produced by publish_stream_discovery: PostgreSQL offsets, nulls, nested labels. */
    private static final String STREAMING_SNAPSHOT="""
            {"eventId":"0199d4a4-0000-7000-8000-000000000001","eventType":"StreamDiscoverySnapshot","schemaVersion":1,
             "aggregateId":"stream:str_550e8400-e29b-41d4-a716-446655440000","sequence":7,"occurredAtUtc":"2026-10-03T20:00:02.123456Z","producer":"streaming",
             "payload":{"streamId":"str_550e8400-e29b-41d4-a716-446655440000","channelId":"chn_0123456789abcdef0123456789abcdef","projectionVersion":7,
              "discoveryPosition":41,"metadataVersion":3,"title":"Conversación","category":{"id":"cat_00000000000000000000000000000001","name":"Conversación"},
              "tags":[],"sessionId":"ses_550e8400-e29b-41d4-a716-446655440001","streamGeneration":2,"sessionVersion":4,"status":"LIVE",
              "availability":"PLAYABLE","startedAtUtc":"2026-10-03T20:00:00+00:00","stateObservedAtUtc":"2026-10-03T20:00:02.123456+00:00",
              "viewerCount":8,"countVersion":5,"viewerCountObservedAtUtc":"2026-10-03T20:00:02.1+00:00"}}""";

    @Test void acceptsTheExactShapeStreamingPublishes() {
        var event=parser.parseEvent(JSON.readTree(STREAMING_SNAPSHOT));
        var p=event.projection();
        assertThat(event.eventId()).isEqualTo("0199d4a4-0000-7000-8000-000000000001");
        assertThat(p.streamId()).isEqualTo("str_550e8400-e29b-41d4-a716-446655440000");
        assertThat(p.projectionVersion()).isEqualTo(7);
        assertThat(p.discoveryPosition()).isEqualTo(41);
        assertThat(p.availability()).isEqualTo("PLAYABLE");
        assertThat(p.startedAtUtc()).isEqualTo(Instant.parse("2026-10-03T20:00:00Z"));
        assertThat(p.stateObservedAtUtc()).isEqualTo(Instant.parse("2026-10-03T20:00:02.123456Z"));
        assertThat(p.viewerCountObservedAtUtc()).isEqualTo(Instant.parse("2026-10-03T20:00:02.1Z"));
        assertThat(p.tags()).isEmpty();
        assertThat(p.contentHash()).hasSize(64);
        assertThat(event.eventHash()).hasSize(64).isNotEqualTo(p.contentHash());
    }

    @Test void aStreamThatNeverBroadcastHasNullSessionFields() {
        var p=parser.parsePayload(neverStarted("str_a","chn_a",1,T));
        assertThat(p.sessionId()).isNull();
        assertThat(p.sessionVersion()).isNull();
        assertThat(p.startedAtUtc()).isNull();
        assertThat(p.streamGeneration()).isZero();
        assertThat(p.status()).isEqualTo("OFFLINE");
    }

    @Test void reconnectGraceIsLiveButReconnecting() {
        var p=parser.parsePayload(reconnecting("str_a","chn_a",2,T));
        assertThat(p.status()).isEqualTo("LIVE");
        assertThat(p.availability()).isEqualTo("RECONNECTING");
    }

    @Test void aMissingViewerObservationIsAllowedAndKeptNull() {
        ObjectNode payload=live("str_a","chn_a",1,5,T);
        payload.putNull("viewerCountObservedAtUtc");
        assertThat(parser.parsePayload(payload).viewerCountObservedAtUtc()).isNull();
    }

    @Test void theContentHashIgnoresKeyOrderAndNumberFormattingButNotValues() {
        ObjectNode a=live("str_a","chn_a",1,5,T);
        ObjectNode reordered=JSON.createObjectNode();
        var names=new java.util.ArrayList<String>();
        a.propertyNames().forEach(names::add);
        java.util.Collections.reverse(names);
        for(String name:names) reordered.set(name,a.get(name));
        assertThat(parser.parsePayload(reordered).contentHash()).isEqualTo(parser.parsePayload(a).contentHash());
        ObjectNode changed=a.deepCopy();
        changed.put("viewerCount",6);
        assertThat(parser.parsePayload(changed).contentHash()).isNotEqualTo(parser.parsePayload(a).contentHash());
    }

    @Test void unknownFieldsAreIgnoredSoProducersCanAddCompatibleOnes() {
        ObjectNode payload=live("str_a","chn_a",1,5,T);
        payload.put("futureField","x");
        ObjectNode event=envelope("evt_1",payload);
        event.put("extraEnvelopeField",1);
        assertThat(parser.parseEvent(event).projection().streamId()).isEqualTo("str_a");
    }

    @Test void trimsTitleAndLabelsButKeepsAccents() {
        ObjectNode payload=live("str_a","chn_a",1,5,T);
        payload.put("title","  Música en vivo  ");
        ((ObjectNode) payload.get("category")).put("name"," Música ");
        var p=parser.parsePayload(payload);
        assertThat(p.title()).isEqualTo("Música en vivo");
        assertThat(p.categoryName()).isEqualTo("Música");
    }

    @Test void rejectsEveryBrokenEnvelopeFieldWithAStableFieldError() {
        check("eventId",e->e.put("eventId","bad id!"));
        check("eventId",e->e.put("eventId",""));
        check("eventId",e->e.remove("eventId"));
        check("eventType",e->e.put("eventType","ChatMessageCreated"));
        check("schemaVersion",e->e.put("schemaVersion",2));
        check("producer",e->e.put("producer","chat"));
        check("occurredAtUtc",e->e.put("occurredAtUtc","yesterday"));
        check("aggregateId",e->e.put("aggregateId","stream:other"));
        check("aggregateId",e->e.put("aggregateId","session:str_a"));
        check("sequence",e->e.put("sequence",99));
        check("sequence",e->e.put("sequence","7"));
        check("payload",e->e.remove("payload"));
        check("payload",e->e.put("payload","x"));
    }

    @Test void rejectsEveryBrokenPayloadField() {
        payloadCheck("streamId",p->p.put("streamId","../x"));
        payloadCheck("channelId",p->p.put("channelId",""));
        payloadCheck("projectionVersion",p->p.put("projectionVersion",0));
        payloadCheck("discoveryPosition",p->p.put("discoveryPosition",0));
        payloadCheck("metadataVersion",p->p.put("metadataVersion",0));
        payloadCheck("streamGeneration",p->p.put("streamGeneration",-1));
        payloadCheck("countVersion",p->p.put("countVersion",-1));
        payloadCheck("title",p->p.put("title","   "));
        payloadCheck("title",p->p.put("title","x".repeat(201)));
        payloadCheck("category",p->p.remove("category"));
        payloadCheck("category",p->((ObjectNode) p.get("category")).put("id","bad id"));
        payloadCheck("tags",p->p.remove("tags"));
        payloadCheck("tags",p->{ var tags=p.putArray("tags"); for(int i=0;i<6;i++) tags.addObject().put("id","tag_"+i).put("name","t"+i); });
        payloadCheck("tags",p->{ var tags=p.putArray("tags"); tags.addObject().put("id","tag_1").put("name","a"); tags.addObject().put("id","tag_1").put("name","b"); });
        payloadCheck("status",p->p.put("status","PAUSED"));
        payloadCheck("availability",p->p.put("availability","ONLINE"));
        payloadCheck("sessionId",p->p.put("sessionId","a b"));
        payloadCheck("sessionVersion",p->p.put("sessionVersion",0));
        payloadCheck("startedAtUtc",p->p.put("startedAtUtc","soon"));
        payloadCheck("stateObservedAtUtc",p->p.putNull("stateObservedAtUtc"));
        payloadCheck("stateObservedAtUtc",p->p.put("stateObservedAtUtc","2026-10-06 12:00:00"));
        payloadCheck("viewerCount",p->p.put("viewerCount",-1));
        payloadCheck("viewerCount",p->p.put("viewerCount",3_000_000_000L));
        payloadCheck("viewerCount",p->p.put("viewerCount","8"));
    }

    @Test void aReachableStreamMustCarryItsLiveSession() {
        for(String field:new String[]{"sessionId","sessionVersion","startedAtUtc"}) {
            ObjectNode payload=live("str_a","chn_a",1,5,T);
            payload.putNull(field);
            assertThatThrownBy(()->parser.parsePayload(payload)).as(field).isInstanceOfSatisfying(DiscoveryException.class,
                    e->assertThat(e.fieldErrors()).containsKey("availability"));
        }
        ObjectNode notLive=live("str_a","chn_a",1,5,T);
        notLive.put("status","ENDED");
        assertThatThrownBy(()->parser.parsePayload(notLive)).isInstanceOf(DiscoveryException.class);
        // OFFLINE may keep or lack a session.
        ObjectNode offline=live("str_a","chn_a",1,0,T);
        offline.put("availability","OFFLINE").put("status","ENDED");
        assertThat(parser.parsePayload(offline).availability()).isEqualTo("OFFLINE");
    }

    @Test void textThatPostgreSqlCannotStoreAndTimestampsOutsideItsRangeAreInvalidNotServerErrors() {
        payloadCheck("title",p->p.put("title","a\u0000b"));
        payloadCheck("sessionId",p->p.put("sessionId","ses\u0000"));
        payloadCheck("startedAtUtc",p->p.put("startedAtUtc","+500000-01-01T00:00:00Z"));
        payloadCheck("stateObservedAtUtc",p->p.put("stateObservedAtUtc","1969-12-31T23:59:59Z"));
        payloadCheck("viewerCountObservedAtUtc",p->p.put("viewerCountObservedAtUtc","+10000-01-01T00:00:00Z"));
        assertThat(parser.parsePayload(live("str_a","chn_a",1,1,T).put("title","Línea con ñ y 日本語 y emoji 🎥")).title()).contains("🎥");
    }

    @Test void nonObjectBodiesAreRejected() {
        for(JsonNode body:new JsonNode[]{null,JSON.readTree("[]"),JSON.readTree("\"x\""),JSON.readTree("null"),JSON.readTree("5")})
            assertThatThrownBy(()->parser.parseEvent(body)).isInstanceOfSatisfying(DiscoveryException.class,e->{
                assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.code()).isEqualTo("INVALID_EVENT");
            });
    }

    private void check(String field,java.util.function.Consumer<ObjectNode> breakIt) {
        ObjectNode event=envelope("evt_1",live("str_a","chn_a",7,5,T));
        breakIt.accept(event);
        assertThatThrownBy(()->parser.parseEvent(event)).as(field).isInstanceOfSatisfying(DiscoveryException.class,e->{
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo("INVALID_EVENT");
            assertThat(e.fieldErrors()).containsKey(field);
        });
    }

    private void payloadCheck(String field,java.util.function.Consumer<ObjectNode> breakIt) {
        ObjectNode payload=live("str_a","chn_a",7,5,T);
        breakIt.accept(payload);
        assertThatThrownBy(()->parser.parsePayload(payload)).as(field).isInstanceOfSatisfying(DiscoveryException.class,e->{
            assertThat(e.code()).isEqualTo("INVALID_EVENT");
            assertThat(e.fieldErrors()).containsKey(field);
        });
    }
}
