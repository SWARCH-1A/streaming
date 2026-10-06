package streaming.core.discovery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import streaming.core.discovery.application.ProjectionReconciler.Result;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recovery from Streaming's private consistent cut (a stand-in server that follows the real contract): rebuild,
 * repair, convergence with events received meanwhile, expiry, and the rule that a failure never corrupts what was served.
 */
class DiscoveryReconcileIT extends DiscoveryITBase {

    private long version(String streamId) { return jdbc.sql("SELECT projection_version FROM discovery.stream_projection WHERE stream_id=:s").param("s",streamId).query(Long.class).single(); }
    private boolean exists(String streamId) { return jdbc.sql("SELECT count(*) FROM discovery.stream_projection WHERE stream_id=:s").param("s",streamId).query(Long.class).single()==1; }
    private Map<String,Object> state() { return jdbc.sql("SELECT last_snapshot_id,last_watermark,last_failure_code,last_success_at_utc,last_cut_captured_at_utc FROM discovery.reconciliation_state").query().singleRow(); }
    private JsonNode item(String name,Channel channel,long version,int viewers) { return live(name,channel,version,viewers); }

    @Test void anEmptyProjectionIsRebuiltFromAllPagesOfTheCut() throws Exception {
        var a=channel("ra",null); var b=channel("rb",null); var c=channel("rc",null);
        CUTS.pageSize(2);
        CUTS.serve(new CutServer.Cut(500,CLOCK.now(),List.of(item("ra",a,4,30),item("rb",b,9,20),item("rc",c,2,10))));

        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);

