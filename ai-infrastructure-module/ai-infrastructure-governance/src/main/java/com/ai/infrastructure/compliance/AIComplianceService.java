package com.ai.infrastructure.compliance;

import com.ai.infrastructure.compliance.policy.ComplianceCheckProvider;
import com.ai.infrastructure.compliance.policy.ComplianceCheckResult;
import com.ai.infrastructure.dto.AIAccessSubjectContext;
import com.ai.infrastructure.dto.AIComplianceReport;
import com.ai.infrastructure.dto.AIComplianceRequest;
import com.ai.infrastructure.dto.AIComplianceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Infrastructure-only compliance service delegating policy decisions to customer hooks.
 */
@Slf4j
@RequiredArgsConstructor
public class AIComplianceService {

    private final Clock clock;

    private final ComplianceCheckProvider complianceProvider;

    public AIComplianceResponse checkCompliance(AIComplianceRequest request) {
        long started = System.nanoTime();
        Objects.requireNonNull(request, "compliance request must not be null");
        LocalDateTime timestamp = Optional.ofNullable(request.getTimestamp())
            .orElseGet(() -> LocalDateTime.now(clock));

        ComplianceCheckProvider provider = requireProvider();

        Decision decision = evaluateCompliance(provider, request);

        AIComplianceReport report = buildReport(request, decision, timestamp);
        String subjectId = resolveSubjectId(request);
        long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return AIComplianceResponse.builder()
            .requestId(request.getRequestId())
            .subjectId(subjectId)
            .overallCompliant(decision.compliant())
            .violations(List.copyOf(decision.violations()))
            .processingTimeMs(durationMs)
            .timestamp(timestamp)
            .success(!decision.failed())
            .errorMessage(decision.failed() ? decision.errorDetails() : null)
            .report(report)
            .build();
    }

    private ComplianceCheckProvider requireProvider() {
        if (complianceProvider == null) {
            throw new IllegalStateException("""
                No ComplianceCheckProvider bean available. Register an implementation of \
                com.ai.infrastructure.compliance.policy.ComplianceCheckProvider to evaluate compliance.""");
        }
        return complianceProvider;
    }

    private Decision evaluateCompliance(ComplianceCheckProvider provider, AIComplianceRequest request) {
        List<String> violations = new ArrayList<>();
        String details = null;
        boolean compliant = true;
        boolean failed = false;

        try {
            ComplianceCheckResult result = provider.checkCompliance(request);
            if (result != null) {
                compliant = result.isCompliant();
                if (result.getViolations() != null) {
                    violations.addAll(result.getViolations());
                }
                details = result.getDetails();
            }
        } catch (Exception ex) {
            log.warn("ComplianceCheckProvider threw an exception for request {}: {}", request.getRequestId(), ex.getMessage());
            compliant = false;
            failed = true;
            violations.add("COMPLIANCE_PROVIDER_ERROR");
            details = ex.getMessage();
        }

        return new Decision(compliant, failed, violations, details);
    }

    private AIComplianceReport buildReport(AIComplianceRequest request,
                                           Decision decision,
                                           LocalDateTime timestamp) {
        return AIComplianceReport.builder()
            .reportId("COMP_" + timestamp.toString())
            .requestId(request.getRequestId())
            .subjectId(resolveSubjectId(request))
            .timestamp(timestamp)
            .overallCompliant(decision.compliant())
            .violations(List.copyOf(decision.violations()))
            .dataClassification(request.getDataClassification())
            .purpose(request.getPurpose())
            .regulationTypes(request.getRegulationTypes())
            .notes(decision.errorDetails())
            .build();
    }

    private String resolveSubjectId(AIComplianceRequest request) {
        if (request == null || request.getAuthContext() == null) {
            return null;
        }
        AIAccessSubjectContext authContext = request.getAuthContext();
        if (authContext.getSubjectId() != null && !authContext.getSubjectId().isBlank()) {
            return authContext.getSubjectId();
        }
        if (authContext.getSessionId() != null && !authContext.getSessionId().isBlank()) {
            return authContext.getSessionId();
        }
        return null;
    }

    private record Decision(boolean compliant,
                            boolean failed,
                            List<String> violations,
                            String errorDetails) {
    }
}
