package streaming.profile.application;

import java.time.Instant;
import java.util.Optional;

public interface IdentityClient {
    Optional<Principal> introspect(String credential);
    Optional<PublicIdentity> findActiveUser(String userId);
    record Principal(String userId,String handle,Instant expiresAt) { }
    record PublicIdentity(String userId,String handle) { }
}
