package streaming.core.channels.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.core.channels.application.FollowStore;

@Repository
public class JdbcFollows implements FollowStore {
    private static final String FOLLOW_COLUMNS="follower_user_id,channel_id,following,follow_version,followed_at_utc,updated_at_utc";
    private static final String SNAPSHOT_COLUMNS="snapshot_id,channel_id,session_id,captured_at_utc,expires_at_utc,recipient_count";
    private final JdbcClient jdbc;

    public JdbcFollows(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Override public Optional<String> channelOwner(String channelId) {
        return jdbc.sql("SELECT owner_user_id FROM channels.channels WHERE channel_id=:id").param("id",channelId).query(String.class).optional();
    }

    @Override public void lockOperation(String userId,String keyHash) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))").param("key","channels.follow-op|"+userId+"|"+keyHash).query().singleRow();
    }

    @Override public Optional<Operation> operation(String userId,String keyHash,Instant now) {
        return jdbc.sql("SELECT fingerprint,response FROM channels.follow_operations WHERE user_id=:user AND key_hash=:key AND retain_until_utc>:now")
                .param("user",userId).param("key",keyHash).param("now",ts(now))
                .query((rs,n)->new Operation(rs.getString(1),rs.getString(2))).optional();
    }

    @Override public void rememberOperation(String userId,String keyHash,String fingerprint,String response,Instant retainUntil) {
        // An expired row with the same key may still exist until the purge removes it.
        jdbc.sql("INSERT INTO channels.follow_operations(user_id,key_hash,fingerprint,response,retain_until_utc) VALUES (:user,:key,:fp,:response,:until) "
                +"ON CONFLICT (user_id,key_hash) DO UPDATE SET fingerprint=EXCLUDED.fingerprint,response=EXCLUDED.response,retain_until_utc=EXCLUDED.retain_until_utc")
                .param("user",userId).param("key",keyHash).param("fp",fingerprint).param("response",response).param("until",ts(retainUntil)).update();
    }

    @Override public Optional<Follow> find(String userId,String channelId) {
        return jdbc.sql("SELECT "+FOLLOW_COLUMNS+" FROM channels.follows WHERE follower_user_id=:user AND channel_id=:channel")
                .param("user",userId).param("channel",channelId).query(JdbcFollows::follow).optional();
    }

    @Override public Optional<Follow> follow(String userId,String channelId,Instant now) {
        // The upsert takes the row lock, so two concurrent PUTs produce exactly one transition.
        return jdbc.sql("INSERT INTO channels.follows AS f("+FOLLOW_COLUMNS+") VALUES (:user,:channel,true,1,:now,:now) "
                +"ON CONFLICT (follower_user_id,channel_id) DO UPDATE SET following=true,follow_version=f.follow_version+1,"
                +"followed_at_utc=:now,updated_at_utc=:now WHERE NOT f.following RETURNING "+FOLLOW_COLUMNS)
                .param("user",userId).param("channel",channelId).param("now",ts(now)).query(JdbcFollows::follow).optional();
    }

    @Override public Optional<Follow> unfollow(String userId,String channelId,Instant now) {
        return jdbc.sql("UPDATE channels.follows SET following=false,follow_version=follow_version+1,followed_at_utc=NULL,updated_at_utc=:now "
                +"WHERE follower_user_id=:user AND channel_id=:channel AND following RETURNING "+FOLLOW_COLUMNS)
                .param("user",userId).param("channel",channelId).param("now",ts(now)).query(JdbcFollows::follow).optional();
    }

    @Override public void enqueue(UUID eventId,Follow follow,String payload,Instant now) {
        jdbc.sql("INSERT INTO channels.follow_outbox(event_id,follower_user_id,channel_id,follow_version,payload,created_at_utc,next_attempt_at_utc) "
                +"VALUES (:event,:user,:channel,:version,:payload,:now,:now)")
                .param("event",eventId).param("user",follow.userId()).param("channel",follow.channelId()).param("version",follow.version())
                .param("payload",payload).param("now",ts(now)).update();
    }

    @Override public List<Follow> followed(String userId,Position after,int limit) {
        if(after==null) return jdbc.sql("SELECT "+FOLLOW_COLUMNS+" FROM channels.follows WHERE follower_user_id=:user AND following "
                +"ORDER BY followed_at_utc DESC,channel_id DESC LIMIT :limit")
                .param("user",userId).param("limit",limit).query(JdbcFollows::follow).list();
        return jdbc.sql("SELECT "+FOLLOW_COLUMNS+" FROM channels.follows WHERE follower_user_id=:user AND following "
                +"AND (followed_at_utc,channel_id)<(:at,:channel) ORDER BY followed_at_utc DESC,channel_id DESC LIMIT :limit")
                .param("user",userId).param("at",ts(after.followedAt())).param("channel",after.channelId()).param("limit",limit)
                .query(JdbcFollows::follow).list();
    }

    @Override public Optional<Snapshot> snapshot(String channelId,String sessionId) {
        return jdbc.sql("SELECT "+SNAPSHOT_COLUMNS+" FROM channels.recipient_snapshots WHERE channel_id=:channel AND session_id=:session")
                .param("channel",channelId).param("session",sessionId).query(JdbcFollows::snapshot).optional();
    }

    @Override public Optional<Snapshot> snapshotById(String snapshotId) {
        return jdbc.sql("SELECT "+SNAPSHOT_COLUMNS+" FROM channels.recipient_snapshots WHERE snapshot_id=:id")
                .param("id",snapshotId).query(JdbcFollows::snapshot).optional();
    }

    @Override public Snapshot captureSnapshot(String snapshotId,String channelId,String sessionId,Instant now,Instant expires) {
        jdbc.sql("DELETE FROM channels.recipient_snapshots WHERE channel_id=:channel AND session_id=:session AND expires_at_utc<=:now")
                .param("channel",channelId).param("session",sessionId).param("now",ts(now)).update();
        // A concurrent first page waits on the unique key and then reads the committed cut instead of a second one.
        boolean created=jdbc.sql("INSERT INTO channels.recipient_snapshots("+SNAPSHOT_COLUMNS+") VALUES (:id,:channel,:session,:now,:expires,0) "
                +"ON CONFLICT (channel_id,session_id) DO NOTHING")
                .param("id",snapshotId).param("channel",channelId).param("session",sessionId).param("now",ts(now)).param("expires",ts(expires)).update()==1;
        if(created) {
            int count=jdbc.sql("INSERT INTO channels.recipient_snapshot_members(snapshot_id,user_id) "
                    +"SELECT :id,follower_user_id FROM channels.follows WHERE channel_id=:channel AND following")
                    .param("id",snapshotId).param("channel",channelId).update();
            jdbc.sql("UPDATE channels.recipient_snapshots SET recipient_count=:count WHERE snapshot_id=:id").param("count",count).param("id",snapshotId).update();
        }
        return snapshot(channelId,sessionId).orElseThrow();
    }

    @Override public List<String> recipients(String snapshotId,String afterUserId,int limit) {
        return jdbc.sql("SELECT user_id FROM channels.recipient_snapshot_members WHERE snapshot_id=:id AND (CAST(:after AS VARCHAR) IS NULL OR user_id>:after) "
                +"ORDER BY user_id LIMIT :limit")
                .param("id",snapshotId).param("after",afterUserId).param("limit",limit).query(String.class).list();
    }

    @Override public VodSettings vodSettings(String channelId) {
        return jdbc.sql("SELECT channel_id,autopublish_enabled,autopublish_version FROM channels.channels WHERE channel_id=:id")
                .param("id",channelId).query(JdbcFollows::settings).single();
    }

    @Override public Optional<VodSettings> updateVodSettings(String channelId,boolean enabled,long expectedVersion) {
        return jdbc.sql("UPDATE channels.channels SET autopublish_enabled=:enabled,"
                +"autopublish_version=autopublish_version+CASE WHEN autopublish_enabled<>:enabled THEN 1 ELSE 0 END "
                +"WHERE channel_id=:id AND autopublish_version=:expected RETURNING channel_id,autopublish_enabled,autopublish_version")
                .param("enabled",enabled).param("id",channelId).param("expected",expectedVersion).query(JdbcFollows::settings).optional();
    }

    @Override public List<Publication> leasePublications(UUID leaseId,Instant now,Instant until,int limit) {
        return jdbc.sql("""
                WITH pending AS (
                    SELECT event_id FROM channels.follow_outbox
                    WHERE published_at_utc IS NULL AND next_attempt_at_utc<=:now
                      AND (lease_until_utc IS NULL OR lease_until_utc<=:now)
                    ORDER BY created_at_utc LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                UPDATE channels.follow_outbox o SET lease_id=:lease,lease_until_utc=:until,attempts=o.attempts+1
                FROM pending WHERE o.event_id=pending.event_id RETURNING o.event_id,o.payload,o.attempts
                """)
                .param("lease",leaseId).param("now",ts(now)).param("until",ts(until)).param("limit",limit)
                .query((rs,n)->new Publication(rs.getObject(1,UUID.class),rs.getString(2),rs.getInt(3))).list();
    }

    @Override public void published(UUID eventId,UUID leaseId,Instant now) {
        jdbc.sql("UPDATE channels.follow_outbox SET published_at_utc=:now,lease_id=NULL,lease_until_utc=NULL WHERE event_id=:event AND lease_id=:lease")
                .param("event",eventId).param("lease",leaseId).param("now",ts(now)).update();
    }

    @Override public void retry(UUID eventId,UUID leaseId,Instant nextAttempt) {
        jdbc.sql("UPDATE channels.follow_outbox SET next_attempt_at_utc=:next,lease_id=NULL,lease_until_utc=NULL WHERE event_id=:event AND lease_id=:lease AND published_at_utc IS NULL")
                .param("event",eventId).param("lease",leaseId).param("next",ts(nextAttempt)).update();
    }

    @Override public void purge(Instant now) {
        jdbc.sql("DELETE FROM channels.follow_operations WHERE retain_until_utc<=:now").param("now",ts(now)).update();
        jdbc.sql("DELETE FROM channels.recipient_snapshots WHERE expires_at_utc<=:now").param("now",ts(now)).update();
        // Confirmed events are kept a week for diagnosis; unconfirmed ones are never purged.
        jdbc.sql("DELETE FROM channels.follow_outbox WHERE published_at_utc<=:limit").param("limit",ts(now.minus(java.time.Duration.ofDays(7)))).update();
    }

    private static Follow follow(ResultSet rs,int n) throws SQLException {
        Timestamp followed=rs.getTimestamp(5);
        return new Follow(rs.getString(1),rs.getString(2),rs.getBoolean(3),rs.getLong(4),followed==null?null:followed.toInstant(),rs.getTimestamp(6).toInstant());
    }
    private static Snapshot snapshot(ResultSet rs,int n) throws SQLException {
        return new Snapshot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant(),rs.getTimestamp(5).toInstant(),rs.getInt(6));
    }
    private static VodSettings settings(ResultSet rs,int n) throws SQLException {
        return new VodSettings(rs.getString(1),rs.getBoolean(2),rs.getLong(3));
    }
    private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }
}
