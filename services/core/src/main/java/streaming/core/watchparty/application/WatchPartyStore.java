package streaming.core.watchparty.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Watch Party writes stay in this repository and participate in the Core transaction. */
public interface WatchPartyStore {
    /** Creates the party and its owner membership atomically with the caller's transaction. */
    Party create(String partyId,String ownerUserId,String title,String accessCodeHash,Instant now);
    Optional<Party> find(String partyId);
    /** Row lock that serializes concurrent mutations of the same party. */
    Optional<Party> lock(String partyId);
    Optional<Party> lockByCodeHash(String accessCodeHash);
    boolean isMember(String partyId,String userId);
    /** Returns true only when the membership was newly inserted. */
    boolean addMember(String partyId,String userId,Instant now);
    int memberCount(String partyId);
    List<PartyStream> streams(String partyId);
    int countStreams(String partyId);
    boolean hasStream(String partyId,String streamId);
    void addStream(String partyId,String streamId,String channelId,Instant now);
    boolean removeStream(String partyId,String streamId);
    /** Increments partyVersion after an effective change of streams. */
    Party bump(String partyId,Instant now);
    Party rotateCode(String partyId,String accessCodeHash,Instant now);
    Party close(String partyId,Instant now);

    record Party(String partyId,String ownerUserId,String title,String status,long version,Instant createdAt,
            Instant updatedAt,Instant closedAt) {
        public boolean closed() { return "CLOSED".equals(status); }
    }
    record PartyStream(String streamId,String channelId,Instant addedAt) { }
}
