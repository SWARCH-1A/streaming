package streaming.core.discovery.domain;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Per-key limiter: a token bucket of {@code burst} tokens refilled at {@code windowMax/window}, plus a moving cap of
 * {@code windowMax} requests in the last {@code window}. The window is tracked in one-second buckets, so a request
 * stays counted for between {@code window} and {@code window}+1 s: never looser than the contract. State is local
 * to the process; retained for deterministic isolated tests. Runtime wiring uses SqlRequestLimiter across replicas.
 * When this test limiter table is full a new key is let through untracked rather than
 * denying unrelated clients.
 */
public final class RequestRateLimiter implements RequestLimiter {
    static final Decision ALLOWED=new Decision(true,0);

    private static final int MAX_KEYS=50_000;
    private final int burst, windowMax, windowSeconds;
    private final double refillPerSecond;
    private final long idleNanos;
    private final LongSupplier nanos;
    private final ConcurrentHashMap<String,State> states=new ConcurrentHashMap<>();
    private final AtomicInteger calls=new AtomicInteger();

    public RequestRateLimiter(int burst,int windowMax,Duration window,LongSupplier nanos) {
        if(burst<1 || windowMax<1 || window.toSeconds()<1 || window.toSeconds()>3600) throw new IllegalArgumentException("invalid rate limit");
        this.burst=burst; this.windowMax=windowMax; this.windowSeconds=(int)window.toSeconds();
        this.refillPerSecond=windowMax/(double)windowSeconds;
        this.idleNanos=2L*windowSeconds*1_000_000_000L;
        this.nanos=nanos;
    }

    public Decision tryAcquire(String key) {
        long now=nanos.getAsLong();
        if((calls.incrementAndGet()&1023)==0) evictIdle(now);
        State state=states.get(key);
        if(state==null) {
            if(states.size()>=MAX_KEYS) {
                evictIdle(now);
                if(states.size()>=MAX_KEYS) return ALLOWED;
            }
            state=states.computeIfAbsent(key,k->new State(now,burst,windowSeconds));
        }
        synchronized(state) { return acquire(state,now); }
    }

    private Decision acquire(State s,long now) {
        long second=Math.floorDiv(now,1_000_000_000L);
        s.tokens=Math.min(burst,s.tokens+Math.max(0,now-s.lastNanos)/1e9*refillPerSecond);
        s.lastNanos=now;
        advance(s,second);
        boolean windowFull=s.total>=windowMax;
        if(s.tokens>=1 && !windowFull) {
            s.tokens-=1; s.buckets[slot(second)]++; s.total++;
            return ALLOWED;
        }
        long retry=1;
        if(s.tokens<1) retry=Math.max(retry,(long)Math.ceil((1-s.tokens)/refillPerSecond));
        if(windowFull) retry=Math.max(retry,secondsUntilOldestExpires(s,second));
        return new Decision(false,retry);
    }

    private void advance(State s,long second) {
        if(s.lastSecond==Long.MIN_VALUE) { s.lastSecond=second; return; }
        long gap=second-s.lastSecond;
        if(gap>=windowSeconds) { Arrays.fill(s.buckets,0); s.total=0; }
        else for(long t=s.lastSecond+1;t<=second;t++) { int i=slot(t); s.total-=s.buckets[i]; s.buckets[i]=0; }
        s.lastSecond=Math.max(s.lastSecond,second);
    }

    private long secondsUntilOldestExpires(State s,long second) {
        for(long t=second-windowSeconds+1;t<=second;t++) if(s.buckets[slot(t)]>0) return Math.max(1,t+windowSeconds-second);
        return 1;
    }

    private int slot(long second) { return (int)Math.floorMod(second,(long)windowSeconds); }

    private void evictIdle(long now) { states.values().removeIf(s->now-s.lastNanos>idleNanos); }

    private static final class State {
        volatile long lastNanos;
        long lastSecond=Long.MIN_VALUE;
        double tokens;
        int total;
        final int[] buckets;
        State(long now,int burst,int windowSeconds) { lastNanos=now; tokens=burst; buckets=new int[windowSeconds]; }
    }
}
