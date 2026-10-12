package streaming.core.channels.application;

import java.util.Optional;

/** Channel-owned port for the account session data required by channel mutations. */
public interface AccountAuthentication {
    Optional<Principal> introspect(String credential);

    record Principal(String userId) { }
}
