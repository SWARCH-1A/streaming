package streaming.core.taxonomy.application;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Safe local selection error; consumers may translate it to their public contract. */
public class TaxonomyException extends RuntimeException {
    private final Map<String,String> fieldErrors;

    public TaxonomyException(String field,String reason) {
        super("La selección de categoría o etiquetas no cumple el contrato.");
        this.fieldErrors=Map.of(field,reason);
    }

    public HttpStatus status() { return HttpStatus.UNPROCESSABLE_ENTITY; }
    public String code() { return "INVALID_TAXONOMY"; }
    public Map<String,String> fieldErrors() { return fieldErrors; }
}
