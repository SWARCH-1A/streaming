package streaming.core.taxonomy.domain;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogRulesTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={" ","\t\n"})
    void categoryIsRequired(String id) {
        assertInvalid(() -> CatalogRules.requiredCategoryId(id),"categoryId","REQUIRED");
    }

    @Test void opaqueCategoryIdsArePreservedForExactLookup() {
        assertThat(CatalogRules.requiredCategoryId("cat_opaque")).isEqualTo("cat_opaque");
        assertThat(CatalogRules.requiredCategoryId(" cat_opaque ")).isEqualTo(" cat_opaque ");
    }

    @Test void zeroAndFiveUniqueTagsAreAllowedButSixAreRejected() {
        assertThat(CatalogRules.uniqueTagIds(List.of())).isEmpty();
        assertThat(CatalogRules.uniqueTagIds(List.of("a","b","c","d","e")))
                .containsExactly("a","b","c","d","e");
        assertInvalid(() -> CatalogRules.uniqueTagIds(List.of("a","b","c","d","e","f")),
                "tagIds","TOO_MANY_TAGS");
    }

    @Test void duplicatesDoNotConsumeSlotsAndKeepFirstOccurrenceOrder() {
        assertThat(CatalogRules.uniqueTagIds(List.of("b","a","b","c","a","d","e","e")))
                .containsExactly("b","a","c","d","e");
    }

    @Test void nullListIsDifferentFromAnExplicitEmptySelection() {
        assertInvalid(() -> CatalogRules.uniqueTagIds(null),"tagIds","REQUIRED");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={" ","\t"})
    void invalidTagIdsAreRejectedBeforeLookup(String id) {
        assertInvalid(() -> CatalogRules.uniqueTagIds(Arrays.asList("tag_valid",id)),
                "tagIds","INVALID_ID");
    }

    @Test void catalogOrderingNormalizesCompatibilityAndCanonicalUnicodeButKeepsAccents() {
        assertThat(CatalogRules.normalizedName("Ｅducacio\u0301n")).isEqualTo("educación");
        assertThat(CatalogRules.normalizedName("IRL")).isEqualTo("irl");
        assertThat(CatalogRules.normalizedName("Música")).isNotEqualTo(CatalogRules.normalizedName("Musica"));
    }

    private static void assertInvalid(Runnable selection,String field,String reason) {
        assertThatThrownBy(selection::run).isInstanceOfSatisfying(CatalogRules.InvalidSelection.class,error -> {
            assertThat(error.field()).isEqualTo(field);
            assertThat(error.reason()).isEqualTo(reason);
        });
    }
}
