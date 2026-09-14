package com.ai.fabric.realapps.agenticresolver.config;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import com.ai.fabric.examples.smoke.SmokeAiProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Structured-output fixture used only by the explicit local smoke profile. */
final class AccountResolverSmokeAiProvider extends SmokeAiProvider {

    static final String NAME = "account-resolver-smoke";

    private final ObjectMapper objectMapper;

    AccountResolverSmokeAiProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return NAME;
    }

    @Override
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        String input = combinedInput(request).toLowerCase(Locale.ROOT);
        String content;
        if (isIntentExtraction(request)) {
            content = intentResponse();
        } else if (input.contains("coordinate one bounded, read-only account-resolution")) {
            content = chainDirective(request, currentUserMessage(request));
        } else if (input.contains("you plan bounded live-information resolution")) {
            content = readActionPlan(request, input);
        } else {
            return super.generateContent(request);
        }
        return AIGenerationResponse.builder()
            .id("account-resolver-smoke-" + UUID.randomUUID())
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

    private String intentResponse() {
        return "{\"intents\":[{\"type\":\"INFORMATION\","
            + "\"intent\":\"account_resolution\",\"confidence\":1.0,"
            + "\"vectorSpace\":\"account-resolution-policy\","
            + "\"requiresRetrieval\":true,\"requiresGeneration\":true,"
            + "\"responseProfile\":\"STANDARD\","
            + "\"requiresTargetResolution\":false,"
            + "\"needsAdvancedRAG\":false,"
            + "\"optimizedQuery\":\"account resolution policy\"}],"
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
            .details("account-chain structured fixture (smoke profile only)")
            .build();
    }

    @Override
    public ProviderConfig getConfig() {
        return ProviderConfig.builder()
            .providerName(NAME)
            .enabled(true)
            .apiKey("local-smoke-key")
            .baseUrl("smoke://account-resolver")
            .defaultModel(NAME)
            .timeoutSeconds(1)
            .maxRetries(0)
            .build();
    }

    private String readActionPlan(
        AIGenerationRequest generationRequest,
        String input
    ) {
        if (input.contains("assess_billing_resolution")) {
            JsonNode context = untrustedContext(generationRequest);
            JsonNode request = embeddedRequest(input);
            String type = context.path("/resolutionType").asText(
                request.path("resolutionType").asText("REFUND")
            ).toUpperCase(Locale.ROOT);
            String amount = context.path("/amount").asText(
                request.path("amount").asText("25")
            );
            return "{\"decision\":\"EXECUTE_READ_ACTIONS_AND_RAG\","
                + "\"actions\":[{\"name\":\"assess_billing_resolution\","
                + "\"params\":{\"resolutionType\":\"" + type + "\","
                + "\"amount\":" + amount + "},\"priority\":1}],"
                + "\"needsMoreSteps\":false,\"suggestedVectorSpaces\":"
                + "[\"account-resolution-policy\"]}";
        }
        return "{\"decision\":\"EXECUTE_READ_ACTIONS_AND_RAG\","
            + "\"actions\":[{\"name\":\"get_account_profile\","
            + "\"params\":{},\"priority\":1}],"
            + "\"needsMoreSteps\":false,\"suggestedVectorSpaces\":"
            + "[\"account-resolution-policy\"]}";
    }

    private String chainDirective(
        AIGenerationRequest request,
        String userMessage
    ) {
        JsonNode context = untrustedContext(request);
        JsonNode completed = context.path("/completedResults");
        JsonNode account = completedResult(
            completed,
            "account-resolver-manager-read@1"
        );
        JsonNode billing = completedResult(
            completed,
            "billing-resolution-manager-advisor@1"
        );
        boolean hasAccount = !account.isMissingNode();
        boolean hasBilling = !billing.isMissingNode();

        if (hasAccount && hasBilling) {
            return directive(
                "COMPLETE",
                List.of(),
                account.path("summary").asText()
                    + " " + billing.path("summary").asText(),
                "Both requested application projections are available.",
                resultIds(account, billing)
            );
        }
        if (hasAccount) {
            if (isCombined(userMessage) && billingInputComplete(context)) {
                return invokeOne(
                    "billing-resolution-manager-advisor@1",
                    "Assess the supplied billing resolution against policy.",
                    "The account-first result is complete and billing remains."
                );
            }
            return completeFrom(account, "The account assessment is complete.");
        }
        if (hasBilling) {
            return completeFrom(billing, "The billing assessment is complete.");
        }

        if (containsAny(userMessage, "help me", "look into this", "resolve this")) {
            return directive(
                "ASK_USER",
                List.of(),
                "Should I inspect account blockers, assess a billing resolution, or do both?",
                "The account-support request is ambiguous."
            );
        }
        if (containsAny(userMessage, "what can you do", "hello", "tell me a joke")) {
            return directive(
                "COMPLETE",
                List.of(),
                "I can inspect current account readiness and assess a supplied refund or account credit without changing account state.",
                "No worker is required to explain the bounded capability."
            );
        }
        if (containsAny(userMessage, "database-admin", "invented specialist")) {
            return directive(
                "COMPLETE",
                List.of(),
                "That specialist is not available. I can use only the approved account-readiness and billing-policy specialists.",
                "The requested target is outside the approved catalog."
            );
        }
        if (userMessage.contains("handoff")) {
            String target = isBilling(userMessage)
                ? "billing-resolution-manager-advisor@1"
                : "account-resolver-manager-read@1";
            return directive(
                "HANDOFF",
                List.of(target(
                    target,
                    "Take terminal ownership of this bounded read-only assessment."
                )),
                null,
                "The user explicitly requested a terminal handoff."
            );
        }
        if (isAccountOnly(userMessage)) {
            return invokeOne(
                "account-resolver-manager-read@1",
                "Inspect current account readiness and blockers.",
                "The request explicitly excludes a billing assessment."
            );
        }
        if (isCombined(userMessage)) {
            if (!billingInputComplete(context)) {
                return missingBillingInput(context);
            }
            if (containsAny(
                userMessage,
                "account first",
                "readiness first",
                "first inspect"
            )) {
                return invokeOne(
                    "account-resolver-manager-read@1",
                    "Inspect current account readiness before billing policy.",
                    "The user explicitly requested sequential account-first work."
                );
            }
            return directive(
                "INVOKE_PARALLEL",
                List.of(
                    target(
                        "account-resolver-manager-read@1",
                        "Inspect current account readiness and blockers."
                    ),
                    target(
                        "billing-resolution-manager-advisor@1",
                        "Assess the supplied billing resolution against policy."
                    )
                ),
                null,
                "Both independent read-only assessments are requested."
            );
        }
        if (isBilling(userMessage)) {
            if (!billingInputComplete(context)) {
                return missingBillingInput(context);
            }
            return invokeOne(
                "billing-resolution-manager-advisor@1",
                "Assess the supplied billing resolution against policy.",
                "The request is a complete billing assessment."
            );
        }
        if (containsAny(
            userMessage,
            "account",
            "order",
            "blocker",
            "payment",
            "address",
            "subscription",
            "readiness"
        )) {
            return invokeOne(
                "account-resolver-manager-read@1",
                "Inspect current account readiness and blockers.",
                "The request asks about current account state."
            );
        }
        return directive(
            "COMPLETE",
            List.of(),
            "This coordinator supports only account readiness and billing-policy assessments.",
            "The request is outside the bounded account-support scope."
        );
    }

    private String missingBillingInput(JsonNode context) {
        String state = applicationContextValue(context, "billingInputState");
        String message = switch (state) {
            case "RESOLUTION_TYPE_MISSING" ->
                "Should I assess a REFUND or an ACCOUNT_CREDIT?";
            case "AMOUNT_MISSING" ->
                "What amount should I assess?";
            default ->
                "Should I assess a REFUND or an ACCOUNT_CREDIT, and for what amount?";
        };
        return directive(
            "ASK_USER",
            List.of(),
            message,
            "Complete billing facts are required before invoking the worker."
        );
    }

    private boolean billingInputComplete(JsonNode context) {
        return "COMPLETE".equals(
            applicationContextValue(context, "billingInputState")
        );
    }

    private String applicationContextValue(JsonNode context, String name) {
        for (JsonNode value : context.path("/applicationContext")) {
            if (name.equals(value.path("name").asText())) {
                return value.path("value").asText();
            }
        }
        return "";
    }

    private JsonNode completedResult(JsonNode values, String specialist) {
        if (values.isArray()) {
            for (JsonNode value : values) {
                if (specialist.equals(value.path("specialist").asText())) {
                    return value;
                }
            }
        }
        return objectMapper.missingNode();
    }

    private String completeFrom(JsonNode result, String reason) {
        return directive(
            "COMPLETE",
            List.of(),
            result.path("summary").asText("The assessment completed."),
            reason,
            resultIds(result)
        );
    }

    private List<String> resultIds(JsonNode... results) {
        return java.util.Arrays.stream(results)
            .map(result -> result.path("resultId").asText())
            .filter(value -> !value.isBlank())
            .toList();
    }

    private String invokeOne(String specialist, String objective, String reason) {
        return directive(
            "INVOKE_ONE",
            List.of(target(specialist, objective)),
            null,
            reason
        );
    }

    private Map<String, String> target(String specialist, String objective) {
        return Map.of(
            "targetSpecialist", specialist,
            "objective", objective
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
                "Could not encode account-chain smoke directive",
                exception
            );
        }
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
                "Account smoke manager received invalid JSON context",
                exception
            );
        }
    }

    private JsonNode embeddedRequest(String input) {
        int typeStart = input.indexOf("\"resolutiontype\"");
        int amountStart = input.indexOf("\"amount\"");
        var value = objectMapper.createObjectNode();
        if (typeStart >= 0) {
            int colon = input.indexOf(':', typeStart);
            int quote = input.indexOf('"', colon + 1);
            int end = input.indexOf('"', quote + 1);
            if (quote >= 0 && end > quote) {
                value.put("resolutionType", input.substring(quote + 1, end));
            }
        }
        if (amountStart >= 0) {
            int colon = input.indexOf(':', amountStart);
            int end = colon + 1;
            while (end < input.length()
                && " 0123456789.".indexOf(input.charAt(end)) >= 0) {
                end++;
            }
            String amount = input.substring(colon + 1, end).trim();
            if (!amount.isBlank()) {
                value.put("amount", amount);
            }
        }
        return value;
    }

    private String currentUserMessage(AIGenerationRequest request) {
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

    private boolean isCombined(String value) {
        return containsAny(
            value,
            "both",
            "as well as",
            "and assess",
            "then assess",
            "plus assess",
            "account first"
        );
    }

    private boolean isAccountOnly(String value) {
        return containsAny(value, "inspect only", "account only")
            && containsAny(value, "do not assess", "without billing");
    }

    private boolean isBilling(String value) {
        return containsAny(
            value,
            "refund",
            "account credit",
            "billing resolution"
        );
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

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
