package streaming.core.watchparty.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WatchPartyRulesTest {
    @Test void theMaximumIsFourStreamsAsAgreed() {
        assertThat(WatchPartyRules.MAX_STREAMS).isEqualTo(4);
    }

    @Test void titleIsStrippedAndKeptExactlyOtherwise() {
        assertThat(WatchPartyRules.title("  Final del torneo \n")).isEqualTo("Final del torneo");
        assertThat(WatchPartyRules.title("Ñandú 😀 en vivo")).isEqualTo("Ñandú 😀 en vivo");
    }

    @Test void titleLimitCountsUnicodeCodePointsNotUtf16Units() {
        String limit="😀".repeat(100);
        assertThat(WatchPartyRules.title(limit)).isEqualTo(limit);
        assertThatThrownBy(()->WatchPartyRules.title(limit+"x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(WatchPartyRules.title("x".repeat(100))).hasSize(100);
        assertThatThrownBy(()->WatchPartyRules.title("x".repeat(101))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings={""," ","\t\n   ","bad\u0000title","line\nbreak","esc\u001bape"})
    void invalidTitlesAreRejected(String value) {
        assertThatThrownBy(()->WatchPartyRules.title(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void partyIdsHaveTheOpaquePublicShape() {
        assertThat(WatchPartyRules.isPartyId("wp_"+"0123456789abcdef".repeat(2))).isTrue();
        for(String bad:new String[]{null,"","wp_","wp_"+"a".repeat(31),"wp_"+"a".repeat(33),"WP_"+"a".repeat(32),"wp_"+"G".repeat(32),"usr_"+"a".repeat(32),"wp_"+"a".repeat(31)+"/"})
            assertThat(WatchPartyRules.isPartyId(bad)).as(String.valueOf(bad)).isFalse();
    }

    @Test void externalIdsAreOpaqueAndSafeToPutInAPath() {
        assertThat(WatchPartyRules.isExternalId("str_550e8400-e29b-41d4-a716-446655440000")).isTrue();
        assertThat(WatchPartyRules.isExternalId("a".repeat(64))).isTrue();
        for(String bad:new String[]{null,"","a".repeat(65),"../etc","a/b","a b","a?x=1","a#b","ñ"})
            assertThat(WatchPartyRules.isExternalId(bad)).as(String.valueOf(bad)).isFalse();
    }

    @Test void accessCodesAre43Base64UrlCharacters() {
        assertThat(WatchPartyRules.isAccessCode("A".repeat(43))).isTrue();
        assertThat(WatchPartyRules.isAccessCode("a-_".repeat(14)+"a")).isTrue();
        for(String bad:new String[]{null,"","A".repeat(42),"A".repeat(44),"A".repeat(42)+"=","A".repeat(42)+"+","A".repeat(42)+" "})
            assertThat(WatchPartyRules.isAccessCode(bad)).as(String.valueOf(bad)).isFalse();
    }
}
