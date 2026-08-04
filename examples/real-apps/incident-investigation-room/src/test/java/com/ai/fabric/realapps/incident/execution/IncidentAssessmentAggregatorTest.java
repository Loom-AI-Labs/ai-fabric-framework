package com.ai.fabric.realapps.incident.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.incident.domain.ChangeRiskFinding;
import com.ai.fabric.realapps.incident.domain.IncidentEvidence;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthFinding;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IncidentAssessmentAggregatorTest {

    private final IncidentAssessmentAggregator aggregator =
        new IncidentAssessmentAggregator();

    @Test
    void aggregatesOnlyApprovedBranchCitations() {
        var result = aggregator.aggregate(input(), outputs(
            List.of("health-1"),
            List.of("change-1")
        ));

        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.evidenceIds())
            .containsExactly("health-1", "change-1");
        assertThat(result.sourceRevision()).isEqualTo("revision-1");
    }

    @Test
    void rejectsCitationOutsideImmutableBranch() {
        assertThatThrownBy(() -> aggregator.aggregate(
            input(),
            outputs(List.of("change-1"), List.of("change-1"))
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("outside its immutable branch");
    }

    private IncidentPlanRequest input() {
        return new IncidentPlanRequest(
            "Investigate",
            "incident-1",
            "deployment-1",
            "revision-1",
            List.of(evidence("health-1", "SERVICE_HEALTH")),
            List.of(evidence("change-1", "RECENT_CHANGE")),
            null
        );
    }

    private PlanStepOutputs outputs(
        List<String> healthEvidence,
        List<String> changeEvidence
    ) {
        return new PlanStepOutputs(Map.of(
            IncidentPlans.SERVICE_HEALTH_STEP,
            new ServiceHealthFinding(
                "DEGRADED",
                "HIGH",
                "Service regression",
                healthEvidence
            ),
            IncidentPlans.CHANGE_RISK_STEP,
            new ChangeRiskFinding(
                "MEDIUM",
                "release-1",
                "Recent change",
                changeEvidence
            )
        ));
    }

    private IncidentEvidence evidence(String id, String category) {
        return new IncidentEvidence(
            id,
            category,
            "Approved evidence",
            "HIGH",
            Instant.parse("2026-08-03T18:00:00Z")
        );
    }
}
