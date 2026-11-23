package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.*;
import ai.fabric.core.*;
import ai.fabric.dto.*;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.action.*;
import ai.fabric.intent.orchestration.*;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.*;
import ai.fabric.prompt.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TargetDecisionRecoveryTest {
    private final AICoreService core = mock(AICoreService.class);
    private final DecisionRecoveryProperties settings = new DecisionRecoveryProperties();
    private final InternalDecisionRecoveryService recovery = new InternalDecisionRecoveryService(settings, core,
        new AIServiceConfig(), new IntentExtractionJsonSupport(new ObjectMapper()),
        new PromptTemplateResolver(new ClasspathPromptTemplateStore(new DefaultResourceLoader()), new PromptBundleProperties()),
        new PromptRenderer());
    private final TargetResolutionStep step = new TargetResolutionStep();

    TargetDecisionRecoveryTest() {
        ObjectProvider<InternalDecisionRecoveryService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recovery);
        ReflectionTestUtils.setField(step, "decisionRecoveryProvider", provider);
    }

    private PipelineContext context(OrchestrationRequestPurpose purpose, Intent... intents) {
        var actor = OrchestrationContext.forUser("owner");
        actor.setSpecialistInstructions("Selected resources resolve through the parameterless backend tool.");
        var request = new OrchestrationRequest("Inspect the selected resource", actor, null,
            ConversationPersistencePolicy.NEVER, null, null, null, purpose, OrchestrationIntentPolicy.MODEL_DIRECTED);
        return PipelineContext.from(request).toBuilder()
            .historyMessages(List.of(AIChatMessage.user("UNTRUSTED_HISTORY_CANARY")))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intents)).build()).build();
    }

    private Intent intent() {
        return Intent.builder().type(IntentType.ACTION).action("read_status").intent("inspect")
            .requiresTargetResolution(true).actionParams(Map.of("existing", "PRIVATE_ARGUMENT_CANARY")).build();
    }

    private AIGenerationResponse answer(String content) {
        return AIGenerationResponse.builder().content(content).model("test-model").build();
    }

    @Test void repairsSpecialistFlagWithoutInventingTargetsOrChangingActionInputs() {
        var registry = mock(AIActionRegistry.class);
        when(registry.getAllMetadata()).thenReturn(List.of(
            AIActionMetaData.builder().name("read_status").description("Read an already authorized selection.")
                .accessMode(ActionAccessMode.READ).build(),
            AIActionMetaData.builder().name("foreign_action").description("FOREIGN_CATALOGUE_CANARY").build()));
        ReflectionTestUtils.setField(recovery, "actionRegistry", registry);
        Intent intent = intent();
        PipelineContext input = context(OrchestrationRequestPurpose.SPECIALIST, intent);
        input.getOrchestrationContext().setEffectiveCapabilityProfile(new EffectiveCapabilityProfile("test", "executor", false,
            Set.of(), Set.of("read_status"), Set.of("read_status"), Set.of(), null, null, "profile-hash"));
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(answer("{\"requiresTargetResolution\":false,\"clarification\":null}"));
        PipelineContext result = step.process(input);
        assertThat(result.isShouldTerminate()).isFalse();
        assertThat(result.getResolvedTargets()).isEmpty();
        assertThat(intent.getRequiresTargetResolution()).isFalse();
        assertThat(intent.getAction()).isEqualTo("read_status");
        assertThat(intent.getActionParams()).containsExactlyEntriesOf(Map.of("existing", "PRIVATE_ARGUMENT_CANARY"));
        assertThat(result.getMetadata().get("semanticDecision").toString()).contains("TARGET_NOT_REQUIRED", "recoveryAttempted=true");
        var calls = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core).generateContent(calls.capture(), eq(LlmPurpose.ORCHESTRATION));
        assertThat(calls.getValue().getPrompt()).contains("read_status", "requiredParameters")
            .doesNotContain("FOREIGN_CATALOGUE_CANARY", "PRIVATE_ARGUMENT_CANARY");
        assertThat(calls.getValue().getSystemPrompt()).contains("parameterless backend tool")
            .doesNotContain("UNTRUSTED_HISTORY_CANARY");
        assertThat(calls.getValue().getMessages()).isEmpty();
        assertThat(calls.getValue().getAuthContext()).isNotNull();
    }

    @Test void selectedActionOutsideSpecialistProfileDoesNotExposeItsContract() {
        var registry = mock(AIActionRegistry.class);
        when(registry.getAllMetadata()).thenReturn(List.of(AIActionMetaData.builder()
            .name("foreign_action").description("FOREIGN_CONTRACT_CANARY").build()));
        ReflectionTestUtils.setField(recovery, "actionRegistry", registry);
        Intent intent = intent(); intent.setAction("foreign_action");
        PipelineContext input = context(OrchestrationRequestPurpose.SPECIALIST, intent);
        input.getOrchestrationContext().setEffectiveCapabilityProfile(new EffectiveCapabilityProfile("test", "executor", false,
            Set.of(), Set.of("read_status"), Set.of("read_status"), Set.of(), null, null, "profile-hash"));
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(answer("{\"requiresTargetResolution\":false,\"clarification\":null}"));
        step.process(input);
        var calls = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core).generateContent(calls.capture(), eq(LlmPurpose.ORCHESTRATION));
        assertThat(calls.getValue().getPrompt()).doesNotContain("FOREIGN_CONTRACT_CANARY");
        assertThat(input.getOrchestrationContext().getEffectiveCapabilityProfile().canExecuteReadAction("foreign_action")).isFalse();
    }

    @Test void nativeSchemaRequirementsAreRetainedButHiddenDefaultsAreNotDisclosed() {
        var registry = mock(AIActionRegistry.class);
        var metadata = AIActionMetaData.builder().name("read_status")
            .inputSchema(Map.of("type", "object", "required", List.of("ticketId", "internalKey"), "properties", Map.of(
                "ticketId", Map.of("type", "string"),
                "internalKey", Map.of("type", "string", "default", "HIDDEN_DEFAULT_CANARY"))))
            .parameterSchemas(Map.of("internalKey", AIActionParamSchema.builder().askUser(false).visibility("SECRET").build())).build();
        when(registry.getAllMetadata()).thenReturn(List.of(metadata));
        ReflectionTestUtils.setField(recovery, "actionRegistry", registry);
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(answer("{\"requiresTargetResolution\":true,\"clarification\":\"Which ticket should I inspect?\"}"));
        var result = step.process(context(OrchestrationRequestPurpose.SPECIALIST, intent()));
        assertThat(result.getEarlyTerminationResult().getMessage()).isEqualTo("Which ticket should I inspect?");
        var calls = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core).generateContent(calls.capture(), eq(LlmPurpose.ORCHESTRATION));
        assertThat(calls.getValue().getPrompt()).contains("publicInputSchema", "ticketId").doesNotContain("HIDDEN_DEFAULT_CANARY");
    }

    @Test void preservesRealUserChoiceAndUsesModelQuestionRatherThanAttachmentBoilerplate() {
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(answer("{\"requiresTargetResolution\":true,\"clarification\":\"Which subscription should I cancel?\"}"));
        Intent intent = intent();
        var result = step.process(context(OrchestrationRequestPurpose.GENERAL, intent));
        assertThat(result.isShouldTerminate()).isTrue();
        assertThat(result.getEarlyTerminationResult().getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.getEarlyTerminationResult().getMessage()).isEqualTo("Which subscription should I cancel?");
        assertThat(intent.getRequiresTargetResolution()).isTrue();
        assertThat(result.getResolvedTargets()).isEmpty();
    }

    @Test void invalidCandidatesShareExistingBoundedRepairBudget() {
        settings.setMaxRepairRounds(2);
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(answer("{\"requiresTargetResolution\":true,\"clarification\":null}"))
            .thenReturn(answer("{\"requiresTargetResolution\":false,\"clarification\":null}"));
        var result = step.process(context(OrchestrationRequestPurpose.SPECIALIST, intent()));
        assertThat(result.isShouldTerminate()).isFalse();
        var calls = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core, times(2)).generateContent(calls.capture(), eq(LlmPurpose.ORCHESTRATION));
        assertThat(calls.getAllValues().getLast().getPrompt()).contains("MISSING_USER_INPUT_QUESTION");
        assertThat(result.getDecisionRecoveryBudget().diagnostics(settings)).containsEntry("repairRounds", 2);
    }

    @Test void exhaustedSpecialistDoesNotExecuteOrPretendUserInformationIsMissing() {
        settings.setMaxRepairRounds(1);
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(answer("not json"));
        var result = step.process(context(OrchestrationRequestPurpose.SPECIALIST, intent()));
        assertThat(result.isShouldTerminate()).isTrue();
        assertThat(result.getEarlyTerminationResult().getType()).isEqualTo(OrchestrationResultType.ERROR);
        assertThat(result.getEarlyTerminationResult().getErrorCode()).isEqualTo(InternalDecisionRecoveryService.UNRESOLVED);
        verify(core, times(1)).generateContent(any(), eq(LlmPurpose.ORCHESTRATION));
        verify(core, never()).generateContent(any(), eq(LlmPurpose.GENERATION));
    }

    @Test void exhaustedGeneralChatUsesConfiguredContextAnswerWithoutExecutingAnAction() {
        settings.setMaxRepairRounds(1);
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(answer("not json"));
        when(core.generateContent(any(), eq(LlmPurpose.GENERATION))).thenReturn(answer("I cannot establish live state from the available context."));
        var result = step.process(context(OrchestrationRequestPurpose.GENERAL, intent()));
        assertThat(result.getEarlyTerminationResult().getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getEarlyTerminationResult().getMessage()).contains("cannot establish live state");
        assertThat(result.getResolvedTargets()).isEmpty();
        verify(core, times(1)).generateContent(any(), eq(LlmPurpose.GENERATION));
    }

    @Test void providerFailureStopsFurtherRepairsAndDoesNotInventAQuestion() {
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenThrow(new IllegalStateException("provider down"));
        var result = step.process(context(OrchestrationRequestPurpose.SPECIALIST, intent()));
        assertThat(result.getEarlyTerminationResult().getType()).isEqualTo(OrchestrationResultType.ERROR);
        assertThat(result.getDecisionRecoveryBudget().isProviderFailed()).isTrue();
        verify(core, times(1)).generateContent(any(), eq(LlmPurpose.ORCHESTRATION));
        verify(core, never()).generateContent(any(), eq(LlmPurpose.GENERATION));
    }

    @Test void disabledRecoveryAndStructuredManagerKeepExistingNoRepairContract() {
        settings.setEnabled(false);
        assertThat(step.process(context(OrchestrationRequestPurpose.GENERAL, intent())).getEarlyTerminationResult().getType())
            .isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        settings.setEnabled(true);
        var actor = OrchestrationContext.forUser("owner");
        var request = new OrchestrationRequest("decide", actor, null, ConversationPersistencePolicy.NEVER, null,
            null, null, OrchestrationRequestPurpose.SPECIALIST, OrchestrationIntentPolicy.STRUCTURED_OUTPUT_ONLY);
        assertThat(recovery.enabledForTargetRepair(PipelineContext.from(request))).isFalse();
        verifyNoInteractions(core);
    }
}
