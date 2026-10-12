package streaming.core.channels.application;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import streaming.core.accounts.profile.application.ProfileApplicationService.ProfileView;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelBootstrapServiceTest {
    ChannelQueries queries=mock(ChannelQueries.class);
    StreamingChannelSnapshots streaming=mock(StreamingChannelSnapshots.class);
    ChannelBootstrapService service=new ChannelBootstrapService(queries,streaming);
    ChannelQueries.Bootstrap local=new ChannelQueries.Bootstrap(new ChannelQueries.ChannelView("chn_a","usr_a","desc",null,1),
            "ana",new ProfileView("usr_a","Ana","bio",null,Instant.now(),1),null,false,"UNKNOWN");
    void local() { when(queries.byHandle("ana")).thenReturn(Optional.of(local)); }
    @Test void authoritativeAbsenceIsOfflineAndProviderFailurePreservesCoreDataAsUnknown() {
        local(); when(streaming.channel("chn_a","rid")).thenReturn(new StreamingChannelSnapshots.Snapshot(false,null,Instant.now()));
        var absent=service.byHandle("ana","rid").orElseThrow();
        assertThat(absent.availability()).isEqualTo("OFFLINE"); assertThat(absent.streamStatusFresh()).isTrue();
        when(streaming.channel("chn_a","rid")).thenThrow(new StreamingChannelSnapshots.Unavailable());
        var unknown=service.byHandle("ana","rid").orElseThrow();
        assertThat(unknown.availability()).isEqualTo("UNKNOWN"); assertThat(unknown.streamStatusFresh()).isFalse();
        assertThat(unknown.channel()).isEqualTo(local.channel()); assertThat(unknown.profile()).isEqualTo(local.profile());
    }
    @Test void staleOrFutureProviderClockCannotConfirmOffline() {
        local();
        for(var time:java.util.List.of(Instant.now().minusSeconds(6),Instant.now().plusSeconds(6))) {
            when(streaming.channel("chn_a",null)).thenReturn(new StreamingChannelSnapshots.Snapshot(false,null,time));
            assertThat(service.byHandle("ana",null).orElseThrow().streamStatusFresh()).isFalse();
        }
    }
    @Test void missingCoreChannelDoesNotCallStreaming() {
        when(queries.byHandle("missing")).thenReturn(Optional.empty());
        assertThat(service.byHandle("missing",null)).isEmpty(); verifyNoInteractions(streaming);
    }
}
