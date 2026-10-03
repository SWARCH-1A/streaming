package streaming.core.accounts.identity.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.accounts.identity.application.IdentityStore;

@Repository
public class JdbcIdentityStore implements IdentityStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    public JdbcIdentityStore(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc=jdbc; this.transactions=transactions;
    }

    @Override public Optional<Registration> registrationByKeyHash(String hash) {
        return jdbc.sql(REGISTRATION_SELECT + " WHERE idempotency_key_hash=:hash")
                .param("hash", hash).query(REGISTRATION_MAPPER).optional();
    }
    @Override public Optional<Registration> registration(String id, String keyHash) {
        return jdbc.sql(REGISTRATION_SELECT + " WHERE registration_id=:id AND idempotency_key_hash=:hash")
                .param("id", id).param("hash", keyHash).query(REGISTRATION_MAPPER).optional();
    }
    @Override public void createAccount(RegistrationDraft d) {
        jdbc.sql("INSERT INTO identity.accounts(user_id,email,normalized_email,handle,canonical_handle,password_hash,created_at_utc) VALUES (:user,:email,:emailNorm,:handle,:handle,:password,:now)")
                .param("user",d.userId()).param("email",d.email()).param("emailNorm",d.normalizedEmail())
                .param("handle",d.handle()).param("password",d.passwordHash()).param("now",java.sql.Timestamp.from(d.now())).update();
    }
    @Override public Registration saveRegistration(RegistrationDraft d,String channelId) {
        jdbc.sql("INSERT INTO identity.registrations(registration_id,idempotency_key_hash,request_fingerprint,user_id,channel_id,created_at_utc,retain_until_utc) VALUES (:id,:key,:fingerprint,:user,:channel,:now,:retain)")
                .param("id",d.registrationId()).param("key",d.idempotencyKeyHash()).param("fingerprint",d.fingerprint())
                .param("user",d.userId()).param("channel",channelId).param("now",java.sql.Timestamp.from(d.now())).param("retain",java.sql.Timestamp.from(d.retainUntil())).update();
        return registrationByKeyHash(d.idempotencyKeyHash()).orElseThrow();
    }
    @Override public Optional<PublicIdentity> activeByUserId(String userId) {
        return jdbc.sql("SELECT user_id,handle FROM identity.accounts WHERE user_id=:id")
                .param("id",userId).query((rs,n)->new PublicIdentity(rs.getString(1),rs.getString(2))).optional();
    }
    @Override public Optional<PublicIdentity> activeByHandle(String handle) {
        return jdbc.sql("SELECT user_id,handle FROM identity.accounts WHERE canonical_handle=:handle")
                .param("handle",handle).query((rs,n)->new PublicIdentity(rs.getString(1),rs.getString(2))).optional();
    }
    @Override public Optional<Account> activeByLogin(String login, boolean email) {
        String sql=email ? "SELECT user_id,handle,password_hash FROM identity.accounts WHERE normalized_email=:value"
                         : "SELECT user_id,handle,password_hash FROM identity.accounts WHERE canonical_handle=:value";
        return jdbc.sql(sql).param("value",login).query((rs,n)->new Account(rs.getString(1),rs.getString(2),rs.getString(3))).optional();
    }
    @Override public boolean tryRecordRegistrationKey(String ipHash, String keyHash, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            lockBucket("registration-ip:"+ipHash);
            int repeated=jdbc.sql("SELECT count(*) FROM identity.rate_limit_events WHERE bucket_type='REGISTRATION_IP' AND bucket_hash=:ip AND source_key_hash=:key")
                    .param("ip",ipHash).param("key",keyHash).query(Integer.class).single();
            if(repeated>0) return true;
            int count=jdbc.sql("SELECT count(*) FROM identity.rate_limit_events WHERE bucket_type='REGISTRATION_IP' AND bucket_hash=:hash AND occurred_at_utc>:since")
                    .param("hash",ipHash).param("since",java.sql.Timestamp.from(now.minusSeconds(3600))).query(Integer.class).single();
            if (count>=10) return false;
            jdbc.sql("INSERT INTO identity.rate_limit_events(bucket_type,bucket_hash,source_key_hash,occurred_at_utc) VALUES ('REGISTRATION_IP',:hash,:key,:now)")
                    .param("hash",ipHash).param("key",keyHash).param("now",java.sql.Timestamp.from(now)).update();
            return true;
        }));
    }
    @Override public boolean loginLimitReached(String identifierHash, String ipHash, Instant since) {
        int idCount=jdbc.sql("SELECT count(*) FROM identity.rate_limit_events WHERE bucket_type='LOGIN_IDENTIFIER' AND bucket_hash=:hash AND occurred_at_utc>:since")
                .param("hash",identifierHash).param("since",java.sql.Timestamp.from(since)).query(Integer.class).single();
        int ipCount=jdbc.sql("SELECT count(*) FROM identity.rate_limit_events WHERE bucket_type='LOGIN_IP' AND bucket_hash=:hash AND occurred_at_utc>:since")
                .param("hash",ipHash).param("since",java.sql.Timestamp.from(since)).query(Integer.class).single();
        return idCount>=5 || ipCount>=50;
    }
    @Override public boolean tryRecordLoginFailure(String identifierHash, String ipHash, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            lockBucket("login-id:"+identifierHash); lockBucket("login-ip:"+ipHash);
            if (loginLimitReached(identifierHash,ipHash,now.minusSeconds(900))) return false;
            jdbc.sql("INSERT INTO identity.rate_limit_events(bucket_type,bucket_hash,occurred_at_utc) VALUES ('LOGIN_IDENTIFIER',:id,:now),('LOGIN_IP',:ip,:now)")
                    .param("id",identifierHash).param("ip",ipHash).param("now",java.sql.Timestamp.from(now)).update();
            return true;
        }));
    }
    @Override public void resetIdentifierFailures(String identifierHash) {
        jdbc.sql("DELETE FROM identity.rate_limit_events WHERE bucket_type='LOGIN_IDENTIFIER' AND bucket_hash=:hash")
                .param("hash",identifierHash).update();
    }
    @Override public void createSession(String userId, String credentialHash, Instant created, Instant expires) {
        jdbc.sql("INSERT INTO identity.sessions(session_id,user_id,credential_hash,created_at_utc,expires_at_utc) VALUES (:id,:user,:hash,:created,:expires)")
                .param("id",UUID.randomUUID()).param("user",userId).param("hash",credentialHash).param("created",java.sql.Timestamp.from(created)).param("expires",java.sql.Timestamp.from(expires)).update();
    }
    @Override public Optional<SessionIdentity> introspect(String credentialHash, Instant now) {
        return jdbc.sql("SELECT s.user_id,a.handle,s.expires_at_utc FROM identity.sessions s JOIN identity.accounts a ON a.user_id=s.user_id WHERE s.credential_hash=:hash AND s.revoked_at_utc IS NULL AND s.expires_at_utc>:now")
                .param("hash",credentialHash).param("now",java.sql.Timestamp.from(now)).query((rs,n)->new SessionIdentity(rs.getString(1),rs.getString(2),rs.getTimestamp(3).toInstant())).optional();
    }
    @Override public void revokeSession(String credentialHash, Instant now) {
        jdbc.sql("UPDATE identity.sessions SET revoked_at_utc=:now WHERE credential_hash=:hash AND revoked_at_utc IS NULL")
                .param("now",java.sql.Timestamp.from(now)).param("hash",credentialHash).update();
    }
    @Override public void purgeExpired(Instant now) {
        jdbc.sql("DELETE FROM identity.sessions WHERE expires_at_utc<=:now OR revoked_at_utc<:cutoff")
                .param("now",java.sql.Timestamp.from(now)).param("cutoff",java.sql.Timestamp.from(now.minusSeconds(7L*24*3600))).update();
        jdbc.sql("DELETE FROM identity.rate_limit_events WHERE occurred_at_utc<:cutoff")
                .param("cutoff",java.sql.Timestamp.from(now.minusSeconds(24*3600))).update();
        jdbc.sql("DELETE FROM identity.registrations WHERE retain_until_utc<=:now")
                .param("now",java.sql.Timestamp.from(now)).update();
    }
    private void lockBucket(String key) { jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key",key).query(rs -> { rs.next(); return Boolean.TRUE; }); }

    private static final String REGISTRATION_SELECT="SELECT registration_id,idempotency_key_hash,request_fingerprint,user_id,channel_id FROM identity.registrations";
    private static final RowMapper<Registration> REGISTRATION_MAPPER=(rs,n)->new Registration(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5));
}
