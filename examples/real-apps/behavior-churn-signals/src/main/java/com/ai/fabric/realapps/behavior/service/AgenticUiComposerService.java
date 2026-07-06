package com.ai.fabric.realapps.behavior.service;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIAccessSubjectContexts;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.llm.structured.StructuredJsonCallExecutor;
import ai.fabric.llm.structured.StructuredJsonCallSpec;
import ai.fabric.llm.structured.StructuredJsonProviderHints;
import ai.fabric.llm.structured.StructuredJsonResult;
import com.ai.fabric.realapps.behavior.service.BehaviorDemoScenarioService.BehaviorEventSummary;
import com.ai.fabric.realapps.behavior.service.BehaviorDemoScenarioService.BehaviorScenarioResult;
import com.ai.fabric.realapps.behavior.service.BehaviorDemoScenarioService.DemoScenarioSummary;
import com.ai.fabric.realapps.behavior.service.BehaviorDemoScenarioService.InsightSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class AgenticUiComposerService {

    public static final List<String> ALLOWED_COMPONENT_TYPES = List.of(
        "RISK_SUMMARY_CARD",
        "RECOMMENDED_ACTION_CARD",
        "EVENT_TIMELINE",
        "RETENTION_OFFER_PANEL",
        "EXPANSION_NUDGE_CARD",
        "PRODUCT_ESCALATION_PANEL",
        "ADOPTION_HELP_PANEL",
        "MONITORING_CARD",
        "HEALTH_SCORE_CARD",
        "NEXT_BEST_ACTIONS"
    );

    private static final List<ComponentCatalogItem> COMPONENT_CATALOG = List.of(
        new ComponentCatalogItem(
            "RISK_SUMMARY_CARD",
            "Shows churn risk, sentiment, trend, churn reason, confidence, and immediate-action posture.",
            "Use for high-risk, negative sentiment, commercial friction, cancellation risk, or accounts needing urgent operator attention."
        ),
        new ComponentCatalogItem(
            "RECOMMENDED_ACTION_CARD",
            "Shows the backend recommendation, action family, policy explanation, and evidence ids.",
            "Use when the operator needs a clear next action backed by policy and evidence."
        ),
        new ComponentCatalogItem(
            "EVENT_TIMELINE",
            "Shows recent raw app behavior events with source, timestamp, and compact event summaries.",
            "Use when event evidence explains why the UI changed; useful for most scenarios."
        ),
        new ComponentCatalogItem(
            "RETENTION_OFFER_PANEL",
            "Shows a confirmation-gated retention offer, discount policy, and offer confirmation message.",
            "Use only for RETENTION_OFFER, high commercial churn, failed payments, cancellation intent, or save-motion scenarios."
        ),
        new ComponentCatalogItem(
            "EXPANSION_NUDGE_CARD",
            "Shows customer, plan, and an expansion-friendly recommendation.",
            "Use for EXPANSION_FOLLOW_UP, healthy enterprise accounts, seat growth, upgrades, or positive expansion signals."
        ),
        new ComponentCatalogItem(
            "PRODUCT_ESCALATION_PANEL",
            "Shows product regression context, policy explanation, and escalation evidence ids.",
            "Use for ENGINEERING_ESCALATION, feature errors, release regressions, dashboard/report failures, or product defects."
        ),
        new ComponentCatalogItem(
            "ADOPTION_HELP_PANEL",
            "Shows customer, support-ticket count, and the operator goal for guided setup help.",
            "Use for ADOPTION_HELP, onboarding friction, help-center searches, setup confusion, or training/support guidance."
        ),
        new ComponentCatalogItem(
            "MONITORING_CARD",
            "Shows customer, usage-drop percent, and action family for low-friction monitoring.",
            "Use for PROACTIVE_CHECK_IN, silent churn, usage decay, no-login signals, or watchlist follow-up."
        ),
        new ComponentCatalogItem(
            "HEALTH_SCORE_CARD",
            "Shows a compact account health score derived from churn risk, sentiment, and confidence.",
            "Use for healthy or improving accounts, expansion-ready accounts, and positive posture summaries."
        ),
        new ComponentCatalogItem(
            "NEXT_BEST_ACTIONS",
            "Shows the behavior insight recommendation list and action family as concise next steps.",
            "Use when multiple lightweight follow-up options should be shown."
        )
    );

    private final AICoreService aiCoreService;
    private final StructuredJsonCallExecutor structuredJsonCallExecutor;
    private final ObjectMapper objectMapper;

    public AgenticUiResponse compose(BehaviorScenarioResult behaviorResult) {
        if (behaviorResult == null || behaviorResult.scenario() == null) {
            throw new IllegalArgumentException("behaviorResult with scenario is required");
        }

        AgenticUiPlan plan = tryLlmPlan(behaviorResult);
        return new AgenticUiResponse(
            behaviorResult.scenario().userId(),
            behaviorResult.scenario().accountId(),
            behaviorResult.scenario().customerName(),
            behaviorResult.scenario(),
            plan
        );
    }

    private AgenticUiPlan tryLlmPlan(BehaviorScenarioResult behaviorResult) {
        String prompt = buildPrompt(behaviorResult);
        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId(behaviorResult.scenario().userId())
            .entityType("agentic-ui-plan")
            .generationType("agentic-ui-layout")
            .systemPrompt(systemPrompt())
            .prompt(prompt)
            .parameters(StructuredJsonProviderHints.jsonObjectResponseParameters())
            .temperature(0.1)
            .maxTokens(700)
            .authContext(AIAccessSubjectContexts.system("agentic-ui-composer"))
            .build();

        AtomicReference<AIGenerationResponse> lastResponse = new AtomicReference<>();
        StructuredJsonResult<Map> result = structuredJsonCallExecutor.execute(
            StructuredJsonCallSpec.<Map>builder()
                .callName("agentic-ui-layout")
                .maxAttempts(2)
                .targetType(Map.class)
                .objectMapper(objectMapper)
                .validator(this::validateRawPlan)
                .caller(context -> {
                    AIGenerationRequest effectiveRequest = context.attemptIndex() == 0
                        ? request
                        : repairRequest(request, context.previousRawContent());
                    AIGenerationResponse response = aiCoreService.generateContent(effectiveRequest, LlmPurpose.ORCHESTRATION);
                    lastResponse.set(response);
                    return response;
                })
                .build()
        );

        if (!result.isSuccess() || result.getValue() == null) {
            String reason = result.getLastFailure() != null ? result.getLastFailure().message() : "unknown";
            return fallbackPlan(behaviorResult, "fallback: " + reason);
        }

        AgenticUiPlan plan = normalizePlan(
            result.getValue(),
            behaviorResult,
            lastResponse.get() != null ? lastResponse.get().getModel() : "unknown",
            result.getAttempts()
        );
        return plan.components().isEmpty() ? fallbackPlan(behaviorResult, "fallback: no valid components") : plan;
    }

    private void validateRawPlan(Map<?, ?> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Plan JSON is required");
        }
        Object components = raw.get("components");
        if (!(components instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Plan must contain a non-empty components array");
        }
    }

    private AIGenerationRequest repairRequest(AIGenerationRequest original, String previousRawContent) {
        return AIGenerationRequest.builder()
            .entityId(original.getEntityId())
            .entityType(original.getEntityType())
            .generationType(original.getGenerationType())
            .systemPrompt(systemPrompt())
            .prompt("""
                Repair the previous response into valid JSON only.
                It must include layout, summary, and components.
                Each component must contain only name and reason.
                Available component catalog:
                %s

                Previous response:
                %s
                """.formatted(toJsonString(COMPONENT_CATALOG), previousRawContent))
            .parameters(StructuredJsonProviderHints.jsonObjectResponseParameters())
            .temperature(0.0)
            .maxTokens(500)
            .authContext(AIAccessSubjectContexts.system("agentic-ui-composer"))
            .build();
    }

    private AgenticUiPlan normalizePlan(Map<?, ?> raw,
                                        BehaviorScenarioResult behaviorResult,
                                        String model,
                                        int attempts) {
        List<AgenticUiComponent> components = new ArrayList<>();
        Object rawComponents = raw.get("components");
        if (rawComponents instanceof List<?> list) {
            int index = 0;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> rawComponent)) {
                    continue;
                }
                String type = stringValue(firstText(rawComponent, "name", "type")).toUpperCase(Locale.ROOT);
                if (!ALLOWED_COMPONENT_TYPES.contains(type)) {
                    continue;
                }
                int priority = index + 1;
                String rationale = StringUtils.hasText(stringValue(rawComponent.get("reason")))
                    ? stringValue(rawComponent.get("reason"))
                    : StringUtils.hasText(stringValue(rawComponent.get("rationale")))
                    ? stringValue(rawComponent.get("rationale"))
                    : "Selected from current behavior insight.";
                components.add(new AgenticUiComponent(
                    stableComponentId(type, components.size() + 1),
                    type,
                    defaultTitle(type),
                    priority,
                    rationale,
                    trustedProps(type, behaviorResult)
                ));
                index++;
            }
        }

        return new AgenticUiPlan(
            stringOrDefault(raw.get("layout"), "adaptive-retention-workbench"),
            stringOrDefault(raw.get("summary"), "UI composed from current behavior insight."),
            "llm",
            StringUtils.hasText(model) ? model : "unknown",
            attempts,
            Instant.now().toString(),
            ALLOWED_COMPONENT_TYPES,
            components
        );
    }

    private AgenticUiPlan fallbackPlan(BehaviorScenarioResult result, String source) {
        List<String> types = fallbackComponentTypes(result.retentionReview().actionFamily());
        List<AgenticUiComponent> components = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            components.add(new AgenticUiComponent(
                stableComponentId(type, i + 1),
                type,
                defaultTitle(type),
                i + 1,
                "Fallback selection from trusted behavior insight and action family.",
                trustedProps(type, result)
            ));
        }
        return new AgenticUiPlan(
            "safe-default-workbench",
            "Safe fallback UI composed from current behavior insight.",
            source,
            "fallback",
            0,
            Instant.now().toString(),
            ALLOWED_COMPONENT_TYPES,
            components
        );
    }

    private List<String> fallbackComponentTypes(String actionFamily) {
        String family = actionFamily != null ? actionFamily.toUpperCase(Locale.ROOT) : "";
        return switch (family) {
            case "RETENTION_OFFER" -> List.of("RISK_SUMMARY_CARD", "RECOMMENDED_ACTION_CARD", "EVENT_TIMELINE", "RETENTION_OFFER_PANEL");
            case "EXPANSION_FOLLOW_UP" -> List.of("HEALTH_SCORE_CARD", "EXPANSION_NUDGE_CARD", "EVENT_TIMELINE");
            case "ADOPTION_HELP" -> List.of("ADOPTION_HELP_PANEL", "EVENT_TIMELINE", "NEXT_BEST_ACTIONS");
            case "ENGINEERING_ESCALATION" -> List.of("PRODUCT_ESCALATION_PANEL", "EVENT_TIMELINE", "RECOMMENDED_ACTION_CARD");
            case "PROACTIVE_CHECK_IN" -> List.of("MONITORING_CARD", "EVENT_TIMELINE", "NEXT_BEST_ACTIONS");
            default -> List.of("RISK_SUMMARY_CARD", "EVENT_TIMELINE", "NEXT_BEST_ACTIONS");
        };
    }

    private Map<String, Object> trustedProps(String type, BehaviorScenarioResult result) {
        return switch (type) {
            case "RISK_SUMMARY_CARD" -> riskProps(result.insight());
            case "RECOMMENDED_ACTION_CARD" -> actionProps(result);
            case "EVENT_TIMELINE" -> Map.of("events", eventItems(result.events()));
            case "RETENTION_OFFER_PANEL" -> retentionProps(result);
            case "EXPANSION_NUDGE_CARD" -> Map.of(
                "customerName", result.scenario().customerName(),
                "planId", result.scenario().planId(),
                "recommendation", result.retentionReview().recommendation()
            );
            case "PRODUCT_ESCALATION_PANEL" -> Map.of(
                "customerName", result.scenario().customerName(),
                "policyExplanation", result.retentionReview().policyExplanation(),
                "evidenceIds", result.retentionReview().evidenceIds()
            );
            case "ADOPTION_HELP_PANEL" -> Map.of(
                "customerName", result.scenario().customerName(),
                "operatorGoal", result.scenario().operatorGoal(),
                "supportTickets", result.scenario().supportTickets()
            );
            case "MONITORING_CARD" -> Map.of(
                "customerName", result.scenario().customerName(),
                "usageDropPercent", result.scenario().usageDropPercent(),
                "actionFamily", result.retentionReview().actionFamily()
            );
            case "HEALTH_SCORE_CARD" -> Map.of(
                "customerName", result.scenario().customerName(),
                "churnRisk", valueOrNull(result.insight() != null ? result.insight().churnRisk() : null),
                "sentiment", valueOrNull(result.insight() != null ? result.insight().sentimentLabel() : null),
                "confidence", valueOrNull(result.insight() != null ? result.insight().confidence() : null)
            );
            case "NEXT_BEST_ACTIONS" -> Map.of(
                "recommendations", result.insight() != null ? result.insight().recommendations() : List.of(result.scenario().operatorGoal()),
                "actionFamily", result.retentionReview().actionFamily()
            );
            default -> Map.of();
        };
    }

    private Map<String, Object> riskProps(InsightSummary insight) {
        if (insight == null) {
            return Map.of("status", "not_analyzed");
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("segment", insight.segment());
        props.put("sentiment", insight.sentimentLabel());
        props.put("sentimentScore", insight.sentimentScore());
        props.put("churnRisk", insight.churnRisk());
        props.put("churnReason", insight.churnReason());
        props.put("trend", insight.trend());
        props.put("confidence", insight.confidence());
        props.put("requiresImmediateAction", insight.requiresImmediateAction());
        return props;
    }

    private Map<String, Object> actionProps(BehaviorScenarioResult result) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("actionFamily", result.retentionReview().actionFamily());
        props.put("recommendation", result.retentionReview().recommendation());
        props.put("policyExplanation", result.retentionReview().policyExplanation());
        props.put("evidenceIds", result.retentionReview().evidenceIds());
        return props;
    }

    private Map<String, Object> retentionProps(BehaviorScenarioResult result) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("discountPercent", result.scenario().defaultDiscountPercent());
        props.put("confirmationMessage", result.retentionOfferPreview().confirmationMessage());
        props.put("confirmationRequired", result.retentionOfferPreview().result().confirmationRequired());
        props.put("message", result.retentionOfferPreview().result().message());
        props.put("data", result.retentionOfferPreview().result().data());
        return props;
    }

    private List<Map<String, Object>> eventItems(List<BehaviorEventSummary> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream()
            .limit(6)
            .map(event -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", event.eventType());
                item.put("source", event.source());
                item.put("timestamp", event.eventTimestamp() != null ? event.eventTimestamp().toString() : null);
                item.put("summary", eventSummary(event.eventData()));
                return item;
            })
            .toList();
    }

    private String eventSummary(String rawEventData) {
        if (!StringUtils.hasText(rawEventData)) {
            return "";
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(rawEventData, Map.class);
            if (data == null || data.isEmpty()) {
                return rawEventData;
            }
            return data.entrySet().stream()
                .limit(4)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse(rawEventData);
        } catch (Exception ignored) {
            return rawEventData;
        }
    }

    private String buildPrompt(BehaviorScenarioResult result) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("scenario", result.scenario());
        context.put("insight", result.insight());
        context.put("retentionReview", result.retentionReview());
        context.put("recentEvents", eventItems(result.events()));
        return """
            AGENTIC_UI_LAYOUT_REQUEST

            Choose the component list for a SaaS behavior intelligence page.
            Return JSON only with this schema:
            {
              "layout": "short-layout-name",
              "summary": "one sentence",
              "components": [
                {"name": "ONE_ALLOWED_COMPONENT_NAME", "reason": "why this component belongs in this UI"}
              ]
            }

            Available component catalog:
            %s

            Current trusted insight and account context:
            %s

            Rules:
            - Return 3 to 5 components.
            - Use only component names from the catalog.
            - Return only names and reasons for components.
            - Choose components from the current insight and action family.
            - Do not invent facts, numbers, ids, offers, or event data.
            - Do not return component props, titles, config, CSS, or layout instructions beyond the short layout name.
            """.formatted(toJsonString(COMPONENT_CATALOG), toJsonString(context));
    }

    private String systemPrompt() {
        return "You are an agentic UI planner. Return only valid JSON. Pick a short ordered list of component names from the provided catalog.";
    }

    private String toJsonString(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private String stringOrDefault(Object value, String fallback) {
        String text = stringValue(value);
        return StringUtils.hasText(text) ? text : fallback;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Object firstText(Map<?, ?> raw, String... keys) {
        if (raw == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = raw.get(key);
            if (StringUtils.hasText(stringValue(value))) {
                return value;
            }
        }
        return "";
    }

    private String stableComponentId(String type, int index) {
        return type.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + index;
    }

    private String defaultTitle(String type) {
        String text = type.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        boolean nextUpper = true;
        for (char ch : text.toCharArray()) {
            if (Character.isWhitespace(ch)) {
                nextUpper = true;
                out.append(ch);
            } else if (nextUpper) {
                out.append(Character.toUpperCase(ch));
                nextUpper = false;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private Object valueOrNull(Object value) {
        return value != null ? value : "";
    }

    public record AgenticUiResponse(
        String userId,
        String accountId,
        String customerName,
        DemoScenarioSummary scenario,
        AgenticUiPlan plan
    ) {}

    public record AgenticUiPlan(
        String layout,
        String summary,
        String source,
        String model,
        int attempts,
        String generatedAt,
        List<String> allowedComponentTypes,
        List<AgenticUiComponent> components
    ) {}

    public record AgenticUiComponent(
        String id,
        String type,
        String title,
        int priority,
        String rationale,
        Map<String, Object> props
    ) {}

    private record ComponentCatalogItem(
        String name,
        String description,
        String useWhen
    ) {}
}
