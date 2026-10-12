package streaming.core.discovery.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.core.discovery.application.ProjectionStore;
import streaming.core.discovery.domain.ApplyOutcome;
import streaming.core.discovery.domain.SearchText;
import streaming.core.discovery.domain.StreamProjection;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcProjectionStore implements ProjectionStore {
    private static final String INSERT_COLUMNS="stream_id,channel_id,projection_version,discovery_position,content_hash,metadata_version,title,"
            +"normalized_title,category_id,category_name,tags,session_id,stream_generation,session_version,status,availability,started_at_utc,"
            +"state_observed_at_utc,viewer_count,count_version,viewer_count_observed_at_utc,received_at_utc,applied_at_utc";
    private static final String INSERT_VALUES=":streamId,:channelId,:version,:position,:hash,:metadataVersion,:title,:normalizedTitle,:categoryId,"
            +":categoryName,CAST(:tags AS jsonb),:sessionId,:generation,:sessionVersion,:status,:availability,:startedAt,:observedAt,:viewers,"
            +":countVersion,:countObservedAt,:receivedAt,:appliedAt";
    private static final String UPDATE_SET="projection_version=:version,discovery_position=:position,content_hash=:hash,metadata_version=:metadataVersion,"
            +"title=:title,normalized_title=:normalizedTitle,category_id=:categoryId,category_name=:categoryName,tags=CAST(:tags AS jsonb),"
            +"session_id=:sessionId,stream_generation=:generation,session_version=:sessionVersion,status=:status,availability=:availability,"
            +"started_at_utc=:startedAt,state_observed_at_utc=:observedAt,viewer_count=:viewers,count_version=:countVersion,"
            +"viewer_count_observed_at_utc=:countObservedAt,received_at_utc=:receivedAt,applied_at_utc=:appliedAt";
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcProjectionStore(JdbcClient jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    private record Existing(long version,String hash,String channelId) { }

    @Override public ApplyOutcome apply(StreamProjection in,Instant receivedAt,Instant appliedAt) {
        for(int attempt=0;attempt<5;attempt++) {
            Optional<Existing> existing=jdbc.sql("SELECT projection_version,content_hash,channel_id FROM discovery.stream_projection WHERE stream_id=:id FOR UPDATE")
                    .param("id",in.streamId()).query((rs,n)->new Existing(rs.getLong(1),rs.getString(2),rs.getString(3))).optional();
            if(existing.isEmpty()) {
                boolean channelTaken=jdbc.sql("SELECT EXISTS(SELECT 1 FROM discovery.stream_projection WHERE channel_id=:channel AND stream_id<>:id)")
                        .param("channel",in.channelId()).param("id",in.streamId()).query(Boolean.class).single();
                if(channelTaken) return ApplyOutcome.CONFLICT_CHANNEL;
                // No conflict target: a concurrent first insert of this stream (same stream_id and channel_id) or of another stream
                // for the same channel is absorbed whichever unique index it hits, instead of aborting the whole transaction.
                int inserted=jdbc.sql("INSERT INTO discovery.stream_projection("+INSERT_COLUMNS+") VALUES ("+INSERT_VALUES+") ON CONFLICT DO NOTHING")
                        .params(parameters(in,receivedAt,appliedAt)).update();
                if(inserted==1) return ApplyOutcome.APPLIED;
                continue;   // a concurrent transaction won: lock the committed row and compare, or detect the channel conflict
            }
            Existing current=existing.get();
            if(!current.channelId().equals(in.channelId())) return ApplyOutcome.CONFLICT_CHANNEL;
            if(in.projectionVersion()>current.version()) {
                jdbc.sql("UPDATE discovery.stream_projection SET "+UPDATE_SET+" WHERE stream_id=:streamId")
                        .params(parameters(in,receivedAt,appliedAt)).update();
                return ApplyOutcome.APPLIED;
            }
            if(in.projectionVersion()==current.version()) return current.hash().equals(in.contentHash())?ApplyOutcome.IGNORED_SAME:ApplyOutcome.CONFLICT_SAME_VERSION;
            return ApplyOutcome.IGNORED_OLDER;
        }
        throw new IllegalStateException("Projection row could not be locked");
    }

    private Map<String,Object> parameters(StreamProjection in,Instant receivedAt,Instant appliedAt) {
        var tags=in.tags().stream().map(t->Map.of("id",t.id(),"name",t.name())).toList();
        var p=new HashMap<String,Object>();
        p.put("streamId",in.streamId()); p.put("channelId",in.channelId()); p.put("version",in.projectionVersion()); p.put("position",in.discoveryPosition());
        p.put("hash",in.contentHash()); p.put("metadataVersion",in.metadataVersion()); p.put("title",in.title());
        p.put("normalizedTitle",SearchText.normalize(in.title())); p.put("categoryId",in.categoryId()); p.put("categoryName",in.categoryName());
        p.put("tags",json.writeValueAsString(tags)); p.put("sessionId",in.sessionId()); p.put("generation",in.streamGeneration());
        p.put("sessionVersion",in.sessionVersion()); p.put("status",in.status()); p.put("availability",in.availability());
        p.put("startedAt",timestamp(in.startedAtUtc())); p.put("observedAt",timestamp(in.stateObservedAtUtc())); p.put("viewers",in.viewerCount());
        p.put("countVersion",in.countVersion()); p.put("countObservedAt",timestamp(in.viewerCountObservedAtUtc()));
        p.put("receivedAt",timestamp(receivedAt)); p.put("appliedAt",timestamp(appliedAt));
        return p;
    }

    @Override public Optional<String> projectionHash(String streamId) {
        return jdbc.sql("SELECT content_hash FROM discovery.stream_projection WHERE stream_id=:id").param("id",streamId).query(String.class).optional();
    }

    @Override public boolean recordInbox(String eventId,String contentHash,String streamId,long projectionVersion,Instant now) {
        return jdbc.sql("INSERT INTO discovery.inbox_events(event_id,content_hash,stream_id,projection_version,outcome,received_at_utc) "
                +"VALUES (:id,:hash,:stream,:version,'APPLIED',:now) ON CONFLICT (event_id) DO NOTHING")
                .param("id",eventId).param("hash",contentHash).param("stream",streamId).param("version",projectionVersion)
                .param("now",timestamp(now)).update()==1;
    }

    @Override public Optional<String> inboxHash(String eventId) {
        return jdbc.sql("SELECT content_hash FROM discovery.inbox_events WHERE event_id=:id").param("id",eventId).query(String.class).optional();
    }

    @Override public void setInboxOutcome(String eventId,String outcome) {
        jdbc.sql("UPDATE discovery.inbox_events SET outcome=:outcome WHERE event_id=:id").param("outcome",outcome).param("id",eventId).update();
    }

    @Override public int purgeInbox(Instant olderThan,int limit) {
        return jdbc.sql("DELETE FROM discovery.inbox_events WHERE event_id IN (SELECT event_id FROM discovery.inbox_events "
                +"WHERE received_at_utc<:cutoff ORDER BY received_at_utc LIMIT :limit)")
                .param("cutoff",timestamp(olderThan)).param("limit",limit).update();
    }

    @Override public void recordConflict(String eventId,String streamId,Long projectionVersion,String reason,String existingHash,
            String incomingHash,String payloadJson,Instant now) {
        jdbc.sql("INSERT INTO discovery.projection_conflicts(event_id,stream_id,projection_version,reason,existing_hash,incoming_hash,payload,detected_at_utc) "
                +"VALUES (:event,:stream,:version,:reason,:existing,:incoming,CAST(:payload AS jsonb),:now)")
                .param("event",eventId).param("stream",streamId).param("version",projectionVersion).param("reason",reason)
                .param("existing",existingHash).param("incoming",incomingHash).param("payload",payloadJson).param("now",timestamp(now)).update();
    }

    @Override public int pruneAbsent(Collection<String> presentStreamIds,long watermark) {
        return jdbc.sql("DELETE FROM discovery.stream_projection WHERE discovery_position<=:watermark "
                +"AND NOT (stream_id = ANY(string_to_array(:ids,',')))")
                .param("watermark",watermark).param("ids",String.join(",",presentStreamIds)).update();
    }

    @Override public ReconciliationState reconciliationState() {
        return jdbc.sql("SELECT last_snapshot_id,last_watermark,last_cut_captured_at_utc,last_success_at_utc,last_attempt_at_utc,"
                +"last_failure_at_utc,last_failure_code FROM discovery.reconciliation_state WHERE singleton")
                .query((rs,n)->new ReconciliationState(rs.getString(1),(Long)rs.getObject(2),instant(rs.getTimestamp(3)),instant(rs.getTimestamp(4)),
                        instant(rs.getTimestamp(5)),instant(rs.getTimestamp(6)),rs.getString(7))).single();
    }

    @Override public void saveSuccess(String snapshotId,long watermark,Instant capturedAt,Instant now) {
        jdbc.sql("UPDATE discovery.reconciliation_state SET last_snapshot_id=:snapshot,last_watermark=:watermark,last_cut_captured_at_utc=:captured,"
                +"last_success_at_utc=:now,last_attempt_at_utc=:now,last_failure_at_utc=NULL,last_failure_code=NULL WHERE singleton")
                .param("snapshot",snapshotId).param("watermark",watermark).param("captured",timestamp(capturedAt)).param("now",timestamp(now)).update();
    }

    @Override public void saveFailure(String code,Instant now) {
        jdbc.sql("UPDATE discovery.reconciliation_state SET last_attempt_at_utc=:now,last_failure_at_utc=:now,last_failure_code=:code WHERE singleton")
                .param("now",timestamp(now)).param("code",code).update();
    }

    static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
    static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
}
