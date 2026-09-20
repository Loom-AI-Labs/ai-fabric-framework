package ai.fabric.indexing.document;

import ai.fabric.indexing.document.model.DocumentIngestionChunk;
import ai.fabric.indexing.document.model.DocumentIngestionManifest;
import ai.fabric.indexing.document.model.DocumentIngestionPlan;
import ai.fabric.indexing.document.model.DocumentManifestChunk;
import ai.fabric.indexing.document.model.DocumentMetadataKeys;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.model.AIIndexDocument;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic provider-safe identities for document plans and chunks. */
public class DocumentChunkIdentity {

    private static final String CHUNK_PREFIX = "aidoc-";
    private static final String PLAN_PREFIX = "aiplan-";
    private static final String MANIFEST_PREFIX = "aimanifest-";
    private static final String PROJECTION_VERSION =
        "ai-fabric-document-projection-v2";

    public String contentFingerprint(String content) {
        return sha256(requireText(content, "content"));
    }

    public ChunkIdentity chunk(
        String entityType,
        String sourceId,
        long sourceVersion,
        int documentOrdinal,
        int chunkIndex,
        String contentFingerprint
    ) {
        if (sourceVersion < 0 || documentOrdinal < 0 || chunkIndex < 0) {
            throw new IllegalArgumentException(
                "sourceVersion, documentOrdinal, and chunkIndex must not be negative"
            );
        }
        String hash = sha256(encoded(
            requireText(entityType, "entityType"),
            requireText(sourceId, "sourceId"),
            Long.toString(sourceVersion),
            Integer.toString(documentOrdinal),
            Integer.toString(chunkIndex),
            requireText(contentFingerprint, "contentFingerprint")
        ));
        return new ChunkIdentity(
            hash.substring(0, 40),
            CHUNK_PREFIX + hash.substring(0, 48)
        );
    }

    public String planId(
        String entityType,
        String sourceId,
        long sourceVersion,
        List<DocumentIngestionChunk> chunks
    ) {
        StringBuilder input = new StringBuilder(encoded(
            requireText(entityType, "entityType"),
            requireText(sourceId, "sourceId"),
            Long.toString(sourceVersion)
        ));
        for (DocumentIngestionChunk chunk : List.copyOf(chunks)) {
            input.append(encoded(
                chunk.chunkId(),
                chunk.entityId(),
                chunk.contentFingerprint()
            ));
        }
        return PLAN_PREFIX + sha256(input.toString()).substring(0, 48);
    }

    public String manifestId(
        String planId,
        List<DocumentManifestChunk> chunks
    ) {
        StringBuilder input = new StringBuilder(encoded(
            requireText(planId, "planId")
        ));
        for (DocumentManifestChunk chunk : List.copyOf(chunks)) {
            input.append(encoded(chunk.entityId()));
        }
        return MANIFEST_PREFIX + sha256(input.toString()).substring(0, 48);
    }

    public void validate(DocumentIngestionPlan plan) {
        Objects.requireNonNull(plan, "plan is required");
        Set<String> chunkIds = new HashSet<>();
        Set<String> entityIds = new HashSet<>();
        for (int index = 0; index < plan.chunks().size(); index++) {
            DocumentIngestionChunk chunk = plan.chunks().get(index);
            AIIndexDocument document = chunk.indexDocument();
            String expectedFingerprint = contentFingerprint(
                document.semanticSearchText()
            );
            ChunkIdentity expectedIdentity = chunk(
                plan.entityType(),
                plan.sourceId(),
                plan.sourceVersion(),
                chunk.documentOrdinal(),
                chunk.chunkIndex(),
                expectedFingerprint
            );

            if (chunk.chunkIndex() != index
                || chunk.chunkCount() != plan.chunks().size()
                || !expectedFingerprint.equals(chunk.contentFingerprint())
                || !expectedIdentity.chunkId().equals(chunk.chunkId())
                || !expectedIdentity.entityId().equals(chunk.entityId())
                || !chunkIds.add(chunk.chunkId())
                || !entityIds.add(chunk.entityId())) {
                throw inconsistentPlan();
            }
            validateCanonicalDocument(plan, chunk, document);
        }

        String expected = planId(
            plan.entityType(),
            plan.sourceId(),
            plan.sourceVersion(),
            plan.chunks()
        );
        if (!expected.equals(plan.planId())) {
            throw inconsistentPlan();
        }
    }

