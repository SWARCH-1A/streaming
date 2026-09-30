package streaming.channels.application;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import streaming.channels.application.ChannelStore.Channel;
import streaming.channels.application.ChannelStore.Fence;
import streaming.channels.domain.ChannelRules;

/**
 * Private saga with Identity: one channel per account, idempotent by registrationId and fenced by pendingUntilUtc.
 * Every operation on the same registrationId is serialized by a transaction-scoped lock, so a lookup that runs
 * after the deadline waits for any in-flight creation and its answer is terminal.
 */
@Service
public class ChannelProvisioningService {
    private static final Logger log=LoggerFactory.getLogger(ChannelProvisioningService.class);
    private final ChannelStore store;
    public ChannelProvisioningService(ChannelStore store) { this.store=store; }

    @Transactional
    public ProvisionResult provision(String ownerUserId,String registrationId,String pendingUntilUtc,String requestId) {
        if(!ChannelRules.isExternalId(ownerUserId) || !ChannelRules.isExternalId(registrationId)) throw invalid("ownerUserId y registrationId son obligatorios.");
        Instant pendingUntil;
        try { pendingUntil=Instant.parse(pendingUntilUtc); } catch(DateTimeParseException|NullPointerException e) { throw invalid("pendingUntilUtc debe ser una fecha UTC ISO-8601."); }
        store.lockKey("registration:"+registrationId);
        Optional<Channel> existing=store.findByRegistration(registrationId);
        if(existing.isPresent()) {
            if(!existing.get().ownerUserId().equals(ownerUserId)) throw new ChannelException(HttpStatus.CONFLICT,"PROVISION_CONFLICT","registrationId ya está asociado a otra cuenta.");
            log.info("event=channel_provision component=channels registrationId={} channelId={} result=REPLAYED",registrationId,existing.get().channelId());
            return new ProvisionResult(existing.get(),false);
        }
        Optional<Fence> fence=store.findFence(registrationId);
        if(fence.isPresent() && !"OPEN".equals(fence.get().state())) throw expired(registrationId);
        Instant deadline=fence.map(Fence::pendingUntil).orElse(pendingUntil), now=Instant.now();
        if(!now.isBefore(deadline)) throw expired(registrationId);
        store.lockKey("owner:"+ownerUserId);
        if(store.findByOwner(ownerUserId).isPresent()) throw new ChannelException(HttpStatus.CONFLICT,"CHANNEL_ALREADY_EXISTS","La cuenta ya tiene un canal.");
        store.saveFence(registrationId,ownerUserId,deadline,"OPEN",now);
        Channel created=store.create("chn_"+UUID.randomUUID().toString().replace("-",""),ownerUserId,registrationId,now,requestId);
        log.info("event=channel_provision component=channels registrationId={} channelId={} result=CREATED",registrationId,created.channelId());
        return new ProvisionResult(created,true);
    }

    /** Terminal lookup: once it answers ABSENT the registrationId stays fenced and no later provision can create a channel. */
    @Transactional
    public ProvisionState lookup(String registrationId) {
        if(!ChannelRules.isExternalId(registrationId)) throw invalid("registrationId no es válido.");
        store.lockKey("registration:"+registrationId);
        Optional<Channel> existing=store.findByRegistration(registrationId);
        if(existing.isPresent()) return new ProvisionState("PROVISIONED",existing.get().ownerUserId(),existing.get().channelId());
        Optional<Fence> fence=store.findFence(registrationId);
        if(fence.isEmpty() || "OPEN".equals(fence.get().state()))
            store.saveFence(registrationId,fence.map(Fence::ownerUserId).orElse(null),fence.map(Fence::pendingUntil).orElse(null),"ABSENT",Instant.now());
        log.info("event=channel_provision_lookup component=channels registrationId={} result=ABSENT",registrationId);
        return new ProvisionState("ABSENT",null,null);
    }

    @Transactional
    public void compensate(String registrationId) {
        if(!ChannelRules.isExternalId(registrationId)) throw invalid("registrationId no es válido.");
        store.lockKey("registration:"+registrationId);
        Optional<Channel> existing=store.findByRegistration(registrationId);
        store.deleteByRegistration(registrationId);
        store.saveFence(registrationId,existing.map(Channel::ownerUserId).orElse(null),null,"DELETED",Instant.now());
        log.info("event=channel_provision_compensation component=channels registrationId={} result={}",registrationId,existing.isPresent()?"DELETED":"ALREADY_ABSENT");
    }

    private static ChannelException expired(String registrationId) {
        log.info("event=channel_provision component=channels registrationId={} result=REGISTRATION_EXPIRED",registrationId);
        return new ChannelException(HttpStatus.GONE,"REGISTRATION_EXPIRED","El registro venció; no se crea el canal.");
    }
    private static ChannelException invalid(String message) { return new ChannelException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
    public record ProvisionResult(Channel channel,boolean created) { }
    public record ProvisionState(String state,String ownerUserId,String channelId) { }
}
