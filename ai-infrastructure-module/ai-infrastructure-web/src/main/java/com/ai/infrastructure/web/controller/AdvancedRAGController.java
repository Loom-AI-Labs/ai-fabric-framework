package com.ai.infrastructure.web.controller;

import com.ai.infrastructure.dto.AdvancedRAGRequest;
import com.ai.infrastructure.dto.AdvancedRAGResponse;
import com.ai.infrastructure.rag.service.AdvancedRAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;

/**
 * REST Controller for Advanced RAG operations
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/advanced-rag")
@RequiredArgsConstructor
public class AdvancedRAGController {

    private final AdvancedRAGService advancedRAGService;

    /**
     * Perform advanced RAG with query expansion and re-ranking
     */
    @PostMapping("/search")
    public ResponseEntity<AdvancedRAGResponse> performAdvancedRAG(
            @Valid @RequestBody AdvancedRAGRequest request) {
        log.info("Processing advanced RAG request for query: {}", request.getQuery());
        
        try {
            AdvancedRAGResponse response = advancedRAGService.performAdvancedRAG(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing advanced RAG request", e);
            return ResponseEntity.internalServerError()
                .body(AdvancedRAGResponse.builder()
                    .query(request.getQuery())
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
            // This would be implemented in the service
            Map<String, Object> stats = Map.of(
                "totalQueries", 0,
                "averageProcessingTime", 0.0,
                "successRate", 0.0,
                "timestamp", System.currentTimeMillis()
            );
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
            Map<String, Object> health = Map.of(
                "status", "UP",
                "service", "AdvancedRAGService",
                "timestamp", System.currentTimeMillis()
            );
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
}