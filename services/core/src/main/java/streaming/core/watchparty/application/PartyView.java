package streaming.core.watchparty.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Published read contract of a watch party. It never carries other members' identities, private account data or
 * the access-code hash; {@code accessCode} is non-null only in the response that creates or rotates it.
 */
public record PartyView(String partyId,String title,String status,long partyVersion,int maxStreams,int memberCount,
        @JsonProperty("isOwner") boolean isOwner,String accessCode,Person owner,List<StreamEntry> streams,
        Instant createdAtUtc,Instant updatedAtUtc,Instant closedAtUtc) {
    public record Person(String userId,String handle,String displayName,String avatarUri) { }
    public record ChannelInfo(String channelId,String handle,String displayName,String avatarUri,String description,String bannerUri) { }
    /** Streaming fields are null and availability is UNKNOWN when Streaming could not answer. */
    public record StreamEntry(String streamId,Instant addedAtUtc,String title,String category,String status,
            String availability,Integer viewerCount,boolean statusFresh,ChannelInfo channel) { }
}
