package streaming.core.channels;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.channels.application.AccountAuthentication.Principal;
import streaming.core.channels.application.FollowApplicationService;
import streaming.core.channels.application.FollowPublisher;
import streaming.core.channels.application.FollowStore;
import streaming.core.channels.infrastructure.FollowOutboxDispatcher;
import streaming.core.security.PrivateCoreListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class FollowIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    @TempDir static Path avatars;
    @TempDir static Path banners;
    private static final String PASSWORD="fixture password for follows";
    private static final String NOTIFICATIONS="fixture-notifications-token-at-least-32-bytes";
    private static final String RECORDING="fixture-recording-settings-token-32-bytes";

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("core.rate-limit-hmac-secret",()->"fixture-only-secret-at-least-32-bytes");
        p.add("core.images.storage-provider",()->"filesystem");
        p.add("profile.storage-root",()->avatars.toString());
        p.add("channels.storage-root",()->banners.toString());
        p.add("core.internal.enabled",()->"true");
        p.add("core.internal.port",()->"0");
        p.add("core.internal.development-http",()->"true");
        p.add("core.internal.streaming-service-token",()->"fixture-streaming-token-at-least-32-bytes");
        p.add("core.internal.notifications-service-token",()->NOTIFICATIONS);
        p.add("core.internal.recording-settings-service-token",()->RECORDING);
    }

    @Autowired IdentityApplicationService accounts;
    @Autowired FollowApplicationService follows;
    @Autowired FollowStore store;
    @Autowired FollowOutboxDispatcher dispatcher;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired PrivateCoreListener listener;
    @Value("${local.server.port}") int port;
    private final AtomicInteger sequence=new AtomicInteger();

    @BeforeEach void reset() {
        jdbc.sql("TRUNCATE identity.accounts,identity.registrations,identity.sessions,identity.rate_limit_events,channels.follow_outbox CASCADE").update();
    }

    @Test void followAndUnfollowAreIdempotentAndKeepAMonotonicVersion() throws Exception {
        var owner=account(); var fan=account();
        UUID key=UUID.randomUUID();
        var first=call("PUT",follow(owner),fan,key,true);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        assertThat(body(first).get("following").asBoolean()).isTrue();
        assertThat(body(first).get("followVersion").asLong()).isEqualTo(1);
        // Same key replays the stored result; a new key on an already active follow changes nothing.
        assertThat(call("PUT",follow(owner),fan,key,true).body()).isEqualTo(first.body());
        assertThat(body(call("PUT",follow(owner),fan,UUID.randomUUID(),true)).get("followVersion").asLong()).isEqualTo(1);
        assertThat(call("DELETE",follow(owner),fan,key,true).statusCode()).isEqualTo(409);
        assertThat(outbox()).isEqualTo(1);

        var removed=body(call("DELETE",follow(owner),fan,UUID.randomUUID(),true));
        assertThat(removed.get("following").asBoolean()).isFalse();
        assertThat(removed.get("followVersion").asLong()).isEqualTo(2);
        assertThat(removed.get("followedAtUtc").isNull()).isTrue();
        assertThat(body(call("DELETE",follow(owner),fan,UUID.randomUUID(),true)).get("followVersion").asLong()).isEqualTo(2);
        assertThat(body(call("PUT",follow(owner),fan,UUID.randomUUID(),true)).get("followVersion").asLong()).isEqualTo(3);
        assertThat(body(call("GET",follow(owner),fan,null,false)).get("followVersion").asLong()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM channels.follows").query(Long.class).single()).isEqualTo(1);
        assertThat(outbox()).isEqualTo(3);
    }

    @Test void identityComesFromTheSessionAndInvalidRequestsAreRejected() throws Exception {
        var owner=account(); var fan=account();
        assertThat(call("PUT",follow(owner),null,UUID.randomUUID(),true).statusCode()).isEqualTo(401);
        assertThat(call("PUT",follow(owner),fan,UUID.randomUUID(),false).statusCode()).isEqualTo(403);
        assertThat(call("PUT",follow(owner),fan,null,true).statusCode()).isEqualTo(400);
        assertThat(call("PUT",follow(owner),owner,UUID.randomUUID(),true).statusCode()).isEqualTo(422);
        assertThat(call("PUT","/api/channels/chn_missing/followers/me",fan,UUID.randomUUID(),true).statusCode()).isEqualTo(404);
        assertThat(call("GET","/api/me/followed-channels?userId="+owner.userId(),fan,null,false).statusCode()).isEqualTo(400);
        assertThat(call("GET","/api/me/followed-channels?limit=51",fan,null,false).statusCode()).isEqualTo(400);
        assertThat(outbox()).isZero();
    }

    @Test void concurrentFollowsProduceOneRelationAndOneEvent() throws Exception {
        var owner=account(); var fan=account();
        var barrier=new CyclicBarrier(4);
        try(var executor=Executors.newFixedThreadPool(4)) {
            var tasks=new ArrayList<java.util.concurrent.Future<Long>>();
            for(int i=0;i<4;i++) tasks.add(executor.submit(()->{
                barrier.await();
                return follows.follow(new Principal(fan.userId()),owner.channelId(),UUID.randomUUID(),"req_concurrent").followVersion();
            }));
            for(var task:tasks) assertThat(task.get()).isEqualTo(1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM channels.follows").query(Long.class).single()).isEqualTo(1);
        assertThat(outbox()).isEqualTo(1);
    }

    @Test void relationAndOutboxCommitOrRollBackTogether() {
        var owner=account(); var fan=account();
        assertThatThrownBy(()->transactions.executeWithoutResult(status->{
            follows.follow(new Principal(fan.userId()),owner.channelId(),UUID.randomUUID(),"req_rollback");
            throw new IllegalStateException("fixture rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM channels.follows").query(Long.class).single()).isZero();
        assertThat(outbox()).isZero();
    }

    @Test void outboxEventMatchesTheContractAndSurvivesBrokerFailures() throws Exception {
        var owner=account(); var fan=account();
        follows.follow(new Principal(fan.userId()),owner.channelId(),UUID.randomUUID(),"req_event");
        var event=json.readTree(jdbc.sql("SELECT payload FROM channels.follow_outbox").query(String.class).single());
        assertThat(event.get("eventType").asText()).isEqualTo("channel.follow.changed.v1");
        assertThat(event.get("aggregateId").asText()).isEqualTo("follow:"+owner.channelId()+":"+fan.userId());
        assertThat(event.get("sequence").asLong()).isEqualTo(event.get("payload").get("followVersion").asLong());
        assertThat(event.get("correlationId").asText()).isEqualTo("req_event");
        assertThat(event.toString()).doesNotContain("@example.test","password","credential");

        // A lost ACK keeps the event pending; the next attempt publishes the same eventId and payload.
        var seen=new ArrayList<FollowStore.Publication>();
        dispatcher.drain(publication->{ seen.add(publication); throw new IllegalStateException("broker down"); });
        assertThat(jdbc.sql("SELECT count(*) FROM channels.follow_outbox WHERE published_at_utc IS NULL").query(Long.class).single()).isEqualTo(1);
        jdbc.sql("UPDATE channels.follow_outbox SET next_attempt_at_utc=now()").update();
        dispatcher.drain(seen::add);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).eventId()).isEqualTo(seen.get(0).eventId());
        assertThat(seen.get(1).payload()).isEqualTo(seen.get(0).payload());
        assertThat(jdbc.sql("SELECT count(*) FROM channels.follow_outbox WHERE published_at_utc IS NULL").query(Long.class).single()).isZero();
    }

    @Test void followedListPaginatesWithoutRepeatsAndCursorIsBoundToItsOwner() throws Exception {
        var fan=account(); var other=account();
        var expected=new HashSet<String>();
        for(int i=0;i<5;i++) {
            var owner=account();
            follows.follow(new Principal(fan.userId()),owner.channelId(),UUID.randomUUID(),"req_list");
            expected.add(owner.channelId());
        }
        var seen=new ArrayList<String>();
        String cursor=null; String firstCursor=null;
        do {
            var page=body(call("GET","/api/me/followed-channels?limit=2"+(cursor==null?"":"&cursor="+cursor),fan,null,false));
            page.get("items").forEach(item->seen.add(item.get("channelId").asText()));
            cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").asText();
            if(firstCursor==null) firstCursor=cursor;
        } while(cursor!=null);
        assertThat(seen).hasSize(5).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(call("GET","/api/me/followed-channels?cursor="+firstCursor,other,null,false).statusCode()).isEqualTo(400);
        assertThat(call("GET","/api/me/followed-channels?cursor="+firstCursor.substring(1),fan,null,false).statusCode()).isEqualTo(400);
    }

    @Test void recipientSnapshotIsStableAcrossPagesAndOnlyForNotifications() throws Exception {
        var owner=account();
        var fans=new ArrayList<Account>();
        for(int i=0;i<5;i++) { var fan=account(); fans.add(fan); follows.follow(new Principal(fan.userId()),owner.channelId(),UUID.randomUUID(),"req_fan"); }
        var request=Map.of("channelId",owner.channelId(),"sessionId","ses_fixture","limit",2);
        var first=internal("/internal/core/notification-recipients",request,"notifications",NOTIFICATIONS);
        assertThat(first.statusCode()).isEqualTo(200);
        var page=body(first);
        assertThat(page.get("totalRecipients").asInt()).isEqualTo(5);
        assertThat(first.body()).doesNotContain("@example.test","email","credential");

        // Followers change between pages: the cut keeps its original inventory.
        follows.unfollow(new Principal(fans.get(4).userId()),owner.channelId(),UUID.randomUUID(),"req_change");
        var late=account();
        follows.follow(new Principal(late.userId()),owner.channelId(),UUID.randomUUID(),"req_change");
        var users=new ArrayList<String>();
        while(true) {
            page.get("items").forEach(item->users.add(item.get("userId").asText()));
            if(page.get("nextCursor").isNull()) break;
            page=body(internal("/internal/core/notification-recipients",Map.of("channelId",owner.channelId(),"sessionId","ses_fixture",
                    "limit",2,"cursor",page.get("nextCursor").asText()),"notifications",NOTIFICATIONS));
            assertThat(page.get("snapshotId").asText()).isEqualTo(body(first).get("snapshotId").asText());
        }
        assertThat(users).containsExactlyInAnyOrderElementsOf(fans.stream().map(Account::userId).toList());
        // Restarting the same session reuses the cut instead of selecting followers again.
        assertThat(body(internal("/internal/core/notification-recipients",request,"notifications",NOTIFICATIONS)).get("snapshotId").asText())
                .isEqualTo(body(first).get("snapshotId").asText());

        jdbc.sql("UPDATE channels.recipient_snapshots SET captured_at_utc=now()-interval '2 hours',expires_at_utc=now()-interval '1 minute'").update();
        assertThat(internal("/internal/core/notification-recipients",Map.of("channelId",owner.channelId(),"sessionId","ses_fixture",
                "cursor",body(first).get("nextCursor").asText()),"notifications",NOTIFICATIONS).statusCode()).isEqualTo(410);

        assertThat(internal("/internal/core/notification-recipients",request,"streaming","fixture-streaming-token-at-least-32-bytes").statusCode()).isEqualTo(401);
        assertThat(internal("/internal/core/notification-recipients",request,"notifications",RECORDING).statusCode()).isEqualTo(401);
        assertThat(publicPost("/internal/core/notification-recipients",request).statusCode()).isEqualTo(404);
    }

    @Test void autopublicationDefaultsToTrueAndUsesOptimisticVersions() throws Exception {
        var owner=account(); var other=account();
        String path="/api/channels/"+owner.channelId()+"/vod-settings";
        var initial=body(call("GET",path,owner,null,false));
        assertThat(initial.get("enabled").asBoolean()).isTrue();
        assertThat(initial.get("version").asLong()).isEqualTo(1);
        assertThat(call("GET",path,other,null,false).statusCode()).isEqualTo(403);

        var updated=body(send("PUT",path,owner,UUID.randomUUID(),true,Map.of("enabled",false,"expectedVersion",1)));
        assertThat(updated.get("enabled").asBoolean()).isFalse();
        assertThat(updated.get("version").asLong()).isEqualTo(2);
        assertThat(send("PUT",path,owner,UUID.randomUUID(),true,Map.of("enabled",true,"expectedVersion",1)).statusCode()).isEqualTo(409);
        assertThat(send("PUT",path,other,UUID.randomUUID(),true,Map.of("enabled",true,"expectedVersion",2)).statusCode()).isEqualTo(403);

        var copy=internalGet("/internal/core/channels/"+owner.channelId()+"/vod-settings","streaming-recording-settings",RECORDING);
        assertThat(copy.statusCode()).isEqualTo(200);
        assertThat(body(copy).get("enabled").asBoolean()).isFalse();
        assertThat(internalGet("/internal/core/channels/"+owner.channelId()+"/vod-settings","notifications",NOTIFICATIONS).statusCode()).isEqualTo(401);
    }

    private record Account(String userId,String channelId,String credential) { }

    private Account account() {
        int n=sequence.incrementAndGet();
        String email="follow"+n+"@example.test";
        var registration=accounts.register(UUID.randomUUID(),email,"follow_"+n+"_"+UUID.randomUUID().toString().replace("-","").substring(0,6),PASSWORD,"ip-"+n);
        var login=accounts.login(email,PASSWORD,"login-"+n);
        return new Account(registration.userId(),registration.channelId(),login.credential());
    }
    private static String follow(Account owner) { return "/api/channels/"+owner.channelId()+"/followers/me"; }
    private long outbox() { return jdbc.sql("SELECT count(*) FROM channels.follow_outbox").query(Long.class).single(); }
    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    private HttpResponse<String> call(String method,String path,Account account,UUID key,boolean csrf) throws Exception {
        return send(method,path,account,key,csrf,null);
    }
    private HttpResponse<String> send(String method,String path,Account account,UUID key,boolean csrf,Object payload) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        var cookies=new ArrayList<String>();
        if(account!=null) cookies.add("stream_session="+account.credential());
        if(csrf) { cookies.add("XSRF-TOKEN=fixture-csrf"); builder.header("X-XSRF-TOKEN","fixture-csrf"); }
        if(!cookies.isEmpty()) builder.header("Cookie",String.join("; ",cookies));
        if(key!=null) builder.header("Idempotency-Key",key.toString());
        var body=payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload));
        if(payload!=null) builder.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(builder.method(method,body).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> internal(String path,Object payload,String name,String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+listener.localPort()+path))
                .header("Content-Type","application/json").header("X-Service-Name",name).header("X-Service-Token",token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> internalGet(String path,String name,String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+listener.localPort()+path))
                .header("X-Service-Name",name).header("X-Service-Token",token).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> publicPost(String path,Object payload) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
                .header("Content-Type","application/json").header("X-Service-Name","notifications").header("X-Service-Token",NOTIFICATIONS)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(),HttpResponse.BodyHandlers.ofString());
    }
}
