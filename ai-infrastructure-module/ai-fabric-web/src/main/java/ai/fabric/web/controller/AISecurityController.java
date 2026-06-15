package ai.fabric.web.controller;

import ai.fabric.dto.AISecurityEvent;
import ai.fabric.dto.AISecurityRequest;
import ai.fabric.dto.AISecurityResponse;
import ai.fabric.security.AISecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for AI Security operations
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/security")
@RequiredArgsConstructor
public class AISecurityController {

    private final AISecurityService aiSecurityService;

    /**
     * Analyze request for security threats
     */
    @PostMapping("/analyze")
    public ResponseEntity<AISecurityResponse> analyzeSecurity(
            @Valid @RequestBody AISecurityRequest request) {
        log.info("Analyzing security request for subject: {}", resolveSubjectId(request));
        
        try {
            AISecurityResponse response = aiSecurityService.analyzeRequest(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error analyzing security request", e);
            return ResponseEntity.internalServerError()
                .body(AISecurityResponse.builder()
                    .requestId(request.getRequestId())
                    .subjectId(resolveSubjectId(request))
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build());
        }
    }

    /**
     * Get security events for a user
     */
    @GetMapping("/events/{subjectId}")
    public ResponseEntity<List<AISecurityEvent>> getSecurityEvents(
            @PathVariable String subjectId) {
        log.info("Retrieving security events for subject: {}", subjectId);
        
        try {
            List<AISecurityEvent> events = aiSecurityService.getSecurityEvents(subjectId);
            return ResponseEntity.ok(events);
        } catch (Exception e) {
            log.error("Error retrieving security events for subject: {}", subjectId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get all security events
     */
    @GetMapping("/events")
    public ResponseEntity<List<AISecurityEvent>> getAllSecurityEvents() {
        log.info("Retrieving all security events");
        
        try {
            List<AISecurityEvent> events = aiSecurityService.getAllSecurityEvents();
            return ResponseEntity.ok(events);
        } catch (Exception e) {
            log.error("Error retrieving all security events", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Clear security events for a user
     */
    @DeleteMapping("/events/{subjectId}")
    public ResponseEntity<Void> clearSecurityEvents(@PathVariable String subjectId) {
        log.info("Clearing security events for subject: {}", subjectId);
        
        try {
            aiSecurityService.clearSecurityEvents(subjectId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error clearing security events for subject: {}", subjectId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get security statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getSecurityStatistics() {
        log.info("Retrieving security statistics");
        
        try {
            Map<String, Object> stats = aiSecurityService.getSecurityStatistics();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error retrieving security statistics", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Health check for security service
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        log.info("Performing security service health check");
        
        try {
            Map<String, Object> health = Map.of(
                "status", "UP",
                "service", "AISecurityService",
                "timestamp", System.currentTimeMillis()
            );
            return ResponseEntity.ok(health);
        } catch (Exception e) {
            log.error("Error performing security service health check", e);
            return ResponseEntity.internalServerError()
                .body(Map.of(
                    "status", "DOWN",
                    "service", "AISecurityService",
                    "error", e.getMessage(),
                    "timestamp", System.currentTimeMillis()
                ));
        }
    }

    private String resolveSubjectId(AISecurityRequest request) {
        if (request == null || request.getAuthContext() == null) {
            return null;
        }
        if (request.getAuthContext().getSubjectId() != null && !request.getAuthContext().getSubjectId().isBlank()) {
            return request.getAuthContext().getSubjectId();
        }
        return request.getAuthContext().getSessionId();
    }
}
