package streaming.profile.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.profile.application.ProfileStore;

@Repository
public class JdbcProfileStore implements ProfileStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    public JdbcProfileStore(JdbcClient jdbc,TransactionTemplate transactions) { this.jdbc=jdbc; this.transactions=transactions; }
    @Override public Optional<Profile> find(String userId) { return jdbc.sql("SELECT user_id,display_name,bio,avatar_key,profile_version,created_at_utc,updated_at_utc FROM profile.profiles WHERE user_id=:user")
            .param("user",userId).query((rs,n)->new Profile(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7).toInstant())).optional(); }
    @Override public void lockUser(String userId) { jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key","profile:"+userId).query(rs -> { rs.next(); return null; }); }
    @Override public Optional<Upload> findUpload(String userId,String uploadHash,Instant now) {
        return jdbc.sql("SELECT user_id,object_key,content_type,expires_at_utc FROM profile.avatar_uploads WHERE upload_id_hash=:hash AND user_id=:user AND expires_at_utc>:now")
                .param("hash",uploadHash).param("user",userId).param("now",now).query((rs,n)->new Upload(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant())).optional();
    }
    @Override public void saveUpload(String userId,String uploadHash,String objectKey,String type,Instant created,Instant expires) {
        jdbc.sql("INSERT INTO profile.avatar_uploads(upload_id_hash,user_id,object_key,content_type,expires_at_utc,created_at_utc) VALUES (:hash,:user,:key,:type,:expires,:created)")
                .param("hash",uploadHash).param("user",userId).param("key",objectKey).param("type",type).param("expires",expires).param("created",created).update();
    }
    @Override public Optional<Upload> consumeUpload(String userId,String uploadHash,Instant now) {
        return transactions.execute(status->{
            var row=jdbc.sql("SELECT user_id,object_key,content_type,expires_at_utc FROM profile.avatar_uploads WHERE upload_id_hash=:hash AND user_id=:user AND expires_at_utc>:now FOR UPDATE")
                    .param("hash",uploadHash).param("user",userId).param("now",now).query((rs,n)->new Upload(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant())).optional();
            row.ifPresent(x->jdbc.sql("DELETE FROM profile.avatar_uploads WHERE upload_id_hash=:hash").param("hash",uploadHash).update());
            return row;
        });
    }
    @Override public Profile update(String userId,String displayName,String bio,String avatarKey,Instant now,String avatarUri) {
        return transactions.execute(status->{
            Profile before=find(userId).orElse(null);
            Profile after=jdbc.sql("INSERT INTO profile.profiles(user_id,display_name,bio,avatar_key,profile_version,created_at_utc,updated_at_utc) VALUES (:user,:name,:bio,:avatar,1,:now,:now) "
                    +"ON CONFLICT(user_id) DO UPDATE SET display_name=EXCLUDED.display_name,bio=EXCLUDED.bio,avatar_key=EXCLUDED.avatar_key, "
                    +"profile_version=profile.profiles.profile_version + CASE WHEN (profile.profiles.display_name,profile.profiles.bio,profile.profiles.avatar_key) IS DISTINCT FROM (EXCLUDED.display_name,EXCLUDED.bio,EXCLUDED.avatar_key) THEN 1 ELSE 0 END, "
                    +"updated_at_utc=CASE WHEN (profile.profiles.display_name,profile.profiles.bio,profile.profiles.avatar_key) IS DISTINCT FROM (EXCLUDED.display_name,EXCLUDED.bio,EXCLUDED.avatar_key) THEN EXCLUDED.updated_at_utc ELSE profile.profiles.updated_at_utc END "
                    +"RETURNING user_id,display_name,bio,avatar_key,profile_version,created_at_utc,updated_at_utc")
                    .param("user",userId).param("name",displayName).param("bio",bio).param("avatar",avatarKey).param("now",now)
                    .query((rs,n)->new Profile(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7).toInstant())).single();
            boolean changed=before==null || !before.displayName().equals(after.displayName()) || !before.bio().equals(after.bio()) || !java.util.Objects.equals(before.avatarKey(),after.avatarKey());
            if(changed) {
                String payload="{\"userId\":\""+userId+"\",\"displayName\":\""+json(after.displayName())+"\",\"avatarUri\":"+(avatarUri==null?"null":"\""+json(avatarUri)+"\"")+",\"profileVersion\":"+after.version()+"}";
                jdbc.sql("INSERT INTO profile.outbox_events(event_id,event_type,schema_version,aggregate_id,sequence_no,occurred_at_utc,payload) VALUES (:event,'ProfilePublicChanged',1,:aggregate,:sequence,:now,CAST(:payload AS jsonb))")
                    .param("event",UUID.randomUUID()).param("aggregate","profile:"+userId).param("sequence",after.version()).param("now",now).param("payload",payload).update();
            }
            return after;
        });
    }
    @Override public List<String> expiredUploadKeys(Instant now,int limit) { return jdbc.sql("SELECT object_key FROM profile.avatar_uploads WHERE expires_at_utc<=:now ORDER BY expires_at_utc LIMIT :limit").param("now",now).param("limit",limit).query(String.class).list(); }
    @Override public void deleteExpiredUploads(Instant now) { jdbc.sql("DELETE FROM profile.avatar_uploads WHERE expires_at_utc<=:now").param("now",now).update(); }
    private static String json(String value) { return value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r"); }
}
