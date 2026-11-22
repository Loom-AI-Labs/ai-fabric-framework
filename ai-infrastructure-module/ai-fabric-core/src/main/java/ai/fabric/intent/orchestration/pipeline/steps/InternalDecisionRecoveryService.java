package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.DecisionRecoveryProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.ResponseGenerationProfile;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.conversation.ConversationPromptContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.OrchestrationIntentPolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InternalDecisionRecoveryService {
    public static final String UNRESOLVED = "INTERNAL_DECISION_UNRESOLVED";
    private static final String FAMILY = "orchestration/decision-recovery";
    @Getter private final DecisionRecoveryProperties settings;
    private final AICoreService core;
    private final AIServiceConfig serviceConfig;
    private final IntentExtractionJsonSupport json;
    private final PromptTemplateResolver prompts;
    private final PromptRenderer renderer;
    private final RagResponseGenerationSupport generation;

    public InternalDecisionRecoveryService(DecisionRecoveryProperties settings, AICoreService core,
        AIServiceConfig serviceConfig, IntentExtractionJsonSupport json,
        PromptTemplateResolver prompts, PromptRenderer renderer) {
        this.settings = settings;
        this.core = core;
        this.serviceConfig = serviceConfig;
        this.json = json;
        this.prompts = prompts;
        this.renderer = renderer;
        this.generation = new RagResponseGenerationSupport(core, serviceConfig, prompts, renderer);
    }

    public boolean enabledFor(PipelineContext context) {
        return settings.isEnabled() && context != null && context.getDecisionRecoveryBudget() != null
            && (context.getOrchestrationRequest() == null
                || (context.getOrchestrationRequest().intentPolicy() == OrchestrationIntentPolicy.MODEL_DIRECTED
                    && context.getOrchestrationRequest().purpose() == OrchestrationRequestPurpose.GENERAL));
    }

    public static OrchestrationResult unresolved(String reason, Map<String, Object> data) {
        Map<String, Object> details = new LinkedHashMap<>(data == null ? Map.of() : data);
        details.put("reason", reason);
        return OrchestrationResult.builder().type(OrchestrationResultType.ERROR).success(false)
            .errorCode(UNRESOLVED).message("Internal decision recovery is required.")
            .data(Map.copyOf(details)).build();
    }

    public static boolean isUnresolved(OrchestrationResult result) {
        return result != null && result.getType() == OrchestrationResultType.ERROR
            && UNRESOLVED.equals(result.getErrorCode());
    }

    public boolean repairRouting(Intent intent, PipelineContext context, OrchestrationResult failure,
                                 List<String> candidates, int maxSpaces, boolean contextAnswerAllowed) {
        if (!enabledFor(context) || intent == null || !isUnresolved(failure)) return false;
        var budget = context.getDecisionRecoveryBudget();
        String finding = String.valueOf(failure.getData().get("reason"));
        List<String> allowed = candidates == null ? List.of() : List.copyOf(candidates);
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("requiresRetrieval", intent.getRequiresRetrieval());
        initial.put("vectorSpace", intent.getVectorSpace());
        Object latestCandidate = initial;
        while (budget.acquire(settings, "SOURCE_SELECTION")) {
            String feedback;
            try {
                feedback = json.objectMapper().writeValueAsString(Map.of(
                    "latestCandidate", latestCandidate, "finding", finding,
                    "allowedVectorSpaces", allowed, "maxSpaces", Math.max(1, maxSpaces),
                    "contextAnswerAllowed", contextAnswerAllowed));
            } catch (Exception ex) { throw new IllegalStateException("Cannot prepare routing feedback", ex); }
            String targets = ConversationPromptContext.activeTargets(context,
                ConversationPromptContext.characterBudget(context));
            String prompt = renderer.render(prompts.resolve(FAMILY, "repair-user").template(), Map.of(
                "query", context.getEffectiveQuery(), "feedback", feedback, "context", targets));
            AIGenerationResponse response;
            long start = System.nanoTime();
            try {
                response = core.generateContent(AIGenerationRequest.builder()
                    .entityId(context.getRequestId() + "-routing-repair")
                    .entityType("intent_extraction").generationType("routing_decision_repair")
                    .systemPrompt(renderer.render(prompts.resolve(FAMILY, "repair-system").template(), Map.of()))
                    .prompt(prompt).messages(ConversationPromptContext.history(context,
                        ConversationPromptContext.characterBudget(context)).messages())
                    .parameters(json.jsonOnlyResponseParameters(RoutingDecision.class))
                    .authContext(OrchestrationAuthContextResolver.from(context.getOrchestrationContext())).build(),
                    LlmPurpose.ORCHESTRATION);
            } catch (Exception ex) {
                budget.providerFailed();
                budget.recordOutcome(Map.of("status", "PROVIDER_FAILED", "modelCalls", 1,
                    "processingTimeMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                    "exceptionType", ex.getClass().getSimpleName()));
                return false;
            }
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("modelCalls", 1);
            trace.put("processingTimeMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            if (response != null && response.getModel() != null) trace.put("model", response.getModel());
            if (response != null && response.getProcessingTimeMs() != null)
                trace.put("providerProcessingTimeMs", response.getProcessingTimeMs());
            latestCandidate = response != null && response.getContent() != null ? response.getContent() : "<empty model output>";
            try {
                RoutingDecision decision = json.parsePayload(response != null ? response.getContent() : null, RoutingDecision.class);
                latestCandidate = decision;
                if (decision.requiresRetrieval() == null) finding = "MISSING_RETRIEVAL_DECISION";
                else if (!decision.requiresRetrieval() && !contextAnswerAllowed) finding = "RETRIEVAL_REQUIRED_BY_POLICY";
                else if (decision.requiresRetrieval() && (decision.vectorSpaces() == null || decision.vectorSpaces().isEmpty()))
                    finding = "MISSING_SOURCE_SELECTION";
                else if (decision.requiresRetrieval() && decision.vectorSpaces().stream().anyMatch(s -> !allowed.contains(s)))
                    finding = "SOURCE_NOT_IN_ALLOWED_CATALOGUE";
                else if (decision.requiresRetrieval() && decision.vectorSpaces().size() > Math.max(1, maxSpaces))
                    finding = "SOURCE_LIMIT_EXCEEDED";
                else {
                    intent.setRequiresRetrieval(decision.requiresRetrieval());
                    intent.setRequiresGeneration(true);
                    intent.setVectorSpace(decision.requiresRetrieval() ? String.join(",", decision.vectorSpaces()) : null);
                    trace.put("status", "REPAIRED");
                    trace.put("requiresRetrieval", decision.requiresRetrieval());
                    budget.recordOutcome(trace);
                    return true;
                }
                trace.put("status", finding);
            } catch (Exception ex) {
                finding = "INVALID_ROUTING_OUTPUT";
                trace.put("status", finding);
            }
            budget.recordOutcome(trace);
        }
        return false;
    }

    public OrchestrationResult finalContextAnswer(PipelineContext context, OrchestrationResult result) {
        if (!enabledFor(context) || !containsRecoverableFailure(result)) return result;
        if (containsHardFailure(result)) {
            OrchestrationResult hard = firstHardFailure(result);
            List<OrchestrationResult> retained = retainedChildren(result);
            Map<String, Object> data = new LinkedHashMap<>(hard.getData() == null ? Map.of() : hard.getData());
            data.put("results", retained);
            return hard.toBuilder().children(retained).data(java.util.Collections.unmodifiableMap(data))
                .internalPinnedTargets(result.getInternalPinnedTargets()).build();
        }
        var budget = context.getDecisionRecoveryBudget();
        if (budget.isProviderFailed()) return serviceFailure(context, result,
            "DECISION_RECOVERY_PROVIDER_FAILED", "The AI service is temporarily unavailable.");
        if (serviceConfig != null && serviceConfig.getFeatures() != null
            && !Boolean.TRUE.equals(serviceConfig.getFeatures().getEnableGeneration())) return result;
        if (!budget.acquireFinalAnswer(settings)) return result;
        List<OrchestrationResult> leaves = result.getChildren() == null || result.getChildren().isEmpty()
            ? List.of(result) : result.getChildren();
        String evidence = CompoundReadEvidenceSupport.preparedRecoveryContext(context, leaves);
        String prompt = renderer.render(prompts.resolve(FAMILY, "context-user").template(), Map.of(
                "query", context.getEffectiveQuery(), "context", evidence == null ? "" : evidence));
        try {
            var trace = generation.generatePromptResponse(prompt, "adhoc", "decision_recovery_context_answer",
                LlmPurpose.GENERATION, "DECISION_RECOVERY_CONTEXT_ANSWER",
                generation.resolveResponseGenerationMaxTokens(ResponseGenerationProfile.STANDARD, context.getOrchestrationPolicy()),
                context, ManagedGenerationPromptSupport.renderManagedGenerationPrompt(prompts, renderer,
                    FAMILY, "context-system", Map.of(), ManagedGenerationPromptSupport.extractPromptPreview(context)));
            List<OrchestrationResult> retained = retainedChildren(result);
            Map<String, Object> data = new LinkedHashMap<>(result.getData() == null ? Map.of() : result.getData());
            data.remove("reason"); data.remove("candidateVectorSpaces"); data.remove("allowedVectorSpaces");
            data.put("answer", trace.content()); data.put("requiresRetrieval", false);
            data.put("requiresGeneration", true); data.put("results", retained);
            List<Object> publicActions = new ArrayList<>();
            for (OrchestrationResult child : leaves) {
                if (child.getData() != null && child.getData().get("actions") instanceof List<?> actions)
                    publicActions.addAll(actions);
            }
            if (!publicActions.isEmpty()) data.put("actions", List.copyOf(publicActions));
            Map<String, Object> metadata = new LinkedHashMap<>(result.getMetadata() == null ? Map.of() : result.getMetadata());
            metadata.putAll(generation.responseGenerationMetadata(trace));
            metadata.put("decisionRecovery", budget.diagnostics(settings));
            metadata.put("answerSource", "AVAILABLE_CONTEXT_RECOVERY");
            metadata.put("newRetrievalPerformedByFallback", false);
            boolean pending = retained.stream().anyMatch(c -> c.getType() == OrchestrationResultType.CONFIRMATION_REQUIRED
                || c.getType() == OrchestrationResultType.CLARIFICATION_REQUIRED);
            return result.toBuilder().type(pending ? OrchestrationResultType.COMPOUND_HANDLED : OrchestrationResultType.INFORMATION_PROVIDED)
                .success(!pending).errorCode(null).message(trace.content()).data(java.util.Collections.unmodifiableMap(data))
                .metadata(Map.copyOf(metadata)).children(retained)
                .internalPinnedTargets(CompoundReadEvidenceSupport.collectPinnedTargets(leaves)).build();
        } catch (Exception ex) {
            return serviceFailure(context, result, "GENERATION_FAILED", "The AI response could not be generated.");
        }
    }

    private OrchestrationResult serviceFailure(PipelineContext context, OrchestrationResult result,
                                               String code, String message) {
        List<OrchestrationResult> retained = retainedChildren(result);
        Map<String, Object> data = new LinkedHashMap<>(result.getData() == null ? Map.of() : result.getData());
        data.remove("reason"); data.remove("candidateVectorSpaces"); data.remove("allowedVectorSpaces");
        data.put("results", retained);
        Map<String, Object> metadata = new LinkedHashMap<>(result.getMetadata() == null ? Map.of() : result.getMetadata());
        metadata.put("decisionRecovery", context.getDecisionRecoveryBudget().diagnostics(settings));
        return result.toBuilder().type(OrchestrationResultType.ERROR).success(false).errorCode(code).message(message)
            .data(java.util.Collections.unmodifiableMap(data)).children(retained).metadata(Map.copyOf(metadata)).build();
    }

    private List<OrchestrationResult> retainedChildren(OrchestrationResult result) {
        if (result.getChildren() == null) return List.of();
        return result.getChildren().stream().filter(child -> !isUnresolved(child)).map(child -> {
            if (child.getChildren() == null || child.getChildren().isEmpty()) return child;
            List<OrchestrationResult> retained = retainedChildren(child);
            Map<String, Object> data = new LinkedHashMap<>(child.getData() == null ? Map.of() : child.getData());
            if (data.containsKey("results")) data.put("results", retained);
            return child.toBuilder().children(retained).data(java.util.Collections.unmodifiableMap(data)).build();
        }).toList();
    }

    private boolean containsRecoverableFailure(OrchestrationResult result) {
        return isUnresolved(result) || result != null && result.getChildren() != null
            && result.getChildren().stream().anyMatch(this::containsRecoverableFailure);
    }

    private boolean containsHardFailure(OrchestrationResult result) {
        if (result == null) return false;
        if ((result.getType() == OrchestrationResultType.ERROR && !isUnresolved(result))
            || result.getType() == OrchestrationResultType.ACTION_DENIED) return true;
        return result.getChildren() != null && result.getChildren().stream().anyMatch(this::containsHardFailure);
    }

    private OrchestrationResult firstHardFailure(OrchestrationResult result) {
        if (result.getChildren() != null) {
            for (OrchestrationResult child : result.getChildren()) {
                if (containsHardFailure(child)) return firstHardFailure(child);
            }
        }
        return result;
    }

    public record RoutingDecision(Boolean requiresRetrieval, List<String> vectorSpaces) {}
}
