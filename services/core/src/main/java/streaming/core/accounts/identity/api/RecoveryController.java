package streaming.core.accounts.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.accounts.identity.application.RecoveryApplicationService;
import streaming.core.security.RequestAuditFilter;
import streaming.core.security.TrustedProxies;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class RecoveryController {
    private final RecoveryApplicationService recovery;
    private final TrustedProxies proxies;
    private final ObjectMapper json;
    public RecoveryController(RecoveryApplicationService recovery, TrustedProxies proxies, ObjectMapper json) {
        this.recovery = recovery; this.proxies = proxies; this.json = json;
    }

    @PostMapping(path = "/api/identity/recovery-requests", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Accepted> request(@RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode body, HttpServletRequest request) {
        fields(body, "email");
        recovery.request(uuid(key), text(body, "email", 400), clientIp(request),
                (String) request.getAttribute(RequestAuditFilter.REQUEST_ID_ATTRIBUTE));
        return ResponseEntity.accepted().header("Cache-Control", "no-store")
                .body(new Accepted(RecoveryApplicationService.ACCEPTED_MESSAGE));
    }

    @PostMapping(path = "/api/identity/recoveries/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> confirm(@RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode body, HttpServletRequest request) {
        fields(body, "recoveryToken", "newPassword");
        recovery.confirm(uuid(key), text(body, "recoveryToken", 43), text(body, "newPassword", 512), clientIp(request));
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    @PostMapping(path = "/internal/core/recovery-deliveries/{recoveryId}/claim",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RecoveryApplicationService.Delivery> claim(@PathVariable String recoveryId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        fields(body, "deliveryId", "attempt");
        var delivery = recovery.claim(uuid(key), recoveryId, text(body, "deliveryId", 64), attempt(body));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(delivery);
    }

    @PutMapping(path = "/internal/core/recovery-deliveries/{recoveryId}/result", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> result(@PathVariable String recoveryId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode body) {
        fields(body, "deliveryId", "attempt", "status");
        recovery.result(uuid(key), recoveryId, text(body, "deliveryId", 64), attempt(body), text(body, "status", 24));
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    private void fields(JsonNode body, String... names) {
        if (!body.isObject() || json.writeValueAsBytes(body).length > 16_384) throw RecoveryApplicationService.invalidPayload();
        Set<String> actual = new HashSet<>();
        body.properties().forEach(entry -> actual.add(entry.getKey()));
        if (!actual.equals(Set.of(names))) throw RecoveryApplicationService.invalidPayload();
    }
    private static String text(JsonNode body, String name, int maxLength) {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual() || value.asText().isEmpty() || value.asText().length() > maxLength)
            throw RecoveryApplicationService.invalidPayload();
        return value.asText();
    }
    private static int attempt(JsonNode body) {
        JsonNode value = body.get("attempt");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw RecoveryApplicationService.invalidPayload();
        return value.intValue();
    }
    private static UUID uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException error) { throw RecoveryApplicationService.invalidPayload(); }
    }
    private String clientIp(HttpServletRequest request) {
        return proxies.clientAddress(request.getRemoteAddr(), java.util.Collections.list(request.getHeaders("X-Forwarded-For")));
    }
    public record Accepted(String message) { }
}
