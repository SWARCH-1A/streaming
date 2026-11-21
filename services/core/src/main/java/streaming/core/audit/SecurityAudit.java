package streaming.core.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import streaming.core.security.RequestAuditFilter;
import tools.jackson.databind.ObjectMapper;

/** A domain commit and its evidence share a transaction. Denials use an independent commit. */
@Service
public class SecurityAudit {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final boolean enabled;
    private final TransactionTemplate independent;
    public SecurityAudit(JdbcClient jdbc,ObjectMapper json,TransactionTemplate transactions,
            @Value("${core.audit.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.json=json;this.enabled=enabled;
        independent=new TransactionTemplate(transactions.getTransactionManager());
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setTimeout(3);
    }
    public void denied(String actor,String reason) {
        if(enabled) independent.executeWithoutResult(tx->record(actor,"ADMIN_ACCESS","ADMIN","administration","DENIED",reason));
    }
    public void record(String actor,String action,String targetType,String target,String outcome,String reason) {
        if(!enabled) return;
        UUID id=UUID.randomUUID();
        String requestId=id.toString();
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object value=attributes.getRequest().getAttribute(RequestAuditFilter.REQUEST_ID_ATTRIBUTE);
            if(value instanceof String s && s.matches("[A-Za-z0-9._-]{1,80}")) requestId=s;
        }
        var payload=new LinkedHashMap<String,Object>();
        payload.put("actorId",actor);payload.put("action",action);payload.put("targetType",targetType);
        payload.put("targetId",target);payload.put("outcome",outcome);payload.put("reasonCode",reason);
        String body=json.writeValueAsString(Map.of("eventId",id.toString(),"eventType","security.audit.core.v1",
            "schemaVersion",1,"producer","core","aggregateId","audit:"+id,"sequence",1,
            "occurredAtUtc",Instant.now().toString(),"correlationId",requestId,"payload",payload));
        jdbc.sql("INSERT INTO core.security_audit_outbox(event_id,envelope) VALUES(:id,:body)")
            .param("id",id).param("body",body).update();
    }
}
