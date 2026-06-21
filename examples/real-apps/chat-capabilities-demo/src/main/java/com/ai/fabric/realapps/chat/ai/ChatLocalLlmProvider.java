package com.ai.fabric.realapps.chat.ai;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline deterministic provider for chat action smoke scenarios.
 *
 * <p>The provider returns framework intent-extraction JSON for a narrow set of demo order-action
 * requests, keeping packaged real-app smokes independent of external LLM credentials.</p>
 */
@Component
public class ChatLocalLlmProvider implements AIProvider {

    static final String PROVIDER_NAME = "chat-local";
    static final int EMBEDDING_DIMENSION = 384;

    private static final Pattern USER_QUESTION_PATTERN = Pattern.compile(
        "User's question is:\\s*\\((.*)\\)\\s*$",
        Pattern.DOTALL
    );
    private static final Pattern ORDER_NUMBER_PATTERN = Pattern.compile(
        "\\bPO-[A-Za-z0-9][A-Za-z0-9\\-]*\\b"
    );

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
        String userMessage = extractUserMessage(prompt);
        String intentJson = buildIntentJson(userMessage);

        return AIGenerationResponse.builder()
            .id("chat-local-" + UUID.randomUUID())
            .requestId(request != null ? request.getEntityId() : null)
            .entityId(request != null ? request.getEntityId() : null)
            .entityType(request != null ? request.getEntityType() : null)
            .generationType(request != null ? request.getGenerationType() : null)
            .content(intentJson)
            .model(PROVIDER_NAME)
            .tokensUsed(intentJson.length())
            .confidence(1.0d)
            .processingTimeMs(0L)
            .generatedAt(LocalDateTime.now())
            .status("SUCCESS")
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
            .successRate(1.0d)
            .averageResponseTime(0.0d)
            .lastUpdated(LocalDateTime.now())
            .details("offline deterministic provider (chat action intent smoke)")
            .build();
    }

    @Override
    public ProviderConfig getConfig() {
        return ProviderConfig.builder()
            .providerName(getProviderName())
            .enabled(true)
            .apiKey("chat-local-key")
            .baseUrl("chat://local")
            .defaultModel(PROVIDER_NAME)
            .defaultEmbeddingModel(PROVIDER_NAME)
            .timeoutSeconds(1)
            .maxRetries(0)
            .priority(0)
            .build();
    }

    String buildIntentJson(String userMessage) {
        String visibleText = visibleUserText(userMessage);
        String lower = visibleText.toLowerCase(Locale.ROOT);

        if (isPositiveConfirmation(lower)) {
            return confirmationIntent("CONFIRMATION_POSITIVE", "confirm");
        }
        if (isNegativeConfirmation(lower)) {
            return confirmationIntent("CONFIRMATION_NEGATIVE", "reject");
        }
        if (lower.contains("cancel") && (lower.contains("order") || lower.contains("purchase"))) {
            String orderNumber = extractOrderNumber(visibleText);
            if (StringUtils.hasText(orderNumber)) {
                return actionIntent("cancel_purchase_order", "\"orderNumber\":\"" + escapeJson(orderNumber) + "\"");
            }
        }

        return informationIntent();
    }

    private String extractUserMessage(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            return "";
        }
        Matcher matcher = USER_QUESTION_PATTERN.matcher(prompt.trim());
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return prompt.trim();
    }

    private String visibleUserText(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return "";
        }
        String normalized = userMessage.trim();
        int blankLine = normalized.lastIndexOf("\n\n");
        if (blankLine >= 0 && blankLine + 2 < normalized.length()) {
            return normalized.substring(blankLine + 2).trim();
        }
        return normalized;
    }

    private boolean isPositiveConfirmation(String lower) {
        return lower.equals("yes")
            || lower.equals("y")
            || lower.equals("confirm")
            || lower.equals("confirmed")
            || lower.equals("go ahead")
            || lower.equals("do it")
            || lower.equals("proceed");
    }

    private boolean isNegativeConfirmation(String lower) {
        return lower.equals("no")
            || lower.equals("n")
            || lower.equals("reject")
            || lower.equals("decline")
            || lower.equals("cancel")
            || lower.equals("do not")
            || lower.equals("don't");
    }

    private String extractOrderNumber(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = ORDER_NUMBER_PATTERN.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private String actionIntent(String action, String actionParamsJson) {
        return """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "%1$s",
                  "action": "%1$s",
                  "confidence": 0.99,
                  "actionParams": {%2$s},
                  "requiresRetrieval": false,
                  "requiresGeneration": false,
                  "requiresTargetResolution": false
                }
              ],
              "orchestrationStrategy": "DIRECT_ACTION"
            }
            """.formatted(action, actionParamsJson).trim();
    }

    private String confirmationIntent(String type, String intent) {
        return """
            {
              "intents": [
                {
                  "type": "%s",
                  "intent": "%s",
                  "confidence": 1.0,
                  "requiresRetrieval": false,
                  "requiresGeneration": false,
                  "requiresTargetResolution": false
                }
              ],
              "orchestrationStrategy": "DIRECT_ACTION"
            }
            """.formatted(type, intent).trim();
    }

    private String informationIntent() {
        return """
            {
              "intents": [
                {
                  "type": "INFORMATION",
                  "intent": "chat_local_fallback",
                  "confidence": 0.7,
                  "requiresRetrieval": false,
                  "requiresGeneration": false,
                  "requiresTargetResolution": false,
                  "directAnswer": "I can help with demo order actions."
                }
              ],
              "orchestrationStrategy": "ADMIT_UNKNOWN"
            }
            """.trim();
    }

    private AIEmbeddingResponse deterministicEmbedding(String text) {
        long seed = (text == null ? "" : text).hashCode() & 0xffffffffL;
        Random random = new Random(seed);
        List<Double> vector = new ArrayList<>(EMBEDDING_DIMENSION);
        double sumSquares = 0.0d;
        for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
            double value = random.nextDouble() * 2.0d - 1.0d;
            vector.add(value);
            sumSquares += value * value;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm > 0.0d) {
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

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
