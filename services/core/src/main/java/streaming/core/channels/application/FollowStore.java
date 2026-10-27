package streaming.core.channels.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Follow writes, their outbox and recipient cuts stay in the Channels schema and the Core transaction. */
public interface FollowStore {
    Optional<String> channelOwner(String channelId);
    /** Serializes requests that share an Idempotency-Key for the same principal. */
    void lockOperation(String userId,String keyHash);
    Optional<Operation> operation(String userId,String keyHash,Instant now);
    void rememberOperation(String userId,String keyHash,String fingerprint,String response,Instant retainUntil);

    Optional<Follow> find(String userId,String channelId);
    /** Activates the relation; empty when it was already active (no version change). */
    Optional<Follow> follow(String userId,String channelId,Instant now);
    /** Deactivates the relation keeping its version; empty when it was not active. */
    Optional<Follow> unfollow(String userId,String channelId,Instant now);
    void enqueue(UUID eventId,Follow follow,String payload,Instant now);
    /** Active follows ordered by (followedAt DESC, channelId DESC), strictly after the given position. */
    List<Follow> followed(String userId,Position after,int limit);

    Optional<Snapshot> snapshot(String channelId,String sessionId);
    Optional<Snapshot> snapshotById(String snapshotId);
    /** Creates the cut once per channel/session; concurrent callers observe the same committed snapshot. */
    Snapshot captureSnapshot(String snapshotId,String channelId,String sessionId,Instant now,Instant expires);
    List<String> recipients(String snapshotId,String afterUserId,int limit);

    VodSettings vodSettings(String channelId);
    Optional<VodSettings> updateVodSettings(String channelId,boolean enabled,long expectedVersion);

    List<Publication> leasePublications(UUID leaseId,Instant now,Instant leaseUntil,int limit);
    void published(UUID eventId,UUID leaseId,Instant now);
    void retry(UUID eventId,UUID leaseId,Instant nextAttempt);
    void purge(Instant now);

    record Follow(String userId,String channelId,boolean following,long version,Instant followedAt,Instant updatedAt) { }
    record Position(Instant followedAt,String channelId) { }
    record Operation(String fingerprint,String response) { }
    record Snapshot(String snapshotId,String channelId,String sessionId,Instant capturedAt,Instant expiresAt,int recipientCount) { }
    record VodSettings(String channelId,boolean enabled,long version) { }
    record Publication(UUID eventId,String payload,int attempts) { }
}
