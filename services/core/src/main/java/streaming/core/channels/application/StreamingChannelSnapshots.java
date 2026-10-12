package streaming.core.channels.application;

import java.time.Instant;
import java.util.List;

/** Public remote read contract owned by Streaming; only these explicit columns reach a bootstrap. */
public interface StreamingChannelSnapshots {
    Snapshot channel(String channelId,String requestId);
    record Snapshot(boolean configured,Stream stream,Instant observedAtUtc) { }
    record Label(String id,String name) { }
    record Stream(String streamId,String channelId,String sessionId,long streamGeneration,String title,
            Label category,List<Label> tags,String status,String availability,boolean statusFresh,long metadataVersion,
            Long sessionVersion,Long viewerCount,Long countVersion,Instant viewerCountObservedAtUtc,Session session) {
        public Stream { tags=List.copyOf(tags); }
    }
    record Session(String sessionId,String streamId,String channelId,long streamGeneration,String status,
            String availability,String playbackUrl,long timelinePositionMs,Instant timelineSampledAtUtc,long metadataVersion,
            long sessionVersion,long viewerCount,long countVersion,Instant viewerCountObservedAtUtc) { }
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("Channel authority is unavailable"); }
    }
}
