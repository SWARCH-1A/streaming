package streaming.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import streaming.identity.application.IdentityApplicationService;
import streaming.identity.application.IdentityApplicationService.LoginResult;
import streaming.identity.application.IdentityApplicationService.RegistrationView;
import streaming.identity.application.IdentityException;

@RestController
public class IdentityController {
    private final IdentityApplicationService identity;
    private final String cookieName;
    private final boolean secureCookie;
    public IdentityController(IdentityApplicationService identity,
            @Value("${identity.cookie-name:stream_session}") String cookieName,
            @Value("${identity.secure-cookie:false}") boolean secureCookie) {
        this.identity=identity; this.cookieName=cookieName; this.secureCookie=secureCookie;
    }

    @PostMapping(path="/api/identity/registrations", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RegistrationResponse> register(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RegistrationRequest request, HttpServletRequest servletRequest) {
        RegistrationView result=identity.register(key,request.email(),request.handle(),request.password(),servletRequest.getRemoteAddr());
        return registrationResponse(result,true);
    }
    @GetMapping(path="/api/identity/registrations/{registrationId}", produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RegistrationResponse> registration(@PathVariable String registrationId,
            @RequestHeader("Idempotency-Key") UUID key) {
        return registrationResponse(identity.registrationStatus(registrationId,key),false);
    }
    private ResponseEntity<RegistrationResponse> registrationResponse(RegistrationView value, boolean post) {
        RegistrationResponse body=new RegistrationResponse(value.registrationId(),value.status(),value.userId(),value.channelId(),"PENDING".equals(value.status())?value.retryAfterSeconds():null);
        if ("PENDING".equals(value.status())) return ResponseEntity.accepted().header("Cache-Control","no-store").body(body);
        return ResponseEntity.status(post?HttpStatus.CREATED:HttpStatus.OK).header("Cache-Control","no-store").body(body);
    }

    @PostMapping(path="/api/identity/sessions", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request,HttpServletRequest servletRequest,HttpServletResponse response) {
        LoginResult result=identity.login(request.login(),request.password(),servletRequest.getRemoteAddr());
        Duration maxAge=Duration.between(Instant.now(),result.expiresAt());
        response.addHeader("Set-Cookie",ResponseCookie.from(cookieName,result.credential()).httpOnly(true).secure(secureCookie).sameSite("Lax").path("/").maxAge(maxAge).build().toString());
        return ResponseEntity.ok().header("Cache-Control","no-store").body(new SessionResponse(result.userId(),result.expiresAt()));
    }
    @DeleteMapping("/api/identity/sessions/current")
    public ResponseEntity<Void> logout(@CookieValue(name="${identity.cookie-name:stream_session}",required=false) String credential,HttpServletResponse response) {
        identity.logout(credential);
        response.addHeader("Set-Cookie",ResponseCookie.from(cookieName,"").httpOnly(true).secure(secureCookie).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString());
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
    @GetMapping(path="/api/identity/csrf", produces=MediaType.APPLICATION_JSON_VALUE)
    public CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getHeaderName(),token.getToken()); }
    @GetMapping(path="/api/identity/public/users/{userId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public PublicIdentityResponse publicUser(@PathVariable String userId) {
        var result=identity.publicByUserId(userId); return new PublicIdentityResponse(result.userId(),result.handle());
    }
    @GetMapping(path="/api/identity/public/handles/{handle}",produces=MediaType.APPLICATION_JSON_VALUE)
    public PublicIdentityResponse publicHandle(@PathVariable String handle) {
        var result=identity.publicByHandle(handle); return new PublicIdentityResponse(result.userId(),result.handle());
    }
    @PostMapping(path="/internal/identity/sessions/introspect",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IntrospectionResponse> introspect(@RequestHeader(name="X-Session-Credential",required=false) String credential) {
        IntrospectionResponse body=identity.introspect(credential).map(v->new IntrospectionResponse(true,v.userId(),v.handle(),v.expiresAt()))
                .orElseGet(()->new IntrospectionResponse(false,null,null,null));
        return ResponseEntity.ok().header("Cache-Control","no-store, private").body(body);
    }

    public record RegistrationRequest(@NotBlank @Size(max=400) String email,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9_]{4,25}") String handle,
            @NotNull String password) { }
    public record LoginRequest(@NotBlank String login,@NotNull String password) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegistrationResponse(String registrationId,String status,String userId,String channelId,Integer retryAfterSeconds) { }
    public record SessionResponse(String userId,Instant expiresAtUtc) { }
    public record CsrfResponse(String headerName,String token) { }
    public record PublicIdentityResponse(String userId,String handle) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IntrospectionResponse(boolean active,String userId,String handle,Instant expiresAtUtc) { }
}
