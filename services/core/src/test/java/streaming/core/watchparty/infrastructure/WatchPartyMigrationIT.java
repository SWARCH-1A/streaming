package streaming.core.watchparty.infrastructure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class WatchPartyMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    private static final String PARTY="wp_"+"a".repeat(32);

    @Test void upgradeFromV4PreservesExistingDataAndAddsTheWatchPartySchema() throws Exception {
        flyway("4").migrate();
        try(Connection connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                Statement sql=connection.createStatement()) {
            sql.executeUpdate("INSERT INTO identity.accounts VALUES ('usr_preserved','migration@example.test','migration@example.test','migration_01','migration_01','test-only-unused-hash',now())");
            sql.executeUpdate("INSERT INTO profile.profiles VALUES ('usr_preserved','Migration','',NULL,0,now(),now())");
            sql.executeUpdate("INSERT INTO channels.channels(channel_id,owner_user_id,description,channel_version,created_at_utc,updated_at_utc) VALUES ('chn_preserved','usr_preserved','Existing',3,now(),now())");
            long catalogVersion=scalar(sql,"SELECT catalog_version FROM taxonomy.catalog_state");
            Map<String,Integer> checksums=checksums(sql);
            assertThat(checksums).containsOnlyKeys("1","2","3","4");

            Flyway upgrade=flyway(null);
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            upgrade.validate();

            assertThat(checksums(sql)).containsAllEntriesOf(checksums).containsKey("5");
            assertThat(scalar(sql,"SELECT count(*) FROM channels.channels WHERE channel_id='chn_preserved' AND channel_version=3")).isEqualTo(1);
            assertThat(scalar(sql,"SELECT catalog_version FROM taxonomy.catalog_state")).isEqualTo(catalogVersion);
            assertThat(scalar(sql,"SELECT count(*) FROM information_schema.tables WHERE table_schema='watchparty'")).isEqualTo(3);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
        }
    }

    @Test void theSchemaEnforcesItsInvariantsEvenWithoutTheApplication() throws Exception {
        flyway(null).migrate();
        try(Connection connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                Statement sql=connection.createStatement()) {
            sql.executeUpdate("INSERT INTO identity.accounts VALUES ('usr_owner','o@example.test','o@example.test','owner_01','owner_01','x',now())");
            sql.executeUpdate("INSERT INTO identity.accounts VALUES ('usr_other','p@example.test','p@example.test','other_01','other_01','x',now())");
            sql.executeUpdate(party(PARTY,"usr_owner","Final","h1","OPEN",null));

            rejects(sql,party("wp_"+"b".repeat(32),"usr_owner","Final","h2","PAUSED",null));
            rejects(sql,party("wp_"+"b".repeat(32),"usr_owner","Final","h2","CLOSED",null));
            rejects(sql,party("wp_"+"b".repeat(32),"usr_owner","Final","h2","OPEN","now()"));
            rejects(sql,party("wp_"+"b".repeat(32),"usr_owner","   ","h2","OPEN",null));
            rejects(sql,party("wp_"+"b".repeat(32),"usr_owner","x".repeat(101),"h2","OPEN",null));
            rejects(sql,party("not_a_party","usr_owner","Final","h2","OPEN",null));
            rejects(sql,party("wp_"+"b".repeat(32),"usr_missing","Final","h2","OPEN",null));
            rejects(sql,party("wp_"+"b".repeat(32),"usr_owner","Final","h1","OPEN",null));
            rejects(sql,party(PARTY,"usr_owner","Final","h3","OPEN",null));

            sql.executeUpdate("INSERT INTO watchparty.party_members VALUES ('"+PARTY+"','usr_other',now())");
            rejects(sql,"INSERT INTO watchparty.party_members VALUES ('"+PARTY+"','usr_other',now())");
            rejects(sql,"INSERT INTO watchparty.party_members VALUES ('"+PARTY+"','usr_missing',now())");

            sql.executeUpdate("INSERT INTO watchparty.party_streams VALUES ('"+PARTY+"','str_1','chn_1',now())");
            rejects(sql,"INSERT INTO watchparty.party_streams VALUES ('"+PARTY+"','str_1','chn_2',now())");
            rejects(sql,"INSERT INTO watchparty.party_streams VALUES ('"+PARTY+"','a/b','chn_2',now())");
            rejects(sql,"INSERT INTO watchparty.party_streams VALUES ('"+PARTY+"','str_2','../x',now())");
            rejects(sql,"INSERT INTO watchparty.party_streams VALUES ('wp_"+"c".repeat(32)+"','str_2','chn_2',now())");

            sql.executeUpdate("UPDATE watchparty.parties SET status='CLOSED',closed_at_utc=now() WHERE party_id='"+PARTY+"'");
            // Deleting an account removes the parties it owns and its memberships, with their streams.
            sql.executeUpdate("DELETE FROM identity.accounts WHERE user_id='usr_owner'");
            assertThat(scalar(sql,"SELECT count(*) FROM watchparty.parties")).isZero();
            assertThat(scalar(sql,"SELECT count(*) FROM watchparty.party_members")).isZero();
            assertThat(scalar(sql,"SELECT count(*) FROM watchparty.party_streams")).isZero();
        }
    }

    private static String party(String id,String owner,String title,String hash,String status,String closedAt) {
        return "INSERT INTO watchparty.parties(party_id,owner_user_id,title,access_code_hash,status,party_version,created_at_utc,updated_at_utc,closed_at_utc) "
                +"VALUES ('"+id+"','"+owner+"','"+title+"','"+hash.repeat(1)+"','"+status+"',0,now(),now(),"+(closedAt==null?"NULL":closedAt)+")";
    }
    private static void rejects(Statement sql,String statement) {
        assertThatThrownBy(()->sql.executeUpdate(statement)).as(statement).isInstanceOf(SQLException.class);
    }
    private static long scalar(Statement sql,String query) throws SQLException {
        try(var row=sql.executeQuery(query)) { row.next(); return row.getLong(1); }
    }
    private static Map<String,Integer> checksums(Statement sql) throws SQLException {
        Map<String,Integer> found=new HashMap<>();
        try(var rows=sql.executeQuery("SELECT version,checksum FROM core.flyway_schema_history WHERE version IS NOT NULL")) {
            while(rows.next()) found.put(rows.getString(1),rows.getInt(2));
        }
        return found;
    }
    private static Flyway flyway(String target) {
        var configuration=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .schemas("core","identity","profile","channels","taxonomy","watchparty").defaultSchema("core");
        if(target!=null) configuration.target(target);
        return configuration.load();
    }
}
