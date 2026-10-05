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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import streaming.core.watchparty.application.StreamDirectory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the public Streaming contract {@code GET /api/streams/{streamId}} (no credentials). Any failure other than
 * a definitive 404 is reported as UNAVAILABLE so callers can fail closed or degrade; it never throws.
 */
@Component
public class HttpStreamDirectory implements StreamDirectory {
    private static final Logger log=LoggerFactory.getLogger(HttpStreamDirectory.class);
    private static final int MAX_BODY_CHARS=65_536;
    private final HttpClient client;
    private final String base;
    private final Duration readTimeout;
    private final ObjectMapper json;

    public HttpStreamDirectory(@Value("${watchparty.streaming-base-url}") String baseUrl,
            @Value("${watchparty.streaming-connect-timeout:PT1S}") Duration connectTimeout,
            @Value("${watchparty.streaming-read-timeout:PT2S}") Duration readTimeout,ObjectMapper json) {
        this.base=baseUrl.replaceAll("/+$","");
        this.readTimeout=readTimeout;
        this.json=json;
        this.client=HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
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
        HttpRequest request;
        try {
            request=HttpRequest.newBuilder(URI.create(base+"/api/streams/"+streamId)).timeout(readTimeout)
                    .header("Accept","application/json").GET().build();
        } catch(RuntimeException e) {
            return CompletableFuture.completedFuture(Lookup.unavailable());
        }
        return client.sendAsync(request,HttpResponse.BodyHandlers.ofString())
                .thenApply(response->parse(streamId,response))
                .exceptionally(error-> {
                    log.warn("event=streaming_lookup_failed component=core module=watchparty streamId={} reason={}",
                            streamId,rootCause(error).getClass().getSimpleName());
                    return Lookup.unavailable();
                });
    }

    private Lookup parse(String streamId,HttpResponse<String> response) {
        int code=response.statusCode();
        if(code==404) return Lookup.notFound();
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
            return Lookup.found(new StreamSnapshot(streamId,channelId,text(body,"title"),text(category,"name"),text(body,"status"),
                    availability,fresh!=null && fresh.isBoolean() && fresh.booleanValue(),
                    viewers!=null && viewers.isIntegralNumber()?viewers.intValue():null));
        } catch(RuntimeException e) {
            log.warn("event=streaming_lookup_invalid component=core module=watchparty streamId={}",streamId);
            return Lookup.unavailable();
        }
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
