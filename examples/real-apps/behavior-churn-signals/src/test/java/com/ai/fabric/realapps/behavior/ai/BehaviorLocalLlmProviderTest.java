package com.ai.fabric.realapps.behavior.ai;

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

class BehaviorLocalLlmProviderTest {

    private final BehaviorLocalLlmProvider provider = new BehaviorLocalLlmProvider();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generatesBehaviorAnalysisJsonFromPromptSignals() throws Exception {
        AIGenerationResponse response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("payment_failed payment failed cancel complaint refund login upgrade")
            .build());

        JsonNode json = objectMapper.readTree(response.getContent());

        assertThat(response.getModel()).isEqualTo("behavior-local");
        assertThat(json.path("segment").asText()).isEqualTo("at_risk");
        assertThat(json.path("sentiment").path("label").asText()).isNotBlank();
        assertThat(json.path("churn").path("risk").asDouble()).isGreaterThan(0.7);
        assertThat(json.path("patterns")).hasSize(10);
        assertThat(json.path("insights").path("signals").path("payment_failed").asInt()).isEqualTo(2);
    }

    @Test
    void distinguishesRegressionAndSilentChurnSignals() throws Exception {
        AIGenerationResponse regression = provider.generateContent(AIGenerationRequest.builder()
            .prompt("feature_error timeout usage_drop support complaint after release")
            .build());
        JsonNode regressionJson = objectMapper.readTree(regression.getContent());

        assertThat(regressionJson.path("segment").asText()).isEqualTo("product_regression_risk");
        assertThat(regressionJson.path("recommendations").toString()).contains("Escalate product regression");

        AIGenerationResponse silent = provider.generateContent(AIGenerationRequest.builder()
            .prompt("usage_drop no_login no login quiet account")
            .build());
        JsonNode silentJson = objectMapper.readTree(silent.getContent());

        assertThat(silentJson.path("segment").asText()).isEqualTo("quiet_disengagement");
        assertThat(silentJson.path("churn").path("reason").asText()).contains("Quiet disengagement");
    }

    @Test
    void recoveryEventsReduceChurnPressure() throws Exception {
        AIGenerationResponse before = provider.generateContent(AIGenerationRequest.builder()
            .prompt("payment_failed payment_failed cancel support_complaint usage_drop")
            .build());
        AIGenerationResponse after = provider.generateContent(AIGenerationRequest.builder()
            .prompt("""
                payment_failed payment_failed cancel support_complaint usage_drop
                payment_succeeded login feature_used usage_recovery positive_feedback billing issue resolved active again
                """)
            .build());

        JsonNode beforeJson = objectMapper.readTree(before.getContent());
        JsonNode afterJson = objectMapper.readTree(after.getContent());

        assertThat(afterJson.path("churn").path("risk").asDouble())
            .isLessThan(beforeJson.path("churn").path("risk").asDouble());
        assertThat(afterJson.path("sentiment").path("score").asDouble())
            .isGreaterThan(beforeJson.path("sentiment").path("score").asDouble());
        assertThat(afterJson.path("patterns").toString()).contains("recovery_signals");
    }

    @Test
    void generatesAgenticUiComponentPlanForLayoutRequests() throws Exception {
        AIGenerationResponse response = provider.generateContent(AIGenerationRequest.builder()
            .generationType("agentic-ui-layout")
            .prompt("Current action family is RETENTION_OFFER")
            .build());

        JsonNode json = objectMapper.readTree(response.getContent());

        assertThat(response.getModel()).isEqualTo("behavior-local");
        assertThat(json.path("layout").asText()).isEqualTo("behavior-agentic-workspace");
        assertThat(json.path("components")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(json.path("components").get(0).path("name").asText()).isEqualTo("RISK_SUMMARY_CARD");
        assertThat(json.path("components").get(0).has("props")).isFalse();
        assertThat(json.path("components").toString()).contains("RETENTION_OFFER_PANEL");
    }

    @Test
    void generatesStableNormalizedEmbeddings() {
        AIEmbeddingRequest request = AIEmbeddingRequest.builder()
            .text("customer behavior signal")
            .build();

        AIEmbeddingResponse first = provider.generateEmbedding(request);
        AIEmbeddingResponse second = provider.generateEmbedding(request);

        assertThat(first.getModel()).isEqualTo("behavior-local");
        assertThat(first.getDimensions()).isEqualTo(BehaviorLocalLlmProvider.EMBEDDING_DIMENSION);
        assertThat(first.getEmbedding()).hasSize(BehaviorLocalLlmProvider.EMBEDDING_DIMENSION);
        assertThat(first.getEmbedding()).isEqualTo(second.getEmbedding());
        assertThat(l2Norm(first.getEmbedding())).isCloseTo(1.0, within(1.0e-12));
        assertThat(provider.generateEmbedding(null).getEmbedding()).hasSize(BehaviorLocalLlmProvider.EMBEDDING_DIMENSION);
    }

    @Test
    void exposesOperationalStatusAndValidConfig() {
        ProviderStatus status = provider.getStatus();
        ProviderConfig config = provider.getConfig();

        assertThat(provider.getProviderName()).isEqualTo("behavior-local");
        assertThat(provider.isAvailable()).isTrue();
        assertThat(status.isOperational()).isTrue();
        assertThat(status.getDetails()).isEqualTo("offline deterministic provider (behavior analysis JSON)");
        assertThat(config.isValid()).isTrue();
        assertThat(config.getApiKey()).isEqualTo("behavior-local-key");
        assertThat(config.getBaseUrl()).isEqualTo("behavior://local");
        assertThat(config.getDefaultModel()).isEqualTo("behavior-local");
    }

    private static double l2Norm(List<Double> vector) {
        return Math.sqrt(vector.stream()
            .mapToDouble(value -> value * value)
            .sum());
    }
}
