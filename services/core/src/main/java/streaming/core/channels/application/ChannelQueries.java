package streaming.core.channels.application;

import java.util.Optional;
import streaming.core.accounts.profile.application.ProfileApplicationService.ProfileView;

/** Published public read contract. A newly registered channel has no emission configuration. */
public interface ChannelQueries {
    Optional<Bootstrap> byHandle(String canonicalHandle);
    Optional<Bootstrap> byOwner(String userId);
    record ChannelView(String channelId,String ownerUserId,String description,String bannerUri,long channelVersion) { }
    record Bootstrap(ChannelView channel,String handle,ProfileView profile,StreamingChannelSnapshots.Stream stream,
            boolean streamStatusFresh,String availability) { }
}
