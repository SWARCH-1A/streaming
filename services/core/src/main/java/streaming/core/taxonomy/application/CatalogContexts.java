package streaming.core.taxonomy.application;

import java.util.List;

/** One consistent SQL snapshot for private Core consumers, including tombstones. */
public interface CatalogContexts {
    Snapshot resolve(List<String> ids);

    record Value(String id,CatalogValues.ValueType kind,String name,boolean active) { }
    record Snapshot(long catalogVersion,List<Value> values) {
        public Snapshot { values=List.copyOf(values); }
    }
}
