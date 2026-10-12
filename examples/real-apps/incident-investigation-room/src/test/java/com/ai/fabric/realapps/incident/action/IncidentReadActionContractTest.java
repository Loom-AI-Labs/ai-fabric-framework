package com.ai.fabric.realapps.incident.action;

import ai.fabric.intent.action.annotation.AIAction;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentReadActionContractTest {

    @Test
    void everyPlannerEligibleIncidentReadDeclaresItsReviewedFallbackSpace() {
        List<Class<?>> actionTypes = List.of(
            ReadServiceMetricsActionHandler.class,
            ReadIncidentAlertsActionHandler.class,
            ReadRecentDeploymentsActionHandler.class,
            ReadChangeApprovalsActionHandler.class
        );

        actionTypes.forEach(actionType -> {
            AIAction contract = actionType.getAnnotation(AIAction.class);
            assertThat(contract.readActionResolutionEligible()).isTrue();
            assertThat(contract.groundingVectorSpaces())
                .as(actionType.getSimpleName())
                .containsExactly("incident-runbook");
        });
    }
}
