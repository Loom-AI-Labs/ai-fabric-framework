package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.*;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.*;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.orchestration.*;
import ai.fabric.intent.orchestration.pipeline.*;
import ai.fabric.intent.orchestration.request.*;
import ai.fabric.prompt.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InternalDecisionRecoveryServiceTest {
    private final AICoreService core = mock(AICoreService.class);
    private final DecisionRecoveryProperties settings = new DecisionRecoveryProperties();
    private final AIServiceConfig config = new AIServiceConfig();
    private final InternalDecisionRecoveryService service = new InternalDecisionRecoveryService(settings, core,
        config, new IntentExtractionJsonSupport(new ObjectMapper()),
        new PromptTemplateResolver(new ClasspathPromptTemplateStore(new DefaultResourceLoader()), new PromptBundleProperties()),
        new PromptRenderer());

    private PipelineContext context() {
        var actor = OrchestrationContext.forUser("owner");
        actor.setConversationId("conversation-1");
        return PipelineContext.from("summarize these items", actor)
            .toBuilder().historyMessages(List.of(AIChatMessage.user("HISTORY_USER_DATA"),
                AIChatMessage.assistant("Desk is GBP499; chair is GBP249")))
            .pinnedTargetsContext("ATTACHED_ITEM_DATA priceGbp=499 stock=8 adjustableHeight=true").build();
    }

    private Intent intent() {
        return Intent.builder().type(IntentType.INFORMATION).intent("summary").requiresRetrieval(true)
            .requiresGeneration(true).requiresTargetResolution(false).needsAdvancedRAG(false).build();
    }

    private OrchestrationResult unresolved() {
        return InternalDecisionRecoveryService.unresolved("MISSING_SOURCE_SELECTION", Map.of());
    }

    @Test void correctsLatestCandidateAcrossThreeRoundsWithoutUsingAnUnapprovedSource() {
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().model("planner").content("{\"requiresRetrieval\":true,\"vectorSpaces\":[]}").build())
            .thenReturn(AIGenerationResponse.builder().model("planner").content("{\"requiresRetrieval\":true,\"vectorSpaces\":[\"foreign\"]}").build())
            .thenReturn(AIGenerationResponse.builder().model("planner").content("{\"requiresRetrieval\":true,\"vectorSpaces\":[\"document\"]}").build());
        PipelineContext pipeline = context();
        Intent intent = intent();
        assertThat(service.repairRouting(intent, pipeline, unresolved(), List.of("document"), 1, true)).isTrue();
        assertThat(intent.getVectorSpace()).isEqualTo("document");
        var requests = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core, times(3)).generateContent(requests.capture(), eq(LlmPurpose.ORCHESTRATION));
        assertThat(requests.getAllValues().get(2).getPrompt()).contains("foreign", "SOURCE_NOT_IN_ALLOWED_CATALOGUE");
        for (var request : requests.getAllValues()) {
            assertThat(request.getAuthContext()).isNotNull();
            assertThat(request.getSystemPrompt()).doesNotContain("HISTORY_USER_DATA", "ATTACHED_ITEM_DATA");
            assertThat(request.getMessages()).hasSize(2);
        }
        assertThat(pipeline.getDecisionRecoveryBudget().diagnostics(settings)).containsEntry("repairRounds", 3);
    }

    @Test void allCompoundChildrenShareRepairAllowanceAndOnlyOneFinalAnswer() {
        settings.setMaxRepairRounds(2);
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().content("{\"requiresRetrieval\":true,\"vectorSpaces\":[]}").build());
        when(core.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("The desk is GBP499; the chair is GBP249.").model("answer").build());
        PipelineContext context = context();
        assertThat(service.repairRouting(intent(), context, unresolved(), List.of("document"), 1, true)).isFalse();
        assertThat(service.repairRouting(intent(), context.toBuilder().build(), unresolved(), List.of("document"), 1, true)).isFalse();
        OrchestrationResult compound = OrchestrationResult.builder().type(OrchestrationResultType.COMPOUND_HANDLED)
            .children(List.of(unresolved(), unresolved())).build();
        OrchestrationResult answer = service.finalContextAnswer(context, compound);
        assertThat(answer.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(answer.getMessage()).contains("GBP499");
        assertThat(answer.getChildren()).isEmpty();
        service.finalContextAnswer(context, compound);
        verify(core, times(2)).generateContent(any(), eq(LlmPurpose.ORCHESTRATION));
        verify(core, times(1)).generateContent(any(), eq(LlmPurpose.GENERATION));
    }

    @Test void finalAnswerHasNativeHistoryAttachmentsAndTrustedSystemInstructions() {
        when(core.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("A useful context answer.").build());
        PipelineContext pipeline = context();
        OrchestrationResult answer = service.finalContextAnswer(pipeline, unresolved());
        assertThat(answer.isSuccess()).isTrue();
        assertThat(answer.getMetadata()).containsEntry("answerSource", "AVAILABLE_CONTEXT_RECOVERY");
        var request = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core).generateContent(request.capture(), eq(LlmPurpose.GENERATION));
        assertThat(request.getValue().getSystemPrompt()).contains("Never ask the user", "not independently verified")
            .doesNotContain("HISTORY_USER_DATA", "ATTACHED_ITEM_DATA");
        assertThat(request.getValue().getPrompt()).contains("summarize these items", "ATTACHED_ITEM_DATA");
        assertThat(request.getValue().getMessages()).hasSize(2);
    }

    @Test void noSourceAndNoHistoryStillAllowsAnHonestModelAnswerWithoutAFakeLookup() {
        when(core.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("I do not have current stock information. Which item interests you?").build());
        PipelineContext context = PipelineContext.from("Which item is in stock?", OrchestrationContext.anonymous());
        OrchestrationResult answer = service.finalContextAnswer(context, unresolved());
        assertThat(answer.getMessage()).contains("do not have current stock");
        verify(core, never()).generateContent(any(), eq(LlmPurpose.ORCHESTRATION));
    }

    @Test void preservesActualWriteReceiptAndPendingConfirmationWithoutPassingRawWriteDataToModel() {
        when(core.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("The first operation completed; please confirm the other operation.").build());
        OrchestrationResult receipt = OrchestrationResult.builder().type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true).data(Map.of("action", "save", "actionExecutionId", "receipt-1", "actionResult", Map.of("secret", "RAW_WRITE_CANARY"))).build();
        OrchestrationResult pending = OrchestrationResult.builder().type(OrchestrationResultType.CONFIRMATION_REQUIRED)
            .data(Map.of("action", "cancel")).build();
        OrchestrationResult compound = OrchestrationResult.builder().type(OrchestrationResultType.COMPOUND_HANDLED)
            .children(List.of(receipt, unresolved(), pending)).build();
        PipelineContext pipeline = context().toBuilder().intentResponse(MultiIntentResponse.builder()
            .intents(List.of(intent(), intent(), intent())).build()).build();
        OrchestrationResult answer = service.finalContextAnswer(pipeline, compound);
        assertThat(answer.getType()).isEqualTo(OrchestrationResultType.COMPOUND_HANDLED);
        assertThat(answer.getChildren()).containsExactly(receipt, pending);
        assertThat(answer.getChildren().getFirst().getData()).containsEntry("actionExecutionId", "receipt-1");
        assertThat(new OrchestrationResultNormalizer().normalize(answer)).isSameAs(answer);
        var request = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core).generateContent(request.capture(), eq(LlmPurpose.GENERATION));
        assertThat(request.getValue().getPrompt()).contains("ACTUAL OPERATION STATE", "ACTION_EXECUTED", "CONFIRMATION_REQUIRED")
            .doesNotContain("RAW_WRITE_CANARY");
    }

    @Test void accessDenialAndOtherServiceFailuresCannotBecomeContextRecovery() {
        for (OrchestrationResult hard : List.of(OrchestrationResult.builder().type(OrchestrationResultType.ACTION_DENIED).build(),
            OrchestrationResult.error("Provider unavailable"))) {
            OrchestrationResult mixed = OrchestrationResult.builder().type(OrchestrationResultType.COMPOUND_HANDLED)
                .children(List.of(unresolved(), hard)).build();
            var result = new OrchestrationResultNormalizer().normalize(service.finalContextAnswer(context(), mixed));
            assertThat(result.getType()).isEqualTo(hard.getType());
            assertThat(result.getMessage()).isEqualTo(hard.getMessage());
            assertThat(result.getChildren()).containsExactly(hard);
        }
        verifyNoInteractions(core);
    }

    @Test void providerFailureIsNotRetriedAndDoesNotTriggerAnotherProviderCall() {
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenThrow(new IllegalStateException("offline"));
        PipelineContext context = context();
        assertThat(service.repairRouting(intent(), context, unresolved(), List.of("document"), 1, true)).isFalse();
        assertThat(service.finalContextAnswer(context, unresolved()).getErrorCode()).isEqualTo("DECISION_RECOVERY_PROVIDER_FAILED");
        verify(core).generateContent(any(), eq(LlmPurpose.ORCHESTRATION));
        verify(core, never()).generateContent(any(), eq(LlmPurpose.GENERATION));
    }

    @Test void finalGenerationFailureDoesNotBecomeAnotherRetryOrSuccessfulAnswer() {
        when(core.generateContent(any(), eq(LlmPurpose.GENERATION))).thenThrow(new IllegalStateException("offline"));
        PipelineContext context = context();
        assertThat(service.finalContextAnswer(context, unresolved()).getErrorCode()).isEqualTo("GENERATION_FAILED");
        service.finalContextAnswer(context, unresolved());
        verify(core).generateContent(any(), eq(LlmPurpose.GENERATION));
    }

    @Test void providerOutagePreservesCompletedWriteAndPendingConfirmationThroughNormalization() {
        var receipt = OrchestrationResult.builder().type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true).data(Map.of("actionExecutionId", "receipt-2")).build();
        var pending = OrchestrationResult.builder().type(OrchestrationResultType.CONFIRMATION_REQUIRED)
            .data(Map.of("action", "cancel")).build();
        var compound = OrchestrationResult.builder().type(OrchestrationResultType.COMPOUND_HANDLED)
            .children(List.of(receipt, unresolved(), pending)).build();
        var context = context();
        context.getDecisionRecoveryBudget().providerFailed();
        var result = new OrchestrationResultNormalizer().normalize(service.finalContextAnswer(context, compound));
        assertThat(result.getErrorCode()).isEqualTo("DECISION_RECOVERY_PROVIDER_FAILED");
        assertThat(result.getChildren()).containsExactly(receipt, pending);
        assertThat(result.getData().get("results")).isEqualTo(List.of(receipt, pending));
        verifyNoInteractions(core);
    }

    @Test void finalGenerationFailureConsumesInternalMarkersButKeepsActualOperationState() {
        when(core.generateContent(any(), eq(LlmPurpose.GENERATION))).thenThrow(new IllegalStateException("offline"));
        var receipt = OrchestrationResult.builder().type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true).data(Map.of("actionExecutionId", "receipt-3")).build();
        var compound = OrchestrationResult.builder().type(OrchestrationResultType.COMPOUND_HANDLED)
            .children(List.of(receipt, unresolved())).build();
        var result = new OrchestrationResultNormalizer().normalize(service.finalContextAnswer(context(), compound));
        assertThat(result.getErrorCode()).isEqualTo("GENERATION_FAILED");
        assertThat(result.getMessage()).isEqualTo("The AI response could not be generated.");
        assertThat(result.getChildren()).containsExactly(receipt);
    }

    @Test void finalAnswerReceivesConfiguredManagedGuidanceInTrustedInstructions() {
        when(core.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("Configured answer").build());
        var context = context();
        context.getOrchestrationContext().setMetadata(Map.of(OrchestrationContextMetadataKeys.PROMPT_PREVIEW,
            Map.of("answerGenerationPrompt", "TONE_GUIDANCE_CANARY", "retrievalPrompt", "BREADTH_GUIDANCE_CANARY",
                "assistantUiPrompt", "PRESENTATION_GUIDANCE_CANARY")));
        service.finalContextAnswer(context, unresolved());
        var request = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core).generateContent(request.capture(), eq(LlmPurpose.GENERATION));
        assertThat(request.getValue().getSystemPrompt()).contains("TONE_GUIDANCE_CANARY",
            "BREADTH_GUIDANCE_CANARY", "PRESENTATION_GUIDANCE_CANARY", "Never ask the user");
    }

    @Test void strictSpecialistAndStructuredOutputContractsDoNotReceiveProseFallback() {
        OrchestrationRequest request = new OrchestrationRequest("task input", OrchestrationContext.forUser("owner"),
            null, ConversationPersistencePolicy.NEVER, null, null, null,
            OrchestrationRequestPurpose.SPECIALIST, OrchestrationIntentPolicy.STRUCTURED_OUTPUT_ONLY);
        assertThat(service.finalContextAnswer(PipelineContext.from(request), unresolved()).getErrorCode())
            .isEqualTo(InternalDecisionRecoveryService.UNRESOLVED);
        verifyNoInteractions(core);
    }

    @Test void cancelledRequestCannotStartRepairOrFinalGeneration() {
        Thread.currentThread().interrupt();
        try {
            assertThat(service.repairRouting(intent(), context(), unresolved(), List.of("document"), 1, true)).isFalse();
            assertThat(service.finalContextAnswer(context(), unresolved()).getErrorCode())
                .isEqualTo(InternalDecisionRecoveryService.UNRESOLVED);
            verifyNoInteractions(core);
        } finally { Thread.interrupted(); }
    }

    @Test void availablePreparedReadFactsSurviveRoutingFailureButRawAndUnusableEvidenceDoNot() {
        when(core.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("The approved status is ready; further details are unavailable.").build());
        var failure = unresolved().withAdditionalData(Map.of("readActionResolution", Map.of("executedActions", List.of(
            Map.of("action", "read", "success", true, "groundingUsable", true, "evidenceSummary", "APPROVED_STATUS_READY"),
            Map.of("action", "read", "success", true, "groundingUsable", false, "evidenceSummary", "UNUSABLE_CANARY"))),
            "actionResult", Map.of("raw", "RAW_OUTPUT_CANARY")));
        var pipeline = context().toBuilder().intentResponse(MultiIntentResponse.builder().intents(List.of(intent())).build()).build();
        service.finalContextAnswer(pipeline, failure);
        var request = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core).generateContent(request.capture(), eq(LlmPurpose.GENERATION));
        assertThat(request.getValue().getPrompt()).contains("APPROVED_STATUS_READY")
            .doesNotContain("RAW_OUTPUT_CANARY", "UNUSABLE_CANARY");
    }

    @Test void malformedRepairOutputIsCorrectedWithoutTreatingItAsAProviderOutage() {
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().content("not json").build())
            .thenReturn(AIGenerationResponse.builder().content("{\"requiresRetrieval\":false,\"vectorSpaces\":[]}").build());
        var context = context();
        assertThat(service.repairRouting(intent(), context, unresolved(), List.of("document"), 1, true)).isTrue();
        assertThat(context.getDecisionRecoveryBudget().isProviderFailed()).isFalse();
        verify(core, times(2)).generateContent(any(), eq(LlmPurpose.ORCHESTRATION));
        var requests = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core, times(2)).generateContent(requests.capture(), eq(LlmPurpose.ORCHESTRATION));
        assertThat(requests.getAllValues().get(1).getPrompt()).contains("not json", "INVALID_ROUTING_OUTPUT");
    }

    @Test void weakRetrievedMatchesRemainBoundedQualifiedEvidenceForTheFinalAnswer() {
        when(core.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("The guide mentions delivery; exact terms are not confirmed.").build());
        var document = RAGResponse.RAGDocument.builder().id("approved-guide").content("APPROVED_LOW_SCORE_GUIDE")
            .score(0.1).build();
        var failure = InternalDecisionRecoveryService.unresolved("WEAK_FAN_OUT_EVIDENCE",
            Map.of("documents", List.of(document), "bestScore", 0.1));
        var pipeline = context().toBuilder().intentResponse(MultiIntentResponse.builder().intents(List.of(intent())).build()).build();
        service.finalContextAnswer(pipeline, failure);
        var request = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core).generateContent(request.capture(), eq(LlmPurpose.GENERATION));
        assertThat(request.getValue().getPrompt()).contains("APPROVED_LOW_SCORE_GUIDE", "weak relevance");
    }
}
