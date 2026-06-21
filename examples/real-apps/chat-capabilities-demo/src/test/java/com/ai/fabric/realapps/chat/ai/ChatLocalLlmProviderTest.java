package com.ai.fabric.realapps.chat.ai;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatLocalLlmProviderTest {

    private final ChatLocalLlmProvider provider = new ChatLocalLlmProvider();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsCancelPurchaseOrderIntentWithOrderNumber() throws Exception {
        AIGenerationResponse response = provider.generateContent(AIGenerationRequest.builder()
            .entityId("intent-1")
            .entityType("intent_extraction")
            .generationType("intent_extraction")
            .prompt("User's question is: (Cancel order PO-123-ABC)")
            .build());

        JsonNode intent = objectMapper.readTree(response.getContent()).path("intents").get(0);
        assertThat(intent.path("type").asText()).isEqualTo("ACTION");
        assertThat(intent.path("action").asText()).isEqualTo("cancel_purchase_order");
        assertThat(intent.path("actionParams").path("orderNumber").asText()).isEqualTo("PO-123-ABC");
    }

    @Test
    void returnsConfirmationForPendingActionFollowUp() throws Exception {
        AIGenerationResponse response = provider.generateContent(AIGenerationRequest.builder()
            .entityId("intent-2")
            .entityType("intent_extraction")
            .generationType("intent_extraction")
            .prompt("""
                User's question is: (PENDING ACTION (requires confirmation):
                - action=offer_order_discount

                no)
                """)
            .build());

        JsonNode intent = objectMapper.readTree(response.getContent()).path("intents").get(0);
        assertThat(intent.path("type").asText()).isEqualTo("CONFIRMATION_NEGATIVE");
        assertThat(intent.path("intent").asText()).isEqualTo("reject");
    }

    @Test
    void returnsDeterministicEmbeddings() {
        var first = provider.generateEmbedding(AIEmbeddingRequest.builder().text("same text").build());
        var second = provider.generateEmbedding(AIEmbeddingRequest.builder().text("same text").build());

        assertThat(first.getDimensions()).isEqualTo(ChatLocalLlmProvider.EMBEDDING_DIMENSION);
        assertThat(first.getEmbedding()).hasSize(ChatLocalLlmProvider.EMBEDDING_DIMENSION);
        assertThat(first.getEmbedding()).isEqualTo(second.getEmbedding());
    }
}
