package streaming.core.accounts.identity.application;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.accounts.identity.application.RecoveryStore.Recovery;
import streaming.core.accounts.identity.domain.IdentityRules;
import tools.jackson.databind.ObjectMapper;

@Service
public class RecoveryApplicationService {
    public static final String ACCEPTED_MESSAGE = "Si existe una cuenta asociada, recibirás un correo para restablecer la contraseña.";
    public static final String EVENT_TYPE = "credential.recovery.delivery.requested.v1";
    private static final Set<String> OUTCOMES = Set.of("DELIVERED", "RETRYABLE_FAILURE", "PERMANENT_FAILURE", "UNKNOWN");
    private final RecoveryStore store;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final ObjectMapper json;
    private final boolean enabled;
    private final RecoverySecrets secrets;
    private final String origin;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public RecoveryApplicationService(RecoveryStore store, TransactionTemplate transactions, PasswordEncoder passwords,
            ObjectMapper json, @Value("${core.recovery.enabled:false}") boolean enabled,
            @Value("${core.recovery.encryption-key:}") String encryptionKey,
            @Value("${core.rate-limit-hmac-secret}") String rateSecret,
            @Value("${core.public-origin}") String origin) {
        this(store, transactions, passwords, json, enabled, encryptionKey, rateSecret, origin, Clock.systemUTC());
    }

    RecoveryApplicationService(RecoveryStore store, TransactionTemplate transactions, PasswordEncoder passwords,
            ObjectMapper json, boolean enabled, String encryptionKey, String rateSecret, String origin, Clock clock) {
        this.store = store; this.transactions = transactions; this.passwords = passwords; this.json = json;
        this.enabled = enabled; this.clock = clock;
        secrets = enabled ? new RecoverySecrets(encryptionKey, rateSecret) : null;
        this.origin = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        if (enabled) {
            URI uri = URI.create(this.origin);
            boolean localHttp = "http".equals(uri.getScheme()) && uri.getHost() != null
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
            if ((!"https".equals(uri.getScheme()) && !localHttp) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getPath().isEmpty())
                throw new IllegalStateException("Recovery requires a configured HTTPS public origin (HTTP only on loopback).");
        }
    }

    public void request(UUID key, String emailInput, String remoteIp, String correlationId) {
        requireEnabled();
        String email;
        try { email = IdentityRules.canonicalEmail(emailInput); }
        catch (IllegalArgumentException error) { throw invalidPayload(); }
        String fingerprint = secrets.fingerprint("REQUEST\0" + email);
        String keyHash = keyHash(key);
        boolean accepted = Boolean.TRUE.equals(transactions.execute(status -> {
            Instant now = clock.instant();
            if (replayed("REQUEST", keyHash, fingerprint, now)) return true;
            if (!store.reserveRequest(secrets.fingerprint("email\0" + email), ipHash(remoteIp), now)) return false;
            var account = store.accountByEmailForUpdate(email);
            if (account.isPresent()) {
                String recoveryId = "rec_" + RecoverySecrets.random(24);
                String deliveryId = "del_" + RecoverySecrets.random(24);
                String token = RecoverySecrets.random(32);
                String eventId = UUID.randomUUID().toString();
                Instant expires = now.plus(30, ChronoUnit.MINUTES);
                String encrypted = secrets.encrypt(account.get().email() + "\n" + token, recoveryId, deliveryId);
                store.invalidate(account.get().userId(), now);
                Recovery recovery = new Recovery(recoveryId, deliveryId, account.get().userId(), RecoverySecrets.hash(token),
                        encrypted, expires, null, null, 1, "PENDING", 0);
                String event = json.writeValueAsString(Map.of("eventId", eventId, "eventType", EVENT_TYPE,
                        "schemaVersion", 1, "producer", "core", "aggregateId", "recovery:" + recoveryId,
                        "sequence", 1, "occurredAtUtc", now.toString(), "correlationId",
                        correlationId != null && correlationId.matches("[A-Za-z0-9_-]{1,128}") ? correlationId : eventId,
                        "payload", Map.of("recoveryId", recoveryId, "deliveryId", deliveryId)));
                store.create(recovery, now, eventId, event);
            }
            store.remember("REQUEST", keyHash, fingerprint, now.plus(24, ChronoUnit.HOURS));
            return true;
        }));
        if (!accepted) throw new IdentityException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Se alcanzó el límite temporal de solicitudes.", 3600);
    }

    public void confirm(UUID key, String token, String newPassword, String remoteIp) {
        requireEnabled();
        validateToken(token);
        try { IdentityRules.validatePassword(newPassword); }
        catch (IllegalArgumentException error) { throw invalidPayload(); }
        String keyHash = keyHash(key);
        String fingerprint = secrets.fingerprint("CONFIRM\0" + token + "\0" + newPassword);
        Confirmation outcome = transactions.execute(status -> {
            Instant now = clock.instant();
            if (replayed("CONFIRM", keyHash, fingerprint, now)) return Confirmation.SUCCESS;
            if (!store.reserveConfirmation(ipHash(remoteIp), now)) return Confirmation.LIMITED;
            var found = store.byTokenHash(RecoverySecrets.hash(token));
            if (found.isEmpty() || !store.lockAccount(found.get().userId())) return Confirmation.INVALID;
            var current = store.recoveryForUpdate(found.get().recoveryId(), found.get().deliveryId());
            if (current.isEmpty() || !current.get().usable(clock.instant())) return Confirmation.INVALID;
            // The account lock also serializes session creation in login.
            store.consume(current.get(), passwords.encode(newPassword), clock.instant());
            store.remember("CONFIRM", keyHash, fingerprint, now.plus(24, ChronoUnit.HOURS));
            return Confirmation.SUCCESS;
        });
        if (outcome == Confirmation.LIMITED) throw limited();
        if (outcome != Confirmation.SUCCESS) throw new IdentityException(HttpStatus.UNPROCESSABLE_ENTITY,
                "RECOVERY_INVALID", "El enlace no es válido o ha vencido. Solicita uno nuevo.");
    }

