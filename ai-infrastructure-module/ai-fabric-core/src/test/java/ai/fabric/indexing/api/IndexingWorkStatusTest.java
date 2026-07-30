package ai.fabric.indexing.api;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexingWorkStatusTest {

    @Test
    void classifiesEveryLifecycleStateWithoutConsumerOwnedRules() {
        assertThat(IndexingWorkState.COMMIT_PENDING.isInProgress()).isTrue();
        assertThat(IndexingWorkState.PENDING.isInProgress()).isTrue();
        assertThat(IndexingWorkState.PROCESSING.isInProgress()).isTrue();

        assertThat(IndexingWorkState.COMPLETED.isSuccessfulTerminal()).isTrue();
        assertThat(IndexingWorkState.SUPERSEDED.isSuccessfulTerminal()).isTrue();
        assertThat(IndexingWorkState.DEAD_LETTER.isSuccessfulTerminal()).isFalse();

        assertThat(IndexingWorkState.COMPLETED.isTerminal()).isTrue();
        assertThat(IndexingWorkState.SUPERSEDED.isTerminal()).isTrue();
        assertThat(IndexingWorkState.DEAD_LETTER.isTerminal()).isTrue();
        assertThat(IndexingWorkState.DEAD_LETTER.requiresOperatorReview())
            .isTrue();
    }

    @Test
    void normalizesSafeOptionalValuesAndDelegatesStateSemantics() {
        IndexingWorkStatus status = status(
            IndexingWorkState.DEAD_LETTER,
            " EMBEDDING_PROVIDER_FAILED ",
            " EMBEDDING_PROVIDER_FAILED ",
            " trace-7 "
        );

        assertThat(status.workId()).isEqualTo("71");
        assertThat(status.errorCode()).isEqualTo("EMBEDDING_PROVIDER_FAILED");
        assertThat(status.deadLetterReason())
            .isEqualTo("EMBEDDING_PROVIDER_FAILED");
        assertThat(status.correlationId()).isEqualTo("trace-7");
        assertThat(status.isTerminal()).isTrue();
        assertThat(status.isSuccessfulTerminal()).isFalse();
        assertThat(status.requiresOperatorReview()).isTrue();
        assertThat(status.isInProgress()).isFalse();
    }

    @Test
    void rejectsIncompleteIdentityAndInvalidRetryCounters() {
        assertThatThrownBy(() -> status(
            IndexingWorkState.PENDING,
            null,
            null,
            null,
            " "
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("entityId is required");

        assertThatThrownBy(() -> new IndexingWorkStatus(
            "71",
            "product",
            "p-1",
            AIIndexWorkType.UPSERT,
            AIProcessOperation.UPDATE,
            IndexingStrategy.SYNC,
            IndexingWorkState.PENDING,
            -1,
            5,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("retryCount must not be negative");
    }

    private IndexingWorkStatus status(
        IndexingWorkState state,
        String errorCode,
        String deadLetterReason,
        String correlationId
    ) {
        return status(
            state,
            errorCode,
            deadLetterReason,
            correlationId,
            "p-1"
        );
    }

    private IndexingWorkStatus status(
        IndexingWorkState state,
        String errorCode,
        String deadLetterReason,
        String correlationId,
        String entityId
    ) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 30, 12, 0);
        return new IndexingWorkStatus(
            "71",
            "product",
            entityId,
            AIIndexWorkType.UPSERT,
            AIProcessOperation.UPDATE,
            IndexingStrategy.SYNC,
            state,
            2,
            5,
            errorCode,
            deadLetterReason,
            correlationId,
            now.minusMinutes(2),
            now.minusMinutes(1),
            now.minusSeconds(30),
            null,
            now,
            now
        );
    }
}
