package streaming.core.discovery.application;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Core's client of Streaming's private consistent cut ({@code POST /internal/streaming/discovery/snapshots}). */
public interface StreamingSnapshotClient {
    /** False when no private Streaming endpoint/credential is configured; reconciliation is then disabled. */
    boolean configured();

    /**
     * @param cursor null for the first page of a new cut
     * @throws SnapshotExpiredException when Streaming answers 410 and a new cut must be started
     * @throws SnapshotUnavailableException on any other failure
     */
    SnapshotPage page(int limit,String cursor);

    record SnapshotPage(String snapshotId,long watermark,Instant capturedAt,Instant expiresAt,List<JsonNode> items,String nextCursor) { }

    class SnapshotExpiredException extends RuntimeException {
        public SnapshotExpiredException() { super("Snapshot expired"); }
    }

    class SnapshotUnavailableException extends RuntimeException {
        private final String code;
        public SnapshotUnavailableException(String code,Throwable cause) { super(code,cause); this.code=code; }
        public String code() { return code; }
    }
}
