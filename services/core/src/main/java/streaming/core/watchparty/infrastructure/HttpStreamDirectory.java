package streaming.core.watchparty.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import streaming.core.watchparty.application.StreamDirectory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the public Streaming contract {@code GET /api/streams/{streamId}} (no credentials). Any failure other than
 * a definitive Streaming 404 ({@code STREAM_NOT_FOUND}) is reported as UNAVAILABLE so callers can fail closed or
 * degrade; it never throws and never waits longer than the read timeout in total, body included. An empty base URL
 * disables the lookups: every stream is UNAVAILABLE.
 */
@Component
public class HttpStreamDirectory implements StreamDirectory {
    private static final Logger log=LoggerFactory.getLogger(HttpStreamDirectory.class);
    private static final int MAX_BODY_CHARS=65_536;
    private static final String NOT_FOUND_CODE="STREAM_NOT_FOUND";
    private final HttpClient client;
    private final String base;
    private final Duration readTimeout;
    private final ObjectMapper json;

    public HttpStreamDirectory(@Value("${watchparty.streaming-base-url:}") String baseUrl,
            @Value("${watchparty.streaming-connect-timeout:PT1S}") Duration connectTimeout,
            @Value("${watchparty.streaming-read-timeout:PT2S}") Duration readTimeout,ObjectMapper json) {
        this.base=baseUrl.strip().replaceAll("/+$","");
        this.readTimeout=readTimeout;
        this.json=json;
        this.client=base.isEmpty()?null:HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
        if(client==null) log.warn("event=streaming_lookup_disabled component=core module=watchparty reason=WATCHPARTY_STREAMING_BASE_URL_empty");
    }

    @Override public Lookup find(String streamId) { return lookup(streamId).join(); }

    @Override public Map<String,Lookup> findAll(Collection<String> streamIds) {
        Map<String,CompletableFuture<Lookup>> pending=new LinkedHashMap<>();
        for(String streamId:streamIds) pending.put(streamId,lookup(streamId));
        Map<String,Lookup> found=new LinkedHashMap<>();
        pending.forEach((streamId,future)->found.put(streamId,future.join()));
        return found;
    }

    private CompletableFuture<Lookup> lookup(String streamId) {
        if(client==null) return CompletableFuture.completedFuture(Lookup.unavailable());
        HttpRequest request;
        try {
            request=HttpRequest.newBuilder(URI.create(base+"/api/streams/"+streamId)).timeout(readTimeout)
                    .header("Accept","application/json").GET().build();
        } catch(RuntimeException e) {
            return CompletableFuture.completedFuture(Lookup.unavailable());
        }
        var exchange=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        // HttpRequest.timeout stops applying once the headers arrive. Cancelling the exchange itself at the deadline (not just
        // failing a dependent future) closes the connection of a stalled body, so repeated polls cannot pile up open exchanges.
        CompletableFuture.delayedExecutor(readTimeout.toMillis(),TimeUnit.MILLISECONDS).execute(()->exchange.cancel(true));
        return exchange
                .thenApply(response->parse(streamId,response))
                .exceptionally(error-> {
                    log.warn("event=streaming_lookup_failed component=core module=watchparty streamId={} reason={}",
                            streamId,rootCause(error).getClass().getSimpleName());
                    return Lookup.unavailable();
                });
    }

    private Lookup parse(String streamId,HttpResponse<String> response) {
        int code=response.statusCode();
        // Only Streaming's own "unknown stream" answer is definitive; a bare 404 from a misrouted URL or a proxy is not.
        if(code==404 && notFoundCode(response.body())) return Lookup.notFound();
        if(code!=200 || response.body()==null || response.body().length()>MAX_BODY_CHARS) {
            log.warn("event=streaming_lookup_failed component=core module=watchparty streamId={} status={}",streamId,code);
            return Lookup.unavailable();
        }
        try {
            JsonNode body=json.readTree(response.body());
            String channelId=text(body,"channelId"), availability=text(body,"availability");
            if(!streamId.equals(text(body,"streamId")) || channelId==null || availability==null) {
                log.warn("event=streaming_lookup_invalid component=core module=watchparty streamId={}",streamId);
                return Lookup.unavailable();
            }
            JsonNode category=body.get("category"), fresh=body.get("statusFresh"), viewers=body.get("viewerCount");
            // The session ID goes to clients that build player paths from it: anything unexpected is treated as no session.
            String sessionId=text(body,"sessionId");
            if(sessionId!=null && !sessionId.matches("[A-Za-z0-9_-]{1,128}")) sessionId=null;
            return Lookup.found(new StreamSnapshot(streamId,channelId,sessionId,text(body,"title"),text(category,"name"),
                    text(body,"status"),availability,fresh!=null && fresh.isBoolean() && fresh.booleanValue(),
                    viewers!=null && viewers.isIntegralNumber()?viewers.intValue():null));
        } catch(RuntimeException e) {
            log.warn("event=streaming_lookup_invalid component=core module=watchparty streamId={}",streamId);
            return Lookup.unavailable();
        }
    }

    private boolean notFoundCode(String body) {
        if(body==null || body.length()>MAX_BODY_CHARS) return false;
        try { return NOT_FOUND_CODE.equals(text(json.readTree(body),"code")); }
        catch(RuntimeException e) { return false; }
    }

    private static String text(JsonNode node,String field) {
        if(node==null || !node.isObject()) return null;
        JsonNode value=node.get(field);
        return value!=null && value.isTextual()?value.textValue():null;
    }
    private static Throwable rootCause(Throwable error) {
        Throwable cause=error;
        while(cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
        return cause;
    }
}
