package streaming.core.watchparty.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Published read contract of a watch party ({@code P2WatchParty}). It never carries other members' identities, private
 * account data or the access-code hash; {@code accessCode} is non-null only in the response that creates or rotates it.
 */
public record PartyView(String partyId,String title,String status,long partyVersion,int maxSources,int memberCount,
        @JsonProperty("isOwner") boolean isOwner,String accessCode,Owner owner,List<Source> sources,
        Instant createdAtUtc,Instant updatedAtUtc,Instant closedAtUtc) {
    public record Owner(String userId,String handle,String displayName,String avatarUri) { }
    public record Channel(String channelId,String handle,String displayName,String avatarUri) { }

    /** What the room can honestly say about one source. Only PLAYABLE hands out a session. */
    public enum SourceState { PLAYABLE, RECONNECTING, OFFLINE, ENDED, SUSPENDED, UNKNOWN }

    /** Streaming fields are null for SUSPENDED and for sources Streaming did not answer; {@code sessionId} only when PLAYABLE. */
    public record Source(String streamId,String sessionId,Instant addedAtUtc,SourceState state,String title,String category,
            Integer viewerCount,boolean statusFresh,Channel channel) { }
}
