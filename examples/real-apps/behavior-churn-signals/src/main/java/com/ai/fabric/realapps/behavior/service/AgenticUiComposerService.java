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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgenticUiComposerService {

    public static final List<String> ALLOWED_COMPONENT_TYPES = List.of(
        "ACCOUNT_STATUS_BANNER",
        "PERSONALIZED_NEXT_STEP",
        "BEHAVIOR_EVIDENCE_FEED",
        "RETENTION_OFFER",
        "UPGRADE_RECOMMENDATION",
        "SERVICE_RECOVERY_UPDATE",
        "QUICK_SETUP_SHORTCUTS",
        "ENGAGEMENT_WATCH",
        "ACTIVITY_POINTS",
        "SMART_SHORTCUTS"
    );

    private static final List<ComponentCatalogItem> COMPONENT_CATALOG = List.of(
        new ComponentCatalogItem(
            "ACCOUNT_STATUS_BANNER",
            "Home-page status module that explains whether this user's account is healthy, blocked, confused, improving, or at risk.",
            "Use for any user whose home page needs a visible account status based on churn, sentiment, trend, or recovery evidence."
        ),
        new ComponentCatalogItem(
            "PERSONALIZED_NEXT_STEP",
            "One clear next step shown to the user based on behavior insight and backend policy.",
            "Use when the home page should guide the user toward retention, adoption help, upgrade, or support."
        ),
        new ComponentCatalogItem(
            "BEHAVIOR_EVIDENCE_FEED",
            "A compact recent-activity feed that explains why this personalized home page changed.",
            "Use when event evidence should be visible, especially after recording new behavior events."
        ),
        new ComponentCatalogItem(
            "RETENTION_OFFER",
            "User-facing save or recovery offer for weak, churning, or billing-frustrated users.",
            "Use only for RETENTION_OFFER, failed payments, cancellation intent, refund pressure, or commercial save-motion scenarios."
        ),
        new ComponentCatalogItem(
            "UPGRADE_RECOMMENDATION",
            "Relevant plan or feature upgrade module for loyal, growing, or highly active users.",
            "Use for EXPANSION_FOLLOW_UP, seat growth, high activity, positive sentiment, or upgrade signals."
        ),
        new ComponentCatalogItem(
            "SERVICE_RECOVERY_UPDATE",
            "Service recovery module for users affected by product errors, regressions, or incidents.",
            "Use for ENGINEERING_ESCALATION, feature errors, release regressions, dashboard/report failures, or product defects."
        ),
        new ComponentCatalogItem(
            "QUICK_SETUP_SHORTCUTS",
            "Simple shortcut module for confused users who need onboarding, setup, or help-center guidance.",
            "Use for ADOPTION_HELP, help searches, setup friction, unresolved usage questions, or onboarding confusion."
        ),
        new ComponentCatalogItem(
            "ENGAGEMENT_WATCH",
            "Quiet check-in module for users whose usage is falling without an explicit complaint.",
            "Use for PROACTIVE_CHECK_IN, silent churn, usage decay, no-login signals, or watchlist follow-up."
        ),
        new ComponentCatalogItem(
            "ACTIVITY_POINTS",
            "Reward or points module for loyal, interactive, or improving users.",
            "Use for healthy accounts, high engagement, positive sentiment, expansion-ready accounts, and active product usage."
        ),
        new ComponentCatalogItem(
            "SMART_SHORTCUTS",
            "Small set of behavior-aware shortcuts for the user home page.",
            "Use when the user needs a few practical actions instead of a full offer or escalation module."
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
            evidenceSummary(behaviorResult.events()),
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
            throw new IllegalStateException("Agentic UI composition failed: " + reason);
        }

        AgenticUiPlan plan = normalizePlan(
            result.getValue(),
            behaviorResult,
            lastResponse.get() != null ? lastResponse.get().getModel() : "unknown",
            result.getAttempts()
        );
        if (plan.components().isEmpty()) {
            throw new IllegalStateException("Agentic UI composition failed: no valid components");
        }
        return plan;
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
                String type = normalizeComponentType(stringValue(firstText(rawComponent, "name", "type", "module")));
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

    private Map<String, Object> trustedProps(String type, BehaviorScenarioResult result) {
        return switch (type) {
            case "ACCOUNT_STATUS_BANNER" -> riskProps(result.insight());
            case "PERSONALIZED_NEXT_STEP" -> actionProps(result);
            case "BEHAVIOR_EVIDENCE_FEED" -> Map.of("events", eventItems(result.events()));
            case "RETENTION_OFFER" -> retentionProps(result);
            case "UPGRADE_RECOMMENDATION" -> Map.of(
                "customerName", result.scenario().customerName(),
                "planId", result.scenario().planId(),
                "recommendation", result.retentionReview().recommendation()
            );
            case "SERVICE_RECOVERY_UPDATE" -> Map.of(
                "customerName", result.scenario().customerName(),
                "policyExplanation", result.retentionReview().policyExplanation(),
                "evidenceIds", result.retentionReview().evidenceIds()
            );
            case "QUICK_SETUP_SHORTCUTS" -> Map.of(
                "customerName", result.scenario().customerName(),
                "operatorGoal", result.scenario().operatorGoal(),
                "supportTickets", result.scenario().supportTickets()
            );
            case "ENGAGEMENT_WATCH" -> Map.of(
                "customerName", result.scenario().customerName(),
                "usageDropPercent", result.scenario().usageDropPercent(),
                "actionFamily", result.retentionReview().actionFamily()
            );
            case "ACTIVITY_POINTS" -> Map.of(
                "customerName", result.scenario().customerName(),
                "churnRisk", valueOrNull(result.insight() != null ? result.insight().churnRisk() : null),
                "sentiment", valueOrNull(result.insight() != null ? result.insight().sentimentLabel() : null),
                "confidence", valueOrNull(result.insight() != null ? result.insight().confidence() : null)
            );
            case "SMART_SHORTCUTS" -> Map.of(
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
        props.put("recommendation", result.retentionReview().recommendation());
        props.put("policyExplanation", result.retentionReview().policyExplanation());
        props.put("confirmationRequired", false);
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

    private AgenticUiEvidenceSummary evidenceSummary(List<BehaviorEventSummary> events) {
        List<BehaviorEventSummary> safeEvents = events != null ? events : List.of();
        Map<String, Long> counts = safeEvents.stream()
            .filter(event -> StringUtils.hasText(event.eventType()))
            .collect(Collectors.groupingBy(
                event -> event.eventType().trim(),
                LinkedHashMap::new,
                Collectors.counting()
            ));
        return new AgenticUiEvidenceSummary(
            safeEvents.size(),
            List.copyOf(counts.keySet()),
            counts,
            eventItems(safeEvents)
        );
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

            Choose the user-facing home modules for a SaaS account home page.
            Return JSON only with this schema:
            {
              "layout": "short-layout-name",
              "summary": "one sentence",
              "components": [
                {"name": "ONE_ALLOWED_HOME_MODULE_NAME", "reason": "why this module belongs on this user's home page"}
              ]
            }

            Available component catalog:
            %s

            Current trusted insight and account context:
            %s

            Rules:
            - Return 3 to 5 components.
            - Use only home module names from the catalog.
            - Return only names and reasons for components.
            - Choose components from the current insight and action family.
            - Do not invent facts, numbers, ids, offers, or event data.
            - Do not return component props, titles, config, CSS, or layout instructions beyond the short layout name.
            """.formatted(toJsonString(COMPONENT_CATALOG), toJsonString(context));
    }

    private String systemPrompt() {
        return "You are a behavior-aware SaaS home page planner. Return only valid JSON. Pick a short ordered list of user-facing home module names from the provided catalog.";
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

    private String normalizeComponentType(String type) {
        String normalized = type != null
            ? type.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_')
            : "";
        return switch (normalized) {
            case "RISK_SUMMARY_CARD" -> "ACCOUNT_STATUS_BANNER";
            case "RECOMMENDED_ACTION_CARD" -> "PERSONALIZED_NEXT_STEP";
            case "EVENT_TIMELINE" -> "BEHAVIOR_EVIDENCE_FEED";
            case "RETENTION_OFFER_PANEL" -> "RETENTION_OFFER";
            case "EXPANSION_NUDGE_CARD" -> "UPGRADE_RECOMMENDATION";
            case "PRODUCT_ESCALATION_PANEL" -> "SERVICE_RECOVERY_UPDATE";
            case "ADOPTION_HELP_PANEL" -> "QUICK_SETUP_SHORTCUTS";
            case "MONITORING_CARD" -> "ENGAGEMENT_WATCH";
            case "HEALTH_SCORE_CARD" -> "ACTIVITY_POINTS";
            case "NEXT_BEST_ACTIONS" -> "SMART_SHORTCUTS";
            default -> normalized;
        };
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
        AgenticUiEvidenceSummary evidence,
        AgenticUiPlan plan
    ) {}

    public record AgenticUiEvidenceSummary(
        int eventCount,
        List<String> eventTypes,
        Map<String, Long> eventTypeCounts,
        List<Map<String, Object>> recentEvents
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
