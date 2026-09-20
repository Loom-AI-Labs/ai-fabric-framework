package com.ai.fabric.realapps.docingest.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.api.IndexingWorkQuery;
import ai.fabric.indexing.api.IndexingWorkStatus;
import ai.fabric.indexing.document.DocumentIndexingQueueAdapter;
import ai.fabric.indexing.document.DocumentQueueSubmissionException;
import ai.fabric.indexing.document.model.DocumentIngestionChunk;
import ai.fabric.indexing.document.model.DocumentIngestionException;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;
import ai.fabric.indexing.document.model.DocumentIngestionManifest;
import ai.fabric.indexing.document.model.DocumentIngestionPlan;
import ai.fabric.indexing.document.model.DocumentIngestionWarning;
import ai.fabric.indexing.document.model.DocumentIngestionWarningCode;
import ai.fabric.indexing.document.model.DocumentManifestChunk;
import ai.fabric.indexing.document.model.DocumentMetadataKeys;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingOptions;
import ai.fabric.indexing.document.springai.SpringAiDocumentReaderFactory;
import ai.fabric.indexing.document.springai.SpringAiTrustedResourcePolicy;
import com.ai.fabric.realapps.docingest.domain.DocumentChunkManifest;
import com.ai.fabric.realapps.docingest.domain.DocumentSource;
import com.ai.fabric.realapps.docingest.repo.DocumentChunkManifestRepository;
import com.ai.fabric.realapps.docingest.repo.DocumentSourceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DocumentIngestionService {

    private static final long MAX_BYTES = 1_000_000L;
    private static final int MAX_QUERY_LENGTH = 1_000;
    private static final int MAX_QUERY_RESULTS = 20;
    private static final List<String> SUPPORTED_EXTENSIONS =
        List.of(".txt", ".json");
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
        new TypeReference<>() { };

    private final DocumentSourceRepository sourceRepository;
    private final DocumentChunkManifestRepository manifestRepository;
    private final SpringAiDocumentReaderFactory readerFactory;
    private final SpringAiDocumentIndexingAdapter planningAdapter;
    private final DocumentIndexingQueueAdapter queueAdapter;
    private final IndexingWorkQuery workQuery;
    private final AICoreService aiCoreService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path trustedRoot;
    private final String entityType;
    private final int previewCharacters;
    private final int previewChunks;

    public DocumentIngestionService(
        DocumentSourceRepository sourceRepository,
        DocumentChunkManifestRepository manifestRepository,
        SpringAiDocumentReaderFactory readerFactory,
        SpringAiDocumentIndexingAdapter planningAdapter,
        DocumentIndexingQueueAdapter queueAdapter,
        IndexingWorkQuery workQuery,
        AICoreService aiCoreService,
        ObjectMapper objectMapper,
        Clock clock,
        @Value("${document-workbench.trusted-root:${java.io.tmpdir}/ai-fabric-document-workbench}")
        String trustedRoot,
        @Value("${document-workbench.entity-type:kb}") String entityType,
        @Value("${document-workbench.preview.max-characters:400}")
        int previewCharacters,
        @Value("${document-workbench.preview.max-chunks:25}") int previewChunks
    ) {
        this.sourceRepository = sourceRepository;
        this.manifestRepository = manifestRepository;
        this.readerFactory = readerFactory;
        this.planningAdapter = planningAdapter;
        this.queueAdapter = queueAdapter;
        this.workQuery = workQuery;
        this.aiCoreService = aiCoreService;
        this.objectMapper = objectMapper;
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.trustedRoot = Path.of(trustedRoot).toAbsolutePath().normalize();
        this.entityType = StringUtils.hasText(entityType)
            ? entityType.trim()
            : "kb";
        this.previewCharacters = Math.max(50, Math.min(previewCharacters, 2_000));
        this.previewChunks = Math.max(1, Math.min(previewChunks, 100));
    }

    @Transactional
    public SourceSummary createSource(CreateSourceCommand command) {
        StoredContent stored = validateSource(command);
        DocumentSource source = new DocumentSource();
        source.setId("doc-" + UUID.randomUUID().toString().replace("-", ""));
        applyContent(source, stored, 1);
        source.setStatus(DocumentSource.Status.PENDING);
        source.setCreatedAt(instantNow());
        source.setUpdatedAt(instantNow());
        return toSummary(sourceRepository.save(source));
    }

    public synchronized SourceSummary replaceSource(
        String sourceId,
        CreateSourceCommand command
    ) {
        reconcile(sourceId);
        DocumentSource source = requireSource(sourceId);
        requireReplaceable(source);
        StoredContent stored = validateSource(command);
        applyContent(source, stored, source.getSourceVersion() + 1);
        source.setStatus(DocumentSource.Status.PENDING);
        clearFailure(source);
        source.setUpdatedAt(instantNow());
        return toSummary(sourceRepository.save(source));
    }

    @Transactional(readOnly = true)
    public PreviewResult preview(String sourceId) {
        DocumentSource source = requireSource(sourceId);
        DocumentIngestionPlan plan = plan(source);
        List<ChunkPreview> chunks = plan.chunks().stream()
            .limit(previewChunks)
            .map(this::toPreview)
            .toList();
        int droppedMetadata = (int) plan.warnings().stream()
            .filter(warning -> warning.code()
                == DocumentIngestionWarningCode.METADATA_KEY_DROPPED
                || warning.code()
                == DocumentIngestionWarningCode.METADATA_LIMIT_REACHED)
            .mapToLong(DocumentIngestionWarning::count)
            .sum();
        return new PreviewResult(
            plan.planId(),
            toSummary(source),
            plan.documentCount(),
            plan.chunks().size(),
            plan.totalContentLength(),
            plan.chunks().size() > chunks.size(),
            chunks,
            droppedMetadata,
            plan.warnings()
        );
    }

    public synchronized IndexResult index(String sourceId) {
        reconcile(sourceId);
        DocumentSource source = requireSource(sourceId);
        if (source.getStatus() == DocumentSource.Status.DELETED
            || source.getStatus() == DocumentSource.Status.DELETING) {
            throw new IllegalStateException("Deleted document sources cannot be indexed");
        }
        List<DocumentChunkManifest> existing = manifestRepository
            .findBySourceIdAndSourceVersionOrderByChunkIndexAsc(
                source.getId(),
                source.getSourceVersion()
            );
        if (!existing.isEmpty()) {
            throw new IllegalStateException(
                "This source version already has an ingestion run"
            );
        }

        DocumentIngestionPlan plan = plan(source);
        DocumentIngestionManifest manifest = planningAdapter.manifest(plan);
        List<DocumentChunkManifest> rows = persistDraft(manifest);
        int activeChunks = Math.toIntExact(manifestRepository.countBySourceIdAndState(
            sourceId,
            DocumentChunkManifest.LifecycleState.ACTIVE
        ));

        try {
            List<IndexingQueueEntry> accepted = queueAdapter.submit(
                plan,
                IndexingStrategy.ASYNC,
                localNow()
            );
            List<String> workIds = requireWorkIds(accepted, plan.chunks().size());
            for (int index = 0; index < rows.size(); index++) {
                rows.get(index).setIndexWorkId(workIds.get(index));
                rows.get(index).setState(
                    DocumentChunkManifest.LifecycleState.INDEXING
                );
                rows.get(index).setUpdatedAt(instantNow());
            }
            manifestRepository.saveAllAndFlush(rows);
            source.setStatus(source.getActiveVersion() == null
                ? DocumentSource.Status.INDEXING
                : DocumentSource.Status.REPLACING);
            clearFailure(source);
            source.setUpdatedAt(instantNow());
            sourceRepository.saveAndFlush(source);
            return new IndexResult(
                toSummary(source),
                manifest.manifestId(),
                rows.size(),
                activeChunks,
                workIds,
                rows.stream().map(DocumentChunkManifest::getEntityId).toList()
            );
        } catch (DocumentQueueSubmissionException exception) {
            applyAcceptedWorkIds(rows, exception.getAcceptedWorkIds());
            markFailed(rows, exception.getCode().name(), exception.getMessage());
            rows = manifestRepository.saveAllAndFlush(rows);
            markSourceFailure(source, exception.getCode().name(), exception.getMessage());
            sourceRepository.saveAndFlush(source);
            if (!exception.getAcceptedWorkIds().isEmpty()) {
                submitCleanup(manifest, rows);
            }
            throw exception;
        } catch (RuntimeException exception) {
            markFailed(
                rows,
                DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED.name(),
                "Document indexing submission failed"
            );
            manifestRepository.saveAllAndFlush(rows);
            markSourceFailure(
                source,
                DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED.name(),
                "Document indexing submission failed"
            );
            sourceRepository.saveAndFlush(source);
            throw exception;
        }
    }

    public synchronized LifecycleResult status(String sourceId) {
        reconcile(sourceId);
        DocumentSource source = requireSource(sourceId);
        List<ManifestRun> runs = groupedRuns(sourceId).values().stream()
            .map(this::toRun)
            .sorted(Comparator.comparingLong(ManifestRun::sourceVersion))
            .toList();
        return new LifecycleResult(toSummary(source), runs);
    }

    public synchronized DeleteResult delete(String sourceId) {
        reconcile(sourceId);
        DocumentSource source = requireSource(sourceId);
        if (source.getActiveVersion() == null) {
            source.setStatus(DocumentSource.Status.DELETED);
            clearFailure(source);
            source.setUpdatedAt(instantNow());
            sourceRepository.saveAndFlush(source);
            return new DeleteResult(toSummary(source), "", 0, List.of(), List.of());
        }

        List<DocumentChunkManifest> rows = manifestRepository
            .findBySourceIdAndSourceVersionOrderByChunkIndexAsc(
                sourceId,
                source.getActiveVersion()
            );
        if (rows.isEmpty()) {
            throw new IllegalStateException("Active document manifest is missing");
        }
        if (rows.stream().allMatch(row -> row.getState()
            == DocumentChunkManifest.LifecycleState.DELETING)) {
            return deleteResult(source, rows);
        }

        rows.forEach(row -> {
            row.setState(DocumentChunkManifest.LifecycleState.SUPERSEDED);
            row.setDeleteWorkId(null);
            row.setUpdatedAt(instantNow());
        });
        rows = manifestRepository.saveAllAndFlush(rows);
        source.setStatus(DocumentSource.Status.DELETING);
        clearFailure(source);
        source.setUpdatedAt(instantNow());
        sourceRepository.saveAndFlush(source);
        submitDeletes(rows, true);
        return deleteResult(requireSource(sourceId), rows);
    }

    @Transactional(readOnly = true)
    public QueryResult query(String query, String tenantId, int requestedLimit) {
        String safeQuery = requiredText(query, "query", MAX_QUERY_LENGTH);
        String safeTenant = requiredText(tenantId, "tenantId", 256);
        int limit = Math.max(1, Math.min(requestedLimit, MAX_QUERY_RESULTS));
        List<DocumentChunkManifest> active = manifestRepository
            .findByStateOrderBySourceIdAscSourceVersionAscChunkIndexAsc(
                DocumentChunkManifest.LifecycleState.ACTIVE
            ).stream()
            .filter(row -> safeTenant.equals(row.getTenantId()))
            .toList();
        Set<String> activeEntityIds = active.stream()
            .map(DocumentChunkManifest::getEntityId)
            .collect(Collectors.toUnmodifiableSet());
        if (activeEntityIds.isEmpty()) {
            return new QueryResult(safeQuery, safeTenant, 0, List.of(), 0L, "");
        }

        AISearchResponse response = aiCoreService.performSearch(
            AISearchRequest.builder()
                .query(safeQuery)
                .entityType(entityType)
                .limit(Math.min(100, Math.max(limit * 5, limit)))
                .threshold(0.0d)
                .metadata(Map.of(DocumentMetadataKeys.TENANT_ID, safeTenant))
                .build()
        );
        List<Evidence> evidence = response == null || response.getResults() == null
            ? List.of()
            : response.getResults().stream()
                .map(this::toEvidence)
                .filter(Objects::nonNull)
                .filter(item -> activeEntityIds.contains(item.entityId()))
                .filter(item -> safeTenant.equals(text(
                    item.metadata().get(DocumentMetadataKeys.TENANT_ID)
                )))
                .limit(limit)
                .toList();
        return new QueryResult(
            safeQuery,
            safeTenant,
            evidence.size(),
            evidence,
            response != null && response.getProcessingTimeMs() != null
                ? response.getProcessingTimeMs()
                : 0L,
            response != null && response.getModel() != null
                ? response.getModel()
                : ""
        );
    }

    private void reconcile(String sourceId) {
        DocumentSource source = requireSource(sourceId);
        source = processIndexingRuns(source);
        processSupersededRuns(source);
        source = requireSource(sourceId);
        processDeletingRuns(source);
        source.setUpdatedAt(instantNow());
        sourceRepository.saveAndFlush(source);
    }

    private DocumentSource processIndexingRuns(DocumentSource source) {
        Map<String, List<DocumentChunkManifest>> runs = group(
            manifestRepository.findBySourceIdAndStateOrderBySourceVersionAscChunkIndexAsc(
                source.getId(),
                DocumentChunkManifest.LifecycleState.INDEXING
            )
        );
        for (List<DocumentChunkManifest> rows : runs.values()) {
            WorkEvaluation evaluation = evaluate(rows, DocumentChunkManifest::getIndexWorkId);
            if (evaluation.failed()) {
                markFailed(rows, evaluation.errorCode(), evaluation.message());
                rows = manifestRepository.saveAllAndFlush(rows);
                markSourceFailure(source, evaluation.errorCode(), evaluation.message());
                submitCleanup(toManifest(rows), rows);
            } else if (evaluation.successful()) {
                source = activate(source, rows);
            }
        }
        return source;
    }

    private DocumentSource activate(
        DocumentSource source,
        List<DocumentChunkManifest> rows
    ) {
        long newVersion = rows.getFirst().getSourceVersion();
        List<DocumentChunkManifest> oldActive = manifestRepository
            .findBySourceIdAndStateOrderBySourceVersionAscChunkIndexAsc(
                source.getId(),
                DocumentChunkManifest.LifecycleState.ACTIVE
            ).stream()
            .filter(row -> row.getSourceVersion() != newVersion)
            .toList();
        rows.forEach(row -> {
            row.setState(DocumentChunkManifest.LifecycleState.ACTIVE);
            row.setFailureCode(null);
            row.setFailureMessage(null);
            row.setUpdatedAt(instantNow());
        });
        manifestRepository.saveAllAndFlush(rows);
        source.setActiveVersion(newVersion);
        source.setStatus(DocumentSource.Status.INDEXED);
        clearFailure(source);
        source = sourceRepository.saveAndFlush(source);

        if (!oldActive.isEmpty()) {
            oldActive.forEach(row -> {
                row.setState(DocumentChunkManifest.LifecycleState.SUPERSEDED);
                row.setUpdatedAt(instantNow());
            });
            oldActive = manifestRepository.saveAllAndFlush(oldActive);
            submitDeletes(oldActive, false);
        }
        return source;
    }

    private void processSupersededRuns(DocumentSource source) {
        Map<String, List<DocumentChunkManifest>> runs = group(
            manifestRepository.findBySourceIdAndStateOrderBySourceVersionAscChunkIndexAsc(
                source.getId(),
                DocumentChunkManifest.LifecycleState.SUPERSEDED
            )
        );
        runs.values().forEach(rows -> submitDeletes(
            rows,
            source.getStatus() == DocumentSource.Status.DELETING
                && Objects.equals(source.getActiveVersion(), rows.getFirst().getSourceVersion())
        ));
    }

    private void processDeletingRuns(DocumentSource source) {
        Map<String, List<DocumentChunkManifest>> runs = group(
            manifestRepository.findBySourceIdAndStateOrderBySourceVersionAscChunkIndexAsc(
                source.getId(),
                DocumentChunkManifest.LifecycleState.DELETING
            )
        );
        for (List<DocumentChunkManifest> rows : runs.values()) {
            WorkEvaluation evaluation = evaluate(rows, DocumentChunkManifest::getDeleteWorkId);
            boolean activeDeletion = source.getStatus()
                == DocumentSource.Status.DELETING
                && Objects.equals(
                    source.getActiveVersion(),
                    rows.getFirst().getSourceVersion()
                );
            if (evaluation.failed()) {
                markFailed(rows, evaluation.errorCode(), evaluation.message());
                manifestRepository.saveAllAndFlush(rows);
                if (activeDeletion) {
                    markSourceFailure(source, evaluation.errorCode(), evaluation.message());
                } else {
                    source.setLastFailureCode(evaluation.errorCode());
                    source.setLastFailureMessage(safeMessage(evaluation.message()));
                }
            } else if (evaluation.successful()) {
                rows.forEach(row -> {
                    row.setState(DocumentChunkManifest.LifecycleState.DELETED);
                    row.setFailureCode(null);
                    row.setFailureMessage(null);
                    row.setUpdatedAt(instantNow());
                });
                manifestRepository.saveAllAndFlush(rows);
                if (activeDeletion) {
                    source.setActiveVersion(null);
                    source.setStatus(DocumentSource.Status.DELETED);
                    clearFailure(source);
                }
            }
        }
    }

    private void submitDeletes(
        List<DocumentChunkManifest> rows,
        boolean activeDeletion
    ) {
        try {
            List<IndexingQueueEntry> accepted = queueAdapter.submitDeletes(
                toManifest(rows),
                IndexingStrategy.ASYNC,
                localNow(),
                instantNow()
            );
            List<String> workIds = requireWorkIds(accepted, rows.size());
            for (int index = 0; index < rows.size(); index++) {
                rows.get(index).setDeleteWorkId(workIds.get(index));
                rows.get(index).setState(
                    DocumentChunkManifest.LifecycleState.DELETING
                );
                rows.get(index).setUpdatedAt(instantNow());
            }
            manifestRepository.saveAllAndFlush(rows);
        } catch (DocumentQueueSubmissionException exception) {
            applyDeleteWorkIds(rows, exception.getAcceptedWorkIds());
            rows.forEach(row -> {
                row.setFailureCode(exception.getCode().name());
                row.setFailureMessage(safeMessage(exception.getMessage()));
                row.setUpdatedAt(instantNow());
            });
            manifestRepository.saveAllAndFlush(rows);
            if (activeDeletion) {
                DocumentSource source = requireSource(rows.getFirst().getSourceId());
                markSourceFailure(source, exception.getCode().name(), exception.getMessage());
                sourceRepository.saveAndFlush(source);
            }
        }
    }

    private void submitCleanup(
        DocumentIngestionManifest manifest,
        List<DocumentChunkManifest> rows
    ) {
        try {
            List<IndexingQueueEntry> accepted = queueAdapter.submitDeletes(
                manifest,
                IndexingStrategy.ASYNC,
                localNow(),
                instantNow()
            );
            applyDeleteWorkIds(rows, requireWorkIds(accepted, rows.size()));
            manifestRepository.saveAllAndFlush(rows);
        } catch (DocumentQueueSubmissionException exception) {
            applyDeleteWorkIds(rows, exception.getAcceptedWorkIds());
            rows.forEach(row -> {
                row.setFailureCode(exception.getCode().name());
                row.setFailureMessage(safeMessage(
                    "Indexing failed and cleanup submission was incomplete"
                ));
                row.setUpdatedAt(instantNow());
            });
            manifestRepository.saveAllAndFlush(rows);
        } catch (RuntimeException exception) {
            rows.forEach(row -> {
                row.setFailureCode(
                    DocumentIngestionFailureCode.DOCUMENT_DELETE_FAILED.name()
                );
                row.setFailureMessage(safeMessage(
                    "Indexing failed and cleanup submission failed"
                ));
                row.setUpdatedAt(instantNow());
            });
            manifestRepository.saveAllAndFlush(rows);
        }
    }

    private WorkEvaluation evaluate(
        List<DocumentChunkManifest> rows,
        Function<DocumentChunkManifest, String> workId
    ) {
        List<IndexingWorkStatus> statuses = new ArrayList<>();
        for (DocumentChunkManifest row : rows) {
            String id = workId.apply(row);
            if (!StringUtils.hasText(id)) {
                return WorkEvaluation.inProgress();
            }
            Optional<IndexingWorkStatus> status = workQuery.findByWorkId(id);
            if (status.isEmpty()) {
                return WorkEvaluation.inProgress();
            }
            statuses.add(status.get());
        }
        Optional<IndexingWorkStatus> failed = statuses.stream()
            .filter(IndexingWorkStatus::requiresOperatorReview)
            .findFirst();
        if (failed.isPresent()) {
            IndexingWorkStatus status = failed.get();
            return WorkEvaluation.failure(
                StringUtils.hasText(status.errorCode())
                    ? status.errorCode()
                    : "INDEXING_WORK_FAILED",
                StringUtils.hasText(status.deadLetterReason())
                    ? status.deadLetterReason()
                    : "Indexing work requires operator review"
            );
        }
        return statuses.stream().allMatch(IndexingWorkStatus::isSuccessfulTerminal)
            ? WorkEvaluation.success()
            : WorkEvaluation.inProgress();
    }

    private DocumentIngestionPlan plan(DocumentSource source) {
        return planningAdapter.plan(
            readerFor(source),
            SpringAiDocumentIndexingOptions.builder()
                .entityType(entityType)
                .sourceId(source.getId())
                .sourceVersion(source.getSourceVersion())
                .sourceName(source.getTitle())
                .tenantId(source.getTenantId())
                .visibility(source.getVisibility())
                .operation(AIProcessOperation.UPDATE)
                .allowedMetadataKey("originalFilename")
                .metadata("originalFilename", source.getOriginalFilename())
                .build()
        );
    }

    private DocumentReader readerFor(DocumentSource source) {
        FileSystemResource resource = new FileSystemResource(source.getStoragePath());
        SpringAiTrustedResourcePolicy policy =
            SpringAiTrustedResourcePolicy.trustedRoot(trustedRoot);
        if (".json".equals(source.getExtension())) {
            return readerFactory.jsonReader(resource, policy, "content", "text", "body");
        }
        return readerFactory.textReader(resource, policy);
    }

    private List<DocumentChunkManifest> persistDraft(
        DocumentIngestionManifest manifest
    ) {
        List<DocumentChunkManifest> rows = manifest.chunks().stream()
            .map(chunk -> toRow(manifest, chunk))
            .toList();
        return manifestRepository.saveAllAndFlush(rows);
    }

    private DocumentChunkManifest toRow(
        DocumentIngestionManifest manifest,
        DocumentManifestChunk chunk
    ) {
        DocumentChunkManifest row = new DocumentChunkManifest();
        row.setManifestId(manifest.manifestId());
        row.setPlanId(manifest.planId());
        row.setSourceId(manifest.sourceId());
        row.setSourceVersion(manifest.sourceVersion());
        row.setSourceName(manifest.sourceName());
        row.setEntityType(manifest.entityType());
        row.setTenantId(manifest.tenantId());
        row.setVisibility(manifest.visibility());
        row.setSourceDocumentId(chunk.sourceDocumentId());
        row.setChunkId(chunk.chunkId());
        row.setChunkIndex(chunk.chunkIndex());
        row.setEntityId(chunk.entityId());
        row.setContentFingerprint(chunk.contentFingerprint());
        row.setState(DocumentChunkManifest.LifecycleState.DRAFT);
        row.setCreatedAt(manifest.createdAt());
        row.setUpdatedAt(instantNow());
        return row;
    }

    private DocumentIngestionManifest toManifest(
        List<DocumentChunkManifest> rows
    ) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("Manifest rows are required");
        }
        List<DocumentChunkManifest> ordered = rows.stream()
            .sorted(Comparator.comparingInt(DocumentChunkManifest::getChunkIndex))
            .toList();
        DocumentChunkManifest first = ordered.getFirst();
        return new DocumentIngestionManifest(
            DocumentIngestionManifest.CURRENT_SCHEMA_VERSION,
            first.getManifestId(),
            first.getPlanId(),
            first.getSourceId(),
            first.getSourceVersion(),
            first.getSourceName(),
            first.getEntityType(),
            first.getTenantId(),
            first.getVisibility(),
            first.getCreatedAt(),
            ordered.stream().map(row -> new DocumentManifestChunk(
                row.getSourceDocumentId(),
                row.getChunkId(),
                row.getChunkIndex(),
                row.getEntityId(),
                row.getContentFingerprint()
            )).toList()
        );
    }

    private ChunkPreview toPreview(DocumentIngestionChunk chunk) {
        String content = chunk.indexDocument().semanticSearchText();
        boolean truncated = content.length() > previewCharacters;
        String bounded = truncated
            ? content.substring(0, previewCharacters)
            : content;
        List<DocumentIngestionWarning> warnings = new ArrayList<>(chunk.warnings());
        if (truncated) {
            warnings.add(DocumentIngestionWarning.one(
                DocumentIngestionWarningCode.PREVIEW_CONTENT_BOUNDED,
                "contentPreview"
            ));
        }
        return new ChunkPreview(
            chunk.sourceDocumentId(),
            chunk.chunkId(),
            chunk.chunkIndex(),
            chunk.chunkCount(),
            chunk.entityId(),
            bounded,
            chunk.contentLength(),
            truncated,
            chunk.contentFingerprint(),
            chunk.indexDocument().vectorMetadata(),
            List.copyOf(warnings)
        );
    }

    private Evidence toEvidence(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Map<String, Object> metadata = metadata(row.get("metadata"));
        String entityId = text(row.get("id"));
        if (!StringUtils.hasText(entityId)) {
            return null;
        }
        return new Evidence(
            entityId,
            text(metadata.get(DocumentMetadataKeys.SOURCE_ID)),
            longValue(metadata.get(DocumentMetadataKeys.SOURCE_VERSION)),
            text(metadata.get(DocumentMetadataKeys.SOURCE_NAME)),
            text(metadata.get(DocumentMetadataKeys.CHUNK_ID)),
            intValue(metadata.get(DocumentMetadataKeys.CHUNK_INDEX)),
            boundedText(text(row.get("content")), 4_000),
            doubleValue(row.get("score")),
            Map.copyOf(metadata)
        );
    }

    private Map<String, Object> metadata(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null && item != null) {
                    result.put(key.toString(), item);
                }
            });
            return result;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return objectMapper.readValue(text, MAP_TYPE);
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private Map<String, List<DocumentChunkManifest>> groupedRuns(String sourceId) {
        return group(manifestRepository
            .findBySourceIdOrderBySourceVersionAscChunkIndexAsc(sourceId));
    }

    private Map<String, List<DocumentChunkManifest>> group(
        List<DocumentChunkManifest> rows
    ) {
        return rows.stream().collect(Collectors.groupingBy(
            DocumentChunkManifest::getManifestId,
            LinkedHashMap::new,
            Collectors.toList()
        ));
    }

    private ManifestRun toRun(List<DocumentChunkManifest> rows) {
        DocumentChunkManifest first = rows.getFirst();
        Set<DocumentChunkManifest.LifecycleState> states = rows.stream()
            .map(DocumentChunkManifest::getState)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return new ManifestRun(
            first.getManifestId(),
            first.getPlanId(),
            first.getSourceVersion(),
            states.size() == 1 ? states.iterator().next().name() : "MIXED",
            rows.size(),
            rows.stream().map(row -> workEvidence(row.getIndexWorkId())).toList(),
            rows.stream().map(row -> workEvidence(row.getDeleteWorkId())).toList(),
            first.getFailureCode(),
            first.getFailureMessage()
        );
    }

    private WorkEvidence workEvidence(String workId) {
        if (!StringUtils.hasText(workId)) {
            return new WorkEvidence("", "NOT_SUBMITTED", null, null);
        }
        Optional<IndexingWorkStatus> status = workQuery.findByWorkId(workId);
        return status.map(value -> new WorkEvidence(
            value.workId(),
            value.status().name(),
            value.errorCode(),
            value.deadLetterReason()
        )).orElseGet(() -> new WorkEvidence(
            workId,
            "UNKNOWN",
            null,
            null
        ));
    }

    private DeleteResult deleteResult(
        DocumentSource source,
        List<DocumentChunkManifest> rows
    ) {
        return new DeleteResult(
            toSummary(source),
            rows.getFirst().getManifestId(),
            rows.size(),
            rows.stream().map(DocumentChunkManifest::getDeleteWorkId)
                .filter(StringUtils::hasText)
                .toList(),
            rows.stream().map(DocumentChunkManifest::getEntityId).toList()
        );
    }

    private List<String> requireWorkIds(
        List<IndexingQueueEntry> accepted,
        int expected
    ) {
        if (accepted == null || accepted.size() != expected) {
            throw new DocumentIngestionException(
                DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED,
                "Document queue returned incomplete acceptance evidence"
            );
        }
        List<String> ids = accepted.stream()
            .map(IndexingQueueEntry::getId)
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .toList();
        if (ids.size() != expected) {
            throw new DocumentIngestionException(
                DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED,
                "Document queue returned incomplete work identifiers"
            );
        }
        return ids;
    }

    private void applyAcceptedWorkIds(
        List<DocumentChunkManifest> rows,
        List<String> workIds
    ) {
        for (int index = 0; index < Math.min(rows.size(), workIds.size()); index++) {
            rows.get(index).setIndexWorkId(workIds.get(index));
        }
    }

    private void applyDeleteWorkIds(
        List<DocumentChunkManifest> rows,
        List<String> workIds
    ) {
        for (int index = 0; index < Math.min(rows.size(), workIds.size()); index++) {
            rows.get(index).setDeleteWorkId(workIds.get(index));
            rows.get(index).setUpdatedAt(instantNow());
        }
    }

    private void markFailed(
        List<DocumentChunkManifest> rows,
        String code,
        String message
    ) {
        rows.forEach(row -> {
            row.setState(DocumentChunkManifest.LifecycleState.FAILED);
            row.setFailureCode(safeCode(code));
            row.setFailureMessage(safeMessage(message));
            row.setUpdatedAt(instantNow());
        });
    }

    private void markSourceFailure(
        DocumentSource source,
        String code,
        String message
    ) {
        source.setStatus(DocumentSource.Status.FAILED);
        source.setLastFailureCode(safeCode(code));
        source.setLastFailureMessage(safeMessage(message));
        source.setUpdatedAt(instantNow());
    }

    private void clearFailure(DocumentSource source) {
        source.setLastFailureCode(null);
        source.setLastFailureMessage(null);
    }

    private Instant instantNow() {
        return clock.instant();
    }

    private LocalDateTime localNow() {
        return LocalDateTime.now(clock);
    }

    private void requireReplaceable(DocumentSource source) {
        if (source.getStatus() == DocumentSource.Status.INDEXING
            || source.getStatus() == DocumentSource.Status.REPLACING
            || source.getStatus() == DocumentSource.Status.DELETING) {
            throw new IllegalStateException(
                "Wait for current document lifecycle work before replacing content"
            );
        }
        if (source.getStatus() == DocumentSource.Status.DELETED) {
            throw new IllegalStateException("Deleted document sources cannot be replaced");
        }
    }

    private StoredContent validateSource(CreateSourceCommand command) {
        if (command == null || command.content() == null
            || command.content().length == 0) {
            throw new IllegalArgumentException("Document content is required");
        }
        if (command.content().length > MAX_BYTES) {
            throw new DocumentIngestionException(
                DocumentIngestionFailureCode.DOCUMENT_LIMIT_EXCEEDED,
                "Document exceeds max supported size of " + MAX_BYTES + " bytes"
            );
        }
        String originalFilename = safeFilename(command.originalFilename());
        String extension = extension(originalFilename);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported document type: " + extension);
        }
        return new StoredContent(
            StringUtils.hasText(command.title())
                ? boundedText(command.title().trim(), 512)
                : originalFilename,
            originalFilename,
            StringUtils.hasText(command.contentType())
                ? boundedText(command.contentType().trim(), 128)
                : "application/octet-stream",
            extension,
            requiredText(command.tenantId(), "tenantId", 256),
            StringUtils.hasText(command.visibility())
                ? boundedText(command.visibility().trim(), 128)
                : "internal",
            command.content()
        );
    }

    private void applyContent(
        DocumentSource source,
        StoredContent stored,
        long version
    ) {
        source.setTitle(stored.title());
        source.setOriginalFilename(stored.originalFilename());
        source.setContentType(stored.contentType());
        source.setExtension(stored.extension());
        source.setTenantId(stored.tenantId());
        source.setVisibility(stored.visibility());
        source.setSourceVersion(version);
        source.setStoragePath(writeContent(
            source.getId(),
            stored.extension(),
            stored.content()
        ).toString());
        source.setContentHash(sha256(stored.content()));
    }

    private Path writeContent(String sourceId, String extension, byte[] content) {
        try {
            Files.createDirectories(trustedRoot);
            Path destination = trustedRoot.resolve(sourceId + extension).normalize();
            if (!destination.startsWith(trustedRoot)) {
                throw new IllegalArgumentException(
                    "Document storage path escaped trusted root"
                );
            }
            Files.write(destination, content);
            return destination;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store trusted document", exception);
        }
    }

    private DocumentSource requireSource(String sourceId) {
        String id = requiredText(sourceId, "sourceId", 256);
        return sourceRepository.findById(id).orElseThrow(() ->
            new IllegalArgumentException("Unknown document source: " + id)
        );
    }

    private SourceSummary toSummary(DocumentSource source) {
        return new SourceSummary(
            source.getId(),
            source.getTitle(),
            source.getOriginalFilename(),
            source.getTenantId(),
            source.getVisibility(),
            source.getContentHash(),
            source.getSourceVersion(),
            source.getActiveVersion(),
            source.getStatus(),
            manifestRepository.countBySourceIdAndState(
                source.getId(),
                DocumentChunkManifest.LifecycleState.ACTIVE
            ),
            source.getLastFailureCode(),
            source.getLastFailureMessage()
        );
    }

    private String safeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            throw new IllegalArgumentException("originalFilename is required");
        }
        String normalized = Path.of(filename).getFileName().toString().trim();
        if (!StringUtils.hasText(normalized)
            || ".".equals(normalized)
            || "..".equals(normalized)) {
            throw new IllegalArgumentException("originalFilename is invalid");
        }
        return boundedText(normalized, 255);
    }

    private String extension(String filename) {
        int index = filename.lastIndexOf('.');
        return index >= 0
            ? filename.substring(index).toLowerCase(Locale.ROOT)
            : "";
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String requiredText(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return boundedText(value.trim(), maxLength);
    }

    private String boundedText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength
            ? value
            : value.substring(0, maxLength);
    }

    private String safeCode(String value) {
        return boundedText(StringUtils.hasText(value) ? value.trim() : "UNKNOWN", 128);
    }

    private String safeMessage(String value) {
        return boundedText(
            StringUtils.hasText(value) ? value.trim() : "Document lifecycle work failed",
            256
        );
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private int intValue(Object value) {
        return value instanceof Number number
            ? number.intValue()
            : parseInt(text(value));
    }

    private long longValue(Object value) {
        return value instanceof Number number
            ? number.longValue()
            : parseLong(text(value));
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(text(value));
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
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
        public static CreateSourceCommand text(
            String title,
            String filename,
            String content
        ) {
            return new CreateSourceCommand(
                title,
                filename,
                "text/plain",
                "default",
                "internal",
                content == null
                    ? null
                    : content.getBytes(StandardCharsets.UTF_8)
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
        long sourceVersion,
        Long activeVersion,
        DocumentSource.Status status,
        long activeChunks,
        String failureCode,
        String failureMessage
    ) { }

    public record ChunkPreview(
        String sourceDocumentId,
        String chunkId,
        int chunkIndex,
        int chunkCount,
        String entityId,
        String contentPreview,
        int contentLength,
        boolean previewTruncated,
        String contentFingerprint,
        Map<String, Object> safeMetadata,
        List<DocumentIngestionWarning> warnings
    ) { }

    public record PreviewResult(
        String planId,
        SourceSummary source,
        int documentCount,
        int chunkCount,
        int totalContentLength,
        boolean chunksTruncated,
        List<ChunkPreview> previewChunks,
        int metadataDroppedCount,
        List<DocumentIngestionWarning> warnings
    ) { }

    public record IndexResult(
        SourceSummary source,
        String manifestId,
        int queuedChunks,
        int activeChunksBeforeSubmission,
        List<String> workIds,
        List<String> entityIds
    ) { }

    public record DeleteResult(
        SourceSummary source,
        String manifestId,
        int queuedDeletes,
        List<String> workIds,
        List<String> entityIds
    ) { }

    public record LifecycleResult(
        SourceSummary source,
        List<ManifestRun> manifests
    ) { }

    public record ManifestRun(
        String manifestId,
        String planId,
        long sourceVersion,
        String state,
        int chunkCount,
        List<WorkEvidence> indexingWork,
        List<WorkEvidence> deletionWork,
        String failureCode,
        String failureMessage
    ) { }

    public record WorkEvidence(
        String workId,
        String state,
        String errorCode,
        String failureReason
    ) { }

    public record QueryResult(
        String query,
        String tenantId,
        int resultCount,
        List<Evidence> evidence,
        long processingTimeMs,
        String embeddingModel
    ) { }

    public record Evidence(
        String entityId,
        String sourceId,
        long sourceVersion,
        String sourceName,
        String chunkId,
        int chunkIndex,
        String content,
        double score,
        Map<String, Object> metadata
    ) { }

    private record StoredContent(
        String title,
        String originalFilename,
        String contentType,
        String extension,
        String tenantId,
        String visibility,
        byte[] content
    ) { }

    private record WorkEvaluation(
        boolean successful,
        boolean failed,
        String errorCode,
        String message
    ) {
        private static WorkEvaluation success() {
            return new WorkEvaluation(true, false, null, null);
        }

        private static WorkEvaluation inProgress() {
            return new WorkEvaluation(false, false, null, null);
        }

        private static WorkEvaluation failure(String code, String message) {
            return new WorkEvaluation(false, true, code, message);
        }
    }
}
