package streaming.core.watchparty.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.security.SessionCredentialFilter;
import streaming.core.watchparty.application.PartyView;
import streaming.core.watchparty.application.WatchPartyApplicationService;
import streaming.core.watchparty.application.WatchPartyException;
import tools.jackson.databind.JsonNode;

/**
 * Watch party REST contract ({@code p2.watch-parties.*}, SPEC-21). The session is resolved by the shared credential filter
 * (cookie or native Bearer), browser mutations carry the common Core CSRF token, and every command needs an Idempotency-Key.
 */
@RestController
public class WatchPartyController {
    private static final String NO_STORE="no-store, private";
    private final WatchPartyApplicationService parties;

    public WatchPartyController(WatchPartyApplicationService parties) { this.parties=parties; }

    @PostMapping(path="/api/watch-parties",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> create(@RequestAttribute(name=SessionCredentialFilter.CREDENTIAL,required=false) String credential,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody JsonNode request) {
        var principal=parties.requirePrincipal(credential);
        return body(HttpStatus.CREATED,parties.create(principal,uuid(key),request));
    }

    @GetMapping(path="/api/watch-parties/{partyId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> get(@RequestAttribute(name=SessionCredentialFilter.CREDENTIAL,required=false) String credential,
            @PathVariable String partyId) {
        var principal=parties.requirePrincipal(credential);
        return body(HttpStatus.OK,parties.get(principal,partyId));
    }

    @PostMapping(path="/api/watch-parties/join",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> join(@RequestAttribute(name=SessionCredentialFilter.CREDENTIAL,required=false) String credential,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody JsonNode request) {
        var principal=parties.requirePrincipal(credential);
        return body(HttpStatus.OK,parties.join(principal,uuid(key),request));
    }

    @PostMapping(path="/api/watch-parties/{partyId}/streams",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> addStream(@RequestAttribute(name=SessionCredentialFilter.CREDENTIAL,required=false) String credential,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@PathVariable String partyId,@RequestBody JsonNode request) {
        var principal=parties.requirePrincipal(credential);
        return body(HttpStatus.CREATED,parties.addStream(principal,uuid(key),partyId,request));
    }

    @DeleteMapping(path="/api/watch-parties/{partyId}/streams/{streamId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> removeStream(@RequestAttribute(name=SessionCredentialFilter.CREDENTIAL,required=false) String credential,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@PathVariable String partyId,@PathVariable String streamId) {
        var principal=parties.requirePrincipal(credential);
        return body(HttpStatus.OK,parties.removeStream(principal,uuid(key),partyId,streamId));
    }

    @PostMapping(path="/api/watch-parties/{partyId}/access-code/rotate",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> rotateAccessCode(@RequestAttribute(name=SessionCredentialFilter.CREDENTIAL,required=false) String credential,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@PathVariable String partyId) {
        var principal=parties.requirePrincipal(credential);
        return body(HttpStatus.OK,parties.rotateAccessCode(principal,uuid(key),partyId));
    }

    @PostMapping(path="/api/watch-parties/{partyId}/close",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> close(@RequestAttribute(name=SessionCredentialFilter.CREDENTIAL,required=false) String credential,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@PathVariable String partyId) {
        var principal=parties.requirePrincipal(credential);
        return body(HttpStatus.OK,parties.close(principal,uuid(key),partyId));
    }

    private static ResponseEntity<PartyView> body(HttpStatus status,PartyView view) {
        return ResponseEntity.status(status).header("Cache-Control",NO_STORE).body(view);
    }

    /** Null when the header is absent (the use case answers 400 IDEMPOTENCY_KEY_REQUIRED); malformed values are rejected here. */
    private static UUID uuid(String key) {
        if(key==null) return null;
        try {
            UUID parsed=UUID.fromString(key);
            if(!parsed.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
            return parsed;
        } catch(IllegalArgumentException e) {
            throw new WatchPartyException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_INVALID","Idempotency-Key debe ser un UUID.");
        }
    }
}
