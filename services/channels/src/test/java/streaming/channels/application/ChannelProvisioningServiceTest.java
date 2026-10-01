package streaming.channels.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import streaming.channels.application.ChannelStore.Fence;

class ChannelProvisioningServiceTest {
    private static final String REGISTRATION_ID="reg_123";

    @Test
    void lookupKeepsOpenProvisionPendingBeforeDeadline() {
        ChannelStore store=org.mockito.Mockito.mock(ChannelStore.class);
        Instant deadline=Instant.now().plus(1,ChronoUnit.HOURS);
        when(store.findByRegistration(REGISTRATION_ID)).thenReturn(Optional.empty());
        when(store.findFence(REGISTRATION_ID)).thenReturn(Optional.of(new Fence(REGISTRATION_ID,"usr_123",deadline,"OPEN")));

        var state=new ChannelProvisioningService(store).lookup(REGISTRATION_ID);

        assertThat(state.state()).isEqualTo("PENDING");
        assertThat(state.retryAfterMs()).isEqualTo(250);
        verify(store,never()).saveFence(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lookupDoesNotFenceUnknownRegistration() {
        ChannelStore store=org.mockito.Mockito.mock(ChannelStore.class);
        when(store.findByRegistration(REGISTRATION_ID)).thenReturn(Optional.empty());
        when(store.findFence(REGISTRATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(()->new ChannelProvisioningService(store).lookup(REGISTRATION_ID))
                .isInstanceOfSatisfying(ChannelException.class,error->{
                    assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.code()).isEqualTo("UNKNOWN_REGISTRATION");
                });
        verify(store,never()).saveFence(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lookupPersistsTerminalAbsentOnlyAfterDeadline() {
        ChannelStore store=org.mockito.Mockito.mock(ChannelStore.class);
        Instant deadline=Instant.now().minus(1,ChronoUnit.HOURS);
        when(store.findByRegistration(REGISTRATION_ID)).thenReturn(Optional.empty());
        when(store.findFence(REGISTRATION_ID)).thenReturn(Optional.of(new Fence(REGISTRATION_ID,"usr_123",deadline,"OPEN")));

        var state=new ChannelProvisioningService(store).lookup(REGISTRATION_ID);

        assertThat(state.state()).isEqualTo("ABSENT");
        verify(store).saveFence(org.mockito.ArgumentMatchers.eq(REGISTRATION_ID),org.mockito.ArgumentMatchers.eq("usr_123"),
                org.mockito.ArgumentMatchers.eq(deadline),org.mockito.ArgumentMatchers.eq("ABSENT"),org.mockito.ArgumentMatchers.any(Instant.class));
    }
}
