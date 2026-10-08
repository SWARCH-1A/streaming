package streaming.core.discovery.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import streaming.core.api.CoreErrorHandler.ErrorBody;
import streaming.core.discovery.application.DiscoveryException;
import streaming.core.security.RequestAuditFilter;

/** Maps Discovery errors of the private inbox to the common REST envelope; scoped so Core's handler is untouched. */
@RestControllerAdvice(assignableTypes=StreamEventsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class DiscoveryErrorAdvice {
    @ExceptionHandler(DiscoveryException.class)
    ResponseEntity<ErrorBody> discovery(DiscoveryException error,HttpServletRequest request) {
        var body=new ErrorBody(error.code(),error.getMessage(),(String)request.getAttribute(RequestAuditFilter.REQUEST_ID_ATTRIBUTE),error.fieldErrors());
        return ResponseEntity.status(error.status()).header("Cache-Control","no-store").body(body);
    }
}
