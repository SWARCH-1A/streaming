package streaming.core.taxonomy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import streaming.core.taxonomy.application.CatalogQueries;
import streaming.core.taxonomy.application.CatalogValues;
import streaming.core.taxonomy.application.CatalogValues.CatalogValue;
import streaming.core.taxonomy.application.TaxonomyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static streaming.core.taxonomy.application.CatalogValues.ValueType.CATEGORY;
import static streaming.core.taxonomy.application.CatalogValues.ValueType.TAG;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaxonomyIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    @TempDir static Path avatars;
    @TempDir static Path banners;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",POSTGRES::getUsername);
        properties.add("spring.datasource.password",POSTGRES::getPassword);
        properties.add("core.rate-limit-hmac-secret",()->"taxonomy-integration-only-secret-at-least-32-bytes");
        properties.add("core.images.storage-provider",()->"filesystem");
        properties.add("profile.storage-root",()->avatars.toString());
        properties.add("channels.storage-root",()->banners.toString());
    }

    @Autowired CatalogValues values;
    @Autowired CatalogQueries queries;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Value("${local.server.port}") int port;
    private final HttpClient browser=HttpClient.newHttpClient();

    @Test void anonymousClientsReceiveOnlyPublicSeedFieldsWithStableOrderAndIds() throws Exception {
        var response=get();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        var body=json.readTree(response.body());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.get("catalogVersion").asLong()).isPositive();
        assertThat(strings(body.get("categories"),"name")).containsExactly(
                "Arte","Ciencia y tecnología","Conversación","Deportes","Educación","Música","Videojuegos");
        assertThat(strings(body.get("categories"),"id")).containsExactly(cat(4),cat(6),cat(1),cat(7),cat(5),cat(3),cat(2));
        assertThat(strings(body.get("tags"),"name")).containsExactly(
                "Casual","Competitivo","Educativo","Español","Inglés","IRL","Principiantes","Programación");
        assertThat(strings(body.get("tags"),"id")).containsExactly(tag(5),tag(4),tag(3),tag(1),tag(2),tag(8),tag(6),tag(7));
        for(String kind:List.of("categories","tags")) {
            for(JsonNode value:body.get(kind)) {
                assertThat(value.size()).isEqualTo(3);
                assertThat(value.get("active").asBoolean()).isTrue();
                assertThat(value.get("id").asText()).isNotBlank();
                assertThat(value.get("name").asText()).isNotBlank();
            }
        }
        assertThat(json.readTree(get().body())).isEqualTo(body);
    }

    @Test void committedServerDataAppearsWithoutClientChangesAndRetiredValuesRemainFindable() throws Exception {
        String categoryId="cat_api_"+UUID.randomUUID().toString().replace("-","");
        String tagId="tag_api_"+UUID.randomUUID().toString().replace("-","");
        long before=json.readTree(get().body()).get("catalogVersion").asLong();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                insert("categories",categoryId,"Astronomía");
                insert("tags",tagId,"Observación");
            });
            var added=json.readTree(get().body());
            assertThat(added.get("catalogVersion").asLong()).isEqualTo(before+2);
            assertThat(strings(added.get("categories"),"id")).contains(categoryId);
            assertThat(strings(added.get("tags"),"id")).contains(tagId);

            jdbc.sql("UPDATE taxonomy.categories SET name='Cielo nocturno',active=false WHERE id=:id").param("id",categoryId).update();
            jdbc.sql("UPDATE taxonomy.tags SET active=false WHERE id=:id").param("id",tagId).update();
            var retired=json.readTree(get().body());
            assertThat(retired.get("catalogVersion").asLong()).isEqualTo(before+4);
            assertThat(strings(retired.get("categories"),"id")).doesNotContain(categoryId);
            assertThat(strings(retired.get("tags"),"id")).doesNotContain(tagId);
            assertThat(values.find(CATEGORY,categoryId)).contains(new CatalogValue(categoryId,"Cielo nocturno",false));
            assertThat(values.find(TAG,tagId)).contains(new CatalogValue(tagId,"Observación",false));
            assertInvalid(() -> values.requireActiveCategory(categoryId),"categoryId","UNKNOWN_OR_INACTIVE");
            assertInvalid(() -> values.requireActiveTags(List.of(tagId)),"tagIds","UNKNOWN_OR_INACTIVE");
        } finally {
            // This class owns its disposable DB. Keep later tests' active seed intact; tombstones are never deleted.
            jdbc.sql("UPDATE taxonomy.categories SET active=false WHERE id=:id").param("id",categoryId).update();
            jdbc.sql("UPDATE taxonomy.tags SET active=false WHERE id=:id").param("id",tagId).update();
        }
    }

    @Test @Transactional
    void localValidationAcceptsZeroOrFiveTagsAndDeduplicatesWithoutChangingOrder() {
        assertThat(values.requireActiveCategory(cat(1))).isEqualTo(new CatalogValue(cat(1),"Conversación",true));
        assertThat(values.requireActiveTags(List.of())).isEmpty();
        assertThat(values.requireActiveTags(List.of(tag(2),tag(1),tag(2),tag(3),tag(4),tag(5),tag(1))))
                .extracting(CatalogValue::id).containsExactly(tag(2),tag(1),tag(3),tag(4),tag(5));
        assertInvalid(() -> values.requireActiveTags(List.of(tag(1),tag(2),tag(3),tag(4),tag(5),tag(6))),
                "tagIds","TOO_MANY_TAGS");
    }

    @ParameterizedTest @Transactional
    @NullAndEmptySource
    @ValueSource(strings={" ","\t"})
    void missingCategoryProducesConsumerFieldError(String id) {
        assertInvalid(() -> values.requireActiveCategory(id),"categoryId","REQUIRED");
    }

    @Test @Transactional
    void unknownWrongKindAndRetiredValuesCannotBeExplicitlySelected() {
        for(String id:List.of("cat_missing",tag(1)," "+cat(1))) {
            assertInvalid(() -> values.requireActiveCategory(id),"categoryId","UNKNOWN_OR_INACTIVE");
        }
        for(String id:List.of("tag_missing",cat(1)," "+tag(1))) {
            assertInvalid(() -> values.requireActiveTags(List.of(id)),"tagIds","UNKNOWN_OR_INACTIVE");
        }
        jdbc.sql("UPDATE taxonomy.categories SET active=false WHERE id=:id").param("id",cat(1)).update();
        jdbc.sql("UPDATE taxonomy.tags SET active=false WHERE id=:id").param("id",tag(1)).update();
        assertInvalid(() -> values.requireActiveCategory(cat(1)),"categoryId","UNKNOWN_OR_INACTIVE");
        assertInvalid(() -> values.requireActiveTags(List.of(tag(2),tag(1))),"tagIds","UNKNOWN_OR_INACTIVE");
        assertThat(values.find(CATEGORY,cat(1))).contains(new CatalogValue(cat(1),"Conversación",false));
        assertThat(values.find(TAG,tag(1))).contains(new CatalogValue(tag(1),"Español",false));
        assertThat(values.find(CATEGORY,"cat_missing")).isEmpty();
        assertThat(values.find(CATEGORY,tag(1))).isEmpty();
        assertThat(values.find(TAG,null)).isEmpty();
        assertThat(queries.activeCatalog().categories()).extracting(CatalogValue::id).doesNotContain(cat(1));
        assertThat(queries.activeCatalog().tags()).extracting(CatalogValue::id).doesNotContain(tag(1));
    }

    @Test @Transactional
    void newValuesUseNormalizedNameThenIdAndSnapshotIncludesTheirVersion() {
        long before=queries.activeCatalog().catalogVersion();
        insert("categories","cat_order_b","Ａbc");
        insert("categories","cat_order_a","abc");
        var snapshot=queries.activeCatalog();
        assertThat(snapshot.catalogVersion()).isEqualTo(before+2);
        assertThat(snapshot.categories().stream().filter(value -> value.id().startsWith("cat_order_")).toList())
                .extracting(CatalogValue::id).containsExactly("cat_order_a","cat_order_b");
        assertThat(snapshot.categories().getFirst().id()).isEqualTo("cat_order_a");
        jdbc.sql("UPDATE taxonomy.categories SET name=name,active=active WHERE id='cat_order_a'").update();
        assertThat(queries.activeCatalog()).isEqualTo(snapshot);
    }

    @Test void activeValidationKeepsRowLocksUntilTheConsumerTransactionFinishes() throws Exception {
        try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                var sql=connection.createStatement()) {
            connection.setAutoCommit(false);
            for(String table:List.of("categories","tags")) {
                String id=table.equals("categories")?cat(1):tag(1);
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    if(table.equals("categories")) values.requireActiveCategory(id);
                    else values.requireActiveTags(List.of(id));
                    try {
                        sql.execute("SET LOCAL lock_timeout='300ms'");
                        assertThatThrownBy(() -> sql.executeUpdate("UPDATE taxonomy."+table+" SET active=false WHERE id='"+id+"'"))
                                .isInstanceOfSatisfying(SQLException.class,error -> assertThat(error.getSQLState()).isEqualTo("55P03"));
                        connection.rollback();
                    } catch(SQLException error) { throw new IllegalStateException(error); }
                });
                sql.execute("SET LOCAL lock_timeout='1s'");
                assertThat(sql.executeUpdate("UPDATE taxonomy."+table+" SET active=false WHERE id='"+id+"'")).isEqualTo(1);
                connection.rollback();
            }
        }
    }

    @Test void sqlFailureReturnsAnExplicit503WithMatchingRequestIdThenRecovers() throws Exception {
        // Fault injection touches only this class's container and restores the relation even on failure.
        jdbc.sql("ALTER TABLE taxonomy.catalog_state RENAME TO catalog_state_unavailable").update();
        try {
            var response=get();
            assertThat(response.statusCode()).isEqualTo(503);
            var body=json.readTree(response.body());
            assertThat(body.get("code").asText()).isEqualTo("CORE_UNAVAILABLE");
            assertThat(body.get("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
            assertThat(response.body()).doesNotContain("SELECT","catalog_state","password");
            assertThat(body.has("categories")).isFalse();
        } finally {
            jdbc.sql("ALTER TABLE taxonomy.catalog_state_unavailable RENAME TO catalog_state").update();
        }
        assertThat(get().statusCode()).isEqualTo(200);
    }

    private void insert(String table,String id,String name) {
        jdbc.sql("INSERT INTO taxonomy."+table+"(id,name,active) VALUES (:id,:name,true)")
                .param("id",id).param("name",name).update();
    }

    private HttpResponse<String> get() throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/taxonomy"))
                .timeout(Duration.ofSeconds(10)).header("X-Request-Id",UUID.randomUUID().toString()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> strings(JsonNode rows,String field) {
        var result=new ArrayList<String>();
        for(JsonNode row:rows) result.add(row.get(field).asText());
        return result;
    }

    private static String cat(int number) { return "cat_"+String.format("%032d",number); }
    private static String tag(int number) { return "tag_"+String.format("%032d",number); }

    private static void assertInvalid(Runnable selection,String field,String reason) {
        assertThatThrownBy(selection::run).isInstanceOfSatisfying(TaxonomyException.class,error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(error.code()).isEqualTo("INVALID_TAXONOMY");
            assertThat(error.fieldErrors()).containsEntry(field,reason);
        });
    }
}
