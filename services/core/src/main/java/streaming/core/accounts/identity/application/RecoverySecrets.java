package streaming.core.accounts.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Keys and plaintext never enter logs, events, record toString(), or public DTOs. */
public final class RecoverySecrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hmacKey;

    public RecoverySecrets(String encodedKey, String hmacSecret) {
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(encodedKey); }
        catch (IllegalArgumentException error) { throw new IllegalStateException("Recovery requires a base64 AES-256 key."); }
        if (decoded.length != 32) throw new IllegalStateException("Recovery requires a base64 AES-256 key.");
        encryptionKey = new SecretKeySpec(decoded, "AES");
        Arrays.fill(decoded, (byte) 0);
        hmacKey = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException error) { throw new IllegalStateException("SHA-256 unavailable."); }
    }

    public String fingerprint(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) { throw new IllegalStateException("Recovery fingerprint unavailable."); }
    }

    public String encrypt(String plaintext, String recoveryId, String deliveryId) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, recoveryId, deliveryId);
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(sealed, 0, envelope, nonce.length, sealed.length);
            return Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException error) { throw new IllegalStateException("Recovery encryption unavailable."); }
    }

    public String decrypt(String envelope, String recoveryId, String deliveryId) {
        try {
            byte[] sealed = Base64.getDecoder().decode(envelope);
            if (sealed.length < 29) throw new GeneralSecurityException();
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(sealed, 12), recoveryId, deliveryId);
            return new String(cipher.doFinal(sealed, 12, sealed.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IllegalStateException("Recovery material could not be authenticated.");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String recoveryId, String deliveryId) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, encryptionKey, new GCMParameterSpec(128, nonce));
        cipher.updateAAD((recoveryId + "\0" + deliveryId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
