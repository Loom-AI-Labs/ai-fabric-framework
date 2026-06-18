package ai.fabric.it;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.extraction.ProgressiveIntentExtractionEngine;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.repository.TestProductRepository;
import ai.fabric.service.AICapabilityService;
import ai.fabric.service.VectorManagementService;
import ai.fabric.spi.RAGProvider;
import ai.fabric.it.support.RealAPITestSupport;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("real-api-test")
@Transactional
public class RealAPIIntentGenerationRoutingIntegrationTest {

    static {
        // Ensure provider configuration is present for real API calls
        RealAPITestSupport.ensureProviderConfigured();
        RealAPITestSupport.ensureLLMProviderSet();
        System.setProperty("EMBEDDING_PROVIDER", System.getProperty("EMBEDDING_PROVIDER", System.getenv("EMBEDDING_PROVIDER") != null ? System.getenv("EMBEDDING_PROVIDER") : "onnx"));
        System.setProperty("ai.providers.embedding-provider", System.getProperty("ai.providers.embedding-provider", System.getenv("EMBEDDING_PROVIDER") != null ? System.getenv("EMBEDDING_PROVIDER") : "onnx"));
    }

    @Autowired
    private AICapabilityService capabilityService;

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private RAGOrchestrator orchestrator;

    @SpyBean
    private RAGProvider ragProvider;

    @MockBean
    private ProgressiveIntentExtractionEngine progressiveIntentExtractionEngine;

    @Autowired
    private TestProductRepository productRepository;


    @BeforeEach
    void setUp() {
        vectorManagementService.clearAllVectors();
        productRepository.deleteAll();
    }

    @Test
    void shouldRouteGenerationAndHonorOptimizedQueryWithRealProvider() {
        assumeRealApiConfigured();

        // Seed a product so retrieval has signal
        TestProduct headphones = persistProduct(
            "SonicWave Pro Headphones",
            "Premium over-ear headphones with spatial audio and active noise cancellation tuned for studio monitoring.",
            "Audio",
            "SonicWave",
            new BigDecimal("149.99")
        );
        capabilityService.processEntityForAI(headphones, "test-product");

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("recommend_products")
            .vectorSpace("test-product")
            .optimizedQuery("test-product entities where category = 'Audio' AND price < 200")
            .requiresGeneration(true)
            .build();

        when(progressiveIntentExtractionEngine.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(new ProgressiveIntentExtractionEngine.ExtractionOutput(
                MultiIntentResponse.builder().intents(List.of(intent)).build(),
                Map.of()
            ));

        OrchestrationResult result = orchestrator.orchestrate("Suggest an affordable audio headset", ai.fabric.intent.orchestration.OrchestrationContext.forUser("real-gen-routing-user"));

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsKeys("ragResponse", "requiresGeneration");
        assertThat(result.getData().get("requiresGeneration")).isEqualTo(true);

        Object ragResponseObj = result.getData().get("ragResponse");
        Assertions.assertThat(ragResponseObj).isInstanceOf(RAGResponse.class);
        RAGResponse ragResponse = (RAGResponse) ragResponseObj;
        Map<String, Object> metadata = ragResponse.getMetadata();
        assertThat(metadata)
            .isNotNull()
            .containsEntry("optimizedQueryProvided", true)
            .containsEntry("requiresGeneration", true);
        assertThat(String.valueOf(metadata.get("embeddingQuery")).toLowerCase())
            .contains("audio")
            .contains("price");

        // Ensure generation path was taken
        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider).performRAGQuery(requestCaptor.capture());
        verify(ragProvider, never()).performRag(any());
        assertThat(requestCaptor.getValue().getMetadata()).containsEntry("optimizedQuery", intent.getOptimizedQuery());
    }

    private void assumeRealApiConfigured() {
        // Check for any configured LLM provider API key
        String llmProvider = System.getProperty("ai.providers.llm-provider",
            System.getenv("LLM_PROVIDER"));
        if (llmProvider == null || llmProvider.isEmpty()) {
            llmProvider = "openai"; // default
        }

        boolean hasKey = false;
        switch (llmProvider.toLowerCase()) {
            case "openai":
                hasKey = StringUtils.hasText(System.getenv("OPENAI_API_KEY"))
                    || StringUtils.hasText(System.getProperty("OPENAI_API_KEY"));
                break;
            case "anthropic":
                hasKey = StringUtils.hasText(System.getenv("ANTHROPIC_API_KEY"))
                    || StringUtils.hasText(System.getProperty("ANTHROPIC_API_KEY"));
                break;
            case "gemini":
                hasKey = StringUtils.hasText(System.getenv("GEMINI_API_KEY"))
                    || StringUtils.hasText(System.getProperty("GEMINI_API_KEY"));
                break;
            case "cohere":
                hasKey = StringUtils.hasText(System.getenv("COHERE_API_KEY"))
                    || StringUtils.hasText(System.getProperty("COHERE_API_KEY"));
                break;
            case "azure":
                hasKey = (StringUtils.hasText(System.getenv("AZURE_API_KEY"))
                    || StringUtils.hasText(System.getProperty("AZURE_API_KEY")))
                    && (StringUtils.hasText(System.getenv("AZURE_ENDPOINT"))
                    || StringUtils.hasText(System.getProperty("AZURE_ENDPOINT")));
                break;
            default:
                // For ONNX/REST, no API key is required
                hasKey = true;
                break;
        }

        Assumptions.assumeTrue(hasKey,
            String.format("%s API key must be configured for real API tests", llmProvider.toUpperCase()));
    }

    private TestProduct persistProduct(String name, String description, String category, String brand, BigDecimal price) {
        TestProduct product = TestProduct.builder()
            .name(name)
            .description(description)
            .category(category)
            .brand(brand)
            .price(price)
            .sku("SKU-" + name.replaceAll("\\s+", "-"))
            .stockQuantity(25)
            .active(true)
            .build();
        return productRepository.save(product);
    }
}
