package streaming.core.taxonomy.infrastructure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

@Testcontainers
class TaxonomyIdMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    String database,url;

    @BeforeEach void createDatabase() throws Exception {
        database="taxonomy_ids_"+UUID.randomUUID().toString().replace("-","");
        try(var c=connect(POSTGRES.getJdbcUrl());var s=c.createStatement()) { s.execute("CREATE DATABASE "+database); }
        url="jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/"+database;
    }
    @AfterEach void dropDatabase() throws Exception {
        try(var c=connect(POSTGRES.getJdbcUrl());var s=c.createStatement()) { s.execute("DROP DATABASE "+database+" WITH (FORCE)"); }
    }
    @Test void upgradePreservesOpaqueIdsTombstonesVersionAndChecksums() throws Exception {
        migrate("3");
        try(var c=connect(url);var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO taxonomy.categories VALUES ('opaque-without-prefix','Retained',false)");
            long version=scalar(c,"SELECT catalog_version FROM taxonomy.catalog_state");
            Map<String,Integer> before=checksums(c);
            migrate("4");
            assertThat(checksums(c)).containsAllEntriesOf(before).hasSize(4);
            assertThat(scalar(c,"SELECT catalog_version FROM taxonomy.catalog_state")).isEqualTo(version);
            assertThat(scalar(c,"SELECT count(*) FROM taxonomy.value_ids")).isEqualTo(16);
            assertThat(scalar(c,"SELECT count(*) FROM taxonomy.public_categories WHERE id='opaque-without-prefix' AND name='Retained' AND NOT active")).isEqualTo(1);
            assertThat(scalar(c,"SELECT count(*) FROM taxonomy.value_ids WHERE id='opaque-without-prefix' AND kind='CATEGORY'")).isEqualTo(1);
            assertThat(flyway("4").migrate().migrationsExecuted).isZero();
        }
    }
    @ParameterizedTest @ValueSource(strings={"categories","tags"})
    void crossTypeCollisionFailsWithoutAdvancingVersion(String first) throws Exception {
        migrate("4");
        String second=first.equals("categories")?"tags":"categories";
        try(var c=connect(url);var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO taxonomy."+first+" VALUES ('shared-id','First',false)");
            long version=scalar(c,"SELECT catalog_version FROM taxonomy.catalog_state");
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO taxonomy."+second+" VALUES ('shared-id','Second',true)"))
                    .isInstanceOf(SQLException.class).extracting(e->((SQLException)e).getSQLState()).isEqualTo("23505");
            assertThat(scalar(c,"SELECT count(*) FROM taxonomy."+second+" WHERE id='shared-id'")).isZero();
            assertThat(scalar(c,"SELECT catalog_version FROM taxonomy.catalog_state")).isEqualTo(version);
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void concurrentInsertRespectsCommitOrRollbackOfFirstReservation(boolean commit) throws Exception {
        migrate("4");
        try(var first=connect(url);var second=connect(url);var executor=Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            try(var s=first.createStatement()) { s.executeUpdate("INSERT INTO taxonomy.categories VALUES ('race-id','First',true)"); }
            var started=new CountDownLatch(1);
            var pending=executor.submit(()-> {
                try(var s=second.createStatement()) {
                    s.execute("SET statement_timeout='10s'");
                    started.countDown();
                    s.executeUpdate("INSERT INTO taxonomy.tags VALUES ('race-id','Second',true)");
                    return "OK";
                } catch(SQLException error) { return error.getSQLState(); }
            });
            try {
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->pending.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                if(commit) first.commit(); else first.rollback();
            }
            assertThat(pending.get(10,TimeUnit.SECONDS)).isEqualTo(commit?"23505":"OK");
            assertThat(scalar(second,"SELECT count(*) FROM taxonomy.value_ids WHERE id='race-id'")).isEqualTo(1);
            assertThat(scalar(second,"SELECT catalog_version FROM taxonomy.catalog_state")).isEqualTo(2);
        }
    }
    @Test void idempotentInsertAndRollbackPreserveReservationsAndVersion() throws Exception {
        migrate("4");
        try(var c=connect(url);var s=c.createStatement()) {
            String insert="INSERT INTO taxonomy.categories VALUES ('retry-id','Value',true)";
            s.executeUpdate(insert);
            assertThat(s.executeUpdate(insert+" ON CONFLICT (id) DO NOTHING")).isZero();
            s.executeUpdate(insert+" ON CONFLICT (id) DO UPDATE SET name=excluded.name");
            assertThat(scalar(c,"SELECT catalog_version FROM taxonomy.catalog_state")).isEqualTo(2);
            c.setAutoCommit(false);
            s.executeUpdate("INSERT INTO taxonomy.tags VALUES ('rollback-id','Value',true)");
            c.rollback(); c.setAutoCommit(true);
            assertThat(scalar(c,"SELECT count(*) FROM taxonomy.value_ids WHERE id='rollback-id'")).isZero();
            assertThat(scalar(c,"SELECT catalog_version FROM taxonomy.catalog_state")).isEqualTo(2);
            s.executeUpdate("INSERT INTO taxonomy.categories VALUES ('rollback-id','Reused after rollback',true)");
        }
    }
    @Test void reservationsCannotBeRetypedRenamedDeletedOrTruncated() throws Exception {
        migrate("4");
        try(var c=connect(url);var s=c.createStatement()) {
            for(String sql:new String[]{"UPDATE taxonomy.value_ids SET kind='TAG' WHERE kind='CATEGORY'",
                    "UPDATE taxonomy.value_ids SET id=id||'_changed'","DELETE FROM taxonomy.value_ids","TRUNCATE taxonomy.value_ids"})
                assertThatThrownBy(()->s.executeUpdate(sql)).isInstanceOf(SQLException.class);
            assertThat(scalar(c,"SELECT count(*) FROM taxonomy.value_ids")).isEqualTo(15);
            assertThat(scalar(c,"SELECT catalog_version FROM taxonomy.catalog_state")).isEqualTo(1);
        }
    }
    @Test void inconsistentV3FailsAtomicallyWithoutRepairingOrRenamingData() throws Exception {
        migrate("3");
        try(var c=connect(url);var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO taxonomy.tags SELECT id,'Collision',true FROM taxonomy.categories LIMIT 1");
            Map<String,Integer> before=checksums(c);
            long version=scalar(c,"SELECT catalog_version FROM taxonomy.catalog_state");
            assertThatThrownBy(()->migrate("4")).hasStackTraceContaining("Catalogue ID collision");
            assertThat(checksums(c)).isEqualTo(before);
            assertThat(scalar(c,"SELECT count(*) FROM information_schema.tables WHERE table_schema='taxonomy' AND table_name='value_ids'")).isZero();
            assertThat(scalar(c,"SELECT count(*) FROM taxonomy.categories c JOIN taxonomy.tags t ON t.id=c.id")).isEqualTo(1);
            assertThat(scalar(c,"SELECT catalog_version FROM taxonomy.catalog_state")).isEqualTo(version);
        }
    }
    Connection connect(String jdbcUrl) throws SQLException { return DriverManager.getConnection(jdbcUrl,POSTGRES.getUsername(),POSTGRES.getPassword()); }
    Flyway flyway(String target) { return Flyway.configure().dataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword())
            .schemas("core","identity","profile","channels","taxonomy").defaultSchema("core").target(target).load(); }
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
