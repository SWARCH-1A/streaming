package streaming.core.channels.application;

/** Confirms one outbox event on the broker; throwing leaves it pending for a later attempt. */
public interface FollowPublisher {
    void publish(FollowStore.Publication publication);
}
