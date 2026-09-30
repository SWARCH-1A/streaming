package streaming.channels.application;

public interface BannerStorage {
    StoredBanner saveTemporary(byte[] bytes);
    void publish(String objectKey);
    byte[] readPublic(String objectKey);
    String contentType(String objectKey);
    void deletePublic(String objectKey);
    void deleteTemporary(String objectKey);
    record StoredBanner(String key,String contentType,int width,int height) { }
}
