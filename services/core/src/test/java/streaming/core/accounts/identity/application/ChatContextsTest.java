package streaming.core.accounts.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import streaming.core.accounts.profile.application.ProfileApplicationService;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatContextsTest {
    IdentityApplicationService accounts=mock(IdentityApplicationService.class);
    ProfileApplicationService profiles=mock(ProfileApplicationService.class);
    StreamingSessions sessions=mock(StreamingSessions.class);
    ChatContexts contexts=new ChatContexts(accounts,profiles,sessions);
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() {
        when(accounts.introspect("credential")).thenReturn(Optional.of(new IdentityApplicationService.SessionView("usr_a","ana",Instant.now().plusSeconds(100))));
        when(profiles.getPublic("usr_a")).thenReturn(new ProfileApplicationService.ProfileView("usr_a","Ana",null,null,Instant.now(),3));
    }
    tools.jackson.databind.JsonNode body() { return json.createObjectNode().put("sessionId","ses_a").put("clientMessageId",UUID.randomUUID().toString()); }
    StreamingSessions.Snapshot snapshot(String status,String availability) { return new StreamingSessions.Snapshot("str_a","ses_a",1,2,status,availability,42,Instant.now()); }
    @Test void everySendReadsFreshAuthorAndLiveGracePermissionWithoutCache() {
        when(sessions.session("ses_a","rid")).thenReturn(snapshot("LIVE","RECONNECTING"));
        assertThat(contexts.authorize("credential",body(),"rid").writeAllowed()).isTrue();
        when(profiles.getPublic("usr_a")).thenReturn(new ProfileApplicationService.ProfileView("usr_a","Ana nueva",null,"/api/profile/avatars/a",Instant.now(),4));
        var newer=contexts.authorize("credential",body(),"rid");
        assertThat(newer.displayName()).isEqualTo("Ana nueva"); assertThat(newer.profileVersion()).isEqualTo(4);
        verify(accounts,times(2)).introspect("credential"); verify(sessions,times(2)).session("ses_a","rid");
    }
    @Test void preparingAndEndedReturnReadOnlyContextForDeduplication() {
        when(sessions.session("ses_a",null)).thenReturn(snapshot("PREPARING","OFFLINE"));
        var preparing=contexts.authorize("credential",body(),null);
        assertThat(preparing.writeAllowed()).isFalse(); assertThat(preparing.denialCode()).isEqualTo("CHAT_NOT_OPEN");
        when(sessions.session("ses_a",null)).thenReturn(snapshot("ENDED","OFFLINE"));
        assertThat(contexts.authorize("credential",body(),null).denialCode()).isEqualTo("CHAT_READ_ONLY");
    }
    @Test void revokedSessionStopsBeforeAuthorOrStreamingLookup() {
        when(accounts.introspect("credential")).thenReturn(Optional.empty());
        assertThatThrownBy(()->contexts.authorize("credential",body(),null)).isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.code()).isEqualTo("AUTH_REQUIRED"));
        verifyNoInteractions(profiles,sessions);
    }
    @Test void missingTimelineAndUnavailableProviderFailClosed() {
        when(sessions.session("ses_a",null)).thenThrow(new StreamingSessions.TimelineUnavailable());
        assertThatThrownBy(()->contexts.authorize("credential",body(),null)).isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.code()).isEqualTo("TIMELINE_UNAVAILABLE"));
        doThrow(new StreamingSessions.Unavailable(false)).when(sessions).session("ses_a",null);
        assertThatThrownBy(()->contexts.authorize("credential",body(),null)).isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.code()).isEqualTo("STREAMING_UNAVAILABLE"));
    }
    @Test void callerCannotOverrideAuthorOrSubmitPathOrNonUuidId() {
        for(var input:java.util.List.of(body().deepCopy().withObject("/").put("userId","usr_other"),
                json.createObjectNode().put("sessionId","../other").put("clientMessageId",UUID.randomUUID().toString()),
                json.createObjectNode().put("sessionId","ses_a").put("clientMessageId","1-1-1-1-1"))) {
            assertThatThrownBy(()->contexts.authorize("credential",input,null)).isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
        }
        verifyNoInteractions(sessions);
    }
}
