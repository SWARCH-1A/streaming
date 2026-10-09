package streaming.core.discovery.infrastructure;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import streaming.core.discovery.domain.RequestLimiter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
class DiscoveryConfiguration {
    @Bean RequestLimiter discoveryRateLimiter(@Value("${discovery.rate-limit.burst:20}") int burst,
            @Value("${discovery.rate-limit.window-max:600}") int windowMax,
            @Value("${discovery.rate-limit.window:PT60S}") Duration window,
            @Value("${core.rate-limit-hmac-secret}") String secret,JdbcClient jdbc,TransactionTemplate transactions) {
        return new SqlRequestLimiter(jdbc,transactions,burst,windowMax,window,secret);
    }

}
