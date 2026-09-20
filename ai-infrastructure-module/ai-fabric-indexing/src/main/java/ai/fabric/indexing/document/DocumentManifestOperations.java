package ai.fabric.indexing.document;

import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.document.model.DocumentIngestionException;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;
import ai.fabric.indexing.document.model.DocumentIngestionManifest;
import ai.fabric.indexing.document.model.DocumentIngestionPlan;
import ai.fabric.indexing.document.model.DocumentManifestChunk;
import ai.fabric.indexing.model.AIIndexDocument;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Creates content-free manifests and exact canonical delete work. */
public class DocumentManifestOperations {

    private final DocumentChunkIdentity identity;
    private final DocumentEntityPolicyValidator entityPolicy;

    public DocumentManifestOperations(
        DocumentChunkIdentity identity,
        DocumentEntityPolicyValidator entityPolicy
    ) {
        this.identity = Objects.requireNonNull(identity, "identity is required");
        this.entityPolicy = Objects.requireNonNull(
            entityPolicy,
            "entityPolicy is required"
        );
    }

    public DocumentIngestionManifest manifest(DocumentIngestionPlan plan) {
        Objects.requireNonNull(plan, "plan is required");
        identity.validate(plan);
        List<DocumentManifestChunk> chunks = plan.chunks().stream()
            .map(chunk -> new DocumentManifestChunk(
                chunk.sourceDocumentId(),
                chunk.chunkId(),
                chunk.chunkIndex(),
                chunk.entityId(),
                chunk.contentFingerprint()
            ))
            .toList();
        return new DocumentIngestionManifest(
            DocumentIngestionManifest.CURRENT_SCHEMA_VERSION,
            identity.manifestId(plan.planId(), chunks),
            plan.planId(),
            plan.sourceId(),
            plan.sourceVersion(),
            plan.sourceName(),
            plan.entityType(),
            plan.tenantId(),
            plan.visibility(),
            plan.createdAt(),
            chunks
        );
    }

    public List<AIIndexDocument> deletionDocuments(
        DocumentIngestionManifest manifest,
        Instant occurredAt
    ) {
        Objects.requireNonNull(manifest, "manifest is required");
        try {
            identity.validate(manifest);
            entityPolicy.requireIndexable(
                manifest.entityType(),
                manifest.tenantId()
            );
            Instant effectiveTime = occurredAt == null ? Instant.now() : occurredAt;
            return manifest.chunks().stream()
                .map(chunk -> deleteDocument(manifest, chunk, effectiveTime))
                .toList();
        } catch (DocumentIngestionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DocumentIngestionException(
                DocumentIngestionFailureCode.DOCUMENT_DELETE_FAILED,
                "Document manifest could not be converted to exact delete work",
                exception
            );
        }
    }

    private AIIndexDocument deleteDocument(
        DocumentIngestionManifest manifest,
        DocumentManifestChunk chunk,
        Instant occurredAt
    ) {
        return new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            identity.projectionHash(),
            manifest.entityType(),
            chunk.entityId(),
            AIIndexWorkType.DELETE,
            AIProcessOperation.DELETE,
            null,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            null,
            "",
            occurredAt
        );
    }
}
