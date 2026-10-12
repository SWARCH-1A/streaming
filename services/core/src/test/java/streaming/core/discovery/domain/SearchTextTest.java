package streaming.core.discovery.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTextTest {
    @Test void foldsCaseAndCompatibilityCharactersButKeepsAccents() {
        assertThat(SearchText.normalize("ＣＡＦÉ")).isEqualTo("café");
        assertThat(SearchText.normalize("ﬁn")).isEqualTo("fin");
        assertThat(SearchText.normalize("Ⅻ")).isEqualTo("xii");
        assertThat(SearchText.normalize("Café")).isNotEqualTo(SearchText.normalize("Cafe"));
        assertThat(SearchText.normalize("ÑANDÚ")).isEqualTo("ñandú");
    }

    @Test void decomposedAndPrecomposedAccentsMatch() {
        assertThat(SearchText.normalize("Café")).isEqualTo(SearchText.normalize("Café"));
    }

    @Test void stripsSurroundingWhitespaceIncludingOnesCreatedByNormalization() {
        assertThat(SearchText.normalize("  Hola \t")).isEqualTo("hola");
        assertThat(SearchText.normalize(" hola ")).isEqualTo("hola");
    }

    @Test void aBlankQueryMeansNoFilter() {
        assertThat(SearchText.queryOrNull(null)).isNull();
        assertThat(SearchText.queryOrNull("")).isNull();
        assertThat(SearchText.queryOrNull("     ")).isNull();
        assertThat(SearchText.queryOrNull(" Música ")).isEqualTo("música");
    }

    @Test void doesNotTransliterateOrTokenize() {
        assertThat(SearchText.normalize("Ciencia y Tecnología")).isEqualTo("ciencia y tecnología");
        assertThat(SearchText.normalize("naïve")).isEqualTo("naïve").isNotEqualTo("naive");
    }
}
