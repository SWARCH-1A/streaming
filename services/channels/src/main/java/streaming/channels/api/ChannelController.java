package streaming.channels.api;

import tools.jackson.databind.JsonNode;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import streaming.channels.application.ChannelApplicationService;
import streaming.channels.application.ChannelApplicationService.ChannelView;
import streaming.channels.application.ChannelApplicationService.PublicChannelView;
import streaming.channels.application.ChannelApplicationService.UploadResponse;
import streaming.channels.application.IdentityClient.Principal;

/** Public REST API of SPEC-03; the shell resolves /channels/{handle} with Identity and then reads by owner. */
@RestController
public class ChannelController {
    private final ChannelApplicationService channels;
    public ChannelController(ChannelApplicationService channels) { this.channels=channels; }

    @GetMapping(path="/api/channels/by-owner/{userId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PublicChannelView> byOwner(@PathVariable String userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(channels.getByOwner(userId));
    }
    @PatchMapping(path="/api/channels/{channelId}",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChannelView> patch(@CookieValue(name="${identity.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String channelId,@RequestBody JsonNode request) {
        Principal principal=channels.requirePrincipal(credential);
        return ResponseEntity.ok().header("Cache-Control","no-store, private").body(channels.patch(principal,channelId,request));
    }
    @PostMapping(path="/api/channels/{channelId}/banner-uploads",consumes=MediaType.MULTIPART_FORM_DATA_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UploadResponse> upload(@CookieValue(name="${identity.cookie-name:stream_session}",required=false) String credential,
            @PathVariable String channelId,@RequestPart("file") MultipartFile file) throws java.io.IOException {
        Principal principal=channels.requirePrincipal(credential);
        return ResponseEntity.status(201).header("Cache-Control","no-store, private").body(channels.upload(principal,channelId,file.getBytes()));
    }
    @GetMapping(path="/api/channels/csrf",produces=MediaType.APPLICATION_JSON_VALUE)
    public CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getHeaderName(),token.getToken()); }
    @GetMapping(path="/api/channels/banners/{key:.+}",produces={"image/jpeg","image/png","image/gif"})
    public ResponseEntity<byte[]> banner(@PathVariable String key) {
        byte[] bytes=channels.readBanner(key);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(channels.bannerContentType(key)))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                .header("X-Content-Type-Options","nosniff").body(bytes);
    }
    public record CsrfResponse(String headerName,String token) { }
}
