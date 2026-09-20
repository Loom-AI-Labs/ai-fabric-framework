package ai.fabric.indexing.document.springai;

import ai.fabric.config.AIIndexingProperties;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.document.DocumentChunkIdentity;
import ai.fabric.indexing.document.DocumentEntityPolicyValidator;
import ai.fabric.indexing.document.DocumentManifestOperations;
import ai.fabric.indexing.document.DocumentMetadataNormalizer;
import ai.fabric.indexing.document.model.DocumentIngestionChunk;
import ai.fabric.indexing.document.model.DocumentIngestionException;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;
import ai.fabric.indexing.document.model.DocumentIngestionManifest;
import ai.fabric.indexing.document.model.DocumentIngestionPlan;
import ai.fabric.indexing.document.model.DocumentIngestionWarning;
import ai.fabric.indexing.document.model.DocumentIngestionWarningCode;
import ai.fabric.indexing.document.model.DocumentMetadataKeys;
import ai.fabric.indexing.model.AIIndexDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Prepares trusted Spring AI documents for the AI Fabric indexing lifecycle. */
public class SpringAiDocumentIndexingAdapter {

    private final AIIndexingProperties.DocumentProperties properties;
    private final DocumentChunkIdentity identity;
    private final DocumentMetadataNormalizer metadataNormalizer;
    private final DocumentEntityPolicyValidator entityPolicy;
    private final DocumentManifestOperations manifestOperations;

    public SpringAiDocumentIndexingAdapter(
        AIIndexingProperties.DocumentProperties properties,
        DocumentChunkIdentity identity,
        DocumentMetadataNormalizer metadataNormalizer,
        DocumentEntityPolicyValidator entityPolicy,
        DocumentManifestOperations manifestOperations
    ) {
        this.properties = Objects.requireNonNull(
            properties,
            "properties is required"
        );
        this.properties.validate();
        this.identity = Objects.requireNonNull(identity, "identity is required");
        this.metadataNormalizer = Objects.requireNonNull(
            metadataNormalizer,
            "metadataNormalizer is required"
        );
        this.entityPolicy = Objects.requireNonNull(
            entityPolicy,
            "entityPolicy is required"
        );
        this.manifestOperations = Objects.requireNonNull(
            manifestOperations,
            "manifestOperations is required"
        );
    }

    public DocumentIngestionPlan plan(
        DocumentReader reader,
        SpringAiDocumentIndexingOptions options
    ) {
        Objects.requireNonNull(reader, "reader is required");
        try {
            List<Document> documents = reader.read();
            return plan(documents == null ? List.of() : documents, options);
        } catch (DocumentIngestionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DocumentIngestionException(
                DocumentIngestionFailureCode.DOCUMENT_PARSE_FAILED,
                "The trusted document reader failed",
                exception
            );
        }
    }

    public DocumentIngestionPlan plan(
        List<Document> documents,
        SpringAiDocumentIndexingOptions options
    ) {
        SpringAiDocumentIndexingOptions resolved = Objects.requireNonNull(
            options,
            "options is required"
        );
        validateBounds(resolved);
        entityPolicy.requireIndexable(
            resolved.entityType(),
            resolved.tenantId()
        );

        List<DocumentIngestionWarning> planWarnings = new ArrayList<>();
        List<Document> input = normalizeInput(documents, resolved, planWarnings);
        List<Document> transformed = transform(input, resolved);
        List<Document> textDocuments = new ArrayList<>();
        for (Document document : transformed) {
            if (document == null
                || !document.isText()
                || document.getText() == null
                || document.getText().isBlank()) {
                planWarnings.add(DocumentIngestionWarning.one(
                    DocumentIngestionWarningCode.TRANSFORMER_DOCUMENT_SKIPPED_EMPTY,
                    "document"
                ));
                continue;
            }
            textDocuments.add(document);
        }
        if (textDocuments.isEmpty()) {
            throw new DocumentIngestionException(
                DocumentIngestionFailureCode.DOCUMENT_PARSE_FAILED,
                "Document preparation produced no indexable text"
            );
        }
        if (textDocuments.size() > resolved.maxChunks()) {
            throw limit("Document preparation exceeded the chunk limit");
        }

        List<DocumentIngestionChunk> chunks = new ArrayList<>();
        int totalContentLength = 0;
        int chunkCount = textDocuments.size();
        for (int index = 0; index < chunkCount; index++) {
            Document source = textDocuments.get(index);
            String content = source.getText().trim();
            if (content.length() > resolved.maxContentLength()) {
                throw limit("Document preparation exceeded the per-chunk content limit");
            }
            totalContentLength += content.length();
            if (totalContentLength > resolved.maxTotalContentLength()) {
                throw limit("Document preparation exceeded the total content limit");
            }
            DocumentIngestionChunk chunk = chunk(
                source,
                resolved,
                index,
                chunkCount,
                content
            );
            chunks.add(chunk);
            planWarnings.addAll(chunk.warnings());
        }

        String planId = identity.planId(
            resolved.entityType(),
            resolved.sourceId(),
            resolved.sourceVersion(),
            chunks
        );
        return new DocumentIngestionPlan(
            planId,
            resolved.sourceId(),
            resolved.sourceVersion(),
            resolved.sourceName(),
            resolved.entityType(),
            resolved.tenantId(),
            resolved.visibility(),
            resolved.occurredAt(),
            input.size(),
            totalContentLength,
            chunks,
            planWarnings
        );
    }

