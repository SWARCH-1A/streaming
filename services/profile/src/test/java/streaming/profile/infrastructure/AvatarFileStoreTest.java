package streaming.profile.infrastructure;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import streaming.profile.application.ProfileException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarFileStoreTest {
    @TempDir Path storage;
    private AvatarFileStore store;

    @BeforeEach
    void setUp() {
        store = new AvatarFileStore(storage.toString());
    }

    @Test
    void acceptsRealPngAndPublishesItUnderOpaqueKey() throws Exception {
        byte[] png = pngImage(200, 200);

        var saved = store.saveTemporary(png);

        assertThat(saved.contentType()).isEqualTo("image/png");
        assertThat(saved.key()).matches("[0-9a-f]{32}\\.png");
        store.publish(saved.key());
        assertThat(store.readPublic(saved.key())).containsExactly(png);
        assertThat(store.contentType(saved.key())).isEqualTo("image/png");
    }

    @Test
    void rejectsNonImageAndOversizedFiles() {
        assertInvalidAvatar(() -> store.saveTemporary("not an image".getBytes()));
        assertInvalidAvatar(() -> store.saveTemporary(new byte[10 * 1024 * 1024 + 1]));
    }

    @Test
    void rejectsAvatarWhenEitherDimensionIsBelowTwoHundredPixels() throws Exception {
        assertInvalidAvatar(() -> store.saveTemporary(pngImage(199, 200)));
        assertInvalidAvatar(() -> store.saveTemporary(pngImage(200, 199)));
    }

    @Test
    void rejectsPathLikeObjectKeys() {
        assertThatThrownBy(() -> store.readPublic("../secret.png"))
                .isInstanceOfSatisfying(ProfileException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static void assertInvalidAvatar(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ProfileException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.code()).isEqualTo("INVALID_AVATAR");
                });
    }

    private static byte[] pngImage(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