    public void validate(DocumentIngestionManifest manifest) {
        String expected = manifestId(manifest.planId(), manifest.chunks());
        if (!expected.equals(manifest.manifestId())) {
            throw new IllegalArgumentException(
                "Document ingestion manifest identity is inconsistent"
            );
        }
    }

    public String projectionHash() {
        return sha256(PROJECTION_VERSION);
    }

    private void validateCanonicalDocument(
        DocumentIngestionPlan plan,
        DocumentIngestionChunk chunk,
        AIIndexDocument document
    ) {
        if (!projectionHash().equals(document.descriptorHash())
            || !plan.entityType().equals(document.entityType())
            || !chunk.entityId().equals(document.entityId())
            || document.workType() != AIIndexWorkType.UPSERT
            || (document.sourceOperation() != AIProcessOperation.CREATE
                && document.sourceOperation() != AIProcessOperation.UPDATE)
            || !Objects.equals(plan.sourceVersion(), document.sourceVersion())) {
            throw inconsistentPlan();
        }

        Map<String, Object> metadata = document.vectorMetadata();
        requireMetadata(metadata, DocumentMetadataKeys.SOURCE_ID, plan.sourceId());
        requireMetadata(
            metadata,
            DocumentMetadataKeys.SOURCE_VERSION,
            plan.sourceVersion()
        );
        requireMetadata(
            metadata,
            DocumentMetadataKeys.SOURCE_NAME,
            plan.sourceName()
        );
        requireMetadata(
            metadata,
            DocumentMetadataKeys.DOCUMENT_ID,
            chunk.sourceDocumentId()
        );
        requireMetadata(metadata, DocumentMetadataKeys.CHUNK_ID, chunk.chunkId());
        requireMetadata(
            metadata,
            DocumentMetadataKeys.CHUNK_INDEX,
            chunk.chunkIndex()
        );
        requireMetadata(
            metadata,
            DocumentMetadataKeys.CHUNK_COUNT,
            chunk.chunkCount()
        );
        requireMetadata(
            metadata,
            DocumentMetadataKeys.CONTENT_FINGERPRINT,
            chunk.contentFingerprint()
        );
        requireOptionalMetadata(
            metadata,
            DocumentMetadataKeys.TENANT_ID,
            plan.tenantId()
        );
        requireOptionalMetadata(
            metadata,
            DocumentMetadataKeys.VISIBILITY,
            plan.visibility()
        );
    }

    private void requireOptionalMetadata(
        Map<String, Object> metadata,
        String key,
        String expected
    ) {
        if (expected == null || expected.isEmpty()) {
            if (metadata.containsKey(key)) {
                throw inconsistentPlan();
            }
            return;
        }
        requireMetadata(metadata, key, expected);
    }

    private void requireMetadata(
        Map<String, Object> metadata,
        String key,
        Object expected
    ) {
        Object actual = metadata.get(key);
        if (!Objects.equals(actual, expected)) {
            throw inconsistentPlan();
        }
    }

    private IllegalArgumentException inconsistentPlan() {
        return new IllegalArgumentException(
            "Document ingestion plan identity is inconsistent"
        );
    }

    private String encoded(String... values) {
        StringBuilder encoded = new StringBuilder();
        for (String value : values) {
            encoded.append(value.length()).append(':').append(value).append('|');
        }
        return encoded.toString();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte current : hash) {
                hex.append(String.format("%02x", current));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public record ChunkIdentity(String chunkId, String entityId) {
    }
}