    public DocumentIngestionManifest manifest(DocumentIngestionPlan plan) {
        return manifestOperations.manifest(plan);
    }

    private List<Document> normalizeInput(
        List<Document> documents,
        SpringAiDocumentIndexingOptions options,
        List<DocumentIngestionWarning> warnings
    ) {
        List<Document> source = documents == null ? List.of() : documents;
        if (source.size() > options.maxDocuments()) {
            throw limit("Document preparation exceeded the document limit");
        }
        List<Document> result = new ArrayList<>();
        for (Document document : source) {
            if (document == null) {
                warnings.add(DocumentIngestionWarning.one(
                    DocumentIngestionWarningCode.PARSER_DOCUMENT_SKIPPED_EMPTY,
                    "document"
                ));
            } else {
                result.add(document);
            }
        }
        if (result.isEmpty()) {
            throw new DocumentIngestionException(
                DocumentIngestionFailureCode.DOCUMENT_PARSE_FAILED,
                "Document reader produced no documents"
            );
        }
        return List.copyOf(result);
    }

    private List<Document> transform(
        List<Document> documents,
        SpringAiDocumentIndexingOptions options
    ) {
        List<Document> current = documents;
        try {
            for (DocumentTransformer transformer : options.transformers()) {
                List<Document> transformed = transformer.transform(current);
                current = transformed == null ? List.of() : transformed;
            }
            if (properties.getDefaultSplitter().isEnabled()
                && options.splitWithTokenTextSplitter()) {
                TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(options.tokenChunkSize())
                    .withMinChunkSizeChars(
                        properties.getDefaultSplitter().getMinChunkSizeChars()
                    )
                    .withMinChunkLengthToEmbed(
                        properties.getDefaultSplitter().getMinChunkLengthToEmbed()
                    )
                    .withMaxNumChunks(options.maxChunks())
                    .build();
                current = splitter.split(current);
            }
            return current == null ? List.of() : current;
        } catch (RuntimeException exception) {
            throw new DocumentIngestionException(
                DocumentIngestionFailureCode.DOCUMENT_PARSE_FAILED,
                "Document transformation failed",
                exception
            );
        }
    }

