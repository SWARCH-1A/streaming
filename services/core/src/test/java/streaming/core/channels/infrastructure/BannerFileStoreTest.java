package streaming.core.channels.infrastructure;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import streaming.core.channels.application.ChannelException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BannerFileStoreTest {
    @TempDir Path storage;
    private BannerFileStore store;

    @BeforeEach
    void setUp() {
        store = new BannerFileStore(storage.toString());
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
        assertInvalidBanner(() -> store.saveTemporary("not an image".getBytes()));
        assertInvalidBanner(() -> store.saveTemporary(new byte[10 * 1024 * 1024 + 1]));
    }

    @Test
    void rejectsTruncatedRealImageAsInvalidInput() throws Exception {
        byte[] png=pngImage(200,200);
        assertInvalidBanner(()->store.saveTemporary(java.util.Arrays.copyOf(png,40)));
    }

    @Test
    void acceptsDimensionsDifferentFromTheRecommendedBannerSize() throws Exception {
        assertThat(store.saveTemporary(pngImage(1, 1)).width()).isEqualTo(1);
    }

    @Test
    void rejectsPathLikeObjectKeys() {
        assertThatThrownBy(() -> store.readPublic("../secret.png"))
                .isInstanceOfSatisfying(ChannelException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static void assertInvalidBanner(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ChannelException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.code()).isEqualTo("INVALID_BANNER");
                });
    }

    private static byte[] pngImage(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
