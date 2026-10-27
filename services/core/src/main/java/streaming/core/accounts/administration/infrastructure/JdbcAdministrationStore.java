package streaming.core.accounts.administration.infrastructure;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import streaming.core.accounts.administration.application.AdministrationStore;

@Repository
public class JdbcAdministrationStore implements AdministrationStore {
    private final JdbcClient jdbc;
    public JdbcAdministrationStore(JdbcClient jdbc) { this.jdbc=jdbc; }
    public boolean isAdministrator(String userId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM identity.accounts WHERE user_id=:id AND account_status='ACTIVE' AND account_role='ADMIN')")
            .param("id",userId).query(Boolean.class).single();
    }
    public void lock(String actor, String target, UUID operationId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))").param("key","admin:"+actor+":"+operationId).query(rs->{rs.next();return true;});
        jdbc.sql("SELECT user_id FROM identity.accounts WHERE user_id IN (:actor,:target) ORDER BY user_id FOR UPDATE")
            .param("actor",actor).param("target",target).query(String.class).list();
    }
    public List<Account> accounts(String after, int limit) {
        return jdbc.sql("SELECT user_id,handle,account_status,account_version FROM identity.accounts WHERE user_id>:after ORDER BY user_id LIMIT :limit")
            .param("after",after).param("limit",limit).query((rs,n)->new Account(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4))).list();
    }
    public Optional<Account> account(String userId) {
        return jdbc.sql("SELECT user_id,handle,account_status,account_version FROM identity.accounts WHERE user_id=:id")
            .param("id",userId).query((rs,n)->new Account(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4))).optional();
    }
    public Optional<Operation> operation(String actor, UUID operationId) {
        return jdbc.sql("SELECT target_user_id,requested_status,expected_version,reason,result_status,result_version,result_handle FROM identity.admin_operations WHERE actor_id=:actor AND operation_id=:id")
            .param("actor",actor).param("id",operationId).query((rs,n)->new Operation(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),
                new Account(rs.getString(1),rs.getString(7),rs.getString(5),rs.getLong(6)))).optional();
    }
    public Account change(String target, String status, Instant now) {
        jdbc.sql("UPDATE identity.accounts SET account_status=:status,account_version=account_version+1 WHERE user_id=:id")
            .param("status",status).param("id",target).update();
        if (status.equals("SUSPENDED")) jdbc.sql("UPDATE identity.sessions SET revoked_at_utc=:now WHERE user_id=:id AND revoked_at_utc IS NULL")
            .param("id",target).param("now",Timestamp.from(now)).update();
        return account(target).orElseThrow();
    }
    public void record(String actor, UUID key, Operation op, Instant now) {
        jdbc.sql("INSERT INTO identity.admin_operations VALUES (:actor,:key,:target,:status,:expected,:reason,:result,:version,:handle,:now)")
            .param("actor",actor).param("key",key).param("target",op.target()).param("status",op.status()).param("expected",op.expectedVersion())
            .param("reason",op.reason()).param("result",op.result().status()).param("version",op.result().accountVersion())
            .param("handle",op.result().handle()).param("now",Timestamp.from(now)).update();
        jdbc.sql("INSERT INTO identity.admin_audit(actor_id,target_user_id,action,reason,result,occurred_at_utc) VALUES (:actor,:target,'ACCOUNT_STATUS',:reason,:result,:now)")
            .param("actor",actor).param("target",op.target()).param("reason",op.reason()).param("result",op.result().status()).param("now",Timestamp.from(now)).update();
    }
    public void enqueue(String userId, long version, UUID eventId, String envelope, Instant now) {
        jdbc.sql("INSERT INTO identity.account_status_outbox(event_id,user_id,account_version,envelope,created_at_utc) VALUES (:id,:user,:version,:body,:now)")
            .param("id",eventId).param("user",userId).param("version",version).param("body",envelope).param("now",Timestamp.from(now)).update();
    }
}
