package streaming.core.discovery.infrastructure;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import streaming.core.discovery.application.BrokerEventIngestService;
import streaming.core.discovery.application.DiscoveryException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Native JSON/AMQP adapter; no shared Java domain DTO is exported to other languages. */
@Configuration
@ConditionalOnProperty(name="core.events-transport",havingValue="amqp")
public class BrokerConfiguration {
    private static final Logger log=LoggerFactory.getLogger(BrokerConfiguration.class);
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    @Bean CachingConnectionFactory brokerConnectionFactory(
            @Value("${spring.rabbitmq.host}") String host,@Value("${spring.rabbitmq.port:5671}") int port,
            @Value("${spring.rabbitmq.virtual-host:streaming-p2}") String vhost,
            @Value("${spring.rabbitmq.username}") String username,@Value("${spring.rabbitmq.password}") String password) throws Exception {
        var raw=new com.rabbitmq.client.ConnectionFactory();
        raw.setHost(host); raw.setPort(port); raw.setVirtualHost(vhost); raw.setUsername(username); raw.setPassword(password);
        raw.useSslProtocol(SSLContext.getDefault()); raw.enableHostnameVerification();
        raw.setRequestedHeartbeat(30); raw.setConnectionTimeout(3000);
        // Spring's connection/container recovery owns reconnection.
        raw.setAutomaticRecoveryEnabled(false);
        var factory=new CachingConnectionFactory(raw);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    @Bean RabbitTemplate brokerTemplate(CachingConnectionFactory brokerConnectionFactory) {
        var template=new RabbitTemplate(brokerConnectionFactory);
        template.setMandatory(true);
        return template;
    }

    @Bean SimpleMessageListenerContainer coreLifecycleConsumer(CachingConnectionFactory brokerConnectionFactory,
            RabbitTemplate brokerTemplate,BrokerEventIngestService ingest) {
        return consumer("core.lifecycle",brokerConnectionFactory,brokerTemplate,ingest);
    }
    @Bean SimpleMessageListenerContainer coreDiscoveryConsumer(CachingConnectionFactory brokerConnectionFactory,
            RabbitTemplate brokerTemplate,BrokerEventIngestService ingest) {
        return consumer("core.discovery",brokerConnectionFactory,brokerTemplate,ingest);
    }

    private SimpleMessageListenerContainer consumer(String queue,CachingConnectionFactory factory,RabbitTemplate publisher,BrokerEventIngestService ingest) {
        var container=new SimpleMessageListenerContainer(factory);
        container.setQueueNames(queue);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setConcurrentConsumers(1); container.setPrefetchCount(16);
        container.setMissingQueuesFatal(false); container.setRecoveryInterval(1000L);
        container.setMessageListener((ChannelAwareMessageListener)(message,channel)-> {
            String failure=null;
            boolean permanent=false;
            try {
                if(message.getBody().length>65536 || !"application/json".equals(message.getMessageProperties().getContentType()))
                    throw new IllegalArgumentException("INVALID_EVENT");
                var body=JSON.readTree(message.getBody());
                String outcome=ingest.ingest(queue,body);
                if(outcome.contains("CONFLICT")) { failure=outcome; permanent=true; }
                else {
                    log.info("event=broker_consumed consumer={} eventId={} correlationId={} outcome={} ageMs={}",queue,
                            body.get("eventId").textValue(),body.get("correlationId").textValue(),outcome,
                            Math.max(0,Instant.now().toEpochMilli()-java.time.OffsetDateTime.parse(body.get("occurredAtUtc").textValue()).toInstant().toEpochMilli()));
                }
            } catch(IllegalArgumentException | java.time.DateTimeException | DiscoveryException e) {
                failure="INVALID_EVENT"; permanent=true;
            } catch(tools.jackson.core.JacksonException e) {
                failure="INVALID_JSON"; permanent=true;
            } catch(Exception e) {
                failure="STORE_UNAVAILABLE";
            }
            if(failure!=null) {
                try { retainFailure(publisher,queue,message,failure,permanent); }
                catch(Exception e) {
                    // Keep the source responsibility when retry/DLQ publication is unknown.
                    log.warn("event=broker_retry_publish_unavailable consumer={}",queue);
                    Thread.sleep(1000);
                    channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,true);
                    return;
                }
            }
            channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
        });
        return container;
    }

    private void retainFailure(RabbitTemplate publisher,String queue,Message original,String cause,boolean permanent) throws Exception {
        var properties=MessagePropertiesBuilder.fromClonedProperties(original.getMessageProperties())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
        Object previous=properties.getHeader("x-p2-attempt");
        int attempt=previous instanceof Number number?Math.max(0,number.intValue()):0;
        long now=Instant.now().toEpochMilli();
        properties.setHeader("x-p2-consumer",queue);
        properties.setHeader("x-p2-cause",cause);
        properties.setHeader("x-p2-attempt",attempt+1);
        if(properties.getHeader("x-p2-first-failure")==null) properties.setHeader("x-p2-first-failure",now);
        properties.setHeader("x-p2-last-failure",now);
        if(properties.getHeader("x-p2-original-route")==null)
            properties.setHeader("x-p2-original-route",original.getMessageProperties().getReceivedRoutingKey());
        boolean dead=permanent || attempt>=3;
        String exchange=queue+(dead?".dead":".retry");
        String route=dead?"failed":Integer.toString(new int[]{1,5,30}[attempt]);
        var correlation=new CorrelationData(java.util.UUID.randomUUID().toString());
        publisher.send(exchange,route,new Message(original.getBody(),properties),correlation);
        var confirm=correlation.getFuture().get(3,TimeUnit.SECONDS);
        if(!confirm.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("Retry/DLQ not durably routed");
        log.warn("event=broker_delivery_failed consumer={} cause={} attempt={} destination={}",queue,cause,attempt+1,dead?"DLQ":"RETRY");
    }
}
