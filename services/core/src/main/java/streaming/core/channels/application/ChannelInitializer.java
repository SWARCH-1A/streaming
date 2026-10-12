package streaming.core.channels.application;

import java.time.Instant;

/** Published write interface; the caller owns the registration transaction. */
public interface ChannelInitializer {
    String createInitialChannel(String ownerUserId, Instant now);
}
