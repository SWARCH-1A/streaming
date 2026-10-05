package streaming.core.watchparty.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.watchparty.application.PartyView;
import streaming.core.watchparty.application.WatchPartyApplicationService;
import tools.jackson.databind.JsonNode;

/** Watch Party REST contract (SPEC-14). Session by cookie; every mutation also requires the shared CSRF token. */
@RestController
public class WatchPartyController {
    private static final String NO_STORE="no-store, private";
    private final WatchPartyApplicationService parties;

    public WatchPartyController(WatchPartyApplicationService parties) { this.parties=parties; }

    @GetMapping(path="/api/watch-parties/csrf",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok().header("Cache-Control",NO_STORE).body(new CsrfResponse(token.getHeaderName(),token.getToken()));
    }

    @PostMapping(path="/api/watch-parties",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> create(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @RequestBody JsonNode request) {
        var principal=parties.requirePrincipal(credential);
        return ResponseEntity.status(201).header("Cache-Control",NO_STORE).body(parties.create(principal,request));
    }

    @GetMapping(path="/api/watch-parties/{partyId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> get(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String partyId) {
        var principal=parties.requirePrincipal(credential);
        return ResponseEntity.ok().header("Cache-Control",NO_STORE).body(parties.get(principal,partyId));
    }

    @PostMapping(path="/api/watch-parties/join",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> join(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @RequestBody JsonNode request) {
        var principal=parties.requirePrincipal(credential);
        return ResponseEntity.ok().header("Cache-Control",NO_STORE).body(parties.join(principal,request));
    }

    @PostMapping(path="/api/watch-parties/{partyId}/streams",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> addStream(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String partyId,@RequestBody JsonNode request) {
        var principal=parties.requirePrincipal(credential);
        return ResponseEntity.status(201).header("Cache-Control",NO_STORE).body(parties.addStream(principal,partyId,request));
    }

    @DeleteMapping(path="/api/watch-parties/{partyId}/streams/{streamId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> removeStream(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String partyId,@PathVariable String streamId) {
        var principal=parties.requirePrincipal(credential);
        return ResponseEntity.ok().header("Cache-Control",NO_STORE).body(parties.removeStream(principal,partyId,streamId));
    }

    @PostMapping(path="/api/watch-parties/{partyId}/access-code/rotate",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> rotateAccessCode(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String partyId) {
        var principal=parties.requirePrincipal(credential);
        return ResponseEntity.ok().header("Cache-Control",NO_STORE).body(parties.rotateAccessCode(principal,partyId));
    }

    @PostMapping(path="/api/watch-parties/{partyId}/close",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PartyView> close(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String partyId) {
        var principal=parties.requirePrincipal(credential);
        return ResponseEntity.ok().header("Cache-Control",NO_STORE).body(parties.close(principal,partyId));
    }

    public record CsrfResponse(String headerName,String token) { }
}
