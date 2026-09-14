package com.ai.fabric.realapps.incident.config;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import com.ai.fabric.examples.smoke.SmokeAiProvider;
import com.ai.fabric.realapps.incident.service.IncidentInvocationMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Deterministic structured-output fixture used only by the local smoke profile. */
final class IncidentSmokeAiProvider extends SmokeAiProvider {

    static final String NAME = "incident-smoke";
    private final IncidentInvocationMetrics metrics;
    private final ObjectMapper objectMapper;

    IncidentSmokeAiProvider(
        IncidentInvocationMetrics metrics,
        ObjectMapper objectMapper
    ) {
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return NAME;
    }

    @Override
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        metrics.recordModelCall();
        String input = combinedInput(request).toLowerCase(Locale.ROOT);
        String content;
        if (isIntentExtraction(request)) {
            content = intentResponse(input);
        } else if (input.contains("you plan bounded live-information resolution")) {
            content = readActionPlannerResponse(input);
        } else if (input.contains("read-only service-health investigator")) {
            content = serviceHealthV2Response(input);
        } else if (input.contains("read-only change-risk investigator")) {
            content = changeRiskV2Response(input);
        } else if (input.contains("read-only service-health specialist")) {
            content = serviceHealthResponse(input);
        } else if (input.contains("read-only change-risk specialist")) {
            content = changeRiskResponse(input);
        } else if (input.contains("classify only the user's question")
            || input.contains("select zero or one exact approved read-only")) {
            content = routingResponse(input, requestInput(request));
        } else if (input.contains("own one bounded incident investigation")) {
            content = chainManagerResponse(request, requestInput(request));
        } else if (input.contains("own one incident-investigation turn")) {
            content = managerResponse(input, requestInput(request));
        } else {
            content = "{\"type\":\"COMPLETE\",\"targetSpecialist\":null,"
                + "\"message\":\"No deterministic incident fixture matched this request.\","
                + "\"reason\":\"Unsupported smoke-profile request\"}";
        }
        return AIGenerationResponse.builder()
            .id("incident-smoke-" + UUID.randomUUID())
            .requestId(request == null ? null : request.getEntityId())
            .content(content)
            .model(NAME)
            .tokensUsed(0)
            .confidence(1.0)
            .processingTimeMs(0L)
            .generatedAt(LocalDateTime.now())
            .status("OK")
            .build();
    }

    private boolean isIntentExtraction(AIGenerationRequest request) {
        return request != null
            && "intent_extraction".equals(request.getGenerationType());
    }

    private String intentResponse(String input) {
        boolean changeRisk = input.contains("read_recent_deployments")
            || input.contains("read_change_approvals");
        String retrievalFields = changeRisk
            ? "\"vectorSpace\":\"incident-runbook\",\"requiresRetrieval\":true,"
            : "\"requiresRetrieval\":false,";
        return "{\"intents\":[{\"type\":\"INFORMATION\","
            + "\"intent\":\"investigate_incident\",\"confidence\":1.0,"
            + retrievalFields
            + "\"requiresGeneration\":true,\"responseProfile\":\"STANDARD\","
            + "\"requiresTargetResolution\":false,\"needsAdvancedRAG\":false,"
            + "\"optimizedQuery\":\"incident health change risk evidence\"}],"
            + "\"orchestrationStrategy\":\"RETRIEVE_AND_GENERATE\"}";
    }

    @Override
    public ProviderStatus getStatus() {
        return ProviderStatus.builder()
            .providerName(NAME)
            .available(true)
            .healthy(true)
            .successRate(1.0)
            .averageResponseTime(0.0)
            .lastUpdated(LocalDateTime.now())
            .details("incident structured-output fixture (smoke profile only)")
            .build();
    }

    @Override
    public ProviderConfig getConfig() {
        return ProviderConfig.builder()
            .providerName(NAME)
            .enabled(true)
            .apiKey("local-smoke-key")
            .baseUrl("smoke://incident")
            .defaultModel(NAME)
            .timeoutSeconds(1)
            .maxRetries(0)
            .build();
    }

    private String serviceHealthResponse(String input) {
        String evidenceId = input.contains("health-inventory-timeouts")
            ? "health-inventory-timeouts"
            : input.contains("health-canary-errors")
                ? "health-canary-errors"
                : "health-checkout-p95";
        return "{\"healthStatus\":\"DEGRADED\",\"severity\":\"HIGH\","
            + "\"summary\":\"Approved service evidence shows a material regression.\","
            + "\"evidenceIds\":[\"" + evidenceId + "\"]}";
    }

    private String changeRiskResponse(String input) {
        String evidenceId = input.contains("change-inventory-query-91")
            ? "change-inventory-query-91"
            : input.contains("change-feed-unavailable")
                ? "change-feed-unavailable"
                : "change-payment-client-284";
        String suspectedChange = input.contains("inventory")
            ? "release 91 stock-allocation query"
            : input.contains("change-feed-unavailable")
                ? "unavailable approved change feed"
                : "payment client release 2026.08.03.284";
        return "{\"riskLevel\":\"HIGH\",\"suspectedChange\":\""
            + suspectedChange + "\","
            + "\"summary\":\"The cited recent change is temporally relevant and requires runbook validation.\","
            + "\"evidenceIds\":[\"" + evidenceId + "\"]}";
    }

    private String routingResponse(String fullInput, String requestInput) {
        boolean change = containsAny(
            requestInput,
            "release",
            "rollback",
            "runbook",
            "change risk"
        );
        String version = fullInput.contains("change-risk-reader@2") ? "2" : "1";
        String target = change
            ? "change-risk-reader@" + version
            : "service-health-reader@" + version;
        return "{\"decision\":\"ROUTE\",\"targetSpecialist\":\""
            + target + "\",\"reason\":\"The request matches one approved read-only incident branch.\"}";
    }

    private String managerResponse(String fullInput, String requestInput) {
        if (containsAny(
            requestInput,
            "both",
            "full investigation",
            "investigate incident"
        )) {
            return "{\"type\":\"COMPLETE\",\"targetSpecialist\":null,"
                + "\"message\":\"Run the registered full incident investigation plan for both evidence branches.\","
                + "\"reason\":\"The request requires both independent readers.\"}";
        }
        boolean change = containsAny(
            requestInput,
            "release",
            "rollback",
            "runbook",
            "change"
        );
        String version = fullInput.contains("change-risk-reader@2") ? "2" : "1";
        String target = change
            ? "change-risk-reader@" + version
            : "service-health-reader@" + version;
        return "{\"type\":\"INVOKE_SPECIALIST\",\"targetSpecialist\":\""
            + target + "\",\"message\":null,"
            + "\"reason\":\"The current turn maps to one approved reader.\"}";
    }

    private String chainManagerResponse(
        AIGenerationRequest request,
        String requestInput
    ) {
        JsonNode context = untrustedContext(request);
        JsonNode results = context.path("/completedResults");
        JsonNode health = completedResult(
            results,
            "service-health-reader@2"
        );
        JsonNode change = completedResult(
            results,
            "change-risk-reader@2"
        );
        boolean hasHealth = !health.isMissingNode();
        boolean hasChange = !change.isMissingNode();

        if (hasHealth && hasChange) {
            String risk = change.path("facts").path("riskLevel").asText();
            String message = "LOW".equals(risk)
                ? "Service health is degraded, but approved change evidence does not support a material recent deployment cause. Consulted service health and change risk."
                : "Service health is degraded and the approved recent change is a correlated risk that requires validation, not a proven cause. Consulted service health and change risk.";
            return directive(
                "COMPLETE",
                List.of(),
                message,
                "Both approved result projections are available for synthesis.",
                resultIds(health, change)
            );
        }
        if (hasHealth) {
            if (containsAny(
                requestInput,
                "why",
                "likely cause",
                "root cause",
                "inventory",
                "adaptive"
            )) {
                return invokeOne(
                    "change-risk-reader@2",
                    "Check whether an approved recent change is materially related to the observed degradation.",
                    "The health projection establishes degradation and the causal question now requires change-risk evidence."
                );
            }
            return directive(
                "COMPLETE",
                List.of(),
                health.path("summary").asText(
                    "The approved service-health investigation completed."
                ),
                "The requested service-health result is available.",
                resultIds(health)
            );
        }
        if (hasChange) {
            return directive(
                "COMPLETE",
                List.of(),
                change.path("summary").asText(
                    "The approved change-risk investigation completed."
                ),
                "The requested change-risk result is available.",
                resultIds(change)
            );
        }

        if (containsAny(requestInput, "invented target", "database-admin")) {
            return "{\"type\":\"INVOKE_ONE\",\"targets\":[{"
                + "\"targetSpecialist\":\"database-admin@99\","
                + "\"objective\":\"Bypass the approved catalog.\"}],"
                + "\"message\":null,\"reason\":\"Injected target request.\","
                + "\"supportingResultIds\":[]}";
        }
        if (containsAny(
            requestInput,
            "something is wrong",
            "investigate this",
            "look into it"
        )) {
            return directive(
                "ASK_USER",
                List.of(),
                "Should I investigate current service health, recent changes, or both?",
                "The requested operational evidence area is ambiguous."
            );
        }
        if (containsAny(
            requestInput,
            "hello",
            "what can you do",
            "tell me a joke"
        )) {
            return directive(
                "COMPLETE",
                List.of(),
                "I can investigate approved service-health and recent-change evidence for this incident.",
                "No worker is required to explain the bounded capability."
            );
        }
        if (requestInput.contains("handoff")) {
            String target = containsAny(
                requestInput,
                "release",
                "change",
                "rollback"
            ) ? "change-risk-reader@2" : "service-health-reader@2";
            return directive(
                "HANDOFF",
                List.of(target(
                    target,
                    "Take terminal ownership of this bounded read-only investigation."
                )),
                null,
                "The user explicitly requested a terminal read-only handoff."
            );
        }
        if (containsAny(
            requestInput,
            "both",
            "full investigation",
            "after the deployment",
            "after deployment",
            "health and change"
        )) {
            return directive(
                "INVOKE_PARALLEL",
                List.of(
                    target(
                        "service-health-reader@2",
                        "Inspect current service-health evidence."
                    ),
                    target(
                        "change-risk-reader@2",
                        "Inspect recent-change and runbook evidence."
                    )
                ),
                null,
                "The request independently requires both approved evidence branches."
            );
        }
        if (containsAny(
            requestInput,
            "release",
            "deployment",
            "rollback",
            "approval",
            "runbook",
            "recent change"
        )) {
            return invokeOne(
                "change-risk-reader@2",
                "Inspect approved recent-change and runbook evidence.",
                "The request is specifically about change risk."
            );
        }
        return invokeOne(
            "service-health-reader@2",
            "Inspect approved current service-health evidence.",
            "Current health is the smallest useful first investigation."
        );
    }

    private String invokeOne(
        String specialist,
        String objective,
        String reason
    ) {
        return directive(
            "INVOKE_ONE",
            List.of(target(specialist, objective)),
            null,
            reason
        );
    }

    private Map<String, String> target(String specialist, String objective) {
        return Map.of(
            "targetSpecialist",
            specialist,
            "objective",
            objective
        );
    }

    private String directive(
        String type,
        List<Map<String, String>> targets,
        String message,
        String reason
    ) {
        return directive(type, targets, message, reason, List.of());
    }

    private String directive(
        String type,
        List<Map<String, String>> targets,
        String message,
        String reason,
        List<String> supportingResultIds
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("type", type);
        value.put("targets", targets);
        value.put("message", message);
        value.put("reason", reason);
        value.put("supportingResultIds", supportingResultIds);
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Could not encode incident smoke directive",
                exception
            );
        }
    }

    private List<String> resultIds(JsonNode... results) {
        return java.util.Arrays.stream(results)
            .map(result -> result.path("resultId").asText())
            .filter(resultId -> !resultId.isBlank())
            .toList();
    }

    private JsonNode completedResult(JsonNode results, String specialist) {
        if (!results.isArray()) {
            return objectMapper.missingNode();
        }
        for (JsonNode result : results) {
            if (specialist.equals(result.path("specialist").asText())) {
                return result;
            }
        }
        return objectMapper.missingNode();
    }

    private JsonNode untrustedContext(AIGenerationRequest request) {
        String prompt = request == null ? "" : safe(request.getPrompt());
        String marker = "untrusted application json context:";
        int start = prompt.toLowerCase(Locale.ROOT).indexOf(marker);
        if (start < 0) {
            return objectMapper.createObjectNode();
        }
        String json = prompt.substring(start + marker.length()).trim();
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Incident smoke manager received invalid JSON context",
                exception
            );
        }
    }

    private String readActionPlannerResponse(String input) {
        if (input.contains("read_service_metrics")
            || input.contains("read_incident_alerts")) {
            String action = containsAny(
                requestSection(input),
                "alert",
                "error budget"
            ) ? "read_incident_alerts" : "read_service_metrics";
            return planner(action, "EXECUTE_READ_ACTIONS", false);
        }
        if (input.contains("read_recent_deployments")
            || input.contains("read_change_approvals")) {
            boolean priorDeployment = priorEvidenceSection(input)
                .contains("read_recent_deployments");
            String request = requestSection(input);
            boolean asksForDeployment = containsAny(
                request,
                "release",
                "deployment",
                "recent change"
            );
            boolean asksForApproval = containsAny(
                request,
                "approval",
                "rollback"
            );
            String action = priorDeployment
                || asksForApproval && !asksForDeployment
                    ? "read_change_approvals"
                    : "read_recent_deployments";
            return planner(
                action,
                "EXECUTE_READ_ACTIONS_AND_RAG",
                !priorDeployment && "read_recent_deployments".equals(action)
            );
        }
        return "{\"decision\":\"USE_RAG_ONLY\",\"actions\":[],"
            + "\"needsMoreSteps\":false,\"suggestedVectorSpaces\":[]}";
    }

    private String planner(
        String action,
        String decision,
        boolean needsMoreSteps
    ) {
        String vectorSpaces = decision.endsWith("_AND_RAG")
            ? "[\"incident-runbook\"]"
            : "[]";
        return "{\"decision\":\"" + decision + "\",\"actions\":[{"
            + "\"name\":\"" + action + "\",\"params\":{"
            + "\"windowMinutes\":60,\"limit\":6},\"priority\":1}],"
            + "\"needsMoreSteps\":" + needsMoreSteps + ","
            + "\"suggestedVectorSpaces\":" + vectorSpaces + "}";
    }

    private String serviceHealthV2Response(String input) {
        ScenarioFixture fixture = fixture(input);
        boolean alerts = actionEvidenceSection(input).contains(
            "read_action_facts.read_incident_alerts"
        );
        String action = alerts
            ? "read_incident_alerts"
            : "read_service_metrics";
        int count = alerts ? fixture.alertCount() : fixture.metricCount();
        String evidence = alerts
            ? fixture.alertEvidenceJson()
            : fixture.healthEvidenceJson();
        return "{\"healthStatus\":\"DEGRADED\",\"severity\":\""
            + fixture.healthSeverity() + "\","
            + "\"summary\":\"Approved live evidence shows the current service condition without using unrelated events.\","
            + "\"evidenceIds\":" + evidence + ","
            + "\"dataSources\":[{\"action\":\"" + action
            + "\",\"candidateCount\":" + count
            + ",\"groundingUsable\":true}],"
            + "\"candidateEventCount\":" + count + ","
            + "\"selectionReason\":\"The cited current signals directly describe the reported service symptom.\","
            + "\"sourceRevision\":\"" + fixture.sourceRevision() + "\"}";
    }

    private String changeRiskV2Response(String input) {
        ScenarioFixture fixture = fixture(input);
        String actionEvidence = actionEvidenceSection(input);
        boolean deployments = actionEvidence.contains(
            "read_action_facts.read_recent_deployments"
        );
        boolean approvals = actionEvidence.contains(
            "read_action_facts.read_change_approvals"
        );
        StringBuilder sources = new StringBuilder("[");
        int total = 0;
        if (deployments) {
            sources.append("{\"action\":\"read_recent_deployments\","
                + "\"candidateCount\":").append(fixture.deploymentCount())
                .append(",\"groundingUsable\":true}");
            total += fixture.deploymentCount();
        }
        if (approvals) {
            if (sources.length() > 1) {
                sources.append(',');
            }
            sources.append("{\"action\":\"read_change_approvals\","
                + "\"candidateCount\":").append(fixture.approvalCount())
                .append(",\"groundingUsable\":true}");
            total += fixture.approvalCount();
        }
        sources.append(']');
        String eventEvidence = deployments && approvals
            ? fixture.changeEvidenceJson()
            : deployments
                ? deploymentEvidenceJson(fixture.sourceRevision())
                : approvalEvidenceJson(fixture.sourceRevision());
        return "{\"riskLevel\":\"" + fixture.changeRisk() + "\","
            + "\"suspectedChange\":\"" + fixture.suspectedChange() + "\","
            + "\"summary\":\"Live change evidence and scoped runbook guidance were evaluated without treating guidance as proof.\","
            + "\"evidenceIds\":" + eventEvidence + ","
            + "\"runbookEvidenceIds\":[\"" + fixture.runbookId() + "\"],"
            + "\"dataSources\":" + sources + ","
            + "\"candidateEventCount\":" + total + ","
            + "\"selectionReason\":\"The cited change is temporally relevant; causality remains bounded by the live evidence.\","
            + "\"sourceRevision\":\"" + fixture.sourceRevision() + "\"}";
    }

    private String deploymentEvidenceJson(String revision) {
        return switch (revision) {
            case "incident-rev-inventory-3" ->
                "[\"change-inventory-query-91\"]";
            case "incident-rev-search-11" ->
                "[\"change-search-copy-12\"]";
            case "incident-rev-orders-4" ->
                "[\"change-orders-pool-44\"]";
            case "incident-rev-failure-2" ->
                "[\"change-feed-unavailable\"]";
            default -> "[\"change-payment-client-284\"]";
        };
    }

    private String approvalEvidenceJson(String revision) {
        return switch (revision) {
            case "incident-rev-inventory-3" ->
                "[\"approval-inventory-index\"]";
            case "incident-rev-search-11" ->
                "[\"approval-search-none\"]";
            case "incident-rev-orders-4" ->
                "[\"approval-orders-rollback\"]";
            case "incident-rev-failure-2" -> "[]";
            default -> "[\"approval-payment-rollback\"]";
        };
    }

    private ScenarioFixture fixture(String input) {
        if (input.contains("incident-rev-inventory-3")) {
            return new ScenarioFixture(
                "incident-rev-inventory-3", 4, 2, 2, 1,
                "[\"health-inventory-timeouts\",\"db-inventory-pool-pressure\"]",
                "[\"alert-inventory-pool\"]",
                "[\"change-inventory-query-91\",\"approval-inventory-index\"]",
                "runbook-inventory-index", "HIGH",
                "release 91 stock-allocation query", "HIGH"
            );
        }
        if (input.contains("incident-rev-search-11")) {
            return new ScenarioFixture(
                "incident-rev-search-11", 5, 2, 2, 2,
                "[\"health-search-errors\",\"dependency-search-provider\"]",
                "[\"alert-search-dependency\"]",
                "[\"approval-search-none\"]",
                "runbook-search-dependency", "HIGH",
                "no material recent runtime change", "LOW"
            );
        }
        if (input.contains("incident-rev-orders-4")) {
            return new ScenarioFixture(
                "incident-rev-orders-4", 3, 2, 3, 2,
                "[\"health-orders-latency\"]",
                "[\"alert-orders-latency\"]",
                "[\"change-orders-pool-44\",\"approval-orders-rollback\"]",
                "runbook-orders-pool", "MEDIUM",
                "orders connection-pool configuration", "MEDIUM"
            );
        }
        if (input.contains("incident-rev-failure-2")) {
            return new ScenarioFixture(
                "incident-rev-failure-2", 1, 1, 1, 0,
                "[\"health-canary-errors\"]",
                "[\"alert-canary-errors\"]",
                "[\"change-feed-unavailable\"]",
                "runbook-canary-source-failure", "HIGH",
                "unavailable approved change feed", "UNKNOWN"
            );
        }
        return new ScenarioFixture(
            "incident-rev-checkout-7", 5, 2, 2, 2,
            "[\"health-checkout-errors\",\"health-checkout-p95\",\"dependency-payment-latency\"]",
            "[\"alert-checkout-error-budget\"]",
            "[\"change-payment-client-284\",\"approval-payment-rollback\"]",
            "runbook-payment-rollback", "HIGH",
            "payment client release 2026.08.03.284", "HIGH"
        );
    }

    private String requestSection(String input) {
        int start = input.indexOf("request query:");
        int end = input.indexOf("resolved mode:");
        if (start < 0 || end <= start) {
            return input;
        }
        return input.substring(start, end);
    }

    private String priorEvidenceSection(String input) {
        int start = input.indexOf("prior read-action evidence json:");
        int end = input.indexOf("planner budgets:");
        if (start < 0 || end <= start) {
            return "";
        }
        return input.substring(start, end);
    }

    private String actionEvidenceSection(String input) {
        int start = input.indexOf("approved orchestration grounding");
        if (start < 0) {
            return "";
        }
        int end = input.indexOf("application output contract", start);
        return end > start
            ? input.substring(start, end)
            : input.substring(start);
    }

    private record ScenarioFixture(
        String sourceRevision,
        int metricCount,
        int alertCount,
        int deploymentCount,
        int approvalCount,
        String healthEvidenceJson,
        String alertEvidenceJson,
        String changeEvidenceJson,
        String runbookId,
        String healthSeverity,
        String suspectedChange,
        String changeRisk
    ) {}

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String combinedInput(AIGenerationRequest request) {
        if (request == null) {
            return "";
        }
        return String.join(
            "\n",
            safe(request.getSystemPrompt()),
            safe(request.getPrompt()),
            safe(request.getContext()),
            String.valueOf(request.getParameters())
        );
    }

    private String requestInput(AIGenerationRequest request) {
        if (request == null) {
            return "";
        }
        String prompt = safe(request.getPrompt());
        String normalized = prompt.toLowerCase(Locale.ROOT);
        String marker = "application question:";
        int start = normalized.lastIndexOf(marker);
        if (start >= 0) {
            prompt = prompt.substring(start + marker.length()).trim();
            String contextMarker = "untrusted application json context:";
            int contextStart = prompt.toLowerCase(Locale.ROOT)
                .indexOf(contextMarker);
            if (contextStart >= 0) {
                prompt = prompt.substring(0, contextStart).trim();
            }
        }
        return prompt.toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
