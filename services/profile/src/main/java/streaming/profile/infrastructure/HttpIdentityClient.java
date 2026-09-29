package streaming.profile.infrastructure;

import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import streaming.profile.application.IdentityClient;
import streaming.profile.application.ProfileException;

@Component
public class HttpIdentityClient implements IdentityClient {
    private final RestClient client; private final String serviceToken;
    public HttpIdentityClient(RestClient.Builder builder,@Value("${profile.identity-base-url}") String baseUrl,
            @Value("${profile.identity-service-token}") String serviceToken) {
        java.net.URI uri=java.net.URI.create(baseUrl); String host=uri.getHost();
        boolean loopback="localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"::1".equals(host);
        if(!"https".equalsIgnoreCase(uri.getScheme()) && !loopback) throw new IllegalStateException("La comunicación remota Profile→Identity requiere HTTPS/TLS.");
        SimpleClientHttpRequestFactory timeouts=new SimpleClientHttpRequestFactory(); timeouts.setConnectTimeout(java.time.Duration.ofSeconds(1)); timeouts.setReadTimeout(java.time.Duration.ofSeconds(2));
        this.client=builder.baseUrl(baseUrl).requestFactory(timeouts).build(); this.serviceToken=serviceToken;
    }
    @Override public Optional<Principal> introspect(String credential) {
        if(credential==null || credential.isBlank()) return Optional.empty();
        try {
            Introspection result=client.post().uri("/internal/identity/sessions/introspect").header("X-Service-Name","profile")
                    .header("X-Service-Token",serviceToken).header("X-Session-Credential",credential).retrieve().body(Introspection.class);
            return result!=null && result.active()?Optional.of(new Principal(result.userId(),result.handle(),result.expiresAtUtc())):Optional.empty();
        } catch(RestClientException e) { throw unavailable(); }
    }
    @Override public Optional<PublicIdentity> findActiveUser(String userId) {
        try {
            IdentityResponse response=client.get().uri("/api/identity/public/users/{id}",userId).retrieve().body(IdentityResponse.class);
            return response==null?Optional.empty():Optional.of(new PublicIdentity(response.userId(),response.handle()));
        } catch(HttpClientErrorException.NotFound e) { return Optional.empty(); }
        catch(RestClientException e) { throw unavailable(); }
    }
    private static ProfileException unavailable() { return new ProfileException(HttpStatus.SERVICE_UNAVAILABLE,"IDENTITY_UNAVAILABLE","No fue posible validar la identidad en este momento."); }
    private record Introspection(boolean active,String userId,String handle,Instant expiresAtUtc) { }
    private record IdentityResponse(String userId,String handle) { }
}
