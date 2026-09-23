package com.ai.fabric.realapps.docingest.service;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.AIIndexingProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIEntityIndexingPolicy;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.api.IndexingWorkQuery;
import ai.fabric.indexing.api.IndexingWorkState;
import ai.fabric.indexing.api.IndexingWorkStatus;
import ai.fabric.indexing.document.DocumentChunkIdentity;
import ai.fabric.indexing.document.DocumentEntityPolicyValidator;
import ai.fabric.indexing.document.DocumentIndexingQueueAdapter;
import ai.fabric.indexing.document.DocumentManifestOperations;
import ai.fabric.indexing.document.DocumentMetadataNormalizer;
import ai.fabric.indexing.document.model.DocumentIngestionException;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;
import ai.fabric.indexing.document.model.DocumentMetadataKeys;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentReaderFactory;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.queue.IndexingQueueService;
import com.ai.fabric.realapps.docingest.domain.DocumentChunkManifest;
import com.ai.fabric.realapps.docingest.domain.DocumentSource;
import com.ai.fabric.realapps.docingest.repo.DocumentChunkManifestRepository;
import com.ai.fabric.realapps.docingest.repo.DocumentSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest
@EntityScan(basePackageClasses = DocumentSource.class)
@EnableJpaRepositories(basePackageClasses = DocumentSourceRepository.class)
class DocumentIngestionServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-20T12:00:00Z"),
        ZoneOffset.UTC
    );

    @TempDir
    Path trustedRoot;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IndexingQueueService queueService = mock(IndexingQueueService.class);
    private final AIEntityConfigurationLoader configurationLoader =
        mock(AIEntityConfigurationLoader.class);
    private final IndexingWorkQuery workQuery = mock(IndexingWorkQuery.class);
    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final List<AIIndexDocument> queuedDocuments = new ArrayList<>();
    private final Map<String, IndexingWorkStatus> workStatuses =
        new LinkedHashMap<>();
    private final AtomicLong workSequence = new AtomicLong();

    @jakarta.annotation.Resource
    private DocumentSourceRepository sourceRepository;

    @jakarta.annotation.Resource
    private DocumentChunkManifestRepository manifestRepository;

    private DocumentIngestionService service;

    @BeforeEach
    void setUp() {
        when(configurationLoader.getEntityConfig("kb")).thenReturn(
            AIEntityConfig.builder()
                .entityType("kb")
                .indexing(AIEntityIndexingPolicy.builder().enabled(true).build())
                .metadataFields(List.of(AIMetadataField.builder()
                    .name(DocumentMetadataKeys.TENANT_ID)
                    .required(true)
                    .build()))
                .build()
        );
        when(queueService.enqueue(
            any(AIIndexDocument.class),
            any(IndexingStrategy.class),
            any(LocalDateTime.class)
        )).thenAnswer(invocation -> accept(
            invocation.getArgument(0),
            invocation.getArgument(1)
        ));
        when(workQuery.findByWorkId(any(String.class))).thenAnswer(invocation ->
            Optional.ofNullable(workStatuses.get(invocation.getArgument(0)))
        );

        DocumentChunkIdentity identity = new DocumentChunkIdentity();
        DocumentEntityPolicyValidator policy =
            new DocumentEntityPolicyValidator(configurationLoader);
        DocumentManifestOperations manifests =
            new DocumentManifestOperations(identity, policy);
        SpringAiDocumentIndexingAdapter planningAdapter =
            new SpringAiDocumentIndexingAdapter(
                new AIIndexingProperties.DocumentProperties(),
                identity,
                new DocumentMetadataNormalizer(),
                policy,
                manifests
            );
        DocumentIndexingQueueAdapter queueAdapter =
            new DocumentIndexingQueueAdapter(
                queueService,
                identity,
                policy,
                manifests
            );
        service = new DocumentIngestionService(
            sourceRepository,
            manifestRepository,
            new SpringAiDocumentReaderFactory(),
            planningAdapter,
            queueAdapter,
            workQuery,
            aiCoreService,
            objectMapper,
            CLOCK,
            trustedRoot.toString(),
            "kb",
            80,
            10
        );
    }

    @Test
    void previewIsBoundedSafeAndDoesNotQueueWork() {
        String content = "Reset credentials and notify the account owner. "
            + "Then verify the audit trail. ".repeat(8);
        var source = service.createSource(textCommand(
            "Runbook",
            "runbook.txt",
            content,
            "tenant-a"
        ));

        var preview = service.preview(source.id());

        assertThat(preview.planId()).startsWith("aiplan-");
        assertThat(preview.chunkCount()).isPositive();
        assertThat(preview.previewChunks()).isNotEmpty();
        assertThat(preview.previewChunks().getFirst().contentPreview())
            .hasSizeLessThanOrEqualTo(80);
        assertThat(preview.previewChunks().getFirst().safeMetadata())
            .containsEntry(DocumentMetadataKeys.SOURCE_ID, source.id())
            .containsEntry(DocumentMetadataKeys.TENANT_ID, "tenant-a")
            .containsEntry(DocumentMetadataKeys.SOURCE_VERSION, 1L)
            .doesNotContainKeys("path", "sourceUrl", "embedding");
        assertThat(preview.toString()).doesNotContain(trustedRoot.toString());
        verifyNoInteractions(queueService);
    }

    @Test
    void listsOnlySourcesOwnedByTheRequestedTenant() {
        var tenantA = service.createSource(textCommand(
            "Tenant A runbook",
            "tenant-a.txt",
            "Tenant A recovery guidance",
            "tenant-a"
        ));
        service.createSource(textCommand(
            "Tenant B runbook",
            "tenant-b.txt",
            "Tenant B recovery guidance",
            "tenant-b"
        ));

        var result = service.listSources("tenant-a");

        assertThat(result).extracting(DocumentIngestionService.SourceSummary::id)
            .containsExactly(tenantA.id());
        assertThat(result).allSatisfy(source ->
            assertThat(source.tenantId()).isEqualTo("tenant-a")
        );
    }

    @Test
    void jsonSourceUsesTheSamePlanAndQueueLifecycle() {
        var source = service.createSource(new DocumentIngestionService.CreateSourceCommand(
            "Refund policy",
            "refund-policy.json",
            "application/json",
            "tenant-a",
            "internal",
            """
                {"title":"Refunds","content":"Refunds are available within thirty days."}
                """.getBytes(StandardCharsets.UTF_8)
        ));

        var preview = service.preview(source.id());
        var submitted = service.index(source.id());
        complete(submitted.workIds());
        var lifecycle = service.status(source.id());

        assertThat(preview.documentCount()).isPositive();
        assertThat(preview.previewChunks())
            .anySatisfy(chunk -> assertThat(chunk.contentPreview())
                .contains("Refunds are available within thirty days"));
        assertThat(lifecycle.source().status())
            .isEqualTo(DocumentSource.Status.INDEXED);
        assertThat(lifecycle.source().activeVersion()).isEqualTo(1L);
    }

    @Test
    void indexingBecomesActiveOnlyAfterEveryQueueItemCompletes() {
        var source = create("Version one account reset guidance", "tenant-a");

        var submitted = service.index(source.id());

        assertThat(submitted.source().status())
            .isEqualTo(DocumentSource.Status.INDEXING);
        assertThat(submitted.source().activeVersion()).isNull();
        assertThat(submitted.workIds()).isNotEmpty();
        ArgumentCaptor<LocalDateTime> scheduledFor =
            ArgumentCaptor.forClass(LocalDateTime.class);
        verify(queueService, atLeastOnce()).enqueue(
            any(AIIndexDocument.class),
            eq(IndexingStrategy.ASYNC),
            scheduledFor.capture()
        );
        assertThat(scheduledFor.getAllValues())
            .containsOnly(LocalDateTime.ofInstant(CLOCK.instant(), CLOCK.getZone()));
        assertThat(manifestRows(source.id(), 1))
            .extracting(DocumentChunkManifest::getState)
            .containsOnly(DocumentChunkManifest.LifecycleState.INDEXING);

        complete(submitted.workIds());
        var lifecycle = service.status(source.id());

        assertThat(lifecycle.source().status())
            .isEqualTo(DocumentSource.Status.INDEXED);
        assertThat(lifecycle.source().activeVersion()).isEqualTo(1L);
        assertThat(lifecycle.source().activeChunks()).isPositive();
        assertThat(lifecycle.manifests()).singleElement()
            .satisfies(run -> assertThat(run.state()).isEqualTo("ACTIVE"));
    }

    @Test
    void successfulReplacementActivatesNewVersionBeforeDeletingOldIds() {
        var source = create("Version one account reset guidance", "tenant-a");
        var first = service.index(source.id());
        complete(first.workIds());
        service.status(source.id());
        List<String> oldIds = first.entityIds();
        queuedDocuments.clear();

        service.replaceSource(
            source.id(),
            textCommand(
                "Runbook v2",
                "runbook.txt",
                "Version two requires owner notification and an audit task",
                "tenant-a"
            )
        );
        var replacement = service.index(source.id());

        assertThat(replacement.source().status())
            .isEqualTo(DocumentSource.Status.REPLACING);
        assertThat(replacement.source().activeVersion()).isEqualTo(1L);
        assertThat(queuedDocuments)
            .allSatisfy(document -> assertThat(document.workType())
                .isEqualTo(AIIndexWorkType.UPSERT));

        complete(replacement.workIds());
        var activated = service.status(source.id());

        assertThat(activated.source().activeVersion()).isEqualTo(2L);
        assertThat(queuedDocuments.stream()
            .filter(document -> document.workType() == AIIndexWorkType.DELETE)
            .map(AIIndexDocument::entityId))
            .containsExactlyElementsOf(oldIds);
        List<String> retirementWork = manifestRows(source.id(), 1).stream()
            .map(DocumentChunkManifest::getDeleteWorkId)
            .toList();
        complete(retirementWork);
        var retired = service.status(source.id());
        assertThat(retired.manifests())
            .anySatisfy(run -> {
                assertThat(run.sourceVersion()).isEqualTo(1L);
                assertThat(run.state()).isEqualTo("DELETED");
            })
            .anySatisfy(run -> {
                assertThat(run.sourceVersion()).isEqualTo(2L);
                assertThat(run.state()).isEqualTo("ACTIVE");
            });
    }

    @Test
    void failedReplacementPreservesOldActiveManifestAndQueuesCandidateCleanup() {
        var source = create("Stable version one guidance", "tenant-a");
        var first = service.index(source.id());
        complete(first.workIds());
        service.status(source.id());

        service.replaceSource(
            source.id(),
            textCommand(
                "Runbook v2",
                "runbook.txt",
                "Candidate version two guidance",
                "tenant-a"
            )
        );
        var candidate = service.index(source.id());
        fail(candidate.workIds().getFirst(), "EMBEDDING_PROVIDER_FAILED");
        candidate.workIds().stream().skip(1).forEach(this::complete);

        var lifecycle = service.status(source.id());

        assertThat(lifecycle.source().status())
            .isEqualTo(DocumentSource.Status.FAILED);
        assertThat(lifecycle.source().activeVersion()).isEqualTo(1L);
        assertThat(manifestRows(source.id(), 1))
            .extracting(DocumentChunkManifest::getState)
            .containsOnly(DocumentChunkManifest.LifecycleState.ACTIVE);
        assertThat(manifestRows(source.id(), 2))
            .extracting(DocumentChunkManifest::getState)
            .containsOnly(DocumentChunkManifest.LifecycleState.FAILED);
        assertThat(queuedDocuments.stream()
            .filter(document -> document.workType() == AIIndexWorkType.DELETE)
            .map(AIIndexDocument::entityId))
            .containsExactlyElementsOf(candidate.entityIds());
    }

    @Test
    void deleteWaitsForExactQueueOutcomesBeforeMarkingSourceDeleted() {
        var source = create("Delete this indexed guidance", "tenant-a");
        var indexed = service.index(source.id());
        complete(indexed.workIds());
        service.status(source.id());
        queuedDocuments.clear();

        var deletion = service.delete(source.id());

        assertThat(deletion.source().status())
            .isEqualTo(DocumentSource.Status.DELETING);
        assertThat(deletion.entityIds()).containsExactlyElementsOf(indexed.entityIds());
        assertThat(queuedDocuments)
            .allSatisfy(document -> {
                assertThat(document.workType()).isEqualTo(AIIndexWorkType.DELETE);
                assertThat(document.semanticSearchText()).isNull();
                assertThat(document.vectorMetadata()).isEmpty();
            });

        complete(deletion.workIds());
        var deleted = service.status(source.id());
        assertThat(deleted.source().status())
            .isEqualTo(DocumentSource.Status.DELETED);
        assertThat(deleted.source().activeVersion()).isNull();
        assertThat(deleted.source().activeChunks()).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deleteUsesCurrentVersionsAcrossRepositoryTransactionBoundaries() {
        try {
            var source = create("Detached lifecycle guidance", "tenant-a");
            var indexed = service.index(source.id());
            complete(indexed.workIds());
            service.status(source.id());

            var deletion = service.delete(source.id());

            assertThat(deletion.source().status())
                .isEqualTo(DocumentSource.Status.DELETING);
            assertThat(deletion.workIds()).isNotEmpty();
        } finally {
            manifestRepository.deleteAll();
            sourceRepository.deleteAll();
        }
    }

    @Test
    void queryReturnsOnlyActiveTenantEvidenceWithSourceAndChunkIdentity() {
        var source = create("Passwords rotate every ninety days", "tenant-a");
        var indexed = service.index(source.id());
        complete(indexed.workIds());
        service.status(source.id());
        DocumentChunkManifest active = manifestRows(source.id(), 1).getFirst();
        when(aiCoreService.performSearch(any())).thenReturn(
            AISearchResponse.builder()
                .model("smoke-embedding")
                .processingTimeMs(3L)
                .results(List.of(
                    searchRow(active, "Passwords rotate every ninety days", "tenant-a"),
                    Map.of(
                        "id", "other-tenant-id",
                        "content", "Must not leak",
                        "score", 1.0d,
                        "metadata", Map.of(
                            DocumentMetadataKeys.TENANT_ID, "tenant-b",
                            DocumentMetadataKeys.SOURCE_ID, "other-source"
                        )
                    )
                ))
                .build()
        );

        var result = service.query("password rotation", "tenant-a", 5);

        assertThat(result.resultCount()).isEqualTo(1);
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.sourceId()).isEqualTo(source.id());
            assertThat(evidence.sourceVersion()).isEqualTo(1L);
            assertThat(evidence.chunkId()).isEqualTo(active.getChunkId());
            assertThat(evidence.entityId()).isEqualTo(active.getEntityId());
        });
    }

    @Test
    void unsupportedDocumentTypesAndMissingTenantsFailClosed() {
        var binary = new DocumentIngestionService.CreateSourceCommand(
            "Binary",
            "tool.exe",
            "application/octet-stream",
            "tenant-a",
            "internal",
            "not trusted text".getBytes(StandardCharsets.UTF_8)
        );
        var noTenant = textCommand("Runbook", "runbook.txt", "content", " ");

        assertThatThrownBy(() -> service.createSource(binary))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported document type");
        assertThatThrownBy(() -> service.createSource(noTenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenantId is required");
    }

    @Test
    void oversizedSourceFailsWithStableLimitCodeBeforePersistence() {
        var oversized = new DocumentIngestionService.CreateSourceCommand(
            "Too large",
            "large.txt",
            "text/plain",
            "tenant-a",
            "internal",
            new byte[1_000_001]
        );

        assertThatThrownBy(() -> service.createSource(oversized))
            .isInstanceOfSatisfying(
                DocumentIngestionException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(
                    DocumentIngestionFailureCode.DOCUMENT_LIMIT_EXCEEDED
                )
            );
        assertThat(sourceRepository.count()).isZero();
    }

    private DocumentIngestionService.SourceSummary create(
        String content,
        String tenantId
    ) {
        return service.createSource(textCommand(
            "Runbook",
            "runbook.txt",
            content,
            tenantId
        ));
    }

    private List<DocumentChunkManifest> manifestRows(String sourceId, long version) {
        return manifestRepository
            .findBySourceIdAndSourceVersionOrderByChunkIndexAsc(sourceId, version);
    }

    private IndexingQueueEntry accept(
        AIIndexDocument document,
        IndexingStrategy strategy
    ) {
        long id = workSequence.incrementAndGet();
        queuedDocuments.add(document);
        IndexingQueueEntry entry = new IndexingQueueEntry();
        ReflectionTestUtils.setField(entry, "id", id);
        workStatuses.put(String.valueOf(id), status(
            String.valueOf(id),
            document,
            strategy,
            IndexingWorkState.PENDING,
            null,
            null
        ));
        return entry;
    }

    private void complete(List<String> workIds) {
        workIds.forEach(this::complete);
    }

    private void complete(String workId) {
        IndexingWorkStatus current = workStatuses.get(workId);
        workStatuses.put(workId, status(
            workId,
            current,
            IndexingWorkState.COMPLETED,
            null,
            null
        ));
    }

    private void fail(String workId, String code) {
        IndexingWorkStatus current = workStatuses.get(workId);
        workStatuses.put(workId, status(
            workId,
            current,
            IndexingWorkState.DEAD_LETTER,
            code,
            "Provider failure"
        ));
    }

    private IndexingWorkStatus status(
        String workId,
        AIIndexDocument document,
        IndexingStrategy strategy,
        IndexingWorkState state,
        String errorCode,
        String reason
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new IndexingWorkStatus(
            workId,
            document.entityType(),
            document.entityId(),
            document.workType(),
            document.sourceOperation(),
            strategy,
            state,
            0,
            5,
            errorCode,
            reason,
            document.correlationId(),
            now,
            now,
            null,
            state.isTerminal() ? now : null,
            state.requiresOperatorReview() ? now : null,
            now
        );
    }

    private IndexingWorkStatus status(
        String workId,
        IndexingWorkStatus current,
        IndexingWorkState state,
        String errorCode,
        String reason
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new IndexingWorkStatus(
            workId,
            current.entityType(),
            current.entityId(),
            current.workType(),
            current.sourceOperation(),
            current.strategy(),
            state,
            current.retryCount(),
            current.maxRetries(),
            errorCode,
            reason,
            current.correlationId(),
            current.requestedAt(),
            current.scheduledFor(),
            current.startedAt(),
            state.isTerminal() ? now : null,
            state.requiresOperatorReview() ? now : null,
            now
        );
    }

    private Map<String, Object> searchRow(
        DocumentChunkManifest row,
        String content,
        String tenantId
    ) {
        return Map.of(
            "id", row.getEntityId(),
            "entityType", row.getEntityType(),
            "content", content,
            "score", 0.98d,
            "metadata", Map.of(
                DocumentMetadataKeys.SOURCE_ID, row.getSourceId(),
                DocumentMetadataKeys.SOURCE_VERSION, row.getSourceVersion(),
                DocumentMetadataKeys.SOURCE_NAME, row.getSourceName(),
                DocumentMetadataKeys.CHUNK_ID, row.getChunkId(),
                DocumentMetadataKeys.CHUNK_INDEX, row.getChunkIndex(),
                DocumentMetadataKeys.TENANT_ID, tenantId
            )
        );
    }

    private static DocumentIngestionService.CreateSourceCommand textCommand(
        String title,
        String filename,
        String content,
        String tenantId
    ) {
        return new DocumentIngestionService.CreateSourceCommand(
            title,
            filename,
            "text/plain",
            tenantId,
            "internal",
            content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
