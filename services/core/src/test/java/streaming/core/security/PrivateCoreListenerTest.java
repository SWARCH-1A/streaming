package streaming.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivateCoreListenerTest {
    static final String TOKEN="fixture_private_streaming_token_32_bytes";

    @Test void catalogCredentialRemainsOptionalForExistingStreamingClient() {
        assertThatCode(()->new PrivateCoreListener(true,8082,true,"","",TOKEN,""))
                .doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings={"short","contains invalid spaces even when long enough"})
    void invalidCatalogCredentialFailsBeforeListenerStarts(String catalogToken) {
        assertThatThrownBy(()->new PrivateCoreListener(true,8082,true,"","",TOKEN,catalogToken))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(TOKEN).hasMessageNotContaining(catalogToken);
    }
    @Test void ambiguousCredentialCannotBeBothFullAndCatalogOnly() {
        assertThatThrownBy(()->new PrivateCoreListener(true,8082,true,"","",TOKEN,TOKEN))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(TOKEN);
    }
}
