package streaming.core.channels.application;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Channels composes its local read model with one authoritative Streaming batch. */
@Service
public class ChannelBootstrapService {
    private final ChannelQueries channels;
    private final StreamingChannelSnapshots streaming;
    public ChannelBootstrapService(ChannelQueries channels,StreamingChannelSnapshots streaming) { this.channels=channels; this.streaming=streaming; }
    public Optional<ChannelQueries.Bootstrap> byHandle(String handle,String requestId) { return channels.byHandle(handle).map(b->compose(b,requestId)); }
    public Optional<ChannelQueries.Bootstrap> byId(String channelId,String requestId) { return channels.byId(channelId).map(b->compose(b,requestId)); }
    public Optional<ChannelQueries.Bootstrap> byOwner(String owner,String requestId) { return channels.byOwner(owner).map(b->compose(b,requestId)); }
    private ChannelQueries.Bootstrap compose(ChannelQueries.Bootstrap local,String requestId) {
        try {
            var snapshot=streaming.channel(local.channel().channelId(),requestId);
            Instant now=Instant.now();
            if(snapshot.observedAtUtc().isBefore(now.minusSeconds(5)) || snapshot.observedAtUtc().isAfter(now.plusSeconds(5))
                    || (snapshot.configured() && !snapshot.stream().statusFresh())) return unknown(local);
            return new ChannelQueries.Bootstrap(local.channel(),local.handle(),local.profile(),snapshot.stream(),true,
                    snapshot.configured()?snapshot.stream().availability():"OFFLINE");
        } catch(StreamingChannelSnapshots.Unavailable e) { return unknown(local); }
    }
    private static ChannelQueries.Bootstrap unknown(ChannelQueries.Bootstrap local) {
        return new ChannelQueries.Bootstrap(local.channel(),local.handle(),local.profile(),null,false,"UNKNOWN");
    }
}
