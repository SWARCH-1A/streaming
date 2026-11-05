package streaming.core.accounts.identity.application;

/** Returns only after a durable broker confirmation; failures leave the SQL outbox pending. */
@FunctionalInterface
public interface RecoveryPublisher {
    void publish(RecoveryStore.Publication publication);
}
