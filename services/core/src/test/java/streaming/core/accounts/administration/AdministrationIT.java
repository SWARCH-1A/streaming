package streaming.core.accounts.administration;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
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
import streaming.core.accounts.administration.application.AdministrationService;
import streaming.core.accounts.administration.infrastructure.AccountStatusPublisher;
import streaming.core.accounts.identity.application.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdministrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    @TempDir static Path images;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("core.rate-limit-hmac-secret",()->"administration-test-only-secret-32-bytes");p.add("core.images.storage-provider",()->"filesystem");
        p.add("profile.storage-root",()->images.resolve("avatars").toString());p.add("channels.storage-root",()->images.resolve("banners").toString());
    }
    @Autowired IdentityApplicationService identity;
    @Autowired IdentityStore identityStore;
    @Autowired AdministrationService admin;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    String actor, user, session, userSession, channel;
    static final String PASSWORD="fictitious password for tests";
    @BeforeEach void setup() {
        jdbc.sql("TRUNCATE identity.accounts,identity.registrations,identity.sessions,identity.rate_limit_events,profile.profiles,channels.channels CASCADE").update();
        actor=identity.register(UUID.randomUUID(),"admin@example.test","admin_test",PASSWORD,"127.0.0.1").userId();
        var target=identity.register(UUID.randomUUID(),"viewer@example.test","viewer_test",PASSWORD,"127.0.0.1");user=target.userId();channel=target.channelId();
        jdbc.sql("UPDATE identity.accounts SET account_role='ADMIN' WHERE user_id=:id").param("id",actor).update();
        session=identity.login("admin_test",PASSWORD,"127.0.0.1").credential();userSession=identity.login("viewer_test",PASSWORD,"127.0.0.1").credential();
    }
    @Test void protectedListsArePaginatedAndExcludeSecrets() throws Exception {
        assertThat(http("GET","/api/admin/users",null,null,false,null).statusCode()).isEqualTo(401);
        assertThat(http("GET","/api/admin/users",null,userSession,false,null).statusCode()).isEqualTo(403);
        var response=http("GET","/api/admin/users?limit=1",null,session,false,null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("password","email","credential","account_role");
        var page=json.readTree(response.body());assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(admin.accounts(session,page.get("nextCursor").asText(),1).items()).hasSize(1);
        assertThat(http("GET","/api/admin/users?limit=101",null,session,false,null).statusCode()).isEqualTo(400);
        assertThat(admin.channels(session,"",50).items()).hasSize(2);
        assertThatThrownBy(()->admin.channels(session,page.get("nextCursor").asText(),1)).hasMessageContaining("cursor");
        assertThatThrownBy(()->admin.accounts(session,"forged",1)).hasMessageContaining("cursor");
    }
    @Test void statusRequiresCsrfAndValidCommand() throws Exception {
        var command=Map.of("status","SUSPENDED","expectedVersion",0,"reason","Prueba");
        String path="/api/admin/users/"+user+"/status";
        assertThat(http("PUT",path,command,session,false,UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(http("PUT",path,command,userSession,true,UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(http("PUT",path,Map.of("status","SUSPENDED","reason","Prueba"),session,true,UUID.randomUUID()).statusCode()).isEqualTo(400);
        assertThat(http("PUT",path,command,session,true,UUID.randomUUID()).statusCode()).isEqualTo(200);
    }
    @Test void suspensionAtomicallyRevokesSessionsHidesChannelAndQueuesNeutralEvent() throws Exception {
        var result=admin.change(session,user,"SUSPENDED",0,"Prueba",UUID.randomUUID());
        assertThat(result.accountVersion()).isEqualTo(1);
        assertThat(identity.introspect(userSession)).isEmpty();
        assertThatThrownBy(()->identity.login("viewer_test",PASSWORD,"127.0.0.1")).isInstanceOf(IdentityException.class);
        assertThatThrownBy(()->identityStore.createSession(user,"a".repeat(64),Instant.now(),Instant.now().plusSeconds(60))).isInstanceOf(IdentityException.class);
        assertThat(http("GET","/api/channels/"+channel,null,null,false,null).statusCode()).isEqualTo(404);
        assertThat(count("identity.admin_audit")).isEqualTo(1);assertThat(count("identity.account_status_outbox")).isEqualTo(1);
        String body=jdbc.sql("SELECT envelope FROM identity.account_status_outbox").query(String.class).single();
        var event=json.readTree(body);assertThat(event.get("aggregateId").asText()).isEqualTo("account:"+user);
        assertThat(event.get("payload").get("accountVersion").asLong()).isEqualTo(event.get("sequence").asLong());
        assertThat(body).doesNotContain("Prueba",session,userSession,"email");
    }
    @Test void idempotencyKeepsOriginalResultAndDoesNotRepeatAuditOrEvent() {
        UUID key=UUID.randomUUID();var first=admin.change(session,user,"SUSPENDED",0,"Prueba",key);
        assertThat(admin.change(session,user,"SUSPENDED",0,"Prueba",key)).isEqualTo(first);
        assertThatThrownBy(()->admin.change(session,user,"ACTIVE",0,"Prueba",key)).isInstanceOf(IdentityException.class).hasMessageContaining("IDEMPOTENCY_KEY_REUSED");
        assertThat(count("identity.admin_audit")).isEqualTo(1);assertThat(count("identity.account_status_outbox")).isEqualTo(1);
        admin.change(session,user,"ACTIVE",1,"Revisión",UUID.randomUUID());
        assertThat(admin.change(session,user,"SUSPENDED",0,"Prueba",key)).isEqualTo(first);
        assertThat(identity.introspect(userSession)).isEmpty(); // reactivation never revives revoked sessions
        assertThat(identity.login("viewer_test",PASSWORD,"127.0.0.1")).isNotNull();
        assertThat(jdbc.sql("SELECT account_status FROM identity.accounts WHERE user_id=:id").param("id",user).query(String.class).single()).isEqualTo("ACTIVE");
    }
    @Test void staleVersionAndUnknownTargetProduceNoWrites() {
        assertThatThrownBy(()->admin.change(session,user,"SUSPENDED",7,"Prueba",UUID.randomUUID())).hasMessageContaining("ACCOUNT_VERSION_CONFLICT");
        assertThatThrownBy(()->admin.change(session,"usr_unknown","SUSPENDED",0,"Prueba",UUID.randomUUID())).hasMessageContaining("ACCOUNT_NOT_FOUND");
        assertThat(count("identity.admin_operations")).isZero();assertThat(count("identity.admin_audit")).isZero();
    }
    @Test void noOpIsAuditedButDoesNotInventAVersionOrEvent() {
        assertThat(admin.change(session,user,"ACTIVE",0,"Confirmación",UUID.randomUUID()).accountVersion()).isZero();
        assertThat(count("identity.admin_audit")).isEqualTo(1);assertThat(count("identity.account_status_outbox")).isZero();
    }
    @Test void failureToPersistEventRollsBackStatusRevocationAndAudit() {
        jdbc.sql("ALTER TABLE identity.account_status_outbox ADD CONSTRAINT reject_test CHECK(false) NOT VALID").update();
        try { assertThatThrownBy(()->admin.change(session,user,"SUSPENDED",0,"Prueba",UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.sql("ALTER TABLE identity.account_status_outbox DROP CONSTRAINT reject_test").update(); }
        assertThat(identity.introspect(userSession)).isPresent();assertThat(count("identity.admin_audit")).isZero();
        assertThat(count("identity.admin_operations")).isZero();
    }
    @Test void concurrentCommandsCannotBothUseOneVersion() throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task=()->{barrier.await();try{admin.change(session,user,"SUSPENDED",0,"Prueba",UUID.randomUUID());return true;}
                catch(IdentityException ex){assertThat(ex.code()).isEqualTo("ACCOUNT_VERSION_CONFLICT");return false;}};
            var a=pool.submit(task);var b=pool.submit(task);assertThat(a.get(10,TimeUnit.SECONDS)^b.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("identity.account_status_outbox")).isEqualTo(1);
    }
    @Test void confirmLossPreservesEventAndRetryAcknowledgesSamePayload() {
        admin.change(session,user,"SUSPENDED",0,"Prueba",UUID.randomUUID());
        String original=jdbc.sql("SELECT envelope FROM identity.account_status_outbox").query(String.class).single();
        var rabbit=mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        doThrow(new IllegalStateException("offline")).when(rabbit).send(anyString(),anyString(),any(org.springframework.amqp.core.Message.class),any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        new AccountStatusPublisher(jdbc,transactions,rabbit).publish();
        assertThat(jdbc.sql("SELECT count(*) FROM identity.account_status_outbox WHERE published_at_utc IS NULL AND attempts=1").query(Integer.class).single()).isEqualTo(1);
        reset(rabbit);
        doAnswer(call->{
            var message=call.getArgument(2,org.springframework.amqp.core.Message.class);
            assertThat(new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(original);
            call.getArgument(3,org.springframework.amqp.rabbit.connection.CorrelationData.class).getFuture()
                .complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).send(anyString(),anyString(),any(org.springframework.amqp.core.Message.class),any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        jdbc.sql("UPDATE identity.account_status_outbox SET next_attempt_at_utc=CURRENT_TIMESTAMP").update();
        new AccountStatusPublisher(jdbc,transactions,rabbit).publish(); // new publisher instance simulates worker recovery
        assertThat(jdbc.sql("SELECT count(*) FROM identity.account_status_outbox WHERE published_at_utc IS NOT NULL").query(Integer.class).single()).isEqualTo(1);
    }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM "+table).query(Long.class).single(); }
    @Test void publicProxyRoutesAdministrationAndPreservesAuthorization() throws Exception {
        org.testcontainers.Testcontainers.exposeHostPorts(port);
        Path config=Path.of("../../infra/reverse-proxy/Caddyfile").toAbsolutePath().normalize();
        Path index=images.resolve("index.html");java.nio.file.Files.writeString(index,"<!doctype html><title>Vault tv fixture</title>");
        try(var proxy=new org.testcontainers.containers.GenericContainer<>("caddy:2-alpine")
            .withExposedPorts(3000).withAccessToHost(true)
            .withEnv("CORE_UPSTREAM","http://host.testcontainers.internal:"+port)
            .withCopyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(config),"/etc/caddy/Caddyfile")
            .withCopyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(index),"/srv/index.html")) {
            proxy.start();String base="http://"+proxy.getHost()+":"+proxy.getMappedPort(3000);
            var client=HttpClient.newHttpClient();
            var page=client.send(HttpRequest.newBuilder(URI.create(base+"/admin")).header("Accept","text/html").GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);assertThat(page.body()).contains("Vault tv fixture");
            var denied=client.send(HttpRequest.newBuilder(URI.create(base+"/api/admin/users")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(401);
            var allowed=client.send(HttpRequest.newBuilder(URI.create(base+"/api/admin/users")).header("Cookie","stream_session="+session).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(allowed.statusCode()).isEqualTo(200);assertThat(json.readTree(allowed.body()).get("items").size()).isEqualTo(2);
            assertThat(client.send(HttpRequest.newBuilder(URI.create(base+"/internal/core/access-context")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        }
    }
    private HttpResponse<String> http(String method,String path,Object body,String credential,boolean csrf,UUID key) throws Exception {
        var client=HttpClient.newHttpClient();var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        String cookie=credential==null?"":"stream_session="+credential;
        if(csrf) {
            var token=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/identity/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
            String value=json.readTree(token.body()).get("token").asText();builder.header("X-XSRF-TOKEN",value);cookie+="; XSRF-TOKEN="+value;
        }
        if(!cookie.isEmpty())builder.header("Cookie",cookie);
        if(key!=null)builder.header("Idempotency-Key",key.toString());
        if(body!=null)builder.header("Content-Type","application/json");
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
}
