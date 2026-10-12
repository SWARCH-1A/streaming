package streaming.core.discovery;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import streaming.core.discovery.application.DiscoveryException;
import streaming.core.discovery.application.ProjectionIngestService.Kind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static streaming.core.discovery.TestPayloads.envelope;

/** The private inbox of Streaming snapshots, over the real TLS listener with the service credential. */
class DiscoveryIngestIT extends DiscoveryITBase {
    private static final String ROUTE="/internal/core/discovery/stream-events";

    private ObjectNode event(String eventId,ObjectNode payload) { return envelope(eventId,payload); }
    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
    private String projectionTitle(String streamId) { return jdbc.sql("SELECT title FROM discovery.stream_projection WHERE stream_id=:s").param("s",streamId).query(String.class).single(); }
    private long projectionVersion(String streamId) { return jdbc.sql("SELECT projection_version FROM discovery.stream_projection WHERE stream_id=:s").param("s",streamId).query(Long.class).single(); }

    // ---- delivery semantics

    @Test void aNewEventIsAcceptedWith202AndAppliedAtOnce() throws Exception {
        var ch=channel("first",null);
        var response=deliver(event("evt_first",live("first",ch,1,12)));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(body(response).get("eventId").textValue()).isEqualTo("evt_first");
        assertThat(body(response).get("outcome").textValue()).isEqualTo("APPLIED");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(projectionVersion("str_first")).isEqualTo(1);
        assertThat(count("discovery.inbox_events")).isEqualTo(1);
        assertThat(field(streams(java.util.Map.of()),"streamId")).as("visible to the public query immediately").containsExactly("str_first");
    }

    @Test void anIdenticalRedeliveryIs200AndChangesNothing() throws Exception {
        var ch=channel("dup",null);
        var envelope=event("evt_dup",live("dup",ch,1,12));
        assertThat(deliver(envelope).statusCode()).isEqualTo(202);
        String appliedAt=jdbc.sql("SELECT CAST(applied_at_utc AS text) FROM discovery.stream_projection").query(String.class).single();
        CLOCK.advance(java.time.Duration.ofSeconds(1));

        var again=deliver(envelope);

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(body(again).get("outcome").textValue()).isEqualTo("DUPLICATE");
        assertThat(jdbc.sql("SELECT CAST(applied_at_utc AS text) FROM discovery.stream_projection").query(String.class).single()).isEqualTo(appliedAt);
        assertThat(count("discovery.inbox_events")).isEqualTo(1);
        assertThat(count("discovery.projection_conflicts")).isZero();
    }

    @Test void theSameEventIdWithDifferentContentIs409AndIsRecordedWithoutChangingTheProjection() throws Exception {
        var ch=channel("reuse",null);
        assertThat(deliver(event("evt_reuse",live("reuse",ch,1,5))).statusCode()).isEqualTo(202);

        var other=deliver(event("evt_reuse",(ObjectNode)live("reuse",ch,2,99).put("title","Otro título")));

        assertThat(other.statusCode()).isEqualTo(409);
        assertThat(body(other).get("code").textValue()).isEqualTo("EVENT_ID_CONFLICT");
        assertThat(projectionVersion("str_reuse")).isEqualTo(1);
        assertThat(projectionTitle("str_reuse")).isEqualTo("Directo reuse");
        assertThat(jdbc.sql("SELECT reason FROM discovery.projection_conflicts").query(String.class).single()).isEqualTo("EVENT_ID_REUSED");
    }

    @Test void aNewerVersionReplacesAndAnOlderOrEqualOneNeverDoes() throws Exception {
        var ch=channel("versions",null);
        assertThat(deliver(event("e1",live("versions",ch,5,10))).statusCode()).isEqualTo(202);

        var newer=deliver(event("e2",(ObjectNode)live("versions",ch,6,20).put("title","Seis")));
        assertThat(body(newer).get("outcome").textValue()).isEqualTo("APPLIED");
        assertThat(projectionTitle("str_versions")).isEqualTo("Seis");

        var older=deliver(event("e3",(ObjectNode)live("versions",ch,4,30).put("title","Cuatro")));
        assertThat(older.statusCode()).as("accepted so Streaming does not retry it").isEqualTo(202);
        assertThat(body(older).get("outcome").textValue()).isEqualTo("IGNORED_OLDER");
        assertThat(projectionTitle("str_versions")).isEqualTo("Seis");

        var same=deliver(event("e4",(ObjectNode)live("versions",ch,6,20).put("title","Seis")));
        assertThat(same.statusCode()).isEqualTo(202);
        assertThat(body(same).get("outcome").textValue()).isEqualTo("IGNORED_SAME");
        assertThat(projectionVersion("str_versions")).isEqualTo(6);
        assertThat(count("discovery.projection_conflicts")).isZero();
    }