    public Delivery claim(UUID key, String recoveryId, String deliveryId, int attempt) {
        requireEnabled();
        validateDelivery(recoveryId, deliveryId, attempt);
        String keyHash = keyHash(key);
        String fingerprint = secrets.fingerprint("CLAIM\0" + recoveryId + "\0" + deliveryId + "\0" + attempt);
        Recovery recovery = transactions.execute(status -> {
            Instant now = clock.instant();
            boolean replay = replayed("CLAIM", keyHash, fingerprint, now);
            Recovery value = store.recoveryForUpdate(recoveryId, deliveryId).orElseThrow(RecoveryApplicationService::notFound);
            if (!value.usable(now) || value.encryptedMaterial() == null) {
                if (Set.of("PENDING", "RETRYABLE_FAILURE").contains(value.deliveryState()))
                    store.result(recoveryId, "PERMANENT_FAILURE");
                return value;
            }
            boolean sameAttempt = attempt == value.deliveryAttempt() && value.deliveryState().equals("CLAIMED");
            boolean nextAttempt = attempt == value.deliveryAttempt() + 1
                    && Set.of("PENDING", "RETRYABLE_FAILURE").contains(value.deliveryState());
            if (!sameAttempt && !nextAttempt) throw conflict();
            if (!sameAttempt) store.claim(recoveryId, attempt);
            if (!replay) store.remember("CLAIM", keyHash, fingerprint, now.plus(24, ChronoUnit.HOURS));
            return value;
        });
        if (!recovery.usable(clock.instant()) || recovery.encryptedMaterial() == null) throw gone();
        String plaintext = secrets.decrypt(recovery.encryptedMaterial(), recoveryId, deliveryId);
        int split = plaintext.indexOf('\n');
        if (split < 1) throw new IllegalStateException("Recovery delivery material is invalid.");
        return new Delivery(recoveryId, deliveryId, attempt, plaintext.substring(0, split),
                origin + "/reset-password#token=" + plaintext.substring(split + 1), recovery.expiresAt());
    }

    public void result(UUID key, String recoveryId, String deliveryId, int attempt, String outcome) {
        requireEnabled();
        validateDelivery(recoveryId, deliveryId, attempt);
        if (outcome == null || !OUTCOMES.contains(outcome)) throw invalidPayload();
        String keyHash = keyHash(key);
        String fingerprint = secrets.fingerprint("RESULT\0" + recoveryId + "\0" + deliveryId + "\0" + attempt + "\0" + outcome);
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            if (replayed("RESULT", keyHash, fingerprint, now)) return;
            Recovery recovery = store.recoveryForUpdate(recoveryId, deliveryId).orElseThrow(RecoveryApplicationService::notFound);
            if (attempt != recovery.deliveryAttempt() || (!recovery.deliveryState().equals("CLAIMED")
                    && !recovery.deliveryState().equals(outcome))) throw conflict();
            store.result(recoveryId, outcome);
            store.remember("RESULT", keyHash, fingerprint, now.plus(24, ChronoUnit.HOURS));
        });
    }

    @Scheduled(fixedDelayString = "${core.recovery.cleanup-interval:PT1M}")
    public void purge() { if (enabled) transactions.executeWithoutResult(status -> store.purge(clock.instant())); }

    private boolean replayed(String operation, String keyHash, String fingerprint, Instant now) {
        store.lock("operation:" + operation + ":" + keyHash);
        var previous = store.operation(operation, keyHash, now);
        if (previous.isPresent() && !previous.get().equals(fingerprint)) throw conflict();
        return previous.isPresent();
    }
    private String ipHash(String ip) { return secrets.fingerprint("ip\0" + (ip == null ? "unknown" : ip)); }
    private static String keyHash(UUID key) { if (key == null) throw invalidPayload(); return RecoverySecrets.hash(key.toString()); }
    private void requireEnabled() {
        if (!enabled) throw new IdentityException(HttpStatus.SERVICE_UNAVAILABLE, "RECOVERY_UNAVAILABLE", "La recuperación no está disponible en este momento.");
    }
    private static void validateToken(String token) { if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidPayload(); }
    private static void validateDelivery(String recoveryId, String deliveryId, int attempt) {
        if (recoveryId == null || !recoveryId.matches("rec_[A-Za-z0-9_-]{32}")
                || deliveryId == null || !deliveryId.matches("del_[A-Za-z0-9_-]{32}") || attempt < 1) throw invalidPayload();
    }
    private static IdentityException limited() { return new IdentityException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Se alcanzó el límite temporal de solicitudes.", 900); }
    private static IdentityException conflict() { return new IdentityException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "La operación entra en conflicto con una solicitud anterior."); }
    private static IdentityException notFound() { return new IdentityException(HttpStatus.NOT_FOUND, "RECOVERY_NOT_FOUND", "No se encontró la entrega solicitada."); }
    private static IdentityException gone() { return new IdentityException(HttpStatus.GONE, "RECOVERY_EXPIRED", "La entrega ya no está disponible."); }
    public static IdentityException invalidPayload() { return new IdentityException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "La solicitud no cumple el contrato."); }
    private enum Confirmation { SUCCESS, INVALID, LIMITED }
    public record Delivery(String recoveryId, String deliveryId, int attempt, String recipient, String resetUrl, Instant expiresAtUtc) {
        @Override public String toString() { return "RecoveryDelivery[" + deliveryId + "]"; }
    }
}
