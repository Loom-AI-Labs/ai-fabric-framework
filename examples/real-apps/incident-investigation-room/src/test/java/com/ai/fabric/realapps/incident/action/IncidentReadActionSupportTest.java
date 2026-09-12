package com.ai.fabric.realapps.incident.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.intent.action.ActionContext;
import com.ai.fabric.realapps.incident.domain.IncidentEventType;
import com.ai.fabric.realapps.incident.service.InMemoryIncidentEventRepository;
import com.ai.fabric.realapps.incident.service.IncidentActionScopeResolver;
import com.ai.fabric.realapps.incident.service.IncidentEventRepository;
import com.ai.fabric.realapps.incident.service.IncidentScenarioCatalog;
import com.ai.fabric.realapps.incident.service.IncidentInvocationMetrics;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IncidentReadActionSupportTest {

    private final IncidentReadActionSupport support =
        new IncidentReadActionSupport(
            new IncidentActionScopeResolver(new IncidentScenarioCatalog()),
            new InMemoryIncidentEventRepository(),
            new IncidentInvocationMetrics()
        );

    @Test
    void emitsBoundedCanonicalFactsFromTrustedIdentity() {
        var result = support.execute(
            context(
                "checkout-regression",
                "commerce-api-prod",
                "action:read_service_metrics"
            ),
            "read_service_metrics",
            Set.of(
                IncidentEventType.SERVICE_METRIC,
                IncidentEventType.DEPENDENCY_SIGNAL
            ),
            60,
            3
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(support.facts(result))
            .containsEntry("factSource", "authorized_incident_events")
            .containsEntry("action", "read_service_metrics")
            .containsEntry("sourceRevision", "incident-rev-checkout-7")
            .containsEntry("candidateEventCount", 3);
        assertThat(support.facts(result).toString())
            .contains("health-checkout-errors")
            .doesNotContain("other-tenant-critical-error")
            .doesNotContain("nearby-deployment-failure")
            .doesNotContain("tenantId")
            .doesNotContain("deploymentId");
    }

    @Test
    void rejectsMissingScopeAndSpoofedDeployment() {
        assertThat(support.allowed(
            context(
                "checkout-regression",
                "commerce-api-prod",
                "action:read_incident_alerts"
            ),
            "read_service_metrics"
        )).isFalse();
        assertThat(support.allowed(
            context(
                "checkout-regression",
                "commerce-api-staging",
                "action:read_service_metrics"
            ),
            "read_service_metrics"
        )).isFalse();
    }

    @Test
    void exposesUnavailableSourceAsStableFailedAction() {
        var result = support.execute(
            context(
                "branch-failure",
                "checkout-api-canary",
                "action:read_recent_deployments"
            ),
            "read_recent_deployments",
            Set.of(IncidentEventType.DEPLOYMENT),
            60,
            6
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode())
            .isEqualTo("INCIDENT_SOURCE_UNAVAILABLE");
        assertThat(support.facts(result)).isEmpty();
    }

    @Test
    void representsAnAuthorizedEmptySourceWithoutInventingEvidence() {
        IncidentEventRepository emptyEvents = mock(
            IncidentEventRepository.class
        );
        when(emptyEvents.findAuthorized(any(), any()))
            .thenReturn(List.of());
        IncidentReadActionSupport emptySupport =
            new IncidentReadActionSupport(
                new IncidentActionScopeResolver(
                    new IncidentScenarioCatalog()
                ),
                emptyEvents,
                new IncidentInvocationMetrics()
            );

        var result = emptySupport.execute(
            context(
                "checkout-regression",
                "commerce-api-prod",
                "action:read_service_metrics"
            ),
            "read_service_metrics",
            Set.of(IncidentEventType.SERVICE_METRIC),
            60,
            6
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(emptySupport.facts(result))
            .containsEntry("candidateEventCount", 0)
            .containsEntry("candidateEventIds", List.of())
            .containsEntry("events", List.of());
    }

    private ActionContext context(
        String incidentId,
        String deploymentId,
        String... scopes
    ) {
        ActionContext context = mock(ActionContext.class);
        when(context.authContext()).thenReturn(
            AIAccessSubjectContext.builder()
                .subjectId(incidentId)
                .subjectType("INCIDENT")
                .authMode("TRUSTED_APPLICATION")
                .callerType("SERVICE")
                .tenantId("public-demo")
                .deploymentId(deploymentId)
                .grantedScopes(java.util.List.of(scopes))
                .build()
        );
        return context;
    }
}
