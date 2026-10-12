package streaming.core.taxonomy.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import streaming.core.taxonomy.domain.CatalogRules;

/** Published selection contract. Validation and the returned version share one catalog snapshot. */
@Service
public class CatalogSelections {
    private final CatalogContexts catalog;
    public CatalogSelections(CatalogContexts catalog) { this.catalog=catalog; }
    public Selection validate(boolean categoryPresent,String categoryId,boolean tagsPresent,List<String> tagIds) {
        String category=null; List<String> tags=null;
        try {
            if(categoryPresent) category=CatalogRules.requiredCategoryId(categoryId);
            if(tagsPresent) tags=CatalogRules.uniqueTagIds(tagIds);
        } catch(CatalogRules.InvalidSelection error) { throw new TaxonomyException(error.field(),error.reason()); }
        var ids=new ArrayList<String>();
        if(category!=null) ids.add(category);
        if(tags!=null) ids.addAll(tags);
        var snapshot=catalog.resolve(ids);
        var selected=category==null?null:active(snapshot,category,CatalogValues.ValueType.CATEGORY,"categoryId");
        var selectedTags=tags==null?null:tags.stream().map(id->active(snapshot,id,CatalogValues.ValueType.TAG,"tagIds")).toList();
        return new Selection(snapshot.catalogVersion(),selected,selectedTags);
    }
    private static CatalogContexts.Value active(CatalogContexts.Snapshot snapshot,String id,CatalogValues.ValueType kind,String field) {
        return snapshot.values().stream().filter(v->v.id().equals(id) && v.kind()==kind && v.active()).findFirst()
                .orElseThrow(()->new TaxonomyException(field,"UNKNOWN_OR_INACTIVE"));
    }
    public record Selection(long catalogVersion,CatalogContexts.Value category,List<CatalogContexts.Value> tags) { }
}
