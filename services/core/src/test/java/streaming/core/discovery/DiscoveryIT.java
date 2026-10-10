package streaming.core.discovery;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-07 acceptance criteria over the real HTTP server and PostgreSQL: what the public GraphQL returns. */
class DiscoveryIT extends DiscoveryITBase {

    private JsonNode firstError(HttpResponse<String> response) {
        JsonNode errors=json.readTree(response.body()).get("errors");
        assertThat(errors).as(response.body()).isNotNull();
        return errors.get(0);
    }

    // ---- CA-01 ranking and stable pagination

    @Test void ranksByViewersThenMostRecentStartThenStreamId() throws Exception {
        Instant t=CLOCK.now();
        var a=channel("a_chan",null); var b=channel("b_chan",null); var c=channel("c_chan",null); var d=channel("d_chan",null);
        sendAll(startedAgo(live("a",a,1,10),t,600),startedAgo(live("b",b,1,10),t,300),startedAgo(live("c",c,1,10),t,300),startedAgo(live("d",d,1,50),t,900));

        var result=streams(Map.of());

        assertThat(field(result,"streamId")).containsExactly("str_d","str_b","str_c","str_a");
        assertThat(result.get("nextCursor").isNull()).isTrue();
        assertThat(result.get("statusFresh").booleanValue()).isTrue();
        assertThat(count("discovery.ranking_snapshots")).as("a single page needs no frozen ordering").isZero();
    }

    @Test void paginationIsStableWhileViewerCountsChange() throws Exception {
        int[] viewers={50,40,30,20,10};
        for(int i=0;i<5;i++) sendAll(live("s"+i,channel("chan"+i,null),1,viewers[i]));

        var first=streams(Map.of("limit",2));
        assertThat(field(first,"streamId")).containsExactly("str_s0","str_s1");
        String cursor=first.get("nextCursor").textValue();
        assertThat(cursor).isNotBlank();
        assertThat(count("discovery.ranking_snapshots")).isEqualTo(1);

        // the ranking changes completely between page 1 and page 2
        var s0=live("s0",new Channel("usr_chan0","chn_chan0","chan0"),2,1);
        var s4=live("s4",new Channel("usr_chan4","chn_chan4","chan4"),2,999);
        sendAll(s0,s4);

        var second=streams(Map.of("limit",2,"cursor",cursor));
        assertThat(field(second,"streamId")).containsExactly("str_s2","str_s3");
        var third=streams(Map.of("limit",2,"cursor",second.get("nextCursor").textValue()));
        assertThat(field(third,"streamId")).containsExactly("str_s4");
        assertThat(third.get("nextCursor").isNull()).isTrue();
        assertThat(third.get("items").get(0).get("viewerCount").intValue()).as("rows show current values, only the order is frozen").isEqualTo(999);

        var fresh=streams(Map.of("limit",5));
        assertThat(field(fresh,"streamId")).containsExactly("str_s4","str_s1","str_s2","str_s3","str_s0");
    }

    @Test void aCursorBelongsToItsFilterAndExpiresAfterFiveMinutes() throws Exception {
        for(int i=0;i<3;i++) sendAll(live("p"+i,channel("pg"+i,null),1,30-i));
        String cursor=streams(Map.of("limit",2)).get("nextCursor").textValue();

        var otherFilter=graphql(STREAMS,Map.of("limit",2,"cursor",cursor,"q","directo"));
        assertThat(otherFilter.statusCode()).as("a field error answers 200 with partial data").isEqualTo(200);
        assertThat(firstError(otherFilter).at("/extensions/code").textValue()).isEqualTo("INVALID_CURSOR");
        assertThat(firstError(otherFilter).at("/extensions/httpStatus").intValue()).isEqualTo(422);
        assertThat(json.readTree(otherFilter.body()).at("/data/streams").isNull()).isTrue();

        for(String bad:List.of("garbage","00000000-0000-0000-0000-000000000000.1",cursor+"9")) {
            assertThat(firstError(graphql(STREAMS,Map.of("cursor",bad))).at("/extensions/code").textValue()).as(bad).isEqualTo("INVALID_CURSOR");
        }

        CLOCK.advance(Duration.ofMinutes(5).plusSeconds(1));
        for(int i=0;i<3;i++) sendAll(live("p"+i,new Channel("usr_pg"+i,"chn_pg"+i,"pg"+i),2,30-i));   // fresh observations again
        assertThat(firstError(graphql(STREAMS,Map.of("limit",2,"cursor",cursor))).at("/extensions/code").textValue()).isEqualTo("INVALID_CURSOR");
        maintenance.purge();
        assertThat(count("discovery.ranking_snapshots")).as("expired snapshots are purged").isZero();
    }

