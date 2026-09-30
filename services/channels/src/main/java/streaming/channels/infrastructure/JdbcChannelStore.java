package streaming.channels.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.channels.application.ChannelStore;

/** PostgreSQL adapter; callers own the transaction boundaries. */
@Repository
public class JdbcChannelStore implements ChannelStore {
    private static final String CHANNEL_COLUMNS="channel_id,owner_user_id,registration_id,description,banner_key,channel_version,created_at_utc,updated_at_utc";
    private static final String PROJECTION_COLUMNS="channel_id,stream_id,session_id,stream_generation,session_version,status,availability,title,category_id,tag_ids,"
            +"metadata_version,viewer_count,count_version,updated_at_utc";
    private final JdbcClient jdbc;
    public JdbcChannelStore(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Override public Optional<Channel> find(String channelId) {
        return jdbc.sql("SELECT "+CHANNEL_COLUMNS+" FROM channels.channels WHERE channel_id=:id").param("id",channelId).query(JdbcChannelStore::channel).optional();
    }
    @Override public Optional<Channel> findByOwner(String ownerUserId) {
        return jdbc.sql("SELECT "+CHANNEL_COLUMNS+" FROM channels.channels WHERE owner_user_id=:owner").param("owner",ownerUserId).query(JdbcChannelStore::channel).optional();
    }
    @Override public Optional<Channel> findByRegistration(String registrationId) {
        return jdbc.sql("SELECT "+CHANNEL_COLUMNS+" FROM channels.channels WHERE registration_id=:reg").param("reg",registrationId).query(JdbcChannelStore::channel).optional();
    }
    @Override public Optional<Channel> lockChannel(String channelId) {
        return jdbc.sql("SELECT "+CHANNEL_COLUMNS+" FROM channels.channels WHERE channel_id=:id FOR UPDATE").param("id",channelId).query(JdbcChannelStore::channel).optional();
    }
    @Override public void lockKey(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key","channels:"+key).query(rs -> { rs.next(); return Boolean.TRUE; });
    }

    @Override public Optional<Fence> findFence(String registrationId) {
        return jdbc.sql("SELECT registration_id,owner_user_id,pending_until_utc,state FROM channels.registration_fences WHERE registration_id=:reg").param("reg",registrationId)
                .query((rs,n)->new Fence(rs.getString(1),rs.getString(2),instant(rs,3),rs.getString(4))).optional();
    }
    @Override public void saveFence(String registrationId,String ownerUserId,Instant pendingUntil,String state,Instant now) {
        jdbc.sql("INSERT INTO channels.registration_fences(registration_id,owner_user_id,pending_until_utc,state,updated_at_utc) VALUES (:reg,:owner,:until,:state,:now) "
                +"ON CONFLICT(registration_id) DO UPDATE SET owner_user_id=COALESCE(EXCLUDED.owner_user_id,channels.registration_fences.owner_user_id), "
                +"pending_until_utc=COALESCE(channels.registration_fences.pending_until_utc,EXCLUDED.pending_until_utc),state=EXCLUDED.state,updated_at_utc=EXCLUDED.updated_at_utc")
                .param("reg",registrationId).param("owner",ownerUserId).param("until",timestamp(pendingUntil)).param("state",state).param("now",timestamp(now)).update();
    }
    @Override public Channel create(String channelId,String ownerUserId,String registrationId,Instant now,String requestId) {
        Channel created=jdbc.sql("INSERT INTO channels.channels(channel_id,owner_user_id,registration_id,description,banner_key,channel_version,created_at_utc,updated_at_utc) "
                +"VALUES (:id,:owner,:reg,NULL,NULL,0,:now,:now) RETURNING "+CHANNEL_COLUMNS)
                .param("id",channelId).param("owner",ownerUserId).param("reg",registrationId).param("now",timestamp(now)).query(JdbcChannelStore::channel).single();
        String payload="{\"ownerUserId\":\""+json(ownerUserId)+"\",\"registrationId\":\""+json(registrationId)+"\",\"channelId\":\""+json(channelId)+"\",\"channelVersion\":0,\"requestId\":"
                +(requestId==null?"null":"\""+json(requestId)+"\"")+"}";
        outbox("ChannelProvisioned",channelId,0,now,payload);
        return created;
    }
    @Override public boolean deleteByRegistration(String registrationId) {
        Optional<Channel> channel=findByRegistration(registrationId);
        if(channel.isEmpty()) return false;
        String id=channel.get().channelId();
        jdbc.sql("DELETE FROM channels.banner_uploads WHERE channel_id=:id").param("id",id).update();
        jdbc.sql("DELETE FROM channels.stream_projections WHERE channel_id=:id").param("id",id).update();
        return jdbc.sql("DELETE FROM channels.channels WHERE channel_id=:id").param("id",id).update()==1;
    }
    @Override public Channel update(Channel before,String description,String bannerKey,String bannerUri,Instant now) {
        Channel after=jdbc.sql("UPDATE channels.channels SET description=:description,banner_key=:banner,channel_version=channel_version+1,updated_at_utc=:now "
                +"WHERE channel_id=:id RETURNING "+CHANNEL_COLUMNS)
                .param("description",description).param("banner",bannerKey).param("now",timestamp(now)).param("id",before.channelId()).query(JdbcChannelStore::channel).single();
        String payload="{\"channelId\":\""+json(after.channelId())+"\",\"ownerUserId\":\""+json(after.ownerUserId())+"\",\"description\":"+quoted(after.description())
                +",\"bannerUri\":"+quoted(bannerUri)+",\"channelVersion\":"+after.version()+"}";
        outbox("ChannelChanged",after.channelId(),after.version(),now,payload);
        return after;
    }

    @Override public void saveUpload(String channelId,String ownerUserId,String uploadHash,String objectKey,String contentType,Instant created,Instant expires) {
        jdbc.sql("INSERT INTO channels.banner_uploads(upload_id_hash,channel_id,owner_user_id,object_key,content_type,expires_at_utc,created_at_utc) "
                +"VALUES (:hash,:channel,:owner,:key,:type,:expires,:created)")
                .param("hash",uploadHash).param("channel",channelId).param("owner",ownerUserId).param("key",objectKey).param("type",contentType)
                .param("expires",timestamp(expires)).param("created",timestamp(created)).update();
    }
    @Override public Optional<Upload> findUpload(String channelId,String ownerUserId,String uploadHash,Instant now) {
        return jdbc.sql("SELECT channel_id,owner_user_id,object_key,content_type,expires_at_utc FROM channels.banner_uploads "
                +"WHERE upload_id_hash=:hash AND channel_id=:channel AND owner_user_id=:owner AND expires_at_utc>:now")
                .param("hash",uploadHash).param("channel",channelId).param("owner",ownerUserId).param("now",timestamp(now)).query(JdbcChannelStore::upload).optional();
    }
    @Override public Optional<Upload> consumeUpload(String channelId,String ownerUserId,String uploadHash,Instant now) {
        return jdbc.sql("DELETE FROM channels.banner_uploads WHERE upload_id_hash=:hash AND channel_id=:channel AND owner_user_id=:owner AND expires_at_utc>:now "
                +"RETURNING channel_id,owner_user_id,object_key,content_type,expires_at_utc")
                .param("hash",uploadHash).param("channel",channelId).param("owner",ownerUserId).param("now",timestamp(now)).query(JdbcChannelStore::upload).optional();
    }
    @Override public List<String> expiredUploadKeys(Instant now,int limit) {
        return jdbc.sql("SELECT object_key FROM channels.banner_uploads WHERE expires_at_utc<=:now ORDER BY expires_at_utc LIMIT :limit")
                .param("now",timestamp(now)).param("limit",limit).query(String.class).list();
    }
    @Override public void deleteExpiredUploads(Instant now) {
        jdbc.sql("DELETE FROM channels.banner_uploads WHERE expires_at_utc<=:now").param("now",timestamp(now)).update();
    }

    @Override public boolean markEventProcessed(String eventId,String eventType,Instant now) {
        return jdbc.sql("INSERT INTO channels.processed_events(event_id,event_type,processed_at_utc) VALUES (:id,:type,:now) ON CONFLICT(event_id) DO NOTHING")
                .param("id",eventId).param("type",eventType).param("now",timestamp(now)).update()==1;
    }
    @Override public Optional<StreamProjection> findProjection(String channelId) {
        return jdbc.sql("SELECT "+PROJECTION_COLUMNS+" FROM channels.stream_projections WHERE channel_id=:id").param("id",channelId).query(JdbcChannelStore::projection).optional();
    }
    @Override public Optional<StreamProjection> findProjectionBySession(String sessionId) {
        return jdbc.sql("SELECT "+PROJECTION_COLUMNS+" FROM channels.stream_projections WHERE session_id=:session").param("session",sessionId).query(JdbcChannelStore::projection).optional();
    }
    @Override public void saveProjection(StreamProjection p) {
        jdbc.sql("INSERT INTO channels.stream_projections("+PROJECTION_COLUMNS+") VALUES (:channel,:stream,:session,:generation,:sessionVersion,:status,:availability,:title,:category,:tags,"
                +":metadataVersion,:viewers,:countVersion,:now) ON CONFLICT(channel_id) DO UPDATE SET stream_id=EXCLUDED.stream_id,session_id=EXCLUDED.session_id,"
                +"stream_generation=EXCLUDED.stream_generation,session_version=EXCLUDED.session_version,status=EXCLUDED.status,availability=EXCLUDED.availability,"
                +"title=EXCLUDED.title,category_id=EXCLUDED.category_id,tag_ids=EXCLUDED.tag_ids,metadata_version=EXCLUDED.metadata_version,"
                +"viewer_count=EXCLUDED.viewer_count,count_version=EXCLUDED.count_version,updated_at_utc=EXCLUDED.updated_at_utc")
                .param("channel",p.channelId()).param("stream",p.streamId()).param("session",p.sessionId()).param("generation",p.streamGeneration())
                .param("sessionVersion",p.sessionVersion()).param("status",p.status()).param("availability",p.availability()).param("title",p.title())
                .param("category",p.categoryId()).param("tags",String.join(",",p.tagIds())).param("metadataVersion",p.metadataVersion())
                .param("viewers",p.viewerCount()).param("countVersion",p.countVersion()).param("now",timestamp(p.updatedAt())).update();
    }

    private void outbox(String type,String channelId,long sequence,Instant now,String payload) {
        jdbc.sql("INSERT INTO channels.outbox_events(event_id,event_type,schema_version,aggregate_id,sequence_no,occurred_at_utc,payload) "
                +"VALUES (:event,:type,1,:aggregate,:sequence,:now,CAST(:payload AS jsonb))")
                .param("event",UUID.randomUUID()).param("type",type).param("aggregate","channel:"+channelId).param("sequence",sequence)
                .param("now",timestamp(now)).param("payload",payload).update();
    }
    private static Channel channel(ResultSet rs,int n) throws SQLException {
        return new Channel(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6),instant(rs,7),instant(rs,8));
    }
    private static Upload upload(ResultSet rs,int n) throws SQLException {
        return new Upload(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),instant(rs,5));
    }
    private static StreamProjection projection(ResultSet rs,int n) throws SQLException {
        String tags=rs.getString(10);
        return new StreamProjection(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),
                rs.getString(9),tags==null||tags.isEmpty()?List.of():Arrays.asList(tags.split(",")),rs.getLong(11),rs.getLong(12),rs.getLong(13),instant(rs,14));
    }
    private static Instant instant(ResultSet rs,int column) throws SQLException { java.sql.Timestamp t=rs.getTimestamp(column); return t==null?null:t.toInstant(); }
    private static java.sql.Timestamp timestamp(Instant value) { return value==null?null:java.sql.Timestamp.from(value); }
    private static String quoted(String value) { return value==null?"null":"\""+json(value)+"\""; }
    private static String json(String value) {
        StringBuilder out=new StringBuilder();
        for(char c:value.toCharArray()) {
            switch(c) {
                case '"'->out.append("\\\""); case '\\'->out.append("\\\\"); case '\n'->out.append("\\n"); case '\r'->out.append("\\r"); case '\t'->out.append("\\t");
                default->{ if(c<0x20) out.append(String.format("\\u%04x",(int)c)); else out.append(c); }
            }
        }
        return out.toString();
    }
}
