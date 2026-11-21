package streaming.core.audit;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import streaming.core.accounts.administration.application.AdministrationService;
import streaming.core.accounts.identity.application.IdentityException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditQueryControllerTest {
    private final AdministrationService administration=mock(AdministrationService.class);
    private final CountDownLatch release=new CountDownLatch(1);
    private HttpServer server;
    private final String token="fixture-audit-token-01234567890123456789";
    private AuditQueryController controller() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        when(administration.requireAdministrator("session")).thenReturn("usr_admin");
        return new AuditQueryController(administration,new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),token,true);
    }
    @AfterEach void stop() { release.countDown();if(server!=null) server.stop(0); }
    @Test void forwardsVerifiedActorAndReturnsPrivatePage() throws Exception {
        var controller=controller();
        server.createContext("/internal/audit/events",exchange->{
            assertEquals(token,exchange.getRequestHeaders().getFirst("X-Service-Token"));
            assertEquals("usr_admin",exchange.getRequestHeaders().getFirst("X-Audit-Actor"));
            byte[] body="{\"items\":[],\"nextCursor\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()) { out.write(body); }
        });server.start();
        var response=controller.events("session",new LinkedMultiValueMap<>());
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertEquals("no-store, private",response.getHeaders().getFirst("Cache-Control"));
        assertEquals(0,response.getBody().path("items").size());
    }
    @Test void rejectsOversizedUpstreamBody() throws Exception {
        var controller=controller();
        server.createContext("/internal/audit/events",exchange->{
            byte[] body=new byte[262145];exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()) { out.write(body); }
        });server.start();
        var error=assertThrows(IdentityException.class,()->controller.events("session",new LinkedMultiValueMap<>()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.status());
    }
    @Test void completeBodyMustArriveWithinDeadline() throws Exception {
        var controller=controller();
        server.createContext("/internal/audit/events",exchange->{
            exchange.sendResponseHeaders(200,100);
            try(var out=exchange.getResponseBody()) {
                out.write('{');out.flush();
                try { release.await(); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });server.start();
        assertTimeoutPreemptively(Duration.ofSeconds(5),()->{
            var error=assertThrows(IdentityException.class,()->controller.events("session",new LinkedMultiValueMap<>()));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.status());
        });
    }
    @Test void deniesBeforeContactingUpstream() throws Exception {
        var controller=controller();
        when(administration.requireAdministrator("session")).thenThrow(new IdentityException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","denied"));
        var error=assertThrows(IdentityException.class,()->controller.events("session",new LinkedMultiValueMap<>()));
        assertEquals(HttpStatus.FORBIDDEN,error.status());
    }
}
