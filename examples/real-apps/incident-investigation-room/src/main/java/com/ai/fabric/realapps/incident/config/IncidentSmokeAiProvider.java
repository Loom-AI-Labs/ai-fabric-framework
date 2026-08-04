package com.ai.fabric.realapps.incident.config;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import com.ai.fabric.examples.smoke.SmokeAiProvider;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/** Deterministic structured-output fixture used only by the local smoke profile. */
final class IncidentSmokeAiProvider extends SmokeAiProvider {

    static final String NAME = "incident-smoke";

    @Override
    public String getProviderName() {
        return NAME;
    }

    @Override
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        String input = combinedInput(request).toLowerCase(Locale.ROOT);
        String content;
        if (input.contains("read-only service-health specialist")) {
            content = serviceHealthResponse(input);
        } else if (input.contains("read-only change-risk specialist")) {
            content = changeRiskResponse(input);
        } else if (input.contains("select zero or one exact approved read-only incident specialist")) {
            content = routingResponse(requestInput(request));
        } else if (input.contains("own one incident-investigation conversation turn")) {
            content = managerResponse(requestInput(request));
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

    private String routingResponse(String input) {
        boolean change = containsAny(input, "release", "rollback", "runbook", "change risk");
        String target = change ? "change-risk-reader@1" : "service-health-reader@1";
        return "{\"decision\":\"ROUTE\",\"targetSpecialist\":\""
            + target + "\",\"reason\":\"The request matches one approved read-only incident branch.\"}";
    }

    private String managerResponse(String input) {
        if (containsAny(input, "both", "full investigation", "investigate incident")) {
            return "{\"type\":\"COMPLETE\",\"targetSpecialist\":null,"
                + "\"message\":\"Run the registered full incident investigation plan for both evidence branches.\","
                + "\"reason\":\"The request requires both independent readers.\"}";
        }
        boolean change = containsAny(input, "release", "rollback", "runbook", "change");
        String target = change ? "change-risk-reader@1" : "service-health-reader@1";
        return "{\"type\":\"INVOKE_SPECIALIST\",\"targetSpecialist\":\""
            + target + "\",\"message\":null,"
            + "\"reason\":\"The current turn maps to one approved reader.\"}";
    }

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
