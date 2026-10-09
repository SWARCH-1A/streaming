package streaming.core.discovery.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.discovery.domain.RequestLimiter;

/** Atomic token bucket plus exact rolling cap, using PostgreSQL time and one row lock per hashed key. */
public final class SqlRequestLimiter implements RequestLimiter {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final int burst,max;
    private final Duration window;
    private final byte[] secret;
    public SqlRequestLimiter(JdbcClient jdbc,TransactionTemplate transactions,int burst,int max,Duration window,String secret) {
        if(burst<1 || max<1 || window.toSeconds()<1 || window.toSeconds()>3600) throw new IllegalArgumentException("invalid rate limit");
        if(secret==null || secret.length()<32) throw new IllegalArgumentException("shared rate-limit secret required");
        this.jdbc=jdbc;this.transactions=transactions;this.burst=burst;this.max=max;this.window=window;
        this.secret=secret.getBytes(StandardCharsets.UTF_8);
    }
    @Override public Decision tryAcquire(String key) {
        String hash=hash(key);
        return transactions.execute(status -> {
            // Touch takes the existing row lock too: idle cleanup cannot delete a newly active key between statements.
            jdbc.sql("INSERT INTO discovery.rate_buckets(bucket_hash,tokens,refilled_at,touched_at) VALUES (:key,:tokens,clock_timestamp(),clock_timestamp()) ON CONFLICT (bucket_hash) DO UPDATE SET touched_at=clock_timestamp()")
                    .param("key",hash).param("tokens",burst).update();
            var bucket=jdbc.sql("SELECT tokens,refilled_at FROM discovery.rate_buckets WHERE bucket_hash=:key FOR UPDATE")
                    .param("key",hash).query((rs,n)->new Bucket(rs.getDouble(1),rs.getTimestamp(2).toInstant())).single();
            Instant observed=jdbc.sql("SELECT clock_timestamp()").query((rs,n)->rs.getTimestamp(1).toInstant()).single();
            // A backwards SQL clock never refills tokens or shortens the already observed rolling window.
            Instant now=observed.isBefore(bucket.refilled())?bucket.refilled():observed;
            double tokens=Math.min(burst,bucket.tokens()+Duration.between(bucket.refilled(),now).toNanos()/1e9*max/window.toSeconds());
            Instant since=now.minus(window);
            jdbc.sql("DELETE FROM discovery.rate_events WHERE bucket_hash=:key AND admitted_at<:since")
                    .param("key",hash).param("since",java.sql.Timestamp.from(since)).update();
            var usage=jdbc.sql("SELECT count(*),min(admitted_at) FROM discovery.rate_events WHERE bucket_hash=:key")
                    .param("key",hash).query((rs,n)->new Usage(rs.getInt(1),rs.getTimestamp(2)==null?null:rs.getTimestamp(2).toInstant())).single();
            boolean allowed=tokens>=1 && usage.count()<max;
            long retry=1;
            if(tokens<1) retry=Math.max(retry,(long)Math.ceil((1-tokens)*window.toSeconds()/max));
            if(usage.count()>=max) retry=Math.max(retry,(long)Math.ceil(Duration.between(now,usage.oldest().plus(window)).toNanos()/1e9));
            if(allowed) {
                tokens-=1;
                jdbc.sql("INSERT INTO discovery.rate_events(bucket_hash,admitted_at) VALUES (:key,:now)")
                        .param("key",hash).param("now",java.sql.Timestamp.from(now)).update();
            }
            jdbc.sql("UPDATE discovery.rate_buckets SET tokens=:tokens,refilled_at=:now,touched_at=:now WHERE bucket_hash=:key")
                    .param("tokens",tokens).param("now",java.sql.Timestamp.from(now)).param("key",hash).update();
            // Bounded idle cleanup; SKIP LOCKED does not block active keys on other replicas.
            jdbc.sql("WITH idle AS (SELECT bucket_hash FROM discovery.rate_buckets WHERE touched_at<:before ORDER BY touched_at LIMIT 100 FOR UPDATE SKIP LOCKED) DELETE FROM discovery.rate_buckets b USING idle WHERE b.bucket_hash=idle.bucket_hash")
                    .param("before",java.sql.Timestamp.from(observed.minus(window.multipliedBy(2)))).update();
            return new Decision(allowed,allowed?0:retry);
        });
    }
    private String hash(String key) {
        try {
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("discovery:"+key).getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException e) { throw new IllegalStateException("HMAC unavailable"); }
    }
    private record Bucket(double tokens,Instant refilled) { }
    private record Usage(int count,Instant oldest) { }
}
