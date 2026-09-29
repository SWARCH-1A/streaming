package streaming.identity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.identity.application.IdentityStore;

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
    @Override public Registration reserve(RegistrationDraft d) {
        return transactions.execute(status -> {
            jdbc.sql("INSERT INTO identity.registrations(registration_id,idempotency_key_hash,request_fingerprint,email,normalized_email,handle,password_hash,pending_since_utc,pending_until_utc,status,user_id,next_attempt_at,retain_until_utc) VALUES (:id,:key,:fingerprint,:email,:emailNorm,:handle,:password,:now,:deadline,'PENDING',:user,:now,:retain)")
                    .param("id",d.registrationId()).param("key",d.idempotencyKeyHash()).param("fingerprint",d.fingerprint())
                    .param("email",d.email()).param("emailNorm",d.normalizedEmail()).param("handle",d.handle())
                    .param("password",d.passwordHash()).param("now",d.now()).param("deadline",d.deadline())
                    .param("user",d.userId()).param("retain",d.retainUntil()).update();
            jdbc.sql("INSERT INTO identity.reservations(normalized_email,canonical_handle,registration_id,user_id) VALUES (:email,:handle,:registration,:user)")
                    .param("email",d.normalizedEmail()).param("handle",d.handle()).param("registration",d.registrationId()).param("user",d.userId()).update();
            return registrationByKeyHash(d.idempotencyKeyHash()).orElseThrow();
        });
    }
    @Override public List<Registration> dueRegistrations(Instant now, int limit) {
        return jdbc.sql(REGISTRATION_SELECT + " WHERE status='PENDING' AND next_attempt_at<=:now ORDER BY next_attempt_at LIMIT :limit")
                .param("now",now).param("limit",limit).query(REGISTRATION_MAPPER).list();
    }
    @Override public Optional<Registration> pendingRegistration(String id) {
        return jdbc.sql(REGISTRATION_SELECT + " WHERE registration_id=:id AND status='PENDING'")
                .param("id",id).query(REGISTRATION_MAPPER).optional();
    }
    @Override public boolean activate(String id, String channelId, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Optional<Registration> found = jdbc.sql(REGISTRATION_SELECT + " WHERE registration_id=:id FOR UPDATE")
                    .param("id",id).query(REGISTRATION_MAPPER).optional();
            if (found.isEmpty() || !"PENDING".equals(found.get().status()) || !now.isBefore(found.get().deadline())) return false;
            Registration r=found.get();
            jdbc.sql("INSERT INTO identity.accounts(user_id,email,normalized_email,handle,canonical_handle,password_hash,created_at_utc) VALUES (:user,:email,:emailNorm,:handle,:canonical,:password,:now)")
                    .param("user",r.userId()).param("email",r.email()).param("emailNorm",r.normalizedEmail())
                    .param("handle",r.handle()).param("canonical",r.handle()).param("password",r.passwordHash()).param("now",now).update();
            int changed=jdbc.sql("UPDATE identity.registrations SET status='ACTIVE',channel_id=:channel,retain_until_utc=:retain,email=NULL,normalized_email=NULL,handle=NULL,password_hash=NULL WHERE registration_id=:id AND status='PENDING' AND pending_until_utc>clock_timestamp()")
                    .param("channel",channelId).param("retain",now.plusSeconds(30L*24*3600)).param("id",id).param("now",now).update();
            if (changed != 1) throw new IllegalStateException("La activación perdió la cerca de vencimiento.");
            String payload="{\"userId\":\""+r.userId()+"\",\"handle\":\""+r.handle()+"\"}";
            jdbc.sql("INSERT INTO identity.outbox_events(event_id,event_type,schema_version,aggregate_id,sequence_no,occurred_at_utc,payload) VALUES (:event,'IdentityPublicChanged',1,:aggregate,1,:now,CAST(:payload AS jsonb))")
                    .param("event",UUID.randomUUID()).param("aggregate","identity:"+r.userId()).param("now",now).param("payload",payload).update();
            return true;
        }));
    }
    @Override public boolean expire(String id, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int changed=jdbc.sql("UPDATE identity.registrations SET status='EXPIRED',next_attempt_at=:now,retain_until_utc=:retain,email=NULL,normalized_email=NULL,handle=NULL,password_hash=NULL WHERE registration_id=:id AND status='PENDING' AND pending_until_utc<=:now")
                    .param("now",now).param("retain",now.plusSeconds(30L*24*3600)).param("id",id).update();
            if (changed == 1) jdbc.sql("DELETE FROM identity.reservations WHERE registration_id=:id").param("id",id).update();
            return changed == 1;
        }));
    }
    @Override public void markCompensationComplete(String id) {
        jdbc.sql("UPDATE identity.registrations SET compensation_complete=TRUE WHERE registration_id=:id AND status='EXPIRED'")
                .param("id",id).update();
    }
    @Override public void scheduleRetry(String id, Instant nextAt) {
        jdbc.sql("UPDATE identity.registrations SET attempt_count=attempt_count+1,next_attempt_at=:next WHERE registration_id=:id AND status='PENDING'")
                .param("next",nextAt).param("id",id).update();
    }
    @Override public List<Registration> expiredNeedingCompensation(Instant now, int limit) {
        return jdbc.sql(REGISTRATION_SELECT + " WHERE status='EXPIRED' AND compensation_complete=FALSE AND pending_until_utc<=:now ORDER BY pending_until_utc LIMIT :limit")
                .param("now",now).param("limit",limit).query(REGISTRATION_MAPPER).list();
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
                    .param("hash",ipHash).param("since",now.minusSeconds(3600)).query(Integer.class).single();
            if (count>=10) return false;
            jdbc.sql("INSERT INTO identity.rate_limit_events(bucket_type,bucket_hash,source_key_hash,occurred_at_utc) VALUES ('REGISTRATION_IP',:hash,:key,:now)")
                    .param("hash",ipHash).param("key",keyHash).param("now",now).update();
            return true;
        }));
    }
    @Override public boolean loginLimitReached(String identifierHash, String ipHash, Instant since) {
        int idCount=jdbc.sql("SELECT count(*) FROM identity.rate_limit_events WHERE bucket_type='LOGIN_IDENTIFIER' AND bucket_hash=:hash AND occurred_at_utc>:since")
                .param("hash",identifierHash).param("since",since).query(Integer.class).single();
        int ipCount=jdbc.sql("SELECT count(*) FROM identity.rate_limit_events WHERE bucket_type='LOGIN_IP' AND bucket_hash=:hash AND occurred_at_utc>:since")
                .param("hash",ipHash).param("since",since).query(Integer.class).single();
        return idCount>=5 || ipCount>=50;
    }
    @Override public boolean tryRecordLoginFailure(String identifierHash, String ipHash, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            lockBucket("login-id:"+identifierHash); lockBucket("login-ip:"+ipHash);
            if (loginLimitReached(identifierHash,ipHash,now.minusSeconds(900))) return false;
            jdbc.sql("INSERT INTO identity.rate_limit_events(bucket_type,bucket_hash,occurred_at_utc) VALUES ('LOGIN_IDENTIFIER',:id,:now),('LOGIN_IP',:ip,:now)")
                    .param("id",identifierHash).param("ip",ipHash).param("now",now).update();
            return true;
        }));
    }
    @Override public void resetIdentifierFailures(String identifierHash) {
        jdbc.sql("DELETE FROM identity.rate_limit_events WHERE bucket_type='LOGIN_IDENTIFIER' AND bucket_hash=:hash")
                .param("hash",identifierHash).update();
    }
    @Override public void createSession(String userId, String credentialHash, Instant created, Instant expires) {
        jdbc.sql("INSERT INTO identity.sessions(session_id,user_id,credential_hash,created_at_utc,expires_at_utc) VALUES (:id,:user,:hash,:created,:expires)")
                .param("id",UUID.randomUUID()).param("user",userId).param("hash",credentialHash).param("created",created).param("expires",expires).update();
    }
    @Override public Optional<SessionIdentity> introspect(String credentialHash, Instant now) {
        return jdbc.sql("SELECT s.user_id,a.handle,s.expires_at_utc FROM identity.sessions s JOIN identity.accounts a ON a.user_id=s.user_id WHERE s.credential_hash=:hash AND s.revoked_at_utc IS NULL AND s.expires_at_utc>:now")
                .param("hash",credentialHash).param("now",now).query((rs,n)->new SessionIdentity(rs.getString(1),rs.getString(2),rs.getTimestamp(3).toInstant())).optional();
    }
    @Override public void revokeSession(String credentialHash, Instant now) {
        jdbc.sql("UPDATE identity.sessions SET revoked_at_utc=:now WHERE credential_hash=:hash AND revoked_at_utc IS NULL")
                .param("now",now).param("hash",credentialHash).update();
    }
    @Override public void purgeExpired(Instant now) {
        jdbc.sql("DELETE FROM identity.sessions WHERE expires_at_utc<=:now OR revoked_at_utc<:cutoff")
                .param("now",now).param("cutoff",now.minusSeconds(7L*24*3600)).update();
        jdbc.sql("DELETE FROM identity.rate_limit_events WHERE occurred_at_utc<:cutoff")
                .param("cutoff",now.minusSeconds(24*3600)).update();
        jdbc.sql("DELETE FROM identity.registrations WHERE retain_until_utc<=:now AND status IN ('ACTIVE','EXPIRED')")
                .param("now",now).update();
    }
    private void lockBucket(String key) { jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key",key).query(rs -> { rs.next(); return null; }); }

    private static final String REGISTRATION_SELECT="SELECT registration_id,idempotency_key_hash AS key_hash,request_fingerprint,email,normalized_email,handle,password_hash,status,user_id,channel_id,pending_since_utc,pending_until_utc,compensation_complete,attempt_count FROM identity.registrations";
    private static final RowMapper<Registration> REGISTRATION_MAPPER=(rs,n)->mapRegistration(rs);
    private static Registration mapRegistration(ResultSet r) throws SQLException {
        return new Registration(r.getString("registration_id"),r.getString("key_hash"),r.getString("request_fingerprint"),r.getString("status"),r.getString("user_id"),r.getString("channel_id"),r.getTimestamp("pending_since_utc").toInstant(),r.getTimestamp("pending_until_utc").toInstant(),r.getString("email"),r.getString("normalized_email"),r.getString("handle"),r.getString("password_hash"),r.getInt("attempt_count"));
    }
}
