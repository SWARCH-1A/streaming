package streaming.core.accounts.identity.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityRulesTest {
    @Test
    void canonicalizesEmailByTrimmingAndLowercasing() {
        assertThat(IdentityRules.canonicalEmail("  Person+tag@Example.TEST "))
                .isEqualTo("person+tag@example.test");
    }

    @Test
    void rejectsMalformedEmail() {
        assertThatThrownBy(() -> IdentityRules.canonicalEmail("not-an-email"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handleIsAsciiCaseInsensitiveAndWithinFourToTwentyFiveCharacters() {
        assertThat(IdentityRules.canonicalHandle("Caster_01")).isEqualTo("caster_01");
        assertThat(IdentityRules.canonicalHandle("a".repeat(4))).hasSize(4);
        assertThat(IdentityRules.canonicalHandle("a".repeat(25))).hasSize(25);
    }

    @Test
    void rejectsInvalidHandleLengthAndNonAsciiCharacters() {
        for (String handle : new String[] {"abc", "a".repeat(26), "castér", "has space"}) {
            assertThatThrownBy(() -> IdentityRules.canonicalHandle(handle))
                    .as("handle: %s", handle)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void passwordLengthUsesUnicodeCodePointsAndPreservesWhitespace() {
        IdentityRules.validatePassword("  pass word  ");
        IdentityRules.validatePassword("😀".repeat(12));
        IdentityRules.validatePassword("x".repeat(128));
    }

    @Test
    void rejectsPasswordsOutsideTwelveToOneHundredTwentyEightCodePoints() {
        assertThatThrownBy(() -> IdentityRules.validatePassword("x".repeat(11)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityRules.validatePassword("😀".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
