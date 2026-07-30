package ai.fabric.indexing.query;

import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.IndexingStatus;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.api.IndexingWorkState;
import ai.fabric.indexing.api.IndexingWorkStatus;
import ai.fabric.repository.IndexingQueueRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultIndexingWorkQueryTest {

    @Test
    void projectsTheCompleteSafeLoomAiStatusWithoutQueuePayloads()
        throws Exception {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        IndexingQueueEntry entry = entry(IndexingStatus.DEAD_LETTER);
        entry.setPayload("raw source and model evidence");
        entry.setResultPayload("raw provider result");
        entry.setProcessingNode("private-worker-node");
        when(repository.findById(71L)).thenReturn(Optional.of(entry));

        IndexingWorkStatus status = new DefaultIndexingWorkQuery(repository)
            .findByWorkId("71")
            .orElseThrow();

        assertThat(status.workId()).isEqualTo("71");
        assertThat(status.entityType()).isEqualTo("product");
        assertThat(status.entityId()).isEqualTo("p-1");
        assertThat(status.workType()).isEqualTo(AIIndexWorkType.UPSERT);
        assertThat(status.sourceOperation())
            .isEqualTo(AIProcessOperation.UPDATE);
        assertThat(status.strategy()).isEqualTo(IndexingStrategy.SYNC);
        assertThat(status.status()).isEqualTo(IndexingWorkState.DEAD_LETTER);
        assertThat(status.retryCount()).isEqualTo(5);
        assertThat(status.maxRetries()).isEqualTo(5);
        assertThat(status.errorCode()).isEqualTo("EMBEDDING_PROVIDER_FAILED");
        assertThat(status.deadLetterReason())
            .isEqualTo("EMBEDDING_PROVIDER_FAILED");
        assertThat(status.correlationId()).isEqualTo("trace-71");
        assertThat(status.requestedAt())
            .isEqualTo(LocalDateTime.of(2026, 7, 30, 11, 58));
        assertThat(status.scheduledFor())
            .isEqualTo(LocalDateTime.of(2026, 7, 30, 11, 59));
        assertThat(status.startedAt())
            .isEqualTo(LocalDateTime.of(2026, 7, 30, 11, 59, 30));
        assertThat(status.completedAt()).isNull();
        assertThat(status.lastErrorAt())
            .isEqualTo(LocalDateTime.of(2026, 7, 30, 12, 0));
        assertThat(status.updatedAt())
            .isEqualTo(LocalDateTime.of(2026, 7, 30, 12, 0));
        assertThat(status.isTerminal()).isTrue();
        assertThat(status.isSuccessfulTerminal()).isFalse();
        assertThat(status.requiresOperatorReview()).isTrue();

        JsonNode serialized = new ObjectMapper()
            .findAndRegisterModules()
            .valueToTree(status);
        assertThat(serialized.has("payload")).isFalse();
        assertThat(serialized.has("resultPayload")).isFalse();
        assertThat(serialized.has("processingNode")).isFalse();
        assertThat(serialized.toString())
            .doesNotContain("raw source")
            .doesNotContain("raw provider")
            .doesNotContain("private-worker-node");
    }

    @Test
    void mapsEveryInternalQueueStateIntoThePublicContract() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        DefaultIndexingWorkQuery query = new DefaultIndexingWorkQuery(repository);
        Map<IndexingStatus, IndexingWorkState> states = new LinkedHashMap<>();
        states.put(IndexingStatus.COMMIT_PENDING, IndexingWorkState.COMMIT_PENDING);
        states.put(IndexingStatus.PENDING, IndexingWorkState.PENDING);
        states.put(IndexingStatus.PROCESSING, IndexingWorkState.PROCESSING);
        states.put(IndexingStatus.COMPLETED, IndexingWorkState.COMPLETED);
        states.put(IndexingStatus.SUPERSEDED, IndexingWorkState.SUPERSEDED);
        states.put(IndexingStatus.DEAD_LETTER, IndexingWorkState.DEAD_LETTER);

        long workId = 80;
        for (Map.Entry<IndexingStatus, IndexingWorkState> state : states.entrySet()) {
            IndexingQueueEntry entry = entry(state.getKey());
            ReflectionTestUtils.setField(entry, "id", workId);
            when(repository.findById(workId)).thenReturn(Optional.of(entry));

            assertThat(query.findByWorkId(String.valueOf(workId)))
                .get()
                .extracting(IndexingWorkStatus::status)
                .isEqualTo(state.getValue());
            workId++;
        }
    }

    @Test
    void returnsEmptyForUnknownWorkWithoutLeakingRepositoryDetails() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThat(new DefaultIndexingWorkQuery(repository).findByWorkId("404"))
            .isEmpty();
        verify(repository).findById(404L);
    }

    @Test
    void rejectsMalformedWorkIdsBeforeRepositoryAccess() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        DefaultIndexingWorkQuery query = new DefaultIndexingWorkQuery(repository);

        assertThatThrownBy(() -> query.findByWorkId("not-a-work-id"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("workId must be a positive integer");
        assertThatThrownBy(() -> query.findByWorkId("0"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("workId must be a positive integer");
        assertThatThrownBy(() -> query.findByWorkId(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("workId is required");

        verify(repository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    private IndexingQueueEntry entry(IndexingStatus status) {
        IndexingQueueEntry entry = new IndexingQueueEntry();
        ReflectionTestUtils.setField(entry, "id", 71L);
        entry.setEntityType("product");
        entry.setEntityId("p-1");
        entry.setWorkType(AIIndexWorkType.UPSERT);
        entry.setSourceOperation(AIProcessOperation.UPDATE);
        entry.setStrategy(IndexingStrategy.SYNC);
        entry.setStatus(status);
        entry.setRetryCount(5);
        entry.setMaxRetries(5);
        entry.setErrorCode("EMBEDDING_PROVIDER_FAILED");
        entry.setDeadLetterReason("EMBEDDING_PROVIDER_FAILED");
        entry.setCorrelationId("trace-71");
        entry.setRequestedAt(LocalDateTime.of(2026, 7, 30, 11, 58));
        entry.setScheduledFor(LocalDateTime.of(2026, 7, 30, 11, 59));
        entry.setStartedAt(LocalDateTime.of(2026, 7, 30, 11, 59, 30));
        entry.setLastErrorAt(LocalDateTime.of(2026, 7, 30, 12, 0));
        entry.setUpdatedAt(LocalDateTime.of(2026, 7, 30, 12, 0));
        return entry;
    }
}
