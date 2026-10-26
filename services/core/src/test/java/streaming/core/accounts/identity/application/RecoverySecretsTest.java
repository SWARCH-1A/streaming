package streaming.core.accounts.identity.application;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoverySecretsTest {
    private final RecoverySecrets secrets = new RecoverySecrets(
            Base64.getEncoder().encodeToString(new byte[32]), "fixture-only-hmac-secret-at-least-32-bytes");

    @Test void encryptionUsesFreshNoncesAndAuthenticatesTheDeliveryIdentity() {
        String plaintext = "fixture@example.test\n" + "a".repeat(43);
        String first = secrets.encrypt(plaintext, "rec_fixture", "del_fixture");
        String second = secrets.encrypt(plaintext, "rec_fixture", "del_fixture");
        assertThat(first).isNotEqualTo(second).doesNotContain("fixture@example.test");
        assertThat(secrets.decrypt(first, "rec_fixture", "del_fixture")).isEqualTo(plaintext);
        assertThatThrownBy(() -> secrets.decrypt(first, "rec_other", "del_fixture")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> secrets.decrypt(first, "rec_fixture", "del_other")).isInstanceOf(IllegalStateException.class);
        byte[] corrupted = Base64.getDecoder().decode(first);
        corrupted[corrupted.length - 1] ^= 1;
        assertThatThrownBy(() -> secrets.decrypt(Base64.getEncoder().encodeToString(corrupted), "rec_fixture", "del_fixture"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void weakOrInvalidEncryptionKeysFailWithoutEchoingTheInput() {
        assertThatThrownBy(() -> new RecoverySecrets("fixture-invalid-key", "fixture-hmac"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("fixture-invalid-key");
        assertThatThrownBy(() -> new RecoverySecrets(Base64.getEncoder().encodeToString(new byte[16]), "fixture-hmac"))
                .isInstanceOf(IllegalStateException.class);
    }
}
