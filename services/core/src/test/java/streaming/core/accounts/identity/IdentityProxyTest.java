package streaming.core.accounts.identity;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import streaming.core.accounts.identity.api.IdentityController;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.security.TrustedProxies;
import static org.mockito.Mockito.*;

/** The proxy policy must apply to Identity's SQL-backed IP quotas as well as Discovery. */
class IdentityProxyTest {
    final IdentityApplicationService accounts=mock(IdentityApplicationService.class);
    final IdentityController controller=new IdentityController(accounts,"stream_session",false,TrustedProxies.parse("192.0.2.10"));

    @ParameterizedTest
    @CsvSource({"192.0.2.10,203.0.113.11,203.0.113.11", "192.0.2.20,203.0.113.11,192.0.2.20", "192.0.2.10,invalid,192.0.2.10", "192.0.2.10,'203.0.113.11, 203.0.113.12',203.0.113.12"})
    void registrationUsesObservedClientOnlyThroughConfiguredProxy(String peer,String forwarded,String expected) {
        var request=new MockHttpServletRequest();request.setRemoteAddr(peer);request.addHeader("X-Forwarded-For",forwarded);
        UUID key=UUID.randomUUID();
        when(accounts.register(key,"fixture@example.test","fixture_01","fixture-only password",expected))
                .thenReturn(new IdentityApplicationService.RegistrationView("reg_fixture","ACTIVE","usr_fixture","chn_fixture"));
        controller.register(key,new IdentityController.RegistrationRequest("fixture@example.test","fixture_01","fixture-only password"),request);
        verify(accounts).register(key,"fixture@example.test","fixture_01","fixture-only password",expected);
    }
    @Test void loginUsesTheSameProxyBoundary() {
        var request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.10");request.addHeader("X-Forwarded-For","203.0.113.20");
        when(accounts.login("fixture_01","fixture-only password","203.0.113.20"))
                .thenReturn(new IdentityApplicationService.LoginResult("usr_fixture","fixture_01","fictitious-credential",Instant.now().plusSeconds(60)));
        controller.login(new IdentityController.LoginRequest("fixture_01","fixture-only password"),request,new MockHttpServletResponse());
        verify(accounts).login("fixture_01","fixture-only password","203.0.113.20");
    }
}
