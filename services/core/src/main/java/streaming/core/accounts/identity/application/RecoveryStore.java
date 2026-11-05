package streaming.core.accounts.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cuentas owns both recovery state and its delivery outbox. No external reader uses these tables. */
public interface RecoveryStore {
    void lock(String key);
    Optional<String> operation(String operation, String keyHash, Instant now);
    void remember(String operation, String keyHash, String fingerprint, Instant retainUntil);
    boolean reserveRequest(String emailHash, String ipHash, Instant now);
    boolean reserveConfirmation(String ipHash, Instant now);
    Optional<Account> accountByEmailForUpdate(String normalizedEmail);
    boolean lockAccount(String userId);
    Optional<Recovery> byTokenHash(String tokenHash);
    Optional<Recovery> recoveryForUpdate(String recoveryId, String deliveryId);
    void invalidate(String userId, Instant now);
    void create(Recovery recovery, Instant createdAt, String eventId, String eventJson);
    void consume(Recovery recovery, String passwordHash, Instant now);
    void claim(String recoveryId, int attempt);
    void result(String recoveryId, String status);
    void purge(Instant now);
    List<Publication> leasePublications(UUID leaseId, Instant now, Instant leaseUntil, int limit);
    void published(String eventId, UUID leaseId, Instant now);
    void retry(String eventId, UUID leaseId, Instant nextAttempt);

    record Account(String userId, String email) {
        @Override public String toString() { return "RecoveryAccount[redacted]"; }
    }
    record Recovery(String recoveryId, String deliveryId, String userId, String tokenHash,
                    String encryptedMaterial, Instant expiresAt, Instant usedAt, Instant invalidatedAt,
                    long version, String deliveryState, int deliveryAttempt) {
        public boolean usable(Instant now) { return usedAt == null && invalidatedAt == null && now.isBefore(expiresAt); }
        @Override public String toString() { return "Recovery[" + recoveryId + "]"; }
    }
    record Publication(String eventId, String payload, int attempts) { }
}
