package com.ai.fabric.realapps.behavior.ai;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Offline deterministic provider that returns JSON for BehaviorAnalysisService prompts.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.providers.llm-provider", havingValue = "behavior-local", matchIfMissing = true)
public class BehaviorLocalLlmProvider implements AIProvider {

    public static final String PROVIDER_NAME = "behavior-local";
    static final int EMBEDDING_DIMENSION = 384;

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        if (request != null && "agentic-ui-layout".equalsIgnoreCase(request.getGenerationType())) {
            return generateAgenticUiLayout(request);
        }

        String prompt = request != null ? request.getPrompt() : "";
        Map<String, Integer> counts = countSignals(prompt);

        int cancellations = counts.getOrDefault("cancel", 0);
        int paymentFailures = counts.getOrDefault("payment_failed", 0);
        int complaints = counts.getOrDefault("complaint", 0);
        int upgrades = counts.getOrDefault("upgrade", 0);
        int logins = counts.getOrDefault("login", 0);
        int usageDrops = counts.getOrDefault("usage_drop", 0);
        int featureErrors = counts.getOrDefault("feature_error", 0);
        int helpSearches = counts.getOrDefault("help_search", 0);
        int positiveSignals = counts.getOrDefault("positive", 0);
        int noLoginSignals = counts.getOrDefault("no_login", 0);
        int recoverySignals = counts.getOrDefault("recovery", 0);

        double churnRisk = 0.2
            + 0.35 * clamp01(paymentFailures / 3.0)
            + 0.45 * clamp01(cancellations / 2.0)
            + 0.25 * clamp01(complaints / 3.0)
            + 0.25 * clamp01(usageDrops / 2.0)
            + 0.2 * clamp01(noLoginSignals / 2.0)
            + 0.18 * clamp01(featureErrors / 2.0)
            - 0.15 * clamp01(upgrades / 2.0)
            - 0.12 * clamp01(positiveSignals / 2.0)
            - 0.28 * clamp01(recoverySignals / 4.0);
        churnRisk = clamp01(churnRisk);

        double sentimentScore = 0.1
            + 0.25 * clamp01(logins / 5.0)
            + 0.3 * clamp01(upgrades / 2.0)
            + 0.25 * clamp01(positiveSignals / 2.0)
            + 0.35 * clamp01(recoverySignals / 4.0)
            - 0.35 * clamp01(complaints / 3.0)
            - 0.4 * clamp01(paymentFailures / 3.0)
            - 0.45 * clamp01(cancellations / 2.0)
            - 0.25 * clamp01(usageDrops / 2.0)
            - 0.28 * clamp01(featureErrors / 2.0)
            - 0.12 * clamp01(helpSearches / 2.0);
        sentimentScore = Math.max(-1.0, Math.min(1.0, sentimentScore));

        String label = sentimentLabel(sentimentScore, churnRisk);
        String trend = trendLabel(churnRisk, sentimentScore);
        String segment = recoverySignals >= 3 && churnRisk < 0.65
            ? "recovering"
            : featureErrors > 0 && usageDrops > 0
            ? "product_regression_risk"
            : usageDrops > 0 && complaints == 0 && cancellations == 0
                ? "quiet_disengagement"
                : churnRisk > 0.7
                    ? "at_risk"
                    : upgrades > 0 || positiveSignals > 0
                        ? "expanding"
                        : helpSearches > 1
                            ? "onboarding_friction"
                            : "steady";

        String reason = reason(churnRisk, cancellations, paymentFailures, complaints, usageDrops, featureErrors, helpSearches, noLoginSignals);

        String responseJson = """
            {
              "segment": "%s",
              "patterns": ["payment_signals:%d","cancel_signals:%d","complaints:%d","logins:%d","upgrades:%d","usage_drops:%d","feature_errors:%d","help_searches:%d","positive_signals:%d","recovery_signals:%d"],
              "sentiment": {"score": %.3f, "label": "%s"},
              "churn": {"risk": %.3f, "reason": "%s"},
              "trend": "%s",
              "recommendations": [%s],
              "insights": {"signals": %s},
              "confidence": 0.78
            }
            """.formatted(
            segment,
            paymentFailures,
            cancellations,
            complaints,
            logins,
            upgrades,
            usageDrops,
            featureErrors,
            helpSearches,
            positiveSignals,
            recoverySignals,
            sentimentScore,
            label,
            churnRisk,
            reason.replace("\"", "'"),
            trend,
            recommendationsJson(churnRisk, label, featureErrors, usageDrops, helpSearches, positiveSignals, noLoginSignals),
            toJson(counts)
        );

