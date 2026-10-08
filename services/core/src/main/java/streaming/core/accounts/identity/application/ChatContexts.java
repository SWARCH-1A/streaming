package streaming.core.accounts.identity.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import streaming.core.accounts.profile.application.ProfileApplicationService;
import tools.jackson.databind.JsonNode;

/** Accounts publishes a fresh authorized author snapshot; Chat still owns quota/dedupe/commit. */
@Service
public class ChatContexts {
    private final IdentityApplicationService accounts;
    private final ProfileApplicationService profiles;
    private final StreamingSessions sessions;
    public ChatContexts(IdentityApplicationService accounts,ProfileApplicationService profiles,StreamingSessions sessions) {
        this.accounts=accounts; this.profiles=profiles; this.sessions=sessions;
    }
    public Context authorize(String credential,JsonNode body,String requestId) {
        var principal=accounts.introspect(credential).orElseThrow(()->error(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED"));
        String sessionId;
        try {
            if(body==null || !body.isObject() || !Set.of("sessionId","clientMessageId").containsAll(body.propertyNames())
                    || !body.path("sessionId").isTextual() || !body.path("clientMessageId").isTextual()) throw new IllegalArgumentException();
            String clientId=body.get("clientMessageId").asText();
            if(!UUID.fromString(clientId).toString().equalsIgnoreCase(clientId)) throw new IllegalArgumentException();
            sessionId=body.get("sessionId").asText();
            if(!validId(sessionId)) throw new IllegalArgumentException();
        } catch(IllegalArgumentException e) { throw error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR"); }
        var profile=profiles.getPublic(principal.userId());
        var snapshot=snapshot(sessionId,requestId);
        boolean allowed=snapshot.status().equals("LIVE");
        String denial=allowed?null:snapshot.status().equals("PREPARING")?"CHAT_NOT_OPEN":"CHAT_READ_ONLY";
        return new Context(principal.userId(),principal.handle(),profile.displayName(),profile.avatarUri(),profile.profileVersion(),
                sessionId,snapshot.streamGeneration(),snapshot.sessionVersion(),snapshot.availability(),Instant.now(),
                snapshot.timelinePositionMs(),snapshot.sessionVersion(),allowed,denial);
    }
    public StreamingSessions.Snapshot snapshot(String sessionId,String requestId) {
        if(!validId(sessionId)) throw error(HttpStatus.NOT_FOUND,"SESSION_NOT_FOUND");
        try { return sessions.session(sessionId,requestId); }
        catch(StreamingSessions.TimelineUnavailable e) { throw error(HttpStatus.SERVICE_UNAVAILABLE,"TIMELINE_UNAVAILABLE"); }
        catch(StreamingSessions.Unavailable e) {
            throw error(e.notFound()?HttpStatus.NOT_FOUND:HttpStatus.SERVICE_UNAVAILABLE,e.notFound()?"SESSION_NOT_FOUND":"STREAMING_UNAVAILABLE");
        }
    }
    private static boolean validId(String id) { return id!=null && id.matches("[A-Za-z0-9_-]{1,128}"); }
    private static IdentityException error(HttpStatus status,String code) { return new IdentityException(status,code,"No fue posible obtener el contexto autorizado."); }
    public record Context(String userId,String handle,String displayName,String avatarUri,long profileVersion,String sessionId,
            long streamGeneration,long sessionVersion,String availability,Instant authorizedAtUtc,Long timelinePositionMs,
            Long timelineSampleVersion,boolean writeAllowed,String denialCode) { }
}
