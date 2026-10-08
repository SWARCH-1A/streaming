package streaming.core.discovery.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.core.discovery.application.RankingSnapshots;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcRankingSnapshots implements RankingSnapshots {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcRankingSnapshots(JdbcClient jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    @Override public void save(UUID snapshotId,String filterHash,List<String> orderedStreamIds,Instant createdAt,Instant expiresAt) {
        jdbc.sql("INSERT INTO discovery.ranking_snapshots(snapshot_id,filter_hash,stream_ids,created_at_utc,expires_at_utc) "
                +"VALUES (:id,:filter,CAST(:ids AS jsonb),:created,:expires)")
                .param("id",snapshotId).param("filter",filterHash).param("ids",json.writeValueAsString(orderedStreamIds))
                .param("created",JdbcProjectionStore.timestamp(createdAt)).param("expires",JdbcProjectionStore.timestamp(expiresAt)).update();
    }

    @Override public Optional<Snapshot> find(UUID snapshotId,Instant now) {
        return jdbc.sql("SELECT filter_hash,CAST(stream_ids AS text) FROM discovery.ranking_snapshots WHERE snapshot_id=:id AND expires_at_utc>:now")
                .param("id",snapshotId).param("now",JdbcProjectionStore.timestamp(now))
                .query((rs,n)->new Snapshot(snapshotId,rs.getString(1),ids(json.readTree(rs.getString(2)))) ).optional();
    }

    @Override public int purgeExpired(Instant now,int limit) {
        return jdbc.sql("DELETE FROM discovery.ranking_snapshots WHERE snapshot_id IN (SELECT snapshot_id FROM discovery.ranking_snapshots "
                +"WHERE expires_at_utc<=:now LIMIT :limit)").param("now",JdbcProjectionStore.timestamp(now)).param("limit",limit).update();
    }

    private static List<String> ids(JsonNode array) {
        var ids=new java.util.ArrayList<String>();
        for(JsonNode id:array) ids.add(id.textValue());
        return ids;
    }
}