        assertThat(count("discovery.stream_projection")).isEqualTo(3);
        assertThat(version("str_rb")).isEqualTo(9);
        assertThat(field(streams(Map.of()),"streamId")).containsExactly("str_ra","str_rb","str_rc");
        assertThat(CUTS.requests.get()).as("two pages").isEqualTo(2);
        assertThat(CUTS.cutsStarted.get()).isEqualTo(1);
        assertThat(CUTS.authorizations).containsOnly("Bearer "+CONSUMER_TOKEN);
        assertThat(CUTS.requestBodies.getFirst()).contains("\"limit\":50").doesNotContain("cursor");
        assertThat(CUTS.requestBodies.get(1)).contains("\"cursor\"");
        var state=state();
        assertThat(state.get("last_watermark")).isEqualTo(500L);
        assertThat(state.get("last_failure_code")).isNull();
        assertThat(state.get("last_snapshot_id")).isNotNull();
    }

    @Test void theCutRepairsLostEventsButNeverOverwritesNewerOnes() throws Exception {
        var a=channel("ka",null); var b=channel("kb",null); var c=channel("kc",null);
        sendAll(live("ka",a,7,70),live("kb",b,1,10));                           // ka is newer than the cut, kb older, kc missing
        CUTS.serve(new CutServer.Cut(900,CLOCK.now(),List.of(item("ka",a,3,3),item("kb",b,5,55),item("kc",c,2,22))));

        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);

        assertThat(version("str_ka")).as("the event is newer than the cut").isEqualTo(7);
        assertThat(version("str_kb")).as("the cut repaired a lost event").isEqualTo(5);
        assertThat(version("str_kc")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT viewer_count FROM discovery.stream_projection WHERE stream_id='str_ka'").query(Integer.class).single()).isEqualTo(70);
        assertThat(count("discovery.projection_conflicts")).isZero();
    }

    @Test void aRowAbsentFromTheCutIsRemovedOnlyIfItWasCommittedAtOrBeforeTheWatermark() throws Exception {
        var old=channel("old",null); var newer=channel("new",null); var kept=channel("kept",null);
        sendAll(live("old",old,10,1),                 // discoveryPosition 100
                live("new",newer,500,1),              // discoveryPosition 5000: published after the cut was taken
                live("kept",kept,3,1));               // present in the cut
        CUTS.serve(new CutServer.Cut(1000,CLOCK.now(),List.of(item("kept",kept,3,1))));

        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);

        assertThat(exists("str_old")).as("proved absent by the cut").isFalse();
        assertThat(exists("str_new")).as("newer than the cut: could not be in it").isTrue();
        assertThat(exists("str_kept")).isTrue();
    }

    @Test void eventsReceivedWhileTheCutIsBeingReadAreNotLostOrDowngraded() throws Exception {
        var a=channel("wa",null); var late=channel("wlate",null);
        sendAll(live("wa",a,1,1));
        CUTS.pageSize(1);
        CUTS.serve(new CutServer.Cut(100,CLOCK.now(),List.of(item("wa",a,3,30),item("wb",channel("wb",null),2,20))));
        CUTS.onPage(page-> {
            if(page==1) {                                // between page 1 and page 2 Streaming keeps publishing
                sendAll(live("wa",a,9,90),live("wlate",late,50,5));   // newer version, and a stream born after the cut (position 500 > watermark 100)
            }
        });

        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);

        assertThat(version("str_wa")).as("the live event wins over the older cut").isEqualTo(9);
        assertThat(exists("str_wlate")).as("born after the cut: not pruned").isTrue();
        assertThat(exists("str_wb")).isTrue();
        assertThat(jdbc.sql("SELECT viewer_count FROM discovery.stream_projection WHERE stream_id='str_wa'").query(Integer.class).single()).isEqualTo(90);
    }

    @Test void anExpiredCutIsRestartedFromTheFirstPage() throws Exception {
        var a=channel("ea",null); var b=channel("eb",null);
        CUTS.pageSize(1);
        CUTS.serve(new CutServer.Cut(50,CLOCK.now(),List.of(item("ea",a,1,1),item("eb",b,1,1))));
        CUTS.expireNextFollowUp();

        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);

        assertThat(CUTS.cutsStarted.get()).as("a second cut was started after the 410").isEqualTo(2);
        assertThat(count("discovery.stream_projection")).isEqualTo(2);
        assertThat(state().get("last_failure_code")).isNull();
    }

    @Test void anUnavailableStreamingKeepsWhatWasServedAndRecordsTheFailure() throws Exception {
        var a=channel("fa",null);
        CUTS.serve(new CutServer.Cut(10,CLOCK.now(),List.of(item("fa",a,1,5))));
        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);
        Object capturedBefore=state().get("last_cut_captured_at_utc");
        Object successBefore=state().get("last_success_at_utc");
        CLOCK.advance(Duration.ofSeconds(2));
        sendAll(live("fa",a,2,6));                      // the event path keeps the projection fresh meanwhile

        CUTS.failWith(503);
        assertThat(reconciler.runOnce()).isEqualTo(Result.FAILED);

        assertThat(count("discovery.stream_projection")).isEqualTo(1);
        assertThat(version("str_fa")).isEqualTo(2);
        assertThat(field(streams(Map.of()),"streamId")).as("events keep serving while the cut is down").containsExactly("str_fa");
        var state=state();
        assertThat(state.get("last_failure_code")).isEqualTo("HTTP_503");
        assertThat(state.get("last_cut_captured_at_utc")).as("the proof of absence does not rejuvenate").isEqualTo(capturedBefore);
        assertThat(state.get("last_success_at_utc")).isEqualTo(successBefore);

        CUTS.failWith(0);
        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);
        assertThat(state().get("last_failure_code")).as("recovery clears the failure").isNull();
    }

    @Test void aBadCutIsRejectedAsAWholeAndNothingOfItIsPublished() throws Exception {
        var a=channel("ga",null); var b=channel("gb",null);
        sendAll(live("ga",a,1,1));
        ObjectNode broken=(ObjectNode)item("gb",b,1,1);
        broken.put("availability","SOMETHING_ELSE");
        CUTS.pageSize(1);
        CUTS.serve(new CutServer.Cut(10,CLOCK.now(),List.of(item("ga",a,5,50),broken)));

        assertThat(reconciler.runOnce()).isEqualTo(Result.FAILED);

        assertThat(version("str_ga")).as("the valid first page was not applied").isEqualTo(1);
        assertThat(exists("str_gb")).isFalse();
        assertThat(state().get("last_failure_code")).isEqualTo("INVALID_EVENT");

        CUTS.serve(new CutServer.Cut(10,CLOCK.now(),List.of(item("ga",a,5,50),item("ga",a,6,60))));
        assertThat(reconciler.runOnce()).isEqualTo(Result.FAILED);
        assertThat(state().get("last_failure_code")).isEqualTo("DUPLICATE_IN_CUT");
        assertThat(version("str_ga")).isEqualTo(1);
    }

    @Test void aRestartedConsumerRecoversTheWholeProjectionFromTheCutAlone() throws Exception {
        var channels=List.of(channel("za",null),channel("zb",null),channel("zc",null),channel("zd",null),channel("ze",null));
        var items=new java.util.ArrayList<JsonNode>();
        for(int i=0;i<5;i++) items.add(item("z"+"abcde".charAt(i),channels.get(i),i+1,50-i));
        CUTS.pageSize(2);
        CUTS.serve(new CutServer.Cut(5000,CLOCK.now(),items));
        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);
        var before=jdbc.sql("SELECT stream_id,projection_version,content_hash,viewer_count FROM discovery.stream_projection ORDER BY stream_id").query().listOfRows();

        jdbc.sql("TRUNCATE discovery.stream_projection,discovery.inbox_events").update();     // the read model is lost
        assertThat(field(streams(Map.of()),"streamId")).isEmpty();
        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);

        assertThat(jdbc.sql("SELECT stream_id,projection_version,content_hash,viewer_count FROM discovery.stream_projection ORDER BY stream_id").query().listOfRows()).isEqualTo(before);
        assertThat(field(streams(Map.of("limit",5)),"streamId")).containsExactly("str_za","str_zb","str_zc","str_zd","str_ze");
    }

    @Test void theCutIsCheckedAtOneConsistentInstantEvenWhenTheProjectionIsEmpty() throws Exception {
        channel("empty",null);
        CUTS.serve(new CutServer.Cut(0,CLOCK.now(),List.of()));
        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);
        assertThat(count("discovery.stream_projection")).isZero();
        Instant captured=jdbc.sql("SELECT last_cut_captured_at_utc FROM discovery.reconciliation_state").query(java.sql.Timestamp.class).single().toInstant();
        assertThat(captured).isEqualTo(CLOCK.now());
    }
}
