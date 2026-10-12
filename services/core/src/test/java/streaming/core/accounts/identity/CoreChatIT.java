package streaming.core.accounts.identity;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.accounts.profile.application.ProfileApplicationService;
import streaming.core.accounts.profile.application.AccountIdentity.Principal;
import streaming.core.security.PrivateCoreListener;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Real Core SQL/TLS/auth composition. The Streaming stub isolates upstream failure semantics only. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoreChatIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    @TempDir static Path data;
    static final String TOKEN="fixture_core_chat_service_token_32_bytes";
    static final String STREAMING="fixture_private_streaming_token_32_bytes";
    static final String CONSUMER="fixture_streaming_consumer_token_32_bytes";
    static final String CONTEXT="/internal/core/chat/message-context";
    static HttpServer upstream;
    static final AtomicReference<String> state=new AtomicReference<>("LIVE");
    static java.util.concurrent.ExecutorService workers;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) throws Exception {
        Path keystore=data.resolve("private.p12");
        var keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                "-genkeypair","-alias","core","-keyalg","RSA","-keysize","2048","-validity","2",
                "-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-storetype","PKCS12",
                "-keystore",keystore.toString(),"-storepass","fixture-password","-noprompt")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!keytool.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)) { keytool.destroyForcibly(); throw new IllegalStateException("keytool timeout"); }
        if(keytool.exitValue()!=0) throw new IllegalStateException("keystore failed");
        upstream=HttpServer.create(new InetSocketAddress("0.0.0.0",0),0);
        workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(); upstream.setExecutor(workers);
        upstream.createContext("/",e->{
            assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer "+CONSUMER);
            String s=state.get(); e.getResponseHeaders().set("Content-Type","application/json");
            if(s.equals("UNAVAILABLE")) { e.sendResponseHeaders(503,-1); e.close(); return; }
            String body="{\"streamId\":\"str_a\",\"sessionId\":\"ses_a\",\"streamGeneration\":1,\"sessionVersion\":2,\"status\":\""+s+"\",\"availability\":\""+(s.equals("LIVE")?"PLAYABLE":"OFFLINE")+"\",\"timelinePositionMs\":42,\"timelineSampledAtUtc\":\""+Instant.now()+"\"}";
            e.sendResponseHeaders(200,0);e.getResponseBody().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));e.close();
        }); upstream.start();
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("core.images.storage-provider",()->"filesystem");p.add("profile.storage-root",()->data.resolve("avatars").toString());p.add("channels.storage-root",()->data.resolve("banners").toString());
        p.add("core.rate-limit-hmac-secret",()->"fixture_hmac_key_at_least_32_bytes");
        p.add("core.internal.enabled",()->true);p.add("core.internal.port",()->0);
        p.add("core.internal.streaming-service-token",()->STREAMING);p.add("core.internal.chat-service-token",()->TOKEN);
        p.add("core.internal.tls-keystore",keystore::toString);p.add("core.internal.tls-keystore-password",()->"fixture-password");
        p.add("core.streaming.base-url",()->"http://127.0.0.1:"+upstream.getAddress().getPort());
        p.add("core.streaming.consumer-token",()->CONSUMER);p.add("core.streaming.development-http",()->true);
    }
    @Autowired IdentityApplicationService accounts;
    @Autowired ProfileApplicationService profiles;
    @Autowired PrivateCoreListener listener;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int publicPort;
    HttpClient http;
    @BeforeEach void setup() throws Exception {
        KeyStore store=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(data.resolve("private.p12"))) {store.load(in,"fixture-password".toCharArray());}
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
        var tls=SSLContext.getInstance("TLS");tls.init(null,trust.getTrustManagers(),null);
        http=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).build();state.set("LIVE");
    }
    @AfterAll static void close() { if(upstream!=null) upstream.stop(0); if(workers!=null) workers.close(); }
    String body() { return json.writeValueAsString(Map.of("sessionId","ses_a","clientMessageId",UUID.randomUUID().toString())); }
    HttpResponse<String> call(String base,String path,String service,String token,String credential,String body) throws Exception {
        var r=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5)).header("X-Service-Name",service).header("X-Service-Token",token).header("Content-Type","application/json");
        if(credential!=null) r.header("X-Session-Credential",credential);
        return http.send(body==null?r.GET().build():r.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    String privateBase() {return "https://localhost:"+listener.localPort();}
    @Test void actualListenerEnforcesServiceRouteAndTokenAndPublicPortIsUnreachable() throws Exception {
        for(String path:List.of(CONTEXT,"/internal/core/chat/sessions/ses_a")) {
            String input=path.equals(CONTEXT)?body():null;
            assertThat(call("http://localhost:"+publicPort,path,"chat",TOKEN,"credential",input).statusCode()).isEqualTo(404);
            assertThat(call(privateBase(),path,"streaming",STREAMING,"credential",input).statusCode()).isEqualTo(401);
            assertThat(call(privateBase(),path,"chat",STREAMING,"credential",input).statusCode()).isEqualTo(401);
        }
        assertThat(call(privateBase(),"/internal/core/streaming/catalog-values","chat",TOKEN,null,"{\"ids\":[\"cat_a\"]}").statusCode()).isEqualTo(401);
        var snapshot=call(privateBase(),"/internal/core/chat/sessions/ses_a","chat",TOKEN,null,null);
        assertThat(snapshot.statusCode()).isEqualTo(200);assertThat(snapshot.body()).doesNotContain("userId","credential",CONSUMER);
        assertThat(snapshot.headers().firstValue("Cache-Control")).contains("no-store");
    }
    @Test void newContextObservesProfileCommitEndedAndLogout() throws Exception {
        String handle="chat_"+UUID.randomUUID().toString().substring(0,8);
        var account=accounts.register(UUID.randomUUID(),handle+"@example.test",handle,"fixture integration password","127.11.0.1");
        var login=accounts.login(handle,"fixture integration password","127.12.0.1");
        var first=call(privateBase(),CONTEXT,"chat",TOKEN,login.credential(),body());
        assertThat(first.statusCode()).isEqualTo(200);var before=json.readTree(first.body());assertThat(before.get("writeAllowed").asBoolean()).isTrue();
        profiles.patch(new Principal(account.userId(),handle,null),json.createObjectNode().put("displayName","Autora nueva"));
        state.set("ENDED");var after=json.readTree(call(privateBase(),CONTEXT,"chat",TOKEN,login.credential(),body()).body());
        assertThat(after.get("displayName").asText()).isEqualTo("Autora nueva");assertThat(after.get("profileVersion").asLong()).isGreaterThan(before.get("profileVersion").asLong());
        assertThat(after.get("writeAllowed").asBoolean()).isFalse();assertThat(after.get("denialCode").asText()).isEqualTo("CHAT_READ_ONLY");
        accounts.logout(login.credential());assertThat(call(privateBase(),CONTEXT,"chat",TOKEN,login.credential(),body()).statusCode()).isEqualTo(401);
    }
    @Test void upstreamFailureReturnsCorrelatedSafe503() throws Exception {
        state.set("UNAVAILABLE");var r=call(privateBase(),"/internal/core/chat/sessions/ses_a","chat",TOKEN,null,null);
        assertThat(r.statusCode()).isEqualTo(503);assertThat(json.readTree(r.body()).get("code").asText()).isEqualTo("STREAMING_UNAVAILABLE");
        assertThat(json.readTree(r.body()).get("requestId").asText()).isEqualTo(r.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(r.body()).doesNotContain(TOKEN,CONSUMER,"127.0.0.1","Exception");
    }
    @Test void oversizedAndMalformedBodiesFailWithSafeContractErrorBeforeSessionLookup() throws Exception {
        for(String input:List.of("{", "{\"sessionId\":\""+"x".repeat(17000)+"\"}")) {
            var r=call(privateBase(),CONTEXT,"chat",TOKEN,"credential",input);
            assertThat(r.statusCode()).isEqualTo(400);assertThat(json.readTree(r.body()).get("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(r.body()).doesNotContain(TOKEN,"credential","Exception");
        }
        var chunked=HttpRequest.newBuilder(URI.create(privateBase()+CONTEXT)).header("Content-Type","application/json")
                .header("X-Service-Name","chat").header("X-Service-Token",TOKEN)
                .POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream("x".repeat(17000).getBytes()))).build();
        assertThat(http.send(chunked,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }
}
