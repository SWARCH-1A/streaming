package streaming.core.channels.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Channel writes stay in this repository and participate in the Core transaction. */
public interface ChannelStore {
    Optional<Channel> find(String channelId);
    Optional<Channel> lockChannel(String channelId);
    Channel update(Channel before,String description,String bannerKey,String bannerUri,Instant now);
    void saveUpload(String channelId,String ownerUserId,String uploadHash,String objectKey,String contentType,Instant created,Instant expires);
    Optional<Upload> findUpload(String channelId,String ownerUserId,String uploadHash,Instant now);
    Optional<Upload> consumeUpload(String channelId,String ownerUserId,String uploadHash,Instant now);
    List<String> deleteExpiredUploads(Instant now,int limit);
    boolean isObjectReferenced(String objectKey,boolean published);

    record Channel(String channelId,String ownerUserId,String description,String bannerKey,long version,Instant createdAt,Instant updatedAt) { }
    record Upload(String channelId,String ownerUserId,String objectKey,String contentType,Instant expiresAt) { }
}
