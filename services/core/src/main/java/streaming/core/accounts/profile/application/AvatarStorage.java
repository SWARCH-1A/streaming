package streaming.core.accounts.profile.application;

public interface AvatarStorage {
    StoredAvatar saveTemporary(byte[] bytes);
    void publish(String objectKey);
    byte[] readPublic(String objectKey);
    String contentType(String objectKey);
    void deletePublic(String objectKey);
    void deleteTemporary(String objectKey);
    java.util.List<StoredObject> objectsOlderThan(java.time.Instant cutoff);
    record StoredObject(String key,boolean published) { }
    record StoredAvatar(String key,String contentType,int width,int height) { }
}
