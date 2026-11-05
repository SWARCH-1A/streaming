package streaming.core.accounts.identity.infrastructure;

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
import streaming.core.accounts.identity.application.RecoveryApplicationService;
import streaming.core.accounts.identity.application.RecoveryPublisher;
import streaming.core.accounts.identity.application.RecoveryStore;

/** Confirms the original opaque recovery job on the shared broker; SMTP never runs in Core. */
@Component
@ConditionalOnProperty(name="core.events-transport",havingValue="amqp")
public class RabbitRecoveryPublisher implements RecoveryPublisher {
    private final RabbitTemplate broker;

    public RabbitRecoveryPublisher(RabbitTemplate broker) { this.broker=broker; }

    @Override public void publish(RecoveryStore.Publication publication) {
        var properties=new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(publication.eventId());
        properties.setType(RecoveryApplicationService.EVENT_TYPE);
        var correlation=new CorrelationData(UUID.randomUUID().toString());
        broker.send("p2.events",RecoveryApplicationService.EVENT_TYPE,
                new Message(publication.payload().getBytes(StandardCharsets.UTF_8),properties),correlation);
        try {
            var confirm=correlation.getFuture().get(3,TimeUnit.SECONDS);
            if(!confirm.isAck() || correlation.getReturned()!=null)
                throw new IllegalStateException("Recovery publication was not durably routed");
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Recovery publication interrupted");
        } catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            throw new IllegalStateException("Recovery publication confirmation unavailable");
        }
    }
}
