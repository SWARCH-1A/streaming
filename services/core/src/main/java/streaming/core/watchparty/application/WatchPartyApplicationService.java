package streaming.core.watchparty.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.watchparty.application.AccountSessions.Principal;
import streaming.core.watchparty.application.ChannelDirectory.ChannelCard;
import streaming.core.watchparty.application.PartyView.SourceState;
import streaming.core.watchparty.application.StreamDirectory.Lookup;
import streaming.core.watchparty.application.StreamDirectory.Outcome;
import streaming.core.watchparty.application.WatchPartyStore.Party;
import streaming.core.watchparty.application.WatchPartyStore.PartyStream;
import streaming.core.watchparty.domain.WatchPartyRules;
import tools.jackson.databind.JsonNode;

/**
 * Watch party use cases (SPEC-21, ADR-025). Streaming is only called outside database transactions so a slow remote read
 * never holds the party row lock; the limit and duplicate invariants are re-checked under that lock. Every command
 * carries an Idempotency-Key whose outcome is recorded in the same transaction as the change, so a repeated or
 * interrupted request neither duplicates nor loses it. The access code is never recorded: a replay returns the room
 * without it and the owner rotates the code if it was lost.
 */
@Service
public class WatchPartyApplicationService {
    public static final Duration IDEMPOTENCY_RETENTION=Duration.ofHours(24);
    private static final SecureRandom RANDOM=new SecureRandom();
    private final WatchPartyStore parties; private final AccountSessions accounts; private final StreamDirectory streams;
    private final ChannelDirectory channels; private final TransactionTemplate transactions;

    public WatchPartyApplicationService(WatchPartyStore parties,AccountSessions accounts,StreamDirectory streams,
            ChannelDirectory channels,TransactionTemplate transactions) {
        this.parties=parties; this.accounts=accounts; this.streams=streams; this.channels=channels; this.transactions=transactions;
    }

