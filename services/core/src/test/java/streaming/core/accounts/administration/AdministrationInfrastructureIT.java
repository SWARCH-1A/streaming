package streaming.core.accounts.administration;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import streaming.core.accounts.administration.infrastructure.AccountStatusPublisher;
import static org.assertj.core.api.Assertions.*;

/** Disposable providers; does not connect to the developer's application databases. */
class AdministrationInfrastructureIT {
    @Test void upgradeFromV8PreservesIdentityAndRunsTheOperatorScript() throws Exception {
        try (var db = new PostgreSQLContainer("postgres:18-alpine")) {
            db.start();
            Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).target("8").load().migrate();
            var jdbc = JdbcClient.create(new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()));
            jdbc.sql("INSERT INTO identity.accounts VALUES ('usr_upgrade','fixture@example.test','fixture@example.test','upgrade_test','upgrade_test','test-only-hash',CURRENT_TIMESTAMP)").update();
            jdbc.sql("INSERT INTO identity.sessions VALUES (:id,'usr_upgrade',:hash,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 hour',NULL)")
                .param("id",UUID.randomUUID()).param("hash","a".repeat(64)).update();
            var checksums = jdbc.sql("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank").query().listOfRows();
            var flyway=Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).load();
            flyway.migrate(); flyway.validate();
            assertThat(jdbc.sql("SELECT version,checksum FROM flyway_schema_history WHERE version<>'9' AND version IS NOT NULL ORDER BY installed_rank").query().listOfRows()).isEqualTo(checksums);
            assertThat(jdbc.sql("SELECT account_role||':'||account_status||':'||account_version FROM identity.accounts").query(String.class).single()).isEqualTo("USER:ACTIVE:0");
            db.copyFileToContainer(MountableFile.forHostPath(Path.of("scripts/provision-admin.sql")),"/tmp/provision-admin.sql");
            for (int i=0;i<2;i++) {
                var result=db.execInContainer("psql","-U",db.getUsername(),"-d",db.getDatabaseName(),"-v","target_user=usr_upgrade","-f","/tmp/provision-admin.sql");
                assertThat(result.getExitCode()).as(result.getStderr()).isZero();
            }
            assertThat(jdbc.sql("SELECT account_role FROM identity.accounts").query(String.class).single()).isEqualTo("ADMIN");
            assertThat(jdbc.sql("SELECT count(*) FROM identity.admin_audit").query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT revoked_at_utc IS NOT NULL FROM identity.sessions").query(Boolean.class).single()).isTrue();
            assertThat(db.execInContainer("psql","-U",db.getUsername(),"-d",db.getDatabaseName(),"-v","target_user=usr_missing","-f","/tmp/provision-admin.sql").getExitCode()).isNotZero();
        }
    }

    @Test void realBrokerOutageAndWorkerRestartPreserveAndConfirmOriginalEvent() throws Exception {
        try (var db=new PostgreSQLContainer("postgres:18-alpine");
             var broker=new GenericContainer<>("rabbitmq:4.3.7-management@sha256:79fb281ef7e0a15e3c5e0e12e66e944cebbf14a129493ef464369abd2c48d01a").withExposedPorts(5672)
                 .withEnv("RABBITMQ_DEFAULT_USER","fixture").withEnv("RABBITMQ_DEFAULT_PASS","fixture-only-password")
                 .waitingFor(Wait.forLogMessage(".*Server startup complete.*",1))) {
            db.start(); broker.start();
            Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).load().migrate();
            var ds=new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());
            var jdbc=JdbcClient.create(ds);var tx=new TransactionTemplate(new JdbcTransactionManager(ds));
            jdbc.sql("INSERT INTO identity.accounts(user_id,email,normalized_email,handle,canonical_handle,password_hash,created_at_utc) VALUES ('usr_broker','broker@example.test','broker@example.test','broker_test','broker_test','test-only-hash',CURRENT_TIMESTAMP)").update();
            UUID id=UUID.randomUUID();
            String envelope="{\"eventId\":\""+id+"\",\"eventType\":\"account.status.changed.v1\",\"schemaVersion\":1,\"producer\":\"core\",\"aggregateId\":\"account:usr_broker\",\"sequence\":1,\"occurredAtUtc\":\"2026-10-24T00:00:00Z\",\"correlationId\":\"fixture\",\"payload\":{\"userId\":\"usr_broker\",\"accountVersion\":1,\"status\":\"SUSPENDED\"}}";
            jdbc.sql("INSERT INTO identity.account_status_outbox(event_id,user_id,account_version,envelope,created_at_utc) VALUES (:id,'usr_broker',1,:body,CURRENT_TIMESTAMP)").param("id",id).param("body",envelope).update();
            var factory=new CachingConnectionFactory(broker.getHost(),broker.getMappedPort(5672));
            factory.setUsername("fixture");factory.setPassword("fixture-only-password");
            factory.setConnectionTimeout(1000);factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);factory.setPublisherReturns(true);
            try {
                var rabbit=new RabbitTemplate(factory);rabbit.setMandatory(true);
                var topology=new RabbitAdmin(factory);var exchange=new TopicExchange("p2.events",true,false);
                topology.declareExchange(exchange);var queue=new Queue("fixture.account-status",true);topology.declareQueue(queue);
                topology.declareBinding(BindingBuilder.bind(queue).to(exchange).with("account.status.changed.v1"));
                assertThat(broker.execInContainer("rabbitmqctl","stop_app").getExitCode()).isZero();factory.resetConnection();
                new AccountStatusPublisher(jdbc,tx,rabbit).publish();
                assertThat(jdbc.sql("SELECT published_at_utc IS NULL AND attempts=1 FROM identity.account_status_outbox").query(Boolean.class).single()).isTrue();
                assertThat(broker.execInContainer("rabbitmqctl","start_app").getExitCode()).isZero();
                jdbc.sql("UPDATE identity.account_status_outbox SET next_attempt_at_utc=CURRENT_TIMESTAMP").update();
                new AccountStatusPublisher(jdbc,tx,rabbit).publish();
                var delivered=rabbit.receive("fixture.account-status",5000);
                assertThat(delivered).isNotNull();
                assertThat(new String(delivered.getBody(),StandardCharsets.UTF_8)).isEqualTo(envelope);
                assertThat(jdbc.sql("SELECT published_at_utc IS NOT NULL AND attempts=2 FROM identity.account_status_outbox").query(Boolean.class).single()).isTrue();
            } finally { factory.destroy(); }
        }
    }
}
