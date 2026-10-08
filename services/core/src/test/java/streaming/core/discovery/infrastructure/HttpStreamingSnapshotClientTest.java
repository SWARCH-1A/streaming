package streaming.core.discovery.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import streaming.core.discovery.application.StreamingSnapshotClient.SnapshotExpiredException;
import streaming.core.discovery.application.StreamingSnapshotClient.SnapshotUnavailableException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpStreamingSnapshotClientTest {
    private static final String TOKEN="consumer-token-that-must-never-leak-0123456789";
    private HttpServer server;
    private volatile int status;
    private volatile String body;
    private volatile long delayMillis;
    private final List<String> authorization=new CopyOnWriteArrayList<>();
    private final List<String> requestBodies=new CopyOnWriteArrayList<>();
    private final List<String> paths=new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/",this::handle);
        server.start();
        status=200;
        body="""
            {"snapshotId":"7b1c0f5e-3a4b-4c8e-9d11-0123456789ab","watermark":41,"capturedAtUtc":"2026-10-06T12:00:00.5+00:00",
             "expiresAtUtc":"2026-10-06T12:05:00.5+00:00","items":[{"streamId":"str_a"},{"streamId":"str_b"}],"nextCursor":"cursor-2"}""";
    }
    @AfterEach void stop() { server.stop(0); }

    private void handle(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath());
        authorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
        if(delayMillis>0) try { Thread.sleep(delayMillis); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        try(exchange) {
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch(IOException ignored) { /* the client may have timed out */ }
    }

    private HttpStreamingSnapshotClient client(String base,String token,Duration read) {
        return new HttpStreamingSnapshotClient(base,token,Duration.ofMillis(500),read,JsonMapper.builder().build());
    }
    private HttpStreamingSnapshotClient client() { return client("http://127.0.0.1:"+server.getAddress().getPort(),TOKEN,Duration.ofMillis(800)); }

    @Test void callsThePrivateCutWithTheBearerCredentialAndParsesThePage() {
        var page=client().page(50,null);

        assertThat(paths).containsExactly("POST /internal/streaming/discovery/snapshots");
        assertThat(authorization).containsExactly("Bearer "+TOKEN);
        assertThat(requestBodies.getFirst()).isEqualTo("{\"limit\":50}");
        assertThat(page.snapshotId()).isEqualTo("7b1c0f5e-3a4b-4c8e-9d11-0123456789ab");
        assertThat(page.watermark()).isEqualTo(41);
        assertThat(page.capturedAt()).isEqualTo(Instant.parse("2026-10-06T12:00:00.5Z"));
        assertThat(page.expiresAt()).isEqualTo(Instant.parse("2026-10-06T12:05:00.5Z"));
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().getFirst().get("streamId").textValue()).isEqualTo("str_a");
        assertThat(page.nextCursor()).isEqualTo("cursor-2");
    }

    @Test void followingPagesSendTheOpaqueCursorAndNullMeansTheLastPage() {
        body=body.replace("\"nextCursor\":\"cursor-2\"","\"nextCursor\":null");
        var page=client().page(25,"cursor-2");
        assertThat(requestBodies.getFirst()).isEqualTo("{\"limit\":25,\"cursor\":\"cursor-2\"}");
        assertThat(page.nextCursor()).isNull();
    }

    @Test void aTrailingSlashInTheBaseUrlDoesNotChangeThePath() {
        client("http://127.0.0.1:"+server.getAddress().getPort()+"//",TOKEN,Duration.ofMillis(800)).page(50,null);
        assertThat(paths).containsExactly("POST /internal/streaming/discovery/snapshots");
    }

    @Test void gone410MeansTheCutExpiredAndMustBeRestarted() {
        status=410; body="{\"code\":\"SNAPSHOT_EXPIRED\"}";
        assertThatThrownBy(()->client().page(50,"c")).isInstanceOf(SnapshotExpiredException.class);
    }

    @Test void everyOtherStatusIsAStableUnavailableCode() {
        for(int code:new int[]{400,401,403,404,422,429,500,502,503}) {
            status=code; body="{\"code\":\"X\"}";
            assertThatThrownBy(()->client().page(50,null)).as("HTTP "+code).isInstanceOfSatisfying(SnapshotUnavailableException.class,
                    e->assertThat(e.code()).isEqualTo("HTTP_"+code));
        }
    }

    @Test void malformedOrIncompleteResponsesAreInvalid() {
        for(String bad:new String[]{"not json","[]","{}","{\"items\":[]}","{\"snapshotId\":\"s\",\"watermark\":\"1\",\"capturedAtUtc\":\"2026-10-06T12:00:00Z\",\"expiresAtUtc\":\"2026-10-06T12:00:00Z\",\"items\":[]}",
                "{\"snapshotId\":\"s\",\"watermark\":1,\"capturedAtUtc\":\"yesterday\",\"expiresAtUtc\":\"2026-10-06T12:00:00Z\",\"items\":[]}",
                "{\"snapshotId\":\"s\",\"watermark\":1,\"capturedAtUtc\":\"2026-10-06T12:00:00Z\",\"expiresAtUtc\":\"2026-10-06T12:00:00Z\",\"items\":{}}"}) {
            body=bad;
            assertThatThrownBy(()->client().page(50,null)).as(bad).isInstanceOfSatisfying(SnapshotUnavailableException.class,
                    e->assertThat(e.code()).isEqualTo("INVALID_RESPONSE"));
        }
    }

    @Test void aSlowOrUnreachableStreamingIsTransportUnavailable() throws IOException {
        delayMillis=1500;
        assertThatThrownBy(()->client().page(50,null)).isInstanceOfSatisfying(SnapshotUnavailableException.class,e->assertThat(e.code()).isEqualTo("TRANSPORT_UNAVAILABLE"));
        int closedPort;
        try(var socket=new java.net.ServerSocket(0)) { closedPort=socket.getLocalPort(); }
        assertThatThrownBy(()->client("http://127.0.0.1:"+closedPort,TOKEN,Duration.ofMillis(800)).page(50,null))
                .isInstanceOfSatisfying(SnapshotUnavailableException.class,e->assertThat(e.code()).isEqualTo("TRANSPORT_UNAVAILABLE"));
    }

    @Test void isDisabledWithoutAnEndpointOrACredentialAndMakesNoCall() {
        assertThat(client("",TOKEN,Duration.ofSeconds(1)).configured()).isFalse();
        assertThat(client("http://127.0.0.1:"+server.getAddress().getPort(),"",Duration.ofSeconds(1)).configured()).isFalse();
        assertThat(client("http://127.0.0.1:"+server.getAddress().getPort(),"   ",Duration.ofSeconds(1)).configured()).isFalse();
        assertThat(client().configured()).isTrue();
        assertThatThrownBy(()->client("",TOKEN,Duration.ofSeconds(1)).page(50,null)).isInstanceOfSatisfying(SnapshotUnavailableException.class,
                e->assertThat(e.code()).isEqualTo("NOT_CONFIGURED"));
        assertThat(paths).isEmpty();
    }

    @Test void theCredentialNeverAppearsInAnyErrorMessage() {
        status=500;
        assertThatThrownBy(()->client().page(50,null)).satisfies(e->{
            assertThat(String.valueOf(e.getMessage())).doesNotContain(TOKEN);
            assertThat(e.toString()).doesNotContain(TOKEN);
        });
    }
}
