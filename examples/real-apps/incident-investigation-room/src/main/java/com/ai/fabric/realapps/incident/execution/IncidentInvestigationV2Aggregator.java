package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanResultAggregator;
import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.incident.domain.ChangeRiskInvestigationFinding;
import com.ai.fabric.realapps.incident.domain.IncidentDataSourceUsage;
import com.ai.fabric.realapps.incident.domain.IncidentInvestigationAssessment;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthInvestigationFinding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class IncidentInvestigationV2Aggregator
    implements PlanResultAggregator<IncidentPlanRequest, IncidentInvestigationAssessment> {

    @Override
    public PlanComponentId id() {
        return IncidentPlans.ASSESSMENT_RESULT_V2;
    }

    @Override
    public Class<IncidentPlanRequest> planInputType() {
        return IncidentPlanRequest.class;
    }

    @Override
    public Class<IncidentInvestigationAssessment> outputType() {
        return IncidentInvestigationAssessment.class;
    }

    @Override
    public Map<String, Class<?>> requiredStepOutputs() {
        return Map.of(
            IncidentPlans.SERVICE_HEALTH_STEP,
            ServiceHealthInvestigationFinding.class,
            IncidentPlans.CHANGE_RISK_STEP,
            ChangeRiskInvestigationFinding.class
        );
    }

    @Override
    public IncidentInvestigationAssessment aggregate(
        IncidentPlanRequest input,
        PlanStepOutputs outputs
    ) {
        ServiceHealthInvestigationFinding health = outputs.require(
            IncidentPlans.SERVICE_HEALTH_STEP,
            ServiceHealthInvestigationFinding.class
        );
        ChangeRiskInvestigationFinding change = outputs.require(
            IncidentPlans.CHANGE_RISK_STEP,
            ChangeRiskInvestigationFinding.class
        );
        if (!input.sourceRevision().equals(health.sourceRevision())
            || !input.sourceRevision().equals(change.sourceRevision())) {
            throw new IllegalArgumentException(
                "Validated branches do not match the plan source revision"
            );
        }
        LinkedHashSet<String> citations = new LinkedHashSet<>();
        citations.addAll(health.evidenceIds());
        citations.addAll(change.evidenceIds());
        citations.addAll(change.runbookEvidenceIds());
        List<IncidentDataSourceUsage> sources = new ArrayList<>();
        sources.addAll(health.dataSources());
        sources.addAll(change.dataSources());
        String severity = higher(health.severity(), change.riskLevel());
        return new IncidentInvestigationAssessment(
            input.incidentId(),
            input.deploymentId(),
            input.sourceRevision(),
            severity,
            health.healthStatus(),
            change.riskLevel(),
            change.suspectedChange(),
            recommendation(change),
            List.copyOf(citations),
            health,
            change,
            List.copyOf(sources),
            "ACTION_AND_RAG_CITATIONS_VALIDATED"
        );
    }

    private String recommendation(ChangeRiskInvestigationFinding change) {
        return "Validate " + change.suspectedChange()
            + " against the cited live signals, then follow only the cited approved runbook.";
    }

    private String higher(String first, String second) {
        String a = normalized(first);
        String b = normalized(second);
        return rank(a) >= rank(b) ? a : b;
    }

    private String normalized(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
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
}
