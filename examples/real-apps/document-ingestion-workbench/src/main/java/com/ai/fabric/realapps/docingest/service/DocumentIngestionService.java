package com.ai.fabric.realapps.docingest.service;

import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingOptions;
import ai.fabric.indexing.document.springai.SpringAiDocumentReaderFactory;
import ai.fabric.indexing.document.springai.SpringAiTrustedResourcePolicy;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.queue.IndexingQueueService;
import com.ai.fabric.realapps.docingest.domain.DocumentChunkManifest;
import com.ai.fabric.realapps.docingest.domain.DocumentSource;
import com.ai.fabric.realapps.docingest.repo.DocumentChunkManifestRepository;
import com.ai.fabric.realapps.docingest.repo.DocumentSourceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.DocumentReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentIngestionService {

    private static final long MAX_BYTES = 1_000_000L;
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".txt", ".json");
    private final DocumentSourceRepository sourceRepository;
    private final DocumentChunkManifestRepository chunkManifestRepository;
    private final SpringAiDocumentReaderFactory readerFactory;
    private final SpringAiDocumentIndexingAdapter indexingAdapter;
    private final IndexingQueueService queueService;
    private final ObjectMapper objectMapper;
    private final Path trustedRoot;
    private final String entityType;

    public DocumentIngestionService(DocumentSourceRepository sourceRepository,
                                    DocumentChunkManifestRepository chunkManifestRepository,
                                    SpringAiDocumentReaderFactory readerFactory,
                                    SpringAiDocumentIndexingAdapter indexingAdapter,
                                    IndexingQueueService queueService,
                                    ObjectMapper objectMapper,
                                    @Value("${document-workbench.trusted-root:${java.io.tmpdir}/ai-fabric-document-workbench}") String trustedRoot,
                                    @Value("${document-workbench.entity-type:kb}") String entityType) {
        this.sourceRepository = sourceRepository;
        this.chunkManifestRepository = chunkManifestRepository;
        this.readerFactory = readerFactory;
        this.indexingAdapter = indexingAdapter;
        this.queueService = queueService;
        this.objectMapper = objectMapper;
        this.trustedRoot = Path.of(trustedRoot).toAbsolutePath().normalize();
        this.entityType = StringUtils.hasText(entityType) ? entityType.trim() : "kb";
    }

    @Transactional
    public SourceSummary createSource(CreateSourceCommand command) {
        StoredContent stored = storeNewSource(command);

        DocumentSource source = new DocumentSource();
        source.setId("doc-" + UUID.randomUUID().toString().replace("-", ""));
        source.setTitle(stored.title());
        source.setOriginalFilename(stored.originalFilename());
        source.setContentType(stored.contentType());
        source.setExtension(stored.extension());
        source.setTenantId(stored.tenantId());
        source.setVisibility(stored.visibility());
        source.setStoragePath(writeContent(source.getId(), stored.extension(), stored.content()).toString());
        source.setContentHash(sha256(stored.content()));
        source.setSourceVersion(1);
        source.setStatus(DocumentSource.Status.PENDING);
        source.setCreatedAt(Instant.now());
        source.setUpdatedAt(Instant.now());
        return toSummary(sourceRepository.save(source));
    }

    @Transactional
    public SourceSummary replaceSource(String sourceId, CreateSourceCommand command) {
        DocumentSource source = requireSource(sourceId);
        StoredContent stored = storeNewSource(command);

        source.setTitle(stored.title());
        source.setOriginalFilename(stored.originalFilename());
        source.setContentType(stored.contentType());
        source.setExtension(stored.extension());
        source.setTenantId(stored.tenantId());
        source.setVisibility(stored.visibility());
        source.setSourceVersion(source.getSourceVersion() + 1);
        source.setStoragePath(writeContent(source.getId(), stored.extension(), stored.content()).toString());
        source.setContentHash(sha256(stored.content()));
        source.setStatus(DocumentSource.Status.PENDING);
        source.setUpdatedAt(Instant.now());
        return toSummary(sourceRepository.save(source));
    }

    @Transactional(readOnly = true)
    public PreviewResult preview(String sourceId) {
        DocumentSource source = requireSource(sourceId);
        List<PlannedChunk> chunks = planChunks(source);
        return new PreviewResult(
            toSummary(source),
            chunks.size(),
            chunks.stream().limit(5).map(PlannedChunk::preview).toList(),
            chunks.stream()
                .map(PlannedChunk::preview)
                .mapToInt(ChunkPreview::metadataDroppedCount)
                .sum()
        );
    }

    @Transactional
    public IndexResult index(String sourceId) {
        DocumentSource source = requireSource(sourceId);
        List<PlannedChunk> chunks = planChunks(source);
        List<DocumentChunkManifest> oldChunks = chunkManifestRepository.findBySourceIdOrderByChunkIndexAsc(sourceId);

        for (DocumentChunkManifest oldChunk : oldChunks) {
            queueService.enqueue(
                deleteDocument(oldChunk),
                IndexingStrategy.ASYNC
            );
        }
        chunkManifestRepository.deleteBySourceId(sourceId);

        List<String> indexedEntityIds = new ArrayList<>();
        for (PlannedChunk chunk : chunks) {
            IndexingQueueEntry ignored = queueService.enqueue(
                chunk.document(),
                IndexingStrategy.ASYNC
            );
            indexedEntityIds.add(chunk.document().entityId());
            chunkManifestRepository.save(toManifest(source, chunk));
        }

        source.setStatus(DocumentSource.Status.INDEXED);
        source.setUpdatedAt(Instant.now());
        sourceRepository.save(source);

        return new IndexResult(
            toSummary(source),
            chunks.size(),
            oldChunks.size(),
            indexedEntityIds
        );
    }

    @Transactional
    public DeleteResult delete(String sourceId) {
        DocumentSource source = requireSource(sourceId);
        List<DocumentChunkManifest> chunks = chunkManifestRepository.findBySourceIdOrderByChunkIndexAsc(sourceId);
        List<String> deletedEntityIds = new ArrayList<>();

        for (DocumentChunkManifest chunk : chunks) {
            queueService.enqueue(
                deleteDocument(chunk),
                IndexingStrategy.ASYNC
            );
            deletedEntityIds.add(chunk.getEntityId());
        }
        chunkManifestRepository.deleteBySourceId(sourceId);

        source.setStatus(DocumentSource.Status.DELETED);
        source.setUpdatedAt(Instant.now());
        sourceRepository.save(source);

        return new DeleteResult(toSummary(source), deletedEntityIds.size(), deletedEntityIds);
    }

    private StoredContent storeNewSource(CreateSourceCommand command) {
        if (command == null || command.content() == null || command.content().length == 0) {
            throw new IllegalArgumentException("Document content is required");
        }
        if (command.content().length > MAX_BYTES) {
            throw new IllegalArgumentException("Document exceeds max supported size of " + MAX_BYTES + " bytes");
        }

        String originalFilename = safeFilename(command.originalFilename());
        String extension = extension(originalFilename);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported document type: " + extension);
        }
        String title = StringUtils.hasText(command.title()) ? command.title().trim() : originalFilename;
        String tenantId = StringUtils.hasText(command.tenantId()) ? command.tenantId().trim() : "default";
        String visibility = StringUtils.hasText(command.visibility()) ? command.visibility().trim() : "internal";
        String contentType = StringUtils.hasText(command.contentType()) ? command.contentType().trim() : "application/octet-stream";
        return new StoredContent(
            title,
            originalFilename,
            contentType,
            extension,
            tenantId,
            visibility,
            command.content()
        );
    }

    private Path writeContent(String sourceId, String extension, byte[] content) {
        try {
            Files.createDirectories(trustedRoot);
            Path destination = trustedRoot.resolve(sourceId + extension).normalize();
            if (!destination.startsWith(trustedRoot)) {
                throw new IllegalArgumentException("Document storage path escaped trusted root");
            }
            Files.write(destination, content);
            return destination;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to store trusted document", ex);
        }
    }

    private List<PlannedChunk> planChunks(DocumentSource source) {
        DocumentReader reader = readerFor(source);
        SpringAiDocumentIndexingOptions options = SpringAiDocumentIndexingOptions.builder()
            .entityType(entityType)
            .sourceId(source.getId())
            .sourceName(source.getTitle())
            .operation(AIProcessOperation.UPDATE)
            .strategy(IndexingStrategy.ASYNC)
            .metadata("sourceId", source.getId())
            .metadata("sourceName", source.getTitle())
            .metadata("sourceVersion", source.getSourceVersion())
            .metadata("tenantId", source.getTenantId())
            .metadata("visibility", source.getVisibility())
            .metadata("originalFilename", source.getOriginalFilename())
            .build();
        return indexingAdapter.toIndexDocuments(reader, options).stream()
            .map(document -> new PlannedChunk(document, toPreview(document)))
            .toList();
    }

    private DocumentReader readerFor(DocumentSource source) {
        FileSystemResource resource = new FileSystemResource(source.getStoragePath());
        SpringAiTrustedResourcePolicy policy = SpringAiTrustedResourcePolicy.trustedRoot(trustedRoot);
        if (".json".equals(source.getExtension())) {
            return readerFactory.jsonReader(resource, policy, "content", "text", "body");
        }
        return readerFactory.textReader(resource, policy);
    }

    private ChunkPreview toPreview(AIIndexDocument document) {
        Map<String, Object> metadata = document.vectorMetadata();
        return new ChunkPreview(
            document.entityId(),
            textValue(metadata.get("_springAiDocumentId")),
            intValue(metadata.get("_springAiChunkIndex")),
            intValue(metadata.get("_springAiChunkCount")),
            document.semanticSearchText(),
            textValue(metadata.get("_springAiContentFingerprint")),
            metadata,
            intValue(metadata.get("_springAiMetadataDroppedCount"))
        );
    }

    private DocumentChunkManifest toManifest(DocumentSource source, PlannedChunk chunk) {
        DocumentChunkManifest manifest = new DocumentChunkManifest();
        manifest.setSourceId(source.getId());
        manifest.setSourceVersion(source.getSourceVersion());
        manifest.setEntityType(chunk.document().entityType());
        manifest.setEntityId(chunk.document().entityId());
        manifest.setChunkIndex(chunk.preview().chunkIndex());
        manifest.setChunkCount(chunk.preview().chunkCount());
        manifest.setContentFingerprint(chunk.preview().contentFingerprint());
        manifest.setMetadataJson(writeMetadata(chunk.document().vectorMetadata()));
        manifest.setCreatedAt(Instant.now());
        return manifest;
    }

    private AIIndexDocument deleteDocument(DocumentChunkManifest chunk) {
        return indexingAdapter.toDeleteDocument(
            chunk.getEntityType(),
            chunk.getEntityId(),
            Instant.now()
        );
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof CharSequence sequence) {
            try {
                return Integer.parseInt(sequence.toString());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String textValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize chunk metadata", ex);
        }
    }

    private DocumentSource requireSource(String sourceId) {
        if (!StringUtils.hasText(sourceId)) {
            throw new IllegalArgumentException("sourceId is required");
        }
        return sourceRepository.findById(sourceId.trim())
            .orElseThrow(() -> new IllegalArgumentException("Unknown document source: " + sourceId));
    }

    private SourceSummary toSummary(DocumentSource source) {
        long chunkCount = chunkManifestRepository.countBySourceId(source.getId());
        return new SourceSummary(
            source.getId(),
            source.getTitle(),
            source.getOriginalFilename(),
            source.getTenantId(),
            source.getVisibility(),
            source.getContentHash(),
            source.getSourceVersion(),
            source.getStatus(),
            chunkCount
        );
    }

    private String safeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            throw new IllegalArgumentException("originalFilename is required");
        }
        String normalized = Path.of(filename).getFileName().toString().trim();
        if (!StringUtils.hasText(normalized) || ".".equals(normalized) || "..".equals(normalized)) {
            throw new IllegalArgumentException("originalFilename is invalid");
        }
        return normalized;
    }

    private String extension(String filename) {
        int index = filename.lastIndexOf('.');
        return index >= 0 ? filename.substring(index).toLowerCase(Locale.ROOT) : "";
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record CreateSourceCommand(
        String title,
        String originalFilename,
        String contentType,
        String tenantId,
        String visibility,
        byte[] content
    ) {
        public static CreateSourceCommand text(String title, String filename, String content) {
            return new CreateSourceCommand(
                title,
                filename,
                "text/plain",
                "default",
                "internal",
                content == null ? null : content.getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    public record SourceSummary(
        String id,
        String title,
        String originalFilename,
        String tenantId,
        String visibility,
        String contentHash,
        int sourceVersion,
        DocumentSource.Status status,
        long indexedChunks
    ) {}

    public record ChunkPreview(
        String entityId,
        String documentId,
        int chunkIndex,
        int chunkCount,
        String content,
        String contentFingerprint,
        Map<String, Object> metadata,
        int metadataDroppedCount
    ) {}

    public record PreviewResult(
        SourceSummary source,
        int chunkCount,
        List<ChunkPreview> previewChunks,
        int metadataDroppedCount
    ) {}

    public record IndexResult(
        SourceSummary source,
        int indexedChunks,
        int replacedChunks,
        List<String> indexedEntityIds
    ) {}

    public record DeleteResult(
        SourceSummary source,
        int deletedChunks,
        List<String> deletedEntityIds
    ) {}

    private record StoredContent(
        String title,
        String originalFilename,
        String contentType,
        String extension,
        String tenantId,
        String visibility,
        byte[] content
    ) {}

    private record PlannedChunk(
        AIIndexDocument document,
        ChunkPreview preview
    ) {}
}
