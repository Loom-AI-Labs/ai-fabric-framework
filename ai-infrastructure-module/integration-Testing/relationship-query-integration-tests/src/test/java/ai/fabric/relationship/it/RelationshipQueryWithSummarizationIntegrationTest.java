package ai.fabric.relationship.it;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import ai.fabric.relationship.it.config.TestRelationshipQueryAccessControlPolicy;
import ai.fabric.relationship.it.entity.BrandEntity;
import ai.fabric.relationship.it.entity.ProductEntity;
import ai.fabric.relationship.it.repository.BrandRepository;
import ai.fabric.relationship.it.repository.ProductRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

    @SpringBootTest(
        classes = RelationshipQueryIntegrationTestApplication.class,
        properties = {
        "ai.infrastructure.relationship.enabled=true",
        "ai.infrastructure.relationship.enable-orchestrator-integration=true",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:rq_summarization;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driverClassName=org.h2.Driver",

        "ai.providers.llm-provider=test",
        "ai.providers.enable-fallback=false",
        "ai.service.features.enable-embeddings=false",

        "ai.infrastructure.relationship.enable-vector-search=false",
        "ai.infrastructure.relationship.fallback-to-vector-search=false",
        "ai.infrastructure.relationship.fallback-to-simple-search=false",

        "ai.relationship-query.post-action-generation.enabled=true",
        "ai.relationship-query.post-action-generation.max-items=10",
        "ai.relationship-query.post-action-generation.max-chars=8000"
    }
)
@Import({
    TestRelationshipQueryAccessControlPolicy.class,
    RelationshipQueryWithSummarizationIntegrationTest.TestProviderConfiguration.class
})
class RelationshipQueryWithSummarizationIntegrationTest {

    @TestConfiguration
    static class TestProviderConfiguration {
        @Bean
        AIProvider testAiProvider() {
            return new TestAiProvider();
        }

        @Bean
        EntityAccessPolicy allowAllEntityAccessPolicy() {
            return (userId, entity) -> true;
        }
    }

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RAGOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        brandRepository.deleteAll();

        BrandEntity nike = new BrandEntity();
        nike.setName("Nike");
        brandRepository.save(nike);

        BrandEntity adidas = new BrandEntity();
        adidas.setName("Adidas");
        brandRepository.save(adidas);

        ProductEntity matching = new ProductEntity();
        matching.setName("Blue Runner Running Shoes");
        matching.setColor("blue");
        matching.setPrice(new BigDecimal("85.00"));
        matching.setStatus("ACTIVE");
        matching.setBrand(nike);
        productRepository.save(matching);

