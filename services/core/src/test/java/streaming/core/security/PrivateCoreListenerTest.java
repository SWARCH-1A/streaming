package streaming.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivateCoreListenerTest {
    static final String TOKEN="fixture_private_streaming_token_32_bytes";

    @Test void catalogCredentialRemainsOptionalForExistingStreamingClient() {
        assertThatCode(()->new PrivateCoreListener(true,8082,true,"","",TOKEN,"",""))
                .doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings={"short","contains invalid spaces even when long enough"})
    void invalidCatalogCredentialFailsBeforeListenerStarts(String catalogToken) {
        assertThatThrownBy(()->new PrivateCoreListener(true,8082,true,"","",TOKEN,catalogToken,""))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(TOKEN).hasMessageNotContaining(catalogToken);
    }
    @Test void ambiguousCredentialCannotBeBothFullAndCatalogOnly() {
        assertThatThrownBy(()->new PrivateCoreListener(true,8082,true,"","",TOKEN,TOKEN,""))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(TOKEN);
    }
    @Test void chatCredentialCannotReuseStreamingOrCatalogCredential() {
        for(String chat:new String[]{TOKEN,"short","fixture_catalog_only_service_token_32_bytes"})
            assertThatThrownBy(()->new PrivateCoreListener(true,8082,true,"","",TOKEN,"fixture_catalog_only_service_token_32_bytes",chat))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(chat);
    }
}
