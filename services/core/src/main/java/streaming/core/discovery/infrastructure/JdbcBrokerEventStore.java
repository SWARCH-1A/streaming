package streaming.core.discovery.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.core.discovery.application.BrokerEventStore;
import streaming.core.discovery.domain.SessionLifecycle;

@Repository
public class JdbcBrokerEventStore implements BrokerEventStore {
    private final JdbcClient jdbc;

    public JdbcBrokerEventStore(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Override public boolean recordInbox(String consumer,String eventId,String fingerprint) {
        return jdbc.sql("INSERT INTO discovery.broker_inbox(consumer,event_id,fingerprint,outcome) "
                +"VALUES (:consumer,:id,:hash,'RECEIVED') ON CONFLICT DO NOTHING")
                .param("consumer",consumer).param("id",eventId).param("hash",fingerprint).update()==1;
    }

    @Override public String inboxFingerprint(String consumer,String eventId) {
        return jdbc.sql("SELECT fingerprint FROM discovery.broker_inbox WHERE consumer=:consumer AND event_id=:id")
                .param("consumer",consumer).param("id",eventId).query(String.class).single();
    }

    @Override public void setInboxOutcome(String consumer,String eventId,String outcome) {
        jdbc.sql("UPDATE discovery.broker_inbox SET outcome=:outcome WHERE consumer=:consumer AND event_id=:id")
                .param("outcome",outcome).param("consumer",consumer).param("id",eventId).update();
    }

    @Override public void deleteInbox(String consumer,String eventId) {
        jdbc.sql("DELETE FROM discovery.broker_inbox WHERE consumer=:consumer AND event_id=:id")
                .param("consumer",consumer).param("id",eventId).update();
    }

    @Override public boolean insertLifecycleIfAbsent(SessionLifecycle incoming) {
        return jdbc.sql("INSERT INTO discovery.session_lifecycle(stream_id,channel_id,session_id,stream_generation,session_version,"
                +"status,availability,first_live_at_utc,occurred_at_utc,state_fingerprint) "
                +"VALUES (:stream,:channel,:session,:generation,:version,:status,:availability,:first,:occurred,:hash) ON CONFLICT DO NOTHING")
                .params(parameters(incoming)).update()==1;
    }

    @Override public LifecycleVersion lockLifecycle(String streamId) {
        return jdbc.sql("SELECT channel_id,stream_generation,session_version,state_fingerprint,status "
                +"FROM discovery.session_lifecycle WHERE stream_id=:stream FOR UPDATE")
                .param("stream",streamId).query((rs,n)->new LifecycleVersion(rs.getString(1),rs.getLong(2),rs.getLong(3),
                        rs.getString(4),rs.getString(5))).single();
    }

    @Override public void updateLifecycle(SessionLifecycle incoming) {
        jdbc.sql("UPDATE discovery.session_lifecycle SET session_id=:session,stream_generation=:generation,session_version=:version,"
                +"status=:status,availability=:availability,first_live_at_utc=:first,occurred_at_utc=:occurred,state_fingerprint=:hash "
                +"WHERE stream_id=:stream")
                .params(parameters(incoming)).update();
    }

    private static Map<String,Object> parameters(SessionLifecycle incoming) {
        var parameters=new HashMap<String,Object>();
        parameters.put("stream",incoming.streamId()); parameters.put("channel",incoming.channelId());
        parameters.put("session",incoming.sessionId()); parameters.put("generation",incoming.streamGeneration());
        parameters.put("version",incoming.sessionVersion()); parameters.put("status",incoming.status());
        parameters.put("availability",incoming.availability()); parameters.put("first",timestamp(incoming.firstLiveAtUtc()));
        parameters.put("occurred",timestamp(incoming.occurredAtUtc())); parameters.put("hash",incoming.fingerprint());
        return parameters;
    }

    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
}
