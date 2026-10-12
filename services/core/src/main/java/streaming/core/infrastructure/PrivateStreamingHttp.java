package streaming.core.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Transport only: bounded bodies/deadlines and correlation, no domain decisions or retries. */
@Component
public class PrivateStreamingHttp {
    private final URI base;
    private final String token;
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(100))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public PrivateStreamingHttp(@Value("${core.streaming.base-url:}") String url,
            @Value("${core.streaming.consumer-token:}") String token,
            @Value("${core.streaming.development-http:false}") boolean developmentHttp,ObjectMapper json) {
        this.json=json; this.token=token;
        if(url.isBlank()) { base=null; return; }
        URI parsed;
        try { parsed=URI.create(url); }
        catch(IllegalArgumentException e) { throw new IllegalStateException("Invalid private Streaming URL"); }
        if(parsed.getHost()==null || parsed.getUserInfo()!=null || parsed.getRawQuery()!=null || parsed.getRawFragment()!=null
                || !(parsed.getPath().isEmpty() || parsed.getPath().equals("/"))
                || !("https".equals(parsed.getScheme()) || (developmentHttp && "http".equals(parsed.getScheme())))
                || (parsed.getPort()!=-1 && (parsed.getPort()<1 || parsed.getPort()>65535))
                || !token.matches("[A-Za-z0-9_-]{32,256}"))
            throw new IllegalStateException("Private Streaming requires HTTPS and a service credential; isolated HTTP must be explicit");
        base=parsed;
    }

    public JsonNode exchange(String path,Object body,Duration budget,String requestId) {
        if(base==null) throw new Failure(false);
        long started=System.nanoTime();
        var request=HttpRequest.newBuilder(base.resolve(path)).timeout(budget)
                .header("Authorization","Bearer "+token).header("Accept","application/json");
        if(requestId!=null && requestId.matches("[A-Za-z0-9._-]{1,80}")) request.header("X-Request-Id",requestId);
        if(body==null) request.GET();
        else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var pending=client.sendAsync(request.build(),HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),64*1024));
        HttpResponse<byte[]> response;
        try { response=pending.get(budget.toNanos(),TimeUnit.NANOSECONDS); }
        catch(InterruptedException e) { pending.cancel(true); Thread.currentThread().interrupt(); throw new Failure(false); }
        catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            pending.cancel(true); throw new Failure(false);
        }
        if(response.statusCode()!=200) throw new Failure(response.statusCode()==404);
        try {
            if(!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim().equalsIgnoreCase("application/json"))
                throw new IllegalArgumentException();
            JsonNode value=json.readTree(response.body());
            if(value==null || !value.isObject() || System.nanoTime()-started>budget.toNanos()) throw new IllegalArgumentException();
            return value;
        } catch(RuntimeException e) { throw new Failure(false); }
    }

    /** No upstream body/URL/cause is attached: exceptions cannot disclose tokens or internal data. */
    public static final class Failure extends RuntimeException {
        private final boolean notFound;
        public Failure(boolean notFound) { super("Streaming is unavailable"); this.notFound=notFound; }
        public boolean notFound() { return notFound; }
    }
}
