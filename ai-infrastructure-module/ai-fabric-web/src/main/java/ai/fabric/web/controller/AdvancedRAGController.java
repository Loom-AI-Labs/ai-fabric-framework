package ai.fabric.web.controller;

import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.rag.service.AdvancedRAGService;
import ai.fabric.service.VectorManagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST Controller for Advanced RAG operations
 */
@Slf4j
@RestController
@RequestMapping("${ai.web.base-path:/api/ai}/advanced-rag")
@ConditionalOnBean(AdvancedRAGService.class)
@ConditionalOnProperty(prefix = "ai.web.controllers", name = "advanced-rag", havingValue = "true", matchIfMissing = true)
public class AdvancedRAGController {

    private static final String UNKNOWN_QUERY = "unknown";

    private final AdvancedRAGService advancedRAGService;
    private final VectorManagementService vectorManagementService;

    public AdvancedRAGController(AdvancedRAGService advancedRAGService) {
        this(advancedRAGService, null);
    }

    public AdvancedRAGController(AdvancedRAGService advancedRAGService,
                                 VectorManagementService vectorManagementService) {
        this.advancedRAGService = advancedRAGService;
        this.vectorManagementService = vectorManagementService;
    }

    /**
     * Perform advanced RAG with query expansion and re-ranking
     */
    @PostMapping("/search")
    public ResponseEntity<AdvancedRAGResponse> performAdvancedRAG(
            @Valid @RequestBody AdvancedRAGRequest request) {
        log.info("Processing advanced RAG request for query: {}", resolveQuery(request));
        
        try {
            AdvancedRAGResponse response = advancedRAGService.performAdvancedRAG(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing advanced RAG request", e);
            return ResponseEntity.internalServerError()
                .body(AdvancedRAGResponse.builder()
                    .query(resolveQuery(request))
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build());
        }
    }

    /**
     * Get advanced RAG statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getAdvancedRAGStats() {
        log.info("Retrieving advanced RAG statistics");
        
        try {
            Map<String, Object> stats = new LinkedHashMap<>(advancedRAGService.getStatistics());
            stats.put("service", "AdvancedRAGService");
            stats.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error retrieving advanced RAG statistics", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Health check for advanced RAG service
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        log.info("Performing advanced RAG health check");
        
        try {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("status", "UP");
            health.put("service", "AdvancedRAGService");
            health.put("vectorDatabase", vectorDatabaseDiagnostics());
            health.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(health);
        } catch (Exception e) {
            log.error("Error performing advanced RAG health check", e);
            return ResponseEntity.internalServerError()
                .body(Map.of(
                    "status", "DOWN",
                    "service", "AdvancedRAGService",
                    "error", e.getMessage(),
                    "timestamp", System.currentTimeMillis()
                ));
        }
    }

    private Map<String, Object> vectorDatabaseDiagnostics() {
        if (vectorManagementService == null) {
            return Map.of(
                "available", false,
                "diagnosticsAvailable", false,
                "reason", "VectorManagementService bean not available"
            );
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>(vectorManagementService.getProviderDiagnostics());
        diagnostics.putIfAbsent("available", true);
        return diagnostics;
    }

    private String resolveQuery(AdvancedRAGRequest request) {
        return request != null && request.getQuery() != null && !request.getQuery().isBlank()
            ? request.getQuery().trim()
            : UNKNOWN_QUERY;
    }
}
