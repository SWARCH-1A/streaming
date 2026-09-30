package streaming.channels.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChannelStore {
    Optional<Channel> find(String channelId);
    Optional<Channel> findByOwner(String ownerUserId);
    Optional<Channel> findByRegistration(String registrationId);
    Optional<Channel> lockChannel(String channelId);
    void lockKey(String key);

    Optional<Fence> findFence(String registrationId);
    void saveFence(String registrationId,String ownerUserId,Instant pendingUntil,String state,Instant now);
    Channel create(String channelId,String ownerUserId,String registrationId,Instant now,String requestId);
    boolean deleteByRegistration(String registrationId);
    Channel update(Channel before,String description,String bannerKey,String bannerUri,Instant now);

    void saveUpload(String channelId,String ownerUserId,String uploadHash,String objectKey,String contentType,Instant created,Instant expires);
    Optional<Upload> findUpload(String channelId,String ownerUserId,String uploadHash,Instant now);
    Optional<Upload> consumeUpload(String channelId,String ownerUserId,String uploadHash,Instant now);
    List<String> expiredUploadKeys(Instant now,int limit);
    void deleteExpiredUploads(Instant now);

    boolean markEventProcessed(String eventId,String eventType,Instant now);
    Optional<StreamProjection> findProjection(String channelId);
    Optional<StreamProjection> findProjectionBySession(String sessionId);
    void saveProjection(StreamProjection projection);

    record Channel(String channelId,String ownerUserId,String registrationId,String description,String bannerKey,long version,Instant createdAt,Instant updatedAt) { }
    record Fence(String registrationId,String ownerUserId,Instant pendingUntil,String state) { }
    record Upload(String channelId,String ownerUserId,String objectKey,String contentType,Instant expiresAt) { }
    record StreamProjection(String channelId,String streamId,String sessionId,long streamGeneration,long sessionVersion,String status,String availability,
            String title,String categoryId,List<String> tagIds,long metadataVersion,long viewerCount,long countVersion,Instant updatedAt) {
        public static StreamProjection offline(String channelId,Instant now) {
            return new StreamProjection(channelId,null,null,0,0,"OFFLINE","OFFLINE",null,null,List.of(),0,0,0,now);
        }
    }
}
