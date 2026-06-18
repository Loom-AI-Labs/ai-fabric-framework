package com.ai.fabric.realapps.crm.ai;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CrmLocalLlmProviderTest {

    private final CrmLocalLlmProvider provider = new CrmLocalLlmProvider();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generatesDealPlanForAccountQuery() throws Exception {
        AIGenerationResponse response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("User Query: \"show won deals for Globex\"")
            .build());

        JsonNode plan = objectMapper.readTree(response.getContent());

        assertThat(response.getModel()).isEqualTo("crm-local");
        assertThat(plan.path("primaryEntityType").asText()).isEqualTo("deal");
        assertThat(plan.path("directFilters").path("deal").get(0).path("value").asText()).isEqualTo("WON");
        assertThat(plan.path("relationshipPaths").get(0).path("conditions").get(0).path("value").asText())
            .isEqualTo("Globex");
        assertThat(plan.path("needsSemanticSearch").asBoolean()).isFalse();
    }

    @Test
    void generatesAccountPlanWithRegionAndRevenueFilters() throws Exception {
        AIGenerationResponse response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("User Query: \"accounts in EMEA with revenue over 500000\"")
            .build());

        JsonNode accountFilters = objectMapper.readTree(response.getContent())
            .path("directFilters")
            .path("account");

        assertThat(accountFilters.size()).isEqualTo(2);
        assertThat(accountFilters.get(0).path("field").asText()).isEqualTo("account.region");
        assertThat(accountFilters.get(0).path("value").asText()).isEqualTo("EMEA");
        assertThat(accountFilters.get(1).path("field").asText()).isEqualTo("account.annualRevenue");
        assertThat(accountFilters.get(1).path("value").asInt()).isEqualTo(500000);
    }

    @Test
    void generatesStableNormalizedEmbeddings() {
        AIEmbeddingRequest request = AIEmbeddingRequest.builder()
            .text("crm query planning")
            .build();

        AIEmbeddingResponse first = provider.generateEmbedding(request);
        AIEmbeddingResponse second = provider.generateEmbedding(request);

        assertThat(first.getModel()).isEqualTo("crm-local");
        assertThat(first.getDimensions()).isEqualTo(CrmLocalLlmProvider.EMBEDDING_DIMENSION);
        assertThat(first.getEmbedding()).hasSize(CrmLocalLlmProvider.EMBEDDING_DIMENSION);
        assertThat(first.getEmbedding()).isEqualTo(second.getEmbedding());
        assertThat(l2Norm(first.getEmbedding())).isCloseTo(1.0, within(1.0e-12));
        assertThat(provider.generateEmbedding(null).getEmbedding()).hasSize(CrmLocalLlmProvider.EMBEDDING_DIMENSION);
    }

    @Test
    void exposesOperationalStatusAndValidConfig() {
        ProviderStatus status = provider.getStatus();
        ProviderConfig config = provider.getConfig();

        assertThat(provider.getProviderName()).isEqualTo("crm-local");
        assertThat(provider.isAvailable()).isTrue();
        assertThat(status.isOperational()).isTrue();
        assertThat(status.getDetails()).isEqualTo("offline deterministic provider (relationship query planning)");
        assertThat(config.isValid()).isTrue();
        assertThat(config.getApiKey()).isEqualTo("crm-local-key");
        assertThat(config.getBaseUrl()).isEqualTo("crm://local");
        assertThat(config.getDefaultModel()).isEqualTo("crm-local");
    }

    private static double l2Norm(List<Double> vector) {
        return Math.sqrt(vector.stream()
            .mapToDouble(value -> value * value)
            .sum());
    }
}
