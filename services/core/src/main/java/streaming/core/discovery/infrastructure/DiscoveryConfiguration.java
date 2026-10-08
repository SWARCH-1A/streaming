package streaming.core.discovery.infrastructure;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import streaming.core.discovery.domain.RequestRateLimiter;
import streaming.core.discovery.domain.TrustedProxies;

@Configuration
class DiscoveryConfiguration {
    /** One limiter per process: P1 runs a single Core replica; sharing it across replicas needs a shared store. */
    @Bean RequestRateLimiter discoveryRateLimiter(@Value("${discovery.rate-limit.burst:20}") int burst,
            @Value("${discovery.rate-limit.window-max:600}") int windowMax,
            @Value("${discovery.rate-limit.window:PT60S}") Duration window) {
        return new RequestRateLimiter(burst,windowMax,window,System::nanoTime);
    }

    /** Fails startup on a malformed list so a typo cannot silently disable the per-IP limit. */
    @Bean TrustedProxies discoveryTrustedProxies(@Value("${core.trusted-proxies:}") String trustedProxies) {
        return TrustedProxies.parse(trustedProxies);
    }
}
