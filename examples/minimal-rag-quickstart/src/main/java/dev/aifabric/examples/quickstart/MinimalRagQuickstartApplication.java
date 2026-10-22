package dev.aifabric.examples.quickstart;

import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.vector.lucene.LuceneVectorAutoConfiguration;
import ai.fabric.vector.lucene.LuceneVectorDatabaseService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SpringBootApplication(exclude = {
    AIInfrastructureAutoConfiguration.class,
    LuceneVectorAutoConfiguration.class
})
public class MinimalRagQuickstartApplication {

    private static final String DOCUMENT =
        "AI Fabric uses vector embeddings to retrieve relevant documents for semantic search and RAG.";
    private static final String QUESTION =
        "How can I retrieve documents for a RAG application?";

    public static void main(String[] args) {
        SpringApplication.run(MinimalRagQuickstartApplication.class, args);
    }

    @Bean
    EmbeddingProvider embeddingProvider() {
        return new DemoEmbeddingProvider();
    }

    @Bean(destroyMethod = "cleanup")
    VectorDatabaseService vectorDatabaseService() {
        return new LuceneVectorDatabaseService(new AIProviderConfig());
    }

    @Bean
    CommandLineRunner semanticRetrievalDemo(
        EmbeddingProvider embeddings,
        VectorDatabaseService vectors
    ) {
        return args -> {
            List<Double> documentVector = embed(embeddings, DOCUMENT);
            vectors.storeVector(
                "guide",
                "getting-started",
                DOCUMENT,
                documentVector,
                Map.of("source", "quickstart")
            );

            AISearchRequest request = AISearchRequest.builder()
                .query(QUESTION)
                .entityType("guide")
                .limit(1)
                .threshold(0.0)
                .build();
            AISearchResponse response = vectors.search(embed(embeddings, QUESTION), request);

            System.out.println("Indexed document: " + DOCUMENT);
            System.out.println("Semantic query:  " + QUESTION);
            System.out.println("Top result:      " + response.getResults().getFirst().get("content"));
        };
    }

    private static List<Double> embed(EmbeddingProvider provider, String text) {
        return provider.generateEmbedding(AIEmbeddingRequest.builder().text(text).build()).getEmbedding();
    }


    static final class DemoEmbeddingProvider implements EmbeddingProvider {
        private static final List<String> CONCEPTS = List.of(
            "fabric", "vector", "embedding", "retrieve", "document", "semantic", "rag", "search"
        );

        @Override
        public String getProviderName() {
            return "demo";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
            String normalized = request.getText().toLowerCase(Locale.ROOT);
            List<Double> values = new ArrayList<>(CONCEPTS.size());
            for (String concept : CONCEPTS) {
                values.add(normalized.contains(concept) ? 1.0 : 0.0);
            }
            return AIEmbeddingResponse.builder()
                .embedding(values)
                .dimensions(values.size())
                .model("demo-concept-vector")
                .processingTimeMs(0L)
                .requestId("local-demo")
                .build();
        }

        @Override
        public List<AIEmbeddingResponse> generateEmbeddings(List<String> texts) {
            return texts.stream()
                .map(text -> generateEmbedding(AIEmbeddingRequest.builder().text(text).build()))
                .toList();
        }

        @Override
        public int getEmbeddingDimension() {
            return CONCEPTS.size();
        }

        @Override
        public Map<String, Object> getStatus() {
            return Map.of("provider", getProviderName(), "available", true);
        }
    }
}
