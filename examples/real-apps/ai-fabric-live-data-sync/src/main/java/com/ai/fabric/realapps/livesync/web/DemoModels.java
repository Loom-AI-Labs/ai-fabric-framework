package com.ai.fabric.realapps.livesync.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class DemoModels {

    private DemoModels() {
    }

    public record WorkspaceResponse(
        String workspaceId,
        Instant expiresAt,
        DemoState state
    ) {
    }

    public record DemoState(
        String workspaceId,
        Map<String, Integer> sourceCounts,
        Map<String, Integer> vectorCounts,
        int sourceTotal,
        int vectorTotal,
        int synchronizedTotal,
        List<EntityRecord> entities,
        List<SyncEvent> events,
        List<IndexingWorkView> indexingWork,
        AnnotationCoverage annotationCoverage,
        Instant checkedAt
    ) {
    }

    public record EntityRecord(
        String kind,
        String entityType,
        String recordKey,
        String title,
        int revision,
        Instant updatedAt,
        Map<String, Object> fields,
        VectorProof vector
    ) {
    }

    public record VectorProof(
        boolean present,
        boolean inSync,
        String vectorId,
        String content,
        Map<String, Object> metadata,
        String message
    ) {
        public static VectorProof missing(String message) {
            return new VectorProof(false, false, null, null, Map.of(), message);
        }
    }

    public record SyncEvent(
        String id,
        String operation,
        String kind,
        String entityType,
        String recordKey,
        String title,
        Integer revision,
        boolean sourcePresent,
        boolean vectorPresent,
        boolean inSync,
        long elapsedMs,
        String indexingWorkId,
        String indexingDispatchStatus,
        String message,
        Instant occurredAt
    ) {
    }

    public record AnnotationCoverage(
        List<AnnotationUse> annotations,
        String extractionOwner,
        String lifecycleOwner,
        String consistencyMode
    ) {
    }

    public record AnnotationUse(
        String annotation,
        String location,
        String proof
    ) {
    }

    public record EntityUpdateRequest(
        String title,
        String summary,
        String specification,
        String category,
        BigDecimal price,
        String guidance,
        String audience,
        String status,
        LocalDate effectiveDate,
        String symptoms,
        String resolution,
        String productArea,
        String severity
    ) {
    }

    public record MutationResponse(
        SyncEvent mutation,
        DemoState state,
        Map<String, Object> metadata,
        IndexingWorkView indexingWork
    ) {
    }

    public record EntityCreateRequest(
        String recordKey,
        EntityUpdateRequest entity
    ) {
    }

    public record IndexingWorkView(
        String workId,
        String entityType,
        String entityId,
        String workType,
        String sourceOperation,
        String strategy,
        String status,
        int retryCount,
        int maxRetries,
        String errorCode,
        String deadLetterReason,
        String correlationId,
        boolean terminal,
        boolean successfulTerminal,
        boolean inProgress,
        boolean requiresOperatorReview,
        java.time.LocalDateTime requestedAt,
        java.time.LocalDateTime scheduledFor,
        java.time.LocalDateTime startedAt,
        java.time.LocalDateTime completedAt,
        java.time.LocalDateTime lastErrorAt,
        java.time.LocalDateTime updatedAt
    ) {
    }

    public record LifecycleWorkResponse(
        String scenario,
        String guidance,
        Map<String, Object> metadata,
        IndexingWorkView indexingWork,
        DemoState state
    ) {
    }

    public record SearchRequest(String query, Integer limit) {
    }

    public record SearchHit(
        String id,
        String title,
        String entityType,
        String recordKey,
        String content,
        double score,
        Map<String, Object> metadata
    ) {
    }

    public record SearchResponse(
        String query,
        List<SearchHit> hits,
        Map<String, Integer> hitsByEntityType,
        long processingTimeMs,
        String embeddingModel
    ) {
    }

    public record ChatRequest(
        String message,
        String conversationId,
        String mode,
        String position
    ) {
    }

    public record ChatResponse(
        String conversationId,
        ChatResult result
    ) {
    }

    public record ChatResult(
        String type,
        boolean success,
        String message,
        String errorCode,
        Map<String, Object> data,
        Map<String, Object> metadata
    ) {
        public static ChatResult error(String errorCode, String message) {
            return new ChatResult("ERROR", false, message, errorCode, Map.of(), Map.of());
        }
    }
}