    // ---- CA-03 / CA-04 title search and what is listed

    @Test void onlyLivePlayableStreamsAreListed() throws Exception {
        Instant t=CLOCK.now();
        var live=channel("is_live",null); var grace=channel("in_grace",null); var never=channel("never_on",null); var ended=channel("was_live",null); var prep=channel("preparing",null);
        sendAll(live("live",live,1,5),TestPayloads.reconnecting("str_grace",grace.channelId(),1,t),TestPayloads.neverStarted("str_never",never.channelId(),1,t),
                TestPayloads.ended("str_ended",ended.channelId(),1,t),(ObjectNode)TestPayloads.neverStarted("str_prep",prep.channelId(),1,t).put("status","PREPARING"));

        assertThat(field(streams(Map.of()),"streamId")).containsExactly("str_live");
        assertThat(count("discovery.stream_projection")).isEqualTo(5);
    }

    @Test void titleSearchIsPartialCaseInsensitiveNfkcAndKeepsAccents() throws Exception {
        sendAll((ObjectNode)live("m",channel("m_chan",null),1,3).put("title","Música en VIVO"),
                (ObjectNode)live("c",channel("c_chan",null),1,2).put("title","Conversación épica"),
                (ObjectNode)live("x",channel("x_chan",null),1,1).put("title","Charla sin acentos"));

        assertThat(field(streams(Map.of("q","vivo")),"streamId")).containsExactly("str_m");
        assertThat(field(streams(Map.of("q","MÚSICA")),"streamId")).containsExactly("str_m");
        assertThat(field(streams(Map.of("q","sica en v")),"streamId")).containsExactly("str_m");
        assertThat(field(streams(Map.of("q","ＣＯＮＶＥＲＳＡＣＩÓＮ")),"streamId")).as("compatibility characters fold with NFKC").containsExactly("str_c");
        assertThat(field(streams(Map.of("q","ÉPICA")),"streamId")).containsExactly("str_c");
        assertThat(field(streams(Map.of("q","musica")),"streamId")).as("accents are preserved: no accent-insensitive match").isEmpty();
        assertThat(field(streams(Map.of("q","epica")),"streamId")).isEmpty();
        assertThat(field(streams(Map.of("q","   ")),"streamId")).as("blank means no filter").hasSize(3);
        assertThat(field(streams(Map.of("q","charla%")),"streamId")).as("wildcards are literals").isEmpty();
        assertThat(field(streams(Map.of("q","' OR 1=1 --")),"streamId")).as("user text is a parameter").isEmpty();
    }

    @Test void searchTextPostgreSqlCannotStoreIsAFieldErrorNotAnOutage() throws Exception {
        sendAll(live("nul",channel("nul_c",null),1,5));
        for(String escaped:List.of("\\u0000","abc\\u0000def","\\u0000x","x\\u0000")) {   // JSON escape of a NUL character
            for(String root:List.of("streams","channels")) {
                String body="{\"query\":\"query($q:String){"+root+"(q:$q){items{"+(root.equals("streams")?"streamId":"handle")+"}}}\",\"variables\":{\"q\":\""+escaped+"\"}}";
                var response=graphqlRaw(body);
                assertThat(response.statusCode()).as(root+" "+escaped+" "+response.body()).isEqualTo(200);
                JsonNode error=firstError(response);
                assertThat(error.at("/extensions/code").textValue()).isEqualTo("INVALID_FILTER");
                assertThat(error.at("/extensions/httpStatus").intValue()).isEqualTo(422);
                assertThat(error.at("/extensions/fieldErrors/q").textValue()).isEqualTo("INVALID_TEXT");
                assertThat(json.readTree(response.body()).at("/data/"+root).isNull()).isTrue();
            }
        }
        // an unpaired surrogate is not a NUL: whatever the JSON layer decides, it must never become a server error
        var lone=graphqlRaw("{\"query\":\"query($q:String){streams(q:$q){items{streamId}}}\",\"variables\":{\"q\":\"\\ud800\"}}");
        assertThat(lone.statusCode()).as(lone.body()).isLessThan(500);
        assertThat(field(streams(Map.of("q","directo nul")),"streamId")).as("normal searches are unaffected").containsExactly("str_nul");
    }

