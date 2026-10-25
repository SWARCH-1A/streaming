package streaming.core.accounts.administration.api;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import streaming.core.accounts.administration.application.AdministrationService;
import streaming.core.accounts.administration.application.AdministrationStore;
import streaming.core.channels.application.ChannelAdministration;

@RestController
@RequestMapping("/api/admin")
public class AdministrationController {
    private final AdministrationService service;
    public AdministrationController(AdministrationService service) { this.service=service; }
    @GetMapping("/users")
    public ResponseEntity<AdministrationService.Page<AdministrationStore.Account>> users(
            @CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @RequestParam(defaultValue="") String cursor,@RequestParam(defaultValue="20") int limit,@RequestParam java.util.Map<String,String> query) {
        checkQuery(query);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.accounts(credential,cursor,limit));
    }
    @GetMapping("/channels")
    public ResponseEntity<AdministrationService.Page<ChannelAdministration.Channel>> channels(
            @CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @RequestParam(defaultValue="") String cursor,@RequestParam(defaultValue="20") int limit,@RequestParam java.util.Map<String,String> query) {
        checkQuery(query);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.channels(credential,cursor,limit));
    }
    @PutMapping("/users/{userId}/status")
    public ResponseEntity<AdministrationStore.Account> status(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String userId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody tools.jackson.databind.JsonNode command) {
        if(!command.isObject() || command.size()!=3 || !command.hasNonNull("status") || !command.get("status").isTextual()
                || !command.hasNonNull("reason") || !command.get("reason").isTextual() || !command.hasNonNull("expectedVersion")
                || !command.get("expectedVersion").isIntegralNumber() || !command.get("expectedVersion").canConvertToLong()) throw invalid();
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.change(credential,userId,command.get("status").asText(),
            command.get("expectedVersion").asLong(),command.get("reason").asText(),key));
    }
    private static void checkQuery(java.util.Map<String,String> query) {
        if(!java.util.Set.of("cursor","limit").containsAll(query.keySet())) throw invalid();
    }
    private static streaming.core.accounts.identity.application.IdentityException invalid() {
        return new streaming.core.accounts.identity.application.IdentityException(org.springframework.http.HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","La solicitud no cumple el contrato.");
    }
}
