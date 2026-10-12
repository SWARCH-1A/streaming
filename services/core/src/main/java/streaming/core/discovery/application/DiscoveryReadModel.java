package streaming.core.discovery.application;

import java.time.Instant;
import java.util.List;
import streaming.core.discovery.domain.ChannelCursor;
import streaming.core.discovery.domain.StreamProjection;

/**
 * Read side of Discovery: one SQL statement per query combining the Streaming projection with the published views
 * of Accounts, Profile and Channels (columns listed explicitly, no private tables, no per-row HTTP).
 */
public interface DiscoveryReadModel {
    /** IDs of fresh, playable, live streams matching the filter, ordered by viewers, start time and ID. */
    List<String> rankedStreamIds(StreamQuery query,Instant oldestFresh,Instant newestFresh,int max);
    List<StreamRow> streams(List<String> streamIds,Instant oldestFresh,Instant newestFresh);
    /** Public channels matching the text, in the contract order, strictly after the cursor. */
    List<ChannelRow> channels(String normalizedQuery,ChannelCursor after,int limit);

    record StreamQuery(String normalizedTitle,String categoryId,String tagId) { }

    record StreamRow(String streamId,String sessionId,String channelId,String handle,String displayName,String avatarUri,
            String title,String categoryId,String categoryName,List<StreamProjection.Tag> tags,String status,String availability,
            int viewerCount,Instant viewerCountObservedAt,Instant startedAt,long metadataVersion,long sessionVersion,
            Instant stateObservedAt) { }

    /** {@code hasProjection} is false when Streaming has published nothing for the channel. */
    record ChannelRow(String channelId,String userId,String handle,String displayName,String avatarUri,long channelVersion,
            int bestClass,int fieldRank,boolean hasProjection,String title,String status,String availability,
            Long metadataVersion,Long sessionVersion,Instant stateObservedAt) { }
}
