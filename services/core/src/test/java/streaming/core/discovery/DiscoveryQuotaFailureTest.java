package streaming.core.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import streaming.core.discovery.api.DiscoveryGraphQlController;
import streaming.core.discovery.domain.RequestLimiter;
import streaming.core.discovery.infrastructure.graphql.DiscoveryGraphQl;
import streaming.core.discovery.infrastructure.graphql.GraphQlQueryGuard;
import streaming.core.security.RequestAuditFilter;
import streaming.core.security.TrustedProxies;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DiscoveryQuotaFailureTest {
    @Test void unavailableSharedQuotaReturnsGraphQl503WithoutExecutingQuery() throws Exception {
        var engine=mock(DiscoveryGraphQl.class);
        var limiter=mock(RequestLimiter.class);
        when(limiter.tryAcquire(anyString())).thenThrow(new DataAccessResourceFailureException("fixture unavailable"));
        var request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.1");
        request.setAttribute(RequestAuditFilter.REQUEST_ID_ATTRIBUTE,"fixture-request");
        var controller=new DiscoveryGraphQlController(engine,mock(GraphQlQueryGuard.class),limiter,TrustedProxies.parse(""),new ObjectMapper());
        var response=controller.graphql(request);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        var error=new ObjectMapper().readTree(response.getBody()).at("/errors/0/extensions");
        assertThat(error.path("code").asText()).isEqualTo("DISCOVERY_UNAVAILABLE");
        assertThat(error.path("requestId").asText()).isEqualTo("fixture-request");
        verifyNoInteractions(engine);
    }
}
