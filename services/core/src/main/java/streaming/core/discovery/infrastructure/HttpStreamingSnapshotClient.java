package streaming.core.discovery.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import streaming.core.discovery.application.StreamingSnapshotClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Calls Streaming's private cut with {@code Authorization: Bearer}. The credential is never logged or echoed; every
 * failure is reduced to a stable code. Disabled (no calls) while the base URL or the credential is not configured.
 */
@Component
public class HttpStreamingSnapshotClient implements StreamingSnapshotClient {
    private static final int MAX_RESPONSE_CHARS=4*1024*1024;
    private final HttpClient client;
    private final String endpoint;
    private final String token;
    private final Duration readTimeout;
    private final ObjectMapper json;

    public HttpStreamingSnapshotClient(@Value("${discovery.streaming.base-url:}") String baseUrl,
            @Value("${discovery.streaming.consumer-token:}") String token,
            @Value("${discovery.streaming.connect-timeout:PT1S}") Duration connectTimeout,
            @Value("${discovery.streaming.read-timeout:PT5S}") Duration readTimeout,ObjectMapper json) {
        String base=baseUrl==null?"":baseUrl.strip().replaceAll("/+$","");
        this.endpoint=base.isEmpty()?null:base+"/internal/streaming/discovery/snapshots";
        this.token=token==null?"":token.strip();
        this.readTimeout=readTimeout; this.json=json;
        this.client=HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public boolean configured() { return endpoint!=null && !token.isEmpty(); }

    @Override public SnapshotPage page(int limit,String cursor) {
        if(!configured()) throw new SnapshotUnavailableException("NOT_CONFIGURED",null);
        var body=new LinkedHashMap<String,Object>();
        body.put("limit",limit);
        if(cursor!=null) body.put("cursor",cursor);
        HttpResponse<String> response;
        try {
            HttpRequest request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(readTimeout)
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Accept","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8)).build();
            response=client.send(request,HttpResponse.BodyHandlers.ofString());
        } catch(IOException e) {
            throw new SnapshotUnavailableException("TRANSPORT_UNAVAILABLE",e);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SnapshotUnavailableException("INTERRUPTED",e);
        } catch(RuntimeException e) {
            throw new SnapshotUnavailableException("TRANSPORT_UNAVAILABLE",e);
        }
        int status=response.statusCode();
        if(status==410) throw new SnapshotExpiredException();
        if(status!=200) throw new SnapshotUnavailableException("HTTP_"+status,null);
        if(response.body()==null || response.body().length()>MAX_RESPONSE_CHARS) throw new SnapshotUnavailableException("INVALID_RESPONSE",null);
        try { return parse(json.readTree(response.body())); }
        catch(RuntimeException e) { throw new SnapshotUnavailableException("INVALID_RESPONSE",e); }
    }

    private static SnapshotPage parse(JsonNode body) {
        if(body==null || !body.isObject()) throw new IllegalArgumentException("object expected");
        JsonNode items=body.get("items"), next=body.get("nextCursor");
        if(items==null || !items.isArray()) throw new IllegalArgumentException("items expected");
        var list=new ArrayList<JsonNode>();
        for(JsonNode item:items) list.add(item);
        String nextCursor=next==null || next.isNull()?null:text(next);
        return new SnapshotPage(text(body.get("snapshotId")),requireLong(body.get("watermark")),instant(body.get("capturedAtUtc")),
                instant(body.get("expiresAtUtc")),list,nextCursor);
    }
    private static String text(JsonNode node) {
        if(node==null || !node.isTextual() || node.textValue().isBlank()) throw new IllegalArgumentException("text expected");
        return node.textValue();
    }
    private static long requireLong(JsonNode node) {
        if(node==null || !node.isIntegralNumber() || !node.canConvertToLong()) throw new IllegalArgumentException("integer expected");
        return node.longValue();
    }
    private static java.time.Instant instant(JsonNode node) {
        try { return OffsetDateTime.parse(text(node)).toInstant(); }
        catch(DateTimeParseException e) { throw new IllegalArgumentException("timestamp expected"); }
    }
}
