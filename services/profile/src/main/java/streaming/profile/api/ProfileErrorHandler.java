package streaming.profile.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import streaming.profile.application.ProfileException;

@RestControllerAdvice
public class ProfileErrorHandler {
    @ExceptionHandler(ProfileException.class)
    ResponseEntity<ErrorBody> profile(ProfileException e,HttpServletRequest request) {
        return ResponseEntity.status(e.status()).body(new ErrorBody(e.code(),e.getMessage(),requestId(request)));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<ErrorBody> invalid(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR","La solicitud no cumple el contrato.",requestId(request)));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> tooLarge(HttpServletRequest request) {
        return ResponseEntity.status(413).body(new ErrorBody("INVALID_AVATAR","El avatar debe ocupar como máximo 10 MB.",requestId(request)));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(HttpServletRequest request) {
        return ResponseEntity.internalServerError().body(new ErrorBody("INTERNAL_ERROR","No fue posible completar la solicitud.",requestId(request)));
    }
    private String requestId(HttpServletRequest request) { String id=request.getHeader("X-Request-Id"); return id!=null&&id.matches("[A-Za-z0-9._-]{1,80}")?id:UUID.randomUUID().toString(); }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code,String message,String requestId) { }
}
