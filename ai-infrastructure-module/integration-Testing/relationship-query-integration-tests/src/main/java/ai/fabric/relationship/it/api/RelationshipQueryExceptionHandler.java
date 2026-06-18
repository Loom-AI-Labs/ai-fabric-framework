package ai.fabric.relationship.it.api;

import ai.fabric.dto.RAGResponse;
import ai.fabric.relationship.exception.RelationshipQueryErrorContext;
import ai.fabric.relationship.exception.RelationshipQueryException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Integration-test REST layer should never leak 5xx for expected planner/runtime failures.
 *
 * <p>The test contract expects HTTP 200 with a structured {@link RAGResponse} payload; failures
 * should be expressed via {@code success=false} and {@code errorMessage}.</p>
 */
@Slf4j
@RestControllerAdvice
public class RelationshipQueryExceptionHandler {

    @ExceptionHandler(RelationshipQueryException.class)
    public ResponseEntity<RAGResponse> handleRelationshipQueryException(RelationshipQueryException ex) {
        RelationshipQueryErrorContext ctx = ex.getContext().orElse(null);
        log.warn("Relationship query request failed: {} (stage={})", ex.getMessage(), ctx != null ? ctx.getExecutionStage() : null, ex);

        Map<String, Object> metadata = ctx == null
            ? Map.of("errorStage", "UNKNOWN")
            : Map.of(
                "errorStage", ctx.getExecutionStage(),
                "primaryEntityType", ctx.getPrimaryEntityType(),
                "candidateEntityTypes", ctx.getCandidateEntityTypes(),
                "fallbackUsed", ctx.isFallbackUsed(),
                "attributes", ctx.getAttributes(),
                "timestamp", String.valueOf(ctx.getTimestamp())
            );

        RAGResponse response = RAGResponse.builder()
            .success(false)
            .errorMessage(ex.getMessage())
            .originalQuery(ctx != null ? ctx.getOriginalQuery() : null)
            .documents(List.of())
            .totalResults(0)
            .returnedResults(0)
            .processingTimeMs(0L)
            .timestamp(LocalDateTime.now())
            .metadata(metadata)
            .build();

        return ResponseEntity.ok(response);
    }
}
