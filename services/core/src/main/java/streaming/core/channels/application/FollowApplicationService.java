package streaming.core.channels.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import streaming.core.channels.application.AccountAuthentication.Principal;
import streaming.core.channels.application.FollowStore.Follow;
import streaming.core.channels.application.FollowStore.Snapshot;
import streaming.core.channels.domain.ChannelRules;
import tools.jackson.databind.ObjectMapper;

/**
 * SPEC-03 P2. The follower always comes from the session principal, never from the request body.
 * The relation, its Idempotency-Key result and its outbox event commit in one transaction.
 */
@Service
public class FollowApplicationService {
    public static final String EVENT_TYPE="channel.follow.changed.v1";
    public static final Duration IDEMPOTENCY_RETENTION=Duration.ofHours(24);
    public static final Duration SNAPSHOT_LIFETIME=Duration.ofHours(1);
    private static final int DEFAULT_LIMIT=20, MAX_LIMIT=50;
    private final FollowStore store; private final FollowCursors cursors; private final ObjectMapper json;

    public FollowApplicationService(FollowStore store,FollowCursors cursors,ObjectMapper json) {
        this.store=store; this.cursors=cursors; this.json=json;
    }

    public FollowView state(Principal principal,String channelId) {
        requireChannel(channelId);
        return store.find(principal.userId(),channelId).map(FollowApplicationService::view).orElseGet(()->notFollowing(channelId));
    }

    /** PUT semantics: repeating it keeps one relation and does not change its version. */
    @Transactional
    public FollowView follow(Principal principal,String channelId,UUID key,String requestId) {
        return idempotent(principal.userId(),key,"FOLLOW|"+channelId,FollowView.class,()->{
            String owner=requireChannel(channelId);
            if(owner.equals(principal.userId()))
                throw new ChannelException(HttpStatus.UNPROCESSABLE_ENTITY,"CHANNEL_SELF_FOLLOW","No puedes seguir tu propio canal.");
            Instant now=now();
            var changed=store.follow(principal.userId(),channelId,now);
            changed.ifPresent(follow->enqueue(follow,requestId,now));
            return view(changed.or(()->store.find(principal.userId(),channelId)).orElseThrow());
        });
    }

    /** DELETE semantics: the row and its version survive, so late events cannot revive the follow. */
    @Transactional
    public FollowView unfollow(Principal principal,String channelId,UUID key,String requestId) {
        return idempotent(principal.userId(),key,"UNFOLLOW|"+channelId,FollowView.class,()->{
            requireChannel(channelId);
            Instant now=now();
            var changed=store.unfollow(principal.userId(),channelId,now);
            changed.ifPresent(follow->enqueue(follow,requestId,now));
            return changed.or(()->store.find(principal.userId(),channelId)).map(FollowApplicationService::view)
                    .orElseGet(()->notFollowing(channelId));
        });
    }

    public FollowPage followed(Principal principal,Integer limit,String cursor) {
        int size=limit(limit); Instant now=now();
        FollowStore.Position after=null;
        if(cursor!=null) {
            var fields=cursors.read(cursor,"F",principal.userId(),2,now);
            try { after=new FollowStore.Position(fromMicros(Long.parseLong(fields.get(0))),fields.get(1)); }
            catch(NumberFormatException e) { throw new ChannelException(HttpStatus.BAD_REQUEST,"CURSOR_INVALID","El cursor no es válido."); }
        }
        var rows=new ArrayList<>(store.followed(principal.userId(),after,size+1));
        String next=null;
        if(rows.size()>size) {
            rows.subList(size,rows.size()).clear();
            Follow last=rows.getLast();
            next=cursors.issue("F",principal.userId(),now,Long.toString(micros(last.followedAt())),last.channelId());
        }
        return new FollowPage(rows.stream().map(f->new FollowedChannel(f.channelId(),f.followedAt(),f.version())).toList(),next);
    }

