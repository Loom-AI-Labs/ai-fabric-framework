package ai.fabric.intent.orchestration.pipeline.steps;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.intent.action.*;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.workflow.*;
import ai.fabric.llm.structured.*;
import ai.fabric.prompt.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoundedWorkflowStepTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final AIActionRegistry registry = mock(AIActionRegistry.class);
    final AIActionHandler writer = mock(AIActionHandler.class);
    final AICoreService core = mock(AICoreService.class);
    final PromptTemplateResolver prompts = mock(PromptTemplateResolver.class);
    final WorkflowExecution.Authority authority = mock(WorkflowExecution.Authority.class);
    final Store store = new Store();
    final AtomicInteger saves = new AtomicInteger();
    final ArrayDeque<String> replies = new ArrayDeque<>();
    final AIActionMetaData write = AIActionMetaData.builder().name("save_draft").accessMode(ActionAccessMode.WRITE_ONLY)
        .parameterSchemas(Map.of("operationId", AIActionParamSchema.builder().name("operationId").type(AIActionParamType.STRING)
            .visibility("INTERNAL").resolveFrom(Map.of("source", "RUNTIME_CONTEXT", "field", "workflowOperationId")).build()))
        .requiredParameters(Set.of("operationId")).build();
    BoundedWorkflowStep step;
    PipelineContext context;
    @BeforeEach void prepare() {
        var policy = new WorkflowPolicy(true, 8, 3, 16, 2, Duration.ofSeconds(120));
        var request = new BoundedWorkflowRunner.Request("work", "request", "authority", "definition", Instant.now().plusSeconds(120), false);
        var profile = new EffectiveCapabilityProfile("author", "author", false, Set.of(), Set.of("save_draft"),
            Set.of(), Set.of("save_draft"), OrchestrationPolicy.RagBudgets.defaults(),
            OrchestrationPolicy.ReadActionResolutionPolicy.defaults(), "hash");
        var orchestration = OrchestrationContext.builder().userId("user").specialistActionPolicyWrites(true)
            .workflowExecution(new WorkflowExecution(request, policy, store, authority)).build();
        context = PipelineContext.from("Prepare the API tools", orchestration).toBuilder().effectiveCapabilityProfile(profile).build();
        var template = new PromptTemplate(new PromptTemplateKey("orchestration/workflow", "v1", "system"), "Trusted workflow instructions");
        when(prompts.resolve("orchestration/workflow", "system")).thenReturn(new ResolvedPromptTemplate(template, List.of("v1")));
        when(registry.getAllMetadata()).thenReturn(List.of(write));
        when(registry.findMetadata("save_draft")).thenReturn(Optional.of(write));
        when(registry.findHandler("save_draft")).thenReturn(Optional.of(writer));
        when(writer.validateActionAllowed(any())).thenReturn(true);
        when(writer.executeAction(anyMap(), any())).thenAnswer(call -> {
            assertThat(((Map<?, ?>) call.getArgument(0)).get("operationId")).isEqualTo("work:step:1");
            saves.incrementAndGet(); return ActionResult.builder().success(true).data(ActionPayload.object(Map.of("receiptId", "receipt"))).build();
        });
        when(authority.fingerprint(anyMap())).thenReturn("params");
        when(authority.observation(any(), any(), any())).thenReturn(Map.of("receiptId", "receipt"));
        when(core.generateContent(any(), any())).thenAnswer(call -> {
            WorkflowModelCallScope.beforeModelCall();
            var input = (AIGenerationRequest) call.getArgument(0);
            assertThat(input.getSystemPrompt()).contains("Trusted workflow instructions");
            return AIGenerationResponse.builder().content(replies.remove()).build();
        });
        step = new BoundedWorkflowStep(registry, core, new DefaultStructuredJsonCallExecutor(new StructuredJsonExtractor(), mapper),
            prompts, new PromptRenderer(), mapper);
    }
    @Test void normalPipelineDispatchesGovernedWriteWithServerOperationIdAndNoVectorRequirement() {
        replies.add("{\"type\":\"ACTION\",\"action\":\"save_draft\",\"parameters\":{\"operationId\":\"forged\"}}");
        replies.add("{\"type\":\"COMPLETE\",\"message\":\"Draft saved.\"}");
        var result = step.process(context).getIntentResult();
        assertThat(result.isSuccess()).isTrue(); assertThat(saves.get()).isEqualTo(1);
        assertThat(result.getData()).containsKey("workflow");
        assertThat(store.value.usage().modelCalls()).isEqualTo(2);
    }
    @Test void absentBindingLeavesOrdinaryChatUnchangedWithoutInference() {
        context.getOrchestrationContext().setWorkflowExecution(null);
        assertThat(step.shouldSkip(context)).isTrue();
        assertThat(step.process(context)).isSameAs(context);
        verifyNoInteractions(core, writer);
    }
    @Test void disabledSpecialistWritePolicyNeverInvokesTheWriter() {
        context.getOrchestrationContext().setSpecialistActionPolicyWrites(false);
        replies.add("{\"type\":\"ACTION\",\"action\":\"save_draft\",\"parameters\":{}}");
        assertThat(step.process(context).getIntentResult().isSuccess()).isFalse();
        verify(writer, never()).executeAction(anyMap(), any());
    }
    @Test void confirmationRequiredProducesNativePendingProposalWithoutWriting() {
        write.setConfirmationRequired(true);
        replies.add("{\"type\":\"ACTION\",\"action\":\"save_draft\",\"parameters\":{}}");
        var result = step.process(context).getIntentResult();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(result.getActionProposalCandidate()).isNotNull();
        assertThat(result.getActionProposalCandidate().parameters()).containsEntry("operationId", "work:step:1");
        verify(writer, never()).executeAction(anyMap(), any());
    }
    @Test void structuralDecisionFailureGetsBoundedModelRepairNotAHiddenFallback() {
        replies.add("{\"type\":\"ACTION\",\"parameters\":{}}");
        replies.add("{\"type\":\"NEEDS_INPUT\",\"questions\":[\"Which API host?\"],\"message\":\"Please provide the API host.\"}");
        var result = step.process(context).getIntentResult();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(store.value.usage().modelCalls()).isEqualTo(2);
        assertThat(store.value.usage().corrections()).isEqualTo(1);
        var requests = org.mockito.ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core, times(2)).generateContent(requests.capture(), any());
        assertThat(requests.getAllValues().get(1).getPrompt()).contains("previousFindingDetail", "questions are strings, not objects");
        verify(writer, never()).executeAction(anyMap(), any());
    }
    @Test void hostCompletionFindingIsFedBackWithinTheSameBudgetWithoutPerformingAWrite() {
        when(authority.validateCompletion(any())).thenReturn(new BoundedWorkflowRunner.CompletionFinding(
            "DRAFT_EFFECT_MISSING", "The checked candidate has no saved receipt."));
        replies.add("{\"type\":\"COMPLETE\",\"message\":\"Saved\"}");
        replies.add("{\"type\":\"NEEDS_INPUT\",\"questions\":[\"Which connection?\"]}");
        var result = step.process(context).getIntentResult();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(store.value.usage().corrections()).isEqualTo(1);
        var requests = org.mockito.ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core, times(2)).generateContent(requests.capture(), any());
        assertThat(requests.getAllValues().get(1).getPrompt()).contains("DRAFT_EFFECT_MISSING", "no saved receipt");
        verify(writer, never()).executeAction(anyMap(), any());
    }
    @Test void providerFailureStopsWithoutAnotherCallOrCannedAnswer() {
        doThrow(new IllegalStateException("Provider unavailable")).when(core).generateContent(any(), any());
        var result = step.process(context).getIntentResult();
        assertThat(result.isSuccess()).isFalse(); assertThat(result.getMessage()).isNull();
        verify(core, times(1)).generateContent(any(), any());
        verify(writer, never()).executeAction(anyMap(), any());
    }
    static class Store implements WorkflowCheckpointStore {
        WorkflowCheckpoint value;
        public void verifyLease(String workId) { }
        public Optional<WorkflowCheckpoint> find(String workId) { return Optional.ofNullable(value); }
        public boolean create(WorkflowCheckpoint checkpoint) { if (value != null) return false; value = checkpoint; return true; }
        public boolean compareAndSet(WorkflowCheckpoint expected, WorkflowCheckpoint next) {
            if (value != expected) return false; value = next; return true;
        }
    }
}
