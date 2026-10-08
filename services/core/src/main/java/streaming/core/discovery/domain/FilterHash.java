package streaming.core.discovery.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Binds a cursor to the exact filter that produced it. */
public final class FilterHash {
    private FilterHash() { }

    public static String of(String kind,String normalizedQuery,String categoryId,String tagId) {
        return sha256(kind+'\u0000'+nullToEmpty(normalizedQuery)+'\u0000'+nullToEmpty(categoryId)+'\u0000'+nullToEmpty(tagId));
    }
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static String nullToEmpty(String value) { return value==null?"":value; }
}
