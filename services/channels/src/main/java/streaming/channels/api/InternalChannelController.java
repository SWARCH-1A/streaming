package streaming.channels.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import streaming.channels.application.ChannelProvisioningService;
import streaming.channels.application.ChannelProvisioningService.ProvisionResult;
import streaming.channels.application.StreamProjectionService;
import streaming.channels.application.StreamProjectionService.StreamEvent;

/** Private network API: authenticated by InternalServiceTokenFilter and never routed by the public proxy. */
@RestController
public class InternalChannelController {
    private final ChannelProvisioningService provisioning; private final StreamProjectionService projection;
    public InternalChannelController(ChannelProvisioningService provisioning,StreamProjectionService projection) { this.provisioning=provisioning; this.projection=projection; }

    @PostMapping(path="/internal/channels/provision",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProvisionResponse> provision(@RequestBody ProvisionRequest request,
            @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
            @RequestHeader(name="X-Request-Id",required=false) String requestId) {
        ProvisionResult result=provisioning.provision(request.ownerUserId(),request.registrationId(),request.pendingUntilUtc(),requestId!=null?requestId:idempotencyKey);
        var c=result.channel();
        return ResponseEntity.status(result.created()?201:200).body(new ProvisionResponse(c.channelId(),c.ownerUserId(),c.registrationId(),c.version()));
    }
    @GetMapping(path="/internal/channels/provisions/{registrationId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ProvisionStateResponse state(@PathVariable String registrationId) {
        var state=provisioning.lookup(registrationId);
        return new ProvisionStateResponse(state.state(),registrationId,state.ownerUserId(),state.channelId());
    }
    @DeleteMapping("/internal/channels/provisions/{registrationId}")
    public ResponseEntity<Void> compensate(@PathVariable String registrationId) {
        provisioning.compensate(registrationId); return ResponseEntity.noContent().build();
    }
    /** HTTP delivery of Streaming events (StreamSession*, StreamMetadataUpdated, ViewerCountChanged); duplicates are acknowledged. */
    @PostMapping(path="/internal/channels/stream-events",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public EventAck streamEvent(@RequestBody StreamEvent event) { return new EventAck(event.eventId(),projection.apply(event)); }

    public record ProvisionRequest(String ownerUserId,String registrationId,String pendingUntilUtc) { }
    public record ProvisionResponse(String channelId,String ownerUserId,String registrationId,long channelVersion) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProvisionStateResponse(String state,String registrationId,String ownerUserId,String channelId) { }
    public record EventAck(String eventId,String result) { }
}
