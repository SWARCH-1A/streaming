package streaming.core.accounts.identity;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.accounts.identity.application.IdentityException;
import streaming.core.accounts.identity.application.RecoveryApplicationService;
import streaming.core.accounts.identity.application.RecoverySecrets;
import streaming.core.accounts.identity.application.RecoveryStore;
import streaming.core.accounts.identity.infrastructure.JdbcIdentityStore;
import streaming.core.security.PrivateCoreListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecoveryIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    @TempDir static Path avatars;
    @TempDir static Path banners;
    private static final String OLD = "  original password 😀  ";
    private static final String NEW = "  replacement password 🛰️  ";
    private static final String EMAIL = "fixture@example.test";
    private static final String WORKER = "fixture-recovery-worker-secret-at-least-32-bytes";

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword);
        p.add("core.rate-limit-hmac-secret", () -> "fixture-only-secret-at-least-32-bytes");
        p.add("core.images.storage-provider", () -> "filesystem");
        p.add("profile.storage-root", () -> avatars.toString());
        p.add("channels.storage-root", () -> banners.toString());
        p.add("core.recovery.enabled", () -> "true");
        p.add("core.recovery.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        p.add("core.internal.enabled", () -> "true");
        p.add("core.internal.port", () -> "0");
        p.add("core.internal.development-http", () -> "true");
        p.add("core.internal.streaming-service-token", () -> "fixture-streaming-token-at-least-32-bytes");
        p.add("core.internal.recovery-service-token", () -> WORKER);
    }

    @Autowired IdentityApplicationService accounts;
    @Autowired RecoveryApplicationService recovery;
    @Autowired RecoveryStore store;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired PrivateCoreListener listener;
    @MockitoSpyBean JdbcIdentityStore identityStore;
    @Value("${local.server.port}") int port;
    private HttpClient browser;
    private String csrf;

    @BeforeEach void reset() throws Exception {
        jdbc.sql("TRUNCATE identity.accounts,identity.registrations,identity.sessions,identity.rate_limit_events,identity.recovery_operations CASCADE").update();
        browser = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var response = browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/identity/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        csrf = json.readTree(response.body()).get("token").asText();
    }

    @Test void httpResponseIsUniformAndIdempotentForExistingAndMissingAccounts() throws Exception {
        register();
        UUID key = UUID.randomUUID();
        var known = request("/api/identity/recovery-requests", Map.of("email", EMAIL), key, true);
        var missing = request("/api/identity/recovery-requests", Map.of("email", "missing@example.test"), UUID.randomUUID(), true);
        var repeated = request("/api/identity/recovery-requests", Map.of("email", " FIXTURE@EXAMPLE.TEST "), key, true);
        assertThat(known.statusCode()).isEqualTo(202);
        assertThat(missing.statusCode()).isEqualTo(known.statusCode());
        assertThat(missing.body()).isEqualTo(known.body());
        assertThat(repeated.body()).isEqualTo(known.body());
        assertThat(known.headers().firstValue("cache-control")).contains("no-store");
        assertThat(count("identity.credential_recoveries")).isEqualTo(1);
        assertThat(count("identity.recovery_outbox")).isEqualTo(1);
        assertThat(request("/api/identity/recovery-requests", Map.of("email", "other@example.test"), key, true).statusCode()).isEqualTo(409);
        assertThat(known.body()).doesNotContain(EMAIL, "token", "userId", "recoveryId");
    }

    @Test void csrfExactFieldsAndCredentialShapesAreValidated() throws Exception {
        assertThat(request("/api/identity/recovery-requests", Map.of("email", EMAIL), UUID.randomUUID(), false).statusCode()).isEqualTo(403);
        assertThat(request("/api/identity/recovery-requests", Map.of("email", EMAIL, "userId", "usr_other"), UUID.randomUUID(), true).statusCode()).isEqualTo(400);
        assertThat(request("/api/identity/recovery-requests", Map.of("email", "bad"), UUID.randomUUID(), true).statusCode()).isEqualTo(400);
        assertThat(request("/api/identity/recovery-requests", Map.of("email", "a".repeat(20_000)), UUID.randomUUID(), true).statusCode()).isEqualTo(413);
        assertThat(request("/api/identity/recoveries/confirm", Map.of("recoveryToken", "short", "newPassword", NEW), UUID.randomUUID(), true).statusCode()).isEqualTo(400);
        assertThat(count("identity.credential_recoveries")).isZero();
    }

    @Test void confirmationRevokesEverySessionPreservesResourcesAndAllowsOnlyExactNewPassword() {
        var account = register();
        var first = accounts.login(EMAIL, OLD, "login-ip-1");
        var second = accounts.login(EMAIL, OLD, "login-ip-2");
        var issued = issue();
        String token = token(issued);
        UUID key = UUID.randomUUID();
        recovery.confirm(key, token, NEW, "confirm-ip");
        assertThat(accounts.introspect(first.credential())).isEmpty();
        assertThat(accounts.introspect(second.credential())).isEmpty();
        error(() -> accounts.login(EMAIL, OLD, "login-ip-3"), 401);
        error(() -> accounts.login(EMAIL, NEW.trim(), "login-ip-3"), 401);
        assertThat(accounts.login(EMAIL, NEW, "login-ip-3").userId()).isEqualTo(account.userId());
        assertThat(jdbc.sql("SELECT user_id FROM profile.profiles").query(String.class).single()).isEqualTo(account.userId());
        assertThat(jdbc.sql("SELECT channel_id FROM channels.channels").query(String.class).single()).isEqualTo(account.channelId());
        recovery.confirm(key, token, NEW, "confirm-ip");
        error(() -> recovery.confirm(UUID.randomUUID(), token, NEW, "confirm-ip"), 422);
        error(() -> recovery.confirm(key, token, OLD, "confirm-ip"), 409);
        assertThat(jdbc.sql("SELECT encrypted_material FROM identity.credential_recoveries").query(String.class).optional()).isEmpty();
    }

    @Test void concurrentConfirmationsOnlyConsumeOnce() throws Exception {
        register();
        String token = token(issue());
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                barrier.await();
                try { recovery.confirm(UUID.randomUUID(), token, NEW + i, "concurrent-" + i); return 204; }
                catch (IdentityException error) { return error.status().value(); }
            })).toList();
            assertThat(List.of(tasks.get(0).get(), tasks.get(1).get())).containsExactlyInAnyOrder(204, 422);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM identity.credential_recoveries WHERE used_at_utc IS NOT NULL").query(Long.class).single()).isEqualTo(1);
    }

    @Test void loginVerifiedBeforeResetCannotCreateASessionAfterReset() throws Exception {
        var account = register();
        String token = token(issue());
        var verified = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            verified.countDown();
            if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return invocation.callRealMethod();
        }).when(identityStore).accountForUpdate(account.userId());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var login = executor.submit(() -> {
                try { accounts.login(EMAIL, OLD, "login-ip"); return 200; }
                catch (IdentityException error) { return error.status().value(); }
            });
            try {
                assertThat(verified.await(10, TimeUnit.SECONDS)).isTrue();
                recovery.confirm(UUID.randomUUID(), token, NEW, "confirm-ip");
            } finally { resume.countDown(); }
            assertThat(login.get(10, TimeUnit.SECONDS)).isEqualTo(401);
        }
        assertThat(count("identity.sessions")).isZero();
    }

    @Test void rollbackRestoresTokenPasswordSessionsAndIdempotencyTogether() {
        register();
        var session = accounts.login(EMAIL, OLD, "login-ip");
        String token = token(issue());
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            recovery.confirm(key, token, NEW, "confirm-ip");
            throw new IllegalStateException("fixture rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.introspect(session.credential())).isPresent();
        assertThat(accounts.login(EMAIL, OLD, "login-ip")).isNotNull();
        recovery.confirm(key, token, NEW, "confirm-ip");
        assertThat(accounts.introspect(session.credential())).isEmpty();
    }

    @Test void requestAndOutboxAreRolledBackTogether() {
        register();
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            recovery.request(key, EMAIL, "request-ip", "req_fixture");
            throw new IllegalStateException("fixture rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("identity.credential_recoveries")).isZero();
        assertThat(count("identity.recovery_outbox")).isZero();
        recovery.request(key, EMAIL, "request-ip", "req_fixture");
        assertThat(count("identity.credential_recoveries")).isEqualTo(1);
        assertThat(count("identity.recovery_outbox")).isEqualTo(1);
    }

    @Test void expirationReplacementAndTamperingDoNotChangePassword() {
        register();
        String first = token(issue());
        String second = token(issue());
        error(() -> recovery.confirm(UUID.randomUUID(), first, NEW, "confirm-ip"), 422);
        String tampered = (second.startsWith("a") ? "b" : "a") + second.substring(1);
        error(() -> recovery.confirm(UUID.randomUUID(), tampered, NEW, "confirm-ip"), 422);
        jdbc.sql("UPDATE identity.credential_recoveries SET created_at_utc=now()-interval '1 hour',expires_at_utc=now()-interval '1 second'").update();
        error(() -> recovery.confirm(UUID.randomUUID(), second, NEW, "confirm-ip"), 422);
        assertThat(accounts.login(EMAIL, OLD, "login-ip")).isNotNull();
        recovery.purge();
        assertThat(jdbc.sql("SELECT count(*) FROM identity.credential_recoveries WHERE encrypted_material IS NOT NULL").query(Long.class).single()).isZero();
    }

    @Test void quotasIncludeMissingAddressesAndReplaysDoNotCreateAnotherDelivery() {
        register();
        for (String address : List.of(EMAIL, "missing@example.test")) {
            UUID key = UUID.randomUUID();
            recovery.request(key, address, "request-ip", "req_fixture");
            for (int i = 0; i < 5; i++) recovery.request(key, address, "request-ip", "req_fixture");
            recovery.request(UUID.randomUUID(), address, "request-ip", "req_fixture");
            recovery.request(UUID.randomUUID(), address, "request-ip", "req_fixture");
            error(() -> recovery.request(UUID.randomUUID(), address, "request-ip", "req_fixture"), 429);
        }
        assertThat(count("identity.credential_recoveries")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM identity.rate_limit_events WHERE bucket_type='RECOVERY_EMAIL'").query(Long.class).single()).isEqualTo(6);
    }

    @Test void ipQuotaIsSharedAcrossAddressesAndConfirmationAttempts() {
        for (int i = 0; i < 10; i++) recovery.request(UUID.randomUUID(), "missing" + i + "@example.test", "same-ip", "req_fixture");
        error(() -> recovery.request(UUID.randomUUID(), EMAIL, "same-ip", "req_fixture"), 429);
        error(() -> recovery.confirm(UUID.randomUUID(), "a".repeat(43), NEW, "same-ip"), 429);
        recovery.request(UUID.randomUUID(), EMAIL, "different-ip", "req_fixture");
    }

    @Test void encryptedDeliveryAndBrokerPayloadKeepSecretsOutOfStorageAndEvents() {
        register();
        var issued = issue();
        String token = token(issued);
        String encrypted = jdbc.sql("SELECT encrypted_material FROM identity.credential_recoveries").query(String.class).single();
        assertThat(encrypted).doesNotContain(EMAIL, token);
        String hash = jdbc.sql("SELECT token_hash FROM identity.credential_recoveries").query(String.class).single();
        assertThat(hash).isEqualTo(RecoverySecrets.hash(token));
        String event = jdbc.sql("SELECT payload FROM identity.recovery_outbox").query(String.class).single();
        assertThat(event).doesNotContain(EMAIL, token, "resetUrl", "password");
        error(() -> recovery.claim(UUID.randomUUID(), issued.recoveryId(), "del_" + "x".repeat(32), 1), 404);
        recovery.result(UUID.randomUUID(), issued.recoveryId(), issued.deliveryId(), 1, "DELIVERED");
        error(() -> recovery.claim(UUID.randomUUID(), issued.recoveryId(), issued.deliveryId(), 1), 410);
        recovery.confirm(UUID.randomUUID(), token, NEW, "confirm-ip");
    }

    @Test void privateCredentialCannotReadOtherRoutesAndPublicListenerHidesClaims() throws Exception {
        register();
        var issued = issue();
        String path = "/internal/core/recovery-deliveries/" + issued.recoveryId() + "/claim";
        String body = json.writeValueAsString(Map.of("deliveryId", issued.deliveryId(), "attempt", 1));
        assertThat(privateRequest(port, path, body, "recovery-email", WORKER).statusCode()).isEqualTo(404);
        assertThat(privateRequest(listener.localPort(), path, body, "streaming", "fixture-streaming-token-at-least-32-bytes").statusCode()).isEqualTo(401);
        assertThat(privateRequest(listener.localPort(), path, "x".repeat(20_000), "streaming", "fixture-streaming-token-at-least-32-bytes").statusCode()).isEqualTo(401);
        var authorized = privateRequest(listener.localPort(), path, body, "recovery-email", WORKER);
        assertThat(authorized.statusCode()).isEqualTo(200);
        assertThat(authorized.headers().firstValue("cache-control")).contains("no-store");
        assertThat(privateRequest(listener.localPort(), "/internal/core/chat/message-context", "{}", "recovery-email", WORKER).statusCode()).isEqualTo(401);
    }

    @Test void deliveryRetriesPreserveTheTokenAndStaleOutcomesCannotOverwriteSuccess() {
        register();
        var first = issue();
        UUID resultKey = UUID.randomUUID();
        recovery.result(resultKey, first.recoveryId(), first.deliveryId(), 1, "RETRYABLE_FAILURE");
        recovery.result(resultKey, first.recoveryId(), first.deliveryId(), 1, "RETRYABLE_FAILURE");
        var second = recovery.claim(UUID.randomUUID(), first.recoveryId(), first.deliveryId(), 2);
        assertThat(second.resetUrl()).isEqualTo(first.resetUrl());
        assertThat(second.expiresAtUtc()).isEqualTo(first.expiresAtUtc());
        error(() -> recovery.result(UUID.randomUUID(), first.recoveryId(), first.deliveryId(), 1, "DELIVERED"), 409);
        recovery.result(UUID.randomUUID(), first.recoveryId(), first.deliveryId(), 2, "DELIVERED");
        error(() -> recovery.result(UUID.randomUUID(), first.recoveryId(), first.deliveryId(), 2, "RETRYABLE_FAILURE"), 409);
        assertThat(jdbc.sql("SELECT delivery_state FROM identity.credential_recoveries").query(String.class).single()).isEqualTo("DELIVERED");
    }

    @Test void outboxLeasesExpireAndKeepStableEventIdentityUntilConfirmation() {
        register();
        issue();
        var now = java.time.Instant.now();
        UUID firstLease = UUID.randomUUID();
        var first = transactions.execute(status -> store.leasePublications(firstLease, now, now.plusSeconds(10), 10));
        assertThat(first).hasSize(1);
        List<RecoveryStore.Publication> held = transactions.execute(status -> store.leasePublications(UUID.randomUUID(), now, now.plusSeconds(10), 10));
        assertThat(held).isEmpty();
        UUID secondLease = UUID.randomUUID();
        var repeated = transactions.execute(status -> store.leasePublications(secondLease, now.plusSeconds(11), now.plusSeconds(21), 10));
        assertThat(repeated).hasSize(1);
        assertThat(repeated.getFirst().eventId()).isEqualTo(first.getFirst().eventId());
        assertThat(repeated.getFirst().payload()).isEqualTo(first.getFirst().payload());
        store.published(first.getFirst().eventId(), firstLease, now);
        assertThat(jdbc.sql("SELECT count(*) FROM identity.recovery_outbox WHERE published_at_utc IS NOT NULL").query(Long.class).single()).isZero();
        store.published(repeated.getFirst().eventId(), secondLease, now.plusSeconds(12));
        assertThat(jdbc.sql("SELECT count(*) FROM identity.recovery_outbox WHERE published_at_utc IS NOT NULL").query(Long.class).single()).isEqualTo(1);
    }

    private IdentityApplicationService.RegistrationView register() { return accounts.register(UUID.randomUUID(), EMAIL, "fixture_01", OLD, "registration-ip"); }
    private RecoveryApplicationService.Delivery issue() {
        recovery.request(UUID.randomUUID(), EMAIL, "request-ip", "req_fixture");
        JsonNode payload = json.readTree(jdbc.sql("SELECT payload FROM identity.recovery_outbox ORDER BY created_at_utc DESC LIMIT 1").query(String.class).single()).get("payload");
        return recovery.claim(UUID.randomUUID(), payload.get("recoveryId").asText(), payload.get("deliveryId").asText(), 1);
    }
    private String token(RecoveryApplicationService.Delivery delivery) { return URI.create(delivery.resetUrl()).getFragment().substring("token=".length()); }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single(); }
    private static void error(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(IdentityException.class, error -> assertThat(error.status().value()).isEqualTo(status));
    }
    private HttpResponse<String> request(String path, Object value, UUID key, boolean includeCsrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value)));
        if (includeCsrf) builder.header("X-XSRF-TOKEN", csrf);
        return browser.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> privateRequest(int targetPort, String path, String body, String name, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Service-Name", name).header("X-Service-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
