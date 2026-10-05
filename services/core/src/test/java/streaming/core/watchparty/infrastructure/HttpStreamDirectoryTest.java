package streaming.core.watchparty.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import streaming.core.watchparty.application.StreamDirectory.Lookup;
import streaming.core.watchparty.application.StreamDirectory.Outcome;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class HttpStreamDirectoryTest {
    private static final String STREAM="str_550e8400-e29b-41d4-a716-446655440000";
    private HttpServer server;
    private final Map<String,Consumer<HttpExchange>> routes=new ConcurrentHashMap<>();
    private final List<String> seenPaths=new CopyOnWriteArrayList<>();
    private final List<Map<String,List<String>>> seenHeaders=new CopyOnWriteArrayList<>();
    private HttpStreamDirectory directory;

    @BeforeEach void start() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/",exchange-> {
            seenPaths.add(exchange.getRequestURI().getPath());
            seenHeaders.add(Map.copyOf(exchange.getRequestHeaders()));
            Consumer<HttpExchange> route=routes.get(exchange.getRequestURI().getPath());
            if(route==null) reply(exchange,404,"{\"code\":\"STREAM_NOT_FOUND\"}"); else route.accept(exchange);
        });
        server.start();
        directory=directory("http://127.0.0.1:"+server.getAddress().getPort(),Duration.ofMillis(300));
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void aPlayableStreamIsReadFromThePublicContractWithoutCredentials() {
        route(STREAM,200,live(STREAM,"chn_1","PLAYABLE"));

        Lookup lookup=directory.find(STREAM);

        assertThat(lookup.outcome()).isEqualTo(Outcome.FOUND);
        var s=lookup.snapshot();
        assertThat(s.streamId()).isEqualTo(STREAM);
        assertThat(s.channelId()).isEqualTo("chn_1");
        assertThat(s.title()).isEqualTo("En vivo");
        assertThat(s.category()).isEqualTo("Conversación");
        assertThat(s.status()).isEqualTo("LIVE");
        assertThat(s.availability()).isEqualTo("PLAYABLE");
        assertThat(s.playable()).isTrue();
        assertThat(s.statusFresh()).isTrue();
        assertThat(s.viewerCount()).isEqualTo(8);
        assertThat(seenPaths).containsExactly("/api/streams/"+STREAM);
        var headers=seenHeaders.getFirst();
        assertThat(headers.keySet()).noneMatch(h->h.equalsIgnoreCase("Cookie") || h.equalsIgnoreCase("Authorization") || h.equalsIgnoreCase("X-Session-Credential"));
    }

    @Test void anOfflineStreamWithoutViewerCountIsFoundButNotPlayable() {
        route(STREAM,200,"{\"streamId\":\""+STREAM+"\",\"channelId\":\"chn_1\",\"title\":\"T\",\"category\":{\"id\":\"cat_1\",\"name\":\"Música\"},"
                +"\"status\":\"OFFLINE\",\"availability\":\"OFFLINE\",\"statusFresh\":true}");
        var s=directory.find(STREAM).snapshot();
        assertThat(s.playable()).isFalse();
        assertThat(s.viewerCount()).isNull();
        assertThat(s.category()).isEqualTo("Música");
    }

    @Test void aMissingStreamIsADefinitiveNotFound() {
        assertThat(directory.find(STREAM).outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(directory.find(STREAM).snapshot()).isNull();
    }

    @Test void serverErrorsAndRateLimitsAreUnavailableNotNotFound() {
        for(int status:new int[]{500,502,503,429,400,401,403}) {
            route(STREAM,status,"{\"code\":\"X\"}");
            assertThat(directory.find(STREAM).outcome()).as("HTTP "+status).isEqualTo(Outcome.UNAVAILABLE);
        }
    }

    @Test void aServerThatIsNotListeningIsUnavailable() throws IOException {
        int closedPort;
        try(var socket=new java.net.ServerSocket(0)) { closedPort=socket.getLocalPort(); }
        assertThat(directory("http://127.0.0.1:"+closedPort,Duration.ofMillis(300)).find(STREAM).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test void aSlowServerTimesOutAndIsUnavailable() {
        routes.put("/api/streams/"+STREAM,exchange-> { sleep(1500); reply(exchange,200,live(STREAM,"chn_1","PLAYABLE")); });
        long started=System.nanoTime();
        assertThat(directory.find(STREAM).outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofMillis(1400));
    }

    @Test void invalidOrInconsistentPayloadsAreUnavailable() {
        for(String body:new String[]{"not json","[]","{}","null","{\"streamId\":\""+STREAM+"\"}",
                "{\"streamId\":\""+STREAM+"\",\"channelId\":\"chn_1\"}",
                live("str_other","chn_1","PLAYABLE"),
                "{\"streamId\":\""+STREAM+"\",\"channelId\":5,\"availability\":\"PLAYABLE\"}"}) {
            route(STREAM,200,body);
            assertThat(directory.find(STREAM).outcome()).as(body).isEqualTo(Outcome.UNAVAILABLE);
        }
    }

    @Test void findAllLooksStreamsUpConcurrentlyAndIsolatesFailures() {
        List<String> ids=List.of("str_a","str_b","str_c","str_d");
        for(String id:ids.subList(0,3)) routes.put("/api/streams/"+id,exchange-> { sleep(400); reply(exchange,200,live(id(exchange),"chn_"+id(exchange),"PLAYABLE")); });
        routes.put("/api/streams/str_d",exchange-> { sleep(400); reply(exchange,503,"{}"); });
        directory=directory("http://127.0.0.1:"+server.getAddress().getPort(),Duration.ofSeconds(3));

        long started=System.nanoTime();
        Map<String,Lookup> found=directory.findAll(ids);
        long millis=Duration.ofNanos(System.nanoTime()-started).toMillis();

        assertThat(found.keySet()).containsExactlyElementsOf(ids);
        assertThat(found.get("str_a").outcome()).isEqualTo(Outcome.FOUND);
        assertThat(found.get("str_c").snapshot().channelId()).isEqualTo("chn_str_c");
        assertThat(found.get("str_d").outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(millis).as("four 400 ms calls run in parallel, not in sequence").isLessThan(1300);
    }

    @Test void aTrailingSlashInTheBaseUrlDoesNotChangeThePath() {
        route(STREAM,200,live(STREAM,"chn_1","PLAYABLE"));
        directory=directory("http://127.0.0.1:"+server.getAddress().getPort()+"//",Duration.ofMillis(300));
        assertThat(directory.find(STREAM).outcome()).isEqualTo(Outcome.FOUND);
        assertThat(seenPaths).containsExactly("/api/streams/"+STREAM);
    }

    private static HttpStreamDirectory directory(String base,Duration readTimeout) {
        return new HttpStreamDirectory(base,Duration.ofMillis(500),readTimeout,JsonMapper.builder().build());
    }
    private void route(String streamId,int status,String body) { routes.put("/api/streams/"+streamId,exchange->reply(exchange,status,body)); }
    private static String id(HttpExchange exchange) {
        String path=exchange.getRequestURI().getPath();
        return path.substring(path.lastIndexOf('/')+1);
    }
    private static String live(String streamId,String channelId,String availability) {
        return "{\"streamId\":\""+streamId+"\",\"channelId\":\""+channelId+"\",\"sessionId\":\"ses_1\",\"streamGeneration\":1,\"title\":\"En vivo\","
                +"\"category\":{\"id\":\"cat_1\",\"name\":\"Conversación\"},\"tags\":[],\"status\":\"LIVE\",\"availability\":\""+availability+"\","
                +"\"statusFresh\":true,\"viewerCount\":8,\"countVersion\":3,\"metadataVersion\":1,\"sessionVersion\":2}";
    }
    private static void sleep(long millis) { try { Thread.sleep(millis); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static void reply(HttpExchange exchange,int status,String body) {
        try(exchange) {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch(IOException ignored) { /* the client may already have timed out */ }
    }
}