    @Test void theSameVersionWithDifferentContentIsAConflictAndNeverOverwrites() throws Exception {
        var ch=channel("clash",null);
        assertThat(deliver(event("e1",live("clash",ch,3,10))).statusCode()).isEqualTo(202);

        var conflict=deliver(event("e2",(ObjectNode)live("clash",ch,3,10).put("title","Distinto")));

        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(body(conflict).get("code").textValue()).isEqualTo("PROJECTION_CONFLICT");
        assertThat(projectionTitle("str_clash")).isEqualTo("Directo clash");
        var recorded=jdbc.sql("SELECT reason,payload->>'title' AS title FROM discovery.projection_conflicts").query().singleRow();
        assertThat(recorded.get("reason")).isEqualTo("SAME_VERSION_DIFFERENT_CONTENT");
        assertThat(recorded.get("title")).as("the operator keeps the rejected payload").isEqualTo("Distinto");
        assertThat(jdbc.sql("SELECT outcome FROM discovery.inbox_events WHERE event_id='e2'").query(String.class).single()).isEqualTo("CONFLICT");
    }

    @Test void aStreamCannotMoveToAnotherChannelAndTwoStreamsCannotShareOne() throws Exception {
        var a=channel("chan_a",null); var b=channel("chan_b",null);
        assertThat(deliver(event("e1",live("moves",a,1,1))).statusCode()).isEqualTo(202);

        var moved=deliver(event("e2",live("moves",b,2,1)));
        assertThat(moved.statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT channel_id FROM discovery.stream_projection WHERE stream_id='str_moves'").query(String.class).single()).isEqualTo("chn_chan_a");
        assertThat(jdbc.sql("SELECT reason FROM discovery.projection_conflicts").query(String.class).single()).isEqualTo("CHANNEL_MISMATCH");

        var second=deliver(event("e3",live("squatter",a,1,1)));
        assertThat(second.statusCode()).as("one stream per channel: a conflict, not a server error Streaming would retry forever").isEqualTo(409);
        assertThat(count("discovery.stream_projection")).isEqualTo(1);
        assertThat(count("discovery.projection_conflicts")).isEqualTo(2);
    }

    // ---- invalid input

    @Test void invalidEventsAre422WithFieldErrorsAndLeaveNoTrace() throws Exception {
        var ch=channel("bad",null);
        record Break(String field,java.util.function.Consumer<ObjectNode> change) { }
        for(var b:List.of(new Break("eventType",e->e.put("eventType","Other")),new Break("producer",e->e.put("producer","chat")),
                new Break("aggregateId",e->e.put("aggregateId","stream:str_other")),new Break("sequence",e->e.put("sequence",99)),
                new Break("schemaVersion",e->e.put("schemaVersion",2)),new Break("eventId",e->e.put("eventId","has space")),
                new Break("occurredAtUtc",e->e.put("occurredAtUtc","yesterday")),new Break("status",e->((ObjectNode)e.get("payload")).put("status","BROKEN")),
                new Break("title",e->((ObjectNode)e.get("payload")).put("title","  ")),new Break("title",e->((ObjectNode)e.get("payload")).put("title","a\u0000b")),
                new Break("startedAtUtc",e->((ObjectNode)e.get("payload")).put("startedAtUtc","+500000-01-01T00:00:00Z")),
                new Break("availability",e->((ObjectNode)e.get("payload")).put("availability","PLAYABLE").put("status","ENDED")),
                new Break("payload",e->e.putNull("payload")))) {
            var envelope=event("evt_bad",live("bad",ch,1,1));
            b.change().accept(envelope);
            var response=deliver(envelope);
            assertThat(response.statusCode()).as(b.field()).isEqualTo(422);
            assertThat(body(response).get("code").textValue()).isEqualTo("INVALID_EVENT");
            assertThat(body(response).at("/fieldErrors/"+b.field()).isMissingNode()).as(b.field()+": "+response.body()).isFalse();
            assertThat(response.body()).doesNotContain("Exception","org.springframework","at streaming.");
        }
        assertThat(count("discovery.inbox_events")).isZero();
        assertThat(count("discovery.stream_projection")).isZero();
    }

    @Test void malformedJsonIs400AndNeverReachesStorage() throws Exception {
        for(String bad:List.of("{","not json","","[1,2")) {
            var response=privatePost(ROUTE,"streaming",SERVICE_TOKEN,bad);
            assertThat(response.statusCode()).as(bad).isEqualTo(400);
        }
        assertThat(count("discovery.inbox_events")).isZero();
    }

    // ---- authentication and exposure

    @Test void onlyStreamingWithTheFullServiceCredentialMayDeliver() throws Exception {
        var ch=channel("auth",null);
        String valid=json.writeValueAsString(event("evt_auth",live("auth",ch,1,1)));
        String garbage="{this is not json";
        for(String token:new String[]{null,"","wrong",CATALOG_TOKEN}) {
            assertThat(privatePost(ROUTE,"streaming",token,valid).statusCode()).as("token "+token).isEqualTo(401);
            assertThat(privatePost(ROUTE,"streaming",token,garbage).statusCode()).as("credentials are checked before the body is parsed").isEqualTo(401);
        }
        for(String service:new String[]{null,"chat","Streaming","streaming-x"}) assertThat(privatePost(ROUTE,service,SERVICE_TOKEN,valid).statusCode()).as("service "+service).isEqualTo(401);
        assertThat(count("discovery.inbox_events")).isZero();
        assertThat(privatePost(ROUTE,"streaming",SERVICE_TOKEN,valid).statusCode()).isEqualTo(202);
    }

    @Test void thePublicPortAnswers404EvenWithValidCredentialsAndSpoofedForwarding() throws Exception {
        var ch=channel("spoof",null);
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+ROUTE)).header("Content-Type","application/json")
                .header("X-Service-Name","streaming").header("X-Service-Token",SERVICE_TOKEN)
                .header("X-Forwarded-Port",Integer.toString(listener.localPort())).header("X-Forwarded-Proto","https").header("X-Forwarded-For","127.0.0.1")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(event("evt_spoof",live("spoof",ch,1,1))))).build();
        assertThat(http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        assertThat(count("discovery.inbox_events")).isZero();
    }

    @Test void theCutEndpointOfStreamingIsNotACoreRoute() throws Exception {
        assertThat(privatePost("/internal/core/discovery/snapshots","streaming",SERVICE_TOKEN,"{}").statusCode()).isIn(401,404);
    }

    // ---- ordering, duplication and concurrency

    @Test void anyDeliveryOrderWithDuplicatesConvergesToTheNewestVersion() throws Exception {
        var ch=channel("chaos",null);
        var events=new ArrayList<ObjectNode>();
        for(int v=1;v<=30;v++) events.add(event("evt_v"+v,(ObjectNode)live("chaos",ch,v,v).put("title","Versión "+v)));
        var delivery=new ArrayList<>(events);
        var random=new Random(7);
        for(int i=0;i<40;i++) delivery.add(events.get(random.nextInt(events.size())));
        Collections.shuffle(delivery,random);

        int accepted=0, duplicates=0;
        for(var e:delivery) {
            var response=deliver(e);
            if(response.statusCode()==202) accepted++; else if(response.statusCode()==200) duplicates++; else throw new AssertionError(response.statusCode()+" "+response.body());
        }

        assertThat(accepted).isEqualTo(30);
        assertThat(duplicates).isEqualTo(40);
        assertThat(projectionVersion("str_chaos")).isEqualTo(30);
        assertThat(projectionTitle("str_chaos")).isEqualTo("Versión 30");
        assertThat(count("discovery.stream_projection")).isEqualTo(1);
        assertThat(count("discovery.projection_conflicts")).isZero();
    }

    @Test void concurrentDeliveriesOfTheSameEventApplyItExactlyOnce() throws Exception {
        var ch=channel("race",null);
        var envelope=event("evt_race",live("race",ch,1,5));
        var kinds=runConcurrently(12,i->()->ingest.ingest(envelope).kind());
        assertThat(kinds.stream().filter(k->k==Kind.ACCEPTED).count()).isEqualTo(1);
        assertThat(kinds.stream().filter(k->k==Kind.DUPLICATE).count()).isEqualTo(11);
        assertThat(count("discovery.inbox_events")).isEqualTo(1);
    }

    @Test void concurrentVersionsOfANewStreamNeverLoseTheNewestNorFail() throws Exception {
        var ch=channel("storm",null);
        for(int round=0;round<25;round++) {         // the first-insert race is timing dependent: repeat it
            jdbc.sql("TRUNCATE discovery.stream_projection,discovery.inbox_events").update();
            int r=round;
            var results=runConcurrently(16,i->()->ingest.ingest(event("evt_"+r+"_"+i,(ObjectNode)live("storm",ch,i+1,i).put("title","V"+(i+1)))).kind());
            assertThat(results).as("round "+round).containsOnly(Kind.ACCEPTED);
            assertThat(projectionVersion("str_storm")).as("round "+round).isEqualTo(16);
            assertThat(projectionTitle("str_storm")).isEqualTo("V16");
            assertThat(count("discovery.stream_projection")).isEqualTo(1);
        }
    }

    @Test void twoStreamsRacingForOneChannelLeaveOneWinnerAndAConflictNeverAServerError() throws Exception {
        var ch=channel("contested",null);
        for(int round=0;round<25;round++) {
            jdbc.sql("TRUNCATE discovery.stream_projection,discovery.inbox_events,discovery.projection_conflicts").update();
            int r=round;
            var results=runConcurrently(8,i->()->ingest.ingest(event("evt_"+r+"_"+i,live(i%2==0?"left":"right",ch,1+i/2,i))).kind());
            assertThat(count("discovery.stream_projection")).as("round "+round+" "+results).isEqualTo(1);
            assertThat(results).as("round "+round).containsOnly(Kind.ACCEPTED,Kind.CONFLICT).contains(Kind.CONFLICT);
            String winner=jdbc.sql("SELECT stream_id FROM discovery.stream_projection").query(String.class).single();
            assertThat(count("discovery.projection_conflicts")).as("every event of the loser is recorded").isEqualTo(results.stream().filter(k->k==Kind.CONFLICT).count());
            assertThat(winner).isIn("str_left","str_right");
        }
    }

    @Test void aFailureWhileApplyingRollsBackTheInboxSoTheRedeliveryIsAppliedNotMistakenForADuplicate() throws Exception {
        var ch=channel("atomic",null);
        jdbc.sql("CREATE FUNCTION discovery.test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.title='boom' THEN RAISE EXCEPTION 'injected failure'; END IF; RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER test_fail BEFORE INSERT OR UPDATE ON discovery.stream_projection FOR EACH ROW EXECUTE FUNCTION discovery.test_fail()").update();
        try {
            var failing=deliver(event("evt_atomic",(ObjectNode)live("atomic",ch,1,1).put("title","boom")));
            assertThat(failing.statusCode()).as("Streaming must retry it").isGreaterThanOrEqualTo(500);
            assertThat(failing.body()).doesNotContain("injected failure","Exception");
            assertThat(count("discovery.inbox_events")).as("no half-recorded delivery").isZero();
            assertThat(count("discovery.stream_projection")).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER test_fail ON discovery.stream_projection").update();
            jdbc.sql("DROP FUNCTION discovery.test_fail()").update();
        }
        var retry=deliver(event("evt_atomic",(ObjectNode)live("atomic",ch,1,1).put("title","ok")));
        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(projectionTitle("str_atomic")).isEqualTo("ok");
    }

    // ---- housekeeping

    @Test void deliveredEventIdentitiesAreForgottenAfterTheRetryWindowButTheProjectionRemains() throws Exception {
        var ch=channel("purge",null);
        var envelope=event("evt_purge",live("purge",ch,1,1));
        assertThat(deliver(envelope).statusCode()).isEqualTo(202);
        CLOCK.advance(java.time.Duration.ofMinutes(30));
        maintenance.purge();
        assertThat(count("discovery.inbox_events")).as("inside the window").isEqualTo(1);
        CLOCK.advance(java.time.Duration.ofMinutes(31));
        maintenance.purge();
        assertThat(count("discovery.inbox_events")).isZero();
        assertThat(count("discovery.stream_projection")).isEqualTo(1);
        assertThat(deliver(envelope).statusCode()).as("an identical old event is still harmless").isEqualTo(202);
        var sameContentNewId=event("evt_purge2",(ObjectNode)envelope.get("payload").deepCopy());
        assertThat(body(deliver(sameContentNewId)).get("outcome").textValue()).isEqualTo("IGNORED_SAME");
    }

    @Test void theServiceRejectsAnInvalidEventDirectlyToo() {
        assertThatThrownBy(()->ingest.ingest(json.createObjectNode().put("eventId","x"))).isInstanceOf(DiscoveryException.class);
    }

    private <T> List<T> runConcurrently(int threads,java.util.function.IntFunction<Callable<T>> task) throws Exception {
        try(var pool=Executors.newFixedThreadPool(threads)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var futures=new ArrayList<java.util.concurrent.Future<T>>();
            for(int i=0;i<threads;i++) {
                Callable<T> work=task.apply(i);
                futures.add(pool.submit(()-> { start.await(); return work.call(); }));
            }
            start.countDown();
            var results=new ArrayList<T>();
            for(var future:futures) results.add(future.get(30,java.util.concurrent.TimeUnit.SECONDS));
            return results;
        }
    }
}
