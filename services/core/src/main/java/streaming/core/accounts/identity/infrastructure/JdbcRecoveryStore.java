package streaming.core.accounts.identity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.core.accounts.identity.application.RecoveryStore;

@Repository
public class JdbcRecoveryStore implements RecoveryStore {
    private final JdbcClient jdbc;
    public JdbcRecoveryStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public void lock(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "recovery:" + key)
                .query(rs -> { rs.next(); return true; });
    }

    @Override public Optional<String> operation(String operation, String keyHash, Instant now) {
        jdbc.sql("DELETE FROM identity.recovery_operations WHERE operation=:operation AND key_hash=:key AND retain_until_utc<=:now")
                .param("operation", operation).param("key", keyHash).param("now", ts(now)).update();
        return jdbc.sql("SELECT fingerprint FROM identity.recovery_operations WHERE operation=:operation AND key_hash=:key")
                .param("operation", operation).param("key", keyHash).query(String.class).optional();
    }

    @Override public void remember(String operation, String keyHash, String fingerprint, Instant until) {
        jdbc.sql("INSERT INTO identity.recovery_operations(operation,key_hash,fingerprint,retain_until_utc) VALUES (:operation,:key,:fingerprint,:until)")
                .param("operation", operation).param("key", keyHash).param("fingerprint", fingerprint).param("until", ts(until)).update();
    }

    @Override public boolean reserveRequest(String emailHash, String ipHash, Instant now) {
        lock("ip:" + ipHash);
        lock("email:" + emailHash);
        if (count("RECOVERY_IP", ipHash, now.minusSeconds(900)) >= 10
                || count("RECOVERY_EMAIL", emailHash, now.minusSeconds(3600)) >= 3) return false;
        rate("RECOVERY_IP", ipHash, now);
        rate("RECOVERY_EMAIL", emailHash, now);
        return true;
    }

    @Override public boolean reserveConfirmation(String ipHash, Instant now) {
        lock("ip:" + ipHash);
        if (count("RECOVERY_IP", ipHash, now.minusSeconds(900)) >= 10) return false;
        rate("RECOVERY_IP", ipHash, now);
        return true;
    }

    private long count(String bucket, String hash, Instant since) {
        return jdbc.sql("SELECT count(*) FROM identity.rate_limit_events WHERE bucket_type=:bucket AND bucket_hash=:hash AND occurred_at_utc>:since")
                .param("bucket", bucket).param("hash", hash).param("since", ts(since)).query(Long.class).single();
    }

    private void rate(String bucket, String hash, Instant now) {
        jdbc.sql("INSERT INTO identity.rate_limit_events(bucket_type,bucket_hash,occurred_at_utc) VALUES (:bucket,:hash,:now)")
                .param("bucket", bucket).param("hash", hash).param("now", ts(now)).update();
    }

    @Override public Optional<Account> accountByEmailForUpdate(String email) {
        return jdbc.sql("SELECT user_id,email FROM identity.accounts WHERE normalized_email=:email FOR UPDATE")
                .param("email", email).query((rs,n) -> new Account(rs.getString(1), rs.getString(2))).optional();
    }

    @Override public boolean lockAccount(String userId) {
        return jdbc.sql("SELECT user_id FROM identity.accounts WHERE user_id=:user FOR UPDATE")
                .param("user", userId).query(String.class).optional().isPresent();
    }

    @Override public Optional<Recovery> byTokenHash(String hash) {
        return jdbc.sql(SELECT + " WHERE token_hash=:hash").param("hash", hash).query(MAPPER).optional();
    }

    @Override public Optional<Recovery> recoveryForUpdate(String recoveryId, String deliveryId) {
        return jdbc.sql(SELECT + " WHERE recovery_id=:recovery AND delivery_id=:delivery FOR UPDATE")
                .param("recovery", recoveryId).param("delivery", deliveryId).query(MAPPER).optional();
    }

    @Override public void invalidate(String userId, Instant now) {
        jdbc.sql("UPDATE identity.credential_recoveries SET invalidated_at_utc=:now,encrypted_material=NULL,version=version+1 WHERE user_id=:user AND used_at_utc IS NULL AND invalidated_at_utc IS NULL")
                .param("user", userId).param("now", ts(now)).update();
    }

    @Override public void create(Recovery r, Instant now, String eventId, String eventJson) {
        jdbc.sql("""
                INSERT INTO identity.credential_recoveries
                    (recovery_id,delivery_id,user_id,token_hash,encrypted_material,created_at_utc,expires_at_utc)
                VALUES (:recovery,:delivery,:user,:hash,:material,:now,:expires)
                """)
                .param("recovery", r.recoveryId()).param("delivery", r.deliveryId()).param("user", r.userId())
                .param("hash", r.tokenHash()).param("material", r.encryptedMaterial()).param("now", ts(now))
                .param("expires", ts(r.expiresAt())).update();
        jdbc.sql("INSERT INTO identity.recovery_outbox(event_id,recovery_id,payload,created_at_utc,next_attempt_at_utc) VALUES (:event,:recovery,:payload,:now,:now)")
                .param("event", eventId).param("recovery", r.recoveryId()).param("payload", eventJson).param("now", ts(now)).update();
    }

    @Override public void consume(Recovery r, String passwordHash, Instant now) {
        jdbc.sql("UPDATE identity.accounts SET password_hash=:hash WHERE user_id=:user")
                .param("hash", passwordHash).param("user", r.userId()).update();
        jdbc.sql("UPDATE identity.sessions SET revoked_at_utc=:now WHERE user_id=:user AND revoked_at_utc IS NULL")
                .param("user", r.userId()).param("now", ts(now)).update();
        jdbc.sql("UPDATE identity.credential_recoveries SET used_at_utc=:now,encrypted_material=NULL,version=version+1 WHERE recovery_id=:id")
                .param("id", r.recoveryId()).param("now", ts(now)).update();
        invalidate(r.userId(), now);
    }

    @Override public void claim(String recoveryId, int attempt) {
        jdbc.sql("UPDATE identity.credential_recoveries SET delivery_state='CLAIMED',delivery_attempt=:attempt WHERE recovery_id=:id")
                .param("id", recoveryId).param("attempt", attempt).update();
    }

    @Override public void result(String recoveryId, String status) {
        jdbc.sql("""
                UPDATE identity.credential_recoveries SET delivery_state=:status,
                    encrypted_material=CASE WHEN :status IN ('DELIVERED','PERMANENT_FAILURE','UNKNOWN') THEN NULL ELSE encrypted_material END
                WHERE recovery_id=:id
                """)
                .param("id", recoveryId).param("status", status).update();
    }

    @Override public void purge(Instant now) {
        jdbc.sql("DELETE FROM identity.recovery_operations WHERE retain_until_utc<=:now").param("now", ts(now)).update();
        jdbc.sql("UPDATE identity.credential_recoveries SET encrypted_material=NULL WHERE expires_at_utc<=:now AND encrypted_material IS NOT NULL")
                .param("now", ts(now)).update();
    }

    @Override public List<Publication> leasePublications(UUID leaseId, Instant now, Instant until, int limit) {
        return jdbc.sql("""
                WITH pending AS (
                    SELECT event_id FROM identity.recovery_outbox
                    WHERE published_at_utc IS NULL AND next_attempt_at_utc<=:now
                      AND (lease_until_utc IS NULL OR lease_until_utc<=:now)
                    ORDER BY created_at_utc LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                UPDATE identity.recovery_outbox o SET lease_id=:lease,lease_until_utc=:until,attempts=o.attempts+1
                FROM pending WHERE o.event_id=pending.event_id RETURNING o.event_id,o.payload,o.attempts
                """)
                .param("lease", leaseId).param("now", ts(now)).param("until", ts(until)).param("limit", limit)
                .query((rs,n) -> new Publication(rs.getString(1), rs.getString(2), rs.getInt(3))).list();
    }

    @Override public void published(String eventId, UUID leaseId, Instant now) {
        jdbc.sql("UPDATE identity.recovery_outbox SET published_at_utc=:now,lease_id=NULL,lease_until_utc=NULL WHERE event_id=:event AND lease_id=:lease")
                .param("event", eventId).param("lease", leaseId).param("now", ts(now)).update();
    }

    @Override public void retry(String eventId, UUID leaseId, Instant nextAttempt) {
        jdbc.sql("UPDATE identity.recovery_outbox SET next_attempt_at_utc=:next,lease_id=NULL,lease_until_utc=NULL WHERE event_id=:event AND lease_id=:lease AND published_at_utc IS NULL")
                .param("event", eventId).param("lease", leaseId).param("next", ts(nextAttempt)).update();
    }

    private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }
    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private static final String SELECT = "SELECT recovery_id,delivery_id,user_id,token_hash,encrypted_material,expires_at_utc,used_at_utc,invalidated_at_utc,version,delivery_state,delivery_attempt FROM identity.credential_recoveries";
    private static final RowMapper<Recovery> MAPPER = (rs,n) -> new Recovery(rs.getString("recovery_id"),
            rs.getString("delivery_id"), rs.getString("user_id"), rs.getString("token_hash"), rs.getString("encrypted_material"),
            instant(rs,"expires_at_utc"), instant(rs,"used_at_utc"), instant(rs,"invalidated_at_utc"),
            rs.getLong("version"), rs.getString("delivery_state"), rs.getInt("delivery_attempt"));
}
