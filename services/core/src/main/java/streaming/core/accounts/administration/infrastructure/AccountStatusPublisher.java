package streaming.core.accounts.administration.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** A lost confirm retains the original event. Consumers must deduplicate by eventId/version. */
@Component
@ConditionalOnProperty(name="core.events-transport",havingValue="amqp")
public class AccountStatusPublisher {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbit;
    // Confirm waits must not block Discovery's freshness scheduler.
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("account-status-outbox").factory());
    public AccountStatusPublisher(JdbcClient jdbc,TransactionTemplate transactions,RabbitTemplate rabbit) {
        this.jdbc=jdbc;this.transactions=transactions;this.rabbit=rabbit;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() { worker.scheduleWithFixedDelay(()-> {
        try { publish(); }
        catch(RuntimeException ex) { LoggerFactory.getLogger(getClass()).warn("event=account_status_worker_retry"); }
    },0,1,TimeUnit.SECONDS); }
    @PreDestroy public void stop() { worker.shutdownNow(); }
    public void publish() {
        for(int i=0;i<20;i++) {
            Boolean found=transactions.execute(tx->{
                var pending=jdbc.sql("SELECT event_id,envelope FROM identity.account_status_outbox WHERE published_at_utc IS NULL AND next_attempt_at_utc<=CURRENT_TIMESTAMP ORDER BY created_at_utc LIMIT 1 FOR UPDATE SKIP LOCKED")
                    .query((rs,n)->new Pending(rs.getObject(1,UUID.class),rs.getString(2))).optional();
                if(pending.isEmpty()) return false;
                Pending event=pending.get();
                try {
                    var properties=new MessageProperties();properties.setContentType("application/json");properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    properties.setMessageId(event.id().toString());
                    var correlation=new CorrelationData(UUID.randomUUID().toString());
                    rabbit.send("p2.events","account.status.changed.v1",new Message(event.body().getBytes(StandardCharsets.UTF_8),properties),correlation);
                    var confirm=correlation.getFuture().get(3,TimeUnit.SECONDS);
                    if(!confirm.isAck()||correlation.getReturned()!=null) throw new IllegalStateException("NOT_CONFIRMED");
                    jdbc.sql("UPDATE identity.account_status_outbox SET published_at_utc=CURRENT_TIMESTAMP,attempts=attempts+1 WHERE event_id=:id").param("id",event.id()).update();
                } catch(Exception ex) {
                    if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
                    jdbc.sql("UPDATE identity.account_status_outbox SET attempts=attempts+1,next_attempt_at_utc=CURRENT_TIMESTAMP+INTERVAL '30 seconds' WHERE event_id=:id").param("id",event.id()).update();
                    LoggerFactory.getLogger(getClass()).warn("event=account_status_publish_pending eventId={}",event.id());
                }
                return true;
            });
            if(!Boolean.TRUE.equals(found)) break;
        }
    }
    private record Pending(UUID id,String body) { }
}
