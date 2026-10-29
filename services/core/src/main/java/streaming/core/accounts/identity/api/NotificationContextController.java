package streaming.core.accounts.identity.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.accounts.identity.application.IdentityException;
import tools.jackson.databind.JsonNode;

/**
 * Private reads for the Notifications service (service:notifications). The address is read only at
 * delivery time, so it never travels through the broker or the Notifications database.
 */
@RestController
public class NotificationContextController {
    private final IdentityApplicationService accounts;
    private final JdbcClient jdbc;

    public NotificationContextController(IdentityApplicationService accounts,JdbcClient jdbc) { this.accounts=accounts; this.jdbc=jdbc; }

    @PostMapping(path="/internal/core/notifications/session",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionPrincipal> session(@RequestHeader(name="X-Session-Credential",required=false) String credential) {
        var session=accounts.introspect(credential).orElseThrow(()->
                new IdentityException(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","Se requiere una sesión activa."));
        return ResponseEntity.ok().header("Cache-Control","no-store").body(new SessionPrincipal(session.userId(),session.expiresAt()));
    }

    @PostMapping(path="/internal/core/notifications/email-target",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EmailTarget> emailTarget(@RequestBody JsonNode body) {
        if(body==null || !body.isObject() || body.size()!=1 || !body.has("userId") || !body.get("userId").isTextual()
                || !body.get("userId").textValue().matches("[A-Za-z0-9_-]{1,64}"))
            throw new IdentityException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","La solicitud no cumple el contrato.");
        String userId=body.get("userId").textValue();
        String email=jdbc.sql("SELECT email FROM identity.accounts WHERE user_id=:id").param("id",userId).query(String.class).optional()
                .orElseThrow(()->new IdentityException(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND","Cuenta no encontrada."));
        return ResponseEntity.ok().header("Cache-Control","no-store").body(new EmailTarget(userId,email));
    }

    public record SessionPrincipal(String userId,java.time.Instant expiresAtUtc) { }
    public record EmailTarget(String userId,String email) {
        @Override public String toString() { return "EmailTarget["+userId+"]"; }
    }
}
