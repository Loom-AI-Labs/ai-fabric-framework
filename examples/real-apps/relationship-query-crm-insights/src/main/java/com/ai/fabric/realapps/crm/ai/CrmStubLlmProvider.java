package com.ai.fabric.realapps.crm.ai;

import com.ai.infrastructure.dto.AIEmbeddingRequest;
import com.ai.infrastructure.dto.AIEmbeddingResponse;
import com.ai.infrastructure.dto.AIGenerationRequest;
import com.ai.infrastructure.dto.AIGenerationResponse;
import com.ai.infrastructure.provider.AIProvider;
import com.ai.infrastructure.provider.ProviderConfig;
import com.ai.infrastructure.provider.ProviderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Offline stub LLM provider that returns deterministic RelationshipQuery plans for a small set of demo queries.
 *
 * <p>This exists only to make the Real_App runnable without external keys.</p>
 */
@Slf4j
@Component
public class CrmStubLlmProvider implements AIProvider {

    @Override
    public String getProviderName() {
        return "crm-stub";
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
            .model("crm-stub")
            .requestId("gen-" + UUID.randomUUID())
            .build();
    }

    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        throw new UnsupportedOperationException("crm-stub does not support embeddings");
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
            .details("offline deterministic stub (relationship query planning only)")
            .build();
    }

    @Override
    public ProviderConfig getConfig() {
        return ProviderConfig.builder()
            .providerName(getProviderName())
            .enabled(true)
            .apiKey("stub")
            .baseUrl("stub://local")
            .defaultModel("crm-stub")
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
            String region = lower.contains("emea") ? "EMEA" : lower.contains("apac") ? "APAC" : lower.contains("na") ? "NA" : null;
            String revenue = extractRevenueThreshold(lower);
            String regionFilter = region != null
                ? """
                    {"field":"account.region","operator":"EQUALS","value":"%s","entityType":"account"}
                  """.formatted(region)
                : null;
            String revenueFilter = revenue != null
                ? """
                    {"field":"account.annualRevenue","operator":"GREATER_THAN","value":%s,"entityType":"account"}
                  """.formatted(revenue)
                : null;

            String filters = joinNonNull(regionFilter, revenueFilter);
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

    private String extractRevenueThreshold(String lower) {
        String token = "revenue over";
        int idx = lower.indexOf(token);
        if (idx < 0) {
            return null;
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
        return number.isEmpty() ? null : number.toString();
    }

    private String joinNonNull(String first, String second) {
        StringBuilder builder = new StringBuilder();
        if (first != null) {
            builder.append(first);
        }
        if (second != null) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(second);
        }
        return builder.toString();
    }
}
