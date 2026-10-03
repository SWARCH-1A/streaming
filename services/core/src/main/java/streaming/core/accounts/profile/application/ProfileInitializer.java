package streaming.core.accounts.profile.application;

import java.time.Instant;

/** Published write interface; the caller owns the registration transaction. */
public interface ProfileInitializer {
    void createInitialProfile(String userId, String handle, Instant now);
}
