package ai.fabric.relationship.service;

import ai.fabric.dto.RAGResponse;
import ai.fabric.relationship.exception.RelationshipQueryErrorContext;
import ai.fabric.relationship.exception.RelationshipQueryException;
import ai.fabric.relationship.model.QueryOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Simple wrapper around {@link LLMDrivenJPAQueryService}.
 *
 * <p>This module previously exposed multiple fallback stages (metadata traversal, vector fallback, etc.).
 * The current implementation favors simplicity and materializes directly from relational query results.</p>
 */
@Slf4j
public class ReliableRelationshipQueryService {

    private static final String ERROR_INVALID_QUERY = "INVALID_QUERY";
    private static final String ERROR_EXECUTION_FAILED = "EXECUTION_FAILED";
    private static final String PROVIDER = "relationship-query";
    private static final String NO_CONTEXT_MESSAGE = "Relationship query did not return context.";

    private final LLMDrivenJPAQueryService primaryService;

    public ReliableRelationshipQueryService(LLMDrivenJPAQueryService primaryService) {
        this.primaryService = Objects.requireNonNull(primaryService, "primaryService is required");
    }

    public RAGResponse execute(String query) {
        return execute(query, null, null);
    }

    public RAGResponse execute(String query, @Nullable QueryOptions options) {
        return execute(query, null, options);
    }

    public RAGResponse execute(String query, @Nullable List<String> entityTypes, @Nullable QueryOptions options) {
        long startedAt = System.nanoTime();
        if (!StringUtils.hasText(query)) {
            return failure(
                query,
                entityTypes,
                ERROR_INVALID_QUERY,
                "Relationship query text is required.",
                null,
                startedAt
            );
        }

        try {
            return primaryService.executeRelationshipQuery(
                query,
                entityTypes,
                options != null ? options : QueryOptions.defaults()
            );
        } catch (RelationshipQueryException ex) {
            log.warn("Relationship query failed with structured context: {}", ex.getMessage());
            return failure(query, entityTypes, ERROR_EXECUTION_FAILED, safeMessage(ex), ex, startedAt);
        } catch (RuntimeException ex) {
            log.warn("Relationship query failed: {}", ex.getMessage());
            return failure(query, entityTypes, ERROR_EXECUTION_FAILED, safeMessage(ex), ex, startedAt);
        }
    }

    private RAGResponse failure(String query,
                                @Nullable List<String> entityTypes,
                                String errorCode,
                                String message,
                                @Nullable RuntimeException cause,
                                long startedAt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", PROVIDER);
        metadata.put("errorCode", errorCode);
        metadata.put("entityTypes", cleanEntityTypes(entityTypes));
        if (cause != null) {
            metadata.put("errorType", cause.getClass().getSimpleName());
        }
        if (cause instanceof RelationshipQueryException relationshipQueryException) {
            relationshipQueryException.getContext().ifPresent(context -> addContextMetadata(metadata, context));
        }

        long processingTimeMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
        return RAGResponse.builder()
            .success(false)
            .documents(List.of())
            .context(NO_CONTEXT_MESSAGE)
            .totalDocuments(0)
            .usedDocuments(0)
            .totalResults(0)
            .returnedResults(0)
            .relevanceScores(List.of())
            .hybridSearchUsed(false)
            .processingTimeMs(processingTimeMs)
            .originalQuery(query)
            .timestamp(LocalDateTime.now())
            .metadata(Collections.unmodifiableMap(metadata))
            .errorMessage(message)
            .warnings(List.of(message))
            .confidenceScore(0.0)
            .build();
    }

    private void addContextMetadata(Map<String, Object> metadata, RelationshipQueryErrorContext context) {
        putIfPresent(metadata, "executionStage", context.getExecutionStage());
        putIfPresent(metadata, "primaryEntityType", context.getPrimaryEntityType());
        putIfPresent(metadata, "candidateEntityTypes", context.getCandidateEntityTypes());
        metadata.put("fallbackUsed", context.isFallbackUsed());
        putIfPresent(metadata, "timestamp", context.getTimestamp() != null ? context.getTimestamp().toString() : null);
        putIfPresent(metadata, "errorAttributes", context.getAttributes());
    }

    private List<String> cleanEntityTypes(@Nullable List<String> entityTypes) {
        if (entityTypes == null || entityTypes.isEmpty()) {
            return List.of();
        }
        return entityTypes.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .toList();
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private String safeMessage(Exception ex) {
        return ex != null && StringUtils.hasText(ex.getMessage())
            ? ex.getMessage().trim()
            : "Relationship query execution failed.";
    }
}
