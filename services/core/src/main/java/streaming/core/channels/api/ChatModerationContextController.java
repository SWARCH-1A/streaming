package streaming.core.channels.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.channels.application.ChatModerationContext;
import tools.jackson.databind.JsonNode;

@RestController
public class ChatModerationContextController {
    private final ChatModerationContext contexts;
    public ChatModerationContextController(ChatModerationContext contexts) { this.contexts=contexts; }
    @PostMapping("/internal/core/chat/moderation-context")
    public ResponseEntity<ChatModerationContext.Context> authorize(
            @RequestHeader(name="X-Session-Credential",required=false) String credential,@RequestBody JsonNode request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(contexts.authorize(credential,request));
    }
}
