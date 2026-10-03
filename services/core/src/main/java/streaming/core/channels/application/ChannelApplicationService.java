package streaming.core.channels.application;

import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import streaming.core.channels.application.ChannelStore.Channel;
import streaming.core.channels.application.ChannelStore.Upload;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.accounts.identity.application.IdentityApplicationService.SessionView;
import streaming.core.channels.application.ChannelQueries.ChannelView;
import streaming.core.channels.domain.ChannelRules;

@Service
public class ChannelApplicationService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final ChannelStore channels; private final IdentityApplicationService identity; private final BannerStorage banners; private final String bannerPublicBase;
    public ChannelApplicationService(ChannelStore channels,IdentityApplicationService identity,BannerStorage banners,
            @Value("${channels.banner-public-base-url:/api/channels/banners}") String bannerPublicBase) {
        this.channels=channels; this.identity=identity; this.banners=banners; this.bannerPublicBase=bannerPublicBase.replaceAll("/$","");
    }
    public SessionView requirePrincipal(String credential) {
        return identity.introspect(credential).orElseThrow(()->new ChannelException(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","Se requiere una sesión activa."));
    }
    public UploadResponse upload(SessionView principal,String channelId,byte[] bytes) {
        if(!ChannelRules.isExternalId(channelId)) throw notFound();
        requireOwner(principal,channels.find(channelId).orElseThrow(ChannelApplicationService::notFound));
        BannerStorage.StoredBanner stored=banners.saveTemporary(bytes);
        String uploadId="upl_"+newToken(); Instant now=Instant.now(),expires=now.plus(Duration.ofMinutes(15));
        try { channels.saveUpload(channelId,principal.userId(),sha256(uploadId),stored.key(),stored.contentType(),now,expires); }
        catch(RuntimeException e) { banners.deleteTemporary(stored.key()); throw e; }
        return new UploadResponse(uploadId,expires);
    }
    /** Partial update over the latest persisted row; the row lock serializes concurrent PATCH requests. */
    @Transactional
    public ChannelView patch(SessionView principal,String channelId,JsonNode body) {
        if(body==null || !body.isObject() || body.isEmpty()) throw invalid("Se requiere al menos un campo editable.");
        Set<String> allowed=Set.of("description","bannerUploadId");
        for(String name:body.propertyNames()) if(!allowed.contains(name)) throw invalid("El canal solo permite editar description y bannerUploadId.");
        if(!ChannelRules.isExternalId(channelId)) throw notFound();
        Channel before=channels.lockChannel(channelId).orElseThrow(ChannelApplicationService::notFound);
        requireOwner(principal,before);

        String description=before.description(), bannerKey=before.bannerKey();
        if(body.has("description")) {
            JsonNode value=body.get("description");
            if(value==null || value.isNull()) description="";
            else if(!value.isTextual()) throw invalid("description debe ser texto o null.");
            else { try { description=ChannelRules.description(value.textValue()); } catch(IllegalArgumentException e) { throw invalid(e.getMessage()); } }
        }
        String published=null, pendingKey=null;
        if(body.has("bannerUploadId")) {
            JsonNode value=body.get("bannerUploadId");
            if(value==null || value.isNull()) bannerKey=null;
            else if(!value.isTextual() || !value.textValue().matches("upl_[A-Za-z0-9_-]{20,80}")) throw invalid("bannerUploadId no es válido.");
            else {
                String hash=sha256(value.textValue());
                Upload upload=channels.findUpload(channelId,principal.userId(),hash,Instant.now()).orElseThrow(()->invalid("La carga expiró, ya se usó o pertenece a otro canal."));
                pendingKey=upload.objectKey(); bannerKey=pendingKey;
                try {
                    banners.publish(pendingKey); published=pendingKey;
                    if(channels.consumeUpload(channelId,principal.userId(),hash,Instant.now()).isEmpty()) throw invalid("La carga ya fue consumida.");
                } catch(RuntimeException e) { banners.deletePublic(pendingKey); throw e; }
            }
        }
        if(Objects.equals(description,before.description()) && Objects.equals(bannerKey,before.bannerKey())) return view(before);
        Channel after;
        try { after=channels.update(before,description,bannerKey,bannerUri(bannerKey),Instant.now()); }
        catch(RuntimeException e) { if(published!=null) banners.deletePublic(published); throw e; }
        final String cleanPending=pendingKey, removed=before.bannerKey(), newlyPublished=published;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                if(cleanPending!=null) banners.deleteTemporary(cleanPending);
                if(removed!=null && !removed.equals(after.bannerKey())) banners.deletePublic(removed);
            }
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED && newlyPublished!=null) banners.deletePublic(newlyPublished);
            }
        });
        return view(after);
    }
    public byte[] readBanner(String key) { return banners.readPublic(key); }
    public String bannerContentType(String key) { return banners.contentType(key); }
    @Scheduled(fixedDelayString="PT5M")
    public void purgeExpiredUploads() {
        Instant now=Instant.now();
        for(String key:channels.deleteExpiredUploads(now,500)) banners.deleteTemporary(key);
        // Protect files whose SQL reference is still being committed.
        for(var object:banners.objectsOlderThan(now.minus(Duration.ofDays(1)))) {
            if(!channels.isObjectReferenced(object.key(),object.published())) {
                if(object.published()) banners.deletePublic(object.key()); else banners.deleteTemporary(object.key());
            }
        }
    }
    private static void requireOwner(SessionView principal,Channel channel) {
        if(!channel.ownerUserId().equals(principal.userId())) throw new ChannelException(HttpStatus.FORBIDDEN,"CHANNEL_FORBIDDEN","Solo el propietario puede modificar el canal.");
    }
    private ChannelView view(Channel c) { return new ChannelView(c.channelId(),c.ownerUserId(),c.description(),bannerUri(c.bannerKey()),c.version()); }
    private String bannerUri(String key) { return key==null?null:bannerPublicBase+"/"+key; }
    private static String newToken() { byte[] b=new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static ChannelException notFound() { return new ChannelException(HttpStatus.NOT_FOUND,"CHANNEL_NOT_FOUND","Canal no encontrado."); }
    private static ChannelException invalid(String message) { return new ChannelException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
    public record UploadResponse(String uploadId,Instant expiresAtUtc) { }
}
