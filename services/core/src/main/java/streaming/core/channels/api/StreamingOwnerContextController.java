package streaming.core.channels.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.channels.application.StreamingOwnerContext;
import tools.jackson.databind.JsonNode;

@RestController
public class StreamingOwnerContextController {
    private final StreamingOwnerContext contexts;
    public StreamingOwnerContextController(StreamingOwnerContext contexts) { this.contexts=contexts; }
    @PostMapping("/internal/core/streaming/owner-context")
    public ResponseEntity<StreamingOwnerContext.Context> authorize(
            @RequestHeader(name="X-Session-Credential",required=false) String credential,@RequestBody JsonNode request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(contexts.authorize(credential,request));
    }
}
