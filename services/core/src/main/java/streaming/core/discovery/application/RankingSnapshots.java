package streaming.core.discovery.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Frozen ordering of a `streams` result so paging does not skip or repeat rows while viewer counts change. */
public interface RankingSnapshots {
    void save(UUID snapshotId,String filterHash,List<String> orderedStreamIds,Instant createdAt,Instant expiresAt);
    /** Empty when unknown or expired. */
    Optional<Snapshot> find(UUID snapshotId,Instant now);
    int purgeExpired(Instant now,int limit);

    record Snapshot(UUID snapshotId,String filterHash,List<String> streamIds) { }
}