    // ---- CA-05 / CA-07 filters and tombstones

    @Test void categoryAndSingleTagAreCombinedWithAnd() throws Exception {
        sendAll(tags(category(live("one",channel("one_c",null),1,5),CAT_1,"Conversación"),TAG_1,"Español"),
                tags(category(live("two",channel("two_c",null),1,4),CAT_1,"Conversación"),TAG_2,"Inglés"),
                tags(category(live("three",channel("three_c",null),1,3),CAT_2,"Videojuegos"),TAG_1,"Español"),
                tags(category(live("four",channel("four_c",null),1,2),CAT_2,"Videojuegos"),TAG_1,"Español",TAG_2,"Inglés"));

        assertThat(field(streams(Map.of("categoryId",CAT_1)),"streamId")).containsExactly("str_one","str_two");
        assertThat(field(streams(Map.of("tagId",TAG_1)),"streamId")).containsExactly("str_one","str_three","str_four");
        assertThat(field(streams(Map.of("tagId",TAG_2)),"streamId")).containsExactly("str_two","str_four");
        assertThat(field(streams(Map.of("categoryId",CAT_1,"tagId",TAG_1)),"streamId")).containsExactly("str_one");
        assertThat(field(streams(Map.of("categoryId",CAT_2,"tagId",TAG_2)),"streamId")).containsExactly("str_four");
        assertThat(field(streams(Map.of("categoryId",CAT_1,"tagId",TAG_3)),"streamId")).as("a valid combination with no match is empty, not an error").isEmpty();
        assertThat(field(streams(Map.of("categoryId",CAT_2,"tagId",TAG_1,"q","DIRECTO FOUR")),"streamId")).containsExactly("str_four");
    }

    @Test void unknownInactiveOrWrongKindFiltersAreFieldErrorsWithPartialData() throws Exception {
        sendAll(live("a",channel("a_c",null),1,5));
        jdbc.sql("UPDATE taxonomy.tags SET active=FALSE WHERE id=:id").param("id",TAG_3).update();

        record Case(Map<String,Object> variables,String field) { }
        for(var c:List.of(new Case(Map.of("categoryId","cat_does_not_exist"),"categoryId"),new Case(Map.of("tagId","tag_does_not_exist"),"tagId"),
                new Case(Map.of("tagId",TAG_3),"tagId"),new Case(Map.of("categoryId",TAG_1),"categoryId"),new Case(Map.of("tagId",CAT_1),"tagId"),
                new Case(Map.of("categoryId","has space"),"categoryId"))) {
            var response=graphql(STREAMS,c.variables());
            assertThat(response.statusCode()).as(c.toString()).isEqualTo(200);
            var error=firstError(response);
            assertThat(error.at("/extensions/code").textValue()).as(c.toString()).isEqualTo("INVALID_FILTER");
            assertThat(error.at("/extensions/httpStatus").intValue()).isEqualTo(422);
            assertThat(error.at("/extensions/fieldErrors/"+c.field()).isMissingNode()).as(c.toString()).isFalse();
            assertThat(error.at("/path/0").textValue()).isEqualTo("streams");
            assertThat(json.readTree(response.body()).at("/data/streams").isNull()).isTrue();
        }
        var unaffected=graphql("{ channels { items { handle } } }",Map.of());
        assertThat(ok(unaffected).at("/channels/items").size()).as("the other root field still resolves").isEqualTo(1);
    }

