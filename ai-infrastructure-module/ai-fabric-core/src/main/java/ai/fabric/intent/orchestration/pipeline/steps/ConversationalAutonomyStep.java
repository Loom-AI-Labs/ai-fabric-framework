package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.intent.action.*;
import ai.fabric.intent.action.invocation.*;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.*;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import ai.fabric.intent.orchestration.workflow.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Opt-in SDK workflow admission for an ordinary, already-authorized conversation turn. */
@Component
@RequiredArgsConstructor
public final class ConversationalAutonomyStep implements PipelineStep {
    private final OrchestrationProperties properties;
    private final AIActionRegistry registry;
    private final ObjectMapper mapper;
    private static final Set<String> REPLAN_READ = Set.of("UPSTREAM_HTTP_400", "UPSTREAM_HTTP_404",
        "KNOWLEDGE_SOURCE_SELECTION_REQUIRED");
    public String getStepName() { return "ConversationalAutonomy"; }
    public int getOrder() { return 48; }
    public boolean shouldSkip(PipelineContext context) {
        if (context == null || context.isShouldTerminate() || context.getOrchestrationContext() == null
            || context.getOrchestrationContext().getWorkflowExecution() != null
            || properties.getAutonomy() == null || !properties.getAutonomy().isEnabled()) return true;
        // Explicit execution/specialist contracts keep precedence over deployment-wide chat defaults.
        if (context.getOrchestrationRequest() != null
            && context.getOrchestrationRequest().purpose() != OrchestrationRequestPurpose.GENERAL) return true;
        String requested = context.getOrchestrationContext().getMode();
        if ("standard".equalsIgnoreCase(requested)) return true;
        return !properties.getAutonomy().isDefaultEnabled() && !"autonomous".equalsIgnoreCase(requested);
    }
    public PipelineContext process(PipelineContext context) {
        if (shouldSkip(context)) return context;
        if (context.getObservedPendingAction() != null) {
            // Consent is resolved by the existing semantic confirmation lifecycle, never by a planner flag.
            return context.withMetadata("conversationalAutonomy", Map.of("enabled", true, "state", "EXISTING_CONFIRMATION"));
        }
        var settings = properties.getAutonomy();
        var original = context.getOrchestrationContext();
        var effective = GovernedActionInvocationSupport.effectiveProfile(registry, context, original);
        var reads = new LinkedHashSet<>(effective.executableReadActions());
        var visible = new LinkedHashSet<>(effective.visibleActions());
        var writes = settings.getMaxWritesPerSegment() == 0 ? Set.<String>of() : effective.proposableWriteActions();
        if (writes.isEmpty()) visible.removeAll(effective.proposableWriteActions());
        if (effective.retrievalEnabled() && !effective.effectiveVectorSpaces().isEmpty()
            && registry.findMetadata(AutonomyKnowledgeAction.NAME).isPresent()) {
            reads.add(AutonomyKnowledgeAction.NAME); visible.add(AutonomyKnowledgeAction.NAME);
        } else { reads.remove(AutonomyKnowledgeAction.NAME); visible.remove(AutonomyKnowledgeAction.NAME); }
        var profile = new EffectiveCapabilityProfile(effective.profile(), effective.mode(), effective.retrievalEnabled(),
            effective.effectiveVectorSpaces(), visible, reads, writes,
            effective.ragBudgets(), effective.readActionResolutionPolicy(), effective.profileHash());
        Instant deadline = Instant.now().plusSeconds(settings.getMaxElapsedSeconds());
        String expires = OrchestrationAuthContextResolver.from(original).getExpiresAt();
        if (expires != null && !expires.isBlank()) {
            Instant expiry = Instant.parse(expires); if (expiry.isBefore(deadline)) deadline = expiry;
        }
        final Instant ceiling = deadline;
        String id = "chat-work-" + context.getRequestId();
        String authorityHash = fingerprint(List.of(OrchestrationAuthContextResolver.from(original), profile));
        String definitionHash = fingerprint(settings);
        Runnable verify = () -> {
            if (!Instant.now().isBefore(ceiling)) throw new WorkflowAllowance.Stop("WORKFLOW_AUTHORITY_EXPIRED");
            if (original.getOrchestrationTurnGuard() != null) original.getOrchestrationTurnGuard().verifyBeforeOrchestration();
        };
        var store = new ActiveTurnWorkflowCheckpointStore(id, () -> {
            if (original.getOrchestrationTurnGuard() != null) original.getOrchestrationTurnGuard().verifyBeforeOrchestration();
        });
        var policy = new WorkflowPolicy(true, settings.getMaxActionsPerSegment(), settings.getMaxWritesPerSegment(),
            settings.getMaxModelCallsPerSegment(), settings.getMaxCorrectionsPerSegment(), Duration.ofSeconds(settings.getSegmentSeconds()),
            new WorkflowGoalPolicy(true, Duration.ofSeconds(settings.getMaxElapsedSeconds()), Duration.ZERO,
                settings.getMaxConsecutiveNoProgress(), settings.getMaxConsecutivePlannerFailures(), List.of()));
        var request = new BoundedWorkflowRunner.Request(id, fingerprint(context.getEffectiveQuery()), authorityHash, definitionHash, deadline, false);
        var authority = new WorkflowExecution.Authority() {
            public boolean continueWithinRequest() { return true; }
            public boolean conversationalConfirmation() { return true; }
            public void verify(BoundedWorkflowRunner.Request bound) {
                if (!request.equals(bound)) throw new SecurityException("WORKFLOW_TURN_BINDING_DENIED");
                verify.run();
            }
            public String fingerprint(Map<String, Object> parameters) { return ConversationalAutonomyStep.this.fingerprint(parameters); }
            public Map<String, Object> directiveContext() {
                return Map.of("execution", "ACTIVE_CONVERSATION_TURN", "knowledgeSources", profile.effectiveVectorSpaces(),
                    "ragCooperationMode", profile.readActionResolutionPolicy().ragCooperationMode().name());
            }
            public BoundedWorkflowRunner.CompletionFinding validateCompletion(WorkflowCheckpoint checkpoint) {
                // This is an explicit published execution requirement, not inferred intent or confidence.
                boolean required = reads.contains(AutonomyKnowledgeAction.NAME)
                    && profile.readActionResolutionPolicy().ragCooperationMode()
                        == OrchestrationProperties.ReadActionResolutionRagCooperationMode.PARALLEL_ACTIONS_AND_RAG;
                if (!required) return null;
                boolean businessRead = checkpoint.steps().stream().anyMatch(step -> !step.write()
                    && !AutonomyKnowledgeAction.NAME.equals(step.action()) && step.effect() == WorkflowCheckpoint.Effect.EXECUTED);
                boolean searched = checkpoint.steps().stream().anyMatch(step -> AutonomyKnowledgeAction.NAME.equals(step.action())
                    && step.effect() == WorkflowCheckpoint.Effect.EXECUTED);
                return businessRead && !searched ? new BoundedWorkflowRunner.CompletionFinding("WORKFLOW_REQUIRED_KNOWLEDGE_SEARCH",
                    "The published policy requires authorized knowledge search alongside executed business READ tools. Search an available authorized source before completion; do not ask the visitor for an internal domain.") : null;
            }
            public BoundedWorkflowRunner.Outcome reconcile(WorkflowCheckpoint.Step step) {
                // Active-turn journals are not resumed after process/transport loss. Never redispatch a write.
                return new BoundedWorkflowRunner.Outcome(step.write() ? WorkflowCheckpoint.Effect.OUTCOME_UNKNOWN
                    : WorkflowCheckpoint.Effect.REJECTED, Map.of("observationAvailable", false), "WORKFLOW_OBSERVATION_UNAVAILABLE", !step.write());
            }
            public boolean isCorrectableNoncommittedFailure(AIActionMetaData metadata, ActionContext action, GovernedActionInvocationOutcome outcome) {
                return settings.isReplanNonAuthorityReadFailures() && metadata.getAccessMode() == ActionAccessMode.READ
                    && outcome.actionResult() != null && REPLAN_READ.contains(outcome.actionResult().getErrorCode());
            }
            public Map<String, Object> observation(AIActionMetaData metadata, ActionContext action, GovernedActionInvocationOutcome outcome) {
                var data = new LinkedHashMap<String, Object>();
                data.put("action", metadata.getName()); data.put("success", outcome.executed());
                if (outcome.publicFailure() != null) data.put("code", outcome.publicFailure().reason());
                if (outcome.executed()) {
                    try {
                        var facts = registry.findHandler(metadata.getName()).orElseThrow()
                            .buildPostActionLlmFacts(outcome.actionResult(), action).orElse(Map.of());
                        data.put("facts", mapper.convertValue(facts, new TypeReference<Map<String, Object>>() {}));
                    } catch (RuntimeException unavailable) {
                        // An executed write remains executed when only evidence projection failed.
                        data.put("observationAvailable", false);
                    }
                }
                return data;
            }
            public Map<String, Object> responseData(WorkflowCheckpoint checkpoint) {
                var actions = new ArrayList<Map<String, Object>>();
                var documents = new ArrayList<Object>();
                for (var step : checkpoint.steps()) {
                    if (step.effect() != WorkflowCheckpoint.Effect.EXECUTED) continue;
                    Object raw = step.observation().get("facts");
                    if (!(raw instanceof Map<?, ?> facts)) continue;
                    if (AutonomyKnowledgeAction.NAME.equals(step.action())) {
                        if (facts.get("documents") instanceof List<?> docs) documents.addAll(docs);
                    } else {
                        Object payload = step.action().equals(facts.get("action")) && facts.get("success") instanceof Boolean
                            ? facts.getOrDefault("data", null) : facts;
                        actions.add(Map.of("action", step.action(), "actionExecutionId", step.operationId(),
                            "actionResult", Map.of("success", true, "data", payload == null ? Map.of() : payload)));
                    }
                }
                return Map.of("actions", List.copyOf(actions), "documents", List.copyOf(documents));
            }
        };
        var execution = new WorkflowExecution(request, policy, store, authority);
        String instructions = "For this conversation turn, use the available tools and approved history to fulfil the user's request. "
            + "Resolve identifiers from authorized results; do not substitute a display name for an API identifier. "
            + "If knowledge is needed, search an available authorized source and reformulate the search when useful. "
            + "Never ask the user to select an internal knowledge domain or planning attribute. "
            + "Do not repeat an executed write. Stop for genuine missing business input or consent. "
            + "When approved history is sufficient, answer without unnecessary tools. COMPLETE.message must be the useful customer-facing answer, not just a technical execution summary. "
            + settings.getInstructions();
        if (original.getSpecialistInstructions() != null && !original.getSpecialistInstructions().isBlank())
            instructions += "\nExisting application instructions:\n" + original.getSpecialistInstructions();
        var overlays = ai.fabric.prompt.PromptPreviewOverlaySupport.extract(original.getMetadata());
        instructions = ai.fabric.prompt.PromptPreviewOverlaySupport.appendOverlaySection(instructions, overlays, "systemPrompt", "Published business instructions");
        instructions = ai.fabric.prompt.PromptPreviewOverlaySupport.appendOverlaySection(instructions, overlays, "answerGenerationPrompt", "Published answer style and evidence policy");
        var bound = original.toBuilder().workflowExecution(execution).effectiveCapabilityProfile(profile)
            .specialistActionPolicyWrites(!profile.proposableWriteActions().isEmpty())
            .specialistInstructions(instructions).build();
        return context.toBuilder().orchestrationContext(bound).effectiveCapabilityProfile(profile).build()
            .withMetadata("conversationalAutonomy", Map.of("enabled", true, "workId", id,
                "execution", "ACTIVE_REQUEST", "deadline", deadline.toString()));
    }
    private String fingerprint(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalArgumentException("WORKFLOW_BINDING_ENCODING_FAILED"); }
    }
}
