package streaming.core.discovery.domain;

/** Admission belongs to Discovery; HTTP callers do not choose a replica-local quota. */
public interface RequestLimiter {
    record Decision(boolean allowed,long retryAfterSeconds) { }
    Decision tryAcquire(String key);
}
