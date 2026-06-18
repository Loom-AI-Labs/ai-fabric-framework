package ai.fabric.it;

import ai.fabric.dto.Intent;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.it.entity.TestArticle;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.entity.TestUser;
import ai.fabric.it.repository.TestArticleRepository;
import ai.fabric.it.repository.TestProductRepository;
import ai.fabric.it.repository.TestUserRepository;
import ai.fabric.it.support.RealAPITestSupport;
import ai.fabric.service.AICapabilityService;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RealAPI coverage for the progressive extraction multi-step mode across multiple complex queries.
 *
 * <p>This test is intentionally provider-agnostic and only asserts deterministic invariants:
 * - multi-step extraction is used (via extractionDiagnostics)
 * - bounded LLM call limits are respected
 * - the system does not crash (no ERROR result type)</p>
 */
@SpringBootTest(
    classes = TestApplication.class,
    properties = {
        "ai.intent-extraction.progressive.enabled=true",
        "ai.intent-extraction.progressive.force-mode=multi_step",
        "ai.intent-extraction.progressive.repair-enabled=false",
        "ai.intent-extraction.progressive.multi-step-enabled=true",
        "ai.intent-extraction.progressive.max-total-llm-calls=3"
    }
)
@ActiveProfiles("real-api-test")
@Transactional
public class RealAPIProgressiveMultiStepComplexScenariosIntegrationTest {

    static {
        RealAPITestSupport.ensureProviderConfigured();
        RealAPITestSupport.ensureLLMProviderSet();
        System.setProperty("EMBEDDING_PROVIDER",
            System.getProperty("EMBEDDING_PROVIDER", System.getenv("EMBEDDING_PROVIDER") != null ? System.getenv("EMBEDDING_PROVIDER") : "onnx"));
        System.setProperty("ai.providers.embedding-provider",
            System.getProperty("ai.providers.embedding-provider", System.getenv("EMBEDDING_PROVIDER") != null ? System.getenv("EMBEDDING_PROVIDER") : "onnx"));
    }

    private record Scenario(String name, String query, Set<OrchestrationResultType> acceptableTypes) {}

    private static final List<Scenario> SCENARIOS = List.of(
        new Scenario(
            "Multi-domain enterprise evaluation",
            """
            Search the knowledge base for CyberShield incident response and PrivacyGuard GDPR audit reporting.
            Summarize key capabilities, deployment considerations, and give one recommendation with reasoning.
            """,
            Set.of(OrchestrationResultType.INFORMATION_PROVIDED, OrchestrationResultType.CLARIFICATION_REQUIRED, OrchestrationResultType.COMPOUND_HANDLED)
        ),
        new Scenario(
            "Long constraints + structured output",
            """
            From the knowledge base, extract:
            - 3 capabilities for CyberShield
            - 3 capabilities for PrivacyGuard
            - 1 risk and 1 mitigation for each
            Output as JSON with keys: cyberShield, privacyGuard.
            """,
            Set.of(OrchestrationResultType.INFORMATION_PROVIDED, OrchestrationResultType.CLARIFICATION_REQUIRED, OrchestrationResultType.COMPOUND_HANDLED)
        ),
        new Scenario(
            "Multilingual query",
            """
            ¿Qué hace PrivacyGuard y cómo se compara con CyberShield?
            Usa la base de conocimiento. Responde en inglés.
            """,
            Set.of(OrchestrationResultType.INFORMATION_PROVIDED, OrchestrationResultType.CLARIFICATION_REQUIRED, OrchestrationResultType.COMPOUND_HANDLED, OrchestrationResultType.OUT_OF_SCOPE)
        ),
        new Scenario(
            "High-ambiguity request (prefer clarification over misrouting)",
            """
            Search the knowledge base for policies related to audits and incident response.
            If multiple domains exist and you're not sure which one to search, ask me to clarify instead of guessing.
            """,
            Set.of(OrchestrationResultType.CLARIFICATION_REQUIRED, OrchestrationResultType.INFORMATION_PROVIDED, OrchestrationResultType.OUT_OF_SCOPE)
        )
    );

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

    @Autowired
    private TestUserRepository userRepository;

