package streaming.core.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/** Private S3 bucket adapter. Public image URLs are served by Core, not by bucket ACLs. */
@Component
@ConditionalOnProperty(prefix="core.images",name="storage-provider",havingValue="s3")
public class S3ImageObjectStore {
    private static final String KEY_PATTERN="[0-9a-f]{32}\\.(jpg|png|gif)";
    private final S3Client client;
    private final String bucket;
    private final String avatarPrefix;
    private final String bannerPrefix;

    public S3ImageObjectStore(S3Client client,
            @Value("${core.images.s3.bucket}") String bucket,
            @Value("${core.images.s3.avatar-prefix:avatars}") String avatarPrefix,
            @Value("${core.images.s3.banner-prefix:banners}") String bannerPrefix) {
        if(bucket==null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.contains("..") || bucket.contains(".-") || bucket.contains("-.")
                || bucket.matches("\\d+(\\.\\d+){3}")) {
            throw new IllegalStateException("CORE_IMAGE_S3_BUCKET debe identificar un bucket S3.");
        }
        this.client=client;
        this.bucket=bucket;
        this.avatarPrefix=validatePrefix(avatarPrefix,"CORE_IMAGE_S3_AVATAR_PREFIX");
        this.bannerPrefix=validatePrefix(bannerPrefix,"CORE_IMAGE_S3_BANNER_PREFIX");
        if(this.avatarPrefix.equals(this.bannerPrefix)) {
            throw new IllegalStateException("Los prefijos S3 de avatares y portadas deben ser distintos.");
        }
    }

    public void putTemporary(String collection,String key,byte[] bytes,String contentType) {
        client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey(collection,"pending",key))
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    public void publish(String collection,String key) {
        String source=objectKey(collection,"pending",key);
        String destination=objectKey(collection,"public",key);
        client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket)
                .sourceKey(source)
                .destinationBucket(bucket)
                .destinationKey(destination)
                .build());
    }

    public byte[] readPublic(String collection,String key) {
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey(collection,"public",key))
                    .build()).asByteArray();
        } catch(S3Exception e) {
            String code=e.awsErrorDetails()==null?null:e.awsErrorDetails().errorCode();
            if("NoSuchKey".equals(code)) throw new ObjectMissingException();
            throw e;
        }
    }

    public void deletePublic(String collection,String key) {
        delete(collection,"public",key);
    }

    public void deleteTemporary(String collection,String key) {
        delete(collection,"pending",key);
    }

    public List<StoredObject> objectsOlderThan(String collection,Instant cutoff) {
        var objects=new ArrayList<StoredObject>();
        for(String area:List.of("pending","public")) {
            String prefix=collectionPrefix(collection)+"/"+area+"/";
            var request=ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();
            for(var page:client.listObjectsV2Paginator(request)) {
                for(S3Object object:page.contents()) {
                    if(object.lastModified()==null || !object.lastModified().isBefore(cutoff)) continue;
                    String key=object.key().substring(prefix.length());
                    if(key.matches(KEY_PATTERN)) objects.add(new StoredObject(key,area.equals("public")));
                }
            }
        }
        return List.copyOf(objects);
    }

    private void delete(String collection,String area,String key) {
        if(key==null || !key.matches(KEY_PATTERN)) return;
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(collection,area,key))
                .build());
    }

    private String objectKey(String collection,String area,String key) {
        if(!"pending".equals(area) && !"public".equals(area)) throw new IllegalArgumentException("Área de objeto inválida.");
        if(key==null || !key.matches(KEY_PATTERN)) throw new IllegalArgumentException("Clave de imagen inválida.");
        return collectionPrefix(collection)+"/"+area+"/"+key;
    }

    private String collectionPrefix(String collection) {
        return switch(collection) {
            case "avatars" -> avatarPrefix;
            case "banners" -> bannerPrefix;
            default -> throw new IllegalArgumentException("Colección de imagen inválida.");
        };
    }

    private static String validatePrefix(String prefix,String propertyName) {
        if(prefix==null || !prefix.matches("[A-Za-z0-9][A-Za-z0-9/_-]{0,127}")
                || prefix.contains("//") || prefix.contains("..") || prefix.endsWith("/")) {
            throw new IllegalStateException(propertyName+" no es un prefijo S3 válido.");
        }
        return prefix;
    }

    public record StoredObject(String key,boolean published) { }

    public static final class ObjectMissingException extends RuntimeException { }
}
