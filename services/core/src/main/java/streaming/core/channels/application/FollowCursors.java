package streaming.core.channels.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Opaque, HMAC-signed page cursors. The binding (requesting user or private consumer) is part of the
 * signature but never of the visible text, so a cursor cannot be replayed by another principal.
 */
@Component
public class FollowCursors {
    public static final Duration LIFETIME=Duration.ofHours(1);
    private static final Base64.Encoder ENCODE=Base64.getUrlEncoder().withoutPadding();
    private final byte[] key;

    public FollowCursors(@Value("${core.rate-limit-hmac-secret}") String secret) {
        // Domain-separated subkey: the shared Core secret never signs cursors directly.
        this.key=hmac(secret.getBytes(StandardCharsets.UTF_8),"channels.follow-cursor.v1");
    }

    public String issue(String kind,String binding,Instant now,String... fields) {
        String body=kind+"|"+now.getEpochSecond()+"|"+String.join("|",fields);
        return ENCODE.encodeToString(body.getBytes(StandardCharsets.UTF_8))+"."+ENCODE.encodeToString(hmac(key,binding+"|"+body));
    }

    /** Returns the fields after kind and issue time; 400 if tampered or foreign, 410 if expired. */
    public List<String> read(String cursor,String kind,String binding,int fields,Instant now) {
        String body;
        try {
            if(cursor==null || cursor.length()>512) throw invalid();
            int dot=cursor.indexOf('.');
            if(dot<1) throw invalid();
            body=new String(Base64.getUrlDecoder().decode(cursor.substring(0,dot)),StandardCharsets.UTF_8);
            byte[] supplied=Base64.getUrlDecoder().decode(cursor.substring(dot+1));
            if(!MessageDigest.isEqual(supplied,hmac(key,binding+"|"+body))) throw invalid();
        } catch(IllegalArgumentException e) { throw invalid(); }
        String[] parts=body.split("\\|",-1);
        if(parts.length!=fields+2 || !parts[0].equals(kind)) throw invalid();
        long issued;
        try { issued=Long.parseLong(parts[1]); } catch(NumberFormatException e) { throw invalid(); }
        if(Instant.ofEpochSecond(issued).plus(LIFETIME).isBefore(now))
            throw new ChannelException(HttpStatus.GONE,"CURSOR_EXPIRED","El cursor venció; reinicia la consulta.");
        return List.of(parts).subList(2,parts.length);
    }

    private static ChannelException invalid() {
        return new ChannelException(HttpStatus.BAD_REQUEST,"CURSOR_INVALID","El cursor no es válido.");
    }

    private static byte[] hmac(byte[] key,String value) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
