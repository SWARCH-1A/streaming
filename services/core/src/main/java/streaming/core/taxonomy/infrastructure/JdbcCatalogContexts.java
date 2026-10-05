package streaming.core.taxonomy.infrastructure;

import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.core.taxonomy.application.CatalogContexts;
import streaming.core.taxonomy.application.CatalogValues.ValueType;

@Repository
public class JdbcCatalogContexts implements CatalogContexts {
    private final JdbcClient jdbc;
    public JdbcCatalogContexts(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Override public Snapshot resolve(List<String> ids) {
        // The singleton is returned even when no IDs match; version and labels cannot tear.
        var rows=jdbc.sql("""
                SELECT s.catalog_version,v.id,v.kind,v.name,v.active
                FROM taxonomy.catalog_state s LEFT JOIN (
                    SELECT id,'CATEGORY' AS kind,name,active FROM taxonomy.public_categories WHERE id IN (:ids)
                    UNION ALL
                    SELECT id,'TAG' AS kind,name,active FROM taxonomy.public_tags WHERE id IN (:ids)
                ) v ON TRUE WHERE s.singleton=TRUE
                """).param("ids",ids.isEmpty()?List.of(""):ids)
                .query((rs,n)->new Row(rs.getLong("catalog_version"),rs.getString("id")==null?null:
                        new Value(rs.getString("id"),ValueType.valueOf(rs.getString("kind")),rs.getString("name"),rs.getBoolean("active"))))
                .list();
        if(rows.isEmpty()) throw new DataRetrievalFailureException("Catalogue version is missing");
        var values=new ArrayList<Value>();
        for(String id:ids.stream().distinct().toList()) {
            var matches=rows.stream().map(Row::value).filter(v->v!=null && v.id().equals(id)).toList();
            if(matches.size()>1) throw new DataRetrievalFailureException("Ambiguous catalogue identifier");
            values.addAll(matches);
        }
        return new Snapshot(rows.getFirst().version(),values);
    }
    private record Row(long version,Value value) { }
}