        ProductEntity nonMatching = new ProductEntity();
        nonMatching.setName("Adidas Runner Shoes Elite");
        nonMatching.setColor("red");
        nonMatching.setPrice(new BigDecimal("110.00"));
        nonMatching.setStatus("ACTIVE");
        nonMatching.setBrand(adidas);
        productRepository.save(nonMatching);
    }

    @Test
    void relationshipQueryShouldExecuteThenSummarizeUsingProgressiveIntentExtraction() {
        OrchestrationContext ctx = OrchestrationContext.forUser("user-1");

        OrchestrationResult result = orchestrator.orchestrate(
            "Show me blue shoes under $100 from Nike, then summarize the results.",
            ctx
        );

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.isSuccess())
            .withFailMessage(
                "Expected orchestrator success but got type=%s success=%s message=%s dataKeys=%s metadataKeys=%s",
                result.getType(),
                result.isSuccess(),
                result.getMessage(),
                result.getData() != null ? result.getData().keySet() : null,
                result.getMetadata() != null ? result.getMetadata().keySet() : null
            )
            .isTrue();
        Assertions.assertThat(result.getMessage()).containsIgnoringCase("nike");
        Assertions.assertThat(result.getMessage()).containsIgnoringCase("blue");
        Assertions.assertThat(result.getMessage()).contains("85");

        Assertions.assertThat(result.getData())
            .containsKey("postActionGeneration")
            .containsKey("summary");

        Assertions.assertThat(result.getMetadata()).containsKey("extractionDiagnostics");
        Object diagnostics = result.getMetadata().get("extractionDiagnostics");
        Assertions.assertThat(diagnostics).isInstanceOf(Map.class);
        Assertions.assertThat(((Map<?, ?>) diagnostics).get("extractionPath")).isEqualTo("repair");
    }

    private static final class TestAiProvider implements AIProvider {

        private final AtomicBoolean intentExtractionFailedOnce = new AtomicBoolean(false);

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public AIGenerationResponse generateContent(AIGenerationRequest request) {
            String generationType = request != null ? request.getGenerationType() : null;

            if ("intent_extraction".equals(generationType)) {
                // Force a structural failure so ProgressiveIntentExtractionEngine runs the repair strategy.
                if (intentExtractionFailedOnce.compareAndSet(false, true)) {
                    return generationResponse("THIS IS NOT JSON", "test-model");
                }
            }

            if ("intent_extraction_repair".equals(generationType)) {
                return generationResponse(repairedIntentJson(), "test-model");
            }

            if ("planning".equals(generationType)) {
                return generationResponse(productNikeBlueUnder100PlanJson(), "test-model");
            }

            if ("relationship_query_post_action_generation".equals(generationType)) {
                String prompt = request != null ? request.getPrompt() : null;
                Assertions.assertThat(prompt).contains("FACTS");
                Assertions.assertThat(prompt).contains("Blue Runner Running Shoes");
                return generationResponse("Summary: Nike has 1 blue running shoe under $100: Blue Runner Running Shoes ($85).", "test-model");
            }

            throw new IllegalStateException("Unexpected generationType in test provider: " + generationType);
        }

        @Override
        public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
            throw new UnsupportedOperationException("Embeddings are disabled for this integration test");
        }

        @Override
        public ProviderStatus getStatus() {
            return ProviderStatus.builder()
                .healthy(true)
                .available(true)
                .build();
        }

        @Override
        public ProviderConfig getConfig() {
            return ProviderConfig.builder()
                .enabled(true)
                .build();
        }

        private static AIGenerationResponse generationResponse(String content, String model) {
            return AIGenerationResponse.builder()
                .content(content)
                .model(model)
                .build();
        }

        private static String repairedIntentJson() {
            return """
                {
                  "intents": [
                    {
                      "type": "ACTION",
                      "intent": null,
                      "confidence": 0.95,
                      "action": "relationship_query",
                      "actionParams": {
                        "query": "Show me blue shoes under $100 from Nike",
                        "entityTypes": ["product"],
                        "returnMode": "FULL"
                      },
                      "vectorSpace": null,
                      "requiresRetrieval": false,
                      "requiresGeneration": true,
                      "generationInstructions": "Summarize the results in 1-2 sentences.",
                      "needsAdvancedRAG": false,
                      "optimizedQuery": null,
                      "nextStepRecommended": null
                    }
                  ],
                  "compound": false,
                  "orchestrationStrategy": "DIRECT_ACTION",
                  "metadata": {}
                }
                """;
        }

        private static String productNikeBlueUnder100PlanJson() {
            return """
                {
                  "originalQuery": "Show me blue shoes under $100 from Nike",
                  "semanticQuery": "Show me blue shoes under $100 from Nike",
                  "primaryEntityType": "product",
                  "candidateEntityTypes": ["product", "brand"],
                  "relationshipPaths": [
                    {
                      "fromEntityType": "product",
                      "relationshipType": "brand",
                      "toEntityType": "brand",
                      "direction": "FORWARD",
                      "optional": false,
                      "conditions": [
                        {
                          "field": "name",
                          "operator": "EQUALS",
                          "value": "Nike",
                          "entityType": "brand",
                          "caseSensitive": true
                        }
                      ]
                    }
                  ],
                  "directFilters": {
                    "product": [
                      {
                        "field": "color",
                        "operator": "LIKE",
                        "value": "%blue%",
                        "entityType": "product",
                        "caseSensitive": true
                      },
                      {
                        "field": "price",
                        "operator": "LESS_THAN",
                        "value": 100,
                        "entityType": "product",
                        "caseSensitive": true
                      }
                    ]
                  },
                  "relationshipFilters": {},
                  "metadataFilters": {},
                  "queryStrategy": "RELATIONSHIP",
                  "needsSemanticSearch": false,
                  "confidence": 0.9,
                  "context": {}
                }
                """;
        }
    }
}
