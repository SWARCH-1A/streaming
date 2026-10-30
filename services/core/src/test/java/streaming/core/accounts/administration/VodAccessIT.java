package streaming.core.accounts.administration;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
class VodAccessIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    @TempDir static Path images;
    static final String SERVICE="vod-fixture-distinct-token-at-least-32-bytes";
    static final String PASSWORD="fixture password for vod integration";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("core.rate-limit-hmac-secret",()->"vod-test-rate-secret-with-at-least-32-bytes");p.add("core.images.storage-provider",()->"filesystem");
        p.add("profile.storage-root",()->images.resolve("avatars").toString());p.add("channels.storage-root",()->images.resolve("banners").toString());
        p.add("core.internal.enabled",()->"true");p.add("core.internal.port",()->"0");p.add("core.internal.development-http",()->"true");
        p.add("core.internal.streaming-service-token",()->"streaming-fixture-other-secret-32-bytes");p.add("core.internal.vod-service-token",()->SERVICE);
    }
    @Autowired IdentityApplicationService identity;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired PrivateCoreListener listener;
    @Value("${local.server.port}") int publicPort;
    String owner,channel,ownerSession,otherSession;
    @BeforeEach void setup(){jdbc.sql("TRUNCATE identity.accounts,identity.registrations,identity.sessions,identity.rate_limit_events CASCADE").update();var account=identity.register(UUID.randomUUID(),"owner@example.test","vod_owner",PASSWORD,"127.0.0.1");owner=account.userId();channel=account.channelId();identity.register(UUID.randomUUID(),"other@example.test","vod_other",PASSWORD,"127.0.0.2");ownerSession=identity.login("vod_owner",PASSWORD,"127.0.0.1").credential();otherSession=identity.login("vod_other",PASSWORD,"127.0.0.2").credential();}
    HttpResponse<String> call(int port,String credential,String token,String operation)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/core/access-context"))
            .header("Content-Type","application/json").header("X-Service-Name","vod").header("X-Service-Token",token)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("operation",operation,"channelId",channel))));
        if(credential!=null)request.header("X-Session-Credential",credential);
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void publicConnectorAndOtherServiceCannotReadContext()throws Exception{
        assertThat(call(publicPort,null,SERVICE,"READ").statusCode()).isEqualTo(404);
        assertThat(call(listener.localPort(),null,"streaming-fixture-other-secret-32-bytes","READ").statusCode()).isEqualTo(401);
        var response=call(listener.localPort(),null,SERVICE,"READ");assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("email","password","credential");assertThat(json.readTree(response.body()).get("ownerUserId").asText()).isEqualTo(owner);
    }
    @Test void ownerAndCurrentSessionAreAuthoritative()throws Exception{
        assertThat(call(listener.localPort(),ownerSession,SERVICE,"WRITE").statusCode()).isEqualTo(200);
        assertThat(call(listener.localPort(),otherSession,SERVICE,"WRITE").statusCode()).isEqualTo(403);
        assertThat(call(listener.localPort(),"invalid",SERVICE,"READ").statusCode()).isEqualTo(401);
        identity.logout(ownerSession);assertThat(call(listener.localPort(),ownerSession,SERVICE,"READ").statusCode()).isEqualTo(401);
    }
    @Test void suspensionChangesPublicAccessContextAndDoesNotGrantAdmin()throws Exception{
        assertThat(call(listener.localPort(),otherSession,SERVICE,"ADMIN").statusCode()).isEqualTo(403);
        jdbc.sql("UPDATE identity.accounts SET account_status='SUSPENDED',account_version=account_version+1 WHERE user_id=:id").param("id",owner).update();
        var context=json.readTree(call(listener.localPort(),null,SERVICE,"READ").body());assertThat(context.get("active").asBoolean()).isFalse();assertThat(context.get("accountVersion").asLong()).isEqualTo(1);
    }
}
