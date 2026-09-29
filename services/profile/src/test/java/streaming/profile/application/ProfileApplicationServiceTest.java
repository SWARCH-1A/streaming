package streaming.profile.application;

import tools.jackson.databind.node.JsonNodeFactory;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import streaming.profile.application.IdentityClient.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileApplicationServiceTest {
    @Mock ProfileStore profiles;
    @Mock IdentityClient identity;
    @Mock AvatarStorage avatars;
    private ProfileApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ProfileApplicationService(profiles, identity, avatars, "/api/profile/avatars");
    }

    @Test
    void publicReadUsesIdentityHandleFallbackWhenNoProfileProjectionExists() {
        when(identity.findActiveUser("usr_1"))
                .thenReturn(Optional.of(new IdentityClient.PublicIdentity("usr_1", "caster_01")));
        when(profiles.find("usr_1")).thenReturn(Optional.empty());

        var profile = service.getPublic("usr_1");

        assertThat(profile.userId()).isEqualTo("usr_1");
        assertThat(profile.displayName()).isEqualTo("caster_01");
        assertThat(profile.bio()).isEmpty();
        assertThat(profile.avatarUri()).isNull();
        assertThat(profile.profileVersion()).isZero();
    }

    @Test
    void publicReadReturnsSameNotFoundForIdentityThatIsNotActive() {
        when(identity.findActiveUser("usr_pending" )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublic("usr_pending"))
                .isInstanceOfSatisfying(ProfileException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.code()).isEqualTo("PROFILE_NOT_FOUND");
                });
        verify(profiles, never()).find(anyString());
    }

    @Test
    void patchRejectsClientSuppliedTargetUserId() {
        var body = JsonNodeFactory.instance.objectNode().put("userId", "usr_other");

        assertThatThrownBy(() -> service.patch(new Principal("usr_self", "caster_01", null), body))
                .isInstanceOfSatisfying(ProfileException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(profiles).lockUser("usr_self");
        verify(profiles, never()).update(anyString(), anyString(), anyString(), any(), any(), any());
    }
}
