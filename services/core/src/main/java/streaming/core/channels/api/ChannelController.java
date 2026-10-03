package streaming.core.channels.api;

import tools.jackson.databind.JsonNode;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import streaming.core.channels.application.ChannelApplicationService;
import streaming.core.channels.application.ChannelException;
import streaming.core.accounts.identity.domain.IdentityRules;
import streaming.core.channels.application.ChannelQueries;

@RestController
public class ChannelController {
    private final ChannelQueries channels;
    private final ChannelApplicationService edits;
    public ChannelController(ChannelQueries channels,ChannelApplicationService edits) {
        this.channels=channels; this.edits=edits;
    }

    @GetMapping("/api/channels/by-handle/{handle}")
    public ResponseEntity<ChannelQueries.Bootstrap> byHandle(@PathVariable String handle) {
        String canonical;
        try { canonical=IdentityRules.canonicalHandle(handle); }
        catch(IllegalArgumentException e) { throw new ChannelException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",e.getMessage()); }
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .body(channels.byHandle(canonical).orElseThrow(ChannelController::notFound));
    }

    @GetMapping("/api/channels/by-owner/{userId}")
    public ResponseEntity<ChannelQueries.Bootstrap> byOwner(@PathVariable String userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .body(channels.byOwner(userId).orElseThrow(ChannelController::notFound));
    }

    @PatchMapping(path="/api/channels/{channelId}",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChannelQueries.ChannelView> patch(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String channelId,@RequestBody JsonNode request) {
        var principal=edits.requirePrincipal(credential);
        return ResponseEntity.ok().header("Cache-Control","no-store, private").body(edits.patch(principal,channelId,request));
    }

    @PostMapping(path="/api/channels/{channelId}/banner-uploads",consumes=MediaType.MULTIPART_FORM_DATA_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChannelApplicationService.UploadResponse> upload(@CookieValue(name="${core.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String channelId,@RequestPart("file") MultipartFile file) throws java.io.IOException {
        var principal=edits.requirePrincipal(credential);
        return ResponseEntity.status(201).header("Cache-Control","no-store, private").body(edits.upload(principal,channelId,file.getBytes()));
    }

    @GetMapping(path="/api/channels/csrf",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok().header("Cache-Control","no-store, private").body(new CsrfResponse(token.getHeaderName(),token.getToken()));
    }

    @GetMapping(path="/api/channels/banners/{key:.+}",produces={"image/jpeg","image/png","image/gif"})
    public ResponseEntity<byte[]> banner(@PathVariable String key) {
        byte[] bytes=edits.readBanner(key);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(edits.bannerContentType(key)))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                .header("X-Content-Type-Options","nosniff").body(bytes);
    }

    private static ChannelException notFound() {
        return new ChannelException(HttpStatus.NOT_FOUND,"CHANNEL_NOT_FOUND","Canal no encontrado.");
    }
    public record CsrfResponse(String headerName,String token) { }
}
