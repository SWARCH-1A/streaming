package streaming.core.discovery;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import streaming.core.discovery.domain.RequestRateLimiter;
import streaming.core.security.TrustedProxies;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CA-08 rate limit over HTTP: a burst of 3, refilled at 1 per second (6 per 6 s window), per client address. The
 * loopback address is the trusted proxy here, so the forwarded client address decides the bucket. The arithmetic of
 * the limiter itself is covered by RequestRateLimiterTest.
 */
@Import({DiscoveryITBase.Clocks.class,DiscoveryRateLimitIT.Limits.class})
class DiscoveryRateLimitIT extends DiscoveryITBase {
    static final AtomicLong NANOS=new AtomicLong(1_000_000_000L);

    @TestConfiguration static class Limits {
        @Bean @Primary RequestRateLimiter smallRateLimiter() { return new RequestRateLimiter(3,6,Duration.ofSeconds(6),NANOS::get); }
        @Bean @Primary TrustedProxies loopbackProxy() { return TrustedProxies.parse("127.0.0.1/32,::1/128"); }
    }

    @BeforeEach void freshBuckets() { NANOS.addAndGet(Duration.ofHours(2).toNanos()); }

    private HttpResponse<String> call(String forwardedFor) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/discovery/graphql")).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ channels(limit:1) { items { handle } } }\"}"));
        if(forwardedFor!=null) request.header("X-Forwarded-For",forwardedFor);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void theFourthRequestInABurstIs429WithRetryAfterAndTheBucketRefills() throws Exception {
        for(int i=0;i<3;i++) assertThat(call("198.51.100.1").statusCode()).as("request "+i).isEqualTo(200);

        var limited=call("198.51.100.1");

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L,6L);
        JsonNode error=json.readTree(limited.body()).at("/errors/0");
        assertThat(error.at("/extensions/code").textValue()).isEqualTo("RATE_LIMITED");
        assertThat(error.at("/extensions/httpStatus").intValue()).isEqualTo(429);
        assertThat(error.at("/extensions/requestId").textValue()).isNotBlank();
        assertThat(limited.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(limited.body()).doesNotContain("198.51.100.1");

        NANOS.addAndGet(Duration.ofMillis(500).toNanos());
        assertThat(call("198.51.100.1").statusCode()).as("half a token is not enough").isEqualTo(429);
        NANOS.addAndGet(Duration.ofMillis(600).toNanos());
        assertThat(call("198.51.100.1").statusCode()).as("one token refilled after Retry-After").isEqualTo(200);
    }

    @Test void waitingNeverBuildsUpMoreThanTheBurst() throws Exception {
        for(int i=0;i<3;i++) call("198.51.100.2");
        NANOS.addAndGet(Duration.ofHours(1).toNanos());
        int allowed=0;
        for(int i=0;i<6;i++) if(call("198.51.100.2").statusCode()==200) allowed++;
        assertThat(allowed).isEqualTo(3);
    }

    @Test void differentForwardedClientsBehindTheTrustedProxyHaveSeparateBuckets() throws Exception {
        for(int i=0;i<3;i++) call("203.0.113.7");
        assertThat(call("203.0.113.7").statusCode()).isEqualTo(429);
        assertThat(call("203.0.113.8").statusCode()).as("another client").isEqualTo(200);
        assertThat(call("203.0.113.9, 127.0.0.1").statusCode()).as("trusted hops on the right are skipped").isEqualTo(200);
    }

    @Test void aSpoofedLeftmostEntryCannotEvadeTheLimit() throws Exception {
        for(int i=0;i<3;i++) assertThat(call("1.1.1."+i+", 203.0.113.50").statusCode()).isEqualTo(200);
        assertThat(call("9.9.9.9, 203.0.113.50").statusCode()).as("the address added by the proxy is the real client").isEqualTo(429);
    }

    @Test void aMalformedForwardedHeaderFallsBackToTheConnectionAddress() throws Exception {
        for(int i=0;i<3;i++) assertThat(call("not-an-address").statusCode()).isEqualTo(200);
        assertThat(call("also bad").statusCode()).as("both land in the proxy's own bucket").isEqualTo(429);
    }

    @Test void limitedRequestsDoNotReachTheQueryEngine() throws Exception {
        for(int i=0;i<3;i++) call("198.51.100.3");
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/discovery/graphql")).header("Content-Type","application/json")
                .header("X-Forwarded-For","198.51.100.3").POST(HttpRequest.BodyPublishers.ofString("{")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("rate limit is checked before the body").isEqualTo(429);
    }
}
