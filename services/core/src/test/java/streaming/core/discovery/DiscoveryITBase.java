package streaming.core.discovery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import streaming.core.discovery.application.DiscoveryClock;
import streaming.core.discovery.application.DiscoveryMaintenance;
import streaming.core.discovery.application.ProjectionIngestService;
import streaming.core.discovery.application.ProjectionReconciler;
import streaming.core.security.PrivateCoreListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static streaming.core.discovery.TestPayloads.envelope;

/**
 * Shared infrastructure of the Discovery integration tests: one disposable PostgreSQL for the whole JVM (so a cached
 * Spring context never points at a stopped container), a controllable clock, a simulated Streaming cut endpoint, the
 * private TLS listener with an ephemeral certificate, and helpers to seed accounts, projections and GraphQL calls.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DiscoveryITBase.Clocks.class)
public abstract class DiscoveryITBase {
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    static { POSTGRES.start(); }
    static final String SERVICE_TOKEN="fixture_private_streaming_token_32_bytes";
    static final String CATALOG_TOKEN="fixture_catalog_only_service_token_32_bytes";
    static final String CONSUMER_TOKEN="fixture_consumer_token_for_the_cut_32_bytes";
    static final String KEYSTORE_PASSWORD="fixture-password";
    static final MutableClock CLOCK=new MutableClock(Instant.parse("2026-10-06T12:00:00Z"));
    static final CutServer CUTS=new CutServer(CONSUMER_TOKEN);
    static final Path DATA=createTemp();
    static final String CAT_1="cat_00000000000000000000000000000001", CAT_2="cat_00000000000000000000000000000002";
    static final String TAG_1="tag_00000000000000000000000000000001", TAG_2="tag_00000000000000000000000000000002", TAG_3="tag_00000000000000000000000000000003";

    @TestConfiguration static class Clocks {
        @Bean @Primary DiscoveryClock discoveryTestClock() { return CLOCK; }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) throws Exception {
        Path store=DATA.resolve("private.p12");
        if(!Files.exists(store)) {
            var keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                    "-genkeypair","-alias","core","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=localhost",
                    "-ext","SAN=dns:localhost,ip:127.0.0.1","-storetype","PKCS12","-keystore",store.toString(),"-storepass",KEYSTORE_PASSWORD,"-noprompt")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if(!keytool.waitFor(30,TimeUnit.SECONDS) || keytool.exitValue()!=0) { keytool.destroyForcibly(); throw new IllegalStateException("Fixture keystore creation failed"); }
        }
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);
        // These SQL/HTTP tests do not exercise S3; its production default requires a real bucket.
        p.add("core.images.storage-provider",()->"filesystem");
        p.add("profile.storage-root",()->DATA.resolve("avatars").toString());
        p.add("channels.storage-root",()->DATA.resolve("banners").toString());
        p.add("core.rate-limit-hmac-secret",()->"fixture_hmac_key_at_least_32_bytes");
        p.add("core.internal.enabled",()->true);
        p.add("core.internal.port",()->0);
        p.add("core.internal.streaming-service-token",()->SERVICE_TOKEN);
        p.add("core.internal.streaming-catalog-service-token",()->CATALOG_TOKEN);
        p.add("core.internal.tls-keystore",store::toString);
        p.add("core.internal.tls-keystore-password",()->KEYSTORE_PASSWORD);
        p.add("discovery.streaming.base-url",CUTS::baseUrl);
        p.add("discovery.streaming.consumer-token",()->CONSUMER_TOKEN);
        // Background reconciliation is driven explicitly by the tests.
        p.add("discovery.reconcile-initial-delay",()->"PT1H");
        p.add("discovery.reconcile-interval",()->"PT1H");
        p.add("discovery.rate-limit.burst",()->"100000");
        p.add("discovery.rate-limit.window-max",()->"1000000");
    }

    @Autowired protected JdbcClient jdbc;
    @Autowired protected ObjectMapper json;
    @Autowired protected ProjectionIngestService ingest;
    @Autowired protected ProjectionReconciler reconciler;
    @Autowired protected DiscoveryMaintenance maintenance;
    @Autowired protected PrivateCoreListener listener;
    @Value("${local.server.port}") protected int port;
    protected final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private HttpClient tls;

    @BeforeEach void resetEverything() throws Exception {
        jdbc.sql("TRUNCATE discovery.stream_projection,discovery.inbox_events,discovery.projection_conflicts,discovery.ranking_snapshots").update();
        jdbc.sql("UPDATE discovery.reconciliation_state SET last_snapshot_id=NULL,last_watermark=NULL,last_cut_captured_at_utc=NULL,"
                +"last_success_at_utc=NULL,last_attempt_at_utc=NULL,last_failure_at_utc=NULL,last_failure_code=NULL").update();
        jdbc.sql("TRUNCATE identity.accounts,identity.registrations,identity.sessions,identity.rate_limit_events,profile.profiles,profile.avatar_uploads,channels.channels CASCADE").update();
        jdbc.sql("UPDATE taxonomy.categories SET active=TRUE").update();
        jdbc.sql("UPDATE taxonomy.tags SET active=TRUE").update();
        CUTS.reset();
        CLOCK.set(Instant.parse("2026-10-06T12:00:00Z"));
    }

    // --- accounts, profiles and channels, written directly so hundreds can be seeded without hashing passwords

    protected record Channel(String userId,String channelId,String handle) { }

    protected Channel channel(String handle,String displayName) {
        String user="usr_"+handle, channel="chn_"+handle;
        jdbc.sql("INSERT INTO identity.accounts(user_id,email,normalized_email,handle,canonical_handle,password_hash,created_at_utc) "
                +"VALUES (:u,:e,:e,:h,:h,'fixture-hash-not-for-login',now())").param("u",user).param("e",handle+"@private-mail.example.test").param("h",handle).update();
        jdbc.sql("INSERT INTO profile.profiles(user_id,display_name,bio,profile_version,created_at_utc,updated_at_utc) VALUES (:u,:n,'',0,now(),now())")
                .param("u",user).param("n",displayName==null?handle:displayName).update();
        jdbc.sql("INSERT INTO channels.channels(channel_id,owner_user_id,description,channel_version,created_at_utc,updated_at_utc) VALUES (:c,:u,'',2,now(),now())")
                .param("c",channel).param("u",user).update();
        return new Channel(user,channel,handle);
    }

    // --- projections

    protected ObjectNode live(String name,Channel channel,long version,int viewers) {
        ObjectNode payload=TestPayloads.live("str_"+name,channel.channelId(),version,viewers,CLOCK.now());
        payload.put("title","Directo "+name);
        return payload;
    }
    protected static ObjectNode category(ObjectNode payload,String id,String name) { ((ObjectNode)payload.get("category")).put("id",id).put("name",name); return payload; }
    protected static ObjectNode tags(ObjectNode payload,String... idAndName) {
        var array=payload.putArray("tags");
        for(int i=0;i<idAndName.length;i+=2) array.addObject().put("id",idAndName[i]).put("name",idAndName[i+1]);
        return payload;
    }
    protected static ObjectNode startedAgo(ObjectNode payload,Instant observed,long seconds) { return payload.put("startedAtUtc",TestPayloads.pg(observed.minusSeconds(seconds))); }
    protected ProjectionIngestService.IngestResult send(ObjectNode payload) { return ingest.ingest(envelope(UUID.randomUUID().toString(),payload)); }
    protected void sendAll(ObjectNode... payloads) { for(ObjectNode payload:payloads) assertThat(send(payload).kind()).isEqualTo(ProjectionIngestService.Kind.ACCEPTED); }
    protected long count(String table) { return jdbc.sql("SELECT count(*) FROM "+table).query(Long.class).single(); }

    // --- GraphQL over the real public port

    protected static final String STREAMS="query Streams($q:String,$categoryId:ID,$tagId:ID,$limit:Int,$cursor:String){streams(q:$q,categoryId:$categoryId,tagId:$tagId,limit:$limit,cursor:$cursor)"
            +"{items{streamId sessionId channel{channelId handle displayName avatarUri} title category{id name} tags{id name} status availability viewerCount viewerCountFresh "
            +"viewerCountObservedAtUtc startedAtUtc metadataVersion sessionVersion statusFresh} nextCursor generatedAtUtc statusFresh}}";
    protected static final String CHANNELS="query Channels($q:String,$limit:Int,$cursor:String){channels(q:$q,limit:$limit,cursor:$cursor)"
            +"{items{channelId userId handle displayName avatarUri status availability title channelVersion metadataVersion sessionVersion statusFresh} nextCursor generatedAtUtc statusFresh}}";

    protected HttpResponse<String> graphql(String query,Map<String,?> variables) throws Exception { return graphqlRaw(json.writeValueAsString(Map.of("query",query,"variables",variables))); }

    protected HttpResponse<String> graphqlRaw(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/discovery/graphql")).header("Content-Type","application/json")
                .header("X-Request-Id",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }

    /** A successful query: HTTP 200, no errors; returns {@code data}. */
    protected JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body=json.readTree(response.body());
        assertThat(body.has("errors")).as(response.body()).isFalse();
        return body.get("data");
    }
    protected JsonNode streams(Map<String,?> variables) throws Exception { return ok(graphql(STREAMS,variables)).get("streams"); }
    protected JsonNode channels(Map<String,?> variables) throws Exception { return ok(graphql(CHANNELS,variables)).get("channels"); }

    protected static List<String> field(JsonNode connection,String name) {
        var values=new ArrayList<String>();
        for(JsonNode item:connection.get("items")) values.add(item.get(name).textValue());
        return values;
    }

    // --- private listener (TLS, service credential)

    protected HttpResponse<String> privatePost(String path,String serviceName,String token,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("https://localhost:"+listener.localPort()+path)).header("Content-Type","application/json");
        if(serviceName!=null) request.header("X-Service-Name",serviceName);
        if(token!=null) request.header("X-Service-Token",token);
        return tlsClient().send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> deliver(ObjectNode envelope) throws Exception {
        return privatePost("/internal/core/discovery/stream-events","streaming",SERVICE_TOKEN,json.writeValueAsString(envelope));
    }

    private synchronized HttpClient tlsClient() throws Exception {
        if(tls==null) {
            KeyStore store=KeyStore.getInstance("PKCS12");
            try(var in=Files.newInputStream(DATA.resolve("private.p12"))) { store.load(in,KEYSTORE_PASSWORD.toCharArray()); }
            var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
            var context=SSLContext.getInstance("TLS"); context.init(null,trust.getTrustManagers(),null);
            tls=HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(5)).build();
        }
        return tls;
    }

    private static Path createTemp() {
        try { return Files.createTempDirectory("discovery-it"); }
        catch(IOException e) { throw new IllegalStateException(e); }
    }
}
