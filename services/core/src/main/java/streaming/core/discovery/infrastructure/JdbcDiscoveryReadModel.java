package streaming.core.discovery.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.core.discovery.application.DiscoveryReadModel;
import streaming.core.discovery.domain.ChannelCursor;
import streaming.core.discovery.domain.StreamProjection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Single-statement reads over the Streaming projection and the published public views of Accounts, Profile and
 * Channels. User text only ever travels as bound parameters; matching uses strpos, so no LIKE escaping is needed.
 */
@Repository
public class JdbcDiscoveryReadModel implements DiscoveryReadModel {
    private static final String CHANNEL_JOINS="""
            FROM discovery.stream_projection sp
            JOIN channels.public_channels c ON c.channel_id=sp.channel_id
            JOIN identity.public_accounts a ON a.user_id=c.owner_user_id
            JOIN profile.public_profiles p ON p.user_id=c.owner_user_id
            WHERE sp.status='LIVE' AND sp.availability='PLAYABLE' AND sp.state_observed_at_utc BETWEEN :oldest AND :newest""";
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final String avatarBase;

    public JdbcDiscoveryReadModel(JdbcClient jdbc,ObjectMapper json,
            @Value("${profile.avatar-public-base-url:/api/profile/avatars}") String avatarBase) {
        this.jdbc=jdbc; this.json=json; this.avatarBase=avatarBase.replaceAll("/$","");
    }

    @Override public List<String> rankedStreamIds(StreamQuery query,Instant oldestFresh,Instant newestFresh,int max) {
        var sql=new StringBuilder("SELECT sp.stream_id ").append(CHANNEL_JOINS);
        var params=new HashMap<String,Object>();
        params.put("oldest",JdbcProjectionStore.timestamp(oldestFresh)); params.put("newest",JdbcProjectionStore.timestamp(newestFresh));
        if(query.normalizedTitle()!=null) { sql.append(" AND strpos(sp.normalized_title,:q)>0"); params.put("q",query.normalizedTitle()); }
        if(query.categoryId()!=null) { sql.append(" AND sp.category_id=:category"); params.put("category",query.categoryId()); }
        if(query.tagId()!=null) {
            sql.append(" AND sp.tags @> jsonb_build_array(jsonb_build_object('id',CAST(:tag AS text)))"); params.put("tag",query.tagId());
        }
        sql.append(" ORDER BY sp.viewer_count DESC, sp.started_at_utc DESC, sp.stream_id COLLATE \"C\" ASC LIMIT :max");
        params.put("max",max);
        return jdbc.sql(sql.toString()).params(params).query(String.class).list();
    }

    @Override public List<StreamRow> streams(List<String> streamIds,Instant oldestFresh,Instant newestFresh) {
        if(streamIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT sp.stream_id,sp.session_id,sp.channel_id,a.handle,p.display_name,p.avatar_key,sp.title,sp.category_id,sp.category_name,"
                +"CAST(sp.tags AS text) AS tags,sp.status,sp.availability,sp.viewer_count,sp.viewer_count_observed_at_utc,sp.started_at_utc,"
                +"sp.metadata_version,sp.session_version,sp.state_observed_at_utc "+CHANNEL_JOINS
                +" AND sp.stream_id = ANY(string_to_array(:ids,','))")
                .param("oldest",JdbcProjectionStore.timestamp(oldestFresh)).param("newest",JdbcProjectionStore.timestamp(newestFresh))
                .param("ids",String.join(",",streamIds)).query(this::stream).list();
    }

    private StreamRow stream(ResultSet rs,int n) throws SQLException {
        return new StreamRow(rs.getString("stream_id"),rs.getString("session_id"),rs.getString("channel_id"),rs.getString("handle"),
                rs.getString("display_name"),avatar(rs.getString("avatar_key")),rs.getString("title"),rs.getString("category_id"),
                rs.getString("category_name"),tags(rs.getString("tags")),rs.getString("status"),rs.getString("availability"),
                rs.getInt("viewer_count"),JdbcProjectionStore.instant(rs.getTimestamp("viewer_count_observed_at_utc")),
                JdbcProjectionStore.instant(rs.getTimestamp("started_at_utc")),rs.getLong("metadata_version"),rs.getLong("session_version"),
                JdbcProjectionStore.instant(rs.getTimestamp("state_observed_at_utc")));
    }

