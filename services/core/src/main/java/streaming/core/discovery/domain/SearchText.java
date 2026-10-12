package streaming.core.discovery.domain;

import java.text.Normalizer;
import java.util.Locale;

/** Text matching rule of SPEC-07: NFKC, case-insensitive, accents preserved, substring anywhere. */
public final class SearchText {
    private SearchText() { }

    /** Normalizes a value the same way for the query and for stored text. */
    public static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
    }

    /** A blank query means "no text filter". */
    public static String queryOrNull(String query) {
        if(query==null) return null;
        String normalized=normalize(query);
        return normalized.isEmpty()?null:normalized;
    }
}