    private DocumentIngestionChunk chunk(
        Document source,
        SpringAiDocumentIndexingOptions options,
        int chunkIndex,
        int chunkCount,
        String content
    ) {
        String fingerprint = identity.contentFingerprint(content);
        DocumentChunkIdentity.ChunkIdentity chunkIdentity = identity.chunk(
            options.entityType(),
            options.sourceId(),
            options.sourceVersion(),
            chunkIndex,
            chunkIndex,
            fingerprint
        );
        Set<String> allowedKeys = new LinkedHashSet<>(
            properties.getMetadata().getAllowedApplicationKeys()
        );
        allowedKeys.addAll(options.allowedMetadataKeys());
        int applicationMetadataLimit = Math.max(
            0,
            options.maxMetadataEntries() - DocumentMetadataKeys.ALL.size()
        );
        DocumentMetadataNormalizer.NormalizedMetadata normalized =
            metadataNormalizer.normalize(
                source.getMetadata(),
                options.metadata(),
                allowedKeys,
                applicationMetadataLimit,
                options.maxMetadataValueLength(),
                properties.getMetadata().isWarnOnDrop()
            );

        String sourceDocumentId = sourceDocumentId(source, chunkIndex);
        Map<String, Object> metadata = new LinkedHashMap<>(
            normalized.metadata()
        );
        metadata.put(DocumentMetadataKeys.SOURCE_ID, options.sourceId());
        metadata.put(DocumentMetadataKeys.SOURCE_VERSION, options.sourceVersion());
        metadata.put(DocumentMetadataKeys.SOURCE_NAME, options.sourceName());
        metadata.put(DocumentMetadataKeys.DOCUMENT_ID, sourceDocumentId);
        metadata.put(DocumentMetadataKeys.CHUNK_ID, chunkIdentity.chunkId());
        metadata.put(DocumentMetadataKeys.CHUNK_INDEX, chunkIndex);
        metadata.put(DocumentMetadataKeys.CHUNK_COUNT, chunkCount);
        metadata.put(DocumentMetadataKeys.CONTENT_FINGERPRINT, fingerprint);
        if (!options.tenantId().isEmpty()) {
            metadata.put(DocumentMetadataKeys.TENANT_ID, options.tenantId());
        }
        if (!options.visibility().isEmpty()) {
            metadata.put(DocumentMetadataKeys.VISIBILITY, options.visibility());
        }
        if (metadata.size() > options.maxMetadataEntries()) {
            throw limit("Document metadata exceeded the per-chunk entry limit");
        }

        AIIndexDocument indexDocument = new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            identity.projectionHash(),
            options.entityType(),
            chunkIdentity.entityId(),
            AIIndexWorkType.UPSERT,
            options.operation(),
            content,
            content,
            metadata,
            Map.of(),
            Map.of(),
            options.sourceVersion(),
            options.correlationId(),
            options.occurredAt()
        );
        return new DocumentIngestionChunk(
            chunkIndex,
            sourceDocumentId,
            chunkIdentity.chunkId(),
            chunkIndex,
            chunkCount,
            chunkIdentity.entityId(),
            content.length(),
            fingerprint,
            indexDocument,
            normalized.warnings()
        );
    }

    private String sourceDocumentId(Document document, int index) {
        String id = document.getId();
        if (id == null || id.isBlank() || looksLikeResourceLocation(id)) {
            return "document-" + index;
        }
        String normalized = id.trim();
        return normalized.length() <= 256
            ? normalized
            : normalized.substring(0, 256);
    }

    private boolean looksLikeResourceLocation(String value) {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("/")
            || normalized.contains("\\")
            || normalized.startsWith("file:")
            || normalized.startsWith("http:")
            || normalized.startsWith("https:")
            || normalized.startsWith("classpath:");
    }

    private void validateBounds(SpringAiDocumentIndexingOptions options) {
        AIIndexingProperties.DocumentProperties limits = properties;
        if (options.maxDocuments() > limits.getMaxDocumentsPerPlan()
            || options.maxChunks() > limits.getMaxChunksPerPlan()
            || options.maxContentLength()
                > limits.getMaxContentLengthPerChunk()
            || options.maxTotalContentLength()
                > limits.getMaxTotalContentLength()
            || options.maxMetadataEntries()
                > limits.getMaxMetadataEntriesPerChunk()
            || options.maxMetadataValueLength()
                > limits.getMaxMetadataValueLength()
            || options.tokenChunkSize()
                > limits.getDefaultSplitter().getChunkSize()) {
            throw limit(
                "Document preparation options exceed configured safety limits"
            );
        }
    }

    private DocumentIngestionException limit(String message) {
        return new DocumentIngestionException(
            DocumentIngestionFailureCode.DOCUMENT_LIMIT_EXCEEDED,
            message
        );
    }
}