    @Override public List<ChannelRow> channels(String normalizedQuery,ChannelCursor after,int limit) {
        boolean search=normalizedQuery!=null;
        String nh="lower(normalize(CAST(a.handle AS text), NFKC))", nd="lower(normalize(CAST(p.display_name AS text), NFKC))";
        var sql=new StringBuilder();
        sql.append("WITH matched AS (SELECT c.channel_id,c.owner_user_id AS user_id,c.channel_version,a.handle,p.display_name,p.avatar_key");
        if(search) {
            sql.append(",").append(classOf(nh)).append(" AS class_h,").append(classOf(nd)).append(" AS class_d");
        } else sql.append(",0 AS class_h,0 AS class_d");
        sql.append(" FROM channels.public_channels c JOIN identity.public_accounts a ON a.user_id=c.owner_user_id ")
           .append("JOIN profile.public_profiles p ON p.user_id=c.owner_user_id), ")
           .append("ranked AS (SELECT m.*,LEAST(class_h,class_d) AS best_class,CASE WHEN class_h<=class_d THEN 0 ELSE 1 END AS field_rank ")
           .append("FROM matched m WHERE LEAST(class_h,class_d)<3) ")
           .append("SELECT r.channel_id,r.user_id,r.channel_version,r.handle,r.display_name,r.avatar_key,r.best_class,r.field_rank,")
           .append("sp.stream_id IS NOT NULL AS has_projection,sp.title,sp.status,sp.availability,sp.metadata_version,sp.session_version,sp.state_observed_at_utc ")
           .append("FROM ranked r LEFT JOIN discovery.stream_projection sp ON sp.channel_id=r.channel_id");
        var params=new HashMap<String,Object>();
        if(search) params.put("q",normalizedQuery);
        if(after!=null) {
            sql.append(" WHERE (r.best_class,r.field_rank,r.handle COLLATE \"C\",r.user_id COLLATE \"C\") > (:cClass,:cField,:cHandle,:cUser)");
            params.put("cClass",after.bestClass()); params.put("cField",after.fieldRank()); params.put("cHandle",after.handle()); params.put("cUser",after.userId());
        }
        sql.append(" ORDER BY r.best_class,r.field_rank,r.handle COLLATE \"C\",r.user_id COLLATE \"C\" LIMIT :limit");
        params.put("limit",limit);
        return jdbc.sql(sql.toString()).params(params).query(this::channel).list();
    }

    /** 0 exact, 1 prefix, 2 substring, 3 no match. */
    private static String classOf(String normalized) {
        return "CASE WHEN "+normalized+"=:q THEN 0 WHEN starts_with("+normalized+",:q) THEN 1 WHEN strpos("+normalized+",:q)>0 THEN 2 ELSE 3 END";
    }

    private ChannelRow channel(ResultSet rs,int n) throws SQLException {
        return new ChannelRow(rs.getString("channel_id"),rs.getString("user_id"),rs.getString("handle"),rs.getString("display_name"),
                avatar(rs.getString("avatar_key")),rs.getLong("channel_version"),rs.getInt("best_class"),rs.getInt("field_rank"),
                rs.getBoolean("has_projection"),rs.getString("title"),rs.getString("status"),rs.getString("availability"),
                (Long)rs.getObject("metadata_version"),(Long)rs.getObject("session_version"),
                JdbcProjectionStore.instant(rs.getTimestamp("state_observed_at_utc")));
    }

    private List<StreamProjection.Tag> tags(String text) {
        var tags=new ArrayList<StreamProjection.Tag>();
        JsonNode array=json.readTree(text);
        for(JsonNode tag:array) tags.add(new StreamProjection.Tag(tag.get("id").textValue(),tag.get("name").textValue()));
        return tags;
    }
    private String avatar(String key) { return key==null?null:avatarBase+"/"+key; }
}
