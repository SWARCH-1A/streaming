package streaming.core.watchparty.application;

import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.watchparty.application.AccountSessions.Principal;
import streaming.core.watchparty.application.ChannelDirectory.ChannelCard;
import streaming.core.watchparty.application.StreamDirectory.Lookup;
import streaming.core.watchparty.application.StreamDirectory.Outcome;
import streaming.core.watchparty.application.WatchPartyStore.Party;
import streaming.core.watchparty.application.WatchPartyStore.PartyStream;
import streaming.core.watchparty.domain.WatchPartyRules;

/**
 * Watch Party use cases (SPEC-14). Streaming is only called outside database transactions so a slow remote read
 * never holds the party row lock; the tope and duplicate invariants are re-checked under that lock.
 */
@Service
public class WatchPartyApplicationService {
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
    public PartyView create(Principal principal,JsonNode body) {
        String title;
        try { title=WatchPartyRules.title(requireText(body,"title")); }
        catch(IllegalArgumentException e) { throw invalid(e.getMessage()); }
        String partyId="wp_"+UUID.randomUUID().toString().replace("-",""), code=newCode();
        Instant now=now();
        Party party=Objects.requireNonNull(transactions.execute(status->parties.create(partyId,principal.userId(),title,sha256(code),now)));
        return view(party,principal,code,Map.of());
    }

    /** RF-049 and RF-051: members read the composed party; everyone else sees the same 404 as a missing party. */
    public PartyView get(Principal principal,String partyId) {
        Party party=parties.find(requirePartyId(partyId)).orElseThrow(WatchPartyApplicationService::notFound);
        authorize(principal,party,false);
        return view(party,principal,null,Map.of());
    }

    /** RF-050. Joining is idempotent; an unknown or malformed code is indistinguishable from a missing party. */
    public PartyView join(Principal principal,JsonNode body) {
        String code=requireText(body,"accessCode");
        if(!WatchPartyRules.isAccessCode(code)) throw notFound();
        Instant now=now();
        Party party=Objects.requireNonNull(transactions.execute(status-> {
            Party locked=parties.lockByCodeHash(sha256(code)).orElseThrow(WatchPartyApplicationService::notFound);
            if(locked.closed()) throw closed();
            parties.addMember(locked.partyId(),principal.userId(),now);
            return locked;
        }));
        return view(party,principal,null,Map.of());
    }

    /** RF-047. Only a PLAYABLE stream known to Streaming and to Channels is added; nothing is stored otherwise. */
    public PartyView addStream(Principal principal,String partyId,JsonNode body) {
        String streamId=requireText(body,"streamId");
        if(!WatchPartyRules.isExternalId(streamId)) throw invalid("streamId no es válido.");
        Party current=parties.find(requirePartyId(partyId)).orElseThrow(WatchPartyApplicationService::notFound);
        authorize(principal,current,true);
        if(current.closed()) throw closed();
        // Cheap pre-checks avoid a remote call that cannot succeed; the same rules are re-checked under the row lock.
        if(parties.hasStream(current.partyId(),streamId)) throw duplicate();
        if(parties.countStreams(current.partyId())>=WatchPartyRules.MAX_STREAMS) throw full();
        Lookup lookup=streams.find(streamId);
        if(lookup.outcome()==Outcome.UNAVAILABLE) throw new WatchPartyException(HttpStatus.SERVICE_UNAVAILABLE,"STREAMING_UNAVAILABLE","No fue posible consultar la transmisión.");
        if(lookup.outcome()==Outcome.NOT_FOUND) throw streamNotFound();
        if(!lookup.snapshot().playable()) throw new WatchPartyException(HttpStatus.CONFLICT,"STREAM_NOT_LIVE","La transmisión no está en vivo y reproducible.");
        ChannelCard channel=channels.byChannelId(lookup.snapshot().channelId()).orElseThrow(WatchPartyApplicationService::streamNotFound);
        Instant now=now();
        Party party=Objects.requireNonNull(transactions.execute(status-> {
            Party locked=parties.lock(current.partyId()).orElseThrow(WatchPartyApplicationService::notFound);
            if(locked.closed()) throw closed();
            if(parties.hasStream(locked.partyId(),streamId)) throw duplicate();
            if(parties.countStreams(locked.partyId())>=WatchPartyRules.MAX_STREAMS) throw full();
            parties.addStream(locked.partyId(),streamId,channel.channelId(),now);
            return parties.bump(locked.partyId(),now);
        }));
        return view(party,principal,null,Map.of(streamId,lookup));
    }

    /** RF-048. Removing a stream that is not in the party succeeds without changing it. */
    public PartyView removeStream(Principal principal,String partyId,String streamId) {
        if(!WatchPartyRules.isExternalId(streamId)) throw invalid("streamId no es válido.");
        String id=requirePartyId(partyId);
        Instant now=now();
        Party party=Objects.requireNonNull(transactions.execute(status-> {
            Party locked=parties.lock(id).orElseThrow(WatchPartyApplicationService::notFound);
            authorize(principal,locked,true);
            if(locked.closed()) throw closed();
            return parties.removeStream(id,streamId)?parties.bump(id,now):locked;
        }));
        return view(party,principal,null,Map.of());
    }

