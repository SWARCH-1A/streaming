package streaming.core.discovery.application;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Stable, safe error of the Discovery module. {@code code} is part of the public contract. */
public class DiscoveryException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String,String> fieldErrors;

    public DiscoveryException(HttpStatus status,String code,String message) { this(status,code,message,Map.of()); }
    public DiscoveryException(HttpStatus status,String code,String message,Map<String,String> fieldErrors) {
        super(message); this.status=status; this.code=code; this.fieldErrors=Map.copyOf(fieldErrors);
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String,String> fieldErrors() { return fieldErrors; }

    public static DiscoveryException invalidFilter(String field,String reason) {
        return new DiscoveryException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_FILTER","Uno o más filtros no están disponibles.",Map.of(field,reason));
    }
    public static DiscoveryException invalidLimit() {
        return new DiscoveryException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_LIMIT","limit debe ser un entero entre 1 y 50.");
    }
    public static DiscoveryException invalidCursor() {
        return new DiscoveryException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_CURSOR","El cursor no es válido para esta consulta o expiró.");
    }
    public static DiscoveryException limitExceeded(String message) {
        return new DiscoveryException(HttpStatus.UNPROCESSABLE_ENTITY,"QUERY_LIMIT_EXCEEDED",message);
    }
}
