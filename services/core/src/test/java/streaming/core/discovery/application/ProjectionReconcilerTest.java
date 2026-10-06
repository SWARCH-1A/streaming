package streaming.core.discovery.application;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.discovery.application.ProjectionReconciler.Result;
import streaming.core.discovery.application.StreamingSnapshotClient.SnapshotExpiredException;
import streaming.core.discovery.application.StreamingSnapshotClient.SnapshotPage;
import streaming.core.discovery.application.StreamingSnapshotClient.SnapshotUnavailableException;
import streaming.core.discovery.domain.ApplyOutcome;
import streaming.core.discovery.domain.StreamProjection;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static streaming.core.discovery.TestPayloads.live;

@ExtendWith(MockitoExtension.class)
class ProjectionReconcilerTest {
    private static final Instant NOW=Instant.parse("2026-10-06T12:00:10Z");
    private static final Instant CAPTURED=Instant.parse("2026-10-06T12:00:09Z");
    @Mock StreamingSnapshotClient client;
    @Mock ProjectionStore store;
    private ProjectionReconciler reconciler;

    @BeforeEach void setUp() {
        reconciler=new ProjectionReconciler(client,new ProjectionParser(),store,()->NOW,new TransactionTemplate(new NoTransactions()));
    }

    private static JsonNode item(String id,long version) { return live(id,"chn_"+id,version,3,CAPTURED); }
    private static SnapshotPage page(String snapshot,long watermark,String next,JsonNode... items) {
        return new SnapshotPage(snapshot,watermark,CAPTURED,CAPTURED.plusSeconds(300),List.of(items),next);
    }

    @Test void doesNothingWhileStreamingIsNotConfigured() {
        when(client.configured()).thenReturn(false);
        assertThat(reconciler.runOnce()).isEqualTo(Result.SKIPPED);
        verifyNoInteractions(store);
    }

    @Test void readsEveryPageOfOneCutAndPublishesItInOnePass() {
        when(client.configured()).thenReturn(true);
        when(client.page(50,null)).thenReturn(page("snap1",100,"next",item("str_a",4),item("str_b",9)));
        when(client.page(50,"next")).thenReturn(page("snap1",100,null,item("str_c",2)));
        when(store.apply(any(),eq(NOW),eq(NOW))).thenReturn(ApplyOutcome.APPLIED);

        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);

