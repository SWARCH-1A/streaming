package streaming.core.watchparty.application;

import java.util.Optional;

/** Watch Party-owned port for the account session data its commands require. */
public interface AccountSessions {
    Optional<Principal> introspect(String credential);

    record Principal(String userId) { }
}
