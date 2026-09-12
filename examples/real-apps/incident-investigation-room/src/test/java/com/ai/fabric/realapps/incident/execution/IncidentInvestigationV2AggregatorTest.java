package com.ai.fabric.realapps.incident.execution;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.incident.domain.ChangeRiskInvestigationFinding;
import com.ai.fabric.realapps.incident.domain.IncidentDataSourceUsage;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthInvestigationFinding;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IncidentInvestigationV2AggregatorTest {

    private final IncidentInvestigationV2Aggregator aggregator =
        new IncidentInvestigationV2Aggregator();

    @Test
    void usesHealthFindingWhenNoMaterialChangeIsSupported() {
        var result = aggregator.aggregate(
            input(),
            outputs(
                "LOW",
                "No material recent change is supported by the authorized evidence."
            )
        );

        assertThat(result.likelyCause())
            .isEqualTo("External search dependency is timing out.");
        assertThat(result.recommendation())
            .startsWith("No material recent change is supported.");
        assertThat(result.changeRisk()).isEqualTo("LOW");
    }

    @Test
    void usesValidatedChangeWhenMaterialRiskIsSupported() {
        var result = aggregator.aggregate(
            input(),
            outputs("HIGH", "Payment client release 284")
        );

        assertThat(result.likelyCause())
            .isEqualTo("Payment client release 284");
        assertThat(result.recommendation())
            .contains("Payment client release 284")
            .contains("cited operational signals");
    }

    private IncidentPlanRequest input() {
        return new IncidentPlanRequest(
            "Investigate service impact and recent changes.",
            "incident-1",
            "deployment-1",
            "revision-1",
            List.of(),
            List.of(),
            null
        );
    }

    private PlanStepOutputs outputs(String risk, String suspectedChange) {
        List<IncidentDataSourceUsage> healthSources = List.of(
            new IncidentDataSourceUsage("read_service_metrics", 2, true)
        );
        List<IncidentDataSourceUsage> changeSources = List.of(
            new IncidentDataSourceUsage("read_recent_deployments", 2, true)
        );
        return new PlanStepOutputs(Map.of(
            IncidentPlans.SERVICE_HEALTH_STEP,
            new ServiceHealthInvestigationFinding(
                "DEGRADED",
                "HIGH",
                "External search dependency is timing out.",
                List.of("health-1"),
                healthSources,
                2,
                "Current health evidence",
                "revision-1"
            ),
            IncidentPlans.CHANGE_RISK_STEP,
            new ChangeRiskInvestigationFinding(
                risk,
                suspectedChange,
                "Change assessment",
                List.of("change-1"),
                List.of("runbook-1"),
                changeSources,
                2,
                "Current change evidence",
                "revision-1"
            )
        ));
    }
}