        var applied=ArgumentCaptor.forClass(StreamProjection.class);
        verify(store,times(3)).apply(applied.capture(),eq(NOW),eq(NOW));
        assertThat(applied.getAllValues()).extracting(StreamProjection::streamId).containsExactly("str_a","str_b","str_c");
        verify(store).pruneAbsent(List.of("str_a","str_b","str_c"),100);
        verify(store).saveSuccess("snap1",100,CAPTURED,NOW);
        verify(store,never()).saveFailure(anyString(),any());
    }

    @Test void anEmptyCutStillRemovesWhatItProvesAbsent() {
        when(client.configured()).thenReturn(true);
        when(client.page(50,null)).thenReturn(page("snap1",55,null));
        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);
        verify(store).pruneAbsent(List.of(),55);
        verify(store).saveSuccess("snap1",55,CAPTURED,NOW);
    }

    @Test void anExpiredCutRestartsFromTheFirstPage() {
        when(client.configured()).thenReturn(true);
        when(client.page(50,null)).thenThrow(new SnapshotExpiredException()).thenReturn(page("snap2",7,null,item("str_a",1)));
        when(store.apply(any(),any(),any())).thenReturn(ApplyOutcome.APPLIED);

        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);

        verify(client,times(2)).page(50,null);
        verify(store).saveSuccess("snap2",7,CAPTURED,NOW);
        verify(store,never()).saveFailure(anyString(),any());
    }

    @Test void givesUpAfterThreeExpiredCutsAndKeepsThePreviousProjection() {
        when(client.configured()).thenReturn(true);
        when(client.page(50,null)).thenThrow(new SnapshotExpiredException());

        assertThat(reconciler.runOnce()).isEqualTo(Result.FAILED);

        verify(client,times(3)).page(50,null);
        verify(store).saveFailure("SNAPSHOT_EXPIRED",NOW);
        verify(store,never()).apply(any(),any(),any());
        verify(store,never()).pruneAbsent(anyCollection(),anyLong());
        verify(store,never()).saveSuccess(any(),anyLong(),any(),any());
    }

    @Test void anUnavailableStreamingIsRecordedWithoutTouchingTheProjectionOrRetryingBlindly() {
        when(client.configured()).thenReturn(true);
        when(client.page(50,null)).thenThrow(new SnapshotUnavailableException("HTTP_503",null));

        assertThat(reconciler.runOnce()).isEqualTo(Result.FAILED);

        verify(client,times(1)).page(50,null);
        verify(store).saveFailure("HTTP_503",NOW);
        verify(store,never()).apply(any(),any(),any());
        verify(store,never()).pruneAbsent(anyCollection(),anyLong());
    }

    @Test void aFailureOnALaterPageAppliesNothingFromEarlierPages() {
        when(client.configured()).thenReturn(true);
        when(client.page(50,null)).thenReturn(page("snap1",100,"next",item("str_a",4)));
        when(client.page(50,"next")).thenThrow(new SnapshotUnavailableException("TRANSPORT_UNAVAILABLE",null));

        assertThat(reconciler.runOnce()).isEqualTo(Result.FAILED);

        verify(store,never()).apply(any(),any(),any());
        verify(store,never()).pruneAbsent(anyCollection(),anyLong());
        verify(store).saveFailure("TRANSPORT_UNAVAILABLE",NOW);
    }

    @Test void pagesFromDifferentCutsAreRejected() {
        when(client.configured()).thenReturn(true);
        when(client.page(50,null)).thenReturn(page("snap1",100,"next",item("str_a",4)));
        when(client.page(50,"next")).thenReturn(page("snap2",100,null,item("str_b",4)));
        assertThat(reconciler.runOnce()).isEqualTo(Result.FAILED);
        verify(store).saveFailure("INCONSISTENT_CUT",NOW);
        verify(store,never()).apply(any(),any(),any());

        when(client.page(50,"next")).thenReturn(page("snap1",101,null,item("str_b",4)));
        assertThat(reconciler.runOnce()).isEqualTo(Result.FAILED);
        verify(store,times(2)).saveFailure("INCONSISTENT_CUT",NOW);
    }

    @Test void theSameStreamTwiceInOneCutIsRejected() {
        when(client.configured()).thenReturn(true);
        when(client.page(50,null)).thenReturn(page("snap1",100,null,item("str_a",4),item("str_a",5)));
        assertThat(reconciler.runOnce()).isEqualTo(Result.FAILED);
        verify(store).saveFailure("DUPLICATE_IN_CUT",NOW);
        verify(store,never()).apply(any(),any(),any());
    }

    @Test void oneMalformedItemRejectsTheWholeCutSoNothingIsHalfPublished() {
        when(client.configured()).thenReturn(true);
        var broken=item("str_b",4).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)broken).put("availability","ONLINE");
        when(client.page(50,null)).thenReturn(page("snap1",100,null,item("str_a",4),broken));
        assertThat(reconciler.runOnce()).isEqualTo(Result.FAILED);
        verify(store).saveFailure("INVALID_EVENT",NOW);
        verify(store,never()).apply(any(),any(),any());
        verify(store,never()).pruneAbsent(anyCollection(),anyLong());
    }

    @Test void aConflictingItemIsLoggedButDoesNotAbortTheRest() {
        when(client.configured()).thenReturn(true);
        when(client.page(50,null)).thenReturn(page("snap1",100,null,item("str_a",4),item("str_b",4)));
        when(store.apply(any(),any(),any())).thenReturn(ApplyOutcome.CONFLICT_SAME_VERSION,ApplyOutcome.APPLIED);

        assertThat(reconciler.runOnce()).isEqualTo(Result.SUCCESS);

        verify(store,times(2)).apply(any(),any(),any());
        verify(store).pruneAbsent(List.of("str_a","str_b"),100);
        verify(store).saveSuccess("snap1",100,CAPTURED,NOW);
    }

    @Test void aDatabaseFailureWhilePublishingIsReportedAsFailedNeverThrown() {
        when(client.configured()).thenReturn(true);
        when(client.page(50,null)).thenReturn(page("snap1",100,null,item("str_a",4)));
        when(store.apply(any(),any(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));

        assertThat(reconciler.runOnce()).isEqualTo(Result.FAILED);
        verify(store).saveFailure("RECONCILE_ERROR",NOW);
    }

    @Test void failingToRecordTheFailureDoesNotEscape() {
        when(client.configured()).thenReturn(true);
        when(client.page(50,null)).thenThrow(new SnapshotUnavailableException("HTTP_500",null));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("down")).when(store).saveFailure(anyString(),any());
        assertThat(reconciler.runOnce()).isEqualTo(Result.FAILED);
    }

    private static final class NoTransactions implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
        @Override public void commit(TransactionStatus status) { }
        @Override public void rollback(TransactionStatus status) { }
    }
}
