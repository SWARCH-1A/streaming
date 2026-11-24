package streaming.core.audit;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.MultiValueMap;
import streaming.core.accounts.administration.application.AdministrationService;
import streaming.core.accounts.identity.application.IdentityException;
import streaming.core.security.SessionCredentialFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class AuditQueryController {
    private final AdministrationService administration;
    private final ObjectMapper json;
    private final String base,token;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
    public AuditQueryController(AdministrationService administration,ObjectMapper json,
            @Value("${core.audit.base-url:}") String base,@Value("${core.audit.query-token:}") String token,
            @Value("${core.audit.development-http:false}") boolean development) {
        this.administration=administration;this.json=json;this.base=base;this.token=token;
        if(!base.isEmpty() && ((!base.startsWith("https://")&&!development)||token.length()<32))
            throw new IllegalStateException("Audit query requires TLS and a dedicated service secret");
    }
    @GetMapping("/api/admin/audit-events")
    public ResponseEntity<JsonNode> events(@RequestAttribute(name=SessionCredentialFilter.CREDENTIAL,required=false) String credential,
            @RequestParam MultiValueMap<String,String> query) {
        String actor=administration.requireAdministrator(credential);
        if(!Set.of("actorId","targetId","action","outcome","producer","requestId","from","to","cursor","limit").containsAll(query.keySet())
                ||query.values().stream().anyMatch(v->v.size()!=1)) throw error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR");
        String parameters=query.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue().getFirst())).collect(Collectors.joining("&"));
        if(parameters.length()>2048) throw error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR");
        if(base.isEmpty()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"AUDIT_UNAVAILABLE");
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            var request=HttpRequest.newBuilder(URI.create(base+"/internal/audit/events?"+parameters)).timeout(Duration.ofSeconds(3))
                .header("X-Service-Name","core").header("X-Service-Token",token).header("X-Audit-Actor",actor).GET().build();
            pending=client.sendAsync(request,info->new BoundedBody());
            // Include the complete body in the deadline; InputStream responses finish at headers.
            var response=pending.get(3,TimeUnit.SECONDS);
            if(response.statusCode()==400) throw error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR");
            if(response.statusCode()!=200) throw error(HttpStatus.SERVICE_UNAVAILABLE,"AUDIT_UNAVAILABLE");
            return ResponseEntity.ok().header("Cache-Control","no-store, private").body(json.readTree(response.body()));
        } catch(IdentityException e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw error(HttpStatus.SERVICE_UNAVAILABLE,"AUDIT_UNAVAILABLE"); }
        catch(Exception e) { throw error(HttpStatus.SERVICE_UNAVAILABLE,"AUDIT_UNAVAILABLE"); }
        finally { if(pending!=null) pending.cancel(true); }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int remaining=262144;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) { subscription=value;delegate.onSubscribe(value); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(buffer.remaining()>remaining) {
                    subscription.cancel();delegate.onError(new IOException("Audit response exceeds limit"));return;
                }
                remaining-=buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { delegate.onError(error); }
        @Override public void onComplete() { delegate.onComplete(); }
    }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private static IdentityException error(HttpStatus status,String code) { return new IdentityException(status,code,"No fue posible consultar la auditoría."); }
}
