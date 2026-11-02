package streaming.core.watchparty.infrastructure;

import java.util.Optional;
import org.springframework.stereotype.Component;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.watchparty.application.AccountSessions;

/** Adapts the Accounts implementation to the interface owned by Watch Party. */
@Component
public class LocalAccountSessions implements AccountSessions {
    private final IdentityApplicationService accounts;

    public LocalAccountSessions(IdentityApplicationService accounts) { this.accounts=accounts; }

    @Override public Optional<Principal> introspect(String credential) {
        return accounts.introspect(credential).map(session->new Principal(session.userId()));
    }
}
