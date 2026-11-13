package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationInputType;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.Intent;
import ai.fabric.dto.ResponseGenerationProfile;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class RagResponseGenerationSupportTest {

    @Test
    void carriesNativeHistoryToBothGroundedAndNoCurrentEvidenceGenerationWithoutTextPacking() {
        AICoreService ai = mock(AICoreService.class);
        when(ai.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("Supported answer").build());
        var support = newSupport(ai, true);
        var orchestration = OrchestrationContext.forUser("owner-1");
        orchestration.setConversationId("conversation-1");
        var history = List.of(AIChatMessage.user("Compare Aster E1 and Northstar S4"),
            AIChatMessage.assistant("Aster4850; Northstar8920"));
        var pipeline = PipelineContext.from("Which of those two?", orchestration).toBuilder()
            .historyMessages(history).build();
        for (String evidence : List.of("Current authorized Aster4851; Northstar8921", "")) {
            var trace = support.generateRagAnswer(Intent.builder().requiresGeneration(true).build(),
                "Which of those two?", evidence, pipeline);
            assertThat(trace.content()).isEqualTo("Supported answer");
            assertThat(support.responseGenerationMetadata(trace)).containsKey("responseGenerationConversationContext");
        }
        var requests = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(ai, times(2)).generateContent(requests.capture(), eq(LlmPurpose.GENERATION));
        for (AIGenerationRequest request : requests.getAllValues()) {
            assertThat(request.getMessages()).extracting(AIChatMessage::getContent)
                .containsExactly(history.get(0).getContent(), history.get(1).getContent());
            assertThat(request.getPrompt()).doesNotContain("Aster4850");
            assertThat(request.getSystemPrompt()).contains("historical statements", "takes precedence");
        }
        verify(ai, never()).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
    }

    @Test
    void emptyConversationBoundGenerationFailsVisiblyInsteadOfRetryingWithoutHistory() {
        AICoreService ai = mock(AICoreService.class);
        var support = newSupport(ai, true);
        var orchestration = OrchestrationContext.forUser("owner-1");
        orchestration.setConversationId("conversation-1");
        var pipeline = PipelineContext.from("follow-up", orchestration).toBuilder()
            .historyMessages(List.of(AIChatMessage.assistant("earlier facts"))).build();
        assertThatThrownBy(() -> support.generateRagAnswer(Intent.builder().requiresGeneration(true).build(),
            "follow-up", "current authorized evidence", pipeline)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("conversation-bound");
        assertThatThrownBy(() -> support.generateRagAnswer(Intent.builder().requiresGeneration(true).build(),
            "follow-up", "", pipeline)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("conversation-bound");
        verify(ai, never()).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
    }

    @ParameterizedTest
    @MethodSource("generationContexts")
    void shouldPropagateGenerationExceptionWithoutReplacingTheAnswer(boolean withHistory, String evidence) {
        AICoreService ai = mock(AICoreService.class);
        RuntimeException failure = new IllegalStateException("provider unavailable");
        when(ai.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION))).thenThrow(failure);
        when(ai.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenThrow(failure);
        RagResponseGenerationSupport support = newSupport(ai, true);

        assertThatThrownBy(() -> support.generateRagAnswer(Intent.builder().requiresGeneration(true).build(),
            "query", evidence, generationContext(withHistory))).isSameAs(failure);

        verifySingleGenerationCall(ai, withHistory);
    }

    @ParameterizedTest
    @MethodSource("emptyGenerationCases")
    void shouldRejectNullOrBlankGenerationWithoutStaticReplacement(boolean withHistory,
                                                                  String evidence,
                                                                  AIGenerationResponse response) {
        AICoreService ai = mock(AICoreService.class);
        when(ai.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION))).thenReturn(response);
        when(ai.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(response);
        RagResponseGenerationSupport support = newSupport(ai, true);

        assertThatThrownBy(() -> support.generateRagAnswer(Intent.builder().requiresGeneration(true).build(),
            "query", evidence, generationContext(withHistory)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Generation returned no content");

        verifySingleGenerationCall(ai, withHistory);
    }

    @ParameterizedTest
    @MethodSource("emptyBudgetedGenerationCases")
    void shouldNotRetryEmptyBudgetedAttachmentGenerationAsText(boolean withHistory, AIGenerationResponse response) {
        AICoreService ai = mock(AICoreService.class);
        when(ai.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION))).thenReturn(response);
        RagResponseGenerationSupport support = newSupport(ai, true);
        PipelineContext pipeline = budgetedAttachmentContext(withHistory);

        assertThatThrownBy(() -> support.generateRagAnswer(Intent.builder().requiresGeneration(true)
            .responseProfile(ResponseGenerationProfile.CONCISE).build(),
            "query", "current authorized evidence", pipeline))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Generation returned no content");

        verifyBudgetedAttachmentRequest(ai, pipeline);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldPropagateBudgetedAttachmentGenerationExceptionWithoutRetry(boolean withHistory) {
        AICoreService ai = mock(AICoreService.class);
        RuntimeException failure = new IllegalStateException("provider unavailable");
        when(ai.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION))).thenThrow(failure);
        RagResponseGenerationSupport support = newSupport(ai, true);
        PipelineContext pipeline = budgetedAttachmentContext(withHistory);

        assertThatThrownBy(() -> support.generateRagAnswer(Intent.builder().requiresGeneration(true)
            .responseProfile(ResponseGenerationProfile.CONCISE).build(),
            "query", "current authorized evidence", pipeline)).isSameAs(failure);

        verifyBudgetedAttachmentRequest(ai, pipeline);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldPreserveSuccessfulBudgetedAttachmentGeneration(boolean withHistory) {
        AICoreService ai = mock(AICoreService.class);
        String content = "Model-authored answer.\nThe remaining detail is uncertain.";
        when(ai.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content(content).model("gpt-test").processingTimeMs(7L).build());
        RagResponseGenerationSupport support = newSupport(ai, true);
        PipelineContext pipeline = budgetedAttachmentContext(withHistory);

        var trace = support.generateRagAnswer(Intent.builder().requiresGeneration(true)
            .responseProfile(ResponseGenerationProfile.CONCISE).build(),
            "query", "current authorized evidence", pipeline);

        assertThat(trace.content()).isEqualTo(content);
        assertThat(trace.model()).isEqualTo("gpt-test");
        assertThat(trace.providerProcessingTimeMs()).isEqualTo(7L);
        assertThat(trace.path()).isEqualTo("RAG_ANSWER_CONCISE");
        verifyBudgetedAttachmentRequest(ai, pipeline);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldReturnNoContextLimitationWithoutAttemptingDisabledGeneration(boolean withHistory) {
        AICoreService aiCoreService = mock(AICoreService.class);
        RagResponseGenerationSupport support = newSupport(aiCoreService, false);

        RagResponseGenerationSupport.ResponseGenerationTrace trace = support.generateRagAnswer(
            Intent.builder().requiresGeneration(true).build(),
            " where is my order ",
            RagContextSupport.NO_CONTEXT_MESSAGE,
            generationContext(withHistory)
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
    void shouldAppendServerOwnedResponseInstructionsOnlyToFinalGenerationPrompt() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder().content("{\"assessment\":\"READY\"}").build()
        );
        RagResponseGenerationSupport support = newSupport(aiCoreService, true);
        OrchestrationContext orchestrationContext =
            OrchestrationContext.forUser("user");
        PipelineContext pipelineContext = PipelineContext.from(
            new OrchestrationRequest(
                "Can this account continue?",
                orchestrationContext,
                null,
                ConversationPersistencePolicy.CONVERSATION,
                null,
                "Can this account continue?",
                "Return one JSON object with an assessment field."
            )
        );

        support.generateRagAnswer(
            Intent.builder().requiresGeneration(true).build(),
            "Can this account continue?",
            "The account has a verified payment method.",
            pipelineContext
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService).generateTextResponse(
            promptCaptor.capture(),
            eq(LlmPurpose.GENERATION)
        );
        assertThat(promptCaptor.getValue())
            .contains("The account has a verified payment method.")
            .contains("APPLICATION RESPONSE INSTRUCTIONS")
            .contains("Return one JSON object with an assessment field.");
        assertThat(pipelineContext.getEffectiveQuery())
            .doesNotContain("Return one JSON object");
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

    private static Stream<Arguments> generationContexts() {
        return Stream.of(false, true).flatMap(withHistory ->
            Stream.<String>of(null, RagContextSupport.NO_CONTEXT_MESSAGE, "current authorized evidence")
                .map(evidence -> Arguments.of(withHistory, evidence)));
    }

    private static Stream<AIGenerationResponse> emptyGenerationResponses() {
        return Stream.of(null,
            AIGenerationResponse.builder().build(),
            AIGenerationResponse.builder().content("").build(),
            AIGenerationResponse.builder().content(" \n\t").build());
    }

    private static Stream<Arguments> emptyGenerationCases() {
        return generationContexts().flatMap(context -> emptyGenerationResponses()
            .map(response -> Arguments.of(context.get()[0], context.get()[1], response)));
    }

    private static Stream<Arguments> emptyBudgetedGenerationCases() {
        return Stream.of(false, true).flatMap(withHistory -> emptyGenerationResponses()
            .map(response -> Arguments.of(withHistory, response)));
    }

    private PipelineContext generationContext(boolean withHistory) {
        OrchestrationContext orchestration = OrchestrationContext.forUser("user-1");
        orchestration.setConversationId("conversation-1");
        return PipelineContext.from("query", orchestration).toBuilder()
            .historyMessages(withHistory
                ? List.of(AIChatMessage.user("earlier question"), AIChatMessage.assistant("earlier answer"))
                : List.of())
            .build();
    }

    private PipelineContext budgetedAttachmentContext(boolean withHistory) {
        PipelineContext pipeline = generationContext(withHistory);
        pipeline.getOrchestrationContext().setTransientInputParts(List.of(AIGenerationInputPart.builder()
            .type(AIGenerationInputType.FILE_URL)
            .url("https://example.invalid/file.pdf")
            .fileName("file.pdf")
            .build()));
        return pipeline.toBuilder().orchestrationPolicy(new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT, null, null, null, null, null,
            new OrchestrationPolicy.ResponseGenerationBudgets(321, null, null)))
            .build();
    }

    private void verifySingleGenerationCall(AICoreService ai, boolean structured) {
        if (structured) {
            verify(ai).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION));
            verify(ai, never()).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
        } else {
            verify(ai).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
            verify(ai, never()).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION));
        }
    }

    private void verifyBudgetedAttachmentRequest(AICoreService ai, PipelineContext pipeline) {
        var requests = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(ai).generateContent(requests.capture(), eq(LlmPurpose.GENERATION));
        var request = requests.getValue();
        assertThat(request.getEntityType()).isEqualTo("rag");
        assertThat(request.getGenerationType()).isEqualTo("answer");
        assertThat(request.getMaxTokens()).isEqualTo(321);
        assertThat(request.getInputParts()).containsExactlyElementsOf(
            pipeline.getOrchestrationContext().getTransientInputParts());
        assertThat(request.getTransientInputPolicy()).isNotNull();
        assertThat(request.getMessages()).containsExactlyElementsOf(pipeline.getHistoryMessages());
        assertThat(request.getAuthContext()).isNotNull();
        assertThat(request.getPrompt()).contains("current authorized evidence");
        verify(ai, never()).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }
}
