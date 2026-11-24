package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.intent.SpecialistPromptInstructionSupport;
import ai.fabric.intent.action.*;
import ai.fabric.intent.action.invocation.*;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.conversation.ConversationPromptContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.intent.orchestration.workflow.*;
import ai.fabric.llm.structured.*;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.prompt.PromptRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Opt-in workflow within the normal security/history/conversation pipeline. */
@Component
@RequiredArgsConstructor
public class BoundedWorkflowStep implements PipelineStep {
    private final AIActionRegistry registry;
    private final AICoreService core;
    private final StructuredJsonCallExecutor structured;
    private final PromptTemplateResolver prompts;
    private final PromptRenderer renderer;
    private final ObjectMapper mapper;

    @Override public String getStepName() { return "BoundedWorkflow"; }
    @Override public int getOrder() { return 49; }
    @Override public boolean shouldSkip(PipelineContext context) {
        return PipelineStep.super.shouldSkip(context) || context.getOrchestrationContext() == null
            || context.getOrchestrationContext().getWorkflowExecution() == null
            || !context.getOrchestrationContext().getWorkflowExecution().policy().enabled();
    }
    @Override public PipelineContext process(PipelineContext context) {
        if (shouldSkip(context)) return context;
        var orchestration = context.getOrchestrationContext();
        WorkflowExecution execution = orchestration.getWorkflowExecution();
        EffectiveCapabilityProfile effective = GovernedActionInvocationSupport.effectiveProfile(registry, context, orchestration);
        var invoker = new DefaultGovernedActionInvocationService(registry);
        var authority = execution.authority();
        var actions = new BoundedWorkflowRunner.Actions() {
            public void verifyAuthority(BoundedWorkflowRunner.Request request) {
                authority.verify(request);
                if (orchestration.getOrchestrationTurnGuard() != null)
                    orchestration.getOrchestrationTurnGuard().verifyBeforeOrchestration();
            }
            public boolean isWrite(String name) {
                var metadata = registry.findMetadata(name).orElseThrow(() -> new IllegalArgumentException("WORKFLOW_ACTION_NOT_REGISTERED"));
                boolean read = metadata.getAccessMode() == ActionAccessMode.READ;
                if (metadata.getAccessMode() == null || !effective.isActionVisible(name)
                    || (read ? !effective.canExecuteReadAction(name) : !effective.canProposeWriteAction(name)))
                    throw new IllegalArgumentException("WORKFLOW_ACTION_DENIED");
                if (!read && !orchestration.isSpecialistActionPolicyWrites())
                    throw new IllegalArgumentException("WORKFLOW_WRITES_DISABLED");
                return !read;
            }
            public String fingerprint(Map<String, Object> parameters) { return authority.fingerprint(parameters); }
            public BoundedWorkflowRunner.Outcome reconcile(WorkflowCheckpoint.Step step) { return authority.reconcile(step); }
            public BoundedWorkflowRunner.CompletionFinding validateCompletion(WorkflowCheckpoint checkpoint) {
                return authority.validateCompletion(checkpoint);
            }
            public BoundedWorkflowRunner.Outcome invoke(WorkflowCheckpoint.Step step, WorkflowAllowance allowance) {
                isWrite(step.action());
                var metadata = registry.findMetadata(step.action()).orElseThrow();
                var dispatchContext = orchestration.toBuilder().workflowOperationId(step.operationId()).build();
                var parameters = ActionRuntimeContextParamSupport.resolveConfiguredParameters(metadata,
                    step.parameters(), dispatchContext, context);
                var actionContext = new ActionContext(dispatchContext, context, parameters);
                var outcome = invoker.invoke(GovernedActionInvocationSupport.invocation(step.action(), parameters,
                    actionContext, registry, ActionConfirmationState.NOT_CONFIRMED, List.of()));
                WorkflowCheckpoint.Effect effect = switch (outcome.status()) {
                    case EXECUTED -> WorkflowCheckpoint.Effect.EXECUTED;
                    case CONFIRMATION_REQUIRED -> WorkflowCheckpoint.Effect.CONFIRMATION_REQUIRED;
                    case OUTCOME_UNKNOWN -> WorkflowCheckpoint.Effect.OUTCOME_UNKNOWN;
                    default -> WorkflowCheckpoint.Effect.REJECTED;
                };
                String code = outcome.publicFailure() == null ? null : outcome.publicFailure().reason();
                return new BoundedWorkflowRunner.Outcome(effect,
                    authority.observation(metadata, actionContext, outcome), code,
                    outcome.status() == GovernedActionInvocationStatus.INVALID
                        || (outcome.status() == GovernedActionInvocationStatus.FAILED
                            && authority.isCorrectableNoncommittedFailure(metadata, actionContext, outcome))
                        || (metadata.getAccessMode() == ActionAccessMode.READ && outcome.executed()
                            && authority.hasCorrectableValidationFindings(metadata, actionContext, outcome)));
            }
        };
        var runner = new BoundedWorkflowRunner(execution.checkpoints(), Clock.systemUTC());
        BoundedWorkflowRunner.Planner planner = (checkpoint, allowance) -> {
            var input = new LinkedHashMap<String, Object>();
            input.put("request", context.getEffectiveQuery());
            input.put("history", ConversationPromptContext.history(context, 16000).messages());
            input.put("observations", checkpoint.steps().stream().map(step -> Map.of(
                "operationId", step.operationId(), "action", step.action(), "effect", step.effect(), "result", step.observation())).toList());
            input.put("remaining", Map.of("actions", execution.policy().maxActions() - allowance.usage().actions(),
                "writes", execution.policy().maxWrites() - allowance.usage().writes(),
                "modelCalls", execution.policy().maxModelCalls() - allowance.usage().modelCalls()));
            input.put("previewOnly", execution.request().previewOnly());
            if (checkpoint.stopCode() != null) {
                input.put("previousFinding", checkpoint.stopCode());
                if (checkpoint.message() != null) input.put("previousFindingDetail", checkpoint.message());
            }
            List<AIActionMetaData> catalogue = new ArrayList<>();
            for (var metadata : registry.getAllMetadata()) {
                if (metadata != null && effective.isActionVisible(metadata.getName())
                    && (metadata.getAccessMode() == ActionAccessMode.READ
                        ? effective.canExecuteReadAction(metadata.getName())
                        : orchestration.isSpecialistActionPolicyWrites() && effective.canProposeWriteAction(metadata.getName())))
                    catalogue.add(ActionMetadataVisibilitySupport.publicActionMetadata(metadata));
            }
            input.put("actions", catalogue);
            String encodedInput = json(input);
            if (encodedInput.length() > 120000) throw new WorkflowAllowance.Stop("WORKFLOW_CONTEXT_LIMIT");
            var call = structured.execute(StructuredJsonCallSpec.<BoundedWorkflowRunner.Decision>builder()
                .callName("bounded_workflow_next_step").maxAttempts(1)
                .targetType(BoundedWorkflowRunner.Decision.class).objectMapper(mapper)
                .caller(attempt -> core.generateContent(AIGenerationRequest.builder()
                    .entityId(checkpoint.workId()).entityType("workflow").generationType("bounded_workflow")
                    .systemPrompt(SpecialistPromptInstructionSupport.appendToSystemPrompt(
                        renderer.render(prompts.resolve("orchestration/workflow", "system").template(), Map.of()), orchestration))
                    .prompt(encodedInput).authContext(OrchestrationAuthContextResolver.from(orchestration)).build(), LlmPurpose.GENERATION))
                .build());
            if (!call.isSuccess()) {
                if (call.getLastFailure() != null && call.getLastFailure().type() != StructuredJsonFailureType.CALL_ERROR)
                    throw new BoundedWorkflowRunner.PlanningFinding("Previous decision failure: " + call.getLastFailure().type()
                        + ". Return one object using type ACTION, COMPLETE, NEEDS_INPUT or UNSUPPORTED. ACTION needs an action name and parameters object. NEEDS_INPUT questions are strings, not objects."
                        + (call.getLastFailure().truncationSuspected() ? " The previous JSON may have been truncated; keep the next decision bounded." : ""));
                throw new WorkflowAllowance.Stop("WORKFLOW_PLANNING_UNAVAILABLE");
            }
            return call.getValue();
        };
        var result = authority.resumeConfirmedReceipt()
            ? runner.resumeConfirmed(execution.request(), execution.policy(), planner, actions)
            : runner.run(execution.request(), execution.policy(), planner, actions);
        var data = new LinkedHashMap<String, Object>();
        data.put("workId", result.workId()); data.put("state", result.state().name());
        if (result.stopCode() != null) data.put("stopCode", result.stopCode());
        if (result.message() != null) data.put("message", result.message());
        data.put("questions", result.questions()); data.put("usage", result.usage());
        data.put("steps", result.steps().stream().map(step -> Map.of("operationId", step.operationId(),
            "action", step.action(), "write", step.write(), "effect", step.effect().name())).toList());
        OrchestrationResultType type = switch (result.state()) {
            case COMPLETE -> OrchestrationResultType.INFORMATION_PROVIDED;
            case NEEDS_INPUT -> OrchestrationResultType.CLARIFICATION_REQUIRED;
            case CONFIRMATION_REQUIRED -> OrchestrationResultType.CONFIRMATION_REQUIRED;
            default -> OrchestrationResultType.ERROR;
        };
        var response = OrchestrationResult.builder().type(type).success(result.state() == WorkflowCheckpoint.State.COMPLETE)
            .message(result.message()).data(Map.of("workflow", data)).errorCode(result.stopCode()).build();
        if (result.state() == WorkflowCheckpoint.State.CONFIRMATION_REQUIRED) {
            var step = result.steps().getLast();
            var dispatchContext = orchestration.toBuilder().workflowOperationId(step.operationId()).build();
            var metadata = registry.findMetadata(step.action()).orElseThrow();
            var parameters = ActionRuntimeContextParamSupport.resolveConfiguredParameters(metadata, step.parameters(), dispatchContext, context);
            response.setActionProposalCandidate(new ActionProposalCandidate(step.action(), parameters, new ActionContext(dispatchContext, context)));
        }
        return context.terminate(response);
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalArgumentException("WORKFLOW_CONTEXT_ENCODING_FAILED"); }
    }
}
