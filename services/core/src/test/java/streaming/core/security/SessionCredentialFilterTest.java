package streaming.core.security;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SessionCredentialFilterTest {
    final IdentityApplicationService identity=mock(IdentityApplicationService.class);
    final SessionCredentialFilter filter=new SessionCredentialFilter(identity,new ObjectMapper(),"stream_session");
    final String token="A".repeat(43);

    @Test void bearerResolvesOpaqueCoreSessionWithoutCookie() throws Exception {
        var request=new MockHttpServletRequest("PATCH","/api/profile/me");
        request.addHeader("Authorization","Bearer "+token);
        when(identity.introspect(token)).thenReturn(Optional.of(new IdentityApplicationService.SessionView("usr_fixture","fixture_01",Instant.now().plusSeconds(60))));
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->assertThat(req.getAttribute(SessionCredentialFilter.CREDENTIAL)).isEqualTo(token));
        assertThat(SessionCredentialFilter.nativeRequest(request)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
    @Test void contradictoryCredentialsAndInvalidBearerAreRejectedBeforeController() throws Exception {
        for(String header:new String[]{"Basic abc","Bearer bad","Bearer "+token}) {
            var request=new MockHttpServletRequest("GET","/api/profile/me"); request.addHeader("Authorization",header);
            request.setCookies(new Cookie("stream_session",token));
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(req,res)->{ throw new AssertionError("Controller must not run"); });
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).doesNotContain(token);
        }
    }
    @Test void revokedBearerIsNotConvertedToAnonymous() throws Exception {
        var request=new MockHttpServletRequest("GET","/api/channels/chn_fixture"); request.addHeader("Authorization","Bearer "+token);
        when(identity.introspect(token)).thenReturn(Optional.empty());
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->{ throw new AssertionError(); });
        assertThat(response.getStatus()).isEqualTo(401);
    }
    @Test void cookiesAndBrowserOriginKeepCsrfEvenOnNativeLoginPath() {
        var request=new MockHttpServletRequest("POST","/api/identity/mobile-sessions");
        assertThat(SessionCredentialFilter.nativeRequest(request)).isTrue();
        request.addHeader("Origin","https://fixture.example.test");
        assertThat(SessionCredentialFilter.nativeRequest(request)).isFalse();
        request.removeHeader("Origin"); request.addHeader("Cookie","stream_session="+token);
        assertThat(SessionCredentialFilter.nativeRequest(request)).isFalse();
    }
}
