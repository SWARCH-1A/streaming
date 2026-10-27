package streaming.core.channels.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.channels.application.ChannelApplicationService;
import streaming.core.channels.application.ChannelException;
import streaming.core.channels.application.FollowApplicationService;
import streaming.core.security.RequestAuditFilter;
import tools.jackson.databind.JsonNode;

/** p2.channels.* and p2.recording.preference-*; the screen aggregate is composed by the Gateway. */
@RestController
public class FollowController {
    private final FollowApplicationService follows;
    private final ChannelApplicationService sessions;

    public FollowController(FollowApplicationService follows,ChannelApplicationService sessions) {
        this.follows=follows; this.sessions=sessions;
    }

    @GetMapping(path="/api/channels/{channelId}/followers/me",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FollowApplicationService.FollowView> state(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String channelId) {
        return privateBody(follows.state(sessions.requirePrincipal(credential),channelId));
    }

    @PutMapping(path="/api/channels/{channelId}/followers/me",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FollowApplicationService.FollowView> follow(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String channelId,@RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest request) {
        var principal=sessions.requirePrincipal(credential);
        return privateBody(follows.follow(principal,channelId,uuid(key),requestId(request)));
    }

    @DeleteMapping(path="/api/channels/{channelId}/followers/me",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FollowApplicationService.FollowView> unfollow(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String channelId,@RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest request) {
        var principal=sessions.requirePrincipal(credential);
        return privateBody(follows.unfollow(principal,channelId,uuid(key),requestId(request)));
    }

    @GetMapping(path="/api/me/followed-channels",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FollowApplicationService.FollowPage> followed(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @RequestParam Map<String,String> query) {
        var principal=sessions.requirePrincipal(credential);
        if(!Set.of("limit","cursor").containsAll(query.keySet())) throw invalid("Solo se admiten limit y cursor.");
        return privateBody(follows.followed(principal,integer(query.get("limit")),query.get("cursor")));
    }

    @GetMapping(path="/api/channels/{channelId}/vod-settings",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FollowApplicationService.VodSettingsView> vodSettings(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String channelId) {
        return privateBody(follows.vodSettings(sessions.requirePrincipal(credential),channelId));
    }

    @PutMapping(path="/api/channels/{channelId}/vod-settings",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FollowApplicationService.VodSettingsView> updateVodSettings(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String channelId,@RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody JsonNode body) {
        var principal=sessions.requirePrincipal(credential);
        fields(body,Set.of("enabled","expectedVersion"),Set.of("enabled","expectedVersion"));
        if(!body.get("enabled").isBoolean()) throw invalid("enabled debe ser booleano.");
        JsonNode version=body.get("expectedVersion");
        if(!version.isIntegralNumber() || version.asLong()<1 || version.asLong()>9007199254740991L) throw invalid("expectedVersion no es válido.");
        return privateBody(follows.updateVodSettings(principal,channelId,body.get("enabled").asBoolean(),version.asLong(),uuid(key)));
    }

    @PostMapping(path="/internal/core/notification-recipients",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FollowApplicationService.Recipients> recipients(@RequestBody JsonNode body) {
        fields(body,Set.of("channelId","sessionId","limit","cursor"),Set.of("channelId","sessionId"));
        if(body.has("limit") && !body.get("limit").isIntegralNumber()) throw invalid("limit debe ser entero.");
        Integer limit=body.has("limit")?body.get("limit").asInt():null;
        return privateBody(follows.recipients(text(body,"channelId"),text(body,"sessionId"),limit,body.has("cursor")?text(body,"cursor"):null));
    }

    @GetMapping(path="/internal/core/channels/{channelId}/vod-settings",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FollowApplicationService.VodSettingsView> internalVodSettings(@PathVariable String channelId) {
        return privateBody(follows.internalVodSettings(channelId));
    }

    private static <T> ResponseEntity<T> privateBody(T body) {
        return ResponseEntity.ok().header("Cache-Control","no-store, private").body(body);
    }
    private static void fields(JsonNode body,Set<String> allowed,Set<String> required) {
        if(body==null || !body.isObject()) throw invalid("Se requiere un objeto JSON.");
        for(String name:body.propertyNames()) if(!allowed.contains(name)) throw invalid("Campo no admitido: "+name);
        for(String name:required) if(!body.has(name) || body.get(name).isNull()) throw invalid("Falta el campo "+name+".");
    }
    private static String text(JsonNode body,String name) {
        JsonNode value=body.get(name);
        if(value==null || !value.isTextual()) throw invalid(name+" debe ser texto.");
        return value.textValue();
    }
    private static Integer integer(String value) {
        if(value==null) return null;
        try { return Integer.valueOf(value); } catch(NumberFormatException e) { throw invalid("limit debe ser entero."); }
    }
    private static UUID uuid(String key) {
        if(key==null) return null;
        try {
            UUID parsed=UUID.fromString(key);
            if(!parsed.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
            return parsed;
        } catch(IllegalArgumentException e) { throw new ChannelException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_INVALID","Idempotency-Key debe ser un UUID."); }
    }
    private static String requestId(HttpServletRequest request) { return (String)request.getAttribute(RequestAuditFilter.REQUEST_ID_ATTRIBUTE); }
    private static ChannelException invalid(String message) { return new ChannelException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
}
