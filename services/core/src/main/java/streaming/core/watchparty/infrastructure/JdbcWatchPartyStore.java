package streaming.core.watchparty.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.core.watchparty.application.WatchPartyStore;

@Repository
public class JdbcWatchPartyStore implements WatchPartyStore {
    private static final String PARTY_COLUMNS="party_id,owner_user_id,title,status,party_version,created_at_utc,updated_at_utc,closed_at_utc";
    private final JdbcClient jdbc;

    public JdbcWatchPartyStore(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Override public Party create(String partyId,String ownerUserId,String title,String accessCodeHash,Instant now) {
        Party party=jdbc.sql("INSERT INTO watchparty.parties(party_id,owner_user_id,title,access_code_hash,status,party_version,created_at_utc,updated_at_utc) "
                +"VALUES (:id,:owner,:title,:hash,'OPEN',0,:now,:now) RETURNING "+PARTY_COLUMNS)
                .param("id",partyId).param("owner",ownerUserId).param("title",title).param("hash",accessCodeHash)
                .param("now",Timestamp.from(now)).query(JdbcWatchPartyStore::party).single();
        addMember(partyId,ownerUserId,now);
        return party;
    }

    @Override public Optional<Party> find(String partyId) {
        return jdbc.sql("SELECT "+PARTY_COLUMNS+" FROM watchparty.parties WHERE party_id=:id")
                .param("id",partyId).query(JdbcWatchPartyStore::party).optional();
    }

    @Override public Optional<Party> lock(String partyId) {
        return jdbc.sql("SELECT "+PARTY_COLUMNS+" FROM watchparty.parties WHERE party_id=:id FOR UPDATE")
                .param("id",partyId).query(JdbcWatchPartyStore::party).optional();
    }

    @Override public Optional<Party> lockByCodeHash(String accessCodeHash) {
        return jdbc.sql("SELECT "+PARTY_COLUMNS+" FROM watchparty.parties WHERE access_code_hash=:hash FOR UPDATE")
                .param("hash",accessCodeHash).query(JdbcWatchPartyStore::party).optional();
    }

    @Override public boolean isMember(String partyId,String userId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM watchparty.party_members WHERE party_id=:party AND user_id=:user)")
                .param("party",partyId).param("user",userId).query(Boolean.class).single();
    }

    @Override public boolean addMember(String partyId,String userId,Instant now) {
        return jdbc.sql("INSERT INTO watchparty.party_members(party_id,user_id,joined_at_utc) VALUES (:party,:user,:now) ON CONFLICT DO NOTHING")
                .param("party",partyId).param("user",userId).param("now",Timestamp.from(now)).update()==1;
    }

    @Override public int memberCount(String partyId) {
        return jdbc.sql("SELECT count(*) FROM watchparty.party_members WHERE party_id=:party")
                .param("party",partyId).query(Integer.class).single();
    }

    @Override public List<PartyStream> streams(String partyId) {
        return jdbc.sql("SELECT stream_id,channel_id,added_at_utc FROM watchparty.party_streams WHERE party_id=:party ORDER BY added_at_utc,stream_id")
                .param("party",partyId).query((rs,n)->new PartyStream(rs.getString(1),rs.getString(2),rs.getTimestamp(3).toInstant())).list();
    }

    @Override public int countStreams(String partyId) {
        return jdbc.sql("SELECT count(*) FROM watchparty.party_streams WHERE party_id=:party")
                .param("party",partyId).query(Integer.class).single();
    }

    @Override public boolean hasStream(String partyId,String streamId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM watchparty.party_streams WHERE party_id=:party AND stream_id=:stream)")
                .param("party",partyId).param("stream",streamId).query(Boolean.class).single();
    }

    @Override public void addStream(String partyId,String streamId,String channelId,Instant now) {
        jdbc.sql("INSERT INTO watchparty.party_streams(party_id,stream_id,channel_id,added_at_utc) VALUES (:party,:stream,:channel,:now)")
                .param("party",partyId).param("stream",streamId).param("channel",channelId).param("now",Timestamp.from(now)).update();
    }

    @Override public boolean removeStream(String partyId,String streamId) {
        return jdbc.sql("DELETE FROM watchparty.party_streams WHERE party_id=:party AND stream_id=:stream")
                .param("party",partyId).param("stream",streamId).update()==1;
    }

    @Override public Party bump(String partyId,Instant now) {
        return jdbc.sql("UPDATE watchparty.parties SET party_version=party_version+1,updated_at_utc=:now WHERE party_id=:id RETURNING "+PARTY_COLUMNS)
                .param("now",Timestamp.from(now)).param("id",partyId).query(JdbcWatchPartyStore::party).single();
    }

    @Override public Party rotateCode(String partyId,String accessCodeHash,Instant now) {
        return jdbc.sql("UPDATE watchparty.parties SET access_code_hash=:hash,party_version=party_version+1,updated_at_utc=:now "
                +"WHERE party_id=:id RETURNING "+PARTY_COLUMNS)
                .param("hash",accessCodeHash).param("now",Timestamp.from(now)).param("id",partyId).query(JdbcWatchPartyStore::party).single();
    }

    @Override public Party close(String partyId,Instant now) {
        return jdbc.sql("UPDATE watchparty.parties SET status='CLOSED',closed_at_utc=:now,party_version=party_version+1,updated_at_utc=:now "
                +"WHERE party_id=:id RETURNING "+PARTY_COLUMNS)
                .param("now",Timestamp.from(now)).param("id",partyId).query(JdbcWatchPartyStore::party).single();
    }

    private static Party party(ResultSet rs,int n) throws SQLException {
        Timestamp closed=rs.getTimestamp(8);
        return new Party(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),
                rs.getTimestamp(6).toInstant(),rs.getTimestamp(7).toInstant(),closed==null?null:closed.toInstant());
    }
}
