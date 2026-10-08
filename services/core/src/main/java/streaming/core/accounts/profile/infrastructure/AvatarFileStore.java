package streaming.core.accounts.profile.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import streaming.core.accounts.profile.application.AvatarStorage;
import streaming.core.accounts.profile.application.ProfileException;

@Component
@ConditionalOnProperty(prefix="core.images",name="storage-provider",havingValue="filesystem")
public class AvatarFileStore implements AvatarStorage {
    static final long MAX_BYTES=10L*1024*1024, MAX_PIXELS=40_000_000L;
    static final int MIN_DIMENSION=200;
    private final Path root;
    public AvatarFileStore(@Value("${profile.storage-root}") String root) { this.root=Path.of(root).toAbsolutePath().normalize(); }
    @Override public StoredAvatar saveTemporary(byte[] bytes) {
        try {
            ImageInfo info=inspect(bytes);
            String extension=switch(info.format()) { case "jpeg","jpg"->"jpg"; case "png"->"png"; case "gif"->"gif"; default->throw invalid("Formato de avatar no admitido."); };
            String key=UUID.randomUUID().toString().replace("-","")+"."+extension;
            Path directory=resolve("pending"); Files.createDirectories(directory); Files.write(directory.resolve(key),bytes);
            return new StoredAvatar(key,info.contentType(),info.width(),info.height());
        } catch(IOException e) { throw new ProfileException(HttpStatus.INTERNAL_SERVER_ERROR,"AVATAR_STORAGE_FAILED","No fue posible guardar el avatar."); }
    }
    @Override public void publish(String key) {
        Path source=safePath("pending",key), target=safePath("public",key);
        try { Files.createDirectories(target.getParent()); Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING); }
        catch(IOException e) { throw new ProfileException(HttpStatus.INTERNAL_SERVER_ERROR,"AVATAR_STORAGE_FAILED","No fue posible publicar el avatar."); }
    }
    @Override public byte[] readPublic(String key) {
        if(key==null || !key.matches("[0-9a-f]{32}\\.(jpg|png|gif)")) throw invalid("Avatar no encontrado.");
        try { return Files.readAllBytes(safePath("public",key)); }
        catch(IOException e) { throw new ProfileException(HttpStatus.NOT_FOUND,"AVATAR_NOT_FOUND","Avatar no encontrado."); }
    }
    @Override public String contentType(String key) { return key.endsWith(".png")?"image/png":key.endsWith(".gif")?"image/gif":"image/jpeg"; }
    @Override public void deletePublic(String key) { delete("public",key); }
    @Override public void deleteTemporary(String key) { delete("pending",key); }
    @Override public java.util.List<StoredObject> objectsOlderThan(java.time.Instant cutoff) {
        var objects=new java.util.ArrayList<StoredObject>();
        for(String area:java.util.List.of("pending","public")) {
            Path directory=resolve(area);
            if(!Files.isDirectory(directory)) continue;
            try(var files=Files.list(directory)) {
                for(Path file:files.toList()) {
                    String key=file.getFileName().toString();
                    if(Files.isRegularFile(file) && key.matches("[0-9a-f]{32}\\.(jpg|png|gif)") && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        objects.add(new StoredObject(key,area.equals("public")));
                    }
                }
            } catch(IOException e) { throw new ProfileException(HttpStatus.SERVICE_UNAVAILABLE,"AVATAR_STORAGE_FAILED","No fue posible reconciliar avatares."); }
        }
        return objects;
    }
    private void delete(String area,String key) { if(key==null) return; try { Files.deleteIfExists(safePath(area,key)); } catch(IOException ignored) { } }
    private Path safePath(String area,String key) {
        if(!key.matches("[0-9a-f]{32}\\.(jpg|png|gif)")) throw invalid("Avatar no encontrado.");
        Path target=resolve(area).resolve(key).normalize(); if(!target.startsWith(resolve(area))) throw invalid("Avatar no encontrado."); return target;
    }
    private Path resolve(String child) { Path p=root.resolve(child).normalize(); if(!p.startsWith(root)) throw invalid("Ruta de avatar inválida."); return p; }
    static ImageInfo inspect(byte[] bytes) throws IOException {
        if(bytes==null || bytes.length==0 || bytes.length>MAX_BYTES) throw invalid("El avatar debe ocupar como máximo 10 MB y no estar vacío.");
        try(ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if(input==null) throw invalid("El archivo no es una imagen válida.");
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input); if(!readers.hasNext()) throw invalid("El archivo no es una imagen válida.");
            ImageReader reader=readers.next();
            try {
                reader.setInput(input,true,true); String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<MIN_DIMENSION||height<MIN_DIMENSION||(long)width*height>MAX_PIXELS) {
                    throw invalid("El avatar debe medir al menos 200×200 px.");
                }
                if(reader.read(0)==null) throw invalid("El archivo no es una imagen válida.");
                String type=switch(format) { case "jpeg","jpg"->"image/jpeg"; case "png"->"image/png"; case "gif"->"image/gif"; default->throw invalid("Solo se admiten imágenes JPEG, PNG o GIF."); };
                return new ImageInfo(format,type,width,height);
            } finally { reader.dispose(); }
        }
    }
    static ProfileException invalid(String message) { return new ProfileException(HttpStatus.BAD_REQUEST,"INVALID_AVATAR",message); }
    record ImageInfo(String format,String contentType,int width,int height) { }
}
