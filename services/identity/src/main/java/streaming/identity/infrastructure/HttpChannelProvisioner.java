package streaming.identity.infrastructure;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import streaming.identity.application.ChannelProvisioner;

@Component
public class HttpChannelProvisioner implements ChannelProvisioner {
    private final RestClient client;
    private final String serviceToken;
    public HttpChannelProvisioner(RestClient.Builder builder,
            @Value("${identity.channels-base-url}") String baseUrl,
            @Value("${identity.channels-service-token}") String serviceToken) {
        requireTlsOutsideLoopback(baseUrl);
        SimpleClientHttpRequestFactory timeouts=new SimpleClientHttpRequestFactory(); timeouts.setConnectTimeout(java.time.Duration.ofSeconds(1)); timeouts.setReadTimeout(java.time.Duration.ofSeconds(2));
        this.client=builder.baseUrl(baseUrl).requestFactory(timeouts).build(); this.serviceToken=serviceToken;
    }
    @Override public Optional<String> provision(String userId,String registrationId,Instant deadline) {
        try {
            ChannelResponse response=client.post().uri("/internal/channels/provision")
                    .header("X-Service-Name","identity").header("X-Service-Token",serviceToken)
                    .header("Idempotency-Key",registrationId)
                    .body(Map.of("ownerUserId",userId,"registrationId",registrationId,"pendingUntilUtc",deadline.toString()))
                    .retrieve().body(ChannelResponse.class);
            return response==null?Optional.empty():Optional.ofNullable(response.channelId());
        } catch (HttpClientErrorException.Gone expired) { return Optional.empty(); }
    }
    @Override public ProvisionState find(String registrationId) {
        try {
            StateResponse response=client.get().uri("/internal/channels/provisions/{id}",registrationId)
                    .header("X-Service-Name","identity").header("X-Service-Token",serviceToken).retrieve().body(StateResponse.class);
            if(response==null) return ProvisionState.UNKNOWN;
            return "PROVISIONED".equals(response.state())?ProvisionState.PROVISIONED:"ABSENT".equals(response.state())?ProvisionState.ABSENT:ProvisionState.PENDING;
        } catch (HttpClientErrorException.NotFound e) { return ProvisionState.UNKNOWN; }
    }
    @Override public void compensate(String registrationId) {
        client.delete().uri("/internal/channels/provisions/{id}",registrationId)
                .header("X-Service-Name","identity").header("X-Service-Token",serviceToken).retrieve().toBodilessEntity();
    }
    private record ChannelResponse(String channelId) { }
    private record StateResponse(String state) { }
    private static void requireTlsOutsideLoopback(String value) {
        java.net.URI uri=java.net.URI.create(value); String host=uri.getHost();
        boolean loopback="localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"::1".equals(host);
        if(!"https".equalsIgnoreCase(uri.getScheme()) && !loopback) throw new IllegalStateException("La comunicación remota Identity→Channels requiere HTTPS/TLS.");
    }
}
