package streaming.core.taxonomy.api;

import java.util.ArrayList;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.taxonomy.application.CatalogContexts;
import streaming.core.taxonomy.application.TaxonomyException;
import tools.jackson.databind.JsonNode;

@RestController
public class PrivateCatalogController {
    private final CatalogContexts catalog;
    public PrivateCatalogController(CatalogContexts catalog) { this.catalog=catalog; }

    @PostMapping("/internal/core/streaming/catalog-values")
    public ResponseEntity<CatalogContexts.Snapshot> resolve(@RequestBody JsonNode body) {
        JsonNode ids=body.get("ids");
        if(!body.isObject() || ids==null || !ids.isArray() || ids.isEmpty() || ids.size()>50)
            throw new TaxonomyException("ids","INVALID_BATCH");
        var requested=new ArrayList<String>();
        for(JsonNode id:ids) {
            if(!id.isTextual() || id.asText().isBlank() || id.asText().length()>64)
                throw new TaxonomyException("ids","INVALID_ID");
            requested.add(id.asText());
        }
        var unique=requested.stream().distinct().toList();
        var snapshot=catalog.resolve(unique);
        if(snapshot.values().size()!=unique.size()) throw new TaxonomyException("ids","UNKNOWN_ID");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(snapshot);
    }
}
