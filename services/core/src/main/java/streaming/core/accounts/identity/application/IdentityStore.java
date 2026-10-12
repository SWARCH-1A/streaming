package streaming.core.accounts.identity.application;

import java.time.Instant;
import java.util.Optional;

public interface IdentityStore {
    Optional<Registration> registrationByKeyHash(String keyHash);
    Optional<Registration> registration(String registrationId, String keyHash);
    void createAccount(RegistrationDraft draft);
    Registration saveRegistration(RegistrationDraft draft, String channelId);
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
    void purgeExpired(Instant now);
    record RegistrationDraft(String registrationId, String idempotencyKeyHash, String fingerprint,
                             String email, String normalizedEmail, String handle, String passwordHash,
                             String userId, Instant now, Instant retainUntil) { }
    record Registration(String registrationId, String keyHash, String fingerprint, String userId, String channelId) { }
    record PublicIdentity(String userId, String handle) { }
    record Account(String userId, String handle, String passwordHash) { }
    record SessionIdentity(String userId, String handle, Instant expiresAt) { }
}
