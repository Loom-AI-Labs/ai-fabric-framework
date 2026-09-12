package com.ai.fabric.realapps.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.fabric.realapps.incident.domain.AuthorizedIncidentScope;
import com.ai.fabric.realapps.incident.domain.IncidentEventQuery;
import com.ai.fabric.realapps.incident.domain.IncidentEventType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryIncidentEventRepositoryTest {

    private final InMemoryIncidentEventRepository repository =
        new InMemoryIncidentEventRepository();
    private final AuthorizedIncidentScope checkout =
        new AuthorizedIncidentScope(
            "public-demo",
            "checkout-regression",
            "commerce-api-prod",
            "incident-rev-checkout-7"
        );

    @Test
    void filtersTenantDeploymentRevisionWindowAndLimitBeforeReturningEvents() {
        var events = repository.findAuthorized(
            new IncidentEventQuery(
                Set.of(
                    IncidentEventType.SERVICE_METRIC,
                    IncidentEventType.DEPENDENCY_SIGNAL
                ),
                60,
                3,
                "read_service_metrics"
            ),
            checkout
        );

        assertThat(events)
            .extracting(event -> event.id())
            .containsExactly(
                "health-checkout-errors",
                "health-checkout-p95",
                "dependency-payment-latency"
            )
            .doesNotContain(
                "health-checkout-old-spike",
                "other-tenant-critical-error",
                "nearby-deployment-failure",
                "wrong-revision-release"
            );
        assertThat(events).allSatisfy(event -> {
            assertThat(event.tenantId()).isEqualTo("public-demo");
            assertThat(event.deploymentId()).isEqualTo("commerce-api-prod");
            assertThat(event.sourceRevision())
                .isEqualTo("incident-rev-checkout-7");
        });
    }

    @Test
    void reportsOnlyTheCountOfRecordsOutsideTheTrustedBoundary() {
        assertThat(repository.previewAuthorized(checkout)).hasSize(12);
        assertThat(repository.countOutsideBoundary(checkout))
            .isEqualTo(repository.totalCount() - 12);
    }

    @Test
    void successfulScenariosExposeEnoughMixedCandidatesForRealSelection() {
        assertThat(repository.previewAuthorized(scope(
            "inventory-pressure",
            "inventory-api-prod",
            "incident-rev-inventory-3"
        ))).hasSizeGreaterThanOrEqualTo(10);
        assertThat(repository.previewAuthorized(scope(
            "no-material-change",
            "search-api-prod",
            "incident-rev-search-11"
        ))).hasSizeGreaterThanOrEqualTo(10);
        assertThat(repository.previewAuthorized(scope(
            "ambiguous-symptom",
            "orders-api-prod",
            "incident-rev-orders-4"
        ))).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void requiredUnavailableSourceFailsInsteadOfReturningFixtureData() {
        AuthorizedIncidentScope failure = new AuthorizedIncidentScope(
            "public-demo",
            "branch-failure",
            "checkout-api-canary",
            "incident-rev-failure-2"
        );

        assertThatThrownBy(() -> repository.findAuthorized(
            new IncidentEventQuery(
                Set.of(IncidentEventType.DEPLOYMENT),
                60,
                6,
                "read_recent_deployments"
            ),
            failure
        )).isInstanceOf(IncidentEventSourceUnavailableException.class)
            .hasMessageContaining("read_recent_deployments");
    }

    @Test
    void rejectsModelRequestedBoundsOutsideApplicationLimits() {
        assertThatThrownBy(() -> new IncidentEventQuery(
            Set.of(IncidentEventType.ALERT),
            181,
            6,
            "read_incident_alerts"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("windowMinutes");
        assertThatThrownBy(() -> new IncidentEventQuery(
            Set.of(IncidentEventType.ALERT),
            30,
            7,
            "read_incident_alerts"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit");
    }

    private AuthorizedIncidentScope scope(
        String incident,
        String deployment,
        String revision
    ) {
        return new AuthorizedIncidentScope(
            "public-demo",
            incident,
            deployment,
            revision
        );
    }
}
