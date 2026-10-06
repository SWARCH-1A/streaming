package streaming.core.discovery.application;

import java.time.Instant;
import java.util.List;

/** Public result shapes of the GraphQL contract. Field names are the GraphQL field names. */
public final class DiscoveryViews {
    private DiscoveryViews() { }

    public record TaxonomyValue(String id,String name) { }
    public record PublicChannel(String channelId,String handle,String displayName,String avatarUri) { }

    public record LiveStream(String streamId,String sessionId,PublicChannel channel,String title,TaxonomyValue category,
            List<TaxonomyValue> tags,String status,String availability,int viewerCount,boolean viewerCountFresh,
            Instant viewerCountObservedAtUtc,Instant startedAtUtc,long metadataVersion,long sessionVersion,boolean statusFresh) { }

    public record StreamConnection(List<LiveStream> items,String nextCursor,Instant generatedAtUtc,boolean statusFresh) { }

    public record PublicChannelResult(String channelId,String userId,String handle,String displayName,String avatarUri,
            String status,String availability,String title,long channelVersion,Long metadataVersion,Long sessionVersion,
            boolean statusFresh) { }

    public record ChannelConnection(List<PublicChannelResult> items,String nextCursor,Instant generatedAtUtc,boolean statusFresh) { }
}
