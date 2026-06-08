package com.ai.infrastructure.web.controller;

import com.ai.infrastructure.dto.AIComplianceRequest;
import com.ai.infrastructure.dto.AIComplianceResponse;
import com.ai.infrastructure.compliance.AIComplianceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;
/**
 * REST Controller for AI Compliance operations
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/compliance")
@RequiredArgsConstructor
public class AIComplianceController {

    private final AIComplianceService aiComplianceService;

    /**
     * Check compliance for a request
     */
    @PostMapping("/check")
    public ResponseEntity<AIComplianceResponse> checkCompliance(
            @Valid @RequestBody AIComplianceRequest request) {
        log.info("Checking compliance for request: {}", request.getRequestId());
        
        try {
            AIComplianceResponse response = aiComplianceService.checkCompliance(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error checking compliance", e);
            return ResponseEntity.internalServerError()
                .body(AIComplianceResponse.builder()
                    .requestId(request.getRequestId())
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
            return null;
        }
        if (request.getAuthContext().getSubjectId() != null && !request.getAuthContext().getSubjectId().isBlank()) {
            return request.getAuthContext().getSubjectId();
        }
        return request.getAuthContext().getSessionId();
    }
}
