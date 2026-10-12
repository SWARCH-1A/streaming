package streaming.core.discovery.domain;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import streaming.core.discovery.domain.RequestLimiter.Decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestRateLimiterTest {
    private static final long SECOND=1_000_000_000L;
    private AtomicLong now;

    @BeforeEach void start() { now=new AtomicLong(1_000*SECOND); }

    private RequestRateLimiter contract() { return new RequestRateLimiter(20,600,Duration.ofSeconds(60),now::get); }

    @Test void allowsABurstOfTwentyThenRejectsWithRetryAfter() {
        var limiter=contract();
        for(int i=0;i<20;i++) assertThat(limiter.tryAcquire("1.1.1.1").allowed()).as("request "+i).isTrue();
        Decision denied=limiter.tryAcquire("1.1.1.1");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test void refillsTenTokensPerSecond() {
        var limiter=contract();
        for(int i=0;i<20;i++) limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        now.addAndGet(SECOND/10);                                   // 0.1 s -> exactly one token
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        now.addAndGet(SECOND);                                      // 1 s -> ten tokens
        for(int i=0;i<10;i++) assertThat(limiter.tryAcquire("a").allowed()).as("refilled "+i).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test void theBucketNeverHoldsMoreThanTheBurstEvenAfterALongIdle() {
        var limiter=contract();
        now.addAndGet(3600*SECOND);
        for(int i=0;i<20;i++) assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test void sustainedTenPerSecondIsAllowedIndefinitely() {
        var limiter=contract();
        for(int i=0;i<2400;i++) {                                    // 4 minutes at exactly 10 req/s
            assertThat(limiter.tryAcquire("steady").allowed()).as("request "+i).isTrue();
            now.addAndGet(SECOND/10);
        }
    }

    @Test void neverAllowsMoreThanSixHundredInAnySixtySecondWindow() {
        var limiter=contract();
        int allowed=0;
        // Hammer far above the contract for two minutes: 50 requests every second.
        for(int second=0;second<120;second++) {
            for(int i=0;i<50;i++) { if(limiter.tryAcquire("hammer").allowed()) allowed++; now.addAndGet(SECOND/50); }
        }
        assertThat(allowed).isLessThanOrEqualTo(2*600+20);
        // Within the first full minute no more than 600 plus the initial burst can pass.
        var fresh=new RequestRateLimiter(20,600,Duration.ofSeconds(60),now::get);
        int firstMinute=0;
        for(int second=0;second<60;second++) for(int i=0;i<50;i++) { if(fresh.tryAcquire("x").allowed()) firstMinute++; now.addAndGet(SECOND/50); }
        assertThat(firstMinute).isLessThanOrEqualTo(600);
    }

    @Test void theWindowCapIsIndependentFromTheBucketAndReportsWhenTheOldestRequestExpires() {
        var limiter=new RequestRateLimiter(1000,5,Duration.ofSeconds(10),now::get);   // bucket never limits here
        for(int i=0;i<5;i++) assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        Decision denied=limiter.tryAcquire("k");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(10);
        now.addAndGet(3*SECOND);
        assertThat(limiter.tryAcquire("k").retryAfterSeconds()).isEqualTo(7);
        now.addAndGet(7*SECOND);
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
    }

    @Test void keysAreIndependent() {
        var limiter=contract();
        for(int i=0;i<25;i++) limiter.tryAcquire("noisy");
        assertThat(limiter.tryAcquire("noisy").allowed()).isFalse();
        assertThat(limiter.tryAcquire("quiet").allowed()).isTrue();
    }

    @Test void rejectsNonsensicalConfiguration() {
        assertThatThrownBy(()->new RequestRateLimiter(0,600,Duration.ofSeconds(60),now::get)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RequestRateLimiter(20,0,Duration.ofSeconds(60),now::get)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RequestRateLimiter(20,600,Duration.ofMillis(500),now::get)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RequestRateLimiter(20,600,Duration.ofHours(2),now::get)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void aLongSilenceForgetsTheWindowCompletely() {
        var limiter=new RequestRateLimiter(1000,5,Duration.ofSeconds(10),now::get);
        for(int i=0;i<5;i++) limiter.tryAcquire("k");
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();
        now.addAndGet(1000*SECOND);
        for(int i=0;i<5;i++) assertThat(limiter.tryAcquire("k").allowed()).isTrue();
    }
}
