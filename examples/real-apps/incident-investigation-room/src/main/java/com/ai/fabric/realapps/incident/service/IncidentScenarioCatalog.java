package com.ai.fabric.realapps.incident.service;

import com.ai.fabric.realapps.incident.domain.IncidentEvidence;
import com.ai.fabric.realapps.incident.domain.IncidentScenario;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class IncidentScenarioCatalog {

    private final Map<String, IncidentScenario> scenarios;

    public IncidentScenarioCatalog() {
        Instant base = Instant.parse("2026-08-03T18:00:00Z");
        Map<String, IncidentScenario> configured = new LinkedHashMap<>();
        configured.put("checkout-regression", new IncidentScenario(
            "checkout-regression",
            "Checkout latency after release",
            "commerce-api-prod",
            "incident-rev-checkout-7",
            "Checkout latency and error rate increased immediately after a payment-client release.",
            List.of(
                new IncidentEvidence(
                    "health-checkout-p95",
                    "SERVICE_HEALTH",
                    "Checkout p95 latency rose from 420 ms to 2.8 seconds in the last 20 minutes.",
                    "HIGH",
                    base.plusSeconds(1200)
                ),
                new IncidentEvidence(
                    "health-checkout-errors",
                    "SERVICE_HEALTH",
                    "Checkout HTTP 5xx rate rose from 0.2 percent to 6.4 percent while catalog remained healthy.",
                    "HIGH",
                    base.plusSeconds(1260)
                )
            ),
            List.of(
                new IncidentEvidence(
                    "change-payment-client-284",
                    "RECENT_CHANGE",
                    "Release 2026.08.03.284 upgraded the payment client and completed 12 minutes before the regression.",
                    "HIGH",
                    base.plusSeconds(480)
                ),
                new IncidentEvidence(
                    "runbook-payment-rollback",
                    "RUNBOOK",
                    "The payment-client rollback runbook requires a checkout canary before and after rollback.",
                    "MEDIUM",
                    base.minusSeconds(86400)
                )
            ),
            null
        ));
        configured.put("inventory-pressure", new IncidentScenario(
            "inventory-pressure",
            "Inventory saturation",
            "inventory-api-prod",
            "incident-rev-inventory-3",
            "Inventory reads are timing out while database connections approach saturation.",
            List.of(
                new IncidentEvidence(
                    "health-inventory-timeouts",
                    "SERVICE_HEALTH",
                    "Inventory read timeout rate is 8.1 percent and connection-pool utilization is 96 percent.",
                    "HIGH",
                    base.plusSeconds(1800)
                ),
                new IncidentEvidence(
                    "health-inventory-cpu",
                    "SERVICE_HEALTH",
                    "Application CPU remains below 45 percent, reducing the likelihood of compute saturation.",
                    "LOW",
                    base.plusSeconds(1810)
                )
            ),
            List.of(
                new IncidentEvidence(
                    "change-inventory-query-91",
                    "RECENT_CHANGE",
                    "Release 91 added a stock-allocation query without a supporting composite index.",
                    "HIGH",
                    base.plusSeconds(600)
                ),
                new IncidentEvidence(
                    "runbook-inventory-index",
                    "RUNBOOK",
                    "The inventory runbook recommends query-plan capture before applying the approved composite index.",
                    "MEDIUM",
                    base.minusSeconds(43200)
                )
            ),
            null
        ));
        configured.put("no-material-change", new IncidentScenario(
            "no-material-change",
            "Degraded health without a material change",
            "search-api-prod",
            "incident-rev-search-11",
            "Search is degraded by an external dependency, while the approved change feed contains no supported runtime cause.",
            List.of(
                new IncidentEvidence(
                    "health-search-errors",
                    "SERVICE_HEALTH",
                    "Search error rate is elevated to 3.2 percent.",
                    "MEDIUM",
                    base.plusSeconds(3000)
                ),
                new IncidentEvidence(
                    "dependency-search-provider",
                    "DEPENDENCY_SIGNAL",
                    "The external search provider is intermittently timing out.",
                    "HIGH",
                    base.plusSeconds(2970)
                )
            ),
            List.of(new IncidentEvidence(
                "approval-search-none",
                "CHANGE_APPROVAL",
                "No material runtime change is approved or recorded in the incident window.",
                "LOW",
                base.plusSeconds(2050)
            )),
            null
        ));
        configured.put("ambiguous-symptom", new IncidentScenario(
            "ambiguous-symptom",
            "Ambiguous orders latency",
            "orders-api-prod",
            "incident-rev-orders-4",
            "The first report says only that orders are slow, allowing the conversation manager to clarify or route a specific follow-up.",
            List.of(new IncidentEvidence(
                "health-orders-latency",
                "SERVICE_HEALTH",
                "Orders p95 latency is elevated while throughput remains stable.",
                "MEDIUM",
                base.plusSeconds(3600)
            )),
            List.of(new IncidentEvidence(
                "change-orders-pool-44",
                "CONFIGURATION_CHANGE",
                "Connection-pool sizing changed 25 minutes before the latency warning.",
                "MEDIUM",
                base.plusSeconds(3300)
            )),
            null
        ));
        configured.put("branch-failure", new IncidentScenario(
            "branch-failure",
            "Change evidence unavailable",
            "checkout-api-canary",
            "incident-rev-failure-2",
            "A controlled canary proving that ALL_REQUIRED returns no partial incident assessment.",
            List.of(new IncidentEvidence(
                "health-canary-errors",
                "SERVICE_HEALTH",
                "Canary error rate is above its deployment threshold.",
                "HIGH",
                base.plusSeconds(2400)
            )),
            List.of(new IncidentEvidence(
                "change-feed-unavailable",
                "RECENT_CHANGE",
                "The approved change feed is intentionally unavailable for this canary.",
                "UNKNOWN",
                base.plusSeconds(2390)
            )),
            "change-risk"
        ));
        scenarios = Map.copyOf(configured);
    }

    public List<IncidentScenario> all() {
        return List.copyOf(scenarios.values());
    }

    public IncidentScenario require(String id) {
        IncidentScenario scenario = scenarios.get(normalize(id));
        if (scenario == null) {
            throw new IllegalArgumentException("Unknown incident scenario");
        }
        return scenario;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "checkout-regression";
        }
        return value.trim();
    }
}
