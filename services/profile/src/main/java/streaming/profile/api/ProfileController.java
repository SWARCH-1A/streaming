package streaming.profile.api;

import tools.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import streaming.profile.application.IdentityClient.Principal;
import streaming.profile.application.ProfileApplicationService;
import streaming.profile.application.ProfileApplicationService.ProfileView;

@RestController
public class ProfileController {
    private final ProfileApplicationService profiles; private final String cookieName; private final boolean secureCookie;
    public ProfileController(ProfileApplicationService profiles,@Value("${identity.cookie-name:stream_session}") String cookieName,
            @Value("${profile.secure-cookie:false}") boolean secureCookie) { this.profiles=profiles; this.cookieName=cookieName; this.secureCookie=secureCookie; }
    @GetMapping(path="/api/profile/users/{userId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ProfileResponse publicProfile(@PathVariable String userId) { return response(profiles.getPublic(userId)); }
    @GetMapping(path="/api/profile/me",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProfileResponse> me(@CookieValue(name="${identity.cookie-name:stream_session}",required=false) String credential) {
        Principal principal=profiles.requirePrincipal(credential); return ResponseEntity.ok().header("Cache-Control","no-store, private").body(response(profiles.getMe(principal)));
    }
    @PostMapping(path="/api/profile/me/avatar-uploads",consumes=MediaType.MULTIPART_FORM_DATA_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProfileApplicationService.UploadResponse> upload(@CookieValue(name="${identity.cookie-name:stream_session}",required=false) String credential,
            @org.springframework.web.bind.annotation.RequestPart("file") MultipartFile file) throws java.io.IOException {
        Principal principal=profiles.requirePrincipal(credential);
        var uploaded=profiles.upload(principal,file.getBytes());
        return ResponseEntity.status(201).header("Cache-Control","no-store, private").body(uploaded);
    }
    @PatchMapping(path="/api/profile/me",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProfileResponse> patch(@CookieValue(name="${identity.cookie-name:stream_session}",required=false) String credential,@RequestBody JsonNode request) {
        Principal principal=profiles.requirePrincipal(credential); return ResponseEntity.ok().header("Cache-Control","no-store, private").body(response(profiles.patch(principal,request)));
    }
    @GetMapping(path="/api/profile/csrf",produces=MediaType.APPLICATION_JSON_VALUE)
    public CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getHeaderName(),token.getToken()); }
    @GetMapping(path="/api/profile/avatars/{key:.+}",produces={"image/jpeg","image/png","image/gif"})
    public ResponseEntity<byte[]> avatar(@PathVariable String key) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(profiles.avatarContentType(key)))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                .header("X-Content-Type-Options","nosniff").body(profiles.readAvatar(key));
    }
    private ProfileResponse response(ProfileView view) { return new ProfileResponse(view.userId(),view.displayName(),view.bio(),view.avatarUri(),view.updatedAtUtc(),view.profileVersion()); }
    public record ProfileResponse(String userId,String displayName,String bio,String avatarUri,Instant updatedAtUtc,long profileVersion) { }
    public record CsrfResponse(String headerName,String token) { }
}
