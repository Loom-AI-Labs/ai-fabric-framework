package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationInputType;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.ResponseGenerationProfile;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagResponseGenerationSupportTest {

    @Test
    void shouldReturnStaticNoContextFallbackWhenGenerationIsDisabled() {
        AICoreService aiCoreService = mock(AICoreService.class);
        RagResponseGenerationSupport support = newSupport(aiCoreService, false);

        RagResponseGenerationSupport.ResponseGenerationTrace trace = support.generateRagAnswer(
            Intent.builder().requiresGeneration(true).build(),
            " where is my order ",
            RagContextSupport.NO_CONTEXT_MESSAGE,
            PipelineContext.from("where is my order", OrchestrationContext.forUser("user"))
        );

        assertThat(trace.content()).isEqualTo("I don't have enough information to answer your question: where is my order");
        assertThat(support.responseGenerationMetadata(trace)).isEmpty();
        verify(aiCoreService, never()).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
        verify(aiCoreService, never()).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION));
    }

    @Test
    void shouldUseBudgetedGenerateContentForConciseNoContextGeneration() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("Generated fallback")
                .model("gpt-test")
                .processingTimeMs(44L)
                .build()
        );
        RagResponseGenerationSupport support = newSupport(aiCoreService, true);

        RagResponseGenerationSupport.ResponseGenerationTrace trace = support.generateRagAnswer(
            Intent.builder()
                .requiresGeneration(true)
                .responseProfile(ResponseGenerationProfile.CONCISE)
                .build(),
            "query",
            null,
            PipelineContext.from("query", OrchestrationContext.forUser("user"))
        );

        assertThat(trace.content()).isEqualTo("Generated fallback");
        assertThat(trace.path()).isEqualTo("RAG_NO_CONTEXT_CONCISE");
        assertThat(support.responseGenerationMetadata(trace))
            .containsEntry("responseGenerationProviderProcessingTimeMs", 44L)
            .containsEntry("responseGenerationModel", "gpt-test")
            .containsEntry("responseGenerationPath", "RAG_NO_CONTEXT_CONCISE");

        ArgumentCaptor<AIGenerationRequest> requestCaptor = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(requestCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(requestCaptor.getValue().getMaxTokens()).isEqualTo(400);
        assertThat(requestCaptor.getValue().getGenerationType()).isEqualTo("no_context");
    }

    @Test
    void shouldUseGenerateTextResponseForStandardContextAnswerWithoutTokenBudget() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("Contextual answer")
                .model("gpt-test")
                .processingTimeMs(70L)
                .build()
        );
        RagResponseGenerationSupport support = newSupport(aiCoreService, true);

        RagResponseGenerationSupport.ResponseGenerationTrace trace = support.generateRagAnswer(
            Intent.builder()
                .requiresGeneration(true)
                .responseProfile(ResponseGenerationProfile.STANDARD)
                .build(),
            "query",
            "retrieved context",
            PipelineContext.from("query", OrchestrationContext.forUser("user"))
        );

        assertThat(trace.content()).isEqualTo("Contextual answer");
        assertThat(trace.path()).isEqualTo("RAG_ANSWER");
        verify(aiCoreService).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
        verify(aiCoreService, never()).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION));
    }

    @Test
    void shouldUsePolicyBudgetForDeepGenerationProfile() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder().content("Deep answer").build()
        );
        RagResponseGenerationSupport support = newSupport(aiCoreService, true);
        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            null,
            null,
            null,
            null,
            null,
            new OrchestrationPolicy.ResponseGenerationBudgets(null, null, 1_500)
        );
        PipelineContext context = PipelineContext.from("query", OrchestrationContext.forUser("user"))
            .toBuilder()
            .orchestrationPolicy(policy)
            .build();

        support.generateRagAnswer(
            Intent.builder()
                .requiresGeneration(true)
                .responseProfile(ResponseGenerationProfile.DEEP)
                .build(),
            "query",
            "context",
            context
        );

        ArgumentCaptor<AIGenerationRequest> requestCaptor = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(requestCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(requestCaptor.getValue().getMaxTokens()).isEqualTo(1_500);
        assertThat(requestCaptor.getValue().getGenerationType()).isEqualTo("answer");
    }

    @Test
    void shouldSendTransientInputPartsThroughGenerateContent() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder().content("With file").build()
        );
        RagResponseGenerationSupport support = newSupport(aiCoreService, true);
        AIGenerationInputPart filePart = AIGenerationInputPart.builder()
            .type(AIGenerationInputType.FILE_URL)
            .url("https://example.invalid/file.pdf")
            .fileName("file.pdf")
            .build();
        PipelineContext context = PipelineContext.from(
            "query",
            OrchestrationContext.builder()
                .userId("user")
                .transientInputParts(List.of(filePart))
                .build()
        );

        support.generatePromptResponse(
            "prompt",
            "adhoc",
            "generation_only",
            LlmPurpose.GENERATION,
            "GENERATION_ONLY",
            null,
            context
        );

        ArgumentCaptor<AIGenerationRequest> requestCaptor = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(requestCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(requestCaptor.getValue().getInputParts()).containsExactly(filePart);
        assertThat(requestCaptor.getValue().getTransientInputPolicy()).isNotNull();
        assertThat(requestCaptor.getValue().getAuthContext()).isNotNull();
    }

    private RagResponseGenerationSupport newSupport(AICoreService aiCoreService, boolean generationEnabled) {
        AIServiceConfig config = new AIServiceConfig();
        config.getFeatures().setEnableGeneration(generationEnabled);
        return new RagResponseGenerationSupport(
            aiCoreService,
            config,
            promptTemplateResolver(),
            new PromptRenderer()
        );
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }
}
