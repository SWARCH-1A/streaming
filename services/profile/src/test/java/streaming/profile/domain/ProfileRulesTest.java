package streaming.profile.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileRulesTest {
    @Test
    void displayNameAcceptsOneToFiftyUnicodeCodePointsAndPreservesValue() {
        assertThat(ProfileRules.displayName("😀".repeat(50))).hasSize(100);
        assertThat(ProfileRules.displayName("  Name  ")).isEqualTo("  Name  ");
    }

    @Test
    void displayNameRejectsNullEmptyAndMoreThanFiftyCodePoints() {
        for (String value : new String[] {null, "", "😀".repeat(51)}) {
            assertThatThrownBy(() -> ProfileRules.displayName(value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void bioAllowsNullAsEmptyAndUpToThreeHundredCodePoints() {
        assertThat(ProfileRules.bio(null)).isEmpty();
        assertThat(ProfileRules.bio("😀".repeat(300))).hasSize(600);
    }

    @Test
    void bioRejectsMoreThanThreeHundredCodePointsWithoutTruncating() {
        assertThatThrownBy(() -> ProfileRules.bio("😀".repeat(301)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