    /** Replaces the access code; members already inside keep their access. */
    public PartyView rotateAccessCode(Principal principal,String partyId) {
        String id=requirePartyId(partyId), code=newCode();
        Instant now=now();
        Party party=Objects.requireNonNull(transactions.execute(status-> {
            Party locked=parties.lock(id).orElseThrow(WatchPartyApplicationService::notFound);
            authorize(principal,locked,true);
            if(locked.closed()) throw closed();
            return parties.rotateCode(id,sha256(code),now);
        }));
        return view(party,principal,code,Map.of());
    }

    /** Closing an already closed party returns it unchanged. */
    public PartyView close(Principal principal,String partyId) {
        String id=requirePartyId(partyId);
        Instant now=now();
        Party party=Objects.requireNonNull(transactions.execute(status-> {
            Party locked=parties.lock(id).orElseThrow(WatchPartyApplicationService::notFound);
            authorize(principal,locked,true);
            return locked.closed()?locked:parties.close(id,now);
        }));
        return view(party,principal,null,Map.of());
    }

    /** Non-members get 404 (existence is not revealed); members who are not the owner get 403 on owner-only commands. */
    private void authorize(Principal principal,Party party,boolean ownerOnly) {
        boolean owner=party.ownerUserId().equals(principal.userId());
        if(!owner && !parties.isMember(party.partyId(),principal.userId())) throw notFound();
        if(ownerOnly && !owner) throw new WatchPartyException(HttpStatus.FORBIDDEN,"WATCH_PARTY_FORBIDDEN","Solo el propietario puede modificar la sesión.");
    }

    private PartyView view(Party party,Principal viewer,String accessCode,Map<String,Lookup> known) {
        List<PartyStream> items=parties.streams(party.partyId());
        Map<String,Lookup> lookups=new HashMap<>(known);
        List<String> missing=items.stream().map(PartyStream::streamId).filter(id->!lookups.containsKey(id)).toList();
        if(!missing.isEmpty()) lookups.putAll(streams.findAll(missing));
        List<PartyView.StreamEntry> entries=new ArrayList<>();
        for(PartyStream item:items) {
            PartyView.ChannelInfo channel=channels.byChannelId(item.channelId()).map(WatchPartyApplicationService::info).orElse(null);
            Lookup lookup=lookups.getOrDefault(item.streamId(),Lookup.unavailable());
            if(lookup.outcome()==Outcome.FOUND) {
                var s=lookup.snapshot();
                entries.add(new PartyView.StreamEntry(item.streamId(),item.addedAt(),s.title(),s.category(),s.status(),s.availability(),s.viewerCount(),s.statusFresh(),channel));
            } else entries.add(new PartyView.StreamEntry(item.streamId(),item.addedAt(),null,null,"UNKNOWN","UNKNOWN",null,false,channel));
        }
        PartyView.Person owner=channels.byOwner(party.ownerUserId())
                .map(c->new PartyView.Person(party.ownerUserId(),c.handle(),c.displayName(),c.avatarUri()))
                .orElse(new PartyView.Person(party.ownerUserId(),null,null,null));
        return new PartyView(party.partyId(),party.title(),party.status(),party.version(),WatchPartyRules.MAX_STREAMS,
                parties.memberCount(party.partyId()),party.ownerUserId().equals(viewer.userId()),accessCode,owner,entries,
                party.createdAt(),party.updatedAt(),party.closedAt());
    }

    private static PartyView.ChannelInfo info(ChannelCard c) {
        return new PartyView.ChannelInfo(c.channelId(),c.handle(),c.displayName(),c.avatarUri(),c.description(),c.bannerUri());
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
    private static WatchPartyException notFound() { return new WatchPartyException(HttpStatus.NOT_FOUND,"WATCH_PARTY_NOT_FOUND","Sesión de visualización no encontrada."); }
    private static WatchPartyException closed() { return new WatchPartyException(HttpStatus.CONFLICT,"WATCH_PARTY_CLOSED","La sesión de visualización está cerrada."); }
    private static WatchPartyException full() { return new WatchPartyException(HttpStatus.CONFLICT,"WATCH_PARTY_FULL","La sesión ya tiene el máximo de transmisiones."); }
    private static WatchPartyException duplicate() { return new WatchPartyException(HttpStatus.CONFLICT,"STREAM_ALREADY_IN_PARTY","La transmisión ya está en la sesión."); }
    private static WatchPartyException streamNotFound() { return new WatchPartyException(HttpStatus.NOT_FOUND,"STREAM_NOT_FOUND","Transmisión no encontrada."); }
    private static WatchPartyException invalid(String message) { return new WatchPartyException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
}
