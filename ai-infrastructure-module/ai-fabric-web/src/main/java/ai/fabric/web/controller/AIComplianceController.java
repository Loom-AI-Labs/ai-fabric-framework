package ai.fabric.web.controller;

import ai.fabric.dto.AIComplianceRequest;
import ai.fabric.dto.AIComplianceResponse;
import ai.fabric.compliance.AIComplianceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;
/**
 * REST Controller for AI Compliance operations
 */
@Slf4j
@RestController
@RequestMapping("${ai.web.base-path:/api/ai}/compliance")
@ConditionalOnBean(AIComplianceService.class)
@ConditionalOnProperty(prefix = "ai.web.controllers", name = "compliance", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AIComplianceController {

    private static final String UNKNOWN_VALUE = "unknown";

    private final AIComplianceService aiComplianceService;

    /**
     * Check compliance for a request
     */
    @PostMapping("/check")
    public ResponseEntity<AIComplianceResponse> checkCompliance(
            @Valid @RequestBody AIComplianceRequest request) {
        log.info("Checking compliance for request: {}", resolveRequestId(request));
        
        try {
            AIComplianceResponse response = aiComplianceService.checkCompliance(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error checking compliance", e);
            return ResponseEntity.internalServerError()
                .body(AIComplianceResponse.builder()
                    .requestId(resolveRequestId(request))
                    .subjectId(resolveSubjectId(request))
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build());
        }
    }

    /**
     * Health check for compliance service
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        log.info("Performing compliance service health check");
        
        try {
            Map<String, Object> health = Map.of(
                "status", "UP",
                "service", "AIComplianceService",
                "timestamp", System.currentTimeMillis()
            );
            return ResponseEntity.ok(health);
        } catch (Exception e) {
            log.error("Error performing compliance service health check", e);
            return ResponseEntity.internalServerError()
                .body(Map.of(
                    "status", "DOWN",
                    "service", "AIComplianceService",
                    "error", e.getMessage(),
                    "timestamp", System.currentTimeMillis()
                ));
        }
    }

    private String resolveSubjectId(AIComplianceRequest request) {
        if (request == null || request.getAuthContext() == null) {
            return UNKNOWN_VALUE;
        }
        if (hasText(request.getAuthContext().getSubjectId())) {
            return request.getAuthContext().getSubjectId().trim();
        }
        if (hasText(request.getAuthContext().getSessionId())) {
            return request.getAuthContext().getSessionId().trim();
        }
        return UNKNOWN_VALUE;
    }

    private String resolveRequestId(AIComplianceRequest request) {
        return request != null && hasText(request.getRequestId())
            ? request.getRequestId().trim()
            : UNKNOWN_VALUE;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
