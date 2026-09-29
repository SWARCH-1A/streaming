package streaming.identity.application;

import java.time.Instant;
import java.util.Optional;

public interface ChannelProvisioner {
    Optional<String> provision(String userId, String registrationId, Instant pendingUntil);
    ProvisionState find(String registrationId);
    void compensate(String registrationId);
    enum ProvisionState { PENDING, PROVISIONED, ABSENT, UNKNOWN }
}
