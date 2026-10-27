package streaming.core.accounts.administration.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.accounts.identity.application.IdentityException;
import streaming.core.channels.application.ChannelAdministration;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdministrationService {
    public record Page<T>(List<T> items, String nextCursor) { }
    private final AdministrationStore store;
    private final IdentityApplicationService identity;
    private final ChannelAdministration channels;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final AdministrationCursor cursors;
    public AdministrationService(AdministrationStore store, IdentityApplicationService identity, ChannelAdministration channels,
            TransactionTemplate transactions, ObjectMapper json, AdministrationCursor cursors) {
        this.store=store; this.identity=identity; this.channels=channels; this.transactions=transactions; this.json=json;this.cursors=cursors;
    }
    public String requireAdministrator(String credential) {
        String actor=identity.introspect(credential).orElseThrow(()->error(HttpStatus.UNAUTHORIZED,"SESSION_REQUIRED")).userId();
        if (!store.isAdministrator(actor)) throw error(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED");
        return actor;
    }
    private static void page(String after,int limit) {
        if (limit<1 || limit>50 || after==null) throw error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR");
    }
    public Page<AdministrationStore.Account> accounts(String credential,String after,int limit) {
        String actor=requireAdministrator(credential); page(after,limit);
        var rows=store.accounts(cursors.decode(after,actor,"users"),limit+1);
        return new Page<>(rows.stream().limit(limit).toList(),rows.size()>limit?cursors.encode(actor,"users",rows.get(limit-1).userId()):null);
    }
    public Page<ChannelAdministration.Channel> channels(String credential,String after,int limit) {
        String actor=requireAdministrator(credential); page(after,limit);
        var rows=channels.list(cursors.decode(after,actor,"channels"),limit+1);
        return new Page<>(rows.stream().limit(limit).toList(),rows.size()>limit?cursors.encode(actor,"channels",rows.get(limit-1).channelId()):null);
    }
    public AdministrationStore.Account change(String credential,String target,String status,long expectedVersion,String reason,UUID key) {
        String actor=requireAdministrator(credential);
        if (target==null || !target.matches("[A-Za-z0-9_-]{1,64}") || !("ACTIVE".equals(status)||"SUSPENDED".equals(status))
                || expectedVersion<0 || expectedVersion>=9007199254740991L || reason==null || reason.isBlank() || reason.length()>500 || key==null)
            throw error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR");
        String normalized=reason.strip();
        return transactions.execute(tx->{
            store.lock(actor,target,key);
            requireAdministrator(credential); // recheck after serializing with suspension/provisioning
            var previous=store.operation(actor,key).orElse(null);
            if(previous!=null) {
                if(!previous.target().equals(target)||!previous.status().equals(status)||previous.expectedVersion()!=expectedVersion||!previous.reason().equals(normalized))
                    throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUSED");
                return previous.result();
            }
            var account=store.account(target).orElseThrow(()->error(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND"));
            if(account.accountVersion()!=expectedVersion) throw error(HttpStatus.CONFLICT,"ACCOUNT_VERSION_CONFLICT");
            Instant now=Instant.now();
            var result=account.status().equals(status)?account:store.change(target,status,now);
            store.record(actor,key,new AdministrationStore.Operation(target,status,expectedVersion,normalized,result),now);
            if(result.accountVersion()!=account.accountVersion()) {
                UUID eventId=UUID.randomUUID();
                String envelope=json.writeValueAsString(Map.of("eventId",eventId.toString(),"eventType","account.status.changed.v1","schemaVersion",1,
                    "producer","core","aggregateId","account:"+target,"sequence",result.accountVersion(),"occurredAtUtc",now.toString(),
                    "correlationId",key.toString(),"payload",Map.of("userId",target,"accountVersion",result.accountVersion(),"status",status)));
                store.enqueue(target,result.accountVersion(),eventId,envelope,now);
            }
            return result;
        });
    }
    private static IdentityException error(HttpStatus status,String code) {
        return new IdentityException(status,code,"No fue posible completar la operación administrativa ("+code+").");
    }
}
