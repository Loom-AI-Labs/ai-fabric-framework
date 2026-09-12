package com.ai.fabric.realapps.incident.service;

import com.ai.fabric.realapps.incident.domain.IncidentRunbookDocument;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IncidentRunbookCatalog {

    private final List<IncidentRunbookDocument> documents = List.of(
        document(
            "runbook-payment-rollback",
            "commerce-api-prod",
            "incident-rev-checkout-7",
            "Payment client rollback",
            "For checkout degradation correlated with a payment-client release, compare current latency and errors with the pre-release canary. Roll back only through the approved change record, then repeat checkout canaries before closing the incident."
        ),
        document(
            "runbook-inventory-index",
            "inventory-api-prod",
            "incident-rev-inventory-3",
            "Inventory query saturation",
            "When inventory pool pressure follows a query change, capture the query plan first. Apply the approved composite index only after review, then verify timeout rate and connection-pool utilization."
        ),
        document(
            "runbook-search-dependency",
            "search-api-prod",
            "incident-rev-search-11",
            "External search dependency degradation",
            "When local compute is healthy but an external search dependency times out, use dependency isolation and bounded retries. Do not attribute the incident to an unrelated application change without live change evidence."
        ),
        document(
            "runbook-orders-pool",
            "orders-api-prod",
            "incident-rev-orders-4",
            "Orders connection-pool rollback",
            "For latency after a pool configuration change, inspect current latency and saturation first. If correlation is supported, use the approved rollback and verify one canary before restoring traffic."
        ),
        document(
            "runbook-canary-source-failure",
            "checkout-api-canary",
            "incident-rev-failure-2",
            "Required source failure",
            "If a required authoritative change source is unavailable, stop the full assessment and report the source failure. Never replace missing live change data with a guessed cause."
        ),
        new IncidentRunbookDocument(
            "runbook-private-tenant",
            "private-tenant",
            "commerce-api-prod",
            "incident-rev-checkout-7",
            "Private tenant emergency runbook",
            "This private-tenant runbook must never be retrieved by the public demo."
        )
    );

    public List<IncidentRunbookDocument> documents() {
        return documents;
    }

    private IncidentRunbookDocument document(
        String id,
        String deploymentId,
        String sourceRevision,
        String title,
        String content
    ) {
        return new IncidentRunbookDocument(
            id,
            "public-demo",
            deploymentId,
            sourceRevision,
            title,
            content
        );
    }
}
