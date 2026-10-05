package streaming.core.watchparty.infrastructure;

import java.util.Optional;
import org.springframework.stereotype.Component;
import streaming.core.channels.application.ChannelQueries;
import streaming.core.watchparty.application.ChannelDirectory;

/** Adapts the published public channel read contract of Channels; no channel, account or profile table is read here. */
@Component
public class LocalChannelDirectory implements ChannelDirectory {
    private final ChannelQueries channels;

    public LocalChannelDirectory(ChannelQueries channels) { this.channels=channels; }

    @Override public Optional<ChannelCard> byChannelId(String channelId) { return channels.byChannelId(channelId).map(LocalChannelDirectory::card); }
    @Override public Optional<ChannelCard> byOwner(String ownerUserId) { return channels.byOwner(ownerUserId).map(LocalChannelDirectory::card); }

    private static ChannelCard card(ChannelQueries.Bootstrap b) {
        return new ChannelCard(b.channel().channelId(),b.channel().ownerUserId(),b.handle(),b.profile().displayName(),
                b.profile().avatarUri(),b.channel().description(),b.channel().bannerUri());
    }
}
