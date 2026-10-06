package streaming.core.discovery.domain;

/** Result of offering one projection to the store. Only a greater projectionVersion replaces a row. */
public enum ApplyOutcome {
    APPLIED,
    IGNORED_OLDER,
    IGNORED_SAME,
    CONFLICT_SAME_VERSION,
    CONFLICT_CHANNEL;

    public boolean conflict() { return this==CONFLICT_SAME_VERSION || this==CONFLICT_CHANNEL; }
}
