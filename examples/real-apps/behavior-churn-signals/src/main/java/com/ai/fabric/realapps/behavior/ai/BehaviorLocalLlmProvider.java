package com.ai.fabric.realapps.behavior.ai;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import lombok.extern.slf4j.Slf4j;
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
public class BehaviorLocalLlmProvider implements AIProvider {

    static final String PROVIDER_NAME = "behavior-local";
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
        String prompt = request != null ? request.getPrompt() : "";
        Map<String, Integer> counts = countSignals(prompt);

        int cancellations = counts.getOrDefault("cancel", 0);
        int paymentFailures = counts.getOrDefault("payment_failed", 0);
        int complaints = counts.getOrDefault("complaint", 0);
        int upgrades = counts.getOrDefault("upgrade", 0);
        int logins = counts.getOrDefault("login", 0);

        double churnRisk = 0.2
            + 0.35 * clamp01(paymentFailures / 3.0)
            + 0.45 * clamp01(cancellations / 2.0)
            + 0.25 * clamp01(complaints / 3.0)
            - 0.15 * clamp01(upgrades / 2.0);
        churnRisk = clamp01(churnRisk);

        double sentimentScore = 0.1
            + 0.25 * clamp01(logins / 5.0)
            + 0.3 * clamp01(upgrades / 2.0)
            - 0.35 * clamp01(complaints / 3.0)
            - 0.4 * clamp01(paymentFailures / 3.0)
            - 0.45 * clamp01(cancellations / 2.0);
        sentimentScore = Math.max(-1.0, Math.min(1.0, sentimentScore));

        String label = sentimentLabel(sentimentScore, churnRisk);
        String trend = trendLabel(churnRisk, sentimentScore);
        String segment = churnRisk > 0.7 ? "at_risk" : upgrades > 0 ? "expanding" : "steady";

        String reason = churnRisk > 0.7
            ? "Repeated negative signals (payment issues / cancellation intent)"
            : churnRisk > 0.45
                ? "Early risk signals detected"
                : "Healthy activity";

        String responseJson = """
            {
              "segment": "%s",
              "patterns": ["payment_signals:%d","cancel_signals:%d","complaints:%d","logins:%d","upgrades:%d"],
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
            sentimentScore,
            label,
            churnRisk,
            reason.replace("\"", "'"),
            trend,
            recommendationsJson(churnRisk, label),
            toJson(counts)
        );

        return AIGenerationResponse.builder()
            .content(responseJson)
            .model(PROVIDER_NAME)
            .requestId("gen-" + UUID.randomUUID())
            .build();
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

    private String recommendationsJson(double churnRisk, String sentimentLabel) {
        if (churnRisk > 0.8) {
            return "\"Escalate to CSM outreach\", \"Offer retention credit\", \"Fix billing/payment issues\"";
        }
        if (churnRisk > 0.55) {
            return "\"Send proactive check-in\", \"Offer onboarding assistance\", \"Monitor next 7 days\"";
        }
        if ("DELIGHTED".equalsIgnoreCase(sentimentLabel) || "SATISFIED".equalsIgnoreCase(sentimentLabel)) {
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
