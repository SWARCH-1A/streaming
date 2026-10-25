package streaming.core.accounts.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import streaming.core.accounts.identity.application.RecoveryApplicationService;
import streaming.core.accounts.identity.application.RecoveryStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class RabbitRecoveryPublisherTest {
    private final RabbitTemplate broker=mock(RabbitTemplate.class);
    private final RabbitRecoveryPublisher publisher=new RabbitRecoveryPublisher(broker);
    private final RecoveryStore.Publication event=new RecoveryStore.Publication(
            "11111111-1111-4111-8111-111111111111","{\"fixture\":\"opaque-recovery-job\"}",1);

    @Test void waitsForConfirmationAndRetainsTheExactStoredBodyAndIdentity() {
        doAnswer(call->{
            Message message=call.getArgument(2);
            assertThat(message.getBody()).isEqualTo(event.payload().getBytes(StandardCharsets.UTF_8));
            var properties=message.getMessageProperties();
            assertThat(properties.getMessageId()).isEqualTo(event.eventId());
            assertThat(properties.getType()).isEqualTo(RecoveryApplicationService.EVENT_TYPE);
            assertThat(properties.getContentType()).isEqualTo("application/json");
            assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            CorrelationData confirmation=call.getArgument(3);
            confirmation.getFuture().complete(new CorrelationData.Confirm(true,null));
            return null;
        }).when(broker).send(eq("p2.events"),eq(RecoveryApplicationService.EVENT_TYPE),any(Message.class),any(CorrelationData.class));
        publisher.publish(event);
    }

    @Test void anUnroutedPublicationDoesNotCountAsDurableDeliveryEvenWithAnAck() {
        doAnswer(call->{
            CorrelationData confirmation=call.getArgument(3);
            confirmation.setReturned(new ReturnedMessage(call.getArgument(2),312,"NO_ROUTE","p2.events",RecoveryApplicationService.EVENT_TYPE));
            confirmation.getFuture().complete(new CorrelationData.Confirm(true,null));
            return null;
        }).when(broker).send(any(),any(),any(Message.class),any(CorrelationData.class));
        assertThatThrownBy(()->publisher.publish(event)).isInstanceOf(IllegalStateException.class);
    }

    @Test void aNackLeavesThePublicationUnconfirmed() {
        doAnswer(call->{
            CorrelationData confirmation=call.getArgument(3);
            confirmation.getFuture().complete(new CorrelationData.Confirm(false,"fixture-broker-rejection"));
            return null;
        }).when(broker).send(any(),any(),any(Message.class),any(CorrelationData.class));
        assertThatThrownBy(()->publisher.publish(event)).isInstanceOf(IllegalStateException.class);
    }
}
