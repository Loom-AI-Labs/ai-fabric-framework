package com.ai.fabric.realapps.crm.ai;

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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Offline deterministic LLM provider that returns RelationshipQuery plans for a small set of demo queries.
 *
 * <p>This exists only to make the Real_App runnable without external keys.</p>
 */
@Slf4j
@Component
public class CrmLocalLlmProvider implements AIProvider {

    static final String PROVIDER_NAME = "crm-local";
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
        String userQuery = extractUserQuery(prompt);
        String planJson = buildPlan(userQuery);
        return AIGenerationResponse.builder()
            .content(planJson)
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
            .details("offline deterministic provider (relationship query planning)")
            .build();
    }

    @Override
    public ProviderConfig getConfig() {
        return ProviderConfig.builder()
            .providerName(getProviderName())
            .enabled(true)
            .apiKey("crm-local-key")
            .baseUrl("crm://local")
            .defaultModel(PROVIDER_NAME)
            .timeoutSeconds(1)
            .maxRetries(0)
            .build();
    }

    private String extractUserQuery(String prompt) {
        if (prompt == null) {
            return "";
        }
        int idx = prompt.indexOf("User Query:");
        if (idx < 0) {
            return prompt;
        }
        String tail = prompt.substring(idx);
        int firstQuote = tail.indexOf('"');
        int secondQuote = firstQuote >= 0 ? tail.indexOf('"', firstQuote + 1) : -1;
        if (firstQuote >= 0 && secondQuote > firstQuote) {
            return tail.substring(firstQuote + 1, secondQuote);
        }
        return tail;
    }

    private String buildPlan(String userQuery) {
        String q = userQuery == null ? "" : userQuery.trim();
        String lower = q.toLowerCase(Locale.ROOT);

        String accountName = extractAccountName(lower);

        if (lower.contains("deal")) {
            String stage = lower.contains("won") ? "WON" : lower.contains("lost") ? "LOST" : "OPEN";
            return """
                {
                  "primaryEntityType": "deal",
                  "candidateEntityTypes": ["deal","account"],
                  "relationshipPaths": [
                    {
                      "fromEntityType": "deal",
                      "relationshipType": "account",
                      "toEntityType": "account",
                      "direction": "FORWARD",
                      "optional": false,
                      "conditions": [
                        {"field":"account.name","operator":"EQUALS","value":"%s","entityType":"account"}
                      ]
                    }
                  ],
                  "directFilters": {
                    "deal": [
                      {"field":"deal.stage","operator":"EQUALS","value":"%s","entityType":"deal"}
                    ]
                  },
                  "relationshipFilters": {},
                  "metadataFilters": {},
                  "queryStrategy": "RELATIONSHIP",
                  "needsSemanticSearch": false,
                  "confidence": 0.92,
                  "semanticQuery": "%s deals %s"
                }
                """.formatted(accountName, stage, stage, accountName);
        }

        if (lower.contains("ticket") || lower.contains("case")) {
            String priority = lower.contains("high") ? "HIGH" : lower.contains("low") ? "LOW" : "MEDIUM";
            String status = lower.contains("resolved") ? "RESOLVED" : lower.contains("pending") ? "PENDING" : "OPEN";
            return """
                {
                  "primaryEntityType": "support-ticket",
                  "candidateEntityTypes": ["support-ticket","account"],
                  "relationshipPaths": [
                    {
                      "fromEntityType": "support-ticket",
                      "relationshipType": "account",
                      "toEntityType": "account",
                      "direction": "FORWARD",
                      "optional": false,
                      "conditions": [
                        {"field":"account.name","operator":"EQUALS","value":"%s","entityType":"account"}
                      ]
                    }
                  ],
                  "directFilters": {
                    "support-ticket": [
                      {"field":"support-ticket.status","operator":"EQUALS","value":"%s","entityType":"support-ticket"},
                      {"field":"support-ticket.priority","operator":"EQUALS","value":"%s","entityType":"support-ticket"}
                    ]
                  },
                  "relationshipFilters": {},
                  "metadataFilters": {},
                  "queryStrategy": "RELATIONSHIP",
                  "needsSemanticSearch": false,
                  "confidence": 0.9,
                  "semanticQuery": "%s %s tickets %s"
                }
                """.formatted(accountName, status, priority, priority, status, accountName);
        }

        if (lower.contains("contact")) {
            return """
                {
                  "primaryEntityType": "contact",
                  "candidateEntityTypes": ["contact","account"],
                  "relationshipPaths": [
                    {
                      "fromEntityType": "contact",
                      "relationshipType": "account",
                      "toEntityType": "account",
                      "direction": "FORWARD",
                      "optional": false,
                      "conditions": [
                        {"field":"account.name","operator":"EQUALS","value":"%s","entityType":"account"}
                      ]
                    }
                  ],
                  "directFilters": {},
                  "relationshipFilters": {},
                  "metadataFilters": {},
                  "queryStrategy": "RELATIONSHIP",
                  "needsSemanticSearch": false,
                  "confidence": 0.88,
                  "semanticQuery": "contacts for %s"
                }
                """.formatted(accountName, accountName);
        }

        if (lower.contains("account")) {
            Optional<String> region = extractRegion(lower);
            Optional<String> revenue = extractRevenueThreshold(lower);
            String regionFilter = region
                .map(value -> """
                    {"field":"account.region","operator":"EQUALS","value":"%s","entityType":"account"}
                  """.formatted(value))
                .orElse("");
            String revenueFilter = revenue
                .map(value -> """
                    {"field":"account.annualRevenue","operator":"GREATER_THAN","value":%s,"entityType":"account"}
                  """.formatted(value))
                .orElse("");

            String filters = joinPresent(regionFilter, revenueFilter);
            return """
                {
                  "primaryEntityType": "account",
                  "candidateEntityTypes": ["account"],
                  "relationshipPaths": [],
                  "directFilters": {
                    "account": [%s]
                  },
                  "relationshipFilters": {},
                  "metadataFilters": {},
                  "queryStrategy": "RELATIONSHIP",
                  "needsSemanticSearch": false,
                  "confidence": 0.86,
                  "semanticQuery": "accounts %s"
                }
                """.formatted(filters, q.replace("\"", ""));
        }

        return """
            {
              "primaryEntityType": "account",
              "candidateEntityTypes": ["account"],
              "relationshipPaths": [],
              "directFilters": {},
              "relationshipFilters": {},
              "metadataFilters": {},
              "queryStrategy": "RELATIONSHIP",
              "needsSemanticSearch": false,
              "confidence": 0.6,
              "semanticQuery": "%s"
            }
            """.formatted(q.replace("\"", ""));
    }

    private String extractAccountName(String lower) {
        if (lower.contains("acme")) {
            return "Acme";
        }
        if (lower.contains("globex")) {
            return "Globex";
        }
        if (lower.contains("initech")) {
            return "Initech";
        }
        return "Acme";
    }

    private Optional<String> extractRegion(String lower) {
        if (lower.contains("emea")) {
            return Optional.of("EMEA");
        }
        if (lower.contains("apac")) {
            return Optional.of("APAC");
        }
        if (lower.contains("na")) {
            return Optional.of("NA");
        }
        return Optional.empty();
    }

    private Optional<String> extractRevenueThreshold(String lower) {
        String token = "revenue over";
        int idx = lower.indexOf(token);
        if (idx < 0) {
            return Optional.empty();
        }
        String tail = lower.substring(idx + token.length()).trim();
        StringBuilder number = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char ch = tail.charAt(i);
            if (Character.isDigit(ch)) {
                number.append(ch);
            } else if (!number.isEmpty()) {
                break;
            }
        }
        return number.isEmpty() ? Optional.empty() : Optional.of(number.toString());
    }

    private String joinPresent(String first, String second) {
        StringBuilder builder = new StringBuilder();
        if (first != null && !first.isBlank()) {
            builder.append(first);
        }
        if (second != null && !second.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(second);
        }
        return builder.toString();
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
}
