package streaming.core.accounts.profile.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProfileStore {
    Optional<Profile> find(String userId);
    void lockUser(String userId);
    Optional<Upload> findUpload(String userId,String uploadHash,Instant now);
    Optional<Upload> consumeUpload(String userId,String uploadHash,Instant now);
    void saveUpload(String userId,String uploadHash,String objectKey,String contentType,Instant created,Instant expires);
    Profile update(String userId,String displayName,String bio,String avatarKey,Instant now);
    List<String> deleteExpiredUploads(Instant now,int limit);
    boolean isObjectReferenced(String objectKey,boolean published);
    record Profile(String userId,String displayName,String bio,String avatarKey,long version,Instant createdAt,Instant updatedAt) { }
    record Upload(String userId,String objectKey,String contentType,Instant expiresAt) { }
}
