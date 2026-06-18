package ai.fabric.it;

import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.intent.extraction.ProgressiveIntentExtractionEngine;
import ai.fabric.it.support.RealAPITestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = TestApplication.class,
    properties = {
        "ai.intent-extraction.progressive.enabled=true",
        "ai.intent-extraction.progressive.multi-step-enabled=false",
        "ai.intent-extraction.progressive.repair-enabled=true",
        "ai.intent-extraction.progressive.repair-max-attempts=1",
        "ai.intent-extraction.progressive.max-total-llm-calls=3"
    }
)
@ActiveProfiles("real-api-test")
@Transactional
public class RealAPIProgressiveExtractionDiagnosticsIntegrationTest {

    static {
        RealAPITestSupport.ensureProviderConfigured();
        RealAPITestSupport.ensureLLMProviderSet();
        System.setProperty("EMBEDDING_PROVIDER",
            System.getProperty("EMBEDDING_PROVIDER", System.getenv("EMBEDDING_PROVIDER") != null ? System.getenv("EMBEDDING_PROVIDER") : "onnx"));
        System.setProperty("ai.providers.embedding-provider",
            System.getProperty("ai.providers.embedding-provider", "onnx"));
    }

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private ObjectProvider<ProgressiveIntentExtractionEngine> progressiveEngineProvider;

    @Autowired
    private Environment environment;

    @Test
    void shouldIncludeExtractionDiagnosticsMetadata() {
        assumeRealApiConfigured();

        assertThat(environment.getProperty("ai.intent-extraction.progressive.enabled"))
            .as("progressive intent extraction should be enabled for this test")
            .isEqualTo("true");

        assertThat(progressiveEngineProvider.getIfAvailable())
            .as("ProgressiveIntentExtractionEngine bean should be present when enabled")
            .isNotNull();

        OrchestrationResult result = orchestrator.orchestrate("Tell me a joke", ai.fabric.intent.orchestration.OrchestrationContext.forUser("realapi-progressive-user"));

        assertThat(result).isNotNull();
        assertThat(result.getMetadata()).isNotNull();
        assertThat(result.getMetadata()).containsKey("extractionDiagnostics");

        Object diagnosticsObj = result.getMetadata().get("extractionDiagnostics");
        assertThat(diagnosticsObj).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) diagnosticsObj;
        assertThat(diagnostics).containsKeys("extractionPath", "extractionAttempts", "llmCalls");
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
            "No real LLM provider API key configured; skipping progressive extraction real-api test."
        );
    }
}
