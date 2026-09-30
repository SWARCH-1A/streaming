package streaming.channels.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import streaming.channels.application.ChannelException;

@RestControllerAdvice
public class ChannelErrorHandler {
    private static final Logger log=LoggerFactory.getLogger(ChannelErrorHandler.class);
    @ExceptionHandler(ChannelException.class)
    ResponseEntity<ErrorBody> channel(ChannelException e,HttpServletRequest request) {
        if(e.status().is5xxServerError()) log.warn("event=request_failed component=channels path={} code={}",request.getRequestURI(),e.code());
        return ResponseEntity.status(e.status()).body(new ErrorBody(e.code(),e.getMessage(),requestId(request)));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<ErrorBody> invalid(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR","La solicitud no cumple el contrato.",requestId(request)));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> tooLarge(HttpServletRequest request) {
        return ResponseEntity.status(413).body(new ErrorBody("INVALID_BANNER","La portada debe ocupar como máximo 10 MB.",requestId(request)));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> conflict(HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorBody("CHANNEL_ALREADY_EXISTS","La cuenta ya tiene un canal.",requestId(request)));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e,HttpServletRequest request) {
        log.error("event=request_failed component=channels path={} error={}",request.getRequestURI(),e.getClass().getSimpleName(),e);
        return ResponseEntity.internalServerError().body(new ErrorBody("INTERNAL_ERROR","No fue posible completar la solicitud.",requestId(request)));
    }
    private String requestId(HttpServletRequest request) { String id=request.getHeader("X-Request-Id"); return id!=null&&id.matches("[A-Za-z0-9._-]{1,80}")?id:UUID.randomUUID().toString(); }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code,String message,String requestId) { }
}
