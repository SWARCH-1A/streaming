package streaming.core.channels.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.core.accounts.profile.application.ProfileApplicationService.ProfileView;
import streaming.core.channels.application.ChannelInitializer;
import streaming.core.channels.application.ChannelQueries;

@Repository
public class JdbcChannels implements ChannelInitializer, ChannelQueries {
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
    public Optional<Bootstrap> byOwner(String userId) { return read("c.owner_user_id",userId); }

    private Optional<Bootstrap> read(String column,String value) {
        // Other owners publish explicit public columns; private account data never enters this query.
        return jdbc.sql("SELECT c.channel_id,c.owner_user_id,c.description,c.banner_uri,c.channel_version,"
                +"a.handle,p.display_name,p.bio,p.avatar_key,p.updated_at_utc,p.profile_version "
                +"FROM channels.channels c JOIN identity.public_accounts a ON a.user_id=c.owner_user_id "
                +"JOIN profile.public_profiles p ON p.user_id=c.owner_user_id WHERE "+column+"=:value")
                .param("value",value).query((rs,n)->new Bootstrap(
                        new ChannelView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)),
                        rs.getString(6),new ProfileView(rs.getString(2),rs.getString(7),rs.getString(8),
                        rs.getString(9)==null?null:avatarBase+"/"+rs.getString(9),rs.getTimestamp(10).toInstant(),rs.getLong(11)),null)).optional();
    }

}
