package streaming.core.discovery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

import static streaming.core.discovery.TestPayloads.JSON;

/**
 * Stand-in for Streaming's private {@code POST /internal/streaming/discovery/snapshots}. It follows the real contract:
 * Bearer credential, a cut fixed on the first page, opaque cursors, 410 when a cut expired. The content of each new
 * cut comes from a supplier so a test can change the world between cuts.
 */
public final class CutServer implements AutoCloseable {
    public record Cut(long watermark,Instant capturedAt,List<JsonNode> items) { }

    private final HttpServer server;
    private final String token;
    private final Map<String,Cut> cuts=new ConcurrentHashMap<>();
    private final AtomicInteger cutCounter=new AtomicInteger();
    public final List<String> authorizations=new CopyOnWriteArrayList<>();
    public final List<String> requestBodies=new CopyOnWriteArrayList<>();
    public final AtomicInteger requests=new AtomicInteger();
    public final AtomicInteger cutsStarted=new AtomicInteger();
    private volatile Supplier<Cut> factory=()->new Cut(0,Instant.EPOCH,List.of());
    private volatile int pageSize=2;
    private volatile int failStatus;
    private volatile boolean expireNextFollowUp;
    private volatile IntConsumer onPage=n->{ };

    public CutServer(String token) {
        this.token=token;
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.createContext("/internal/streaming/discovery/snapshots",this::handle);
            server.start();
        } catch(IOException e) { throw new IllegalStateException(e); }
    }

    public String baseUrl() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    public void serve(Supplier<Cut> cut) { this.factory=cut; }
    public void serve(Cut cut) { this.factory=()->cut; }
    public void pageSize(int size) { this.pageSize=size; }
    public void failWith(int status) { this.failStatus=status; }
    /** The next request that continues an existing cut answers 410 once, as an expired cut would. */
    public void expireNextFollowUp() { this.expireNextFollowUp=true; }
    public void onPage(IntConsumer hook) { this.onPage=hook; }
    public void reset() {
        factory=()->new Cut(0,Instant.EPOCH,List.of()); pageSize=2; failStatus=0; expireNextFollowUp=false; onPage=n->{ };
        authorizations.clear(); requestBodies.clear(); requests.set(0); cutsStarted.set(0); cuts.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String auth=String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
        authorizations.add(auth);
        String raw=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        requestBodies.add(raw);
        if(!("Bearer "+token).equals(auth)) { reply(exchange,401,"{\"code\":\"SERVICE_AUTH_REQUIRED\"}"); return; }
        if(failStatus!=0) { reply(exchange,failStatus,"{\"code\":\"STREAMING_UNAVAILABLE\"}"); return; }
        JsonNode body=JSON.readTree(raw);
        JsonNode cursorNode=body.get("cursor");
        String snapshotId; int offset;
        int page;
        if(cursorNode==null || cursorNode.isNull()) {
            snapshotId=UUID.randomUUID().toString();
            cuts.put(snapshotId,factory.get());
            cutsStarted.incrementAndGet();
            offset=0; page=1;
        } else {
            String[] parts=cursorNode.textValue().split(":");
            snapshotId=parts[0]; offset=Integer.parseInt(parts[1]);
            if(expireNextFollowUp) { expireNextFollowUp=false; reply(exchange,410,"{\"code\":\"SNAPSHOT_EXPIRED\"}"); return; }
            page=offset/Math.max(1,pageSize)+1;
        }
        onPage.accept(page);
        Cut cut=cuts.get(snapshotId);
        if(cut==null) { reply(exchange,410,"{\"code\":\"SNAPSHOT_EXPIRED\"}"); return; }
        int end=Math.min(cut.items().size(),offset+pageSize);
        var items=new ArrayList<JsonNode>(cut.items().subList(Math.min(offset,end),end));
        var response=JSON.createObjectNode();
        response.put("snapshotId",snapshotId).put("watermark",cut.watermark()).put("capturedAtUtc",TestPayloads.pg(cut.capturedAt()))
                .put("expiresAtUtc",TestPayloads.pg(cut.capturedAt().plusSeconds(300)));
        response.putArray("items").addAll(items);
        if(end<cut.items().size()) response.put("nextCursor",snapshotId+":"+end); else response.putNull("nextCursor");
        reply(exchange,200,JSON.writeValueAsString(response));
    }

    private static void reply(HttpExchange exchange,int status,String body) throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        try(exchange) {
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override public void close() { server.stop(0); }
}
