package streaming.core.accounts.profile.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.accounts.profile.application.ProfileStore;
import streaming.core.accounts.profile.application.ProfileInitializer;

@Repository
public class JdbcProfileStore implements ProfileStore, ProfileInitializer {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    public JdbcProfileStore(JdbcClient jdbc,TransactionTemplate transactions) { this.jdbc=jdbc; this.transactions=transactions; }
    @Override public Optional<Profile> find(String userId) { return jdbc.sql("SELECT user_id,display_name,bio,avatar_key,profile_version,created_at_utc,updated_at_utc FROM profile.profiles WHERE user_id=:user")
            .param("user",userId).query((rs,n)->new Profile(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7).toInstant())).optional(); }
    @Override public void lockUser(String userId) { jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key","profile:"+userId).query(rs -> { rs.next(); return Boolean.TRUE; }); }
    @Override public Optional<Upload> findUpload(String userId,String uploadHash,Instant now) {
        return jdbc.sql("SELECT user_id,object_key,content_type,expires_at_utc FROM profile.avatar_uploads WHERE upload_id_hash=:hash AND user_id=:user AND expires_at_utc>:now")
                .param("hash",uploadHash).param("user",userId).param("now",java.sql.Timestamp.from(now)).query((rs,n)->new Upload(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant())).optional();
    }
    @Override public void saveUpload(String userId,String uploadHash,String objectKey,String type,Instant created,Instant expires) {
        jdbc.sql("INSERT INTO profile.avatar_uploads(upload_id_hash,user_id,object_key,content_type,expires_at_utc,created_at_utc) VALUES (:hash,:user,:key,:type,:expires,:created)")
                .param("hash",uploadHash).param("user",userId).param("key",objectKey).param("type",type).param("expires",java.sql.Timestamp.from(expires)).param("created",java.sql.Timestamp.from(created)).update();
    }
    @Override public Optional<Upload> consumeUpload(String userId,String uploadHash,Instant now) {
        return transactions.execute(status->{
            var row=jdbc.sql("SELECT user_id,object_key,content_type,expires_at_utc FROM profile.avatar_uploads WHERE upload_id_hash=:hash AND user_id=:user AND expires_at_utc>:now FOR UPDATE")
                    .param("hash",uploadHash).param("user",userId).param("now",java.sql.Timestamp.from(now)).query((rs,n)->new Upload(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant())).optional();
            row.ifPresent(x->jdbc.sql("DELETE FROM profile.avatar_uploads WHERE upload_id_hash=:hash").param("hash",uploadHash).update());
            return row;
        });
    }
    @Override public void createInitialProfile(String userId,String handle,Instant now) {
        jdbc.sql("INSERT INTO profile.profiles(user_id,display_name,bio,profile_version,created_at_utc,updated_at_utc) VALUES (:user,:handle,'',0,:now,:now)")
                .param("user",userId).param("handle",handle).param("now",java.sql.Timestamp.from(now)).update();
    }
    @Override public Profile update(String userId,String displayName,String bio,String avatarKey,Instant now) {
        return jdbc.sql("UPDATE profile.profiles SET display_name=:name,bio=:bio,avatar_key=:avatar, "
                +"profile_version=profile_version + CASE WHEN (display_name,bio,avatar_key) IS DISTINCT FROM (:name,:bio,:avatar) THEN 1 ELSE 0 END, "
                +"updated_at_utc=CASE WHEN (display_name,bio,avatar_key) IS DISTINCT FROM (:name,:bio,:avatar) THEN :now ELSE updated_at_utc END "
                +"WHERE user_id=:user RETURNING user_id,display_name,bio,avatar_key,profile_version,created_at_utc,updated_at_utc")
                .param("user",userId).param("name",displayName).param("bio",bio).param("avatar",avatarKey).param("now",java.sql.Timestamp.from(now))
                .query((rs,n)->new Profile(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7).toInstant())).single();
    }
    @Override public List<String> deleteExpiredUploads(Instant now,int limit) {
        return jdbc.sql("DELETE FROM profile.avatar_uploads WHERE upload_id_hash IN (SELECT upload_id_hash FROM profile.avatar_uploads WHERE expires_at_utc<=:now ORDER BY expires_at_utc LIMIT :limit FOR UPDATE SKIP LOCKED) RETURNING object_key")
                .param("now",java.sql.Timestamp.from(now)).param("limit",limit).query(String.class).list();
    }
    @Override public boolean isObjectReferenced(String key,boolean published) {
        String sql=published?"SELECT EXISTS(SELECT 1 FROM profile.profiles WHERE avatar_key=:key)":"SELECT EXISTS(SELECT 1 FROM profile.avatar_uploads WHERE object_key=:key)";
        return jdbc.sql(sql).param("key",key).query(Boolean.class).single();
    }
}
