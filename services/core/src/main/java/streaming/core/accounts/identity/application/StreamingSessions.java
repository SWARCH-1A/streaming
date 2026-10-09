package streaming.core.accounts.identity.application;

import java.time.Instant;

/** Accounts consumes current session authority; Discovery projections cannot authorize messages. */
public interface StreamingSessions {
    Snapshot session(String sessionId,String requestId);
    record Snapshot(String streamId,String sessionId,long streamGeneration,long sessionVersion,
            String status,String availability,long timelinePositionMs,Instant timelineSampledAtUtc) { }
    final class TimelineUnavailable extends RuntimeException {
        public TimelineUnavailable() { super("Session timeline is unavailable"); }
    }
    final class Unavailable extends RuntimeException {
        private final boolean notFound;
        public Unavailable(boolean notFound) { super("Session authority is unavailable"); this.notFound=notFound; }
        public boolean notFound() { return notFound; }
    }
}
