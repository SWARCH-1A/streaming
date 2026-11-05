package streaming.core.accounts.identity.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.accounts.identity.application.RecoveryPublisher;
import streaming.core.accounts.identity.application.RecoveryStore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryOutboxDispatcherTest {
    private final RecoveryStore store = mock(RecoveryStore.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final RecoveryOutboxDispatcher dispatcher = new RecoveryOutboxDispatcher(store, transactions,
            provider(), true);
    private final RecoveryStore.Publication event = new RecoveryStore.Publication("evt_fixture", "{\"eventId\":\"evt_fixture\"}", 1);

    @SuppressWarnings("unchecked") private static ObjectProvider<RecoveryPublisher> provider() { return mock(ObjectProvider.class); }
    @AfterEach void close() { dispatcher.close(); }

    @SuppressWarnings("unchecked") private void pending() {
        doAnswer(invocation -> ((TransactionCallback<Object>) invocation.getArgument(0))
                .doInTransaction(new SimpleTransactionStatus())).when(transactions).execute(any());
        when(store.leasePublications(any(UUID.class), any(Instant.class), any(Instant.class), eq(10)))
                .thenReturn(List.of(event));
    }

    @Test void publicationIsRecordedOnlyAfterDurableConfirmation() {
        pending();
        RecoveryPublisher publisher = publication -> {
            verify(store, never()).published(any(), any(), any());
            org.assertj.core.api.Assertions.assertThat(publication).isSameAs(event);
        };
        dispatcher.drain(publisher);
        verify(store).published(eq(event.eventId()), any(UUID.class), any(Instant.class));
        verify(store, never()).retry(any(), any(), any());
    }

    @Test void failedConfirmationPreservesTheEventAndSchedulesABoundedRetry() {
        pending();
        dispatcher.drain(publication -> { throw new IllegalStateException("fixture-sensitive-transport-diagnostic"); });
        verify(store, never()).published(any(), any(), any());
        verify(store).retry(eq(event.eventId()), any(UUID.class), any(Instant.class));
    }
}
