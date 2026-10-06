package streaming.core.taxonomy.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import streaming.core.taxonomy.application.CatalogQueries;
import streaming.core.taxonomy.application.CatalogValues;
import streaming.core.taxonomy.application.TaxonomyException;
import streaming.core.taxonomy.domain.CatalogRules;

@Repository
public class JdbcCatalog implements CatalogValues, CatalogQueries {
    private static final Comparator<CatalogValue> PUBLIC_ORDER=Comparator
            .comparing((CatalogValue value)->CatalogRules.normalizedName(value.name()))
            .thenComparing(CatalogValue::id);
    private final JdbcClient jdbc;

    public JdbcCatalog(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Override public CatalogSnapshot activeCatalog() {
        // A single statement keeps the version and both lists on one MVCC snapshot, even while
        // a controlled data migration changes values and their version concurrently.
        var rows=jdbc.sql("""
                SELECT s.catalog_version,v.value_type,v.id,v.name,v.active
                FROM taxonomy.catalog_state s
                LEFT JOIN (
                    SELECT 'CATEGORY' AS value_type,id,name,active FROM taxonomy.public_categories WHERE active
                    UNION ALL
                    SELECT 'TAG' AS value_type,id,name,active FROM taxonomy.public_tags WHERE active
                ) v ON TRUE
                WHERE s.singleton=TRUE
                """).query((rs,n)->new CatalogRow(rs.getLong("catalog_version"),rs.getString("value_type"),
                        rs.getString("id")==null?null:value(rs,n))).list();
        if(rows.isEmpty()) throw new DataRetrievalFailureException("Catalogue version is missing");
        var categories=new ArrayList<CatalogValue>();
        var tags=new ArrayList<CatalogValue>();
        for(CatalogRow row:rows) {
            if(row.value()==null) continue;
            if(row.type().equals("CATEGORY")) categories.add(row.value());
            else tags.add(row.value());
        }
        categories.sort(PUBLIC_ORDER);
        tags.sort(PUBLIC_ORDER);
        return new CatalogSnapshot(rows.getFirst().version(),categories,tags);
    }

    @Override public Optional<CatalogValue> find(ValueType type,String id) {
        String table=table(type);
        if(id==null || id.isBlank()) return Optional.empty();
        return jdbc.sql("SELECT id,name,active FROM "+table+" WHERE id=:id")
                .param("id",id).query(JdbcCatalog::value).optional();
    }

    @Override @Transactional
    public CatalogValue requireActiveCategory(String categoryId) {
        String id;
        try { id=CatalogRules.requiredCategoryId(categoryId); }
        catch(CatalogRules.InvalidSelection error) { throw selectionError(error); }
        return jdbc.sql("SELECT id,name,active FROM taxonomy.categories WHERE id=:id FOR SHARE")
                .param("id",id).query(JdbcCatalog::value).optional().filter(CatalogValue::active)
                .orElseThrow(()->new TaxonomyException("categoryId","UNKNOWN_OR_INACTIVE"));
    }

    @Override @Transactional
    public List<CatalogValue> requireActiveTags(List<String> tagIds) {
        List<String> ids;
        try { ids=CatalogRules.uniqueTagIds(tagIds); }
        catch(CatalogRules.InvalidSelection error) { throw selectionError(error); }
        if(ids.isEmpty()) return List.of();
        // Consistent lock order prevents reversed input lists from changing lock acquisition order.
        var found=jdbc.sql("SELECT id,name,active FROM taxonomy.tags WHERE id IN (:ids) ORDER BY id FOR SHARE")
                .param("ids",ids).query(JdbcCatalog::value).list();
        var byId=new HashMap<String,CatalogValue>();
        for(CatalogValue value:found) {
            if(!value.active()) throw new TaxonomyException("tagIds","UNKNOWN_OR_INACTIVE");
            byId.put(value.id(),value);
        }
        if(byId.size()!=ids.size()) throw new TaxonomyException("tagIds","UNKNOWN_OR_INACTIVE");
        return ids.stream().map(byId::get).toList();
    }

    private static String table(ValueType type) {
        return switch(type) {
            case CATEGORY -> "taxonomy.public_categories";
            case TAG -> "taxonomy.public_tags";
        };
    }

    private static CatalogValue value(ResultSet rs,int rowNumber) throws SQLException {
        return new CatalogValue(rs.getString("id"),rs.getString("name"),rs.getBoolean("active"));
    }

    private static TaxonomyException selectionError(CatalogRules.InvalidSelection error) {
        return new TaxonomyException(error.field(),error.reason());
    }

    private record CatalogRow(long version,String type,CatalogValue value) { }
}
