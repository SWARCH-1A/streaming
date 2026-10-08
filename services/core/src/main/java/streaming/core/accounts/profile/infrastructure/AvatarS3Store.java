package streaming.core.accounts.profile.infrastructure;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import streaming.core.accounts.profile.application.AvatarStorage;
import streaming.core.accounts.profile.application.ProfileException;
import streaming.core.storage.S3ImageObjectStore;

@Component
@ConditionalOnProperty(prefix="core.images",name="storage-provider",havingValue="s3")
public class AvatarS3Store implements AvatarStorage {
    private static final String KEY_PATTERN="[0-9a-f]{32}\\.(jpg|png|gif)";
    private final S3ImageObjectStore objects;

    public AvatarS3Store(S3ImageObjectStore objects) { this.objects=objects; }

    @Override public StoredAvatar saveTemporary(byte[] bytes) {
        AvatarFileStore.ImageInfo info;
        try { info=AvatarFileStore.inspect(bytes); }
        catch(IOException e) { throw AvatarFileStore.invalid("El archivo no es una imagen válida."); }
        String extension=switch(info.format()) { case "jpeg","jpg"->"jpg"; case "png"->"png"; case "gif"->"gif"; default->throw AvatarFileStore.invalid("Formato de avatar no admitido."); };
        String key=UUID.randomUUID().toString().replace("-","")+"."+extension;
        try {
            objects.putTemporary("avatars",key,bytes,info.contentType());
            return new StoredAvatar(key,info.contentType(),info.width(),info.height());
        } catch(RuntimeException e) { throw storageFailure(); }
    }

    @Override public void publish(String key) {
        String safe=safeKey(key);
        try { objects.publish("avatars",safe); }
        catch(RuntimeException e) { throw storageFailure(); }
    }

    @Override public byte[] readPublic(String key) {
        String safe=safeKey(key);
        try { return objects.readPublic("avatars",safe); }
        catch(S3ImageObjectStore.ObjectMissingException e) { throw notFound(); }
        catch(RuntimeException e) { throw storageFailure(); }
    }

    @Override public String contentType(String key) {
        safeKey(key);
        return key.endsWith(".png")?"image/png":key.endsWith(".gif")?"image/gif":"image/jpeg";
    }

    @Override public void deletePublic(String key) { delete(key,true); }
    @Override public void deleteTemporary(String key) { delete(key,false); }

    @Override public List<StoredObject> objectsOlderThan(Instant cutoff) {
        try { return objects.objectsOlderThan("avatars",cutoff).stream().map(x->new StoredObject(x.key(),x.published())).toList(); }
        catch(RuntimeException e) { throw new ProfileException(HttpStatus.SERVICE_UNAVAILABLE,"AVATAR_STORAGE_FAILED","No fue posible reconciliar avatares."); }
    }

    private void delete(String key,boolean published) {
        if(key==null) return;
        try {
            String safe=safeKey(key);
            if(published) objects.deletePublic("avatars",safe); else objects.deleteTemporary("avatars",safe);
        } catch(RuntimeException ignored) { }
    }

    private static String safeKey(String key) {
        if(key==null || !key.matches(KEY_PATTERN)) throw AvatarFileStore.invalid("Avatar no encontrado.");
        return key;
    }

    private static ProfileException notFound() {
        return new ProfileException(HttpStatus.NOT_FOUND,"AVATAR_NOT_FOUND","Avatar no encontrado.");
    }

    private static ProfileException storageFailure() {
        return new ProfileException(HttpStatus.SERVICE_UNAVAILABLE,"AVATAR_STORAGE_FAILED","No fue posible acceder al almacenamiento de avatares.");
    }
}
