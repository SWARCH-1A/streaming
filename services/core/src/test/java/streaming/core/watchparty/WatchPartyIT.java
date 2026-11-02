package streaming.core.watchparty;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Watch Party over the real Core (HTTP, session, CSRF, SQL) with a simulated Streaming public API. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class WatchPartyIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    private static final String PASSWORD="correct horse battery staple";
    private static final Map<String,Reply> STREAMING_REPLIES=new ConcurrentHashMap<>();
    private static final List<Map<String,List<String>>> STREAMING_HEADERS=new CopyOnWriteArrayList<>();
    private static final AtomicInteger STREAMING_CALLS=new AtomicInteger();
    private static final HttpServer STREAMING=startStreaming();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",POSTGRES::getUsername);
        properties.add("spring.datasource.password",POSTGRES::getPassword);
        properties.add("core.rate-limit-hmac-secret",()->"integration-only-secret-at-least-32-bytes");
        // These SQL/HTTP tests do not exercise S3; its production default requires a real bucket.
        properties.add("core.images.storage-provider",()->"filesystem");
        properties.add("profile.storage-root",()->tempDirectory("avatars"));
        properties.add("channels.storage-root",()->tempDirectory("banners"));
        properties.add("watchparty.streaming-base-url",()->"http://127.0.0.1:"+STREAMING.getAddress().getPort());
        properties.add("watchparty.streaming-connect-timeout",()->"PT1S");
        properties.add("watchparty.streaming-read-timeout",()->"PT1S");
    }

    @Autowired IdentityApplicationService accounts;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    private int registrations;

    @BeforeEach void reset() {
        jdbc.sql("TRUNCATE identity.accounts,identity.registrations,identity.sessions,identity.rate_limit_events,profile.profiles,profile.avatar_uploads,channels.channels CASCADE").update();
        STREAMING_REPLIES.clear(); STREAMING_HEADERS.clear(); STREAMING_CALLS.set(0);
        registrations=0;
    }

    @AfterAll static void stopStreaming() { STREAMING.stop(0); }

    // --- RF-046 create, CA-01/02/13

    @Test void creatingAPartyShowsTheCodeOnceAndStoresOnlyItsHash() throws Exception {
        var owner=user("owner_01").login();
        var created=owner.send("POST","/api/watch-parties",Map.of("title","  Final del torneo  "),true);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode party=json.readTree(created.body());
        String code=party.get("accessCode").textValue();
        assertThat(code).matches("[A-Za-z0-9_-]{43}");
        assertThat(party.get("partyId").textValue()).matches("wp_[0-9a-f]{32}");
        assertThat(party.get("title").textValue()).isEqualTo("Final del torneo");
        assertThat(party.get("status").textValue()).isEqualTo("OPEN");
        assertThat(party.get("isOwner").booleanValue()).isTrue();
        assertThat(party.get("maxSources").intValue()).isEqualTo(4);
        assertThat(party.get("memberCount").intValue()).isEqualTo(1);
        assertThat(party.get("partyVersion").longValue()).isZero();
        assertThat(party.get("sources")).isEmpty();
        assertThat(party.get("owner").get("handle").textValue()).isEqualTo("owner_01");
        assertThat(party.get("closedAtUtc").isNull()).isTrue();

        String hash=jdbc.sql("SELECT access_code_hash FROM watchparty.parties").query(String.class).single();
        assertThat(hash).isEqualTo(sha256(code)).isNotEqualTo(code);
        assertThat(count("watchparty.party_members")).isEqualTo(1);

        var read=owner.send("GET","/api/watch-parties/"+party.get("partyId").textValue(),null,false);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(json.readTree(read.body()).get("accessCode").isNull()).isTrue();
        assertThat(read.body()).doesNotContain(hash,code).doesNotContain("password","credential","@example.test");
    }

    @Test void creatingRequiresASessionAndAValidTitle() throws Exception {
        var anonymous=new Browser();
        anonymous.csrf();
        var denied=anonymous.send("POST","/api/watch-parties",Map.of("title","Final"),true);
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(json.readTree(denied.body()).get("code").textValue()).isEqualTo("AUTH_REQUIRED");

        var owner=user("owner_01").login();
        List<Object> invalid=List.of(Map.of(),Map.of("title",""),Map.of("title","   "),Map.of("title","x".repeat(101)),Map.of("title",5),
                Map.of("title","ok","ownerUserId","usr_other"),Map.of("title","bad\u0000title"));
        for(Object bad:invalid) {
            var response=owner.send("POST","/api/watch-parties",bad,true);
            assertThat(response.statusCode()).as(String.valueOf(bad)).isEqualTo(400);
            assertThat(json.readTree(response.body()).get("code").textValue()).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(owner.send("POST","/api/watch-parties","[1]",true).statusCode()).isEqualTo(400);
        assertThat(count("watchparty.parties")).isZero();
    }

    @Test void everyMutationRequiresTheSharedCsrfToken() throws Exception {
        var owner=user("owner_01").login();
        String id=createParty(owner,"Final").get("partyId").textValue();
        for(String[] call:new String[][]{{"POST","/api/watch-parties"},{"POST","/api/watch-parties/join"},{"POST","/api/watch-parties/"+id+"/streams"},
                {"POST","/api/watch-parties/"+id+"/access-code/rotate"},{"POST","/api/watch-parties/"+id+"/close"},
                {"DELETE","/api/watch-parties/"+id+"/streams/str_1"}}) {
            var response=owner.send(call[0],call[1],call[0].equals("POST") && !call[1].endsWith("rotate") && !call[1].endsWith("close")?Map.of("x","y"):null,false);
            assertThat(response.statusCode()).as(call[0]+" "+call[1]).isEqualTo(403);
            assertThat(json.readTree(response.body()).get("code").textValue()).isEqualTo("CSRF_INVALID");
        }
        assertThat(count("watchparty.parties")).isEqualTo(1);
    }

    // --- RF-047/049/051 add and read, CA-03/08

    @Test void theOwnerAddsAPlayableStreamAndMembersSeeItWithItsChannel() throws Exception {
        var owner=user("owner_01").login();
        var caster=user("caster_02");
        var member=user("member_03").login();
        String stream=stream("str_caster",caster,"PLAYABLE");
        JsonNode created=createParty(owner,"Final");
        String id=created.get("partyId").textValue();

        var added=owner.send("POST","/api/watch-parties/"+id+"/streams",Map.of("streamId",stream),true);
        assertThat(added.statusCode()).isEqualTo(201);
        JsonNode view=json.readTree(added.body());
        assertThat(view.get("partyVersion").longValue()).isEqualTo(1);
        var entry=view.get("sources").get(0);
        assertThat(entry.get("streamId").textValue()).isEqualTo(stream);
        assertThat(entry.get("title").textValue()).isEqualTo("En vivo");
        assertThat(entry.get("category").textValue()).isEqualTo("Conversación");
        assertThat(entry.get("state").textValue()).isEqualTo("PLAYABLE");
        assertThat(entry.get("sessionId").textValue()).isEqualTo("ses_1");
        assertThat(entry.get("viewerCount").intValue()).isEqualTo(8);
        assertThat(entry.get("statusFresh").booleanValue()).isTrue();
        assertThat(entry.get("channel").get("channelId").textValue()).isEqualTo(caster.channelId);
        assertThat(entry.get("channel").get("handle").textValue()).isEqualTo("caster_02");
        assertThat(entry.get("channel").get("displayName").textValue()).isEqualTo("caster_02");
        assertThat(entry.get("addedAtUtc").isTextual()).isTrue();
        assertThat(jdbc.sql("SELECT channel_id FROM watchparty.party_streams").query(String.class).single()).isEqualTo(caster.channelId);

        // Streaming is called without any credential.
        assertThat(STREAMING_HEADERS).isNotEmpty();
        assertThat(STREAMING_HEADERS).allSatisfy(headers->assertThat(headers.keySet()).noneMatch(h->
                h.equalsIgnoreCase("Cookie") || h.equalsIgnoreCase("Authorization") || h.equalsIgnoreCase("X-Session-Credential")));

        // A member who joined with the code reads the same composition; a stranger cannot even see that it exists.
        String code=created.get("accessCode").textValue();
        assertThat(member.send("POST","/api/watch-parties/join",Map.of("accessCode",code),true).statusCode()).isEqualTo(200);
        var read=member.send("GET","/api/watch-parties/"+id,null,false);
        assertThat(read.statusCode()).isEqualTo(200);
        JsonNode seen=json.readTree(read.body());
        assertThat(seen.get("isOwner").booleanValue()).isFalse();
        assertThat(seen.get("memberCount").intValue()).isEqualTo(2);
        assertThat(seen.get("sources").get(0).get("channel").get("handle").textValue()).isEqualTo("caster_02");
        var stranger=user("stranger_04").login();
        var hidden=stranger.send("GET","/api/watch-parties/"+id,null,false);
        assertThat(hidden.statusCode()).isEqualTo(404);
        assertThat(json.readTree(hidden.body()).get("code").textValue()).isEqualTo("WATCH_PARTY_NOT_FOUND");
        var missing=stranger.send("GET","/api/watch-parties/wp_"+"0".repeat(32),null,false);
        assertThat(missing.statusCode()).isEqualTo(404);
        JsonNode existingButHidden=json.readTree(hidden.body()), reallyMissing=json.readTree(missing.body());
        assertThat(existingButHidden.get("code")).isEqualTo(reallyMissing.get("code"));
        assertThat(existingButHidden.get("message")).isEqualTo(reallyMissing.get("message"));
        assertThat(new Browser().send("GET","/api/watch-parties/"+id,null,false).statusCode()).isEqualTo(401);
    }

    @Test void onlyTheOwnerChangesTheStreamsOfAParty() throws Exception {
        var owner=user("owner_01").login();
        var member=user("member_02").login();
        var stranger=user("stranger_03").login();
        String stream=stream("str_a",user("caster_04"),"PLAYABLE");
        JsonNode created=createParty(owner,"Final");
        String id=created.get("partyId").textValue();
        member.send("POST","/api/watch-parties/join",Map.of("accessCode",created.get("accessCode").textValue()),true);

        var forbidden=member.send("POST","/api/watch-parties/"+id+"/streams",Map.of("streamId",stream),true);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(json.readTree(forbidden.body()).get("code").textValue()).isEqualTo("WATCH_PARTY_FORBIDDEN");
        assertThat(stranger.send("POST","/api/watch-parties/"+id+"/streams",Map.of("streamId",stream),true).statusCode()).isEqualTo(404);
        assertThat(member.send("DELETE","/api/watch-parties/"+id+"/streams/"+stream,null,true).statusCode()).isEqualTo(403);
        assertThat(stranger.send("DELETE","/api/watch-parties/"+id+"/streams/"+stream,null,true).statusCode()).isEqualTo(404);
        assertThat(count("watchparty.party_streams")).isZero();
        assertThat(STREAMING_CALLS.get()).as("authorization is checked before asking Streaming").isZero();
    }

    // --- CA-04 limit and duplicates

    @Test void theLimitOfFourStreamsHoldsEvenWithConcurrentRequests() throws Exception {
        var owner=user("owner_01").login();
        List<String> ids=new ArrayList<>();
        for(int i=0;i<6;i++) ids.add(stream("str_"+i,user("caster_0"+(i+2)),"PLAYABLE"));
        // Streaming answers slowly so all six requests pass the early checks before the first insert commits.
        for(String id:ids) STREAMING_REPLIES.put(id,new Reply(200,live(id,channelOf(id),"PLAYABLE"),150));
        String party=createParty(owner,"Final").get("partyId").textValue();

        List<HttpResponse<String>> results=concurrently(ids.size(),i->owner.send("POST","/api/watch-parties/"+party+"/streams",Map.of("streamId",ids.get(i)),true));

        assertThat(results.stream().filter(r->r.statusCode()==201)).hasSize(4);
        var rejected=results.stream().filter(r->r.statusCode()==409).toList();
        assertThat(rejected).hasSize(2);
        for(var response:rejected) assertThat(json.readTree(response.body()).get("code").textValue()).isEqualTo("WATCH_PARTY_FULL");
        assertThat(count("watchparty.party_streams")).isEqualTo(4);
        assertThat(jdbc.sql("SELECT party_version FROM watchparty.parties").query(Long.class).single()).isEqualTo(4);
    }

    @Test void aStreamCanOnlyBeInAPartyOnceEvenWithConcurrentRequests() throws Exception {
        var owner=user("owner_01").login();
        String stream=stream("str_a",user("caster_02"),"PLAYABLE");
        STREAMING_REPLIES.put(stream,new Reply(200,live(stream,channelOf(stream),"PLAYABLE"),150));
        String party=createParty(owner,"Final").get("partyId").textValue();

        List<HttpResponse<String>> results=concurrently(4,i->owner.send("POST","/api/watch-parties/"+party+"/streams",Map.of("streamId",stream),true));

        assertThat(results.stream().filter(r->r.statusCode()==201)).hasSize(1);
        for(var response:results.stream().filter(r->r.statusCode()!=201).toList()) {
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json.readTree(response.body()).get("code").textValue()).isEqualTo("STREAM_ALREADY_IN_PARTY");
        }
        assertThat(count("watchparty.party_streams")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT party_version FROM watchparty.parties").query(Long.class).single()).isEqualTo(1);
    }

    @Test void concurrentRetriesOfTheSameAddWithTheSameKeyAllSucceedAndAddItOnce() throws Exception {
        var owner=user("owner_01").login();
        String stream=stream("str_a",user("caster_02"),"PLAYABLE");
        STREAMING_REPLIES.put(stream,new Reply(200,live(stream,channelOf(stream),"PLAYABLE"),150));
        String party=createParty(owner,"Final").get("partyId").textValue();
        String key=UUID.randomUUID().toString();

        List<HttpResponse<String>> results=concurrently(5,i->owner.send("POST","/api/watch-parties/"+party+"/streams",Map.of("streamId",stream),true,key));

        // Neither STREAM_ALREADY_IN_PARTY nor WATCH_PARTY_FULL: a retry of a command that succeeded is its recorded success.
        assertThat(results).allSatisfy(r->assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        assertThat(count("watchparty.party_streams")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT party_version FROM watchparty.parties").query(Long.class).single()).isEqualTo(1);
    }

    @Test void concurrentRetriesOfTheAddThatFillsTheRoomAllSucceed() throws Exception {
        var owner=user("owner_01").login();
        List<String> ids=new ArrayList<>();
        for(int i=0;i<4;i++) ids.add(stream("str_"+i,user("caster_0"+(i+2)),"PLAYABLE"));
        String party=createParty(owner,"Final").get("partyId").textValue();
        for(String id:ids.subList(0,3)) assertThat(owner.send("POST","/api/watch-parties/"+party+"/streams",Map.of("streamId",id),true).statusCode()).isEqualTo(201);
        String fourth=ids.get(3);
        STREAMING_REPLIES.put(fourth,new Reply(200,live(fourth,channelOf(fourth),"PLAYABLE"),150));
        String key=UUID.randomUUID().toString();

        List<HttpResponse<String>> results=concurrently(4,i->owner.send("POST","/api/watch-parties/"+party+"/streams",Map.of("streamId",fourth),true,key));

        assertThat(results).allSatisfy(r->assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        assertThat(count("watchparty.party_streams")).isEqualTo(4);
    }

    @Test void theSameStreamMayBeInDifferentParties() throws Exception {
        var owner=user("owner_01").login();
        String stream=stream("str_a",user("caster_02"),"PLAYABLE");
        for(int i=0;i<2;i++) {
            String party=createParty(owner,"Sesión "+i).get("partyId").textValue();
            assertThat(owner.send("POST","/api/watch-parties/"+party+"/streams",Map.of("streamId",stream),true).statusCode()).isEqualTo(201);
        }
        assertThat(count("watchparty.party_streams")).isEqualTo(2);
    }

    // --- CA-05 streams that cannot be added

    @Test void streamsThatAreNotLiveUnknownOrUnreachableAreRejectedAndNothingIsStored() throws Exception {
        var owner=user("owner_01").login();
        var caster=user("caster_02");
        String party=createParty(owner,"Final").get("partyId").textValue();
        String path="/api/watch-parties/"+party+"/streams";

        for(String availability:List.of("OFFLINE","RECONNECTING")) {
            String id=stream("str_"+availability.toLowerCase(),caster,availability);
            var response=owner.send("POST",path,Map.of("streamId",id),true);
            assertThat(response.statusCode()).as(availability).isEqualTo(409);
            assertThat(json.readTree(response.body()).get("code").textValue()).isEqualTo("STREAM_NOT_LIVE");
        }
        var unknown=owner.send("POST",path,Map.of("streamId","str_unknown"),true);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json.readTree(unknown.body()).get("code").textValue()).isEqualTo("STREAM_NOT_FOUND");

        STREAMING_REPLIES.put("str_down",new Reply(503,"{\"code\":\"X\"}",0));
        STREAMING_REPLIES.put("str_garbage",new Reply(200,"not json",0));
        STREAMING_REPLIES.put("str_slow",new Reply(200,live("str_slow",caster.channelId,"PLAYABLE"),2500));
        for(String id:List.of("str_down","str_garbage","str_slow")) {
            var response=owner.send("POST",path,Map.of("streamId",id),true);
            assertThat(response.statusCode()).as(id).isEqualTo(503);
            assertThat(json.readTree(response.body()).get("code").textValue()).isEqualTo("STREAMING_UNAVAILABLE");
        }
        // A live stream whose channel Core does not know cannot be shown, so it is not added either.
        STREAMING_REPLIES.put("str_orphan",new Reply(200,live("str_orphan","chn_unknown","PLAYABLE"),0));
        assertThat(owner.send("POST",path,Map.of("streamId","str_orphan"),true).statusCode()).isEqualTo(404);

        List<Object> malformed=List.of(Map.of("streamId","../x"),Map.of("streamId","a b"),Map.of("streamId",""),Map.of("streamId",1),Map.of(),
                Map.of("streamId","str_ok","extra","x"));
        for(Object bad:malformed) assertThat(owner.send("POST",path,bad,true).statusCode()).as(String.valueOf(bad)).isEqualTo(400);
        assertThat(count("watchparty.party_streams")).isZero();
        assertThat(jdbc.sql("SELECT party_version FROM watchparty.parties").query(Long.class).single()).isZero();
    }

    // --- RF-048 remove, CA-06

    @Test void removingIsIdempotentAndOnlyAnEffectiveRemovalChangesTheVersion() throws Exception {
        var owner=user("owner_01").login();
        String stream=stream("str_a",user("caster_02"),"PLAYABLE");
        String party=createParty(owner,"Final").get("partyId").textValue();
        owner.send("POST","/api/watch-parties/"+party+"/streams",Map.of("streamId",stream),true);

        var removed=owner.send("DELETE","/api/watch-parties/"+party+"/streams/"+stream,null,true);
        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(removed.body()).get("sources")).isEmpty();
        assertThat(json.readTree(removed.body()).get("partyVersion").longValue()).isEqualTo(2);
        var again=owner.send("DELETE","/api/watch-parties/"+party+"/streams/"+stream,null,true);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json.readTree(again.body()).get("partyVersion").longValue()).isEqualTo(2);
        assertThat(owner.send("DELETE","/api/watch-parties/"+party+"/streams/..%2Fx",null,true).statusCode()).isBetween(400,404);
        assertThat(owner.send("DELETE","/api/watch-parties/wp_"+"0".repeat(32)+"/streams/"+stream,null,true).statusCode()).isEqualTo(404);
    }

    // --- RF-050 join and rotate, CA-07/11

    @Test void joiningIsIdempotentAndRotatingTheCodeRevokesOnlyTheOldCode() throws Exception {
        var owner=user("owner_01").login();
        var first=user("first_02").login();
        var second=user("second_03").login();
        var stranger=user("stranger_04").login();
        JsonNode created=createParty(owner,"Final");
        String id=created.get("partyId").textValue(), oldCode=created.get("accessCode").textValue();

        for(int i=0;i<3;i++) {
            var joined=first.send("POST","/api/watch-parties/join",Map.of("accessCode",oldCode),true);
            assertThat(joined.statusCode()).isEqualTo(200);
            assertThat(json.readTree(joined.body()).get("memberCount").intValue()).isEqualTo(2);
            assertThat(json.readTree(joined.body()).get("accessCode").isNull()).isTrue();
        }
        assertThat(count("watchparty.party_members")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT party_version FROM watchparty.parties").query(Long.class).single()).as("joining is not a party change").isZero();

        for(String wrong:List.of("A".repeat(43),"short","","A".repeat(44))) {
            var response=first.send("POST","/api/watch-parties/join",Map.of("accessCode",wrong),true);
            assertThat(response.statusCode()).as(wrong).isEqualTo(404);
            assertThat(json.readTree(response.body()).get("code").textValue()).isEqualTo("WATCH_PARTY_NOT_FOUND");
        }
        assertThat(first.send("POST","/api/watch-parties/join",Map.of(),true).statusCode()).isEqualTo(400);
        var anonymous=new Browser(); anonymous.csrf();
        assertThat(anonymous.send("POST","/api/watch-parties/join",Map.of("accessCode",oldCode),true).statusCode()).isEqualTo(401);

        assertThat(first.send("POST","/api/watch-parties/"+id+"/access-code/rotate",null,true).statusCode()).isEqualTo(403);
        assertThat(stranger.send("POST","/api/watch-parties/"+id+"/access-code/rotate",null,true).statusCode()).isEqualTo(404);
        var rotated=owner.send("POST","/api/watch-parties/"+id+"/access-code/rotate",null,true);
        assertThat(rotated.statusCode()).isEqualTo(200);
        String newCode=json.readTree(rotated.body()).get("accessCode").textValue();
        assertThat(newCode).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(oldCode);
        assertThat(json.readTree(rotated.body()).get("partyVersion").longValue()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT access_code_hash FROM watchparty.parties").query(String.class).single()).isEqualTo(sha256(newCode));

        assertThat(second.send("POST","/api/watch-parties/join",Map.of("accessCode",oldCode),true).statusCode()).isEqualTo(404);
        assertThat(second.send("POST","/api/watch-parties/join",Map.of("accessCode",newCode),true).statusCode()).isEqualTo(200);
        assertThat(first.send("GET","/api/watch-parties/"+id,null,false).statusCode()).as("existing members keep access").isEqualTo(200);
        assertThat(count("watchparty.party_members")).isEqualTo(3);
    }

    @Test void concurrentJoinsOfTheSameUserCreateASingleMembership() throws Exception {
        var owner=user("owner_01").login();
        var member=user("member_02").login();
        String code=createParty(owner,"Final").get("accessCode").textValue();
        List<HttpResponse<String>> results=concurrently(5,i->member.send("POST","/api/watch-parties/join",Map.of("accessCode",code),true));
        assertThat(results).allSatisfy(r->assertThat(r.statusCode()).isEqualTo(200));
        assertThat(count("watchparty.party_members")).isEqualTo(2);
    }

    // --- CA-09/10 live state and degradation

    @Test void theLiveStateOfEachStreamIsReadOnEveryReadAndDegradesWhenStreamingFails() throws Exception {
        var owner=user("owner_01").login();
        var caster=user("caster_02");
        String stream=stream("str_a",caster,"PLAYABLE");
        String party=createParty(owner,"Final").get("partyId").textValue();
        owner.send("POST","/api/watch-parties/"+party+"/streams",Map.of("streamId",stream),true);
        String path="/api/watch-parties/"+party;

        // Only a fresh LIVE and PLAYABLE stream hands out its session; every other case names its state and has none.
        record Case(String body,String state) { }
        for(Case c:List.of(new Case(live(stream,caster.channelId,"PLAYABLE"),"PLAYABLE"),
                new Case(live(stream,caster.channelId,"RECONNECTING"),"RECONNECTING"),
                new Case(live(stream,caster.channelId,"OFFLINE"),"OFFLINE"),
                new Case(live(stream,caster.channelId,"ENDED","OFFLINE",true),"ENDED"),
                new Case(live(stream,caster.channelId,"LIVE","PLAYABLE",false),"UNKNOWN"))) {
            STREAMING_REPLIES.put(stream,new Reply(200,c.body(),0));
            JsonNode entry=json.readTree(owner.send("GET",path,null,false).body()).get("sources").get(0);
            assertThat(entry.get("state").textValue()).as(c.body()).isEqualTo(c.state());
            assertThat(entry.get("sessionId").isNull()).as(c.body()).isEqualTo(!c.state().equals("PLAYABLE"));
            assertThat(entry.get("channel").get("handle").textValue()).isEqualTo("caster_02");
        }
        // A definitive 404 from Streaming means nothing is being broadcast: ENDED, never an invented source.
        STREAMING_REPLIES.remove(stream);
        JsonNode gone=json.readTree(owner.send("GET",path,null,false).body()).get("sources").get(0);
        assertThat(gone.get("state").textValue()).isEqualTo("ENDED");
        assertThat(gone.get("sessionId").isNull()).isTrue();

        // Streaming answers with an error, then it is too slow, then it sends garbage: always 200 + UNKNOWN + channel kept.
        for(Reply broken:List.of(new Reply(503,"{}",0),new Reply(200,live(stream,caster.channelId,"PLAYABLE"),2500),new Reply(200,"not json",0))) {
            STREAMING_REPLIES.put(stream,broken);
            var response=owner.send("GET",path,null,false);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode entry=json.readTree(response.body()).get("sources").get(0);
            assertThat(entry.get("state").textValue()).isEqualTo("UNKNOWN");
            assertThat(entry.get("sessionId").isNull()).isTrue();
            assertThat(entry.get("statusFresh").booleanValue()).isFalse();
            assertThat(entry.get("title").isNull()).isTrue();
            assertThat(entry.get("category").isNull()).isTrue();
            assertThat(entry.get("viewerCount").isNull()).isTrue();
            assertThat(entry.get("channel").get("handle").textValue()).isEqualTo("caster_02");
        }

        // It comes back and the owner finally removes it.
        STREAMING_REPLIES.put(stream,new Reply(200,live(stream,caster.channelId,"PLAYABLE"),0));
        assertThat(json.readTree(owner.send("GET",path,null,false).body()).get("sources").get(0).get("state").textValue()).isEqualTo("PLAYABLE");
        assertThat(owner.send("DELETE",path+"/streams/"+stream,null,true).statusCode()).isEqualTo(200);
    }

    @Test void allStreamsOfAPartyAreReadInParallel() throws Exception {
        var owner=user("owner_01").login();
        List<String> ids=new ArrayList<>();
        for(int i=0;i<4;i++) ids.add(stream("str_"+i,user("caster_0"+(i+2)),"PLAYABLE"));
        String party=createParty(owner,"Final").get("partyId").textValue();
        for(String id:ids) assertThat(owner.send("POST","/api/watch-parties/"+party+"/streams",Map.of("streamId",id),true).statusCode()).isEqualTo(201);
        for(String id:ids) STREAMING_REPLIES.put(id,new Reply(200,live(id,channelOf(id),"PLAYABLE"),400));

        long started=System.nanoTime();
        var response=owner.send("GET","/api/watch-parties/"+party,null,false);
        long millis=(System.nanoTime()-started)/1_000_000;

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("sources")).hasSize(4);
        assertThat(millis).as("four 400 ms lookups must overlap").isLessThan(1400);
    }

    // --- RF-049 close, CA-12/15

    @Test void closingFreezesThePartyAndEveryCommandAnswersConflictUniformly() throws Exception {
        var owner=user("owner_01").login();
        var member=user("member_02").login();
        var late=user("late_03").login();
        String stream=stream("str_a",user("caster_04"),"PLAYABLE");
        JsonNode created=createParty(owner,"Final");
        String id=created.get("partyId").textValue(), code=created.get("accessCode").textValue();
        member.send("POST","/api/watch-parties/join",Map.of("accessCode",code),true);
        owner.send("POST","/api/watch-parties/"+id+"/streams",Map.of("streamId",stream),true);

        assertThat(member.send("POST","/api/watch-parties/"+id+"/close",null,true).statusCode()).isEqualTo(403);
        assertThat(late.send("POST","/api/watch-parties/"+id+"/close",null,true).statusCode()).isEqualTo(404);
        var closed=owner.send("POST","/api/watch-parties/"+id+"/close",null,true);
        assertThat(closed.statusCode()).isEqualTo(200);
        JsonNode view=json.readTree(closed.body());
        assertThat(view.get("status").textValue()).isEqualTo("CLOSED");
        assertThat(view.get("closedAtUtc").isTextual()).isTrue();
        long version=view.get("partyVersion").longValue();
        var again=json.readTree(owner.send("POST","/api/watch-parties/"+id+"/close",null,true).body());
        assertThat(again.get("partyVersion").longValue()).as("closing again is a no-op").isEqualTo(version);
        assertThat(again.get("closedAtUtc")).isEqualTo(view.get("closedAtUtc"));

        for(var response:List.of(late.send("POST","/api/watch-parties/join",Map.of("accessCode",code),true),
                owner.send("POST","/api/watch-parties/"+id+"/streams",Map.of("streamId",stream),true),
                owner.send("DELETE","/api/watch-parties/"+id+"/streams/"+stream,null,true),
                owner.send("POST","/api/watch-parties/"+id+"/access-code/rotate",null,true))) {
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json.readTree(response.body()).get("code").textValue()).isEqualTo("WATCH_PARTY_CLOSED");
        }
        // Members can still read what was shared; nothing changed.
        var read=json.readTree(member.send("GET","/api/watch-parties/"+id,null,false).body());
        assertThat(read.get("status").textValue()).isEqualTo("CLOSED");
        assertThat(read.get("sources")).as("a closed room hands out no source and no session").isEmpty();
        assertThat(count("watchparty.party_streams")).isEqualTo(1);
        assertThat(count("watchparty.party_members")).isEqualTo(2);
    }

    @Test void partyVersionOnlyAdvancesWithEffectiveChangesToStreamsCodeOrState() throws Exception {
        var owner=user("owner_01").login();
        var member=user("member_02").login();
        String stream=stream("str_a",user("caster_03"),"PLAYABLE");
        JsonNode created=createParty(owner,"Final");
        String id=created.get("partyId").textValue();
        assertThat(version(owner,id)).isZero();
        member.send("POST","/api/watch-parties/join",Map.of("accessCode",created.get("accessCode").textValue()),true);
        assertThat(version(owner,id)).isZero();
        owner.send("POST","/api/watch-parties/"+id+"/streams",Map.of("streamId",stream),true);
        assertThat(version(owner,id)).isEqualTo(1);
        owner.send("GET","/api/watch-parties/"+id,null,false);
        assertThat(version(owner,id)).isEqualTo(1);
        owner.send("DELETE","/api/watch-parties/"+id+"/streams/str_not_there",null,true);
        assertThat(version(owner,id)).isEqualTo(1);
        owner.send("DELETE","/api/watch-parties/"+id+"/streams/"+stream,null,true);
        assertThat(version(owner,id)).isEqualTo(2);
        owner.send("POST","/api/watch-parties/"+id+"/access-code/rotate",null,true);
        assertThat(version(owner,id)).isEqualTo(3);
        owner.send("POST","/api/watch-parties/"+id+"/close",null,true);
        assertThat(version(owner,id)).isEqualTo(4);
    }

    // --- CA-05 idempotency, CA-06 suspension, CA-03 permissions

    @Test void repeatingACommandWithTheSameKeyReplaysItsOutcomeWithoutDuplicatingIt() throws Exception {
        var owner=user("owner_01").login();
        String stream=stream("str_a",user("caster_02"),"PLAYABLE");
        String key=UUID.randomUUID().toString();

        var first=owner.send("POST","/api/watch-parties",Map.of("title","Final"),true,key);
        var replay=owner.send("POST","/api/watch-parties",Map.of("title","Final"),true,key);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        JsonNode created=json.readTree(first.body()), repeated=json.readTree(replay.body());
        String id=created.get("partyId").textValue();
        assertThat(repeated.get("partyId").textValue()).isEqualTo(id);
        assertThat(created.get("accessCode").isTextual()).isTrue();
        assertThat(repeated.get("accessCode").isNull()).as("the code is shown once and never recorded").isTrue();
        assertThat(count("watchparty.parties")).isEqualTo(1);
        String code=created.get("accessCode").textValue();
        assertThat(jdbc.sql("SELECT access_code_hash FROM watchparty.parties").query(String.class).single()).isEqualTo(sha256(code));
        assertThat(jdbc.sql("SELECT count(*) FROM watchparty.operations WHERE party_id=:id").param("id",id).query(Long.class).single()).isEqualTo(1);

        String addKey=UUID.randomUUID().toString();
        assertThat(owner.send("POST","/api/watch-parties/"+id+"/streams",Map.of("streamId",stream),true,addKey).statusCode()).isEqualTo(201);
        int calls=STREAMING_CALLS.get();
        // The stream goes offline before the retry: the recorded command is replayed instead of failing with STREAM_NOT_LIVE.
        STREAMING_REPLIES.put(stream,new Reply(200,live(stream,channelOf(stream),"OFFLINE"),0));
        var addAgain=owner.send("POST","/api/watch-parties/"+id+"/streams",Map.of("streamId",stream),true,addKey);
        assertThat(addAgain.statusCode()).isEqualTo(201);
        assertThat(json.readTree(addAgain.body()).get("partyVersion").longValue()).isEqualTo(1);
        assertThat(count("watchparty.party_streams")).isEqualTo(1);
        assertThat(STREAMING_CALLS.get()-calls).as("only the room read asks Streaming, never the replayed command").isEqualTo(1);

        String rotateKey=UUID.randomUUID().toString();
        var rotated=owner.send("POST","/api/watch-parties/"+id+"/access-code/rotate",null,true,rotateKey);
        String newCode=json.readTree(rotated.body()).get("accessCode").textValue();
        var rotatedAgain=owner.send("POST","/api/watch-parties/"+id+"/access-code/rotate",null,true,rotateKey);
        assertThat(json.readTree(rotatedAgain.body()).get("accessCode").isNull()).isTrue();
        assertThat(json.readTree(rotatedAgain.body()).get("partyVersion").longValue()).as("rotated once").isEqualTo(2);
        assertThat(jdbc.sql("SELECT access_code_hash FROM watchparty.parties").query(String.class).single()).isEqualTo(sha256(newCode));

        String closeKey=UUID.randomUUID().toString();
        long version=json.readTree(owner.send("POST","/api/watch-parties/"+id+"/close",null,true,closeKey).body()).get("partyVersion").longValue();
        assertThat(json.readTree(owner.send("POST","/api/watch-parties/"+id+"/close",null,true,closeKey).body()).get("partyVersion").longValue()).isEqualTo(version);
    }

    @Test void theSameKeyWithAnotherPayloadIsAConflictAndEachAccountHasItsOwnKeys() throws Exception {
        var owner=user("owner_01").login();
        var other=user("other_02").login();
        String key=UUID.randomUUID().toString();
        assertThat(owner.send("POST","/api/watch-parties",Map.of("title","A"),true,key).statusCode()).isEqualTo(201);

        var conflict=owner.send("POST","/api/watch-parties",Map.of("title","B"),true,key);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json.readTree(conflict.body()).get("code").textValue()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(count("watchparty.parties")).isEqualTo(1);

        assertThat(other.send("POST","/api/watch-parties",Map.of("title","B"),true,key).statusCode()).as("keys are per account").isEqualTo(201);
        assertThat(count("watchparty.parties")).isEqualTo(2);
    }

    @Test void aMissingOrMalformedIdempotencyKeyIsRejectedOnEveryCommand() throws Exception {
        var owner=user("owner_01").login();
        String id=createParty(owner,"Final").get("partyId").textValue();
        Object[][] commands={{"POST","/api/watch-parties",Map.of("title","B")},{"POST","/api/watch-parties/join",Map.of("accessCode","A".repeat(43))},
                {"POST","/api/watch-parties/"+id+"/streams",Map.of("streamId","str_a")},{"DELETE","/api/watch-parties/"+id+"/streams/str_a",null},
                {"POST","/api/watch-parties/"+id+"/access-code/rotate",null},{"POST","/api/watch-parties/"+id+"/close",null}};
        for(Object[] command:commands) {
            var missing=owner.send((String)command[0],(String)command[1],command[2],true,null);
            assertThat(missing.statusCode()).as(command[1]+" without key").isEqualTo(400);
            assertThat(json.readTree(missing.body()).get("code").textValue()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
            var malformed=owner.send((String)command[0],(String)command[1],command[2],true,"not-a-uuid");
            assertThat(malformed.statusCode()).as(command[1]+" with a bad key").isEqualTo(400);
            assertThat(json.readTree(malformed.body()).get("code").textValue()).isEqualTo("IDEMPOTENCY_KEY_INVALID");
        }
        assertThat(count("watchparty.parties")).isEqualTo(1);
        assertThat(count("watchparty.operations")).isEqualTo(1);
    }

    @Test void concurrentRequestsWithTheSameKeyCreateASingleRoom() throws Exception {
        var owner=user("owner_01").login();
        String key=UUID.randomUUID().toString();
        List<HttpResponse<String>> results=concurrently(5,i->owner.send("POST","/api/watch-parties",Map.of("title","Final"),true,key));
        assertThat(results).allSatisfy(r->assertThat(r.statusCode()).isEqualTo(201));
        List<String> ids=new ArrayList<>();
        int withCode=0;
        for(var result:results) {
            JsonNode view=json.readTree(result.body());
            ids.add(view.get("partyId").textValue());
            if(view.get("accessCode").isTextual()) withCode++;
        }
        assertThat(ids.stream().distinct()).hasSize(1);
        assertThat(withCode).as("only the request that ran the command saw the code").isEqualTo(1);
        assertThat(count("watchparty.parties")).isEqualTo(1);
    }

    @Test void theRoomAndItsMembershipLiveInPostgresqlSoAFreshSessionStillSeesThem() throws Exception {
        var ownerUser=user("owner_01");
        var memberUser=user("member_02");
        String stream=stream("str_a",user("caster_03"),"PLAYABLE");
        var owner=ownerUser.login();
        JsonNode created=createParty(owner,"Final");
        String id=created.get("partyId").textValue();
        owner.send("POST","/api/watch-parties/"+id+"/streams",Map.of("streamId",stream),true);
        memberUser.login().send("POST","/api/watch-parties/join",Map.of("accessCode",created.get("accessCode").textValue()),true);

        // New browsers (no shared cookie jar or memory) log in again, as after losing the client or restarting it.
        for(User user:List.of(ownerUser,memberUser)) {
            JsonNode view=json.readTree(user.login().send("GET","/api/watch-parties/"+id,null,false).body());
            assertThat(view.get("memberCount").intValue()).isEqualTo(2);
            assertThat(view.get("sources")).hasSize(1);
            assertThat(view.get("isOwner").booleanValue()).isEqualTo(user==ownerUser);
        }
    }

    @Test void aSuspendedChannelStopsBeingConsumableFromTheRoomWhileTheOthersKeepTheirState() throws Exception {
        var owner=user("owner_01").login();
        var member=user("member_02").login();
        var suspended=user("caster_03");
        var healthy=user("caster_04");
        String doomed=stream("str_doomed",suspended,"PLAYABLE"), fine=stream("str_fine",healthy,"PLAYABLE");
        JsonNode created=createParty(owner,"Final");
        String id=created.get("partyId").textValue();
        member.send("POST","/api/watch-parties/join",Map.of("accessCode",created.get("accessCode").textValue()),true);
        owner.send("POST","/api/watch-parties/"+id+"/streams",Map.of("streamId",doomed),true);
        owner.send("POST","/api/watch-parties/"+id+"/streams",Map.of("streamId",fine),true);

        jdbc.sql("UPDATE identity.accounts SET account_status='SUSPENDED' WHERE user_id=:id").param("id",suspended.userId).update();

        for(var viewer:List.of(owner,member)) {
            JsonNode sources=json.readTree(viewer.send("GET","/api/watch-parties/"+id,null,false).body()).get("sources");
            assertThat(sources).hasSize(2);
            assertThat(sources.get(0).get("streamId").textValue()).isEqualTo(doomed);
            assertThat(sources.get(0).get("state").textValue()).isEqualTo("SUSPENDED");
            assertThat(sources.get(0).get("sessionId").isNull()).isTrue();
            assertThat(sources.get(0).get("channel").isNull()).isTrue();
            assertThat(sources.get(0).get("title").isNull()).isTrue();
            assertThat(sources.get(1).get("state").textValue()).isEqualTo("PLAYABLE");
            assertThat(sources.get(1).get("sessionId").textValue()).isEqualTo("ses_1");
            assertThat(sources.get(1).get("channel").get("handle").textValue()).isEqualTo("caster_04");
        }
        // A suspended channel cannot be added to another room either, and reactivating it restores the source.
        String other=createParty(owner,"Otra").get("partyId").textValue();
        assertThat(owner.send("POST","/api/watch-parties/"+other+"/streams",Map.of("streamId",doomed),true).statusCode()).isEqualTo(404);
        jdbc.sql("UPDATE identity.accounts SET account_status='ACTIVE' WHERE user_id=:id").param("id",suspended.userId).update();
        assertThat(json.readTree(owner.send("GET","/api/watch-parties/"+id,null,false).body()).get("sources").get(0).get("state").textValue()).isEqualTo("PLAYABLE");
    }

    @Test void aSuspendedAccountLosesItsSessionAndCannotUseItsRooms() throws Exception {
        var owner=user("owner_01");
        var browser=owner.login();
        String id=createParty(browser,"Final").get("partyId").textValue();
        jdbc.sql("UPDATE identity.accounts SET account_status='SUSPENDED' WHERE user_id=:id").param("id",owner.userId).update();
        assertThat(browser.send("GET","/api/watch-parties/"+id,null,false).statusCode()).isEqualTo(401);
        assertThat(browser.send("POST","/api/watch-parties/"+id+"/close",null,true).statusCode()).isEqualTo(401);
    }

    // --- CA-13 privacy

    @Test void responsesNeverExposeOtherMembersPrivateDataOrSecrets() throws Exception {
        var owner=user("owner_01").login();
        var member=user("secret_member_02").login();
        String stream=stream("str_a",user("caster_03"),"PLAYABLE");
        JsonNode created=createParty(owner,"Final");
        String id=created.get("partyId").textValue();
        member.send("POST","/api/watch-parties/join",Map.of("accessCode",created.get("accessCode").textValue()),true);
        owner.send("POST","/api/watch-parties/"+id+"/streams",Map.of("streamId",stream),true);
        String hash=jdbc.sql("SELECT access_code_hash FROM watchparty.parties").query(String.class).single();

        for(var browser:List.of(owner,member)) {
            String body=browser.send("GET","/api/watch-parties/"+id,null,false).body();
            assertThat(body).doesNotContain("secret_member_02",member.userId,hash,"password","credential","email","fingerprint");
            assertThat(json.readTree(body).get("memberCount").intValue()).isEqualTo(2);
            assertThat(json.readTree(body).has("members")).isFalse();
        }
    }

    // --- helpers

    private User user(String handle) {
        var registration=accounts.register(UUID.randomUUID(),handle+"@example.test",handle,PASSWORD,"10.0.0."+(++registrations));
        return new User(handle,registration.userId(),registration.channelId());
    }
    private JsonNode createParty(Browser browser,String title) throws Exception {
        var response=browser.send("POST","/api/watch-parties",Map.of("title",title),true);
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body());
    }
    private long version(Browser browser,String id) throws Exception {
        return json.readTree(browser.send("GET","/api/watch-parties/"+id,null,false).body()).get("partyVersion").longValue();
    }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM "+table).query(Long.class).single(); }
    private String stream(String streamId,User channelOwner,String availability) {
        STREAMING_REPLIES.put(streamId,new Reply(200,live(streamId,channelOwner.channelId,availability),0));
        return streamId;
    }
    private String channelOf(String streamId) throws Exception { return json.readTree(STREAMING_REPLIES.get(streamId).body()).get("channelId").textValue(); }
    private static String live(String streamId,String channelId,String availability) {
        return live(streamId,channelId,"OFFLINE".equals(availability)?"OFFLINE":"LIVE",availability,true);
    }
    private static String live(String streamId,String channelId,String status,String availability,boolean fresh) {
        return "{\"streamId\":\""+streamId+"\",\"channelId\":\""+channelId+"\",\"sessionId\":\"ses_1\",\"streamGeneration\":1,\"title\":\"En vivo\","
                +"\"category\":{\"id\":\"cat_1\",\"name\":\"Conversación\"},\"tags\":[],\"status\":\""+status+"\","
                +"\"availability\":\""+availability+"\",\"statusFresh\":"+fresh+",\"viewerCount\":8,\"countVersion\":3,\"metadataVersion\":1,\"sessionVersion\":2}";
    }
    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static String tempDirectory(String name) {
        try { return java.nio.file.Files.createTempDirectory("watchparty-it-"+name).toString(); }
        catch(IOException e) { throw new IllegalStateException(e); }
    }

    /** Runs {@code count} requests released at the same instant and returns their responses in index order. */
    private <T> List<T> concurrently(int count,CheckedFunction<T> call) throws Exception {
        var barrier=new CyclicBarrier(count);
        try(ExecutorService executor=Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures=new ArrayList<>();
            for(int i=0;i<count;i++) { final int index=i; futures.add(executor.submit(()-> { barrier.await(); return call.apply(index); })); }
            List<T> results=new ArrayList<>();
            for(var future:futures) results.add(future.get());
            return results;
        }
    }
    private interface CheckedFunction<T> { T apply(int index) throws Exception; }

    /** A registered account. {@link #login()} opens an independent browser authenticated as it. */
    private final class User {
        final String handle, userId, channelId;
        User(String handle,String userId,String channelId) { this.handle=handle; this.userId=userId; this.channelId=channelId; }
        Browser login() throws Exception {
            Browser browser=new Browser();
            browser.csrf();
            var response=browser.send("POST","/api/identity/sessions",Map.of("login",handle,"password",PASSWORD),true);
            assertThat(response.statusCode()).isEqualTo(200);
            browser.userId=userId;
            return browser;
        }
    }

    private record Reply(int status,String body,long delayMillis) { }

    private static HttpServer startStreaming() {
        try {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(Executors.newFixedThreadPool(16));
            server.createContext("/api/streams/",exchange-> {
                STREAMING_CALLS.incrementAndGet();
                STREAMING_HEADERS.add(Map.copyOf(exchange.getRequestHeaders()));
                String path=exchange.getRequestURI().getPath();
                Reply reply=STREAMING_REPLIES.get(path.substring(path.lastIndexOf('/')+1));
                if(reply==null) reply=new Reply(404,"{\"code\":\"STREAM_NOT_FOUND\"}",0);
                try { Thread.sleep(reply.delayMillis()); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                respond(exchange,reply);
            });
            server.start();
            return server;
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    private static void respond(HttpExchange exchange,Reply reply) {
        try(exchange) {
            byte[] bytes=reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(reply.status(),bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch(IOException ignored) { /* the caller may have timed out already */ }
    }

    /** An independent browser: its own cookie jar and CSRF token. */
    private final class Browser {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrf;
        String userId;

        void csrf() throws Exception {
            var response=send("GET","/api/identity/csrf",null,false);
            assertThat(response.statusCode()).isEqualTo(200);
            csrf=json.readTree(response.body()).get("token").textValue();
            assertThat(cookies.getCookieStore().getCookies()).anyMatch(c->c.getName().equals("XSRF-TOKEN") && c.getValue().equals(csrf));
        }
        /** Commands on a room carry a fresh Idempotency-Key unless the test chooses its own with the overload below. */
        HttpResponse<String> send(String method,String path,Object body,boolean withCsrf) throws Exception {
            boolean command=path.startsWith("/api/watch-parties") && !method.equals("GET");
            return send(method,path,body,withCsrf,command?UUID.randomUUID().toString():null);
        }
        HttpResponse<String> send(String method,String path,Object body,boolean withCsrf,String idempotencyKey) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("X-Request-Id",UUID.randomUUID().toString());
            if(withCsrf) request.header("X-XSRF-TOKEN",csrf);
            if(idempotencyKey!=null) request.header("Idempotency-Key",idempotencyKey);
            if(body!=null) request.header("Content-Type","application/json");
            String payload=body==null?null:body instanceof String text?text:json.writeValueAsString(body);
            request.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(payload));
            return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
