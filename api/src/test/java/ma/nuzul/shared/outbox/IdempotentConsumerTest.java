package ma.nuzul.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** Story 3.1: the claim-then-handle decision (the transaction and the real table are IdempotentConsumerIT). */
class IdempotentConsumerTest {

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final StatementSpec statement = mock(StatementSpec.class, RETURNS_SELF);
    private final IdempotentConsumer consumer =
            new IdempotentConsumer(jdbc, TransactionOperations.withoutTransaction());
    private final AtomicInteger runs = new AtomicInteger();
    private final UUID eventId = UUID.randomUUID();

    private void claimReturns(int rows) {
        when(jdbc.sql(IdempotentConsumer.CLAIM)).thenReturn(statement);
        when(statement.update()).thenReturn(rows);
    }

    @Test
    void aFirstDeliveryClaimsTheEventAndRunsTheHandler() {
        claimReturns(1);

        boolean handled = consumer.handleOnce("notification", eventId, runs::incrementAndGet);

        assertThat(handled).isTrue();
        assertThat(runs).hasValue(1);
        verify(statement).param("consumer", "notification");
        verify(statement).param("eventId", eventId);
    }

    @Test
    void aDuplicateSkipsTheHandler() {
        claimReturns(0);

        boolean handled = consumer.handleOnce("notification", eventId, runs::incrementAndGet);

        assertThat(handled).isFalse();
        assertThat(runs).hasValue(0);
    }

    @Test
    void aHandlerFailurePropagatesSoTheContainerRetries() {
        claimReturns(1);
        IllegalStateException failure = new IllegalStateException("mail server down");

        assertThatThrownBy(() -> consumer.handleOnce("notification", eventId, () -> {
                    throw failure;
                }))
                .isSameAs(failure);
    }

    @Test
    void theClaimAndTheHandlerRunInsideOneTransaction() {
        claimReturns(1);
        AtomicInteger transactions = new AtomicInteger();
        TransactionOperations counting = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                transactions.incrementAndGet();
                return action.doInTransaction(null);
            }
        };

        new IdempotentConsumer(jdbc, counting).handleOnce("notification", eventId, () -> {
            assertThat(transactions).hasValue(1);
            runs.incrementAndGet();
        });

        assertThat(runs).hasValue(1);
        verify(statement).param(eq("consumer"), anyString());
        verify(statement).param(eq("eventId"), any(UUID.class));
    }
}
