package streaming.core.discovery.infrastructure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V5 on top of existing data, and the integrity rules the Discovery tables enforce by themselves. */
@Testcontainers
class DiscoveryMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    String database,url;

    @BeforeEach void createDatabase() throws Exception {
        database="discovery_"+UUID.randomUUID().toString().replace("-","");
        try(var c=connect(POSTGRES.getJdbcUrl());var s=c.createStatement()) { s.execute("CREATE DATABASE "+database); }
        url="jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/"+database;
    }
    @AfterEach void dropDatabase() throws Exception {
        try(var c=connect(POSTGRES.getJdbcUrl());var s=c.createStatement()) { s.execute("DROP DATABASE "+database+" WITH (FORCE)"); }
    }

    @Test void upgradeKeepsExistingDataAndChecksumsAndExposesOnlyPublicChannelColumns() throws Exception {
        migrate("4");
        try(var c=connect(url);var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO identity.accounts VALUES ('usr_1','a@b.test','a@b.test','Ana','ana','hash',now())");
            s.executeUpdate("INSERT INTO profile.profiles(user_id,display_name,created_at_utc,updated_at_utc) VALUES ('usr_1','Ana',now(),now())");
            s.executeUpdate("INSERT INTO channels.channels(channel_id,owner_user_id,description,channel_version,created_at_utc,updated_at_utc) VALUES ('chn_1','usr_1','secret description',4,now(),now())");
            Map<String,Integer> before=checksums(c);
            long catalog=scalar(c,"SELECT catalog_version FROM taxonomy.catalog_state");

            migrate("5");

            assertThat(checksums(c)).containsAllEntriesOf(before).hasSize(before.size()+1);
            assertThat(scalar(c,"SELECT count(*) FROM identity.accounts")).isEqualTo(1);
            assertThat(scalar(c,"SELECT count(*) FROM channels.channels")).isEqualTo(1);
            assertThat(scalar(c,"SELECT catalog_version FROM taxonomy.catalog_state")).isEqualTo(catalog);
            assertThat(scalar(c,"SELECT channel_version FROM channels.public_channels WHERE channel_id='chn_1' AND owner_user_id='usr_1'")).isEqualTo(4);
            try(var r=s.executeQuery("SELECT * FROM channels.public_channels")) {
                assertThat(r.getMetaData().getColumnCount()).isEqualTo(3);
                assertThat(java.util.stream.IntStream.rangeClosed(1,3).mapToObj(i->{ try { return r.getMetaData().getColumnName(i); } catch(SQLException e) { throw new IllegalStateException(e); } }).toList())
                        .containsExactly("channel_id","owner_user_id","channel_version");
            }
            assertThat(scalar(c,"SELECT count(*) FROM discovery.reconciliation_state")).isEqualTo(1);
            assertThat(scalar(c,"SELECT count(*) FROM discovery.stream_projection")).isZero();
            assertThat(flyway("5").migrate().migrationsExecuted).as("idempotent").isZero();
        }
    }

    @Test void theSingletonReconciliationStateCannotBeDuplicatedOrDeleted() throws Exception {
        migrate("5");
        try(var c=connect(url);var s=c.createStatement()) {
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO discovery.reconciliation_state(singleton) VALUES (TRUE)")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO discovery.reconciliation_state(singleton) VALUES (FALSE)")).isInstanceOf(SQLException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings={
            "'str_1','chn_1',0,1,'ONLINE','PLAYABLE'",                       // unknown status
            "'str_1','chn_1',1,1,'LIVE','WATCHABLE'",                        // unknown availability
            "'str_1','chn_1',0,1,'LIVE','PLAYABLE'",                         // projection version below 1
            "'str_1','chn_1',1,0,'LIVE','PLAYABLE'",                         // discovery position below 1
            "'bad id','chn_1',1,1,'LIVE','PLAYABLE'",                        // identifier shape
            "'str_1','chn 1',1,1,'LIVE','PLAYABLE'"})
    void theProjectionRejectsValuesTheContractDoesNotAllow(String values) throws Exception {
        migrate("5");
        try(var c=connect(url);var s=c.createStatement()) {
            assertThatThrownBy(()->s.executeUpdate(insertProjection(values))).isInstanceOf(SQLException.class)
                    .extracting(e->((SQLException)e).getSQLState()).isEqualTo("23514");
            assertThat(scalar(c,"SELECT count(*) FROM discovery.stream_projection")).isZero();
        }
    }

    @Test void aReachableStreamMustBeLiveWithASessionAndOnlyOfflineMayLackOne() throws Exception {
        migrate("5");
        try(var c=connect(url);var s=c.createStatement()) {
            // PLAYABLE/RECONNECTING without a session, or with a non-LIVE status, is impossible by construction.
            for(String bad:new String[]{
                    "'str_1','chn_1',1,1,'LIVE','PLAYABLE'",                 // helper below leaves the session empty
                    "'str_2','chn_2',1,1,'ENDED','PLAYABLE'"}) {
                assertThatThrownBy(()->s.executeUpdate(insertProjection(bad,false))).isInstanceOf(SQLException.class)
                        .extracting(e->((SQLException)e).getSQLState()).isEqualTo("23514");
            }
            s.executeUpdate(insertProjection("'str_ok','chn_ok',1,1,'LIVE','PLAYABLE'",true));
            s.executeUpdate(insertProjection("'str_off','chn_off',1,1,'OFFLINE','OFFLINE'",false));
            s.executeUpdate(insertProjection("'str_end','chn_end',1,1,'ENDED','OFFLINE'",true));
            assertThat(scalar(c,"SELECT count(*) FROM discovery.stream_projection")).isEqualTo(3);
        }
    }

    @Test void oneStreamPerChannelAndOneRowPerStream() throws Exception {
        migrate("5");
        try(var c=connect(url);var s=c.createStatement()) {
            s.executeUpdate(insertProjection("'str_1','chn_1',1,1,'OFFLINE','OFFLINE'",false));
            assertThatThrownBy(()->s.executeUpdate(insertProjection("'str_2','chn_1',1,1,'OFFLINE','OFFLINE'",false))).isInstanceOf(SQLException.class)
                    .extracting(e->((SQLException)e).getSQLState()).isEqualTo("23505");
            assertThatThrownBy(()->s.executeUpdate(insertProjection("'str_1','chn_9',1,1,'OFFLINE','OFFLINE'",false))).isInstanceOf(SQLException.class)
                    .extracting(e->((SQLException)e).getSQLState()).isEqualTo("23505");
        }
    }

    @Test void inboxConflictAndSnapshotTablesEnforceTheirVocabulary() throws Exception {
        migrate("5");
        try(var c=connect(url);var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO discovery.inbox_events VALUES ('evt_1',repeat('a',64),'str_1',1,'APPLIED',now())");
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO discovery.inbox_events VALUES ('evt_1',repeat('a',64),'str_1',1,'APPLIED',now())")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO discovery.inbox_events VALUES ('evt_2',repeat('a',64),'str_1',1,'MAYBE',now())")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO discovery.inbox_events VALUES ('bad id',repeat('a',64),'str_1',1,'APPLIED',now())")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO discovery.projection_conflicts(stream_id,reason,incoming_hash,payload,detected_at_utc) VALUES ('str_1','OTHER',repeat('a',64),'{}',now())"))
                    .isInstanceOf(SQLException.class);
            s.executeUpdate("INSERT INTO discovery.projection_conflicts(stream_id,reason,incoming_hash,payload,detected_at_utc) VALUES ('str_1','CHANNEL_MISMATCH',repeat('a',64),'{}',now())");
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO discovery.ranking_snapshots VALUES (gen_random_uuid(),repeat('a',64),'[]',now(),now())")).as("expiry after creation").isInstanceOf(SQLException.class);
            s.executeUpdate("INSERT INTO discovery.ranking_snapshots VALUES (gen_random_uuid(),repeat('a',64),'[\"str_1\"]',now(),now()+interval '5 minutes')");
        }
    }

    private static String insertProjection(String leading) { return insertProjection(leading,true); }

    /** @param values stream_id, channel_id, projection_version, discovery_position, status, availability */
    private static String insertProjection(String values,boolean withSession) {
        String session=withSession?"'ses_1',4,now()":"NULL,NULL,NULL";
        return "INSERT INTO discovery.stream_projection(stream_id,channel_id,projection_version,discovery_position,status,availability,session_id,session_version,started_at_utc,"
                +"content_hash,metadata_version,title,normalized_title,category_id,category_name,stream_generation,state_observed_at_utc,viewer_count,count_version,received_at_utc,applied_at_utc) "
                +"VALUES ("+values+","+session+",repeat('b',64),1,'t','t','cat_1','C',0,now(),0,0,now(),now())";
    }

    Connection connect(String jdbcUrl) throws SQLException { return DriverManager.getConnection(jdbcUrl,POSTGRES.getUsername(),POSTGRES.getPassword()); }
    Flyway flyway(String target) { return Flyway.configure().dataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword())
            .schemas("core","identity","profile","channels","taxonomy","discovery").defaultSchema("core").target(target).load(); }
    void migrate(String target) { flyway(target).migrate(); }
    static long scalar(Connection c,String sql) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery(sql)) { assertThat(r.next()).isTrue(); return r.getLong(1); }
    }
    static Map<String,Integer> checksums(Connection c) throws SQLException {
        var result=new LinkedHashMap<String,Integer>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT version,checksum FROM core.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
            while(r.next()) result.put(r.getString(1),r.getInt(2));
        }
        return result;
    }
}
