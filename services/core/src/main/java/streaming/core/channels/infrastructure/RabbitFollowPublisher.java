package streaming.core.channels.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import streaming.core.channels.application.FollowApplicationService;
import streaming.core.channels.application.FollowPublisher;
import streaming.core.channels.application.FollowStore;

/** Publishes follow changes with publisher confirms; Notifications consumes them from notifications.follow. */
@Component
@ConditionalOnProperty(name="core.events-transport",havingValue="amqp")
public class RabbitFollowPublisher implements FollowPublisher {
    private final RabbitTemplate broker;

    public RabbitFollowPublisher(RabbitTemplate broker) { this.broker=broker; }

    @Override public void publish(FollowStore.Publication publication) {
        var properties=new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(publication.eventId().toString());
        properties.setType(FollowApplicationService.EVENT_TYPE);
        var correlation=new CorrelationData(UUID.randomUUID().toString());
        broker.send("p2.events",FollowApplicationService.EVENT_TYPE,
                new Message(publication.payload().getBytes(StandardCharsets.UTF_8),properties),correlation);
        try {
            var confirm=correlation.getFuture().get(3,TimeUnit.SECONDS);
            if(!confirm.isAck() || correlation.getReturned()!=null)
                throw new IllegalStateException("Follow publication was not durably routed");
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Follow publication interrupted");
        } catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            throw new IllegalStateException("Follow publication confirmation unavailable");
        }
    }
}
