package streaming.core.discovery.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Stateless keyset position for channel search: the sort key of the last row returned plus the filter it belongs
 * to. {@code bestClass} is 0 exact, 1 prefix, 2 substring; {@code fieldRank} is 0 when the handle matched best.
 */
public record ChannelCursor(int bestClass,int fieldRank,String handle,String userId,String filter) {
    public String format() {
        String raw=bestClass+"|"+fieldRank+"|"+handle+"|"+userId+"|"+filter;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException when the value is not a cursor produced by {@link #format()}. */
    public static ChannelCursor parse(String value) {
        if(value==null || value.length()>400 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("cursor");
        String raw;
        try { raw=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8); }
        catch(IllegalArgumentException e) { throw new IllegalArgumentException("cursor"); }
        String[] parts=raw.split("\\|",-1);
        if(parts.length!=5 || !parts[0].matches("[0-2]") || !parts[1].matches("[01]")
                || !parts[2].matches("[A-Za-z0-9_]{1,64}") || !parts[3].matches("[A-Za-z0-9_-]{1,64}") || !parts[4].matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("cursor");
        return new ChannelCursor(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),parts[2],parts[3],parts[4]);
    }
}
