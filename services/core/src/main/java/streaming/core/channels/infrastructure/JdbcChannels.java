package streaming.core.channels.infrastructure;

import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.core.accounts.profile.application.ProfileApplicationService.ProfileView;
import streaming.core.channels.application.ChannelInitializer;
import streaming.core.channels.application.ChannelQueries;
import streaming.core.channels.application.ChannelStore;

@Repository
public class JdbcChannels implements ChannelInitializer, ChannelQueries, ChannelStore {
    private static final String CHANNEL_COLUMNS="channel_id,owner_user_id,description,banner_key,channel_version,created_at_utc,updated_at_utc";
    private final JdbcClient jdbc;
    private final String avatarBase;

    public JdbcChannels(JdbcClient jdbc,
            @Value("${profile.avatar-public-base-url:/api/profile/avatars}") String avatarBase) {
        this.jdbc=jdbc;
        this.avatarBase=avatarBase.replaceAll("/$","");
    }

    @Override public String createInitialChannel(String ownerUserId,Instant now) {
        String id="chn_"+UUID.randomUUID().toString().replace("-","");
        jdbc.sql("INSERT INTO channels.channels(channel_id,owner_user_id,description,channel_version,created_at_utc,updated_at_utc) VALUES (:id,:owner,'',0,:now,:now)")
                .param("id",id).param("owner",ownerUserId).param("now",java.sql.Timestamp.from(now)).update();
        return id;
    }

    public Optional<Bootstrap> byHandle(String canonicalHandle) { return read("a.handle",canonicalHandle); }
    public Optional<Bootstrap> byId(String channelId) { return read("c.channel_id",channelId); }
    public Optional<Bootstrap> byOwner(String userId) { return read("c.owner_user_id",userId); }

    @Override public Optional<Channel> find(String channelId) {
        return jdbc.sql("SELECT "+CHANNEL_COLUMNS+" FROM channels.channels WHERE channel_id=:id")
                .param("id",channelId).query(JdbcChannels::channel).optional();
    }

    @Override public Optional<Channel> lockChannel(String channelId) {
        return jdbc.sql("SELECT "+CHANNEL_COLUMNS+" FROM channels.channels WHERE channel_id=:id FOR UPDATE")
                .param("id",channelId).query(JdbcChannels::channel).optional();
    }

    @Override public Channel update(Channel before,String description,String bannerKey,String bannerUri,Instant now) {
        return jdbc.sql("UPDATE channels.channels SET description=:description,banner_key=:key,banner_uri=:uri,"
                +"channel_version=channel_version+1,updated_at_utc=:now WHERE channel_id=:id RETURNING "+CHANNEL_COLUMNS)
                .param("description",description).param("key",bannerKey).param("uri",bannerUri)
                .param("now",java.sql.Timestamp.from(now)).param("id",before.channelId()).query(JdbcChannels::channel).single();
    }

    @Override public void saveUpload(String channelId,String ownerUserId,String uploadHash,String objectKey,String contentType,Instant created,Instant expires) {
        jdbc.sql("INSERT INTO channels.banner_uploads(upload_id_hash,channel_id,owner_user_id,object_key,content_type,expires_at_utc,created_at_utc) "
                +"VALUES (:hash,:channel,:owner,:key,:type,:expires,:created)")
                .param("hash",uploadHash).param("channel",channelId).param("owner",ownerUserId).param("key",objectKey).param("type",contentType)
                .param("expires",java.sql.Timestamp.from(expires)).param("created",java.sql.Timestamp.from(created)).update();
    }

    @Override public Optional<Upload> findUpload(String channelId,String ownerUserId,String uploadHash,Instant now) {
        return uploadQuery("SELECT channel_id,owner_user_id,object_key,content_type,expires_at_utc FROM channels.banner_uploads "
                +"WHERE upload_id_hash=:hash AND channel_id=:channel AND owner_user_id=:owner AND expires_at_utc>:now",channelId,ownerUserId,uploadHash,now);
    }

    @Override public Optional<Upload> consumeUpload(String channelId,String ownerUserId,String uploadHash,Instant now) {
        return uploadQuery("DELETE FROM channels.banner_uploads WHERE upload_id_hash=:hash AND channel_id=:channel "
                +"AND owner_user_id=:owner AND expires_at_utc>:now RETURNING channel_id,owner_user_id,object_key,content_type,expires_at_utc",channelId,ownerUserId,uploadHash,now);
    }

    private Optional<Upload> uploadQuery(String sql,String channelId,String ownerUserId,String uploadHash,Instant now) {
        return jdbc.sql(sql).param("hash",uploadHash).param("channel",channelId).param("owner",ownerUserId).param("now",java.sql.Timestamp.from(now))
                .query((rs,n)->new Upload(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant())).optional();
    }

    @Override public List<String> deleteExpiredUploads(Instant now,int limit) {
        return jdbc.sql("DELETE FROM channels.banner_uploads WHERE upload_id_hash IN "
                +"(SELECT upload_id_hash FROM channels.banner_uploads WHERE expires_at_utc<=:now ORDER BY expires_at_utc LIMIT :limit) RETURNING object_key")
                .param("now",java.sql.Timestamp.from(now)).param("limit",limit).query(String.class).list();
    }

    @Override public boolean isObjectReferenced(String objectKey,boolean published) {
        String sql=published?"SELECT EXISTS(SELECT 1 FROM channels.channels WHERE banner_key=:key)"
                :"SELECT EXISTS(SELECT 1 FROM channels.banner_uploads WHERE object_key=:key)";
        return jdbc.sql(sql).param("key",objectKey).query(Boolean.class).single();
    }

    private static Channel channel(ResultSet rs,int n) throws SQLException {
        return new Channel(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7).toInstant());
    }

    private Optional<Bootstrap> read(String column,String value) {
        // Other owners publish explicit public columns; private account data never enters this query.
        return jdbc.sql("SELECT c.channel_id,c.owner_user_id,c.description,c.banner_uri,c.channel_version,"
                +"a.handle,p.display_name,p.bio,p.avatar_key,p.updated_at_utc,p.profile_version "
                +"FROM channels.channels c JOIN identity.public_accounts a ON a.user_id=c.owner_user_id "
                +"JOIN profile.public_profiles p ON p.user_id=c.owner_user_id WHERE "+column+"=:value")
                .param("value",value).query((rs,n)->new Bootstrap(
                        new ChannelView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)),
                        rs.getString(6),new ProfileView(rs.getString(2),rs.getString(7),rs.getString(8),
                        rs.getString(9)==null?null:avatarBase+"/"+rs.getString(9),rs.getTimestamp(10).toInstant(),rs.getLong(11)),null,false,"UNKNOWN")).optional();
    }

}
