package streaming.core.channels.infrastructure;

import java.util.Optional;
import org.springframework.stereotype.Component;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.channels.application.AccountAuthentication;

/** Adapts the Accounts implementation to the interface owned by Channels. */
@Component
public class LocalAccountAuthentication implements AccountAuthentication {
    private final IdentityApplicationService accounts;

    public LocalAccountAuthentication(IdentityApplicationService accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<Principal> introspect(String credential) {
        return accounts.introspect(credential)
                .map(session -> new Principal(session.userId()));
    }
}
