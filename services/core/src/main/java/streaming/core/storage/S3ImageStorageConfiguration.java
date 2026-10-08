package streaming.core.storage;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@ConditionalOnProperty(prefix="core.images",name="storage-provider",havingValue="s3")
public class S3ImageStorageConfiguration {
    @Bean(destroyMethod="close")
    S3Client coreImageS3Client(
            @Value("${core.images.s3.region}") String region,
            @Value("${core.images.s3.endpoint:}") String endpoint,
            @Value("${core.images.s3.path-style-access:false}") boolean pathStyleAccess) {
        var builder=S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .forcePathStyle(pathStyleAccess);
        if(endpoint!=null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }
}
