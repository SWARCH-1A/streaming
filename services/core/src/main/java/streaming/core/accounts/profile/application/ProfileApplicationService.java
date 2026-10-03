package streaming.core.accounts.profile.application;

import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import streaming.core.accounts.profile.application.AccountIdentity.Principal;
import streaming.core.accounts.profile.application.AccountIdentity.PublicIdentity;
import streaming.core.accounts.profile.application.ProfileStore.Profile;
import streaming.core.accounts.profile.application.ProfileStore.Upload;
import streaming.core.accounts.profile.domain.ProfileRules;

@Service
public class ProfileApplicationService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final ProfileStore profiles; private final AccountIdentity identity; private final AvatarStorage avatars; private final String avatarPublicBase;
    public ProfileApplicationService(ProfileStore profiles,AccountIdentity identity,AvatarStorage avatars,
            @Value("${profile.avatar-public-base-url:/api/profile/avatars}") String avatarPublicBase) {
        this.profiles=profiles; this.identity=identity; this.avatars=avatars; this.avatarPublicBase=avatarPublicBase.replaceAll("/$","");
    }
    public Principal requirePrincipal(String credential) {
        return identity.introspect(credential).orElseThrow(()->new ProfileException(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","Se requiere una sesión activa."));
    }
    public ProfileView getMe(Principal principal) { return view(requiredProfile(principal.userId())); }
    public ProfileView getPublic(String userId) {
        PublicIdentity user=identity.findActiveUser(userId).orElseThrow(()->new ProfileException(HttpStatus.NOT_FOUND,"PROFILE_NOT_FOUND","Perfil no encontrado."));
        return view(requiredProfile(userId));
    }
    public UploadResponse upload(Principal principal,byte[] bytes) {
        AvatarStorage.StoredAvatar stored=avatars.saveTemporary(bytes);
        String uploadId="upl_"+newToken(); Instant now=Instant.now(),expires=now.plus(Duration.ofMinutes(15));
        try { profiles.saveUpload(principal.userId(),sha256(uploadId),stored.key(),stored.contentType(),now,expires); }
        catch(RuntimeException e) { avatars.deleteTemporary(stored.key()); throw e; }
        return new UploadResponse(uploadId,expires);
    }
    @Transactional
    public ProfileView patch(Principal principal,JsonNode body) {
        profiles.lockUser(principal.userId());
        if(body==null || !body.isObject() || body.isEmpty()) throw invalid("Se requiere al menos un campo editable.");
        Set<String> allowed=Set.of("displayName","bio","avatarUploadId");
        for(String name:body.propertyNames()) if(!allowed.contains(name)) throw invalid("El perfil solo permite editar displayName, bio y avatar.");

        Profile before=requiredProfile(principal.userId());
        String displayName=before.displayName();
        String bio=before.bio();
        String avatarKey=before.avatarKey();
        if(body.has("displayName")) {
            JsonNode value=body.get("displayName");
            if(value==null || !value.isTextual()) throw invalid("displayName debe ser texto no nulo.");
            try { displayName=ProfileRules.displayName(value.textValue()); } catch(IllegalArgumentException e) { throw invalid(e.getMessage()); }
        }
        if(body.has("bio")) {
            JsonNode value=body.get("bio");
            if(value==null || value.isNull()) bio="";
            else if(!value.isTextual()) throw invalid("bio debe ser texto o null.");
            else { try { bio=ProfileRules.bio(value.textValue()); } catch(IllegalArgumentException e) { throw invalid(e.getMessage()); } }
        }
        boolean newUpload=false; String pendingKey=null,oldKey=before.avatarKey();
        if(body.has("avatarUploadId")) {
            JsonNode value=body.get("avatarUploadId");
            if(value==null || value.isNull()) avatarKey=null;
            else if(!value.isTextual() || !value.textValue().matches("upl_[A-Za-z0-9_-]{20,80}")) throw invalid("avatarUploadId no es válido.");
            else {
                String raw=value.textValue(),hash=sha256(raw);
                Upload upload=profiles.findUpload(principal.userId(),hash,Instant.now()).orElseThrow(()->invalid("La carga expiró, ya se usó o pertenece a otro usuario."));
                pendingKey=upload.objectKey(); avatarKey=pendingKey; newUpload=true;
                try {
                    avatars.publish(pendingKey);
                    if(profiles.consumeUpload(principal.userId(),hash,Instant.now()).isEmpty()) throw invalid("La carga ya fue consumida.");
                } catch(RuntimeException e) { avatars.deletePublic(pendingKey); throw e; }
            }
        }
        Profile after;
        try { after=profiles.update(principal.userId(),displayName,bio,avatarKey,Instant.now()); }
        catch(RuntimeException e) { if(newUpload) avatars.deletePublic(avatarKey); throw e; }
        final String cleanPending=pendingKey; final String removed=oldKey; final String published=newUpload?pendingKey:null;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                if(cleanPending!=null) avatars.deleteTemporary(cleanPending);
                if(removed!=null && !removed.equals(after.avatarKey())) avatars.deletePublic(removed);
            }
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED && published!=null) avatars.deletePublic(published);
            }
        });
        return view(after);
    }
    public byte[] readAvatar(String key) { return avatars.readPublic(key); }
    public String avatarContentType(String key) { return avatars.contentType(key); }
    @Scheduled(fixedDelayString="PT5M")
    public void purgeExpiredUploads() {
        Instant now=Instant.now();
        for(String key:profiles.deleteExpiredUploads(now,500)) avatars.deleteTemporary(key);
        // A grace period protects objects being written before their SQL reference commits.
        for(var object:avatars.objectsOlderThan(now.minus(Duration.ofDays(1)))) {
            if(!profiles.isObjectReferenced(object.key(),object.published())) {
                if(object.published()) avatars.deletePublic(object.key()); else avatars.deleteTemporary(object.key());
            }
        }
    }
    private Profile requiredProfile(String userId) {
        return profiles.find(userId).orElseThrow(()->new ProfileException(HttpStatus.SERVICE_UNAVAILABLE,"CORE_UNAVAILABLE","No fue posible consultar el perfil."));
    }
    private ProfileView view(Profile profile) {
        return new ProfileView(profile.userId(),profile.displayName(),profile.bio(),profile.avatarKey()==null?null:avatarUri(profile.avatarKey()),profile.updatedAt(),profile.version());
    }
    private String avatarUri(String key) { return avatarPublicBase+"/"+key; }
    private static String newToken() { byte[] b=new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static ProfileException invalid(String message) { return new ProfileException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
    public record ProfileView(String userId,String displayName,String bio,String avatarUri,Instant updatedAtUtc,long profileVersion) { }
    public record UploadResponse(String uploadId,Instant expiresAtUtc) { }
}
