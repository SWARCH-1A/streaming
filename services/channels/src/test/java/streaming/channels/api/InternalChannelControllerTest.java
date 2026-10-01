package streaming.channels.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import streaming.channels.application.ChannelProvisioningService;
import streaming.channels.application.ChannelProvisioningService.ProvisionState;
import streaming.channels.application.StreamProjectionService;

class InternalChannelControllerTest {
    @Test
    void pendingLookupUsesAcceptedStatusAndRetryHint() {
        ChannelProvisioningService provisioning=mock(ChannelProvisioningService.class);
        when(provisioning.lookup("reg_123")).thenReturn(new ProvisionState("PENDING",null,null,250));
        var controller=new InternalChannelController(provisioning,mock(StreamProjectionService.class));

        var response=controller.state("reg_123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().state()).isEqualTo("PENDING");
        assertThat(response.getBody().retryAfterMs()).isEqualTo(250);
    }
}
