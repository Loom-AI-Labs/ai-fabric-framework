package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanResultAggregator;
import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.incident.domain.ChangeRiskFinding;
import com.ai.fabric.realapps.incident.domain.IncidentAssessment;
import com.ai.fabric.realapps.incident.domain.IncidentEvidence;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthFinding;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class IncidentAssessmentAggregator
    implements PlanResultAggregator<IncidentPlanRequest, IncidentAssessment> {

    @Override
    public PlanComponentId id() {
        return IncidentPlans.ASSESSMENT_RESULT;
    }

    @Override
    public Class<IncidentPlanRequest> planInputType() {
        return IncidentPlanRequest.class;
    }

    @Override
    public Class<IncidentAssessment> outputType() {
        return IncidentAssessment.class;
    }

    @Override
    public Map<String, Class<?>> requiredStepOutputs() {
        return Map.of(
            IncidentPlans.SERVICE_HEALTH_STEP,
            ServiceHealthFinding.class,
            IncidentPlans.CHANGE_RISK_STEP,
            ChangeRiskFinding.class
        );
    }

    @Override
    public IncidentAssessment aggregate(
        IncidentPlanRequest input,
        PlanStepOutputs outputs
    ) {
        ServiceHealthFinding health = outputs.require(
            IncidentPlans.SERVICE_HEALTH_STEP,
            ServiceHealthFinding.class
        );
        ChangeRiskFinding change = outputs.require(
            IncidentPlans.CHANGE_RISK_STEP,
            ChangeRiskFinding.class
        );
        validateCitations(health.evidenceIds(), input.serviceEvidence());
        validateCitations(change.evidenceIds(), input.changeEvidence());

        LinkedHashSet<String> citations = new LinkedHashSet<>();
        citations.addAll(health.evidenceIds());
        citations.addAll(change.evidenceIds());
        String severity = higher(health.severity(), change.riskLevel());
        return new IncidentAssessment(
            input.incidentId(),
            input.deploymentId(),
            input.sourceRevision(),
            severity,
            requireText(health.healthStatus(), "healthStatus"),
            requireText(change.riskLevel(), "riskLevel"),
            requireText(change.suspectedChange(), "suspectedChange"),
            "Validate " + requireText(change.suspectedChange(), "suspectedChange")
                + " against the cited service signals, then follow the approved runbook evidence.",
            List.copyOf(citations),
            health,
            change
        );
    }

    private void validateCitations(
        List<String> citations,
        List<IncidentEvidence> approvedEvidence
    ) {
        if (citations == null || citations.isEmpty()) {
            throw new IllegalArgumentException(
                "Each incident branch must cite approved evidence"
            );
        }
        Set<String> approvedIds = approvedEvidence.stream()
            .map(IncidentEvidence::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!approvedIds.containsAll(citations)) {
            throw new IllegalArgumentException(
                "A specialist cited evidence outside its immutable branch input"
            );
        }
    }

    private String higher(String first, String second) {
        String a = normalizedLevel(first);
        String b = normalizedLevel(second);
        return rank(a) >= rank(b) ? a : b;
    }

    private String normalizedLevel(String value) {
        String normalized = requireText(value, "severity")
            .toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN" -> normalized;
            default -> "UNKNOWN";
        };
    }

    private int rank(String value) {
        return switch (value) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            default -> 1;
        };
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
