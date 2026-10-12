package streaming.core.taxonomy.application;

import java.util.List;
import java.util.Optional;

/** Local catalogue contract; no HTTP lookup or ownership of stream associations. */
public interface CatalogValues {
    enum ValueType { CATEGORY, TAG }

    record CatalogValue(String id,String name,boolean active) { }

    /** Resolves existing metadata, including inactive values retained as tombstones. */
    Optional<CatalogValue> find(ValueType type,String id);

    /**
     * Validates an explicit selection and holds a shared row lock until transaction completion.
     * This guarantee is local to Core. Streaming uses the private context snapshot instead;
     * no lock or transaction is held across the network or across databases.
     * Omitted PATCH fields must not be revalidated: their previous association is preserved.
     */
    CatalogValue requireActiveCategory(String categoryId);

    /** As above; deduplicates exact IDs before the five-tag limit, preserving input order. */
    List<CatalogValue> requireActiveTags(List<String> tagIds);
}
