package streaming.core.discovery.application;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.discovery.application.ProjectionIngestService.Kind;
import streaming.core.discovery.domain.ApplyOutcome;
import streaming.core.discovery.domain.StreamProjection;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static streaming.core.discovery.TestPayloads.envelope;
import static streaming.core.discovery.TestPayloads.live;

@ExtendWith(MockitoExtension.class)
class ProjectionIngestServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-06T12:00:05Z");
    private static final Instant OBSERVED=Instant.parse("2026-10-06T12:00:04Z");
    @Mock ProjectionStore store;
    private ProjectionIngestService service;

    @BeforeEach void setUp() {
        service=new ProjectionIngestService(new ProjectionParser(),store,()->NOW,new TransactionTemplate(new NoTransactions()));
    }

    private ObjectNode event(long version) { return envelope("evt_"+version,live("str_a","chn_a",version,5,OBSERVED)); }

    @Test void aNewEventIsRecordedAndAppliedThenAccepted() {
        when(store.recordInbox(eq("evt_7"),anyString(),eq("str_a"),eq(7L),eq(NOW))).thenReturn(true);
        when(store.apply(any(),eq(NOW),eq(NOW))).thenReturn(ApplyOutcome.APPLIED);

        var result=service.ingest(event(7));

        assertThat(result.kind()).isEqualTo(Kind.ACCEPTED);
        assertThat(result.outcome()).isEqualTo("APPLIED");
        verify(store,never()).setInboxOutcome(anyString(),anyString());
        verify(store,never()).recordConflict(any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test void theProjectionHandedToTheStoreIsTheParsedPayload() {
        when(store.recordInbox(anyString(),anyString(),anyString(),anyLong(),any())).thenReturn(true);
        when(store.apply(any(),any(),any())).thenReturn(ApplyOutcome.APPLIED);
        service.ingest(event(7));
        var captured=org.mockito.ArgumentCaptor.forClass(StreamProjection.class);
        verify(store).apply(captured.capture(),eq(NOW),eq(NOW));
        assertThat(captured.getValue().streamId()).isEqualTo("str_a");
        assertThat(captured.getValue().projectionVersion()).isEqualTo(7);
        assertThat(captured.getValue().viewerCount()).isEqualTo(5);
    }

    @Test void anIdenticalRedeliveryIsADuplicateAndChangesNothing() {
        var body=event(7);
        when(store.recordInbox(eq("evt_7"),anyString(),any(),anyLong(),any())).thenReturn(false);
        // Same body hashed twice gives the same hash, which is what the stored inbox row would hold.
        var hash=new ProjectionParser().parseEvent(body).eventHash();
        when(store.inboxHash("evt_7")).thenReturn(Optional.of(hash));

        var result=service.ingest(body);

        assertThat(result.kind()).isEqualTo(Kind.DUPLICATE);
        verify(store,never()).apply(any(),any(),any());
        verify(store,never()).recordConflict(any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test void theSameEventIdWithDifferentContentIsAConflictAndIsRecordedForTheOperator() {
        when(store.recordInbox(eq("evt_7"),anyString(),any(),anyLong(),any())).thenReturn(false);
        when(store.inboxHash("evt_7")).thenReturn(Optional.of("f".repeat(64)));

        var result=service.ingest(event(7));

        assertThat(result.kind()).isEqualTo(Kind.CONFLICT);
        assertThat(result.outcome()).isEqualTo("EVENT_ID_CONFLICT");
        verify(store).recordConflict(eq("evt_7"),eq("str_a"),eq(7L),eq("EVENT_ID_REUSED"),eq("f".repeat(64)),anyString(),anyString(),eq(NOW));
        verify(store,never()).apply(any(),any(),any());
    }

    @Test void anOlderVersionIsAcceptedButIgnored() {
        when(store.recordInbox(anyString(),anyString(),anyString(),anyLong(),any())).thenReturn(true);
        when(store.apply(any(),any(),any())).thenReturn(ApplyOutcome.IGNORED_OLDER);
        var result=service.ingest(event(3));
        assertThat(result.kind()).isEqualTo(Kind.ACCEPTED);
        assertThat(result.outcome()).isEqualTo("IGNORED_OLDER");
        verify(store).setInboxOutcome("evt_3","IGNORED_OLDER");
    }

    @Test void anEqualVersionWithTheSameContentIsAcceptedButIgnored() {
        when(store.recordInbox(anyString(),anyString(),anyString(),anyLong(),any())).thenReturn(true);
        when(store.apply(any(),any(),any())).thenReturn(ApplyOutcome.IGNORED_SAME);
        var result=service.ingest(event(7));
        assertThat(result.outcome()).isEqualTo("IGNORED_SAME");
        verify(store).setInboxOutcome("evt_7","IGNORED_SAME");
    }

    @Test void anEqualVersionWithDifferentContentIsAConflict() {
        when(store.recordInbox(anyString(),anyString(),anyString(),anyLong(),any())).thenReturn(true);
        when(store.apply(any(),any(),any())).thenReturn(ApplyOutcome.CONFLICT_SAME_VERSION);
        when(store.projectionHash("str_a")).thenReturn(Optional.of("a".repeat(64)));

        var result=service.ingest(event(7));

        assertThat(result.kind()).isEqualTo(Kind.CONFLICT);
        assertThat(result.outcome()).isEqualTo("PROJECTION_CONFLICT");
        verify(store).setInboxOutcome("evt_7","CONFLICT");
        verify(store).recordConflict(eq("evt_7"),eq("str_a"),eq(7L),eq("SAME_VERSION_DIFFERENT_CONTENT"),eq("a".repeat(64)),anyString(),anyString(),eq(NOW));
    }

    @Test void aStreamClaimedByAnotherChannelIsAConflict() {
        when(store.recordInbox(anyString(),anyString(),anyString(),anyLong(),any())).thenReturn(true);
        when(store.apply(any(),any(),any())).thenReturn(ApplyOutcome.CONFLICT_CHANNEL);
        when(store.projectionHash("str_a")).thenReturn(Optional.empty());

        var result=service.ingest(event(7));

        assertThat(result.kind()).isEqualTo(Kind.CONFLICT);
        verify(store).recordConflict(eq("evt_7"),eq("str_a"),eq(7L),eq("CHANNEL_MISMATCH"),isNull(),anyString(),anyString(),eq(NOW));
    }

    @Test void anInvalidEventNeverTouchesStorage() {
        ObjectNode broken=event(7);
        broken.put("producer","chat");
        assertThatThrownBy(()->service.ingest(broken)).isInstanceOfSatisfying(DiscoveryException.class,e->{
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo("INVALID_EVENT");
        });
        verifyNoInteractions(store);
    }

    private static final class NoTransactions implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
        @Override public void commit(TransactionStatus status) { }
        @Override public void rollback(TransactionStatus status) { }
    }
}
