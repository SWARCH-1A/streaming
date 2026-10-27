package streaming.core.channels.application;

import java.util.List;

/** Read-only contract owned by Channels; Accounts never writes channel tables. */
public interface ChannelAdministration {
    record Channel(String channelId, String ownerUserId, long channelVersion) { }
    List<Channel> list(String after, int limit);
}
