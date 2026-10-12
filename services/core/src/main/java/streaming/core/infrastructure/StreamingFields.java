package streaming.core.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Strict primitive decoding for neutral contracts; never coerce absent/text numbers to zero. */
final class StreamingFields {
    private StreamingFields() { }
    static String text(JsonNode n,String key) {
        var v=n.get(key);
        if(v==null || !v.isTextual() || v.asText().isEmpty()) throw new IllegalArgumentException();
        return v.asText();
    }
    static String id(JsonNode n,String key) {
        String value=text(n,key);
        if(!value.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException();
        return value;
    }
    static String optionalText(JsonNode n,String key) { return nil(n,key)?null:text(n,key); }
    static boolean nil(JsonNode n,String key) {
        if(!n.has(key)) throw new IllegalArgumentException();
        return n.get(key).isNull();
    }
    static long number(JsonNode n,String key,long min) {
        var v=n.get(key);
        if(v==null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue()<min || v.longValue()>9_007_199_254_740_991L)
            throw new IllegalArgumentException();
        return v.longValue();
    }
    static Long optionalNumber(JsonNode n,String key) { return !n.has(key) || n.get(key).isNull()?null:number(n,key,0); }
    static boolean bool(JsonNode n,String key) {
        var v=n.get(key); if(v==null || !v.isBoolean()) throw new IllegalArgumentException(); return v.booleanValue();
    }
    static String choice(JsonNode n,String key,String... allowed) {
        String value=text(n,key); if(!Set.of(allowed).contains(value)) throw new IllegalArgumentException(); return value;
    }
    static Instant instant(JsonNode n,String key) {
        var value=OffsetDateTime.parse(text(n,key));
        if(!value.getOffset().equals(ZoneOffset.UTC)) throw new IllegalArgumentException();
        return value.toInstant();
    }
    static Instant optionalInstant(JsonNode n,String key) { return !n.has(key) || n.get(key).isNull()?null:instant(n,key); }
}
