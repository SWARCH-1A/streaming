package streaming.core.infrastructure;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class PrivateStreamingHttpTest {
    static final String TOKEN="fixture_streaming_consumer_token_32_bytes";
    HttpServer server;
    java.util.concurrent.ExecutorService executor;
    PrivateStreamingHttp client(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/",handler); server.start();
        return new PrivateStreamingHttp("http://127.0.0.1:"+server.getAddress().getPort(),TOKEN,true,new ObjectMapper());
    }
    @AfterEach void close() { if(server!=null) server.stop(0); if(executor!=null) executor.close(); }
    @Test void explicitDevelopmentFlagIsRequiredAndConfigurationErrorsNeverExposeSecret() {
        for(String url:new String[]{"http://localhost:8091","https://user:secret@localhost","https://localhost/path","https://localhost?token=secret","//localhost","https://localhost:999999"})
            assertThatThrownBy(()->new PrivateStreamingHttp(url,TOKEN,false,new ObjectMapper()))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(TOKEN).hasMessageNotContaining("secret");
    }
    @Test void privateReadPropagatesCorrelationAndPreservesAdditiveFields() throws Exception {
        var http=client(e->{
            assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer "+TOKEN);
            assertThat(e.getRequestHeaders().getFirst("X-Request-Id")).isEqualTo("request-11");
            e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            e.sendResponseHeaders(200,0); e.getResponseBody().write("{\"future\":true}".getBytes()); e.close();
        });
        assertThat(http.exchange("/context",null,Duration.ofSeconds(1),"request-11").get("future").asBoolean()).isTrue();
    }
    @Test void deadlineIncludesReadingTheBodyAndCancelsHungRead() throws Exception {
        var http=client(e->{
            e.getResponseHeaders().set("Content-Type","application/json"); e.sendResponseHeaders(200,0);
            e.getResponseBody().write('{'); e.getResponseBody().flush();
            try { Thread.sleep(600); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            e.close();
        });
        long start=System.nanoTime();
        assertThatThrownBy(()->http.exchange("/context",null,Duration.ofMillis(200),null)).isInstanceOf(PrivateStreamingHttp.Failure.class);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofMillis(500));
    }
    @Test void oversizedBodyIsRejectedRatherThanParsedTruncated() throws Exception {
        var http=client(e->{
            e.getResponseHeaders().set("Content-Type","application/json"); e.sendResponseHeaders(200,0);
            e.getResponseBody().write(("{\"data\":\""+"x".repeat(65536)+"\"}").getBytes()); e.close();
        });
        assertThatThrownBy(()->http.exchange("/context",null,Duration.ofSeconds(1),null)).isInstanceOf(PrivateStreamingHttp.Failure.class);
    }
    @Test void redirectNeverForwardsCredentialToAnotherRoute() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var http=client(e->{ calls.incrementAndGet(); e.getResponseHeaders().set("Location","/leak"); e.sendResponseHeaders(302,-1); e.close(); });
        assertThatThrownBy(()->http.exchange("/context",null,Duration.ofSeconds(1),null)).isInstanceOf(PrivateStreamingHttp.Failure.class);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void misleadingJsonMediaTypeIsRejected() throws Exception {
        var http=client(e->{e.getResponseHeaders().set("Content-Type","application/jsonp");e.sendResponseHeaders(200,2);e.getResponseBody().write("{}".getBytes());e.close();});
        assertThatThrownBy(()->http.exchange("/context",null,Duration.ofSeconds(1),null)).isInstanceOf(PrivateStreamingHttp.Failure.class);
    }
}
