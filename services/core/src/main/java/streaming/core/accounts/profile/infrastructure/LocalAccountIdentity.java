package streaming.core.accounts.profile.infrastructure;

import java.util.Optional;
import org.springframework.stereotype.Component;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.accounts.profile.application.AccountIdentity;

@Component
public class LocalAccountIdentity implements AccountIdentity {
    private final IdentityApplicationService accounts;
    public LocalAccountIdentity(IdentityApplicationService accounts) { this.accounts=accounts; }
    @Override public Optional<Principal> introspect(String credential) {
        return accounts.introspect(credential).map(p->new Principal(p.userId(),p.handle(),p.expiresAt()));
    }
    @Override public Optional<PublicIdentity> findActiveUser(String userId) {
        try {
            var user=accounts.publicByUserId(userId);
            return Optional.of(new PublicIdentity(user.userId(),user.handle()));
        } catch(streaming.core.accounts.identity.application.IdentityException e) {
            if(e.status()==org.springframework.http.HttpStatus.NOT_FOUND) return Optional.empty();
            throw e;
        }
    }
}