    @Test void aFieldErrorInOneRootDoesNotHideTheOther() throws Exception {
        channel("solo",null);
        var response=graphql("{ streams(limit:0){items{streamId}} channels(limit:5){items{handle}} }",Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        var body=json.readTree(response.body());
        assertThat(body.at("/data/streams").isNull()).isTrue();
        assertThat(body.at("/data/channels/items/0/handle").textValue()).isEqualTo("solo");
        assertThat(body.at("/errors/0/extensions/code").textValue()).isEqualTo("INVALID_LIMIT");
        assertThat(body.at("/errors/0/extensions/httpStatus").intValue()).isEqualTo(422);
    }

    @Test void aDeactivatedValueKeepsItsLabelOnExistingStreamsButCannotBeUsedAsAFilter() throws Exception {
        sendAll(tags(category(live("old",channel("old_c",null),1,5),CAT_2,"Videojuegos"),TAG_2,"Inglés"));
        jdbc.sql("UPDATE taxonomy.categories SET active=FALSE WHERE id=:id").param("id",CAT_2).update();
        jdbc.sql("UPDATE taxonomy.tags SET active=FALSE,name='Renombrado' WHERE id=:id").param("id",TAG_2).update();
        try {
            var items=streams(Map.of()).get("items");
            assertThat(items).hasSize(1);
            assertThat(items.get(0).at("/category/id").textValue()).isEqualTo(CAT_2);
            assertThat(items.get(0).at("/category/name").textValue()).as("label of the association, from the stream projection").isEqualTo("Videojuegos");
            assertThat(items.get(0).at("/tags/0/name").textValue()).as("not the renamed catalogue label").isEqualTo("Inglés");

            assertThat(firstError(graphql(STREAMS,Map.of("categoryId",CAT_2))).at("/extensions/code").textValue()).isEqualTo("INVALID_FILTER");
            assertThat(firstError(graphql(STREAMS,Map.of("tagId",TAG_2))).at("/extensions/code").textValue()).isEqualTo("INVALID_FILTER");
        } finally {
            jdbc.sql("UPDATE taxonomy.tags SET name='Inglés' WHERE id=:id").param("id",TAG_2).update();
        }
    }

    // ---- CA-10 freshness

    @Test void aStreamIsListedForExactlyFiveSecondsAfterItsObservation() throws Exception {
        sendAll(live("fresh",channel("fresh_c",null),1,7));

        CLOCK.advance(Duration.ofSeconds(5));
        var edge=streams(Map.of());
        assertThat(field(edge,"streamId")).as("age of 5 s is still fresh").containsExactly("str_fresh");
        assertThat(edge.get("items").get(0).get("statusFresh").booleanValue()).isTrue();

        CLOCK.advance(Duration.ofMillis(1));
        assertThat(field(streams(Map.of()),"streamId")).as("older than 5 s: excluded, never shown as live").isEmpty();
        assertThat(streams(Map.of()).get("statusFresh").booleanValue()).as("an empty connection is not stale").isTrue();
    }

    @Test void anObservationFromTheFutureBeyondTheSkewAllowanceIsNotTrusted() throws Exception {
        Instant future=CLOCK.now().plusSeconds(30);
        sendAll(TestPayloads.live("str_future","chn_future_c",1,5,future));
        channel("future_c",null);
        assertThat(field(streams(Map.of()),"streamId")).isEmpty();
    }

    @Test void viewerCountFreshnessIsIndependentFromStateFreshness() throws Exception {
        Instant t=CLOCK.now();
        var stale=live("stale_count",channel("sc_c",null),1,40);
        stale.put("viewerCountObservedAtUtc",TestPayloads.pg(t.minusSeconds(6)));
        var current=live("current_count",channel("cc_c",null),1,10);
        sendAll(stale,current);

        var items=streams(Map.of()).get("items");
        assertThat(field(streams(Map.of()),"streamId")).containsExactly("str_stale_count","str_current_count");
        assertThat(items.get(0).get("statusFresh").booleanValue()).isTrue();
        assertThat(items.get(0).get("viewerCountFresh").booleanValue()).isFalse();
        assertThat(items.get(0).get("viewerCount").intValue()).as("the last known count is still reported").isEqualTo(40);
        assertThat(items.get(0).get("viewerCountObservedAtUtc").textValue()).startsWith(t.minusSeconds(6).toString().substring(0,19));
        assertThat(items.get(1).get("viewerCountFresh").booleanValue()).isTrue();
    }

    @Test void aChangeReachesTheQueryAsSoonAsItIsAppliedWithoutWaitingForAnyCycle() throws Exception {
        var ch=channel("quick",null);
        assertThat(field(streams(Map.of()),"streamId")).isEmpty();
        sendAll(live("quick",ch,1,1));
        assertThat(field(streams(Map.of()),"streamId")).containsExactly("str_quick");
        sendAll(TestPayloads.ended("str_quick",ch.channelId(),2,CLOCK.now()));
        assertThat(field(streams(Map.of()),"streamId")).isEmpty();
        sendAll(TestPayloads.reconnecting("str_quick",ch.channelId(),3,CLOCK.now()));
        assertThat(field(streams(Map.of()),"streamId")).as("grace is not playable").isEmpty();
        sendAll(live("quick",ch,4,9));
        assertThat(streams(Map.of()).get("items").get(0).get("viewerCount").intValue()).isEqualTo(9);
    }

    // ---- the exact shape of a stream

    @Test void aStreamExposesOnlyTheContractFields() throws Exception {
        Instant t=CLOCK.now();
        var ch=channel("shape",null);
        jdbc.sql("UPDATE profile.profiles SET avatar_key='avatar-key-1.png',display_name='Nombre Público' WHERE user_id=:u").param("u",ch.userId()).update();
        sendAll(tags(category(live("shape",ch,3,12),CAT_1,"Conversación"),TAG_1,"Español",TAG_3,"Educativo"));

        var item=streams(Map.of()).get("items").get(0);

        assertThat(item.get("streamId").textValue()).isEqualTo("str_shape");
        assertThat(item.get("sessionId").textValue()).isEqualTo("ses_str_shape");
        assertThat(item.at("/channel/channelId").textValue()).isEqualTo("chn_shape");
        assertThat(item.at("/channel/handle").textValue()).isEqualTo("shape");
        assertThat(item.at("/channel/displayName").textValue()).isEqualTo("Nombre Público");
        assertThat(item.at("/channel/avatarUri").textValue()).endsWith("/avatar-key-1.png");
        assertThat(item.get("title").textValue()).isEqualTo("Directo shape");
        assertThat(item.at("/category/id").textValue()).isEqualTo(CAT_1);
        assertThat(item.get("tags")).extracting(tag->tag.get("id").textValue()).containsExactly(TAG_1,TAG_3);
        assertThat(item.get("status").textValue()).isEqualTo("LIVE");
        assertThat(item.get("availability").textValue()).isEqualTo("PLAYABLE");
        assertThat(item.get("viewerCount").intValue()).isEqualTo(12);
        assertThat(Instant.parse(item.get("startedAtUtc").textValue())).isEqualTo(t.minusSeconds(600));
        assertThat(item.get("metadataVersion").longValue()).isEqualTo(3);
        assertThat(item.get("sessionVersion").longValue()).isEqualTo(4);
        assertThat(Instant.parse(streams(Map.of()).get("generatedAtUtc").textValue())).isEqualTo(t);
    }

    @Test void internalOrPrivateFieldsCannotBeRequestedAtAll() throws Exception {
        sendAll(live("priv",channel("priv_c",null),1,5));
        for(String field:List.of("email","passwordHash","streamKey","streamGeneration","projectionVersion","discoveryPosition","countVersion","ingestUrl")) {
            var response=graphql("{ streams { items { "+field+" } } }",Map.of());
            assertThat(response.statusCode()).as(field).isEqualTo(400);
            assertThat(firstError(response).at("/extensions/code").textValue()).as(field).isEqualTo("BAD_REQUEST");
        }
        for(String field:List.of("email","passwordHash","bio","createdAtUtc")) {
            assertThat(graphql("{ channels { items { "+field+" } } }",Map.of()).statusCode()).as(field).isEqualTo(400);
        }
        var everything=json.writeValueAsString(streams(Map.of()))+json.writeValueAsString(channels(Map.of()));
        assertThat(everything).doesNotContain("private-mail.example.test","fixture-hash","streamGeneration","projectionVersion","discoveryPosition");
    }

    // ---- CA-02 / CA-09 channels

    @Test void channelsReportLiveReconnectingOfflineAndUnknownWithoutInventingState() throws Exception {
        Instant t=CLOCK.now();
        var live=channel("c_live",null); var grace=channel("c_grace",null); var off=channel("c_off",null); var none=channel("c_none",null);
        sendAll(live("live",live,1,5),TestPayloads.reconnecting("str_grace",grace.channelId(),1,t),TestPayloads.neverStarted("str_off",off.channelId(),1,t));

        var byHandle=toMap(channels(Map.of("limit",50)),"handle");
        assertThat(byHandle.get("c_live").get("status").textValue()).isEqualTo("LIVE");
        assertThat(byHandle.get("c_live").get("availability").textValue()).isEqualTo("PLAYABLE");
        assertThat(byHandle.get("c_live").get("title").textValue()).isEqualTo("Directo live");
        assertThat(byHandle.get("c_live").get("statusFresh").booleanValue()).isTrue();
        assertThat(byHandle.get("c_grace").get("status").textValue()).isEqualTo("LIVE");
        assertThat(byHandle.get("c_grace").get("availability").textValue()).isEqualTo("RECONNECTING");
        assertThat(byHandle.get("c_off").get("status").textValue()).isEqualTo("OFFLINE");
        assertThat(byHandle.get("c_off").get("availability").textValue()).isEqualTo("OFFLINE");
        assertThat(byHandle.get("c_off").get("statusFresh").booleanValue()).isTrue();
        assertThat(byHandle.get("c_none").get("status").textValue()).as("no confirmation yet").isEqualTo("UNKNOWN");
        assertThat(byHandle.get("c_none").get("availability").textValue()).isEqualTo("UNKNOWN");
        assertThat(byHandle.get("c_none").get("statusFresh").booleanValue()).isFalse();
        assertThat(byHandle.get("c_none").get("title").isNull()).isTrue();
        assertThat(byHandle.get("c_none").get("metadataVersion").isNull()).isTrue();
        assertThat(none.channelId()).isEqualTo(byHandle.get("c_none").get("channelId").textValue());
        assertThat(channels(Map.of()).get("statusFresh").booleanValue()).as("not every row is confirmed").isFalse();
    }

    @Test void aStaleStatusBecomesUnknownNeverOffline() throws Exception {
        sendAll(live("will_age",channel("ages",null),1,5));
        assertThat(toMap(channels(Map.of()),"handle").get("ages").get("status").textValue()).isEqualTo("LIVE");

        CLOCK.advance(Duration.ofSeconds(6));
        var aged=toMap(channels(Map.of()),"handle").get("ages");
        assertThat(aged.get("status").textValue()).isEqualTo("UNKNOWN");
        assertThat(aged.get("availability").textValue()).isEqualTo("UNKNOWN");
        assertThat(aged.get("statusFresh").booleanValue()).isFalse();
        assertThat(aged.get("title").textValue()).as("last known metadata stays visible").isEqualTo("Directo will_age");
    }

    @Test void aFreshCutProvesAbsenceSoAConfigurationlessChannelIsOffline() throws Exception {
        channel("no_config",null);
        CUTS.serve(new CutServer.Cut(10,CLOCK.now(),List.of()));
        assertThat(reconciler.runOnce()).isEqualTo(streaming.core.discovery.application.ProjectionReconciler.Result.SUCCESS);

        var proven=toMap(channels(Map.of()),"handle").get("no_config");
        assertThat(proven.get("status").textValue()).isEqualTo("OFFLINE");
        assertThat(proven.get("statusFresh").booleanValue()).isTrue();

        CLOCK.advance(Duration.ofSeconds(6));
        var aged=toMap(channels(Map.of()),"handle").get("no_config");
        assertThat(aged.get("status").textValue()).as("the proof expires with the cut").isEqualTo("UNKNOWN");
        assertThat(aged.get("statusFresh").booleanValue()).isFalse();
    }

    @Test void onlyCompleteAccountsWithProfileAndChannelAreEverVisible() throws Exception {
        channel("complete","Completa");
        jdbc.sql("INSERT INTO identity.accounts(user_id,email,normalized_email,handle,canonical_handle,password_hash,created_at_utc) VALUES "
                +"('usr_noprofile','np@private-mail.example.test','np@private-mail.example.test','noprofile','noprofile','x',now()),"
                +"('usr_nochannel','nc@private-mail.example.test','nc@private-mail.example.test','nochannel','nochannel','x',now())").update();
        jdbc.sql("INSERT INTO profile.profiles(user_id,display_name,bio,profile_version,created_at_utc,updated_at_utc) VALUES ('usr_nochannel','Sin canal','',0,now(),now())").update();
        jdbc.sql("INSERT INTO identity.accounts(user_id,email,normalized_email,handle,canonical_handle,password_hash,created_at_utc) VALUES "
                +"('usr_halfway','hw@private-mail.example.test','hw@private-mail.example.test','halfway','halfway','x',now())").update();
        jdbc.sql("INSERT INTO channels.channels(channel_id,owner_user_id,description,channel_version,created_at_utc,updated_at_utc) VALUES ('chn_halfway','usr_halfway','',0,now(),now())").update();

        assertThat(field(channels(Map.of("q","no")),"handle")).as("account without profile or channel").isEmpty();
        assertThat(field(channels(Map.of("q","halfway")),"handle")).as("channel whose owner has no profile yet").isEmpty();
        assertThat(field(channels(Map.of("q","sin canal")),"handle")).isEmpty();
        assertThat(field(channels(Map.of()),"handle")).containsExactly("complete");
    }

    @Test void channelSearchOrdersExactThenPrefixThenSubstringAndHandleBeforeDisplayName() throws Exception {
        channel("xalpha",null); channel("zeta","Alpha Fan"); channel("alphabet",null); channel("alpha",null); channel("unrelated","Nada");
        channel("beta","Fan de ALPHA");

        assertThat(field(channels(Map.of("q","alpha")),"handle")).containsExactly("alpha","alphabet","zeta","xalpha","beta");
        assertThat(field(channels(Map.of("q","ALPHA")),"handle")).as("case-insensitive").containsExactly("alpha","alphabet","zeta","xalpha","beta");
        assertThat(field(channels(Map.of("q","  alpha ")),"handle")).as("surrounding blanks are ignored").containsExactly("alpha","alphabet","zeta","xalpha","beta");
        assertThat(field(channels(Map.of("q","fan")),"handle")).as("display name prefix before display name substring").containsExactly("beta","zeta");
        assertThat(field(channels(Map.of("q","")),"handle")).as("empty search lists every channel by handle").containsExactly("alpha","alphabet","beta","unrelated","xalpha","zeta");
        assertThat(field(channels(Map.of("q","nomatch")),"handle")).isEmpty();
    }

    @Test void channelSearchKeepsAccentsAndFoldsCompatibilityCharacters() throws Exception {
        channel("xyz","Ángela Pérez"); channel("angela_tv",null); channel("plain","Maria");

        assertThat(field(channels(Map.of("q","ángela")),"handle")).containsExactly("xyz");
        assertThat(field(channels(Map.of("q","ÁNGELA")),"handle")).containsExactly("xyz");
        assertThat(field(channels(Map.of("q","ÁNGELA PÉREZ")),"handle")).containsExactly("xyz");
        assertThat(field(channels(Map.of("q","angela")),"handle")).as("no accent-insensitive match on the display name").containsExactly("angela_tv");
        assertThat(field(channels(Map.of("q","ＡＮＧＥＬＡ")),"handle")).as("full-width folds to ASCII").containsExactly("angela_tv");
        assertThat(field(channels(Map.of("q","maría")),"handle")).isEmpty();
    }

    @Test void channelPagingWalksEveryChannelOnceInOrderEvenWhileChannelsAreAdded() throws Exception {
        for(int i=1;i<=7;i++) channel(String.format("h%02d",i),null);
        var seen=new java.util.ArrayList<String>();
        String cursor=null;
        int pages=0;
        do {
            var page=cursor==null?channels(Map.of("limit",3)):channels(Map.of("limit",3,"cursor",cursor));
            seen.addAll(field(page,"handle"));
            if(pages==0) { channel("h00",null); channel("h035",null); }   // h00 sorts before the cursor, h035 after it
            if(pages==1) channel("h99",null);
            cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").textValue();
            pages++;
        } while(cursor!=null && pages<10);

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsSubsequence("h01","h02","h03","h04","h05","h06","h07");
        assertThat(seen).as("inserted after the cursor: seen exactly once").containsOnlyOnce("h035","h99").doesNotContain("h00");
        assertThat(seen).isSortedAccordingTo(java.util.Comparator.naturalOrder());
    }

    @Test void aChannelCursorOnlyWorksForItsSearch() throws Exception {
        for(int i=1;i<=4;i++) channel("same"+i,null);
        String cursor=channels(Map.of("q","same","limit",2)).get("nextCursor").textValue();
        assertThat(field(channels(Map.of("q","same","limit",2,"cursor",cursor)),"handle")).containsExactly("same3","same4");
        for(var variables:List.<Map<String,Object>>of(Map.of("q","other","limit",2,"cursor",cursor),Map.of("limit",2,"cursor",cursor),Map.of("cursor","!!!!"))) {
            var response=graphql(CHANNELS,variables);
            assertThat(firstError(response).at("/extensions/code").textValue()).as(variables.toString()).isEqualTo("INVALID_CURSOR");
        }
    }

    @Test void aChannelShowsItsCurrentPublicIdentity() throws Exception {
        var ch=channel("identity","Nombre Visible");
        jdbc.sql("UPDATE channels.channels SET channel_version=7 WHERE channel_id=:c").param("c",ch.channelId()).update();
        sendAll(live("identity",ch,1,2));

        var item=channels(Map.of("q","identity")).get("items").get(0);
        assertThat(item.get("channelId").textValue()).isEqualTo("chn_identity");
        assertThat(item.get("userId").textValue()).isEqualTo("usr_identity");
        assertThat(item.get("displayName").textValue()).isEqualTo("Nombre Visible");
        assertThat(item.get("channelVersion").longValue()).isEqualTo(7);
        assertThat(item.get("metadataVersion").longValue()).isEqualTo(3);
        assertThat(item.get("sessionVersion").longValue()).isEqualTo(4);
        assertThat(item.get("avatarUri").isNull()).isTrue();

        jdbc.sql("UPDATE profile.profiles SET display_name='Cambiado' WHERE user_id=:u").param("u",ch.userId()).update();
        assertThat(channels(Map.of("q","identity")).get("items").get(0).get("displayName").textValue()).as("no copy: reads the live public view").isEqualTo("Cambiado");
    }

    @Test void aDatabaseOutageIs503WithoutLeakingDetailsAndRecoversByItself() throws Exception {
        channel("outage",null);
        jdbc.sql("ALTER TABLE discovery.stream_projection RENAME TO stream_projection_away").update();
        try {
            var response=graphql("{ streams { items { streamId } } channels { items { handle } } }",Map.of());
            assertThat(response.statusCode()).isEqualTo(503);
            JsonNode body=json.readTree(response.body());
            assertThat(body.at("/errors/0/extensions/code").textValue()).isEqualTo("DISCOVERY_UNAVAILABLE");
            assertThat(body.at("/errors/0/extensions/httpStatus").intValue()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("stream_projection","PSQL","SQL","relation","org.postgresql");
        } finally {
            jdbc.sql("ALTER TABLE discovery.stream_projection_away RENAME TO stream_projection").update();
        }
        assertThat(field(channels(Map.of()),"handle")).as("recovers without a restart").containsExactly("outage");
    }

    // ---- CA-06 latency at the P1 profile: 5 live streams, 100 channels

    @Test void p95LatencyAtTheP1ProfileIsFarBelowTwoSeconds() throws Exception {
        for(int i=0;i<100;i++) {
            var ch=channel(String.format("perf%03d",i),"Canal de prueba "+i);
            if(i<5) sendAll(live("perf"+i,ch,1,100-i));
        }
        var millis=new java.util.ArrayList<Long>();
        for(int i=0;i<120;i++) {
            long start=System.nanoTime();
            var response=i%2==0?graphql(STREAMS,Map.of("limit",20)):graphql(CHANNELS,Map.of("q","prueba "+(i%10),"limit",20));
            millis.add((System.nanoTime()-start)/1_000_000);
            assertThat(response.statusCode()).isEqualTo(200);
        }
        millis.sort(Long::compare);
        long p95=millis.get((int)Math.ceil(millis.size()*0.95)-1);
        assertThat(p95).as("p95 in ms, samples=%s",millis).isLessThanOrEqualTo(2000);
    }

    private static Map<String,JsonNode> toMap(JsonNode connection,String key) {
        var map=new java.util.LinkedHashMap<String,JsonNode>();
        for(JsonNode item:connection.get("items")) map.put(item.get(key).textValue(),item);
        return map;
    }
}
