package com.ai.fabric.examples.governedactions.provider;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QuickstartAIProvider implements AIProvider {

    private static final String PROVIDER_NAME = "quickstart";

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}");

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

        String prompt = request != null && request.getPrompt() != null
                ? request.getPrompt()
                : "";

        String userQuery = extractUserQuery(prompt).toLowerCase();

        String content = buildIntentResponse(userQuery);

        return AIGenerationResponse.builder()
                .id("quickstart-" + UUID.randomUUID())
                .requestId(request != null ? request.getEntityId() : null)
                .content(content)
                .model(PROVIDER_NAME)
                .tokensUsed(0)
                .confidence(1.0)
                .processingTimeMs(0L)
                .generatedAt(LocalDateTime.now())
                .status("OK")
                .build();
    }

    private String extractUserQuery(String prompt) {

        String marker = "User's question is: (";

        int start = prompt.indexOf(marker);

        if (start == -1) {
            return prompt.trim();
        }

        start += marker.length();

        int end = prompt.lastIndexOf(')');

        String content = end > start
                ? prompt.substring(start, end)
                : prompt.substring(start);

        /*
         * When a confirmation is pending, AI Fabric prepends information
         * about that pending action before the actual user message.
         *
         * Example:
         *
         * PENDING ACTION ...
         * action=update_email
         *
         * yes
         *
         * We only want the actual user message: "yes".
         */
        if (content.contains("PENDING ACTION")) {

            int separator = content.lastIndexOf("\n\n");

            if (separator != -1) {
                return content.substring(separator + 2).trim();
            }
        }

        return content.trim();
    }

    private String buildIntentResponse(String userQuery) {

        if (userQuery.equals("yes") || userQuery.equals("confirm")) {
            return """
                    {
                      "intents": [
                        {
                          "type": "CONFIRMATION_POSITIVE",
                          "intent": "confirmation_positive",
                          "confidence": 1.0
                        }
                      ]
                    }
                    """;
        }

        if (userQuery.equals("no") || userQuery.equals("reject")) {
            return """
                    {
                      "intents": [
                        {
                          "type": "CONFIRMATION_NEGATIVE",
                          "intent": "confirmation_negative",
                          "confidence": 1.0
                        }
                      ]
                    }
                    """;
        }

        if (userQuery.contains("email")) {

            Matcher matcher = EMAIL_PATTERN.matcher(userQuery);

            if (matcher.find()) {

                String email = matcher.group();

                return """
                        {
                          "intents": [
                            {
                              "type": "ACTION",
                              "intent": "update_email",
                              "action": "update_email",
                              "confidence": 1.0,
                              "actionParams": {
                                "email": "%s"
                              }
                            }
                          ]
                        }
                        """.formatted(email);
            }

            return """
                    {
                      "intents": [
                        {
                          "type": "ACTION",
                          "intent": "update_email",
                          "action": "update_email",
                          "confidence": 1.0,
                          "actionParams": {}
                        }
                      ]
                    }
                    """;
        }

        if (userQuery.contains("account")) {
            return """
                    {
                      "intents": [
                        {
                          "type": "ACTION",
                          "intent": "get_account",
                          "action": "get_account",
                          "confidence": 1.0,
                          "actionParams": {}
                        }
                      ]
                    }
                    """;
        }

        return """
                {
                  "intents": [
                    {
                      "type": "OUT_OF_SCOPE",
                      "intent": "out_of_scope",
                      "confidence": 1.0
                    }
                  ]
                }
                """;
    }

    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        throw new UnsupportedOperationException(
                "Embeddings are disabled in this quickstart"
        );
    }

    @Override
    public ProviderStatus getStatus() {
        return ProviderStatus.builder()
                .providerName(PROVIDER_NAME)
                .available(true)
                .healthy(true)
                .successRate(1.0)
                .averageResponseTime(0.0)
                .lastUpdated(LocalDateTime.now())
                .details("Offline deterministic quickstart provider")
                .build();
    }

    @Override
    public ProviderConfig getConfig() {
        return ProviderConfig.builder()
                .providerName(PROVIDER_NAME)
                .enabled(true)
                .apiKey("not-required")
                .baseUrl("quickstart://local")
                .defaultModel(PROVIDER_NAME)
                .timeoutSeconds(1)
                .maxRetries(0)
                .build();
    }
}