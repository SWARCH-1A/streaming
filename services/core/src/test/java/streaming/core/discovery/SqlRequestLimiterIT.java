package streaming.core.discovery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.discovery.infrastructure.SqlRequestLimiter;
import static org.assertj.core.api.Assertions.assertThat;

class SqlRequestLimiterIT extends DiscoveryITBase {
    @Autowired TransactionTemplate transactions;
    static final String SECRET="fixture_shared_discovery_hmac_32_bytes";
    @Test void twoIndependentInstancesShareOneAtomicBurst() throws Exception {
        var a=new SqlRequestLimiter(jdbc,transactions,3,6,Duration.ofHours(1),SECRET);
        var b=new SqlRequestLimiter(jdbc,transactions,3,6,Duration.ofHours(1),SECRET);
        String key="fixture-"+java.util.UUID.randomUUID();
        var tasks=new ArrayList<Callable<Boolean>>();
        for(int i=0;i<20;i++) { var limiter=i%2==0?a:b; tasks.add(()->limiter.tryAcquire(key).allowed()); }
        try(var executor=Executors.newFixedThreadPool(8)) {
            int allowed=0;
            for(var result:executor.invokeAll(tasks)) if(result.get()) allowed++;
            assertThat(allowed).isEqualTo(3);
        }
        assertThat(a.tryAcquire(key).allowed()).isFalse();
        assertThat(b.tryAcquire(key).retryAfterSeconds()).isPositive();
        assertThat(b.tryAcquire(key+"-other").allowed()).isTrue();
    }
    private static String hash(String key) {
        try { var mac=javax.crypto.Mac.getInstance("HmacSHA256"); mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256")); return java.util.HexFormat.of().formatHex(mac.doFinal(("discovery:"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new AssertionError(e); }
    }
    @Test void rollingCapSurvivesRefillAndBackwardClockDoesNotGrantTokens() {
        var limiter=new SqlRequestLimiter(jdbc,transactions,3,3,Duration.ofSeconds(60),SECRET);
        String key="fixture-"+java.util.UUID.randomUUID();
        for(int i=0;i<3;i++) assertThat(limiter.tryAcquire(key).allowed()).isTrue();
        // Fixture SQL advances only the refill anchor, preserving admitted events in the rolling window.
        jdbc.sql("UPDATE discovery.rate_buckets SET refilled_at=refilled_at-interval '1 hour' WHERE bucket_hash=:hash").param("hash",hash(key)).update();
        assertThat(limiter.tryAcquire(key).allowed()).isFalse();
        jdbc.sql("UPDATE discovery.rate_buckets SET tokens=0,refilled_at=clock_timestamp()+interval '1 hour' WHERE bucket_hash=:hash").param("hash",hash(key)).update();
        assertThat(limiter.tryAcquire(key).allowed()).isFalse();
    }
}
