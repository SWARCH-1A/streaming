package streaming.core.channels.infrastructure;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.core.channels.application.ChannelAdministration;

@Repository
public class JdbcChannelAdministration implements ChannelAdministration {
    private final JdbcClient jdbc;
    public JdbcChannelAdministration(JdbcClient jdbc) { this.jdbc=jdbc; }
    public List<Channel> list(String after, int limit) {
        return jdbc.sql("SELECT channel_id,owner_user_id,channel_version FROM channels.channels WHERE channel_id>:after ORDER BY channel_id LIMIT :limit")
            .param("after",after).param("limit",limit)
            .query((rs,n)->new Channel(rs.getString(1),rs.getString(2),rs.getLong(3))).list();
    }
}
