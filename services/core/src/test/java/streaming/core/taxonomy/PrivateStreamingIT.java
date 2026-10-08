package streaming.core.taxonomy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import streaming.core.security.PrivateCoreListener;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrivateStreamingIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    @TempDir static Path data;
    static final String TOKEN="fixture_private_streaming_token_32_bytes";
    static final String CATALOG_TOKEN="fixture_catalog_only_service_token_32_bytes";
    static final String PASSWORD="integration fixture password only";
    static final String CAT="cat_00000000000000000000000000000001";
    static final String TAG="tag_00000000000000000000000000000001";
    static final String OWNER="/internal/core/streaming/owner-context";
    static final String VALUES="/internal/core/streaming/catalog-values";

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) throws Exception {
        Path store=data.resolve("private.p12");
        var keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                "-genkeypair","-alias","core","-keyalg","RSA","-keysize","2048","-validity","2",
                "-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1",
                "-storetype","PKCS12","-keystore",store.toString(),"-storepass","fixture-password","-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if(!keytool.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)) { keytool.destroyForcibly(); throw new IllegalStateException("Fixture keytool timed out"); }
        if(keytool.exitValue()!=0) throw new IllegalStateException("Fixture keystore creation failed");
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("core.images.storage-provider",()->"filesystem");
        p.add("profile.storage-root",()->data.resolve("avatars").toString());
        p.add("channels.storage-root",()->data.resolve("banners").toString());
        p.add("core.rate-limit-hmac-secret",()->"fixture_hmac_key_at_least_32_bytes");
        p.add("core.internal.enabled",()->true);
        p.add("core.internal.port",()->0);
        p.add("core.internal.streaming-service-token",()->TOKEN);
        p.add("core.internal.streaming-catalog-service-token",()->CATALOG_TOKEN);
        p.add("core.internal.tls-keystore",store::toString);
        p.add("core.internal.tls-keystore-password",()->"fixture-password");
    }
    @Autowired IdentityApplicationService accounts;
    @Autowired PrivateCoreListener listener;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int publicPort;
    HttpClient client;
    static User owner,other;
    static int sequence;

    @BeforeEach void setup() throws Exception {
        KeyStore store=KeyStore.getInstance("PKCS12");
        try(var in=Files.newInputStream(data.resolve("private.p12"))) { store.load(in,"fixture-password".toCharArray()); }
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        var tls=SSLContext.getInstance("TLS"); tls.init(null,trust.getTrustManagers(),null);
        client=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).build();
        if(owner==null) { owner=user(); other=user(); }
    }

    @Test void publicPortRejectsPrivateRoutesEvenWithValidTokenAndSpoofedForwarding() throws Exception {
        for(String path:List.of(OWNER,VALUES)) for(String token:List.of(TOKEN,CATALOG_TOKEN)) {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+publicPort+path))
                    .header("Content-Type","application/json").header("X-Service-Name","streaming")
                    .header("X-Service-Token",token).header("X-Session-Credential",owner.credential())
                    .header("X-Forwarded-Port",Integer.toString(listener.localPort())).header("X-Forwarded-Proto","https")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);
        }
    }

    @Test void privateRoutesRequireCorrectServiceAndTokenBeforeParsingBody() throws Exception {
        for(String path:List.of(OWNER,VALUES)) {
            for(String token:List.of("","wrong")) assertThat(post(path,"{}",token,"streaming",owner.credential()).statusCode()).isEqualTo(401);
            assertThat(post(path,"{}",TOKEN,"chat",owner.credential()).statusCode()).isEqualTo(401);
        }
    }

    @ParameterizedTest @ValueSource(strings={"CREATE_CONFIG","PATCH_METADATA","ROTATE_KEY","STOP_SESSION"})
    void catalogCredentialCannotAuthorizeAnyOwnerOperation(String operation) throws Exception {
        var response=post(OWNER,command(owner.channel(),operation,",\"categoryId\":\""+CAT+"\""),CATALOG_TOKEN,"streaming",owner.credential());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo("SERVICE_UNAUTHORIZED");
    }

    @Test void catalogCredentialResolvesValuesWithoutSessionButRequiresCorrectServiceAndRoute() throws Exception {
        String input=json.writeValueAsString(Map.of("ids",List.of(CAT,TAG)));
        assertThat(post(VALUES,input,CATALOG_TOKEN,"streaming",null).statusCode()).isEqualTo(200);
        assertThat(post(VALUES,input,CATALOG_TOKEN,"chat",null).statusCode()).isEqualTo(401);
        assertThat(post("/internal/core/streaming/unknown",input,TOKEN,"streaming",null).statusCode()).isEqualTo(401);
        assertThat(post("/internal/core/streaming/unknown",input,CATALOG_TOKEN,"streaming",null).statusCode()).isEqualTo(401);
    }

    @ParameterizedTest @ValueSource(strings={"CREATE_CONFIG","PATCH_METADATA","ROTATE_KEY","STOP_SESSION"})
    void contextIsBoundToCommandChannelAndOperation(String operation) throws Exception {
        var request=json.createObjectNode().put("commandId",UUID.randomUUID().toString()).put("operation",operation).put("channelId",owner.channel());
        if(operation.equals("CREATE_CONFIG")) request.put("categoryId",CAT).putArray("tagIds");
        var response=post(OWNER,request.toString(),TOKEN,"streaming",owner.credential());
        assertThat(response.statusCode()).isEqualTo(200);
        var body=json.readTree(response.body());
        assertThat(body.get("commandId")).isEqualTo(request.get("commandId"));
        assertThat(body.get("operation").asText()).isEqualTo(operation);
        assertThat(body.get("channelId").asText()).isEqualTo(owner.channel());
        assertThat(body.get("userId").asText()).isEqualTo(owner.id());
        assertThat(java.time.Instant.parse(body.get("authorizedAtUtc").asText())).isBeforeOrEqualTo(java.time.Instant.now());
        assertThat(body.get("catalogVersion").asLong()).isPositive();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain(owner.credential(),"email","password","streamKey");
        if(operation.equals("CREATE_CONFIG")) {
            assertThat(body.at("/category/kind").asText()).isEqualTo("CATEGORY");
            assertThat(body.at("/category/active").asBoolean()).isTrue();
            assertThat(body.get("tags").size()).isZero();
        } else assertThat(body.has("category") || body.has("tags")).isFalse();
    }

    @Test void sessionOwnerAndExistenceAreValidated() throws Exception {
        String body=command(owner.channel(),"PATCH_METADATA","");
        assertThat(post(OWNER,body,TOKEN,"streaming",null).statusCode()).isEqualTo(401);
        assertThat(post(OWNER,body,TOKEN,"streaming",other.credential()).statusCode()).isEqualTo(403);
        assertThat(post(OWNER,command("chn_missing","STOP_SESSION",""),TOKEN,"streaming",owner.credential()).statusCode()).isEqualTo(404);
        User revoked=user(); accounts.logout(revoked.credential());
        assertThat(post(OWNER,command(revoked.channel(),"STOP_SESSION",""),TOKEN,"streaming",revoked.credential()).statusCode()).isEqualTo(401);
    }

    @ParameterizedTest @ValueSource(strings={"",",\"categoryId\":null",",\"categoryId\":\"\"",",\"categoryId\":\"missing\"",",\"categoryId\":\"tag_00000000000000000000000000000001\""})
    void createRequiresAnActiveCategoryOfCorrectKind(String fields) throws Exception {
        assertThat(post(OWNER,command(owner.channel(),"CREATE_CONFIG",fields),TOKEN,"streaming",owner.credential()).statusCode()).isEqualTo(422);
    }

    @Test void tagsAreTypedDeduplicatedAndLimited() throws Exception {
        String five=java.util.stream.IntStream.rangeClosed(1,5).mapToObj(n->"\"tag_"+String.format("%032d",n)+"\"").collect(java.util.stream.Collectors.joining(","));
        for(String tags:List.of("[]","["+five+"]","[\""+TAG+"\",\""+TAG+"\"]")) {
            var response=post(OWNER,command(owner.channel(),"PATCH_METADATA",",\"tagIds\":"+tags),TOKEN,"streaming",owner.credential());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).get("tags").size()).isIn(0,1,5);
        }
        for(String tags:List.of("null","[null]","[\"missing\"]","[\""+CAT+"\"]","["+five+",\"tag_00000000000000000000000000000006\"]"))
            assertThat(post(OWNER,command(owner.channel(),"PATCH_METADATA",",\"tagIds\":"+tags),TOKEN,"streaming",owner.credential()).statusCode()).isEqualTo(422);
    }

    @Test void tombstonesResolveButExplicitSelectionFailsAndOmittedFieldsRemainOmitted() throws Exception {
        String id="cat_private_tombstone";
        jdbc.sql("INSERT INTO taxonomy.categories(id,name,active) VALUES (:id,'Nombre conservado',false)").param("id",id).update();
        var resolved=post(VALUES,json.writeValueAsString(Map.of("ids",List.of(id,TAG))),TOKEN,"streaming",null);
        assertThat(resolved.statusCode()).isEqualTo(200);
        var body=json.readTree(resolved.body());
        assertThat(body.at("/values/0/kind").asText()).isEqualTo("CATEGORY");
        assertThat(body.at("/values/0/name").asText()).isEqualTo("Nombre conservado");
        assertThat(body.at("/values/0/active").asBoolean()).isFalse();
        assertThat(body.at("/values/1/kind").asText()).isEqualTo("TAG");
        assertThat(post(OWNER,command(owner.channel(),"PATCH_METADATA",",\"categoryId\":\""+id+"\""),TOKEN,"streaming",owner.credential()).statusCode()).isEqualTo(422);
        var omitted=post(OWNER,command(owner.channel(),"PATCH_METADATA",""),TOKEN,"streaming",owner.credential());
        assertThat(omitted.statusCode()).isEqualTo(200);
        assertThat(json.readTree(omitted.body()).has("category")).isFalse();
        assertThat(json.readTree(omitted.body()).get("catalogVersion")).isEqualTo(body.get("catalogVersion"));
    }

    @Test void batchBoundsAndUnknownIdsFailWithoutPartialSuccess() throws Exception {
        for(Object ids:List.of(List.of(),List.of("missing"),List.of(CAT,"missing"),java.util.Collections.nCopies(51,CAT)))
            assertThat(post(VALUES,json.writeValueAsString(Map.of("ids",ids)),TOKEN,"streaming",null).statusCode()).isEqualTo(422);
        var max=post(VALUES,json.writeValueAsString(Map.of("ids",java.util.Collections.nCopies(50,CAT))),TOKEN,"streaming",null);
        assertThat(max.statusCode()).isEqualTo(200);
        assertThat(json.readTree(max.body()).get("values").size()).isEqualTo(1);
    }

    @Test void sqlFaultReturnsSafeCorrelated503AndRecovers() throws Exception {
        jdbc.sql("ALTER TABLE taxonomy.catalog_state RENAME TO catalog_state_unavailable").update();
        try {
            for(String path:List.of(OWNER,VALUES)) {
                String input=path.equals(OWNER)?command(owner.channel(),"PATCH_METADATA",""):json.writeValueAsString(Map.of("ids",List.of(CAT)));
                var response=post(path,input,TOKEN,"streaming",owner.credential());
                assertThat(response.statusCode()).isEqualTo(503);
                var error=json.readTree(response.body());
                assertThat(error.get("code").asText()).isEqualTo("CORE_UNAVAILABLE");
                assertThat(error.get("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
                assertThat(response.body()).doesNotContain("SELECT","catalog_state",TOKEN,owner.credential());
            }
        } finally { jdbc.sql("ALTER TABLE taxonomy.catalog_state_unavailable RENAME TO catalog_state").update(); }
        assertThat(post(VALUES,json.writeValueAsString(Map.of("ids",List.of(CAT))),TOKEN,"streaming",null).statusCode()).isEqualTo(200);
    }

    @Test void publicMutationsStillRequireCsrf() throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+publicPort+"/api/identity/sessions"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo("CSRF_INVALID");
    }
    User user() {
        int n=++sequence; String handle="private_"+n;
        var account=accounts.register(UUID.randomUUID(),handle+"@example.test",handle,PASSWORD,"127.1.0."+n);
        var login=accounts.login(handle,PASSWORD,"127.2.0."+n);
        return new User(account.userId(),account.channelId(),login.credential());
    }
    String command(String channel,String operation,String fields) {
        return "{\"commandId\":\""+UUID.randomUUID()+"\",\"operation\":\""+operation+"\",\"channelId\":\""+channel+"\""+fields+"}";
    }
    HttpResponse<String> post(String path,String body,String token,String service,String credential) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("https://localhost:"+listener.localPort()+path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/json").header("X-Service-Name",service);
        if(!token.isEmpty()) request.header("X-Service-Token",token);
        if(credential!=null) request.header("X-Session-Credential",credential);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    record User(String id,String channel,String credential) { }
}
