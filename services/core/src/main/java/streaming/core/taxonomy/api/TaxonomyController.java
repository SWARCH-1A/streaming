package streaming.core.taxonomy.api;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.taxonomy.application.CatalogQueries;

@RestController
public class TaxonomyController {
    private final CatalogQueries catalog;

    public TaxonomyController(CatalogQueries catalog) { this.catalog=catalog; }

    @GetMapping(path="/api/taxonomy",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CatalogQueries.CatalogSnapshot> activeCatalog() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(catalog.activeCatalog());
    }
}
