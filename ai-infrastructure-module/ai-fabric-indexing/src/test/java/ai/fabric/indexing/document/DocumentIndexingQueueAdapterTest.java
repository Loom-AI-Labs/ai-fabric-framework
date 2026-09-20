package ai.fabric.indexing.document;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.AIIndexingProperties;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIEntityIndexingPolicy;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.document.model.DocumentIngestionChunk;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;
import ai.fabric.indexing.document.model.DocumentIngestionManifest;
import ai.fabric.indexing.document.model.DocumentIngestionPlan;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingOptions;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.queue.IndexingQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentIndexingQueueAdapterTest {

    private static final LocalDateTime SCHEDULED_FOR =
        LocalDateTime.of(2026, 7, 24, 9, 30);

    private IndexingQueueService queueService;
    private DocumentIndexingQueueAdapter queueAdapter;
    private SpringAiDocumentIndexingAdapter planningAdapter;

    @BeforeEach
    void setUp() {
        queueService = mock(IndexingQueueService.class);
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        when(loader.getEntityConfig("kb")).thenReturn(AIEntityConfig.builder()
            .entityType("kb")
            .indexing(AIEntityIndexingPolicy.builder().enabled(true).build())
            .build());
        DocumentChunkIdentity identity = new DocumentChunkIdentity();
        DocumentEntityPolicyValidator policy = new DocumentEntityPolicyValidator(loader);
        DocumentManifestOperations manifests = new DocumentManifestOperations(identity, policy);
        planningAdapter = new SpringAiDocumentIndexingAdapter(
            new AIIndexingProperties.DocumentProperties(),
            identity,
            new DocumentMetadataNormalizer(),
            policy,
            manifests
        );
        queueAdapter = new DocumentIndexingQueueAdapter(
            queueService,
            identity,
            policy,
            manifests
        );
    }

    @Test
    void submitsEveryCanonicalChunkAndReturnsExistingQueueEntries() {
        DocumentIngestionPlan plan = plan("First", "Second");
        IndexingQueueEntry first = entry(11);
        IndexingQueueEntry second = entry(12);
        when(queueService.enqueue(
            any(AIIndexDocument.class),
            eq(IndexingStrategy.ASYNC),
            eq(SCHEDULED_FOR)
        )).thenReturn(first, second);

        List<IndexingQueueEntry> accepted = queueAdapter.submit(
            plan,
            IndexingStrategy.ASYNC,
            SCHEDULED_FOR
        );

        assertThat(accepted).containsExactly(first, second);
        ArgumentCaptor<AIIndexDocument> documents =
            ArgumentCaptor.forClass(AIIndexDocument.class);
        verify(queueService, org.mockito.Mockito.times(2)).enqueue(
            documents.capture(),
            eq(IndexingStrategy.ASYNC),
            eq(SCHEDULED_FOR)
        );
        assertThat(documents.getAllValues())
            .extracting(AIIndexDocument::entityId)
            .containsExactlyElementsOf(
                plan.chunks().stream().map(chunk -> chunk.entityId()).toList()
            );
    }

    @Test
    void exposesPreviouslyAcceptedWorkIdsWhenLaterSubmissionFails() {
        IndexingQueueEntry accepted = entry(41);
        when(queueService.enqueue(
            any(AIIndexDocument.class),
            eq(IndexingStrategy.BATCH),
            any(LocalDateTime.class)
        ))
            .thenReturn(accepted)
            .thenThrow(new IllegalStateException("queue unavailable"));

        assertThatThrownBy(() -> queueAdapter.submit(
            plan("First", "Second"),
            IndexingStrategy.BATCH,
            SCHEDULED_FOR
        ))
            .isInstanceOfSatisfying(
                DocumentQueueSubmissionException.class,
                exception -> {
                    assertThat(exception.getCode()).isEqualTo(
                        DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED
                    );
                    assertThat(exception.getAcceptedWorkIds())
                        .containsExactly("41");
                    assertThat(exception.getMessage())
                        .doesNotContain("queue unavailable");
                }
            );
    }

    @Test
    void rejectsNullQueueAcceptanceWithoutReportingSuccess() {
        when(queueService.enqueue(
            any(AIIndexDocument.class),
            eq(IndexingStrategy.ASYNC),
            eq(SCHEDULED_FOR)
        )).thenReturn(null);

        assertThatThrownBy(() -> queueAdapter.submit(
            plan("First"),
            IndexingStrategy.ASYNC,
            SCHEDULED_FOR
        ))
            .isInstanceOfSatisfying(
                DocumentQueueSubmissionException.class,
                exception -> {
                    assertThat(exception.getCode()).isEqualTo(
                        DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED
                    );
                    assertThat(exception.getAcceptedWorkIds()).isEmpty();
                    assertThat(exception.getMessage())
                        .doesNotContain("incomplete acceptance evidence");
                }
            );
    }

    @Test
    void submitsPayloadFreeExactDeletesFromManifest() {
        DocumentIngestionPlan plan = plan("First", "Second");
        DocumentIngestionManifest manifest = planningAdapter.manifest(plan);
        IndexingQueueEntry first = entry(51);
        IndexingQueueEntry second = entry(52);
        when(queueService.enqueue(
            any(AIIndexDocument.class),
            eq(IndexingStrategy.ASYNC),
            eq(SCHEDULED_FOR)
        )).thenReturn(first, second);

        queueAdapter.submitDeletes(
            manifest,
            IndexingStrategy.ASYNC,
            SCHEDULED_FOR,
            Instant.parse("2026-07-24T12:00:00Z")
        );

        ArgumentCaptor<AIIndexDocument> documents =
            ArgumentCaptor.forClass(AIIndexDocument.class);
        verify(queueService, org.mockito.Mockito.times(2)).enqueue(
            documents.capture(),
            eq(IndexingStrategy.ASYNC),
            eq(SCHEDULED_FOR)
        );
        assertThat(documents.getAllValues())
            .allSatisfy(document -> {
                assertThat(document.workType()).isEqualTo(AIIndexWorkType.DELETE);
                assertThat(document.sourceOperation())
                    .isEqualTo(AIProcessOperation.DELETE);
                assertThat(document.semanticSearchText()).isNull();
                assertThat(document.vectorMetadata()).isEmpty();
            })
            .extracting(AIIndexDocument::entityId)
            .containsExactlyElementsOf(
                manifest.chunks().stream().map(chunk -> chunk.entityId()).toList()
            );
    }

    @Test
    void rejectsAutoStrategyBeforeQueueInteraction() {
        assertThatThrownBy(() -> queueAdapter.submit(
            plan("content"),
            IndexingStrategy.AUTO,
            SCHEDULED_FOR
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("concrete");

        verifyNoInteractions(queueService);
    }

    @Test
    void rejectsPlanWhoseCanonicalContentNoLongerMatchesItsIdentity() {
        DocumentIngestionPlan original = plan("First");
        DocumentIngestionChunk originalChunk = original.chunks().getFirst();
        AIIndexDocument originalDocument = originalChunk.indexDocument();
        AIIndexDocument changedDocument = new AIIndexDocument(
            originalDocument.schemaVersion(),
            originalDocument.descriptorHash(),
            originalDocument.entityType(),
            originalDocument.entityId(),
            originalDocument.workType(),
            originalDocument.sourceOperation(),
            "Fraud",
            "Fraud",
            originalDocument.vectorMetadata(),
            originalDocument.llmContext(),
            originalDocument.responseMetadata(),
            originalDocument.sourceVersion(),
            originalDocument.correlationId(),
            originalDocument.occurredAt()
        );
        DocumentIngestionChunk changedChunk = new DocumentIngestionChunk(
            originalChunk.documentOrdinal(),
            originalChunk.sourceDocumentId(),
            originalChunk.chunkId(),
            originalChunk.chunkIndex(),
            originalChunk.chunkCount(),
            originalChunk.entityId(),
            changedDocument.semanticSearchText().length(),
            originalChunk.contentFingerprint(),
            changedDocument,
            originalChunk.warnings()
        );
        DocumentIngestionPlan changedPlan = new DocumentIngestionPlan(
            original.planId(),
            original.sourceId(),
            original.sourceVersion(),
            original.sourceName(),
            original.entityType(),
            original.tenantId(),
            original.visibility(),
            original.createdAt(),
            original.documentCount(),
            changedChunk.contentLength(),
            List.of(changedChunk),
            original.warnings()
        );

        assertThatThrownBy(() -> queueAdapter.submit(
            changedPlan,
            IndexingStrategy.ASYNC,
            SCHEDULED_FOR
        ))
            .isInstanceOfSatisfying(
                DocumentQueueSubmissionException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(
                    DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED
                )
            );

        verifyNoInteractions(queueService);
    }

    private DocumentIngestionPlan plan(String... content) {
        List<Document> documents = java.util.stream.IntStream
            .range(0, content.length)
            .mapToObj(index -> Document.builder()
                .id("document-" + index)
                .text(content[index])
                .build())
            .toList();
        return planningAdapter.plan(
            documents,
            SpringAiDocumentIndexingOptions.builder()
                .entityType("kb")
                .sourceId("handbook")
                .sourceVersion(2)
                .splitWithTokenTextSplitter(false)
                .occurredAt(Instant.parse("2026-07-24T09:30:00Z"))
                .build()
        );
    }

    private IndexingQueueEntry entry(long id) {
        IndexingQueueEntry entry = mock(IndexingQueueEntry.class);
        when(entry.getId()).thenReturn(id);
        return entry;
    }
}
