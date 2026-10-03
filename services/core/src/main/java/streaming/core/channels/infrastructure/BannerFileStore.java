package streaming.core.channels.infrastructure;

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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import streaming.core.channels.application.BannerStorage;
import streaming.core.channels.application.ChannelException;

/** Banner files on a persistent volume: pending/ for single-use uploads, public/ for published immutable keys. */
@Component
public class BannerFileStore implements BannerStorage {
    private static final long MAX_BYTES=10L*1024*1024, MAX_PIXELS=40_000_000L;
    private static final String KEY_PATTERN="[0-9a-f]{32}\\.(jpg|png|gif)";
    private final Path root;
    public BannerFileStore(@Value("${channels.storage-root}") String root) { this.root=Path.of(root).toAbsolutePath().normalize(); }
    @Override public StoredBanner saveTemporary(byte[] bytes) {
        if(bytes==null || bytes.length==0 || bytes.length>MAX_BYTES) throw invalid("La portada debe ocupar como máximo 10 MB y no estar vacía.");
        ImageInfo info;
        try { info=inspect(bytes); }
        catch(IOException e) { throw invalid("El archivo no es una imagen válida."); }
        try {
            String extension=switch(info.format()) { case "jpeg","jpg"->"jpg"; case "png"->"png"; case "gif"->"gif"; default->throw invalid("Formato de portada no admitido."); };
            String key=UUID.randomUUID().toString().replace("-","")+"."+extension;
            Path directory=resolve("pending"); Files.createDirectories(directory); Files.write(directory.resolve(key),bytes);
            return new StoredBanner(key,info.contentType(),info.width(),info.height());
        } catch(IOException e) { throw new ChannelException(HttpStatus.INTERNAL_SERVER_ERROR,"BANNER_STORAGE_FAILED","No fue posible guardar la portada."); }
    }
    @Override public void publish(String key) {
        Path source=safePath("pending",key), target=safePath("public",key);
        try { Files.createDirectories(target.getParent()); Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING); }
        catch(IOException e) { throw new ChannelException(HttpStatus.INTERNAL_SERVER_ERROR,"BANNER_STORAGE_FAILED","No fue posible publicar la portada."); }
    }
    @Override public byte[] readPublic(String key) {
        if(key==null || !key.matches(KEY_PATTERN)) throw notFound();
        try { return Files.readAllBytes(safePath("public",key)); }
        catch(IOException e) { throw notFound(); }
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
            } catch(IOException e) { throw new ChannelException(HttpStatus.SERVICE_UNAVAILABLE,"BANNER_STORAGE_FAILED","No fue posible reconciliar portadas."); }
        }
        return objects;
    }
    private void delete(String area,String key) { if(key==null) return; try { Files.deleteIfExists(safePath(area,key)); } catch(IOException ignored) { } }
    private Path safePath(String area,String key) {
        if(!key.matches(KEY_PATTERN)) throw notFound();
        Path target=resolve(area).resolve(key).normalize(); if(!target.startsWith(resolve(area))) throw notFound(); return target;
    }
    private Path resolve(String child) { Path p=root.resolve(child).normalize(); if(!p.startsWith(root)) throw invalid("Ruta de portada inválida."); return p; }
    /** Decodes the real bytes: the client extension or Content-Type never proves the format. 1200×480 px is only a recommendation. */
    private static ImageInfo inspect(byte[] bytes) throws IOException {
        try(ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if(input==null) throw invalid("El archivo no es una imagen válida.");
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input); if(!readers.hasNext()) throw invalid("El archivo no es una imagen válida.");
            ImageReader reader=readers.next();
            try {
                reader.setInput(input,true,true); String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || (long)width*height>MAX_PIXELS) throw invalid("Las dimensiones de la portada no son válidas.");
                if(reader.read(0)==null) throw invalid("El archivo no es una imagen válida.");
                String type=switch(format) { case "jpeg","jpg"->"image/jpeg"; case "png"->"image/png"; case "gif"->"image/gif"; default->throw invalid("Solo se admiten imágenes JPEG, PNG o GIF."); };
                return new ImageInfo(format,type,width,height);
            } finally { reader.dispose(); }
        }
    }
    private static ChannelException invalid(String message) { return new ChannelException(HttpStatus.BAD_REQUEST,"INVALID_BANNER",message); }
    private static ChannelException notFound() { return new ChannelException(HttpStatus.NOT_FOUND,"BANNER_NOT_FOUND","Portada no encontrada."); }
    private record ImageInfo(String format,String contentType,int width,int height) { }
}
