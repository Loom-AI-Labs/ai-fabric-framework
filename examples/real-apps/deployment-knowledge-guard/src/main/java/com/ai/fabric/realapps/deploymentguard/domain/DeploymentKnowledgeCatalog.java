package com.ai.fabric.realapps.deploymentguard.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DeploymentKnowledgeCatalog {

    private final List<DeploymentContext> contexts = List.of(
        new DeploymentContext("northstar-payments", "northstar", "payments-prod", "Northstar / Payments", "production"),
        new DeploymentContext("northstar-checkout", "northstar", "checkout-edge", "Northstar / Checkout", "production"),
        new DeploymentContext("orbit-payments", "orbit", "payments-prod", "Orbit / Payments", "production"),
        new DeploymentContext("orbit-checkout", "orbit", "checkout-edge", "Orbit / Checkout", "staging")
    );

    private final List<DeploymentKnowledgeDocument> documents = List.of(
        document("northstar", "payments-prod", "status", "Current deployment status",
            "Release 2026.08.03-4 is healthy on all six instances. Error rate is 0.08 percent and payment authorization latency is 184 ms p95."),
        document("northstar", "payments-prod", "incident", "Active incident summary",
            "No active incident. INC-4312 was resolved after rotating the payment gateway certificate. Continue watching TLS handshake failures until 18:00 UTC."),
        document("northstar", "payments-prod", "runbook", "Operator recovery runbook",
            "For payment authorization failures, inspect gateway certificate expiry, compare TLS failures by instance, drain one unhealthy instance, then rerun the authorization canary."),
        document("northstar", "checkout-edge", "status", "Current deployment status",
            "Release 2026.08.02-9 is degraded. Two edge nodes are serving stale cart totals and checkout completion is 96.4 percent."),
        document("northstar", "checkout-edge", "incident", "Active incident summary",
            "INC-4330 is open for stale cart totals after a cache schema rollout. Rollout is paused and affected nodes are isolated."),
        document("northstar", "checkout-edge", "runbook", "Operator recovery runbook",
            "For stale cart totals, stop the cache rollout, isolate nodes on the old schema, invalidate cart-total keys, and run checkout reconciliation before restoring traffic."),
        document("orbit", "payments-prod", "status", "Current deployment status",
            "Release orbit-18.7 is healthy on three instances. Error rate is 0.03 percent and payment authorization latency is 142 ms p95."),
        document("orbit", "payments-prod", "incident", "Active incident summary",
            "No active incident. The most recent alert was a false positive caused by a delayed synthetic probe in the eu-west region."),
        document("orbit", "payments-prod", "runbook", "Operator recovery runbook",
            "For delayed probes, verify regional probe timestamps and queue depth before changing payment capacity. Escalate only when customer traffic shows the same latency."),
        document("orbit", "checkout-edge", "status", "Current deployment status",
            "Release orbit-checkout-42 is in staging validation. Search-to-cart and checkout smoke tests pass, but promotion reconciliation has not completed."),
        document("orbit", "checkout-edge", "incident", "Active incident summary",
            "VAL-882 blocks production promotion because two coupon totals differ from the billing ledger in staging."),
        document("orbit", "checkout-edge", "runbook", "Operator recovery runbook",
            "For coupon reconciliation failures, compare promotion rule revisions, replay the two failed carts in staging, and require a clean ledger diff before promotion." )
    );

    private final Map<String, DeploymentContext> contextsById = indexContexts();
    private final Map<String, DeploymentKnowledgeDocument> documentsById = indexDocuments();

    public List<DeploymentContext> contexts() {
        return contexts;
    }

    public List<DeploymentKnowledgeDocument> documents() {
        return documents;
    }

    public DeploymentContext requireContext(String id) {
        DeploymentContext context = contextsById.get(id);
        if (context == null) {
            throw new IllegalArgumentException("Unknown deployment context");
        }
        return context;
    }

    public DeploymentKnowledgeDocument requireDocument(String id) {
        DeploymentKnowledgeDocument document = documentsById.get(id);
        if (document == null) {
            throw new IllegalArgumentException("Unknown evidence document");
        }
        return document;
    }

    public boolean belongsTo(DeploymentKnowledgeDocument document, DeploymentContext context) {
        return document.tenantId().equals(context.tenantId())
            && document.deploymentId().equals(context.deploymentId());
    }

    public List<DeploymentKnowledgeDocument> documentsFor(DeploymentContext context) {
        return documents.stream().filter(document -> belongsTo(document, context)).toList();
    }

    private DeploymentKnowledgeDocument document(
        String tenant,
        String deployment,
        String sourceType,
        String title,
        String content
    ) {
        String id = tenant + "-" + deployment + "-" + sourceType;
        return new DeploymentKnowledgeDocument(
            id,
            tenant,
            deployment,
            title,
            sourceType,
            title + ". " + content,
            1
        );
    }

    private Map<String, DeploymentContext> indexContexts() {
        Map<String, DeploymentContext> indexed = new LinkedHashMap<>();
        contexts.forEach(context -> indexed.put(context.id(), context));
        return Map.copyOf(indexed);
    }

    private Map<String, DeploymentKnowledgeDocument> indexDocuments() {
        Map<String, DeploymentKnowledgeDocument> indexed = new LinkedHashMap<>();
        documents.forEach(document -> indexed.put(document.id(), document));
        return Map.copyOf(indexed);
    }
}
