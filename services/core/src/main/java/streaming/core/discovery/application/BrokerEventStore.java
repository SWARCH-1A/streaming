package streaming.core.discovery.application;

import streaming.core.discovery.domain.SessionLifecycle;

/**
 * Discovery's durable P2 inbox and lifecycle barrier. The caller owns the transaction: every operation, including
 * the lifecycle row lock, participates in the same transaction as writes through {@link ProjectionStore}.
 */
public interface BrokerEventStore {
    /** @return true when this consumer has not recorded the event ID before. */
    boolean recordInbox(String consumer,String eventId,String fingerprint);
    String inboxFingerprint(String consumer,String eventId);
    void setInboxOutcome(String consumer,String eventId,String outcome);
    void deleteInbox(String consumer,String eventId);

    /** Inserts the first lifecycle barrier, absorbing a concurrent first insert without aborting the transaction. */
    boolean insertLifecycleIfAbsent(SessionLifecycle incoming);
    LifecycleVersion lockLifecycle(String streamId);
    void updateLifecycle(SessionLifecycle incoming);

    record LifecycleVersion(String channelId,long streamGeneration,long sessionVersion,String fingerprint,String status) { }
}
