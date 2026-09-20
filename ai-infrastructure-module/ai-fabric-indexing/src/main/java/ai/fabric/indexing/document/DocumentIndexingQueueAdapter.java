package ai.fabric.indexing.document;

import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;
import ai.fabric.indexing.document.model.DocumentIngestionManifest;
import ai.fabric.indexing.document.model.DocumentIngestionPlan;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.queue.IndexingQueueService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Submits prepared document work through the existing durable indexing queue. */
public class DocumentIndexingQueueAdapter {

    private final IndexingQueueService queueService;
    private final DocumentChunkIdentity identity;
    private final DocumentEntityPolicyValidator entityPolicy;
    private final DocumentManifestOperations manifestOperations;

    public DocumentIndexingQueueAdapter(
        IndexingQueueService queueService,
        DocumentChunkIdentity identity,
        DocumentEntityPolicyValidator entityPolicy,
        DocumentManifestOperations manifestOperations
    ) {
        this.queueService = Objects.requireNonNull(
            queueService,
            "queueService is required"
        );
        this.identity = Objects.requireNonNull(identity, "identity is required");
        this.entityPolicy = Objects.requireNonNull(
            entityPolicy,
            "entityPolicy is required"
        );
        this.manifestOperations = Objects.requireNonNull(
            manifestOperations,
            "manifestOperations is required"
        );
    }

    public List<IndexingQueueEntry> submit(
        DocumentIngestionPlan plan,
        IndexingStrategy strategy,
        LocalDateTime scheduledFor
    ) {
        Objects.requireNonNull(plan, "plan is required");
        requireConcrete(strategy);
        try {
            identity.validate(plan);
            entityPolicy.requireIndexable(plan.entityType(), plan.tenantId());
            for (var chunk : plan.chunks()) {
                if (!plan.entityType().equals(chunk.indexDocument().entityType())
                    || !Objects.equals(
                        plan.sourceVersion(),
                        chunk.indexDocument().sourceVersion()
                    )) {
                    throw new IllegalArgumentException(
                        "Document ingestion plan contains inconsistent chunk policy"
                    );
                }
            }
        } catch (RuntimeException exception) {
            throw new DocumentQueueSubmissionException(
                DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED,
                "Document ingestion plan failed queue validation",
                List.of(),
                exception
            );
        }
        return submitDocuments(
            plan.chunks().stream()
                .map(chunk -> chunk.indexDocument())
                .toList(),
            strategy,
            scheduledFor,
            DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED
        );
    }

    public List<IndexingQueueEntry> submitDeletes(
        DocumentIngestionManifest manifest,
        IndexingStrategy strategy,
        LocalDateTime scheduledFor,
        Instant occurredAt
    ) {
        requireConcrete(strategy);
        List<AIIndexDocument> documents = manifestOperations.deletionDocuments(
            manifest,
            occurredAt
        );
        return submitDocuments(
            documents,
            strategy,
            scheduledFor,
            DocumentIngestionFailureCode.DOCUMENT_DELETE_FAILED
        );
    }

    private List<IndexingQueueEntry> submitDocuments(
        List<AIIndexDocument> documents,
        IndexingStrategy strategy,
        LocalDateTime scheduledFor,
        DocumentIngestionFailureCode failureCode
    ) {
        List<IndexingQueueEntry> accepted = new ArrayList<>();
        try {
            for (AIIndexDocument document : documents) {
                IndexingQueueEntry entry = queueService.enqueue(
                    document,
                    strategy,
                    scheduledFor
                );
                if (entry == null || entry.getId() == null) {
                    throw new IllegalStateException(
                        "Document queue returned incomplete acceptance evidence"
                    );
                }
                accepted.add(entry);
            }
            return List.copyOf(accepted);
        } catch (RuntimeException exception) {
            throw new DocumentQueueSubmissionException(
                failureCode,
                "Document queue submission failed",
                accepted.stream()
                    .map(IndexingQueueEntry::getId)
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toList(),
                exception
            );
        }
    }

    private void requireConcrete(IndexingStrategy strategy) {
        if (strategy == null || strategy == IndexingStrategy.AUTO) {
            throw new IllegalArgumentException(
                "A concrete document indexing strategy is required"
            );
        }
    }
}