        return AIGenerationResponse.builder()
            .content(responseJson)
            .model(PROVIDER_NAME)
            .requestId("gen-" + UUID.randomUUID())
            .build();
    }

    private AIGenerationResponse generateAgenticUiLayout(AIGenerationRequest request) {
        String prompt = request != null && request.getPrompt() != null
            ? request.getPrompt().toUpperCase(Locale.ROOT)
            : "";
        String components;
        String summary;
        if (prompt.contains("RETENTION_OFFER")) {
            summary = "Show account status, a retention next step, recent behavior evidence, and the gated offer.";
            components = componentJson(
                "ACCOUNT_STATUS_BANNER", "The account has urgent commercial risk signals.",
                "PERSONALIZED_NEXT_STEP", "The user needs a governed next step.",
                "BEHAVIOR_EVIDENCE_FEED", "Raw events explain why the home page changed.",
                "RETENTION_OFFER", "A weak or churning user should see a safe recovery offer."
            );
        } else if (prompt.contains("EXPANSION_FOLLOW_UP")) {
            summary = "Show activity points, an upgrade recommendation, and the behavior evidence trail.";
            components = componentJson(
                "ACTIVITY_POINTS", "Low risk and positive engagement should surface earned status.",
                "UPGRADE_RECOMMENDATION", "A loyal or growing user should see a relevant upgrade path.",
                "BEHAVIOR_EVIDENCE_FEED", "Events show why this account is not a save motion."
            );
        } else if (prompt.contains("ADOPTION_HELP")) {
            summary = "Show setup shortcuts, recent friction events, and practical next steps.";
            components = componentJson(
                "QUICK_SETUP_SHORTCUTS", "The user needs guidance instead of a discount.",
                "BEHAVIOR_EVIDENCE_FEED", "Recent app events show the setup problem.",
                "SMART_SHORTCUTS", "Confused users need concise follow-up options."
            );
        } else if (prompt.contains("ENGINEERING_ESCALATION")) {
            summary = "Show service recovery context, event evidence, and the recommended response.";
            components = componentJson(
                "SERVICE_RECOVERY_UPDATE", "Regression evidence should become a service recovery module.",
                "BEHAVIOR_EVIDENCE_FEED", "Raw telemetry supports the escalation.",
                "PERSONALIZED_NEXT_STEP", "The safest action is not discounting."
            );
        } else {
            summary = "Show monitoring context, event evidence, and follow-up actions.";
            components = componentJson(
                "ENGAGEMENT_WATCH", "The user may be drifting without explicit complaints.",
                "BEHAVIOR_EVIDENCE_FEED", "Raw events explain the trend.",
                "SMART_SHORTCUTS", "The user needs a small set of safe actions."
            );
        }

        String responseJson = """
            {
              "layout": "behavior-agentic-workspace",
              "summary": "%s",
              "components": [%s]
            }
            """.formatted(summary, components);
        return AIGenerationResponse.builder()
            .content(responseJson)
            .model(PROVIDER_NAME)
            .requestId("gen-" + UUID.randomUUID())
            .build();
    }

    private String componentJson(String... values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i += 2) {
            if (i > 0) {
                out.append(",");
            }
            out.append("""
                {"name":"%s","reason":"%s"}
                """.formatted(values[i], values[i + 1]));
        }
        return out.toString();
    }

    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        String text = request != null ? request.getText() : "";
        return deterministicEmbedding(text);
    }

    @Override
    public ProviderStatus getStatus() {
        return ProviderStatus.builder()
            .providerName(getProviderName())
            .available(true)
            .healthy(true)
            .successRate(1.0)
            .averageResponseTime(1.0)
            .lastUpdated(LocalDateTime.now())
            .details("offline deterministic provider (behavior analysis JSON)")
            .build();
    }

    @Override
    public ProviderConfig getConfig() {
        return ProviderConfig.builder()
            .providerName(getProviderName())
            .enabled(true)
            .apiKey("behavior-local-key")
            .baseUrl("behavior://local")
            .defaultModel(PROVIDER_NAME)
            .timeoutSeconds(1)
            .maxRetries(0)
            .build();
    }

    private AIEmbeddingResponse deterministicEmbedding(String text) {
        long seed = (text == null ? "" : text).hashCode() & 0xffffffffL;
        Random random = new Random(seed);
        List<Double> vector = new ArrayList<>(EMBEDDING_DIMENSION);
        double sumSquares = 0.0;
        for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
            double value = random.nextDouble() * 2.0 - 1.0;
            vector.add(value);
            sumSquares += value * value;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm > 0.0) {
            for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
                vector.set(i, vector.get(i) / norm);
            }
        }
        return AIEmbeddingResponse.builder()
            .embedding(vector)
            .model(PROVIDER_NAME)
            .dimensions(EMBEDDING_DIMENSION)
            .processingTimeMs(0L)
            .requestId("embedding-" + UUID.randomUUID())
            .build();
    }

    private Map<String, Integer> countSignals(String prompt) {
        String lower = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        Map<String, Integer> counts = new HashMap<>();
        counts.put("cancel", countMatches(lower, "cancel"));
        counts.put("payment_failed", countMatches(lower, "payment_failed") + countMatches(lower, "payment failed"));
        counts.put("complaint", countMatches(lower, "complaint") + countMatches(lower, "refund"));
        counts.put("upgrade", countMatches(lower, "upgrade") + countMatches(lower, "expanded"));
        counts.put("login", countMatches(lower, "login"));
        counts.put("usage_drop", countMatches(lower, "usage_drop") + countMatches(lower, "usage drop"));
        counts.put("feature_error", countMatches(lower, "feature_error") + countMatches(lower, "feature error") + countMatches(lower, "timeout"));
        counts.put("help_search", countMatches(lower, "help_center_search") + countMatches(lower, "help search"));
        counts.put("positive", countMatches(lower, "positive_feedback") + countMatches(lower, "loves") + countMatches(lower, "delighted"));
        counts.put("no_login", countMatches(lower, "no_login") + countMatches(lower, "no login"));
        counts.put("recovery", countMatches(lower, "payment_succeeded")
            + countMatches(lower, "usage_recovery")
            + countMatches(lower, "feature_used")
            + countMatches(lower, "billing issue resolved")
            + countMatches(lower, "active again"));
        return counts;
    }

    private int countMatches(String haystack, String needle) {
        if (haystack == null || haystack.isBlank() || needle == null || needle.isBlank()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private double clamp01(double value) {
        if (value < 0) {
            return 0;
        }
        if (value > 1) {
            return 1;
        }
        return value;
    }

    private String sentimentLabel(double sentimentScore, double churnRisk) {
        if (churnRisk > 0.85) {
            return "CHURNING";
        }
        if (sentimentScore > 0.6) {
            return "DELIGHTED";
        }
        if (sentimentScore > 0.2) {
            return "SATISFIED";
        }
        if (sentimentScore > -0.15) {
            return "NEUTRAL";
        }
        if (sentimentScore > -0.45) {
            return "CONFUSED";
        }
        return "FRUSTRATED";
    }

    private String trendLabel(double churnRisk, double sentimentScore) {
        if (churnRisk > 0.85 || sentimentScore < -0.6) {
            return "RAPIDLY_DECLINING";
        }
        if (churnRisk > 0.6 || sentimentScore < -0.3) {
            return "DECLINING";
        }
        if (sentimentScore > 0.4 && churnRisk < 0.35) {
            return "IMPROVING";
        }
        return "STABLE";
    }

    private String reason(double churnRiskSignals,
                          int cancellations,
                          int paymentFailures,
                          int complaints,
                          int usageDrops,
                          int featureErrors,
                          int helpSearches,
                          int noLoginSignals) {
        if (featureErrors > 0 && usageDrops > 0) {
            return "Usage dropped after repeated feature errors, suggesting a product regression needs escalation";
        }
        if (noLoginSignals > 0 && usageDrops > 0 && cancellations == 0 && complaints == 0) {
            return "Quiet disengagement detected from usage decline and login absence before explicit complaints";
        }
        if (helpSearches > 1 && complaints > 0) {
            return "Onboarding friction detected from repeated help searches and support complaints";
        }
        if (cancellations > 0 || paymentFailures > 1) {
            return "Repeated negative signals (payment issues / cancellation intent)";
        }
        if (churnRiskSignals > 0) {
            return "Early risk signals detected";
        }
        return "Healthy activity";
    }

    private String recommendationsJson(double churnRisk,
                                       String sentimentLabel,
                                       int featureErrors,
                                       int usageDrops,
                                       int helpSearches,
                                       int positiveSignals,
                                       int noLoginSignals) {
        if (featureErrors > 0 && usageDrops > 0) {
            return "\"Escalate product regression\", \"Attach engineering owner\", \"Notify affected account team\"";
        }
        if (noLoginSignals > 0 && usageDrops > 0) {
            return "\"Send proactive check-in\", \"Offer adoption review\", \"Monitor next 7 days\"";
        }
        if (helpSearches > 1) {
            return "\"Schedule onboarding help\", \"Share admin setup guide\", \"Assign adoption specialist\"";
        }
        if (churnRisk > 0.8) {
            return "\"Escalate to CSM outreach\", \"Offer retention credit\", \"Fix billing/payment issues\"";
        }
        if (churnRisk > 0.55) {
            return "\"Send proactive check-in\", \"Offer onboarding assistance\", \"Monitor next 7 days\"";
        }
        if (positiveSignals > 0 || "DELIGHTED".equalsIgnoreCase(sentimentLabel) || "SATISFIED".equalsIgnoreCase(sentimentLabel)) {
            return "\"Prompt for review\", \"Suggest upgrade path\", \"Share advanced features\"";
        }
        return "\"Continue monitoring\", \"Collect feedback\"";
    }

    private String toJson(Map<String, Integer> counts) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) {
                builder.append(",");
            }
            first = false;
            builder.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
        }
        builder.append("}");
        return builder.toString();
    }
}
