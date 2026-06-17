package ai.fabric.rag.service;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.core.AISearchService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.search.VectorSearchService;
import ai.fabric.service.VectorManagementService;
import ai.fabric.vector.VectorDatabase;
import ai.fabric.vector.VectorDatabaseServiceAdapter;
import ai.fabric.vector.memory.InMemoryVectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NoOpCacheManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RAGServiceMemoryVectorIntegrationTest {

    @Test
    void indexesAndRetrievesThroughMemoryVectorStoreWithSmokeEmbeddings() {
        RAGService ragService = offlineRagService();

        ragService.indexContent(
            "faq",
            "faq-headset",
            "Wireless headset pairing supports Bluetooth and noise cancellation.",
            Map.of("category", "audio", "tenant", "demo")
        );
        ragService.indexContent(
            "faq",
            "faq-charger",
            "Laptop charger warranty replacements are available for one year.",
            Map.of("category", "power", "tenant", "demo")
        );

        RAGResponse response = ragService.performRag(RAGRequest.builder()
            .query("wireless headset bluetooth")
            .entityType("faq")
            .limit(2)
            .threshold(0.1)
            .filters(Map.of("category", "audio"))
            .metadata(Map.of("userQuery", "Can my headset use Bluetooth?"))
            .requestId("rag-memory-smoke")
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getRequestId()).isEqualTo("rag-memory-smoke");
        assertThat(response.getOriginalQuery()).isEqualTo("Can my headset use Bluetooth?");
        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getId()).isEqualTo("faq-headset");
        assertThat(response.getDocuments().get(0).getType()).isEqualTo("faq");
        assertThat(response.getDocuments().get(0).getMetadata())
            .containsEntry("category", "audio")
            .containsEntry("tenant", "demo");
        assertThat(response.getContext())
            .contains("vectorSpace=faq id=faq-headset")
            .contains("Wireless headset pairing");
        assertThat(response.getMetadata())
            .containsEntry("embeddingProviderName", "smoke")
            .containsEntry("embeddingModel", "smoke")
            .containsEntry("searchSourceCount", 0)
            .containsEntry("searchSourcesDegraded", false);
    }

    @Test
    void removeContentDeletesIndexedMemoryVector() {
        RAGService ragService = offlineRagService();

        ragService.indexContent(
            "faq",
            "faq-returns",
            "Return policy allows refunds within thirty days.",
            Map.of("category", "returns")
        );
        assertThat(ragService.performRag(RAGRequest.builder()
            .query("refund return policy")
            .entityType("faq")
            .limit(1)
            .threshold(0.1)
            .build()).getDocuments()).hasSize(1);

        ragService.removeContent("faq", "faq-returns");

        RAGResponse response = ragService.performRag(RAGRequest.builder()
            .query("refund return policy")
            .entityType("faq")
            .limit(1)
            .threshold(0.1)
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments()).isEmpty();
        assertThat(response.getContext()).isEqualTo("No relevant context found.");
    }

    private RAGService offlineRagService() {
        AIProviderConfig config = new AIProviderConfig();
        config.setEmbeddingProvider("smoke");

        InMemoryVectorDatabaseService vectorStore = new InMemoryVectorDatabaseService(config);
        AIEmbeddingService embeddingService = new AIEmbeddingService(
            config,
            new SmokeKeywordEmbeddingProvider(),
            new NoOpCacheManager(),
            null
        );
        VectorSearchService vectorSearchService = new VectorSearchService(
            config,
            vectorStore,
            new NoOpCacheManager()
        );
        VectorManagementService vectorManagementService = new VectorManagementService(vectorStore);
        AISearchService searchService = new AISearchService(config, vectorSearchService, vectorManagementService);
        VectorDatabase vectorDatabase = new VectorDatabaseServiceAdapter(vectorStore);

        return new RAGService(config, embeddingService, vectorStore, vectorDatabase, searchService, null);
    }

    private static final class SmokeKeywordEmbeddingProvider implements EmbeddingProvider {

        private static final List<String> TERMS = List.of(
            "wireless",
            "headset",
            "bluetooth",
            "noise",
            "charger",
            "warranty",
            "return",
            "refund"
        );

        @Override
        public String getProviderName() {
            return "smoke";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
            String text = request != null ? request.getText() : "";
            return responseFor(text);
        }

        @Override
        public List<AIEmbeddingResponse> generateEmbeddings(List<String> texts) {
            List<AIEmbeddingResponse> responses = new ArrayList<>();
            if (texts != null) {
                for (String text : texts) {
                    responses.add(responseFor(text));
                }
            }
            return responses;
        }

        @Override
        public int getEmbeddingDimension() {
            return TERMS.size();
        }

        @Override
        public Map<String, Object> getStatus() {
            return Map.of(
                "provider", "smoke",
                "available", true,
                "dimension", TERMS.size(),
                "details", "offline deterministic keyword embeddings"
            );
        }

        private AIEmbeddingResponse responseFor(String text) {
            List<Double> vector = embed(text);
            return AIEmbeddingResponse.builder()
                .embedding(vector)
                .model("smoke-keyword")
                .dimensions(vector.size())
                .processingTimeMs(0L)
                .requestId("smoke-" + UUID.randomUUID())
                .build();
        }

        private List<Double> embed(String text) {
            String normalized = text == null ? "" : text.toLowerCase();
            Map<String, Double> termWeights = new LinkedHashMap<>();
            for (String term : TERMS) {
                termWeights.put(term, normalized.contains(term) ? 1.0 : 0.0);
            }

            List<Double> vector = new ArrayList<>(termWeights.values());
            double norm = Math.sqrt(vector.stream().mapToDouble(value -> value * value).sum());
            if (norm == 0.0) {
                return vector;
            }
            for (int i = 0; i < vector.size(); i++) {
                vector.set(i, vector.get(i) / norm);
            }
            return vector;
        }
    }
}
