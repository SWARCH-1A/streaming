package streaming.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IdentityStore {
    Optional<Registration> registrationByKeyHash(String keyHash);
    Optional<Registration> registration(String registrationId, String keyHash);
    Registration reserve(RegistrationDraft draft);
    List<Registration> dueRegistrations(Instant now, int limit);
    Optional<Registration> pendingRegistration(String registrationId);
    boolean activate(String registrationId, String channelId, Instant now);
    boolean expire(String registrationId, Instant now);
    void scheduleRetry(String registrationId, Instant nextAt);
    Optional<PublicIdentity> activeByUserId(String userId);
    Optional<PublicIdentity> activeByHandle(String canonicalHandle);
    Optional<Account> activeByLogin(String normalizedLogin, boolean email);
    boolean tryRecordRegistrationKey(String ipHash, String keyHash, Instant now);
    boolean loginLimitReached(String identifierHash, String ipHash, Instant since);
    boolean tryRecordLoginFailure(String identifierHash, String ipHash, Instant now);
    void resetIdentifierFailures(String identifierHash);
    void createSession(String userId, String credentialHash, Instant createdAt, Instant expiresAt);
    Optional<SessionIdentity> introspect(String credentialHash, Instant now);
    void revokeSession(String credentialHash, Instant now);
    void markCompensationComplete(String registrationId);
    List<Registration> expiredNeedingCompensation(Instant now, int limit);
    void purgeExpired(Instant now);
    record RegistrationDraft(String registrationId, String idempotencyKeyHash, String fingerprint,
                             String email, String normalizedEmail, String handle, String passwordHash,
                             String userId, Instant now, Instant deadline, Instant retainUntil) { }
    record Registration(String registrationId, String keyHash, String fingerprint, String status,
                        String userId, String channelId, Instant pendingSince, Instant deadline,
                        String email, String normalizedEmail, String handle, String passwordHash, int attemptCount) { }
    record PublicIdentity(String userId, String handle) { }
    record Account(String userId, String handle, String passwordHash) { }
    record SessionIdentity(String userId, String handle, Instant expiresAt) { }
}