    /**
     * Private read for Notifications. The first page captures one cut per channel/session; later pages
     * read only that cut, so followers changing in between are neither repeated nor mixed in.
     */
    @Transactional
    public Recipients recipients(String channelId,String sessionId,Integer limit,String cursor) {
        if(sessionId==null || !sessionId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new ChannelException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","sessionId no es válido.");
        int size=limit(limit); Instant now=now();
        String binding="notifications|"+channelId+"|"+sessionId;
        Snapshot snapshot; String afterUser=null;
        if(cursor==null) {
            requireChannel(channelId);
            snapshot=store.snapshot(channelId,sessionId).filter(s->s.expiresAt().isAfter(now))
                    .orElseGet(()->store.captureSnapshot("rsn_"+UUID.randomUUID().toString().replace("-",""),channelId,sessionId,now,now.plus(SNAPSHOT_LIFETIME)));
        } else {
            var fields=cursors.read(cursor,"R",binding,2,now);
            snapshot=store.snapshotById(fields.get(0))
                    .filter(s->s.channelId().equals(channelId) && s.sessionId().equals(sessionId) && s.expiresAt().isAfter(now))
                    .orElseThrow(()->new ChannelException(HttpStatus.GONE,"SNAPSHOT_EXPIRED","El corte de destinatarios venció; reinicia sin cursor."));
            afterUser=fields.get(1);
        }
        var users=new ArrayList<>(store.recipients(snapshot.snapshotId(),afterUser,size+1));
        String next=null;
        if(users.size()>size) {
            users.subList(size,users.size()).clear();
            next=cursors.issue("R",binding,now,snapshot.snapshotId(),users.getLast());
        }
        return new Recipients(snapshot.snapshotId(),channelId,sessionId,snapshot.capturedAt(),snapshot.expiresAt(),
                snapshot.recipientCount(),users.stream().map(Recipient::new).toList(),next);
    }

    public VodSettingsView vodSettings(Principal principal,String channelId) {
        requireOwner(principal,channelId);
        return settings(store.vodSettings(channelId));
    }

    /** Optimistic update: expectedVersion must match; an unchanged value keeps the same version. */
    @Transactional
    public VodSettingsView updateVodSettings(Principal principal,String channelId,boolean enabled,long expectedVersion,UUID key) {
        return idempotent(principal.userId(),key,"VOD_SETTINGS|"+channelId+"|"+enabled+"|"+expectedVersion,VodSettingsView.class,()->{
            requireOwner(principal,channelId);
            return settings(store.updateVodSettings(channelId,enabled,expectedVersion).orElseThrow(()->
                    new ChannelException(HttpStatus.CONFLICT,"VERSION_CONFLICT","La preferencia cambió; vuelve a leerla.")));
        });
    }

    /** Streaming reads it when a session opens and keeps its own copy for that session. */
    public VodSettingsView internalVodSettings(String channelId) {
        requireChannel(channelId);
        return settings(store.vodSettings(channelId));
    }

    @Scheduled(fixedDelayString="PT5M")
    public void purge() { store.purge(now()); }

    private <T> T idempotent(String userId,UUID key,String request,Class<T> type,Supplier<T> action) {
        if(key==null) throw new ChannelException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","Se requiere Idempotency-Key UUID.");
        String keyHash=sha256(key.toString()), fingerprint=sha256(request);
        store.lockOperation(userId,keyHash);
        var previous=store.operation(userId,keyHash,now());
        if(previous.isPresent()) {
            if(!previous.get().fingerprint().equals(fingerprint))
                throw new ChannelException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","La Idempotency-Key ya se usó con otra solicitud.");
            return json.readValue(previous.get().response(),type);
        }
        T result=action.get();
        store.rememberOperation(userId,keyHash,fingerprint,json.writeValueAsString(result),now().plus(IDEMPOTENCY_RETENTION));
        return result;
    }

    private void enqueue(Follow follow,String requestId,Instant now) {
        UUID eventId=UUID.randomUUID();
        var payload=new LinkedHashMap<String,Object>();
        payload.put("channelId",follow.channelId()); payload.put("followerUserId",follow.userId());
        payload.put("following",follow.following()); payload.put("followVersion",follow.version());
        payload.put("changedAtUtc",follow.updatedAt().toString());
        var event=new LinkedHashMap<String,Object>();
        event.put("eventId",eventId.toString()); event.put("eventType",EVENT_TYPE); event.put("schemaVersion",1);
        event.put("producer","core"); event.put("aggregateId","follow:"+follow.channelId()+":"+follow.userId());
        event.put("sequence",follow.version()); event.put("occurredAtUtc",now.toString());
        event.put("correlationId",requestId!=null && requestId.matches("[A-Za-z0-9_-]{1,128}")?requestId:eventId.toString());
        event.put("payload",payload);
        store.enqueue(eventId,follow,json.writeValueAsString(event),now);
    }

    private String requireChannel(String channelId) {
        if(!ChannelRules.isExternalId(channelId)) throw notFound();
        return store.channelOwner(channelId).orElseThrow(FollowApplicationService::notFound);
    }
    private void requireOwner(Principal principal,String channelId) {
        if(!requireChannel(channelId).equals(principal.userId()))
            throw new ChannelException(HttpStatus.FORBIDDEN,"CHANNEL_FORBIDDEN","Solo el propietario puede gestionar esta preferencia.");
    }
    private static int limit(Integer limit) {
        if(limit==null) return DEFAULT_LIMIT;
        if(limit<1 || limit>MAX_LIMIT) throw new ChannelException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","limit debe estar entre 1 y 50.");
        return limit;
    }
    // PostgreSQL keeps microseconds; truncating here makes cursors and stored rows compare exactly.
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static long micros(Instant value) { return ChronoUnit.MICROS.between(Instant.EPOCH,value); }
    private static Instant fromMicros(long value) { return Instant.EPOCH.plus(value,ChronoUnit.MICROS); }
    private static FollowView view(Follow f) { return new FollowView(f.channelId(),f.following(),f.version(),f.followedAt()); }
    private static FollowView notFollowing(String channelId) { return new FollowView(channelId,false,0,null); }
    private static VodSettingsView settings(FollowStore.VodSettings s) { return new VodSettingsView(s.channelId(),s.enabled(),s.version()); }
    private static ChannelException notFound() { return new ChannelException(HttpStatus.NOT_FOUND,"CHANNEL_NOT_FOUND","Canal no encontrado."); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }

    public record FollowView(String channelId,boolean following,long followVersion,Instant followedAtUtc) { }
    public record FollowedChannel(String channelId,Instant followedAtUtc,long followVersion) { }
    public record FollowPage(List<FollowedChannel> items,String nextCursor) { }
    public record Recipient(String userId) { }
    public record Recipients(String snapshotId,String channelId,String sessionId,Instant capturedAtUtc,Instant expiresAtUtc,
            int totalRecipients,List<Recipient> items,String nextCursor) { }
    public record VodSettingsView(String channelId,boolean enabled,long version) { }
}
