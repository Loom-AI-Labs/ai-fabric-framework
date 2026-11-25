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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<PendingActionStore> pendingActions;

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
        var pinnedTargets = new LinkedHashMap<String, ActionTargetRef>();
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
            public List<WorkflowDelegationProfile> delegatedProfiles() { return authority.delegatedProfiles(); }
            public void verifyDelegation(WorkflowDelegation delegation) { authority.verifyDelegation(delegation); }
            public void verifyDelegatedAction(WorkflowDelegation delegation, String action, Map<String, Object> parameters) {
                authority.verifyDelegatedAction(delegation, action, parameters);
            }
            public BoundedWorkflowRunner.CompletionFinding validateDelegatedCompletion(WorkflowCheckpoint checkpoint, WorkflowDelegation delegation) {
                return authority.validateDelegatedCompletion(checkpoint, delegation);
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
                if (authority.conversationalConfirmation() && outcome.executed() && outcome.actionResult() != null
                    && outcome.actionResult().getPinnedTargets() != null) {
                    for (var target : outcome.actionResult().getPinnedTargets()) {
                        if (target != null && pinnedTargets.size() < 100)
                            pinnedTargets.putIfAbsent((target.vectorSpace() == null ? "" : target.vectorSpace()) + "\u0000" + target.id(), target);
                    }
                }
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
            if (authority.conversationalConfirmation())
                input.put("activeTargets", ConversationPromptContext.activeTargets(context, 16000));
            if (!execution.policy().goalMode()) input.put("observations", checkpoint.steps().stream().map(step -> Map.of(
                "operationId", step.operationId(), "action", step.action(), "effect", step.effect(), "result", step.observation())).toList());
            input.put("remaining", Map.of("actions", execution.policy().maxActions() - allowance.segmentUsage().actions(),
                "writes", execution.policy().maxWrites() - allowance.segmentUsage().writes(),
                "modelCalls", execution.policy().maxModelCalls() - allowance.segmentUsage().modelCalls()));
            WorkflowDelegationProfile activeProfile = null;
            if (checkpoint.goal() != null) {
                input.put("directiveContext", authority.directiveContext());
                input.put("goal", checkpoint.goal()); input.put("cumulativeUsage", checkpoint.usage());
                input.put("journalSize", checkpoint.steps().size());
                input.put("delegatedProfiles", authority.delegatedProfiles().stream()
                    .filter(p -> execution.policy().goal().delegatedProfiles().contains(p.profileId()))
                    .map(p -> Map.of("profileId", p.profileId(), "allowedActions", p.allowedActions())).toList());
                if (checkpoint.goal().activeDelegation() != null) {
                    activeProfile = authority.delegatedProfiles().stream()
                        .filter(p -> p.profileId().equals(checkpoint.goal().activeDelegation().task().profileId()))
                        .findFirst().orElseThrow(() -> new SecurityException("WORKFLOW_PROFILE_UNAVAILABLE"));
                    input.put("delegatedInstructions", activeProfile.instructions());
                }
            }
            input.put("previewOnly", execution.request().previewOnly());
            if (checkpoint.stopCode() != null) {
                input.put("previousFinding", checkpoint.stopCode());
                if (checkpoint.message() != null) input.put("previousFindingDetail", checkpoint.message());
            }
            List<AIActionMetaData> catalogue = new ArrayList<>();
            for (var metadata : registry.getAllMetadata()) {
                if (metadata != null && (activeProfile == null || activeProfile.allowedActions().contains(metadata.getName()))
                    && effective.isActionVisible(metadata.getName())
                    && (metadata.getAccessMode() == ActionAccessMode.READ
                        ? effective.canExecuteReadAction(metadata.getName())
                        : orchestration.isSpecialistActionPolicyWrites() && effective.canProposeWriteAction(metadata.getName())))
                    catalogue.add(ActionMetadataVisibilitySupport.publicActionMetadata(metadata));
            }
            input.put("actions", catalogue);
            if (execution.policy().goalMode()) input.put("observations", WorkflowObservationWindow.project(checkpoint, mapper, 119000 - json(input).length()));
            String delegatedInstructions = activeProfile == null ? "" : "\nActive pinned delegated profile:\n" + activeProfile.instructions();
            String encodedInput = json(input);
            if (encodedInput.length() > 120000) throw new WorkflowAllowance.Stop("WORKFLOW_CONTEXT_LIMIT");
            var call = structured.execute(StructuredJsonCallSpec.<BoundedWorkflowRunner.Decision>builder()
                .callName("bounded_workflow_next_step").maxAttempts(1)
                .targetType(BoundedWorkflowRunner.Decision.class).objectMapper(mapper)
                .caller(attempt -> core.generateContent(AIGenerationRequest.builder()
                    .entityId(checkpoint.workId()).entityType("workflow").generationType("bounded_workflow")
                    .systemPrompt(SpecialistPromptInstructionSupport.appendToSystemPrompt(
                        renderer.render(prompts.resolve("orchestration/workflow", "system").template(), Map.of()), orchestration) + delegatedInstructions)
                    .prompt(encodedInput)
                    .inputParts(authority.conversationalConfirmation() ? orchestration.getTransientInputParts() : List.of())
                    .transientInputPolicy(authority.conversationalConfirmation() && orchestration.getTransientInputParts() != null
                        && !orchestration.getTransientInputParts().isEmpty() ? ai.fabric.dto.TransientInputPolicy.providerFileUrlDefaults() : null)
                    .authContext(OrchestrationAuthContextResolver.from(orchestration)).build(), LlmPurpose.GENERATION))
                .build());
            if (!call.isSuccess()) {
                if (call.getLastFailure() != null && (execution.policy().goalMode() || call.getLastFailure().type() != StructuredJsonFailureType.CALL_ERROR))
                    throw new BoundedWorkflowRunner.PlanningFinding(execution.policy().goalMode()
                        ? "WORKFLOW_" + call.getLastFailure().type() : "WORKFLOW_DECISION_INVALID", "Previous decision failure: " + call.getLastFailure().type()
                        + ". Return one object using type ACTION, COMPLETE, NEEDS_INPUT or UNSUPPORTED. ACTION needs an action name and parameters object. NEEDS_INPUT questions are strings, not objects."
                        + (execution.policy().goalMode() && !execution.policy().goal().delegatedProfiles().isEmpty()
                            ? " DELEGATE is also allowed with delegation {profileId, task, workReferences: [], sourceReferences: []}; use only published listed profiles and authorized references." : "")
                        + (call.getLastFailure().truncationSuspected() ? " The previous JSON may have been truncated; keep the next decision bounded." : ""),
                        call.getLastFailure().providerOutcome(), call.getLastFailure().type() == StructuredJsonFailureType.CALL_ERROR
                            || call.getLastFailure().type() == StructuredJsonFailureType.EMPTY_RESPONSE);
                throw new WorkflowAllowance.Stop("WORKFLOW_PLANNING_UNAVAILABLE");
            }
            var decision = call.getValue();
            if (authority.conversationalConfirmation() && decision.type() == BoundedWorkflowRunner.DecisionType.COMPLETE
                && (decision.message() == null || decision.message().isBlank()))
                throw new BoundedWorkflowRunner.PlanningFinding("WORKFLOW_CUSTOMER_ANSWER_REQUIRED",
                    "COMPLETE needs a nonblank message containing the customer-facing answer supported by approved observations and history.", null, false);
            return decision;
        };
        var result = authority.resumeConfirmedReceipt()
            ? runner.resumeConfirmed(execution.request(), execution.policy(), planner, actions)
            : authority.resumeGoal() && execution.policy().goalMode()
                ? runner.resumeGoal(execution.request(), execution.policy(), planner, actions)
                : runner.run(execution.request(), execution.policy(), planner, actions);
        while (authority.continueWithinRequest() && result.state() == WorkflowCheckpoint.State.YIELDED) {
            // The SDK still owns all planning/recovery. Active-turn hosts need no
            // second scheduler, and durable hosts retain their existing worker path.
            var eligible = result.goal() == null ? null : result.goal().nextEligibleAt();
            if (eligible != null && eligible.isAfter(java.time.Instant.now())) {
                try { Thread.sleep(Math.max(1, java.time.Duration.between(java.time.Instant.now(), eligible).toMillis())); }
                catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt(); throw new WorkflowAllowance.Stop("WORKFLOW_CANCELLED");
                }
            }
            if (!java.time.Instant.now().isBefore(execution.request().deadline())) break;
            authority.verify(execution.request());
            result = runner.run(execution.request(), execution.policy(), planner, actions);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("workId", result.workId()); data.put("state", result.state().name());
        if (result.stopCode() != null) data.put("stopCode", result.stopCode());
        if (result.message() != null) data.put("message", result.message());
        data.put("questions", result.questions()); data.put("usage", result.usage());
        if (result.goal() != null) {
            data.put("segmentNumber", result.goal().segmentNumber());
            data.put("segmentUsage", result.usage().minus(result.goal().segmentStartUsage()));
            if (result.goal().nextEligibleAt() != null) data.put("nextEligibleAt", result.goal().nextEligibleAt().toString());
            data.put("recentFailures", result.goal().recentFailures().stream().map(f -> new WorkflowGoalProgress.Failure(f.code(),
                f.providerOutcome() == null ? null : f.providerOutcome().withoutProviderRequestId())).toList());
        }
        int stepOffset = execution.policy().goalMode() ? Math.max(0, result.steps().size() - 16) : 0;
        data.put("totalStepCount", result.steps().size()); data.put("stepOffset", stepOffset); data.put("hasMore", stepOffset > 0);
        data.put("steps", result.steps().subList(stepOffset, result.steps().size()).stream().map(step -> Map.of("operationId", step.operationId(),
            "action", step.action(), "write", step.write(), "effect", step.effect().name())).toList());
        OrchestrationResultType type = switch (result.state()) {
            case COMPLETE -> OrchestrationResultType.INFORMATION_PROVIDED;
            case NEEDS_INPUT -> OrchestrationResultType.CLARIFICATION_REQUIRED;
            case CONFIRMATION_REQUIRED -> OrchestrationResultType.CONFIRMATION_REQUIRED;
            default -> OrchestrationResultType.ERROR;
        };
        if (authority.conversationalConfirmation() && result.state() == WorkflowCheckpoint.State.COMPLETE
            && result.steps().stream().anyMatch(step -> step.write() && step.effect() == WorkflowCheckpoint.Effect.EXECUTED))
            type = OrchestrationResultType.ACTION_EXECUTED;
        var responseData = new LinkedHashMap<String, Object>(authority.responseData(result));
        responseData.put("workflow", data);
        var response = OrchestrationResult.builder().type(type).success(result.state() == WorkflowCheckpoint.State.COMPLETE)
            .message(result.message()).data(responseData).errorCode(result.stopCode())
            .internalPinnedTargets(List.copyOf(pinnedTargets.values())).build();
        if (authority.conversationalConfirmation() && result.state() == WorkflowCheckpoint.State.NEEDS_INPUT
            && (result.message() == null || result.message().isBlank()) && !result.questions().isEmpty())
            response.setMessage(String.join("\n", result.questions()));
        if (authority.conversationalConfirmation() && !result.steps().isEmpty()) {
            var last = result.steps().getLast();
            var metadata = registry.findMetadata(last.action()).orElse(null);
            if (last.effect() == WorkflowCheckpoint.Effect.REJECTED && "ACTION_NOT_ALLOWED".equals(last.code())
                && metadata != null && !metadata.isAnonymousAllowed() && new ActionContext(orchestration, context).isAnonymous()) {
                response.setErrorCode(ai.fabric.intent.orchestration.auth.AuthenticationRequirement.ERROR_CODE);
                responseData.put(ai.fabric.intent.orchestration.auth.AuthenticationRequirement.DATA_KEY,
                    ai.fabric.intent.orchestration.auth.AuthenticationRequirement.forAction(last.action()));
            }
        }
        if (result.state() == WorkflowCheckpoint.State.CONFIRMATION_REQUIRED) {
            var step = result.steps().getLast();
            var dispatchContext = orchestration.toBuilder().workflowOperationId(step.operationId()).build();
            var metadata = registry.findMetadata(step.action()).orElseThrow();
            var parameters = ActionRuntimeContextParamSupport.resolveConfiguredParameters(metadata, step.parameters(), dispatchContext, context);
            var actionContext = new ActionContext(dispatchContext, context, parameters);
            if (authority.conversationalConfirmation()) {
                var pendingStore = pendingActions == null ? null : pendingActions.getIfAvailable();
                if (pendingStore == null || !orchestration.hasConversation())
                    throw new IllegalStateException("WORKFLOW_CONVERSATIONAL_CONFIRMATION_UNAVAILABLE");
                var handler = registry.findHandler(step.action()).orElseThrow();
                String confirmation = handler.getConfirmationMessage(parameters, actionContext);
                if (confirmation == null || confirmation.isBlank()) confirmation = "Confirm this action?";
                var resolved = new java.util.LinkedHashSet<String>();
                if (metadata.getParameterSchemas() != null) metadata.getParameterSchemas().forEach((name, schema) -> {
                    if (schema != null && schema.getResolveFrom() != null
                        && "RUNTIME_CONTEXT".equalsIgnoreCase(String.valueOf(schema.getResolveFrom().get("source")))
                        && parameters.containsKey(name)) resolved.add(name);
                });
                ai.fabric.intent.orchestration.conversation.ConversationStateStoreAccess.pushPending(pendingStore, context,
                    new PendingAction(step.action(), parameters, confirmation, java.time.Instant.now(), Map.of(), resolved));
                responseData.put("action", step.action()); responseData.put("confirmationRequired", true);
                responseData.put("confirmationMessage", confirmation);
                responseData.put("providedParameters", ActionMetadataVisibilitySupport.publicProvidedParameters(metadata, parameters));
                response.setMessage(confirmation);
            } else response.setActionProposalCandidate(new ActionProposalCandidate(step.action(), parameters, actionContext));
        }
        return context.terminate(response);
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalArgumentException("WORKFLOW_CONTEXT_ENCODING_FAILED"); }
    }
}