    public Principal requirePrincipal(String credential) {
        return accounts.introspect(credential).orElseThrow(()->new WatchPartyException(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","Se requiere una sesión activa."));
    }

    /** RF-046. The access code is returned once and only its hash is stored. */
    public PartyView create(Principal principal,UUID key,JsonNode body) {
        requireKey(key);
        String title;
        try { title=WatchPartyRules.title(requireText(body,"title")); }
        catch(IllegalArgumentException e) { throw invalid(e.getMessage()); }
        String partyId="wp_"+UUID.randomUUID().toString().replace("-",""), code=newCode();
        Done done=once(principal,key,"CREATE|"+title,()->parties.create(partyId,principal.userId(),title,sha256(code),now()));
        return view(done.party(),principal,done.replayed()?null:code,Map.of());
    }

    /** RF-049 and RF-051: members read the composed room; everyone else sees the same 404 as a missing room. */
    public PartyView get(Principal principal,String partyId) {
        Party party=parties.find(requirePartyId(partyId)).orElseThrow(WatchPartyApplicationService::notFound);
        authorize(principal,party,false);
        return view(party,principal,null,Map.of());
    }

    /** RF-050. Joining is idempotent; an unknown or malformed code is indistinguishable from a missing room. */
    public PartyView join(Principal principal,UUID key,JsonNode body) {
        requireKey(key);
        String code=requireText(body,"accessCode");
        if(!WatchPartyRules.isAccessCode(code)) throw notFound();
        String codeHash=sha256(code);
        Done done=once(principal,key,"JOIN|"+codeHash,()-> {
            Party locked=parties.lockByCodeHash(codeHash).orElseThrow(WatchPartyApplicationService::notFound);
            if(locked.closed()) throw closed();
            parties.addMember(locked.partyId(),principal.userId(),now());
            return locked;
        });
        return view(done.party(),principal,null,Map.of());
    }

    /** RF-047. Only a PLAYABLE stream known to Streaming and to Channels is added; nothing is stored otherwise. */
    public PartyView addStream(Principal principal,UUID key,String partyId,JsonNode body) {
        requireKey(key);
        String streamId=requireText(body,"streamId");
        if(!WatchPartyRules.isExternalId(streamId)) throw invalid("streamId no es válido.");
        String id=requirePartyId(partyId), request="ADD|"+id+"|"+streamId;
        Party current=parties.find(id).orElseThrow(WatchPartyApplicationService::notFound);
        authorize(principal,current,true);
        Optional<Party> replay=replayed(principal,key,request);
        if(replay.isPresent()) return view(replay.get(),principal,null,Map.of());
        if(current.closed()) throw closed();
        // Cheap pre-checks avoid a remote call that cannot succeed; the same rules are re-checked under the row lock.
        if(parties.hasStream(id,streamId)) throw duplicate();
        if(parties.countStreams(id)>=WatchPartyRules.MAX_STREAMS) throw full();
        Lookup lookup=streams.find(streamId);
        if(lookup.outcome()==Outcome.UNAVAILABLE) throw new WatchPartyException(HttpStatus.SERVICE_UNAVAILABLE,"STREAMING_UNAVAILABLE","No fue posible consultar la transmisión.");
        if(lookup.outcome()==Outcome.NOT_FOUND) throw streamNotFound();
        if(!lookup.snapshot().playable()) throw new WatchPartyException(HttpStatus.CONFLICT,"STREAM_NOT_LIVE","La transmisión no está en vivo y reproducible.");
        // A suspended owner's channel is not published, so it cannot be added either.
        ChannelCard channel=channels.byChannelId(lookup.snapshot().channelId()).orElseThrow(WatchPartyApplicationService::streamNotFound);
        Done done=once(principal,key,request,()-> {
            Party locked=parties.lock(id).orElseThrow(WatchPartyApplicationService::notFound);
            if(locked.closed()) throw closed();
            if(parties.hasStream(id,streamId)) throw duplicate();
            if(parties.countStreams(id)>=WatchPartyRules.MAX_STREAMS) throw full();
            Instant now=now();
            parties.addStream(id,streamId,channel.channelId(),now);
            return parties.bump(id,now);
        });
        return view(done.party(),principal,null,done.replayed()?Map.of():Map.of(streamId,lookup));
    }

    /** RF-048. Removing a stream that is not in the room succeeds without changing it. */
    public PartyView removeStream(Principal principal,UUID key,String partyId,String streamId) {
        requireKey(key);
        if(!WatchPartyRules.isExternalId(streamId)) throw invalid("streamId no es válido.");
        String id=requirePartyId(partyId);
        Done done=once(principal,key,"REMOVE|"+id+"|"+streamId,()-> {
            Party locked=parties.lock(id).orElseThrow(WatchPartyApplicationService::notFound);
            authorize(principal,locked,true);
            if(locked.closed()) throw closed();
            return parties.removeStream(id,streamId)?parties.bump(id,now()):locked;
        });
        return view(done.party(),principal,null,Map.of());
    }

    /** Replaces the access code; members already inside keep their access. */
    public PartyView rotateAccessCode(Principal principal,UUID key,String partyId) {
        requireKey(key);
        String id=requirePartyId(partyId), code=newCode();
        Done done=once(principal,key,"ROTATE|"+id,()-> {
            Party locked=parties.lock(id).orElseThrow(WatchPartyApplicationService::notFound);
            authorize(principal,locked,true);
            if(locked.closed()) throw closed();
            return parties.rotateCode(id,sha256(code),now());
        });
        return view(done.party(),principal,done.replayed()?null:code,Map.of());
    }

    /** Closing is terminal; closing an already closed room returns it unchanged. */
    public PartyView close(Principal principal,UUID key,String partyId) {
        requireKey(key);
        String id=requirePartyId(partyId);
        Done done=once(principal,key,"CLOSE|"+id,()-> {
            Party locked=parties.lock(id).orElseThrow(WatchPartyApplicationService::notFound);
            authorize(principal,locked,true);
            return locked.closed()?locked:parties.close(id,now());
        });
        return view(done.party(),principal,null,Map.of());
    }

    @Scheduled(fixedDelayString="PT5M")
    public void purge() { parties.purgeOperations(now()); }

    private record Done(Party party,boolean replayed) { }

    /**
     * Runs {@code action} and records its outcome in one transaction. The advisory lock makes concurrent requests with
     * the same key wait for the first one and then replay it; the same key with another payload is a conflict.
     */
    private Done once(Principal principal,UUID key,String request,Supplier<Party> action) {
        String keyHash=sha256(key.toString()), fingerprint=sha256(request);
        return Objects.requireNonNull(transactions.execute(status-> {
            parties.lockOperation(principal.userId(),keyHash);
            Optional<Party> previous=previous(principal,keyHash,fingerprint);
            if(previous.isPresent()) return new Done(authorized(principal,previous.get()),true);
            Party party=action.get();
            Instant now=now();
            parties.rememberOperation(principal.userId(),keyHash,fingerprint,party.partyId(),now,now.plus(IDEMPOTENCY_RETENTION));
            return new Done(party,false);
        }));
    }

    /** Non-locking look used before a remote read, so a replay never depends on the live state of Streaming. */
    private Optional<Party> replayed(Principal principal,UUID key,String request) {
        String keyHash=sha256(key.toString()), fingerprint=sha256(request);
        return previous(principal,keyHash,fingerprint).map(party->authorized(principal,party));
    }

    private Optional<Party> previous(Principal principal,String keyHash,String fingerprint) {
        Optional<WatchPartyStore.Operation> recorded=parties.operation(principal.userId(),keyHash,now());
        if(recorded.isEmpty()) return Optional.empty();
        if(!recorded.get().fingerprint().equals(fingerprint))
            throw new WatchPartyException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","La Idempotency-Key ya se usó con otra solicitud.");
        return Optional.of(parties.find(recorded.get().partyId()).orElseThrow(WatchPartyApplicationService::notFound));
    }

    private Party authorized(Principal principal,Party party) {
        authorize(principal,party,false);
        return party;
    }

    /** Non-members get 404 (existence is not revealed); members who are not the owner get 403 on owner-only commands. */
    private void authorize(Principal principal,Party party,boolean ownerOnly) {
        boolean owner=party.ownerUserId().equals(principal.userId());
        if(!owner && !parties.isMember(party.partyId(),principal.userId())) throw notFound();
        if(ownerOnly && !owner) throw new WatchPartyException(HttpStatus.FORBIDDEN,"WATCH_PARTY_FORBIDDEN","Solo el propietario puede modificar la sala.");
    }

    /**
     * Composes the room. A closed room lists no source: nothing in it can be consumed any more. For an open room every
     * source is classified from what Streaming and Channels say right now; only a fresh, LIVE and PLAYABLE source of an
     * active channel carries its sessionId, and anything unconfirmed is never presented as live.
     */
    private PartyView view(Party party,Principal viewer,String accessCode,Map<String,Lookup> known) {
        List<PartyView.Source> sources=new ArrayList<>();
        if(!party.closed()) {
            List<PartyStream> items=parties.streams(party.partyId());
            Map<String,Lookup> lookups=new HashMap<>(known);
            List<String> missing=items.stream().map(PartyStream::streamId).filter(id->!lookups.containsKey(id)).toList();
            if(!missing.isEmpty()) lookups.putAll(streams.findAll(missing));
            for(PartyStream item:items) sources.add(source(item,lookups.getOrDefault(item.streamId(),Lookup.unavailable())));
        }
        PartyView.Owner owner=channels.byOwner(party.ownerUserId())
                .map(c->new PartyView.Owner(party.ownerUserId(),c.handle(),c.displayName(),c.avatarUri()))
                .orElse(new PartyView.Owner(party.ownerUserId(),null,null,null));
        return new PartyView(party.partyId(),party.title(),party.status(),party.version(),WatchPartyRules.MAX_STREAMS,
                parties.memberCount(party.partyId()),party.ownerUserId().equals(viewer.userId()),accessCode,owner,sources,
                party.createdAt(),party.updatedAt(),party.closedAt());
    }

    private PartyView.Source source(PartyStream item,Lookup lookup) {
        Optional<ChannelCard> card=channels.byChannelId(item.channelId());
        // An owner whose account is suspended no longer publishes the channel: the room hides the source entirely.
        if(card.isEmpty()) return new PartyView.Source(item.streamId(),null,item.addedAt(),SourceState.SUSPENDED,null,null,null,false,null);
        PartyView.Channel channel=new PartyView.Channel(card.get().channelId(),card.get().handle(),card.get().displayName(),card.get().avatarUri());
        if(lookup.outcome()==Outcome.UNAVAILABLE)
            return new PartyView.Source(item.streamId(),null,item.addedAt(),SourceState.UNKNOWN,null,null,null,false,channel);
        if(lookup.outcome()==Outcome.NOT_FOUND)
            return new PartyView.Source(item.streamId(),null,item.addedAt(),SourceState.ENDED,null,null,null,false,channel);
        var s=lookup.snapshot();
        SourceState state;
        if("ENDED".equals(s.status())) state=SourceState.ENDED;
        else if(!s.statusFresh()) state=SourceState.UNKNOWN;
        else if(s.playable()) state=SourceState.PLAYABLE;
        else if("RECONNECTING".equals(s.availability())) state=SourceState.RECONNECTING;
        else state=SourceState.OFFLINE;
        return new PartyView.Source(item.streamId(),state==SourceState.PLAYABLE?s.sessionId():null,item.addedAt(),state,s.title(),
                s.category(),s.viewerCount(),s.statusFresh(),channel);
    }

    private static void requireKey(UUID key) {
        if(key==null) throw new WatchPartyException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","Se requiere Idempotency-Key UUID.");
    }
    private static String requirePartyId(String partyId) {
        if(!WatchPartyRules.isPartyId(partyId)) throw notFound();
        return partyId;
    }
    private static String requireText(JsonNode body,String field) {
        if(body==null || !body.isObject()) throw invalid("El cuerpo debe ser un objeto JSON.");
        for(String name:body.propertyNames()) if(!name.equals(field)) throw invalid("La solicitud solo admite "+field+".");
        JsonNode value=body.get(field);
        if(value==null || !value.isTextual()) throw invalid(field+" es obligatorio y debe ser texto.");
        return value.textValue();
    }
    /** Microsecond precision matches PostgreSQL so a value read back equals the one written. */
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static String newCode() { byte[] b=new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static WatchPartyException notFound() { return new WatchPartyException(HttpStatus.NOT_FOUND,"WATCH_PARTY_NOT_FOUND","Sala de visualización no encontrada."); }
    private static WatchPartyException closed() { return new WatchPartyException(HttpStatus.CONFLICT,"WATCH_PARTY_CLOSED","La sala de visualización está cerrada."); }
    private static WatchPartyException full() { return new WatchPartyException(HttpStatus.CONFLICT,"WATCH_PARTY_FULL","La sala ya tiene el máximo de transmisiones."); }
    private static WatchPartyException duplicate() { return new WatchPartyException(HttpStatus.CONFLICT,"STREAM_ALREADY_IN_PARTY","La transmisión ya está en la sala."); }
    private static WatchPartyException streamNotFound() { return new WatchPartyException(HttpStatus.NOT_FOUND,"STREAM_NOT_FOUND","Transmisión no encontrada."); }
    private static WatchPartyException invalid(String message) { return new WatchPartyException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
}
