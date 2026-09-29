package streaming.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import streaming.identity.application.IdentityException;

@RestControllerAdvice
public class IdentityErrorHandler {
    @ExceptionHandler(IdentityException.class)
    ResponseEntity<ErrorBody> identity(IdentityException e,HttpServletRequest request) {
        HttpHeaders headers=new HttpHeaders();
        if(e.retryAfterSeconds()>0) headers.set("Retry-After",Long.toString(e.retryAfterSeconds()));
        return new ResponseEntity<>(new ErrorBody(e.code(),e.getMessage(),requestId(request),Map.of()),headers,e.status());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalid(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR","La solicitud no cumple el contrato.",requestId(request),Map.of()));
    }
    @ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<ErrorBody> malformed(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR","La solicitud no cumple el contrato.",requestId(request),Map.of()));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(HttpServletRequest request) {
        return ResponseEntity.internalServerError().body(new ErrorBody("INTERNAL_ERROR","No fue posible completar la solicitud.",requestId(request),Map.of()));
    }
    private String requestId(HttpServletRequest request) {
        String incoming=request.getHeader("X-Request-Id"); return incoming!=null && incoming.matches("[A-Za-z0-9._-]{1,80}")?incoming:UUID.randomUUID().toString();
    }
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ErrorBody(String code,String message,String requestId,Map<String,String> fieldErrors) { }
}
