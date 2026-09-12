package com.ai.fabric.realapps.incident.service;

import com.ai.fabric.realapps.incident.domain.AuthorizedIncidentScope;
import com.ai.fabric.realapps.incident.domain.IncidentEvent;
import com.ai.fabric.realapps.incident.domain.IncidentEventQuery;
import com.ai.fabric.realapps.incident.domain.IncidentEventType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryIncidentEventRepository
    implements IncidentEventRepository {

    private static final String PUBLIC_TENANT = "public-demo";

    private final List<IncidentEvent> events;

    public InMemoryIncidentEventRepository() {
        Instant base = Instant.parse("2026-08-03T18:00:00Z");
        List<IncidentEvent> configured = new ArrayList<>();

        addCheckoutEvents(configured, base);
        addInventoryEvents(configured, base);
        addNoChangeEvents(configured, base);
        addAmbiguousEvents(configured, base);
        addFailureEvents(configured, base);
        configured.add(event(
            "other-tenant-critical-error",
            "private-tenant",
            "checkout-regression",
            "commerce-api-prod",
            "incident-rev-checkout-7",
            IncidentEventType.ALERT,
            "alert-manager",
            "A different tenant has a critical checkout alert that must never cross the boundary.",
            "CRITICAL",
            base.plusSeconds(1680)
        ));
        configured.add(event(
            "nearby-deployment-failure",
            PUBLIC_TENANT,
            "checkout-regression",
            "commerce-api-staging",
            "incident-rev-checkout-7",
            IncidentEventType.ALERT,
            "alert-manager",
            "A staging deployment is failing independently of the production incident.",
            "CRITICAL",
            base.plusSeconds(1660)
        ));
        configured.add(event(
            "wrong-revision-release",
            PUBLIC_TENANT,
            "checkout-regression",
            "commerce-api-prod",
            "incident-rev-checkout-6",
            IncidentEventType.DEPLOYMENT,
            "deployment-ledger",
            "An earlier source revision deployed a catalogue client change.",
            "MEDIUM",
            base.plusSeconds(1600)
        ));
        events = List.copyOf(configured);
    }

    @Override
    public List<IncidentEvent> findAuthorized(
        IncidentEventQuery query,
        AuthorizedIncidentScope scope
    ) {
        if (sourceUnavailable(scope, query.sourceName())) {
            throw new IncidentEventSourceUnavailableException(
                query.sourceName()
            );
        }
        List<IncidentEvent> bounded = matchingBoundary(scope);
        Instant anchor = bounded.stream()
            .map(IncidentEvent::observedAt)
            .max(Comparator.naturalOrder())
            .orElseThrow(() -> new IllegalArgumentException(
                "No incident events exist inside the authorized boundary"
            ));
        Instant earliest = anchor.minus(query.windowMinutes(), ChronoUnit.MINUTES);
        return bounded.stream()
            .filter(event -> query.eventTypes().contains(event.type()))
            .filter(event -> !event.observedAt().isBefore(earliest))
            .sorted(Comparator.comparing(IncidentEvent::observedAt).reversed()
                .thenComparing(IncidentEvent::id))
            .limit(query.limit())
            .toList();
    }

    @Override
    public List<IncidentEvent> previewAuthorized(
        AuthorizedIncidentScope scope
    ) {
        return matchingBoundary(scope).stream()
            .sorted(Comparator.comparing(IncidentEvent::observedAt).reversed()
                .thenComparing(IncidentEvent::id))
            .toList();
    }

    @Override
    public int countOutsideBoundary(AuthorizedIncidentScope scope) {
        return Math.toIntExact(events.size() - matchingBoundary(scope).size());
    }

    @Override
    public int totalCount() {
        return events.size();
    }

    private List<IncidentEvent> matchingBoundary(
        AuthorizedIncidentScope scope
    ) {
        return events.stream()
            .filter(event -> event.tenantId().equals(scope.tenantId()))
            .filter(event -> event.incidentId().equals(scope.incidentId()))
            .filter(event -> event.deploymentId().equals(scope.deploymentId()))
            .filter(event -> event.sourceRevision().equals(
                scope.sourceRevision()
            ))
            .toList();
    }

    private boolean sourceUnavailable(
        AuthorizedIncidentScope scope,
        String sourceName
    ) {
        return "branch-failure".equals(scope.incidentId())
            && ("read_recent_deployments".equals(sourceName)
                || "read_change_approvals".equals(sourceName));
    }

    private void addCheckoutEvents(List<IncidentEvent> out, Instant base) {
        String incident = "checkout-regression";
        String deployment = "commerce-api-prod";
        String revision = "incident-rev-checkout-7";
        out.add(event("health-checkout-errors", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "Checkout HTTP 5xx rate rose from 0.2 percent to 6.4 percent while catalogue remained healthy.", "HIGH", base.plusSeconds(1740)));
        out.add(event("health-checkout-p95", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "Checkout p95 latency rose from 420 ms to 2.8 seconds in the last 20 minutes.", "HIGH", base.plusSeconds(1680)));
        out.add(event("dependency-payment-latency", incident, deployment, revision,
            IncidentEventType.DEPENDENCY_SIGNAL, "service-map", "Payment-client upstream latency rose at the same time as checkout latency.", "HIGH", base.plusSeconds(1650)));
        out.add(event("health-catalog-normal", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "Catalogue latency and error rate remain inside normal thresholds.", "LOW", base.plusSeconds(1620)));
        out.add(event("health-checkout-cpu-normal", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "Checkout CPU remains below 48 percent despite the latency increase.", "LOW", base.plusSeconds(1590)));
        out.add(event("alert-checkout-error-budget", incident, deployment, revision,
            IncidentEventType.ALERT, "alert-manager", "Checkout error-budget burn alert is firing at 9.4 times the sustainable rate.", "HIGH", base.plusSeconds(1720)));
        out.add(event("alert-inventory-recovered", incident, deployment, revision,
            IncidentEventType.ALERT, "alert-manager", "A brief inventory warning recovered before the checkout regression began.", "LOW", base.plusSeconds(900)));
        out.add(event("change-payment-client-284", incident, deployment, revision,
            IncidentEventType.DEPLOYMENT, "deployment-ledger", "Release 2026.08.03.284 upgraded the payment client and completed 12 minutes before the regression.", "HIGH", base.plusSeconds(960)));
        out.add(event("change-catalog-cache-77", incident, deployment, revision,
            IncidentEventType.CONFIGURATION_CHANGE, "deployment-ledger", "Catalogue cache TTL changed two hours before the incident with no correlated catalogue degradation.", "LOW", base.plusSeconds(300)));
        out.add(event("approval-payment-rollback", incident, deployment, revision,
            IncidentEventType.CHANGE_APPROVAL, "change-control", "Payment-client release 284 has an approved rollback path requiring checkout canaries.", "MEDIUM", base.plusSeconds(930)));
        out.add(event("approval-catalog-observation", incident, deployment, revision,
            IncidentEventType.CHANGE_APPROVAL, "change-control", "Catalogue cache change is observation-only and has no rollback recommendation.", "LOW", base.plusSeconds(280)));
        out.add(event("health-checkout-old-spike", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "A short checkout latency spike occurred six hours earlier and recovered without intervention.", "LOW", base.minusSeconds(21600)));
    }

    private void addInventoryEvents(List<IncidentEvent> out, Instant base) {
        String incident = "inventory-pressure";
        String deployment = "inventory-api-prod";
        String revision = "incident-rev-inventory-3";
        out.add(event("health-inventory-timeouts", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "Inventory read timeout rate is 8.1 percent.", "HIGH", base.plusSeconds(2340)));
        out.add(event("db-inventory-pool-pressure", incident, deployment, revision,
            IncidentEventType.DATABASE_SIGNAL, "database-monitor", "Inventory connection-pool utilization reached 96 percent with queued borrowers.", "HIGH", base.plusSeconds(2310)));
        out.add(event("health-inventory-cpu", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "Application CPU remains below 45 percent, reducing the likelihood of compute saturation.", "LOW", base.plusSeconds(2280)));
        out.add(event("dependency-pricing-normal", incident, deployment, revision,
            IncidentEventType.DEPENDENCY_SIGNAL, "service-map", "Pricing dependency latency remains within its normal range.", "LOW", base.plusSeconds(2250)));
        out.add(event("alert-inventory-pool", incident, deployment, revision,
            IncidentEventType.ALERT, "alert-manager", "Inventory connection-pool saturation alert is firing.", "HIGH", base.plusSeconds(2320)));
        out.add(event("alert-inventory-cpu-clear", incident, deployment, revision,
            IncidentEventType.ALERT, "alert-manager", "CPU saturation alert is clear.", "LOW", base.plusSeconds(2240)));
        out.add(event("change-inventory-query-91", incident, deployment, revision,
            IncidentEventType.DEPLOYMENT, "deployment-ledger", "Release 91 added a stock-allocation query without a supporting composite index.", "HIGH", base.plusSeconds(1500)));
        out.add(event("change-inventory-banner", incident, deployment, revision,
            IncidentEventType.CONFIGURATION_CHANGE, "deployment-ledger", "A storefront banner flag changed without touching inventory execution paths.", "LOW", base.plusSeconds(1400)));
        out.add(event("approval-inventory-index", incident, deployment, revision,
            IncidentEventType.CHANGE_APPROVAL, "change-control", "Query-plan capture is approved before applying the inventory composite index.", "MEDIUM", base.plusSeconds(1480)));
        out.add(event("health-inventory-old-timeout", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "An isolated timeout occurred five hours before the current incident.", "LOW", base.minusSeconds(18000)));
    }

    private void addNoChangeEvents(List<IncidentEvent> out, Instant base) {
        String incident = "no-material-change";
        String deployment = "search-api-prod";
        String revision = "incident-rev-search-11";
        out.add(event("health-search-errors", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "Search error rate is elevated to 3.2 percent.", "MEDIUM", base.plusSeconds(3000)));
        out.add(event("dependency-search-provider", incident, deployment, revision,
            IncidentEventType.DEPENDENCY_SIGNAL, "service-map", "The external search provider is intermittently timing out.", "HIGH", base.plusSeconds(2970)));
        out.add(event("health-search-cpu-normal", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "Search CPU and memory remain normal.", "LOW", base.plusSeconds(2940)));
        out.add(event("health-search-cache-normal", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "Search cache hit rate remains inside its normal operating range.", "LOW", base.plusSeconds(2910)));
        out.add(event("db-search-pool-normal", incident, deployment, revision,
            IncidentEventType.DATABASE_SIGNAL, "database-monitor", "Search database connection-pool use remains below 40 percent.", "LOW", base.plusSeconds(2880)));
        out.add(event("alert-search-dependency", incident, deployment, revision,
            IncidentEventType.ALERT, "alert-manager", "External dependency timeout alert is firing.", "MEDIUM", base.plusSeconds(2990)));
        out.add(event("alert-search-disk-clear", incident, deployment, revision,
            IncidentEventType.ALERT, "alert-manager", "Search disk-capacity alert is clear and unrelated to the current symptom.", "LOW", base.plusSeconds(2860)));
        out.add(event("change-search-copy-12", incident, deployment, revision,
            IncidentEventType.CONFIGURATION_CHANGE, "deployment-ledger", "A help-text copy change deployed three hours before the incident.", "LOW", base.plusSeconds(2100)));
        out.add(event("change-search-dashboard-8", incident, deployment, revision,
            IncidentEventType.CONFIGURATION_CHANGE, "deployment-ledger", "An operations dashboard label changed without touching the search runtime.", "LOW", base.plusSeconds(2040)));
        out.add(event("approval-search-none", incident, deployment, revision,
            IncidentEventType.CHANGE_APPROVAL, "change-control", "No material runtime change is approved or recorded in the incident window.", "LOW", base.plusSeconds(2050)));
        out.add(event("approval-search-dashboard", incident, deployment, revision,
            IncidentEventType.CHANGE_APPROVAL, "change-control", "The dashboard label update was approved as a non-runtime change.", "LOW", base.plusSeconds(2020)));
    }

    private void addAmbiguousEvents(List<IncidentEvent> out, Instant base) {
        String incident = "ambiguous-symptom";
        String deployment = "orders-api-prod";
        String revision = "incident-rev-orders-4";
        out.add(event("health-orders-latency", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "Orders p95 latency is elevated while throughput remains stable.", "MEDIUM", base.plusSeconds(3600)));
        out.add(event("db-orders-wait", incident, deployment, revision,
            IncidentEventType.DATABASE_SIGNAL, "database-monitor", "Orders database borrower wait time rose after the pool-size change.", "MEDIUM", base.plusSeconds(3570)));
        out.add(event("dependency-orders-payment-normal", incident, deployment, revision,
            IncidentEventType.DEPENDENCY_SIGNAL, "service-map", "Payment dependency latency remains normal during the orders slowdown.", "LOW", base.plusSeconds(3520)));
        out.add(event("alert-orders-latency", incident, deployment, revision,
            IncidentEventType.ALERT, "alert-manager", "Orders latency warning is active, but no error-rate alert is firing.", "MEDIUM", base.plusSeconds(3580)));
        out.add(event("alert-orders-errors-clear", incident, deployment, revision,
            IncidentEventType.ALERT, "alert-manager", "Orders error-rate alert remains clear.", "LOW", base.plusSeconds(3500)));
        out.add(event("change-orders-pool-44", incident, deployment, revision,
            IncidentEventType.CONFIGURATION_CHANGE, "deployment-ledger", "Connection-pool sizing changed 25 minutes before the latency warning.", "MEDIUM", base.plusSeconds(3300)));
        out.add(event("change-orders-copy-18", incident, deployment, revision,
            IncidentEventType.DEPLOYMENT, "deployment-ledger", "Release 18 changed an order-history label without modifying request execution.", "LOW", base.plusSeconds(3200)));
        out.add(event("change-orders-tracing-9", incident, deployment, revision,
            IncidentEventType.CONFIGURATION_CHANGE, "deployment-ledger", "Trace sampling changed from 5 to 10 percent before the incident.", "LOW", base.plusSeconds(3150)));
        out.add(event("approval-orders-rollback", incident, deployment, revision,
            IncidentEventType.CHANGE_APPROVAL, "change-control", "The pool configuration has an approved rollback after one canary check.", "MEDIUM", base.plusSeconds(3280)));
        out.add(event("approval-orders-copy", incident, deployment, revision,
            IncidentEventType.CHANGE_APPROVAL, "change-control", "The order-history label release was approved as presentation-only.", "LOW", base.plusSeconds(3180)));
    }

    private void addFailureEvents(List<IncidentEvent> out, Instant base) {
        String incident = "branch-failure";
        String deployment = "checkout-api-canary";
        String revision = "incident-rev-failure-2";
        out.add(event("health-canary-errors", incident, deployment, revision,
            IncidentEventType.SERVICE_METRIC, "metrics", "Canary error rate is above its deployment threshold.", "HIGH", base.plusSeconds(4200)));
        out.add(event("alert-canary-errors", incident, deployment, revision,
            IncidentEventType.ALERT, "alert-manager", "Canary error alert is firing.", "HIGH", base.plusSeconds(4180)));
        out.add(event("change-feed-unavailable", incident, deployment, revision,
            IncidentEventType.DEPLOYMENT, "deployment-ledger", "The approved change feed is intentionally unavailable for this canary.", "UNKNOWN", base.plusSeconds(4170)));
    }

    private IncidentEvent event(
        String id,
        String incident,
        String deployment,
        String revision,
        IncidentEventType type,
        String source,
        String summary,
        String severity,
        Instant observedAt
    ) {
        return event(id, PUBLIC_TENANT, incident, deployment, revision,
            type, source, summary, severity, observedAt);
    }

    private IncidentEvent event(
        String id,
        String tenant,
        String incident,
        String deployment,
        String revision,
        IncidentEventType type,
        String source,
        String summary,
        String severity,
        Instant observedAt
    ) {
        return new IncidentEvent(
            id,
            tenant,
            incident,
            deployment,
            revision,
            type,
            source,
            summary,
            severity,
            observedAt,
            Map.of()
        );
    }
}
