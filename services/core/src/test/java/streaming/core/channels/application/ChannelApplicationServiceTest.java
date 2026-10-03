package streaming.core.channels.application;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import streaming.core.channels.application.AccountAuthentication.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelApplicationServiceTest {
    @Mock ChannelStore channels;
    @Mock AccountAuthentication authentication;
    @Mock BannerStorage banners;
    private ChannelApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ChannelApplicationService(channels, authentication, banners, "/api/channels/banners");
    }

    @Test
    void requirePrincipalReturnsTheChannelsPrincipalFromItsAuthenticationPort() {
        var principal = new Principal("usr_owner");
        when(authentication.introspect("session-token")).thenReturn(Optional.of(principal));

        assertThat(service.requirePrincipal("session-token")).isSameAs(principal);
    }

    @Test
    void requirePrincipalRejectsAnUnknownSession() {
        when(authentication.introspect(null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requirePrincipal(null))
                .isInstanceOfSatisfying(ChannelException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(error.code()).isEqualTo("AUTH_REQUIRED");
                });
    }
}
