package streaming.core.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import streaming.core.accounts.identity.application.IdentityException;
import streaming.core.accounts.profile.application.ProfileException;
import streaming.core.channels.application.ChannelException;
import streaming.core.security.RequestAuditFilter;
import streaming.core.taxonomy.application.TaxonomyException;

@RestControllerAdvice
public class CoreErrorHandler {
    @ExceptionHandler(IdentityException.class)
    ResponseEntity<ErrorBody> identity(IdentityException error,HttpServletRequest request) {
        HttpHeaders headers=new HttpHeaders();
        headers.setCacheControl("no-store");
        if(error.retryAfterSeconds()>0) headers.set("Retry-After",Long.toString(error.retryAfterSeconds()));
        return new ResponseEntity<>(body(error.code(),error.getMessage(),request),headers,error.status());
    }
    @ExceptionHandler(ProfileException.class)
    ResponseEntity<ErrorBody> profile(ProfileException error,HttpServletRequest request) {
        return response(error.status(),error.code(),error.getMessage(),request);
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorBody> database(HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE,"CORE_UNAVAILABLE","No fue posible completar la solicitud.",request);
    }
    @ExceptionHandler(ChannelException.class)
    ResponseEntity<ErrorBody> channel(ChannelException error,HttpServletRequest request) {
        return response(error.status(),error.code(),error.getMessage(),request);
    }
    @ExceptionHandler(TaxonomyException.class)
    ResponseEntity<ErrorBody> taxonomy(TaxonomyException error,HttpServletRequest request) {
        var body=new ErrorBody(error.code(),error.getMessage(),
                (String)request.getAttribute(RequestAuditFilter.REQUEST_ID_ATTRIBUTE),error.fieldErrors());
        return ResponseEntity.status(error.status()).header("Cache-Control","no-store").body(body);
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<ErrorBody> malformed(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","La solicitud no cumple el contrato.",request);
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> tooLarge(HttpServletRequest request) {
        String code=request.getRequestURI().startsWith("/api/channels/")?"INVALID_BANNER":"INVALID_AVATAR";
        return response(HttpStatus.PAYLOAD_TOO_LARGE,code,"La carga excede el límite permitido.",request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(HttpServletRequest request) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","No fue posible completar la solicitud.",request);
    }
    private ResponseEntity<ErrorBody> response(HttpStatus status,String code,String message,HttpServletRequest request) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(body(code,message,request));
    }
    public static ErrorBody body(String code,String message,HttpServletRequest request) {
        return new ErrorBody(code,message,(String)request.getAttribute(RequestAuditFilter.REQUEST_ID_ATTRIBUTE),Map.of());
    }
    public record ErrorBody(String code,String message,String requestId,Map<String,String> fieldErrors) { }
}
