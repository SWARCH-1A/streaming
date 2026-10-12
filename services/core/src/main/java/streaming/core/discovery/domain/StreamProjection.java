package streaming.core.discovery.domain;

import java.time.Instant;
import java.util.List;

/** Public projection of one Streaming configuration, as published in a StreamDiscoverySnapshot. */
public record StreamProjection(String streamId,String channelId,long projectionVersion,long discoveryPosition,
        long metadataVersion,String title,String categoryId,String categoryName,List<Tag> tags,String sessionId,
        long streamGeneration,Long sessionVersion,String status,String availability,Instant startedAtUtc,
        Instant stateObservedAtUtc,int viewerCount,long countVersion,Instant viewerCountObservedAtUtc,String contentHash) {
    public StreamProjection { tags=List.copyOf(tags); }
    public record Tag(String id,String name) { }
}