    @TestFactory
    Stream<DynamicTest> multiStepScenariosShouldNotErrorAndExposeDiagnostics() {
        assumeRealApiConfigured();
        resetAndSeedKnowledgeBase();

        return SCENARIOS.stream()
            .map(scenario -> DynamicTest.dynamicTest(
                scenario.name(),
                () -> {
                    OrchestrationResult result = orchestrator.orchestrate(scenario.query(), ai.fabric.intent.orchestration.OrchestrationContext.forUser("realapi-multistep-user"));

                    assertThat(result).isNotNull();
                    assertThat(result.getType())
                        .as("Result type should be one of the acceptable outcomes for this scenario")
                        .isIn(scenario.acceptableTypes());
                    assertThat(result.getType()).isNotEqualTo(OrchestrationResultType.ERROR);

                    Map<String, Object> metadata = result.getMetadata();
                    assertThat(metadata).isNotNull();
                    assertThat(metadata).containsKey("extractionDiagnostics");

                    Object diagnosticsObj = metadata.get("extractionDiagnostics");
                    assertThat(diagnosticsObj).isInstanceOf(Map.class);

                    @SuppressWarnings("unchecked")
                    Map<String, Object> diagnostics = (Map<String, Object>) diagnosticsObj;
                    assertThat(String.valueOf(diagnostics.get("extractionPath"))).isEqualTo("multi_step");

                    Object llmCallsObj = diagnostics.get("llmCalls");
                    assertThat(llmCallsObj).isInstanceOfAny(Integer.class, Long.class);
                    long llmCalls = llmCallsObj instanceof Integer i ? i : (Long) llmCallsObj;
                    assertThat(llmCalls)
                        .as("Multi-step mode must respect max total LLM calls")
                        .isBetween(1L, 3L);

                    Object attemptsObj = diagnostics.get("attempts");
                    assertThat(attemptsObj).isInstanceOf(List.class);

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> attempts = (List<Map<String, Object>>) attemptsObj;
                    boolean sawMultiStepAttempt = attempts != null && attempts.stream()
                        .anyMatch(event -> event != null && "multi_step".equals(String.valueOf(event.get("strategy"))));
                    assertThat(sawMultiStepAttempt)
                        .as("Diagnostics should include at least one multi_step attempt entry")
                        .isTrue();

                    // If the system claims success, it must provide a non-empty message.
                    if (result.isSuccess()) {
                        assertThat(result.getMessage()).isNotBlank();
                    }
                }
            ));
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
            "No real LLM provider API key configured; skipping multi-step RealAPI scenario suite."
        );
    }

    private void resetAndSeedKnowledgeBase() {
        vectorManagementService.clearAllVectors();
        productRepository.deleteAll();
        articleRepository.deleteAll();
        userRepository.deleteAll();

        TestProduct cyberShield = productRepository.save(TestProduct.builder()
            .name("Enterprise Security Suite")
            .description("""
                CyberShield is a comprehensive security monitoring and threat detection platform.
                Provides real-time alerts, vulnerability scanning, and incident response playbooks.
                Includes SIEM integration and automated containment workflows.
                """)
            .category("Security")
            .brand("CyberShield")
            .price(new BigDecimal("2999.99"))
            .sku("CS-ENT-001")
            .stockQuantity(100)
            .active(true)
            .build());
        capabilityService.processEntityForAI(cyberShield, "test-product");

        TestProduct privacyGuard = productRepository.save(TestProduct.builder()
            .name("Data Privacy Compliance Tool")
            .description("""
                PrivacyGuard is an automated GDPR and CCPA compliance verification tool.
                Scans data flows, identifies PII exposure, and generates audit reports.
                Supports evidence export for audits and continuous monitoring.
                """)
            .category("Compliance")
            .brand("PrivacyGuard")
            .price(new BigDecimal("1599.00"))
            .sku("PG-COMP-002")
            .stockQuantity(100)
            .active(true)
            .build());
        capabilityService.processEntityForAI(privacyGuard, "test-product");

        TestArticle article = articleRepository.save(TestArticle.builder()
            .title("GDPR Audit Readiness: Evidence, Logs, and Incident Response")
            .content("""
                This article covers GDPR audit readiness, including what evidence to collect,
                how to prepare audit logs, and how incident response ties into compliance.
                References PrivacyGuard for audit reporting and CyberShield for incident response workflows.
                """)
            .summary("A guide to GDPR audit readiness and incident response.")
            .author("Compliance Team")
            .tags("gdpr,audit,incident-response,compliance")
            .published(true)
            .publishDate(LocalDateTime.now().minusDays(3))
            .readTime(7)
            .viewCount(420)
            .build());
        capabilityService.processEntityForAI(article, "test-article");

        TestUser user = userRepository.save(TestUser.builder()
            .firstName("Amina")
            .lastName("Hassan")
            .email("amina.hassan@example.com")
            .bio("""
                Security engineer who maintains CyberShield incident response workflows and collaborates with compliance.
                Interested in audit automation and privacy tooling like PrivacyGuard.
                """)
            .age(32)
            .location("Berlin")
            .phoneNumber("+49-555-0101")
            .dateOfBirth(LocalDate.of(1993, 2, 18))
            .active(true)
            .build());
        capabilityService.processEntityForAI(user, "test-user");
    }
}
