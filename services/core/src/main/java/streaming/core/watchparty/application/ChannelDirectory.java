package streaming.core.watchparty.application;

import java.util.Optional;

/** Watch Party-owned port over the published public channel read contract. */
public interface ChannelDirectory {
    Optional<ChannelCard> byChannelId(String channelId);
    Optional<ChannelCard> byOwner(String ownerUserId);

    record ChannelCard(String channelId,String ownerUserId,String handle,String displayName,String avatarUri,
            String description,String bannerUri) { }
}
