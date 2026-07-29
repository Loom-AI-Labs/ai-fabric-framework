package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIChatRole;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionNames;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.actiondraft.ActionDraftContinuation;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.llm.structured.StructuredJsonCallSpec;
import ai.fabric.llm.structured.StructuredJsonProviderHints;
import ai.fabric.llm.structured.StructuredJsonResult;
import ai.fabric.llm.structured.StructuredJsonCallExecutor;
import ai.fabric.llm.structured.springai.SpringAiStructuredOutputSupport;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Uses a bounded structured LLM call to decide whether the current turn
 * semantically continues an incomplete action draft.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionDraftContinuationAnalyzer {

    private static final String TEMPLATE_FAMILY =
        "intent-extraction/action-draft-continuation";
    private static final String TEMPLATE_SYSTEM = "system";
    private static final String TEMPLATE_USER = "user";
    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final int MAX_HISTORY_CHARACTERS = 6_000;

    private final AICoreService aiCoreService;
    private final StructuredJsonCallExecutor structuredJsonCallExecutor;
    private final AIActionRegistry actionRegistry;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;

    public AnalysisOutcome analyze(PipelineContext context) {
        ActionDraftContinuation continuation = context != null
            ? context.getActionDraftContinuation()
            : null;
        if (continuation == null
            || !StringUtils.hasText(context.getEffectiveQuery())) {
            return AnalysisOutcome.notEvaluated();
        }

        AIActionMetaData metadata = actionRegistry
            .findMetadata(continuation.action())
            .orElse(null);
        if (metadata == null) {
            return AnalysisOutcome.failed(
                "ACTION_METADATA_UNAVAILABLE",
                0
            );
        }

        Set<String> allowedParameters = publicParameterNames(metadata);
        var structuredOutput =
            SpringAiStructuredOutputSupport.bean(ContinuationDecision.class);
        String systemPrompt;
        String userPrompt;
        try {
            systemPrompt = promptRenderer.render(
                promptTemplateResolver
                    .resolve(TEMPLATE_FAMILY, TEMPLATE_SYSTEM)
                    .template(),
                Map.of(
                    "action_name", oneLine(continuation.action()),
                    "collected_parameter_names",
                    fieldNames(continuation.collectedParams().keySet()),
                    "missing_parameter_names",
                    fieldNames(continuation.missingParameters()),
                    "parameter_contracts",
                    parameterContracts(metadata, allowedParameters),
                    "output_format", structuredOutput.format()
                )
            );
            userPrompt = promptRenderer.render(
                promptTemplateResolver
                    .resolve(TEMPLATE_FAMILY, TEMPLATE_USER)
                    .template(),
                Map.of("current_user_message", context.getEffectiveQuery())
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Action draft continuation prompt could not be prepared ({})",
                ex.getClass().getSimpleName()
            );
            return AnalysisOutcome.failed("PROMPT_UNAVAILABLE", 0);
        }

        AtomicReference<AIGenerationResponse> providerResponse =
            new AtomicReference<>();
        StructuredJsonResult<ContinuationDecision> result =
            structuredJsonCallExecutor.execute(
                StructuredJsonCallSpec.<ContinuationDecision>builder()
                    .callName("action_draft_continuation")
                    .maxAttempts(2)
                    .retryOnCallError(false)
                    .caller(attempt -> {
                        String attemptSystemPrompt = attempt.attemptIndex() > 0
                            ? systemPrompt
                                + "\n\nThe previous response did not satisfy the JSON contract. Return only a corrected JSON object."
                            : systemPrompt;
                        AIGenerationResponse response =
                            aiCoreService.generateContent(
                                AIGenerationRequest.builder()
                                    .entityId(
                                        "action-draft-" + UUID.randomUUID()
                                    )
                                    .entityType("action-draft-continuation")
                                    .generationType(
                                        "semantic_continuation"
                                    )
                                    .systemPrompt(attemptSystemPrompt)
                                    .prompt(userPrompt)
                                    .messages(
                                        boundedHistory(
                                            context.getHistoryMessages(),
                                            context.getEffectiveQuery()
                                        )
                                    )
                                    .parameters(
                                        StructuredJsonProviderHints
                                            .jsonObjectResponseParameters()
                                    )
                                    .maxTokens(450)
                                    .temperature(0.0d)
                                    .authContext(authContext(context))
                                    .build(),
                                LlmPurpose.ORCHESTRATION
                            );
                        providerResponse.set(response);
                        return response;
                    })
                    .responseConverter(structuredOutput.converter())
                    .validator(decision ->
                        validateDecision(
                            decision,
                            continuation,
                            allowedParameters
                        )
                    )
                    .build()
            );

        if (!result.isSuccess() || result.getValue() == null) {
            String failure = result.getLastFailure() != null
                ? result.getLastFailure().type().name()
                : "UNKNOWN";
            return AnalysisOutcome.failed(failure, result.getAttempts());
        }

        ContinuationDecision decision = result.getValue();
        String model = providerResponse.get() != null
            ? providerResponse.get().getModel()
            : null;
        if (!Boolean.TRUE.equals(decision.continuesDraft())) {
            return AnalysisOutcome.independent(
                result.getAttempts(),
                model
            );
        }

        Map<String, Object> suppliedParameters =
            ActionDraftContinuationSupport.sanitizeAnalyzedParameters(
                metadata,
                decision.providedParams(),
                allowedParameters
            );
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .intent(continuation.action())
            .action(continuation.action())
            .actionParams(suppliedParameters)
            .confidence(normalizeConfidence(decision.confidence()))
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .requiresTargetResolution(false)
            .build();
        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(intent))
            .orchestrationStrategy("ACTION_DRAFT_CONTINUATION")
            .metadata(Map.of("semanticContinuation", true))
            .build();
        response.normalize();
        return AnalysisOutcome.continued(
            response,
            suppliedParameters.keySet(),
            result.getAttempts(),
            model
        );
    }

    private void validateDecision(
        ContinuationDecision decision,
        ActionDraftContinuation continuation,
        Set<String> allowedParameters
    ) {
        if (decision == null || decision.continuesDraft() == null) {
            throw new IllegalArgumentException(
                "continuesDraft must be supplied"
            );
        }
        if (!Boolean.TRUE.equals(decision.continuesDraft())) {
            return;
        }
        if (!AIActionNames.normalize(continuation.action()).equals(
            AIActionNames.normalize(decision.action())
        )) {
            throw new IllegalArgumentException(
                "continued action must match the stored draft"
            );
        }
        if (decision.providedParams() == null) {
            return;
        }
        for (String key : decision.providedParams().keySet()) {
            if (!containsIgnoreCase(allowedParameters, key)) {
                throw new IllegalArgumentException(
                    "providedParams contains an unknown or hidden field"
                );
            }
        }
    }

    private Set<String> publicParameterNames(AIActionMetaData metadata) {
        Set<String> names = new LinkedHashSet<>();
        addPublicNames(names, metadata, metadata.getParameters());
        addPublicNames(names, metadata, metadata.getParameterSchemas());
        if (metadata.getRequiredParameters() != null) {
            metadata.getRequiredParameters().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(name ->
                    ActionParameterSupport.isUserVisibleActionParameter(
                        metadata,
                        name
                    )
                )
                .forEach(names::add);
        }
        return Collections.unmodifiableSet(names);
    }

    private void addPublicNames(
        Set<String> target,
        AIActionMetaData metadata,
        Map<String, ?> source
    ) {
        if (source == null) {
            return;
        }
        source.keySet().stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .filter(name ->
                ActionParameterSupport.isUserVisibleActionParameter(
                    metadata,
                    name
                )
            )
            .forEach(target::add);
    }

    private String parameterContracts(
        AIActionMetaData metadata,
        Set<String> names
    ) {
        if (names.isEmpty()) {
            return "- none";
        }
        List<String> contracts = new ArrayList<>();
        for (String name : names) {
            String description = metadata.getParameters() != null
                ? metadata.getParameters().get(name)
                : null;
            var schema = ActionParameterSupport.paramSchema(metadata, name);
            String type = schema != null && schema.getType() != null
                ? schema.getType().name()
                : "VALUE";
            boolean required = metadata.getRequiredParameters() != null
                && metadata.getRequiredParameters().contains(name);
            contracts.add(
                "- " + oneLine(name)
                    + " (" + type + (required ? ", required" : ", optional")
                    + "): " + abbreviate(oneLine(description), 240)
            );
        }
        return String.join("\n", contracts);
    }

    private List<AIChatMessage> boundedHistory(
        List<AIChatMessage> source,
        String currentMessage
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<AIChatMessage> accepted = new ArrayList<>();
        int characters = 0;
        for (int index = source.size() - 1; index >= 0; index--) {
            AIChatMessage message = source.get(index);
            if (message == null
                || (message.getRole() != AIChatRole.USER
                    && message.getRole() != AIChatRole.ASSISTANT)
                || !StringUtils.hasText(message.getContent())) {
                continue;
            }
            String content = message.getContent().trim();
            if (accepted.isEmpty()
                && message.getRole() == AIChatRole.USER
                && content.equals(currentMessage.trim())) {
                continue;
            }
            if (accepted.size() >= MAX_HISTORY_MESSAGES
                || characters + content.length() > MAX_HISTORY_CHARACTERS) {
                break;
            }
            accepted.add(
                AIChatMessage.builder()
                    .role(message.getRole())
                    .content(content)
                    .build()
            );
            characters += content.length();
        }
        Collections.reverse(accepted);
        return List.copyOf(accepted);
    }

    private AIAccessSubjectContext authContext(PipelineContext context) {
        if (context.getOrchestrationRequest() != null
            && context.getOrchestrationRequest().trustedExecutionContext()
                != null) {
            return OrchestrationAuthContextResolver.from(
                context.getOrchestrationRequest().trustedExecutionContext()
            );
        }
        return OrchestrationAuthContextResolver.from(
            context.getOrchestrationContext()
        );
    }

    private String fieldNames(Iterable<String> names) {
        List<String> safeNames = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                if (StringUtils.hasText(name)) {
                    safeNames.add(oneLine(name));
                }
            }
        }
        return safeNames.toString();
    }

    private boolean containsIgnoreCase(Set<String> names, String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return false;
        }
        String normalized = candidate.trim();
        return names.stream().anyMatch(
            name -> name.equalsIgnoreCase(normalized)
        );
    }

    private double normalizeConfidence(Double confidence) {
        if (confidence == null) {
            return 0.8d;
        }
        return Math.max(0.0d, Math.min(1.0d, confidence));
    }

    private String oneLine(String value) {
        return value == null
            ? ""
            : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String abbreviate(String value, int maxCharacters) {
        if (value == null || value.length() <= maxCharacters) {
            return value != null ? value : "";
        }
        return value.substring(0, maxCharacters);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContinuationDecision(
        Boolean continuesDraft,
        String action,
        Map<String, Object> providedParams,
        Double confidence,
        String reason
    ) {
        public ContinuationDecision {
            providedParams = providedParams == null
                ? Map.of()
                : Collections.unmodifiableMap(
                    new LinkedHashMap<>(providedParams)
                );
        }
    }

    public record AnalysisOutcome(
        boolean evaluated,
        boolean continued,
        MultiIntentResponse response,
        List<String> suppliedParameterNames,
        int attempts,
        String model,
        String failureType
    ) {
        public AnalysisOutcome {
            suppliedParameterNames = suppliedParameterNames == null
                ? List.of()
                : List.copyOf(suppliedParameterNames);
        }

        public static AnalysisOutcome notEvaluated() {
            return new AnalysisOutcome(
                false, false, null, List.of(), 0, null, null
            );
        }

        public static AnalysisOutcome failed(
            String failureType,
            int attempts
        ) {
            return new AnalysisOutcome(
                true, false, null, List.of(), attempts, null,
                failureType
            );
        }

        public static AnalysisOutcome independent(
            int attempts,
            String model
        ) {
            return new AnalysisOutcome(
                true, false, null, List.of(), attempts, model, null
            );
        }

        public static AnalysisOutcome continued(
            MultiIntentResponse response,
            Set<String> suppliedParameterNames,
            int attempts,
            String model
        ) {
            return new AnalysisOutcome(
                true,
                true,
                response,
                suppliedParameterNames != null
                    ? List.copyOf(suppliedParameterNames)
                    : List.of(),
                attempts,
                model,
                null
            );
        }

        public Map<String, Object> diagnostics() {
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("evaluated", evaluated);
            diagnostics.put("continued", continued);
            diagnostics.put("attempts", attempts);
            diagnostics.put(
                "suppliedParameterNames",
                suppliedParameterNames
            );
            if (StringUtils.hasText(model)) {
                diagnostics.put("model", model);
            }
            if (StringUtils.hasText(failureType)) {
                diagnostics.put("failureType", failureType);
                diagnostics.put(
                    "fallback",
                    "NORMAL_INTENT_EXTRACTION"
                );
            }
            return Collections.unmodifiableMap(diagnostics);
        }
    }
}
