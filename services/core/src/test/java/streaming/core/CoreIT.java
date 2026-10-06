package streaming.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.accounts.identity.application.IdentityException;
import streaming.core.accounts.profile.application.AccountIdentity.Principal;
import streaming.core.accounts.profile.application.ProfileApplicationService;
import streaming.core.accounts.profile.application.ProfileException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoreIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    @TempDir static Path avatars;
    @TempDir static Path banners;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",POSTGRES::getUsername);
        properties.add("spring.datasource.password",POSTGRES::getPassword);
        properties.add("core.rate-limit-hmac-secret",()->"integration-only-secret-at-least-32-bytes");
        properties.add("profile.storage-root",()->avatars.toString());
        properties.add("channels.storage-root",()->banners.toString());
    }

    @Autowired IdentityApplicationService accounts;
    @Autowired ProfileApplicationService profiles;
    @Autowired streaming.core.channels.application.ChannelApplicationService channels;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    private static final String PASSWORD="  exact password 😀  ";
    private CookieManager cookies;
    private HttpClient browser;
    private String csrf;

    @BeforeEach void reset() {
        // Only the disposable container owned by this class can be touched.
        jdbc.sql("TRUNCATE identity.accounts,identity.registrations,identity.sessions,identity.rate_limit_events,profile.profiles,profile.avatar_uploads,channels.channels CASCADE").update();
        cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        browser=HttpClient.newBuilder().cookieHandler(cookies).build();
        csrf=null;
    }

    @Test void successfulRegistrationCommitsDefaultsAndNeverCreatesASession() throws Exception {
        var result=register(UUID.randomUUID(),"Caster+tag@EXAMPLE.TEST","Caster_01");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertCounts(1);
        assertThat(count("identity.sessions")).isZero();
        assertThat(profiles.getPublic(result.userId()).displayName()).isEqualTo("caster_01");
        assertThat(profiles.getPublic(result.userId()).profileVersion()).isZero();
        var bootstrap=request("GET","/api/channels/by-handle/Caster_01",null,false);
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        JsonNode body=json.readTree(bootstrap.body());
        assertThat(body.get("channel").get("channelId").asText()).isEqualTo(result.channelId());
        assertThat(body.get("channel").get("channelVersion").asLong()).isZero();
        assertThat(body.get("handle").asText()).isEqualTo("caster_01");
        assertThat(body.get("stream").isNull()).isTrue();
        assertThat(bootstrap.body()).doesNotContain("email","password","credential","fingerprint");
        assertThat(request("GET","/api/channels/by-owner/"+result.userId(),null,false).body()).isEqualTo(bootstrap.body());
    }

    @ParameterizedTest
    @ValueSource(strings={"profile.profiles","channels.channels","identity.registrations"})
    void failureAtEveryDependentWriteRollsBackTheWholeRegistration(String table) {
        jdbc.sql("ALTER TABLE "+table+" ADD CONSTRAINT test_reject_insert CHECK(false) NOT VALID").update();
        UUID key=UUID.randomUUID();
        try {
            assertThatThrownBy(()->register(key,"rollback@example.test","rollback_01"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertCounts(0);
        } finally { jdbc.sql("ALTER TABLE "+table+" DROP CONSTRAINT test_reject_insert").update(); }
        register(key,"rollback@example.test","rollback_01");
        assertCounts(1);
        assertThat(count("identity.rate_limit_events")).isEqualTo(1);
    }

    @Test void concurrentSameKeyReturnsExactlyTheSameResources() throws Exception {
        UUID key=UUID.randomUUID();
        var barrier=new CyclicBarrier(4);
        try(var executor=Executors.newFixedThreadPool(4)) {
            var futures=java.util.stream.IntStream.range(0,4).mapToObj(i->executor.submit(()-> {
                barrier.await(); return register(key,"race@example.test","race_01");
            })).toList();
            var first=futures.getFirst().get();
            for(var future:futures) assertThat(future.get()).isEqualTo(first);
        }
        assertCounts(1);
        assertThat(count("identity.rate_limit_events")).isEqualTo(1);
    }

    @Test void responseLossReplaysAndDifferentPayloadOrOccupiedNamesHaveDistinctGenericErrors() {
        UUID key=UUID.randomUUID();
        var first=register(key,"unique@example.test","unique_01");
        assertThat(register(key," UNIQUE@EXAMPLE.TEST ","UNIQUE_01")).isEqualTo(first);
        assertThat(accounts.registrationStatus(first.registrationId(),key)).isEqualTo(first);
        assertThatThrownBy(()->register(key,"different@example.test","different_01"))
                .isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        for(var input:List.of(new String[]{"unique@example.test","another_01"},new String[]{"another@example.test","unique_01"})) {
            assertThatThrownBy(()->register(UUID.randomUUID(),input[0],input[1]))
                    .isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.code()).isEqualTo("REGISTRATION_UNAVAILABLE"));
        }
        assertThatThrownBy(()->accounts.registrationStatus(first.registrationId(),UUID.randomUUID()))
                .isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.status().value()).isEqualTo(404));
        assertCounts(1);
    }

    @Test void concurrentConflictingKeysLeaveOneCompleteAccountOnly() throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var tasks=java.util.stream.IntStream.range(0,2).mapToObj(i->executor.submit(()-> {
                barrier.await();
                try { register(UUID.randomUUID(),"same@example.test","same_01"); return "ACTIVE"; }
                catch(IdentityException e) { return e.code(); }
            })).toList();
            assertThat(List.of(tasks.get(0).get(),tasks.get(1).get()))
                    .containsExactlyInAnyOrder("ACTIVE","REGISTRATION_UNAVAILABLE");
        }
        assertCounts(1);
    }

    @Test void httpSessionAndProfileShareCsrfAndLogoutRevokesImmediately() throws Exception {
        obtainCsrf();
        var registration=request("POST","/api/identity/registrations",Map.of("email","http@example.test","handle","http_01","password",PASSWORD),true);
        assertThat(registration.statusCode()).isEqualTo(201);
        assertThat(cookies.getCookieStore().getCookies()).noneMatch(c->c.getName().equals("stream_session"));
        var login=request("POST","/api/identity/sessions",Map.of("login","HTTP_01","password",PASSWORD),true);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).doesNotContain("credential","password");
        assertThat(login.headers().firstValue("set-cookie").orElseThrow()).contains("HttpOnly","SameSite=Lax","Path=/").doesNotContain("Domain=");
        String raw=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("stream_session")).findFirst().orElseThrow().getValue();
        assertThat(raw).matches("[A-Za-z0-9_-]{43}");
        String hash=jdbc.sql("SELECT credential_hash FROM identity.sessions").query(String.class).single();
        assertThat(hash).hasSize(64).isNotEqualTo(raw);
        assertThat(jdbc.sql("SELECT extract(epoch FROM expires_at_utc-created_at_utc) FROM identity.sessions").query(Double.class).single()).isBetween(86399.0,86401.0);
        assertThat(request("GET","/api/profile/me",null,false).statusCode()).isEqualTo(200);
        assertThat(request("PATCH","/api/profile/me",Map.of("displayName","Changed"),false).statusCode()).isEqualTo(403);
        assertThat(request("PATCH","/api/profile/me",Map.of("displayName","Changed"),true).statusCode()).isEqualTo(200);
        JsonNode profile=json.readTree(request("GET","/api/profile/me",null,false).body());
        assertThat(profile.get("profileVersion").asLong()).isEqualTo(1);
        assertThat(request("DELETE","/api/identity/sessions/current",null,false).statusCode()).isEqualTo(403);
        assertThat(request("DELETE","/api/identity/sessions/current",null,true).statusCode()).isEqualTo(204);
        assertThat(accounts.introspect(raw)).isEmpty();
        assertThat(request("GET","/api/profile/me",null,false).statusCode()).isEqualTo(401);
        assertThat(request("PATCH","/api/profile/me",Map.of("bio","x"),true).statusCode()).isEqualTo(401);
    }

    @Test void expiredSessionsAndWrongExactPasswordNeverAuthorize() {
        var account=register(UUID.randomUUID(),"expires@example.test","expires_01");
        assertThatThrownBy(()->accounts.login("expires_01",PASSWORD.trim(),"127.0.0.1"))
                .isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.status().value()).isEqualTo(401));
        var session=accounts.login("expires_01",PASSWORD,"127.0.0.1");
        assertThat(accounts.introspect(session.credential())).isPresent();
        jdbc.sql("UPDATE identity.sessions SET created_at_utc=now()-interval '2 days',expires_at_utc=now()-interval '1 day'").update();
        assertThat(accounts.introspect(session.credential())).isEmpty();
        assertThatThrownBy(()->profiles.requirePrincipal(session.credential())).isInstanceOf(ProfileException.class);
        assertThat(profiles.getPublic(account.userId()).displayName()).isEqualTo("expires_01");
    }

    @Test void noOpAndConcurrentPartialPatchesPreserveFieldsAndPublishLatestProfileInChannel() throws Exception {
        var account=register(UUID.randomUUID(),"patch@example.test","patch_01");
        var principal=new Principal(account.userId(),"patch_01",Instant.now().plusSeconds(3600));
        assertThat(profiles.patch(principal,json.readTree("{\"bio\":\"\"}")).profileVersion()).isZero();
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var name=executor.submit(()->{ barrier.await(); return profiles.patch(principal,json.readTree("{\"displayName\":\"Visible\"}")); });
            var bio=executor.submit(()->{ barrier.await(); return profiles.patch(principal,json.readTree("{\"bio\":\"About\"}")); });
            name.get(); bio.get();
        }
        var result=profiles.getMe(principal);
        assertThat(result.displayName()).isEqualTo("Visible"); assertThat(result.bio()).isEqualTo("About");
        assertThat(result.profileVersion()).isEqualTo(2);
        var bootstrap=json.readTree(request("GET","/api/channels/by-handle/patch_01",null,false).body());
        assertThat(bootstrap.get("profile").get("displayName").asText()).isEqualTo("Visible");
        assertThat(bootstrap.get("profile").get("profileVersion").asLong()).isEqualTo(2);
        assertThatThrownBy(()->profiles.patch(principal,json.readTree("{\"userId\":\"someone_else\"}")))
                .isInstanceOf(ProfileException.class);
        assertThat(profiles.getMe(principal)).isEqualTo(result);
    }

    @Test void avatarPermissionsAreOwnerBoundSingleUseAndSqlFailurePreservesPreviousObject() throws Exception {
        var account=register(UUID.randomUUID(),"avatar@example.test","avatar_01");
        var other=register(UUID.randomUUID(),"other@example.test","other_01");
        var principal=new Principal(account.userId(),"avatar_01",null);
        var stranger=new Principal(other.userId(),"other_01",null);
        byte[] image=png();
        var first=profiles.upload(principal,image);
        assertThatThrownBy(()->profiles.patch(stranger,avatarPatch(first.uploadId()))).isInstanceOf(ProfileException.class);
        var previous=profiles.patch(principal,avatarPatch(first.uploadId()));
        assertThat(request("GET",previous.avatarUri(),null,false).statusCode()).isEqualTo(200);
        assertThatThrownBy(()->profiles.patch(principal,avatarPatch(first.uploadId()))).isInstanceOf(ProfileException.class);
        var next=profiles.upload(principal,image);
        String key=jdbc.sql("SELECT object_key FROM profile.avatar_uploads").query(String.class).single();
        jdbc.sql("ALTER TABLE profile.profiles ADD CONSTRAINT test_reject_update CHECK(profile_version<=1)").update();
        try { assertThatThrownBy(()->profiles.patch(principal,avatarPatch(next.uploadId()))).isInstanceOf(DataIntegrityViolationException.class); }
        finally { jdbc.sql("ALTER TABLE profile.profiles DROP CONSTRAINT test_reject_update").update(); }
        assertThat(profiles.getMe(principal)).isEqualTo(previous);
        assertThat(Files.exists(avatars.resolve("public").resolve(key))).isFalse();
        assertThat(count("profile.avatar_uploads")).isEqualTo(1);
        var replaced=profiles.patch(principal,avatarPatch(next.uploadId()));
        assertThat(replaced.avatarUri()).isNotEqualTo(previous.avatarUri());
        assertThat(request("GET",previous.avatarUri(),null,false).statusCode()).isEqualTo(404);
        assertThat(profiles.readAvatar(key)).containsExactly(image);
    }

    @Test void channelEditsUseCoreSessionCsrfOwnershipAndFreshPublicComposition() throws Exception {
        var account=register(UUID.randomUUID(),"channel@example.test","channel_01");
        var other=register(UUID.randomUUID(),"stranger@example.test","stranger_01");
        String path="/api/channels/"+account.channelId();
        obtainCsrf();
        assertThat(request("PATCH",path,Map.of("description","Hello"),true).statusCode()).isEqualTo(401);
        assertThat(request("POST","/api/identity/sessions",Map.of("login","stranger_01","password",PASSWORD),true).statusCode()).isEqualTo(200);
        assertThat(request("PATCH",path,Map.of("description","Hello"),true).statusCode()).isEqualTo(403);
        request("DELETE","/api/identity/sessions/current",null,true);
        request("POST","/api/identity/sessions",Map.of("login","channel_01","password",PASSWORD),true);
        assertThat(request("PATCH",path,Map.of("description","Hello"),false).statusCode()).isEqualTo(403);
        assertThat(request("PATCH",path,Map.of("description","Hello"),true).statusCode()).isEqualTo(200);
        assertThat(request("PATCH",path,Map.of("description","x".repeat(501)),true).statusCode()).isEqualTo(400);
        assertThat(request("PATCH",path,Map.of("ownerUserId",other.userId()),true).statusCode()).isEqualTo(400);
        var bootstrap=json.readTree(request("GET","/api/channels/by-handle/CHANNEL_01",null,false).body());
        assertThat(bootstrap.get("channel").get("description").asText()).isEqualTo("Hello");
        assertThat(bootstrap.get("channel").get("channelVersion").asLong()).isEqualTo(1);
        assertThat(bootstrap.get("stream").isNull()).isTrue();
        assertThat(request("GET","/api/channels/by-owner/"+account.userId(),null,false).body()).isEqualTo(json.writeValueAsString(bootstrap));
        request("DELETE","/api/identity/sessions/current",null,true);
        assertThat(request("PATCH",path,Map.of("description","Revoked"),true).statusCode()).isEqualTo(401);
        assertThat(request("GET","/api/channels/csrf",null,false).statusCode()).isEqualTo(200);
        for(String retired:List.of("/internal/channels/provision","/internal/channels/provisions/old","/internal/channels/stream-events")) {
            assertThat(request("POST",retired,Map.of(),true).statusCode()).isEqualTo(404);
            assertThat(request("GET",retired,null,false).statusCode()).isEqualTo(404);
        }
    }

    @Test void channelPatchesSerializeDifferentFieldsAndKeepVersionOnNoOpOrInvalidInput() throws Exception {
        var account=register(UUID.randomUUID(),"parallel@example.test","parallel_01");
        var owner=new streaming.core.channels.application.AccountAuthentication.Principal(account.userId());
        assertThat(channels.patch(owner,account.channelId(),json.readTree("{\"description\":null}")).channelVersion()).isZero();
        var upload=channels.upload(owner,account.channelId(),png());
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var description=executor.submit(()->{ barrier.await(); return channels.patch(owner,account.channelId(),json.valueToTree(Map.of("description","😀".repeat(500)))); });
            var banner=executor.submit(()->{ barrier.await(); return channels.patch(owner,account.channelId(),json.valueToTree(Map.of("bannerUploadId",upload.uploadId()))); });
            description.get(); banner.get();
        }
        var bootstrap=json.readTree(request("GET","/api/channels/by-handle/parallel_01",null,false).body());
        assertThat(bootstrap.get("channel").get("description").asText()).isEqualTo("😀".repeat(500));
        assertThat(bootstrap.get("channel").get("bannerUri").asText()).startsWith("/api/channels/banners/");
        assertThat(bootstrap.get("channel").get("channelVersion").asLong()).isEqualTo(2);
        assertThat(channels.patch(owner,account.channelId(),json.valueToTree(Map.of("description","😀".repeat(500)))).channelVersion()).isEqualTo(2);
        assertThatThrownBy(()->channels.patch(owner,account.channelId(),json.readTree("{}"))).isInstanceOf(streaming.core.channels.application.ChannelException.class);
    }

    @Test void channelBannerUploadsAreOwnerBoundSingleUseExpiringAndRollbackSafe() throws Exception {
        var account=register(UUID.randomUUID(),"banner@example.test","banner_01");
        var other=register(UUID.randomUUID(),"bannerother@example.test","banner_other");
        var owner=new streaming.core.channels.application.AccountAuthentication.Principal(account.userId());
        var stranger=new streaming.core.channels.application.AccountAuthentication.Principal(other.userId());
        var first=channels.upload(owner,account.channelId(),png());
        var patch=json.valueToTree(Map.of("bannerUploadId",first.uploadId()));
        assertThatThrownBy(()->channels.upload(stranger,account.channelId(),png())).isInstanceOf(streaming.core.channels.application.ChannelException.class);
        assertThatThrownBy(()->channels.patch(stranger,other.channelId(),patch)).isInstanceOf(streaming.core.channels.application.ChannelException.class);
        var previous=channels.patch(owner,account.channelId(),patch);
        var publicImage=request("GET",previous.bannerUri(),null,false);
        assertThat(publicImage.statusCode()).isEqualTo(200);
        assertThat(publicImage.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("image/png");
        assertThatThrownBy(()->channels.patch(owner,account.channelId(),patch)).isInstanceOf(streaming.core.channels.application.ChannelException.class);
        var next=channels.upload(owner,account.channelId(),png());
        var nextPatch=json.valueToTree(Map.of("bannerUploadId",next.uploadId()));
        String key=jdbc.sql("SELECT object_key FROM channels.banner_uploads").query(String.class).single();
        jdbc.sql("ALTER TABLE channels.channels ADD CONSTRAINT test_reject_channel_update CHECK(channel_version<=1)").update();
        try { assertThatThrownBy(()->channels.patch(owner,account.channelId(),nextPatch)).isInstanceOf(DataIntegrityViolationException.class); }
        finally { jdbc.sql("ALTER TABLE channels.channels DROP CONSTRAINT test_reject_channel_update").update(); }
        assertThat(Files.exists(banners.resolve("public").resolve(key))).isFalse();
        assertThat(count("channels.banner_uploads")).isEqualTo(1);
        // Rollback after the repository write also restores the permission and retains the old object.
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(()->transaction.execute(status->{
            channels.patch(owner,account.channelId(),nextPatch);
            throw new IllegalStateException("rollback after write");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(banners.resolve("public").resolve(key))).isFalse();
        assertThat(request("GET",previous.bannerUri(),null,false).statusCode()).isEqualTo(200);
        var replaced=channels.patch(owner,account.channelId(),nextPatch);
        assertThat(replaced.channelVersion()).isEqualTo(2);
        assertThat(request("GET",previous.bannerUri(),null,false).statusCode()).isEqualTo(404);
        assertThat(request("GET",replaced.bannerUri(),null,false).statusCode()).isEqualTo(200);
        var expired=channels.upload(owner,account.channelId(),png());
        jdbc.sql("UPDATE channels.banner_uploads SET created_at_utc=now()-interval '1 hour',expires_at_utc=now()-interval '1 minute'").update();
        assertThatThrownBy(()->channels.patch(owner,account.channelId(),json.valueToTree(Map.of("bannerUploadId",expired.uploadId())))).isInstanceOf(streaming.core.channels.application.ChannelException.class);
        channels.purgeExpiredUploads();
        assertThat(count("channels.banner_uploads")).isZero();
        assertThat(channels.patch(owner,account.channelId(),json.readTree("{\"bannerUploadId\":null}")).bannerUri()).isNull();
        assertThat(request("GET",replaced.bannerUri(),null,false).statusCode()).isEqualTo(404);
    }

    @Test void channelBannerMultipartUsesSharedSecurityAndDecodedImages() throws Exception {
        var account=register(UUID.randomUUID(),"multipart@example.test","multipart_01");
        obtainCsrf();
        request("POST","/api/identity/sessions",Map.of("login","multipart_01","password",PASSWORD),true);
        String boundary="channel-boundary";
        var bytes=new ByteArrayOutputStream();
        bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"banner.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bytes.write(png());
        bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var upload=browser.send(HttpRequest.newBuilder(uri("/api/channels/"+account.channelId()+"/banner-uploads"))
                .header("X-XSRF-TOKEN",csrf).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(upload.statusCode()).isEqualTo(201);
        String uploadId=json.readTree(upload.body()).get("uploadId").asText();
        assertThat(request("PATCH","/api/channels/"+account.channelId(),Map.of("bannerUploadId",uploadId),true).statusCode()).isEqualTo(200);
    }

    @Test void csrfCorrelationAndCorsCoverEveryMutationAndRetiredInternalRouteIsDenied() throws Exception {
        for(String path:List.of("/api/identity/registrations","/api/identity/sessions","/api/profile/me/avatar-uploads","/api/channels/chn_test/banner-uploads")) {
            var response=request("POST",path,Map.of(),false);
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(json.readTree(response.body()).get("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        }
        assertThat(request("POST","/internal/identity/sessions/introspect",null,false).statusCode()).isEqualTo(404);
        var preflight=browser.send(HttpRequest.newBuilder(uri("/api/profile/me")).header("Origin","http://localhost:3000")
                .header("Access-Control-Request-Method","PATCH").header("Access-Control-Request-Headers","X-XSRF-TOKEN,Content-Type")
                .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Methods").orElseThrow()).contains("PATCH");
    }

    @Test void rateLimitsAreSqlAuthoritativeAndIdempotentReplaysDoNotConsumeQuota() {
        UUID firstKey=UUID.randomUUID();
        var first=register(firstKey,"quota0@example.test","quota_0");
        for(int i=0;i<12;i++) assertThat(register(firstKey,"quota0@example.test","quota_0")).isEqualTo(first);
        for(int i=1;i<10;i++) register(UUID.randomUUID(),"quota"+i+"@example.test","quota_"+i);
        assertThatThrownBy(()->register(UUID.randomUUID(),"quota10@example.test","quota_10"))
                .isInstanceOfSatisfying(IdentityException.class,e->{ assertThat(e.status().value()).isEqualTo(429); assertThat(e.retryAfterSeconds()).isEqualTo(3600); });
        for(int i=0;i<5;i++) assertThatThrownBy(()->accounts.login("quota_0","wrong","127.0.0.2"))
                .isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.status().value()).isEqualTo(401));
        assertThatThrownBy(()->accounts.login("quota_0",PASSWORD,"127.0.0.2"))
                .isInstanceOfSatisfying(IdentityException.class,e->{ assertThat(e.status().value()).isEqualTo(429); assertThat(e.retryAfterSeconds()).isEqualTo(900); });
    }

    @Test void expiredUploadsAreCleanedInBatchesAndOldOrphansNeverRemoveReferencedAvatars() throws Exception {
        var account=register(UUID.randomUUID(),"cleanup@example.test","cleanup_01");
        var principal=new Principal(account.userId(),"cleanup_01",null);
        var upload=profiles.upload(principal,png());
        jdbc.sql("UPDATE profile.avatar_uploads SET expires_at_utc=now()-interval '1 minute'").update();
        assertThatThrownBy(()->profiles.patch(principal,avatarPatch(upload.uploadId()))).isInstanceOf(ProfileException.class);
        profiles.purgeExpiredUploads();
        assertThat(count("profile.avatar_uploads")).isZero();

        var current=profiles.patch(principal,avatarPatch(profiles.upload(principal,png()).uploadId()));
        String referenced=current.avatarUri().substring(current.avatarUri().lastIndexOf('/')+1);
        Path publicDirectory=avatars.resolve("public"),pendingDirectory=avatars.resolve("pending");
        String orphan=UUID.randomUUID().toString().replace("-","")+".png";
        Files.write(publicDirectory.resolve(orphan),png()); Files.write(pendingDirectory.resolve(orphan),png());
        var old=java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(2*86400));
        for(Path file:List.of(publicDirectory.resolve(referenced),publicDirectory.resolve(orphan),pendingDirectory.resolve(orphan))) Files.setLastModifiedTime(file,old);
        profiles.purgeExpiredUploads();
        assertThat(Files.exists(publicDirectory.resolve(orphan))).isFalse();
        assertThat(Files.exists(pendingDirectory.resolve(orphan))).isFalse();
        assertThat(profiles.readAvatar(referenced)).isNotEmpty();

        jdbc.sql("INSERT INTO profile.avatar_uploads(upload_id_hash,user_id,object_key,content_type,created_at_utc,expires_at_utc) "
                +"SELECT md5(g::text)||md5('upload:'||g::text),:user,lpad(to_hex(g),32,'0')||'.png','image/png',now()-interval '1 hour',now()-interval '45 minutes' FROM generate_series(1,501) g")
                .param("user",account.userId()).update();
        for(int i=1;i<=501;i++) Files.write(pendingDirectory.resolve(String.format("%032x.png",i)),png());
        profiles.purgeExpiredUploads();
        assertThat(count("profile.avatar_uploads")).isEqualTo(1);
        String remaining=jdbc.sql("SELECT object_key FROM profile.avatar_uploads").query(String.class).single();
        assertThat(Files.exists(pendingDirectory.resolve(remaining))).isTrue();
        profiles.purgeExpiredUploads();
        assertThat(count("profile.avatar_uploads")).isZero();
        for(int i=1;i<=501;i++) assertThat(Files.exists(pendingDirectory.resolve(String.format("%032x.png",i)))).isFalse();
    }

    @Test void baselineRefusesAnExistingSchemaWithoutCoreHistory() throws Exception {
        jdbc.sql("CREATE DATABASE legacy_guard").update();
        String url="jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/legacy_guard";
        try(var connection=java.sql.DriverManager.getConnection(url,POSTGRES.getUsername(),POSTGRES.getPassword());var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA identity"); statement.execute("CREATE TABLE identity.legacy_accounts(id text PRIMARY KEY)");
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword())
                    .schemas("core","identity","profile","channels").defaultSchema("core").load();
            assertThatThrownBy(flyway::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class).hasMessageContaining("non-empty");
            try(var result=statement.executeQuery("SELECT to_regclass('identity.legacy_accounts') IS NOT NULL")) {
                result.next(); assertThat(result.getBoolean(1)).isTrue();
            }
        } finally { jdbc.sql("DROP DATABASE legacy_guard").update(); }
    }

    private IdentityApplicationService.RegistrationView register(UUID key,String email,String handle) {
        return accounts.register(key,email,handle,PASSWORD,"127.0.0.1");
    }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM "+table).query(Long.class).single(); }
    private void assertCounts(long count) {
        for(String table:List.of("identity.accounts","profile.profiles","channels.channels","identity.registrations")) assertThat(count(table)).as(table).isEqualTo(count);
    }
    private URI uri(String path) { return URI.create("http://localhost:"+port+path); }
    private void obtainCsrf() throws Exception {
        var response=request("GET","/api/identity/csrf",null,false);
        assertThat(response.statusCode()).isEqualTo(200);
        csrf=json.readTree(response.body()).get("token").asText();
        assertThat(cookies.getCookieStore().getCookies()).anyMatch(c->c.getName().equals("XSRF-TOKEN") && c.getValue().equals(csrf));
    }
    private HttpResponse<String> request(String method,String path,Object body,boolean withCsrf) throws Exception {
        var request=HttpRequest.newBuilder(uri(path)).header("X-Request-Id",UUID.randomUUID().toString());
        if(withCsrf) request.header("X-XSRF-TOKEN",csrf);
        if(path.equals("/api/identity/registrations")) request.header("Idempotency-Key",UUID.randomUUID().toString());
        if(body!=null) request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return browser.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode avatarPatch(String uploadId) { return json.valueToTree(Map.of("avatarUploadId",uploadId)); }
    private static byte[] png() throws Exception {
        var output=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(200,200,BufferedImage.TYPE_INT_RGB),"png",output); return output.toByteArray();
    }
}
