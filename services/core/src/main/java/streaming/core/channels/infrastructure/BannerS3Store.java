package streaming.core.channels.infrastructure;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import streaming.core.channels.application.BannerStorage;
import streaming.core.channels.application.ChannelException;
import streaming.core.storage.S3ImageObjectStore;

@Component
@ConditionalOnProperty(prefix="core.images",name="storage-provider",havingValue="s3")
public class BannerS3Store implements BannerStorage {
    private static final String KEY_PATTERN="[0-9a-f]{32}\\.(jpg|png|gif)";
    private final S3ImageObjectStore objects;

    public BannerS3Store(S3ImageObjectStore objects) { this.objects=objects; }

    @Override public StoredBanner saveTemporary(byte[] bytes) {
        BannerFileStore.ImageInfo info;
        try { info=BannerFileStore.inspect(bytes); }
        catch(IOException e) { throw BannerFileStore.invalid("El archivo no es una imagen válida."); }
        String extension=switch(info.format()) { case "jpeg","jpg"->"jpg"; case "png"->"png"; case "gif"->"gif"; default->throw BannerFileStore.invalid("Formato de portada no admitido."); };
        String key=UUID.randomUUID().toString().replace("-","")+"."+extension;
        try {
            objects.putTemporary("banners",key,bytes,info.contentType());
            return new StoredBanner(key,info.contentType(),info.width(),info.height());
        } catch(RuntimeException e) { throw storageFailure(); }
    }

    @Override public void publish(String key) {
        String safe=safeKey(key);
        try { objects.publish("banners",safe); }
        catch(RuntimeException e) { throw storageFailure(); }
    }

    @Override public byte[] readPublic(String key) {
        String safe=safeKey(key);
        try { return objects.readPublic("banners",safe); }
        catch(S3ImageObjectStore.ObjectMissingException e) { throw BannerFileStore.notFound(); }
        catch(RuntimeException e) { throw storageFailure(); }
    }

    @Override public String contentType(String key) {
        safeKey(key);
        return key.endsWith(".png")?"image/png":key.endsWith(".gif")?"image/gif":"image/jpeg";
    }

    @Override public void deletePublic(String key) { delete(key,true); }
    @Override public void deleteTemporary(String key) { delete(key,false); }

    @Override public List<StoredObject> objectsOlderThan(Instant cutoff) {
        try { return objects.objectsOlderThan("banners",cutoff).stream().map(x->new StoredObject(x.key(),x.published())).toList(); }
        catch(RuntimeException e) { throw new ChannelException(HttpStatus.SERVICE_UNAVAILABLE,"BANNER_STORAGE_FAILED","No fue posible reconciliar portadas."); }
    }

    private void delete(String key,boolean published) {
        if(key==null) return;
        try {
            String safe=safeKey(key);
            if(published) objects.deletePublic("banners",safe); else objects.deleteTemporary("banners",safe);
        } catch(RuntimeException ignored) { }
    }

    private static String safeKey(String key) {
        if(key==null || !key.matches(KEY_PATTERN)) throw BannerFileStore.notFound();
        return key;
    }

    private static ChannelException storageFailure() {
        return new ChannelException(HttpStatus.SERVICE_UNAVAILABLE,"BANNER_STORAGE_FAILED","No fue posible acceder al almacenamiento de portadas.");
    }
}
