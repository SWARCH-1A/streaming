package streaming.core.taxonomy.infrastructure;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class TaxonomyMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    private String database;
    private String url;

    @BeforeEach void createIsolatedDatabase() throws Exception {
        database="taxonomy_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=connection(POSTGRES.getJdbcUrl());var sql=connection.createStatement()) {
            sql.execute("CREATE DATABASE "+database);
        }
        url="jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/"+database;
    }

    @AfterEach void dropIsolatedDatabase() throws Exception {
        try(var connection=connection(POSTGRES.getJdbcUrl());var sql=connection.createStatement()) {
            sql.execute("DROP DATABASE "+database+" WITH (FORCE)");
        }
    }

    @Test void upgradeFromV2PreservesAccountsChannelsAndMigrationChecksums() throws Exception {
        flyway("2").migrate();
        try(var connection=connection(url);var sql=connection.createStatement()) {
            sql.executeUpdate("INSERT INTO identity.accounts VALUES ('usr_tax_migration','taxonomy@example.test','taxonomy@example.test','taxonomy_01','taxonomy_01','test-only-unused-hash',now())");
            sql.executeUpdate("INSERT INTO profile.profiles VALUES ('usr_tax_migration','Taxonomy migration','Existing bio',NULL,2,now(),now())");
            sql.executeUpdate("INSERT INTO channels.channels(channel_id,owner_user_id,description,banner_key,channel_version,created_at_utc,updated_at_utc) "
                    +"VALUES ('chn_tax_migration','usr_tax_migration','Existing channel',NULL,4,now(),now())");
            sql.executeUpdate("INSERT INTO identity.registrations VALUES ('reg_tax_migration',repeat('c',64),repeat('d',64),'usr_tax_migration','chn_tax_migration',now(),now()+interval '30 days')");
            Map<String,Integer> checksums=checksums(connection);

            var upgrade=flyway("3");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            upgrade.validate();
            assertThat(checksums(connection)).containsAllEntriesOf(checksums);
            try(var row=sql.executeQuery("SELECT a.user_id,a.canonical_handle,p.bio,p.profile_version,c.channel_id,c.description,c.channel_version,r.channel_id "
                    +"FROM identity.accounts a JOIN profile.profiles p ON p.user_id=a.user_id "
                    +"JOIN channels.channels c ON c.owner_user_id=a.user_id JOIN identity.registrations r ON r.user_id=a.user_id")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("usr_tax_migration");
                assertThat(row.getString(2)).isEqualTo("taxonomy_01");
                assertThat(row.getString(3)).isEqualTo("Existing bio");
                assertThat(row.getLong(4)).isEqualTo(2);
                assertThat(row.getString(5)).isEqualTo("chn_tax_migration");
                assertThat(row.getString(6)).isEqualTo("Existing channel");
                assertThat(row.getLong(7)).isEqualTo(4);
                assertThat(row.getString(8)).isEqualTo("chn_tax_migration");
                assertThat(row.next()).isFalse();
            }
            assertThat(scalar(connection,"SELECT count(*) FROM taxonomy.categories WHERE active")).isEqualTo(7);
            assertThat(scalar(connection,"SELECT count(*) FROM taxonomy.tags WHERE active")).isEqualTo(8);
            assertThat(version(connection)).isEqualTo(1);
            Map<String,String> seed=seed(connection);
            assertThat(seed).hasSize(15).containsEntry("cat_00000000000000000000000000000001","Conversación")
                    .containsEntry("tag_00000000000000000000000000000008","IRL");
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            assertThat(seed(connection)).isEqualTo(seed);
            assertThat(version(connection)).isEqualTo(1);
        }
    }

    @Test void catalogVersionCountsRealChangesAndRollsBackWithVocabulary() throws Exception {
        flyway("3").migrate();
        try(var connection=connection(url);var sql=connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.executeUpdate("INSERT INTO taxonomy.categories(id,name,active) VALUES ('cat_test_version','Nueva categoría',true)");
            assertThat(version(connection)).isEqualTo(2);
            sql.executeUpdate("INSERT INTO taxonomy.tags(id,name,active) VALUES ('tag_test_version','Nueva etiqueta',true)");
            assertThat(version(connection)).isEqualTo(3);
            sql.executeUpdate("UPDATE taxonomy.categories SET name='Nueva categoría',active=true WHERE id='cat_test_version'");
            assertThat(version(connection)).isEqualTo(3);
            sql.executeUpdate("UPDATE taxonomy.categories SET name='Último nombre',active=false WHERE id='cat_test_version'");
            assertThat(version(connection)).isEqualTo(4);
            sql.executeUpdate("UPDATE taxonomy.tags SET active=false WHERE id='tag_test_version'");
            assertThat(version(connection)).isEqualTo(5);
            sql.executeUpdate("UPDATE taxonomy.tags SET active=false WHERE id='tag_test_version'");
            assertThat(version(connection)).isEqualTo(5);
            try(var row=sql.executeQuery("SELECT name,active FROM taxonomy.public_categories WHERE id='cat_test_version'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("Último nombre");
                assertThat(row.getBoolean(2)).isFalse();
            }
            assertThat(scalar(connection,"SELECT count(*) FROM taxonomy.public_tags WHERE id='tag_test_version' AND NOT active")).isEqualTo(1);
            connection.rollback();
            assertThat(version(connection)).isEqualTo(1);
            assertThat(scalar(connection,"SELECT count(*) FROM taxonomy.categories WHERE id='cat_test_version'")).isZero();
            assertThat(scalar(connection,"SELECT count(*) FROM taxonomy.tags WHERE id='tag_test_version'")).isZero();
        }
    }

    @Test void stableIdsAndTombstonesCannotBeRemovedBySql() throws Exception {
        flyway("3").migrate();
        try(var connection=connection(url);var sql=connection.createStatement()) {
            for(String table: new String[]{"categories","tags"}) {
                assertThatThrownBy(() -> sql.executeUpdate("UPDATE taxonomy."+table+" SET id=id||'_changed'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql.executeUpdate("DELETE FROM taxonomy."+table))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql.execute("TRUNCATE taxonomy."+table))
                        .isInstanceOf(SQLException.class);
            }
            assertThat(seed(connection)).hasSize(15);
            assertThat(version(connection)).isEqualTo(1);
        }
    }

    private Connection connection(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(jdbcUrl,POSTGRES.getUsername(),POSTGRES.getPassword());
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword())
                .schemas("core","identity","profile","channels","taxonomy").defaultSchema("core").target(target).load();
    }

    private static long scalar(Connection connection,String query) throws SQLException {
        try(var sql=connection.createStatement();var row=sql.executeQuery(query)) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }

    private static long version(Connection connection) throws SQLException {
        return scalar(connection,"SELECT catalog_version FROM taxonomy.catalog_state");
    }

    private static Map<String,Integer> checksums(Connection connection) throws SQLException {
        var result=new LinkedHashMap<String,Integer>();
        try(var sql=connection.createStatement();var rows=sql.executeQuery("SELECT version,checksum FROM core.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
            while(rows.next()) result.put(rows.getString(1),rows.getInt(2));
        }
        return result;
    }

    private static Map<String,String> seed(Connection connection) throws SQLException {
        var result=new LinkedHashMap<String,String>();
        try(var sql=connection.createStatement();var rows=sql.executeQuery("SELECT id,name FROM taxonomy.categories UNION ALL SELECT id,name FROM taxonomy.tags ORDER BY id")) {
            while(rows.next()) result.put(rows.getString(1),rows.getString(2));
        }
        return result;
    }
}
