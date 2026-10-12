package streaming.core.taxonomy.application;

import java.util.List;
import streaming.core.taxonomy.application.CatalogValues.CatalogValue;

public interface CatalogQueries {
    /** Version and active values come from one SQL snapshot, ordered by normalized name and ID. */
    CatalogSnapshot activeCatalog();

    record CatalogSnapshot(long catalogVersion,List<CatalogValue> categories,List<CatalogValue> tags) {
        public CatalogSnapshot {
            categories=List.copyOf(categories);
            tags=List.copyOf(tags);
        }
    }
}
