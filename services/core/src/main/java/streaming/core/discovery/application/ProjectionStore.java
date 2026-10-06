package streaming.core.discovery.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import streaming.core.discovery.domain.ApplyOutcome;
import streaming.core.discovery.domain.StreamProjection;

/** Writes of the Discovery projection. Callers own the transaction; every method participates in it. */
public interface ProjectionStore {
    /**
     * Offers a projection. Only a greater projectionVersion replaces the row; an equal version must carry the same
     * content and a stream never moves to another channel, otherwise the outcome is a conflict and nothing changes.
     */
    ApplyOutcome apply(StreamProjection incoming,Instant receivedAt,Instant appliedAt);
    Optional<String> projectionHash(String streamId);

    /** @return true when the event ID was new (false: already delivered). */
    boolean recordInbox(String eventId,String contentHash,String streamId,long projectionVersion,Instant now);
    Optional<String> inboxHash(String eventId);
    void setInboxOutcome(String eventId,String outcome);
    int purgeInbox(Instant olderThan,int limit);
    void recordConflict(String eventId,String streamId,Long projectionVersion,String reason,String existingHash,String incomingHash,String payloadJson,Instant now);

    /** Removes rows missing from a consistent cut, but only those committed at or before its watermark. */
    int pruneAbsent(Collection<String> presentStreamIds,long watermark);

    ReconciliationState reconciliationState();
    void saveSuccess(String snapshotId,long watermark,Instant capturedAt,Instant now);
    void saveFailure(String code,Instant now);

    record ReconciliationState(String snapshotId,Long watermark,Instant capturedAt,Instant lastSuccessAt,Instant lastAttemptAt,
            Instant lastFailureAt,String lastFailureCode) { }
}
