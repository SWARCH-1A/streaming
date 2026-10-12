package streaming.core.taxonomy.domain;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class CatalogRules {
    private CatalogRules() { }

    public static String requiredCategoryId(String categoryId) {
        if(categoryId==null || categoryId.isBlank()) throw new InvalidSelection("categoryId","REQUIRED");
        return categoryId;
    }

    public static List<String> uniqueTagIds(List<String> tagIds) {
        if(tagIds==null) throw new InvalidSelection("tagIds","REQUIRED");
        var unique=new LinkedHashSet<String>();
        for(String id:tagIds) {
            if(id==null || id.isBlank()) throw new InvalidSelection("tagIds","INVALID_ID");
            unique.add(id);
            if(unique.size()>5) throw new InvalidSelection("tagIds","TOO_MANY_TAGS");
        }
        return List.copyOf(unique);
    }

    public static String normalizedName(String name) {
        return Normalizer.normalize(name,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    public static final class InvalidSelection extends IllegalArgumentException {
        private final String field;
        private final String reason;

        public InvalidSelection(String field,String reason) {
            super("La selección de categoría o etiquetas no cumple el contrato.");
            this.field=field;
            this.reason=reason;
        }

        public String field() { return field; }
        public String reason() { return reason; }
    }
}
