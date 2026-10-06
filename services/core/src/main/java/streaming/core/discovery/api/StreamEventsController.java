package streaming.core.discovery.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.api.CoreErrorHandler;
import streaming.core.discovery.application.ProjectionIngestService;
import tools.jackson.databind.JsonNode;

/**
 * Private inbox of Streaming's public snapshots. It is reachable only through the private listener with the
 * Streaming service credential (see PrivateCoreSecurity); the public port answers 404.
 * 202 first delivery, 200 identical redelivery, 409 conflicting content, 422 invalid, 400 malformed JSON.
 */
@RestController
public class StreamEventsController {
    private final ProjectionIngestService ingest;

    public StreamEventsController(ProjectionIngestService ingest) { this.ingest=ingest; }

    @PostMapping(path="/internal/core/discovery/stream-events",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> receive(@RequestBody JsonNode body,HttpServletRequest request) {
        var result=ingest.ingest(body);
        return switch(result.kind()) {
            case ACCEPTED -> ResponseEntity.accepted().header("Cache-Control","no-store")
                    .body(Map.of("eventId",result.eventId(),"outcome",result.outcome()));
            case DUPLICATE -> ResponseEntity.ok().header("Cache-Control","no-store")
                    .body(Map.of("eventId",result.eventId(),"outcome",result.outcome()));
            case CONFLICT -> ResponseEntity.status(409).header("Cache-Control","no-store")
                    .body(CoreErrorHandler.body(result.outcome(),"El evento contradice lo ya recibido.",request));
        };
    }
}
