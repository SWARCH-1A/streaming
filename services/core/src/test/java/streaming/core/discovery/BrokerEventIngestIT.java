package streaming.core.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import streaming.core.discovery.application.BrokerEventIngestService;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real repository adapters and their shared transaction against disposable PostgreSQL. */
class BrokerEventIngestIT extends DiscoveryITBase {
    @Autowired BrokerEventIngestService broker;

    @Test void aNewLifecycleAndItsInboxOutcomeCommitTogether() {
        var event=lifecycle(1,1,"LIVE");
        assertThat(deliverBroker(event)).isEqualTo("APPLIED");
        assertThat(lifecycleVersion()).isEqualTo(1);
        assertThat(inboxOutcome(event)).isEqualTo("APPLIED");
        assertThat(jdbc.sql("SELECT channel_id,session_id,status,availability,first_live_at_utc,occurred_at_utc "
                +"FROM discovery.session_lifecycle").query().singleRow())
                .containsEntry("channel_id","chn_broker").containsEntry("session_id","ses_broker_1")
                .containsEntry("status","LIVE").containsEntry("availability","PLAYABLE")
                .doesNotContainValue(null);
    }

    @Test void preparingRetainsANullFirstLiveTimeAndANewerVersionUpdatesTheBarrier() {
        assertThat(deliverBroker(lifecycle(1,1,"PREPARING"))).isEqualTo("APPLIED");
        assertThat(jdbc.sql("SELECT first_live_at_utc IS NULL FROM discovery.session_lifecycle").query(Boolean.class).single()).isTrue();
        assertThat(deliverBroker(lifecycle(1,2,"LIVE"))).isEqualTo("APPLIED");
        assertThat(lifecycleVersion()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT first_live_at_utc IS NOT NULL FROM discovery.session_lifecycle").query(Boolean.class).single()).isTrue();
    }

    @Test void duplicatesAndOlderOrEqualVersionsDoNotRepeatOrRegressTheEffect() {
        var latest=lifecycle(1,5,"LIVE");
        assertThat(deliverBroker(latest)).isEqualTo("APPLIED");
        assertThat(deliverBroker(latest)).isEqualTo("DUPLICATE");
        var older=lifecycle(1,3,"LIVE");
        assertThat(deliverBroker(older)).isEqualTo("IGNORED_OLDER");
        assertThat(inboxOutcome(older)).isEqualTo("IGNORED_OLDER");
        assertThat(deliverBroker(lifecycle(1,5,"LIVE"))).isEqualTo("IGNORED_SAME");
        assertThat(lifecycleVersion()).isEqualTo(5);
        assertThat(count("discovery.broker_inbox")).isEqualTo(3);
    }

    @Test void reusedEventIdsAreConflictsAndKeepTheOriginalFingerprintAndEffect() {
        var first=lifecycle(1,1,"LIVE");
        assertThat(deliverBroker(first)).isEqualTo("APPLIED");
        var changed=lifecycle(1,2,"LIVE");
        changed.put("eventId",first.get("eventId").textValue());
        assertThat(deliverBroker(changed)).isEqualTo("EVENT_ID_CONFLICT");
        assertThat(deliverBroker(first)).isEqualTo("DUPLICATE");
        assertThat(lifecycleVersion()).isEqualTo(1);
        assertThat(count("discovery.broker_inbox")).isEqualTo(1);
        assertThat(count("discovery.projection_conflicts")).isEqualTo(1);
    }

    @Test void lifecycleConflictsKeepTheirResultOnRedriveWithoutLeavingAnInboxReceipt() {
        assertThat(deliverBroker(lifecycle(1,5,"LIVE"))).isEqualTo("APPLIED");
        var conflict=lifecycle(1,5,"ENDED");
        assertThat(deliverBroker(conflict)).isEqualTo("LIFECYCLE_CONFLICT");
        assertThat(deliverBroker(conflict)).isEqualTo("LIFECYCLE_CONFLICT");
        assertThat(count("discovery.broker_inbox")).isEqualTo(1);
        assertThat(count("discovery.projection_conflicts")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT status FROM discovery.session_lifecycle").query(String.class).single()).isEqualTo("LIVE");
    }

    @Test void anEndedGenerationCannotBecomeLiveButANewGenerationCanStart() {
        assertThat(deliverBroker(lifecycle(1,5,"ENDED"))).isEqualTo("APPLIED");
        assertThat(deliverBroker(lifecycle(1,6,"LIVE"))).isEqualTo("LIFECYCLE_CONFLICT");
        assertThat(deliverBroker(lifecycle(2,1,"PREPARING"))).isEqualTo("APPLIED");
        assertThat(deliverBroker(lifecycle(1,9,"LIVE"))).isEqualTo("IGNORED_OLDER");
        assertThat(jdbc.sql("SELECT stream_generation FROM discovery.session_lifecycle").query(Long.class).single()).isEqualTo(2);
        assertThat(lifecycleVersion()).isEqualTo(1);
    }

    @Test void aStreamCannotMoveToAnotherChannel() {
        assertThat(deliverBroker(lifecycle(1,1,"LIVE"))).isEqualTo("APPLIED");
        var moved=lifecycle(1,2,"LIVE");
        ((ObjectNode)moved.get("payload")).put("channelId","chn_other");
        assertThat(deliverBroker(moved)).isEqualTo("LIFECYCLE_CONFLICT");
        assertThat(jdbc.sql("SELECT channel_id FROM discovery.session_lifecycle").query(String.class).single()).isEqualTo("chn_broker");
    }

    @Test void discoveryUsesItsOwnRepositoryAndInboxAndRemainsVisibleToGraphQl() throws Exception {
        channel("broker",null);
        var event=discovery(1);
        assertThat(deliverBroker(event)).isEqualTo("APPLIED");
        assertThat(deliverBroker(event)).isEqualTo("DUPLICATE");
        assertThat(inboxOutcome(event)).isEqualTo("APPLIED");
        assertThat(field(streams(java.util.Map.of()),"streamId")).containsExactly("str_broker");
        assertThat(count("discovery.session_lifecycle")).isZero();
    }

    @Test void projectionConflictsRemainConflictsOnRedrive() {
        assertThat(deliverBroker(discovery(1))).isEqualTo("APPLIED");
        var conflict=discovery(1);
        ((ObjectNode)conflict.get("payload")).put("title","Conflicting title");
        assertThat(deliverBroker(conflict)).isEqualTo("PROJECTION_CONFLICT");
        assertThat(deliverBroker(conflict)).isEqualTo("PROJECTION_CONFLICT");
        assertThat(count("discovery.broker_inbox")).isEqualTo(1);
        assertThat(count("discovery.projection_conflicts")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT DISTINCT reason FROM discovery.projection_conflicts").query(String.class).single())
                .isEqualTo("SAME_VERSION_DIFFERENT_CONTENT");
    }

    @Test void projectionChannelConflictsUseTheExistingVocabularyWithoutChangingTheProjection() {
        assertThat(deliverBroker(discovery(1))).isEqualTo("APPLIED");
        var moved=discovery(2);
        ((ObjectNode)moved.get("payload")).put("channelId","chn_other");
        assertThat(deliverBroker(moved)).isEqualTo("PROJECTION_CONFLICT");
        assertThat(jdbc.sql("SELECT reason FROM discovery.projection_conflicts").query(String.class).single())
                .isEqualTo("CHANNEL_MISMATCH");
        assertThat(projectionVersion()).isEqualTo(1);
        assertThat(count("discovery.broker_inbox")).isEqualTo(1);
    }

    @Test void concurrentRedeliveriesApplyTheEffectOnce() throws Exception {
        var event=lifecycle(1,1,"LIVE");
        try(var executor=Executors.newFixedThreadPool(8)) {
            var tasks=new ArrayList<Callable<String>>();
            for(int i=0;i<16;i++) tasks.add(()->deliverBroker(event));
            var outcomes=new ArrayList<String>();
            for(var result:executor.invokeAll(tasks)) outcomes.add(result.get());
            assertThat(outcomes.stream().filter("APPLIED"::equals).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter("DUPLICATE"::equals).count()).isEqualTo(15);
        }
        assertThat(count("discovery.broker_inbox")).isEqualTo(1);
        assertThat(count("discovery.session_lifecycle")).isEqualTo(1);
    }

    @Test void concurrentVersionsConvergeToTheNewestLifecycle() throws Exception {
        var tasks=new ArrayList<Callable<String>>();
        for(int version:List.of(7,2,10,1,8,5,12,3,11,6,4,9)) {
            var event=lifecycle(1,version,"LIVE");
            tasks.add(()->deliverBroker(event));
        }
        try(var executor=Executors.newFixedThreadPool(8)) {
            for(var result:executor.invokeAll(tasks)) assertThat(result.get()).isIn("APPLIED","IGNORED_OLDER");
        }
        assertThat(lifecycleVersion()).isEqualTo(12);
        assertThat(count("discovery.broker_inbox")).isEqualTo(12);
    }

    @ParameterizedTest @ValueSource(strings={"core.lifecycle","core.discovery"})
    void failureAfterTheFirstEffectRollsBackBothRepositoriesAndAllowsRedelivery(String consumer) {
        var event="core.lifecycle".equals(consumer)?lifecycle(1,1,"LIVE"):discovery(1);
        rejectAppliedOutcome(()->assertThatThrownBy(()->deliverBroker(event)).isInstanceOf(DataAccessException.class));
        assertThat(count("discovery.broker_inbox")).isZero();
        assertThat(count("discovery.session_lifecycle")).isZero();
        assertThat(count("discovery.stream_projection")).isZero();
        assertThat(deliverBroker(event)).isEqualTo("APPLIED");
        assertThat(deliverBroker(event)).isEqualTo("DUPLICATE");
    }

    @ParameterizedTest @ValueSource(strings={"core.lifecycle","core.discovery"})
    void failureAfterUpdatingAnExistingEffectKeepsItsPreviousVersion(String consumer) {
        boolean lifecycle="core.lifecycle".equals(consumer);
        assertThat(deliverBroker(lifecycle?lifecycle(1,1,"LIVE"):discovery(1))).isEqualTo("APPLIED");
        var newer=lifecycle?lifecycle(1,2,"LIVE"):discovery(2);
        rejectAppliedOutcome(()->assertThatThrownBy(()->deliverBroker(newer)).isInstanceOf(DataAccessException.class));
        assertThat(count("discovery.broker_inbox")).isEqualTo(1);
        assertThat(lifecycle?lifecycleVersion():projectionVersion()).isEqualTo(1);
        assertThat(deliverBroker(newer)).isEqualTo("APPLIED");
        assertThat(lifecycle?lifecycleVersion():projectionVersion()).isEqualTo(2);
    }

    @Test void invalidInputDoesNotReachEitherRepository() {
        var event=lifecycle(1,1,"LIVE");
        ((ObjectNode)event.get("payload")).put("recoveryToken","fixture-forbidden-field");
        assertThatThrownBy(()->deliverBroker(event)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("discovery.broker_inbox")).isZero();
        assertThat(count("discovery.session_lifecycle")).isZero();
    }

    private void rejectAppliedOutcome(Runnable assertion) {
        // Enforced only on new writes: existing APPLIED rows remain valid fixture data.
        jdbc.sql("ALTER TABLE discovery.broker_inbox ADD CONSTRAINT fixture_reject_applied CHECK (outcome<>'APPLIED') NOT VALID").update();
        try { assertion.run(); }
        finally { jdbc.sql("ALTER TABLE discovery.broker_inbox DROP CONSTRAINT fixture_reject_applied").update(); }
    }

    private String inboxOutcome(ObjectNode event) {
        return jdbc.sql("SELECT outcome FROM discovery.broker_inbox WHERE event_id=:id")
                .param("id",event.get("eventId").textValue()).query(String.class).single();
    }
    private long lifecycleVersion() { return jdbc.sql("SELECT session_version FROM discovery.session_lifecycle").query(Long.class).single(); }
    private long projectionVersion() { return jdbc.sql("SELECT projection_version FROM discovery.stream_projection").query(Long.class).single(); }
    private String deliverBroker(ObjectNode event) {
        return broker.ingest("stream.session.changed.v1".equals(event.get("eventType").textValue())?"core.lifecycle":"core.discovery",event);
    }

    private ObjectNode lifecycle(long generation,long version,String status) {
        var payload=json.createObjectNode();
        payload.put("streamId","str_broker").put("channelId","chn_broker").put("sessionId","ses_broker_"+generation)
                .put("streamGeneration",generation).put("sessionVersion",version).put("status",status)
                .put("availability",switch(status) { case "LIVE" -> "PLAYABLE"; case "RECONNECT_GRACE" -> "RECONNECTING"; default -> "OFFLINE"; })
                .put("initialLive",false);
        if("PREPARING".equals(status)) payload.putNull("firstLiveAtUtc");
        else payload.put("firstLiveAtUtc",CLOCK.now().minusSeconds(1).toString());
        return envelope("stream.session.changed.v1","session:"+payload.get("sessionId").textValue(),version,payload);
    }

    private ObjectNode discovery(long version) {
        return envelope("stream.discovery.snapshot.v1","stream:str_broker",version,
                TestPayloads.live("str_broker","chn_broker",version,5,CLOCK.now()));
    }

    private ObjectNode envelope(String type,String aggregate,long sequence,ObjectNode payload) {
        String id=UUID.randomUUID().toString();
        var event=json.createObjectNode();
        event.put("eventId",id).put("eventType",type).put("schemaVersion",1).put("producer","streaming")
                .put("aggregateId",aggregate).put("sequence",sequence).put("occurredAtUtc",CLOCK.now().toString()).put("correlationId",id);
        event.set("payload",payload);
        return event;
    }
}
