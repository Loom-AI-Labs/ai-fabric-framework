package ai.fabric.it;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.extraction.ProgressiveIntentExtractionEngine;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.it.entity.TestArticle;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.repository.TestArticleRepository;
import ai.fabric.it.repository.TestProductRepository;
import ai.fabric.it.support.RealAPITestSupport;
import ai.fabric.service.AICapabilityService;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Deterministic RealAPI integration coverage for the vectorSpace clarification policy.
 *
 * <p>This test intentionally mocks intent extraction to force a retrieval intent without a vectorSpace,
 * then validates that the routing policy returns CLARIFICATION_REQUIRED instead of misrouting.</p>
 */
@SpringBootTest(
    classes = TestApplication.class,
    properties = {
        // Force the router to always require clarification when vectorSpace is missing and there are multiple spaces.
        "ai.rag.vectorspace-routing.strategy=CLARIFICATION",
        "ai.rag.vectorspace-routing.fan-out-max-spaces=3"
    }
)
@ActiveProfiles("real-api-test")
@Transactional
public class RealAPIVectorSpaceClarificationPolicyIntegrationTest {

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

    @Autowired
    private TestArticleRepository articleRepository;

    @MockitoBean
    private ProgressiveIntentExtractionEngine progressiveIntentExtractionEngine;

    @Test
    void shouldReturnClarificationRequiredWhenVectorSpaceMissingAndPolicyRequiresClarification() {
        assumeRealApiConfigured();
        seedMultipleVectorSpaces();

        Intent retrievalIntentMissingSpace = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("kb_search")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .vectorSpace("")
            .optimizedQuery("enterprise security suite incident response and privacy compliance audits")
            .build();

        when(progressiveIntentExtractionEngine.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(new ProgressiveIntentExtractionEngine.ExtractionOutput(
                MultiIntentResponse.builder().intents(List.of(retrievalIntentMissingSpace)).build(),
                Map.of()
            ));

        OrchestrationResult result = orchestrator.orchestrate("Search the knowledge base for incident response and audit readiness and summarize findings.", ai.fabric.intent.orchestration.OrchestrationContext.forUser("realapi-clarification-policy-user"));

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.isSuccess()).isFalse();

        assertThat(result.getData()).isNotNull().containsKey("candidateVectorSpaces");
        Object candidatesObj = result.getData().get("candidateVectorSpaces");
        assertThat(candidatesObj).isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) candidatesObj;
        assertThat(candidates).isNotEmpty();

        assertThat(result.getMetadata())
            .as("Early clarification should include routing metadata for observability")
            .isNotNull()
            .containsKey("vectorSpaceRouting");

        Object routingObj = result.getMetadata().get("vectorSpaceRouting");
        assertThat(routingObj).isInstanceOf(List.class);
    }

    private void seedMultipleVectorSpaces() {
        vectorManagementService.clearAllVectors();
        productRepository.deleteAll();
        articleRepository.deleteAll();

        TestProduct product = productRepository.save(TestProduct.builder()
            .name("CyberShield")
            .description("Enterprise security monitoring and incident response workflows.")
            .category("Security")
            .brand("CyberShield")
            .price(new BigDecimal("2999.00"))
            .sku("CS-001")
            .stockQuantity(10)
            .active(true)
            .build());
        capabilityService.processEntityForAI(product, "test-product");
        RealAPITestSupport.awaitVectorExists(vectorManagementService, "test-product", product.getId().toString(), Duration.ofSeconds(60));

        TestArticle article = articleRepository.save(TestArticle.builder()
            .title("PrivacyGuard audit evidence checklist")
            .content("Audit readiness, GDPR evidence capture, and compliance reporting using PrivacyGuard.")
            .summary("Audit and GDPR readiness.")
            .author("Compliance Team")
            .tags("gdpr,audit,compliance")
            .published(true)
            .publishDate(LocalDateTime.now().minusDays(2))
            .readTime(5)
            .viewCount(120)
            .build());
        capabilityService.processEntityForAI(article, "test-article");
        RealAPITestSupport.awaitVectorExists(vectorManagementService, "test-article", article.getId().toString(), Duration.ofSeconds(60));
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
            "No real LLM provider API key configured; skipping vectorSpace clarification RealAPI test."
        );
    }
}
