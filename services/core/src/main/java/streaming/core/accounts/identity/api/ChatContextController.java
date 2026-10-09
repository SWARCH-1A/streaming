package streaming.core.accounts.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.accounts.identity.application.ChatContexts;
import streaming.core.accounts.identity.application.IdentityException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import streaming.core.security.RequestAuditFilter;
import tools.jackson.databind.JsonNode;

@RestController
public class ChatContextController {
    private final ChatContexts contexts;
    private final ObjectMapper json;
    public ChatContextController(ChatContexts contexts,ObjectMapper json) { this.contexts=contexts; this.json=json; }
    @PostMapping("/internal/core/chat/message-context")
    public ResponseEntity<ChatContexts.Context> context(@RequestHeader(name="X-Session-Credential",required=false) String credential,
            HttpServletRequest request) throws java.io.IOException {
        JsonNode body;
        try {
            if(request.getContentType()==null) throw new IllegalArgumentException();
            var media=MediaType.parseMediaType(request.getContentType());
            if(!media.getType().equalsIgnoreCase("application") || !media.getSubtype().equalsIgnoreCase("json")) throw new IllegalArgumentException();
            // Bound chunked bodies too; Tomcat maxPostSize alone only covers form parsing.
            if(request.getContentLengthLong()>16*1024) throw new IllegalArgumentException();
            byte[] bytes=request.getInputStream().readNBytes(16*1024+1);
            if(bytes.length>16*1024) throw new IllegalArgumentException();
            body=json.readTree(bytes);
        } catch(RuntimeException e) {
            throw new IdentityException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","La solicitud no cumple el contrato JSON de hasta 16 KiB.");
        }
        return ResponseEntity.ok().header("Cache-Control","no-store").body(contexts.authorize(credential,body,requestId(request)));
    }
    @GetMapping("/internal/core/chat/sessions/{sessionId}")
    public ResponseEntity<SessionSnapshot> snapshot(@PathVariable String sessionId,HttpServletRequest request) {
        var snapshot=contexts.snapshot(sessionId,requestId(request));
        return ResponseEntity.ok().header("Cache-Control","no-store").body(new SessionSnapshot(snapshot.sessionId(),snapshot.streamId(),
                snapshot.streamGeneration(),snapshot.sessionVersion(),snapshot.status(),snapshot.availability(),snapshot.timelinePositionMs()));
    }
    private static String requestId(HttpServletRequest request) { return (String)request.getAttribute(RequestAuditFilter.REQUEST_ID_ATTRIBUTE); }
    public record SessionSnapshot(String sessionId,String streamId,long streamGeneration,long sessionVersion,
            String status,String availability,long timelinePositionMs) { }
}
