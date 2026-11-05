package streaming.core.accounts.administration.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import streaming.core.accounts.identity.application.IdentityException;

@Component
public class AdministrationCursor {
    private final byte[] secret;
    public AdministrationCursor(@Value("${core.rate-limit-hmac-secret}") String secret) { this.secret=secret.getBytes(StandardCharsets.UTF_8); }
    public String encode(String actor,String kind,String after) {
        String payload=actor+"|"+kind+"|"+after+"|"+Instant.now().plusSeconds(300).getEpochSecond();
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(encoded));
    }
    public String decode(String cursor,String actor,String kind) {
        if(cursor.isEmpty()) return "";
        try {
            if(cursor.length()>512) throw new IllegalArgumentException();
            var pieces=cursor.split("\\.",-1);
            if(pieces.length!=2 || !MessageDigest.isEqual(sign(pieces[0]),Base64.getUrlDecoder().decode(pieces[1]))) throw new IllegalArgumentException();
            var fields=new String(Base64.getUrlDecoder().decode(pieces[0]),StandardCharsets.UTF_8).split("\\|",-1);
            if(fields.length!=4 || !fields[0].equals(actor) || !fields[1].equals(kind) || !fields[2].matches("[A-Za-z0-9_-]{1,64}")
                    || Long.parseLong(fields[3])<=Instant.now().getEpochSecond()) throw new IllegalArgumentException();
            return fields[2];
        } catch(IllegalArgumentException ex) { throw new IdentityException(HttpStatus.GONE,"CURSOR_INVALID","El cursor venció o no corresponde a esta consulta."); }
    }
    private byte[] sign(String value) {
        try { var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return mac.doFinal(("admin-cursor:"+value).getBytes(StandardCharsets.UTF_8)); }
        catch(java.security.GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
}
