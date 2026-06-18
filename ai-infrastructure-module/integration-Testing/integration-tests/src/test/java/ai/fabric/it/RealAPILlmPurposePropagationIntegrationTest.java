package ai.fabric.it;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.extraction.ProgressiveIntentExtractionEngine;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.repository.TestProductRepository;
import ai.fabric.it.support.RealAPITestSupport;
import ai.fabric.service.AICapabilityService;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * RealAPI coverage that purpose-based routing is exercised end-to-end.
 *
 * <p>We assert purposes are propagated to {@link AICoreService}:
 * - intent extraction uses {@link LlmPurpose#ORCHESTRATION}
 * - RAG answer generation uses {@link LlmPurpose#GENERATION}</p>
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("real-api-test")
@Transactional
public class RealAPILlmPurposePropagationIntegrationTest {

    static {
        RealAPITestSupport.ensureProviderConfigured();
        RealAPITestSupport.ensureLLMProviderSet();
        System.setProperty("EMBEDDING_PROVIDER",
            System.getProperty("EMBEDDING_PROVIDER", System.getenv("EMBEDDING_PROVIDER") != null ? System.getenv("EMBEDDING_PROVIDER") : "onnx"));
        System.setProperty("ai.providers.embedding-provider",
            System.getProperty("ai.providers.embedding-provider", System.getenv("EMBEDDING_PROVIDER") != null ? System.getenv("EMBEDDING_PROVIDER") : "onnx"));
    }

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private AICapabilityService capabilityService;

    @Autowired
    private VectorManagementService vectorManagementService;


    @Autowired
    private TestProductRepository productRepository;

    @MockitoSpyBean
    private AICoreService aiCoreService;

    @MockitoSpyBean
    private IntentQueryExtractor intentQueryExtractor;

    @MockitoBean
    private ProgressiveIntentExtractionEngine progressiveIntentExtractionEngine;

    private static IntentExtractionInput input(String userQuery) {
        return new IntentExtractionInput(userQuery, userQuery, List.of());
    }

    @BeforeEach
    void setUp() {
        assumeRealApiConfigured();
        Mockito.reset(aiCoreService, intentQueryExtractor, progressiveIntentExtractionEngine);
        vectorManagementService.clearAllVectors();
        productRepository.deleteAll();
    }

    @Test
    void intentExtractionShouldUseOrchestrationPurpose() {
        OrchestrationContext context = OrchestrationContext.forUser("purpose-orchestration-user");
        MultiIntentResponse response = intentQueryExtractor.extract(
            input("Search the knowledge base for CyberShield incident response capabilities."),
            context
        );

        assertThat(response).isNotNull();
        verify(aiCoreService, atLeastOnce()).generateContent(any(), eq(LlmPurpose.ORCHESTRATION));
    }

    @Test
    void ragAnswerGenerationShouldUseGenerationPurpose() {
        seedProduct();

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("kb_search")
            .vectorSpace("test-product")
            .optimizedQuery("CyberShield incident response playbooks")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .build();

        org.mockito.Mockito.when(progressiveIntentExtractionEngine.extract(
                argThat(in -> in != null && "force-generation-purpose".equals(in.userQuery())),
                any(OrchestrationContext.class)))
            .thenReturn(new ProgressiveIntentExtractionEngine.ExtractionOutput(
                MultiIntentResponse.builder().intents(List.of(intent)).build(),
                Map.of()
            ));

        OrchestrationResult result = orchestrator.orchestrate("force-generation-purpose", ai.fabric.intent.orchestration.OrchestrationContext.forUser("purpose-generation-user"));
        assertThat(result).isNotNull();
        assertThat(result.getType()).isNotNull();

        verify(aiCoreService, atLeastOnce()).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
    }

    private void seedProduct() {
        TestProduct product = productRepository.save(TestProduct.builder()
            .name("Enterprise Security Suite")
            .description("CyberShield provides incident response playbooks and automated containment workflows.")
            .category("Security")
            .brand("CyberShield")
            .price(new BigDecimal("2999.99"))
            .sku("CS-ENT-001")
            .stockQuantity(100)
            .active(true)
            .build());
        capabilityService.processEntityForAI(product, "test-product");
    }

    private void assumeRealApiConfigured() {
        boolean hasOpenAI = StringUtils.hasText(System.getProperty("OPENAI_API_KEY")) ||
                            StringUtils.hasText(System.getenv("OPENAI_API_KEY"));
        boolean hasAnthropic = StringUtils.hasText(System.getProperty("ANTHROPIC_API_KEY")) ||
                               StringUtils.hasText(System.getenv("ANTHROPIC_API_KEY"));
        boolean hasGemini = StringUtils.hasText(System.getProperty("GEMINI_API_KEY")) ||
                            StringUtils.hasText(System.getenv("GEMINI_API_KEY"));
        boolean hasCohere = StringUtils.hasText(System.getProperty("COHERE_API_KEY")) ||
                            StringUtils.hasText(System.getenv("COHERE_API_KEY"));
        boolean hasAzure = StringUtils.hasText(System.getProperty("AZURE_API_KEY")) ||
                           StringUtils.hasText(System.getenv("AZURE_API_KEY"));

        Assumptions.assumeTrue(
            hasOpenAI || hasAnthropic || hasGemini || hasCohere || hasAzure,
            "No real LLM provider API key configured; skipping LlmPurpose propagation RealAPI test."
        );
    }
}
