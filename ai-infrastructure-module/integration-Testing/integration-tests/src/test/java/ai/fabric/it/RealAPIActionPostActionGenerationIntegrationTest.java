package ai.fabric.it;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.ActionFacts;
import ai.fabric.intent.action.annotation.Param;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.it.support.RealAPITestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("real-api-test")
@Import(RealAPIActionPostActionGenerationIntegrationTest.PostActionGenerationTestConfig.class)
@TestPropertySource(properties = {
    "ai.post-action-generation.enabled=true",
    "ai.post-action-generation.max-chars=4000",
    "ai.post-action-generation.max-tokens=80",
    "ai.post-action-generation.temperature=0.0",
    // Make this test deterministic by bypassing progressive extraction and stubbing the extractor output.
    "ai.intent-extraction.progressive.enabled=false"
})
public class RealAPIActionPostActionGenerationIntegrationTest {

    static {
        RealAPITestSupport.ensureProviderConfigured();
        RealAPITestSupport.ensureLLMProviderSet();
    }

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private PostActionGenerationDemoActionHandler actionHandler;

    @MockitoBean
    private IntentQueryExtractor intentQueryExtractor;

    @BeforeEach
    void resetCounter() {
        actionHandler.resetExecutions();
    }

    @Test
    void shouldExecuteActionOnceAndGenerateSummaryFromHandlerFacts() {
        Assumptions.assumeTrue(hasAnyProviderKeyConfigured(), "No LLM provider API key configured; skipping RealAPI scenario.");

        String verificationToken = "POAG-" + Instant.now().toEpochMilli();

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action(PostActionGenerationDemoActionHandler.ACTION_NAME)
            .confidence(1.0d)
            .requiresRetrieval(false)
            .requiresGeneration(true)
            .generationInstructions("Reply with EXACTLY the value of verificationToken from FACTS, and nothing else.")
            .actionParams(Map.of("verificationToken", verificationToken))
            .build();

        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class))).thenReturn(
            MultiIntentResponse.builder()
                .intents(java.util.List.of(intent))
                .orchestrationStrategy("DIRECT_ACTION")
                .build()
        );

        OrchestrationResult result = orchestrator.orchestrate(
            "Execute post action generation demo and then summarize.",
            OrchestrationContext.forUser("post-action-realapi-user")
        );

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();

        assertThat(actionHandler.getExecutionCount()).as("Action must run exactly once").isEqualTo(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = result.getData() instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();

        assertThat(payload).containsKey("actionResult");
        assertThat(payload).containsKey("summary");
        assertThat(payload).containsKey("postActionGeneration");

        String message = result.getMessage();
        assertThat(message).isNotBlank();
        assertThat(message).contains(verificationToken);

        Object summaryRaw = payload.get("summary");
        assertThat(summaryRaw).isInstanceOf(String.class);
        assertThat(summaryRaw.toString()).contains(verificationToken);

        @SuppressWarnings("unchecked")
        Map<String, Object> generationMeta = payload.get("postActionGeneration") instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
        assertThat(generationMeta).containsEntry("used", true);
        assertThat(generationMeta).containsEntry("action", PostActionGenerationDemoActionHandler.ACTION_NAME);
    }

    private boolean hasAnyProviderKeyConfigured() {
        return StringUtils.hasText(System.getProperty("OPENAI_API_KEY")) || StringUtils.hasText(System.getenv("OPENAI_API_KEY"))
            || StringUtils.hasText(System.getProperty("ANTHROPIC_API_KEY")) || StringUtils.hasText(System.getenv("ANTHROPIC_API_KEY"))
            || StringUtils.hasText(System.getProperty("GEMINI_API_KEY")) || StringUtils.hasText(System.getenv("GEMINI_API_KEY"))
            || StringUtils.hasText(System.getProperty("COHERE_API_KEY")) || StringUtils.hasText(System.getenv("COHERE_API_KEY"))
            || (StringUtils.hasText(System.getProperty("AZURE_API_KEY")) || StringUtils.hasText(System.getenv("AZURE_API_KEY")));
    }

    @TestConfiguration
    @Import(PostActionGenerationDemoActionHandler.class)
    static class PostActionGenerationTestConfig {
    }

    @AIAction(
        name = PostActionGenerationDemoActionHandler.ACTION_NAME,
        description = "Test-only action used to validate post-action generation grounded in handler facts.",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = false
    )
    static final class PostActionGenerationDemoActionHandler {

        static final String ACTION_NAME = "post_action_generation_demo";

        private final AtomicInteger executions = new AtomicInteger();

        void resetExecutions() {
            executions.set(0);
        }

        int getExecutionCount() {
            return executions.get();
        }

        @ActionExecute
        public ActionResult execute(@Param(value = "verificationToken", required = true, description = "Token that must be echoed back by the post-action generation call") String verificationToken,
                                    ActionContext context) {
            int count = executions.incrementAndGet();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("verificationToken", verificationToken);
            data.put("executionCount", count);

            return ActionResult.builder()
                .success(true)
                .message("demo_action_executed")
                .data(ActionResultContracts.object(data))
                .build();
        }

        @ActionFacts
        public Optional<Map<String, Object>> facts(ActionResult actionResult, ActionContext context) {
            Map<String, Object> data = actionResult != null && actionResult.getData() != null
                ? actionResult.getData().toMap()
                : Map.of();
            Object token = data.get("verificationToken");

            return Optional.of(Map.of(
                "verificationToken", token != null ? token.toString() : "",
                "executionCount", executions.get()
            ));
        }
    }
}
