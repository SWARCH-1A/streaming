package streaming.core.channels.infrastructure;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ChannelMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");

    @Test void upgradeFromCoreV1PreservesChannelsAndRegistrationIds() throws Exception {
        flyway("1").migrate();
        try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                var sql=connection.createStatement()) {
            sql.executeUpdate("INSERT INTO identity.accounts VALUES ('usr_preserved','migration@example.test','migration@example.test','migration_01','migration_01','test-only-unused-hash',now())");
            sql.executeUpdate("INSERT INTO profile.profiles VALUES ('usr_preserved','Migration','',NULL,0,now(),now())");
            sql.executeUpdate("INSERT INTO channels.channels VALUES ('chn_preserved','usr_preserved','Existing description',NULL,3,now(),now())");
            sql.executeUpdate("INSERT INTO identity.registrations VALUES ('reg_preserved',repeat('a',64),repeat('b',64),'usr_preserved','chn_preserved',now(),now()+interval '30 days')");
            int checksum;
            try(var row=sql.executeQuery("SELECT checksum FROM core.flyway_schema_history WHERE version='1'")) {
                row.next(); checksum=row.getInt(1);
            }

            var upgrade=flyway("2");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            upgrade.validate();
            try(var row=sql.executeQuery("SELECT c.channel_id,c.owner_user_id,c.description,c.channel_version,c.banner_key,r.channel_id "
                    +"FROM channels.channels c JOIN identity.registrations r ON r.user_id=c.owner_user_id")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("chn_preserved");
                assertThat(row.getString(2)).isEqualTo("usr_preserved");
                assertThat(row.getString(3)).isEqualTo("Existing description");
                assertThat(row.getLong(4)).isEqualTo(3);
                assertThat(row.getString(5)).isNull();
                assertThat(row.getString(6)).isEqualTo("chn_preserved");
                assertThat(row.next()).isFalse();
            }
            try(var row=sql.executeQuery("SELECT checksum FROM core.flyway_schema_history WHERE version='1'")) {
                row.next(); assertThat(row.getInt(1)).isEqualTo(checksum);
            }
            assertThat(sql.executeUpdate("UPDATE channels.channels SET description=repeat('x',500) WHERE channel_id='chn_preserved'")).isEqualTo(1);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
        }
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .schemas("core","identity","profile","channels").defaultSchema("core").target(target).load();
    }
}
