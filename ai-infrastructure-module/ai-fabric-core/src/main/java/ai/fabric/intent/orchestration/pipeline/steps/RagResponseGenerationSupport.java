package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.ResponseGenerationProfile;
import ai.fabric.dto.TransientInputPolicy;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ai.fabric.intent.orchestration.pipeline.steps.ManagedGenerationPromptSupport.extractPromptPreview;
import static ai.fabric.intent.orchestration.pipeline.steps.ManagedGenerationPromptSupport.hasManagedGenerationPromptOverride;
import static ai.fabric.intent.orchestration.pipeline.steps.ManagedGenerationPromptSupport.renderManagedGenerationPrompt;

/**
 * Handles RAG answer generation prompts, budget selection, and generation trace metadata.
 */
@Slf4j
final class RagResponseGenerationSupport {

    private static final String METADATA_KEY_RESPONSE_GENERATION_PROCESSING_TIME_MS = "responseGenerationProcessingTimeMs";
    private static final String METADATA_KEY_RESPONSE_GENERATION_PROVIDER_PROCESSING_TIME_MS = "responseGenerationProviderProcessingTimeMs";
    private static final String METADATA_KEY_RESPONSE_GENERATION_MODEL = "responseGenerationModel";
    private static final String METADATA_KEY_RESPONSE_GENERATION_PATH = "responseGenerationPath";

    private static final String TEMPLATE_FAMILY_RAG_GENERATION = "rag/generation";
    private static final String TEMPLATE_RAG_ANSWER = "answer";
    private static final String TEMPLATE_RAG_ANSWER_MANAGED = "answer-managed";
    private static final String TEMPLATE_RAG_NO_CONTEXT = "no-context";
    private static final String TEMPLATE_RAG_NO_CONTEXT_MANAGED = "no-context-managed";

    private static final String PLACEHOLDER_QUERY = "query";
    private static final String PLACEHOLDER_CONTEXT = "context";

    private static final int DEFAULT_CONCISE_RAG_ANSWER_MAX_TOKENS = 400;
    private static final int DEFAULT_DEEP_RAG_ANSWER_MAX_TOKENS = 1_200;
    private static final String RAG_NO_INFO_MESSAGE_PREFIX = "I don't have enough information to answer your question: ";

    private final AICoreService aiCoreService;
    private final AIServiceConfig aiServiceConfig;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;

    RagResponseGenerationSupport(AICoreService aiCoreService,
                                 AIServiceConfig aiServiceConfig,
                                 PromptTemplateResolver promptTemplateResolver,
                                 PromptRenderer promptRenderer) {
        this.aiCoreService = aiCoreService;
        this.aiServiceConfig = aiServiceConfig;
        this.promptTemplateResolver = promptTemplateResolver;
        this.promptRenderer = promptRenderer;
    }

    ResponseGenerationTrace generateRagAnswer(Intent intent,
                                              String query,
                                              String context,
                                              PipelineContext pipelineContext) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        String safeQuery = query.trim();
        Map<String, String> promptPreview = extractPromptPreview(pipelineContext);
        ResponseGenerationProfile responseProfile = resolveResponseGenerationProfile(intent);
        OrchestrationPolicy policy = pipelineContext != null ? pipelineContext.getOrchestrationPolicy() : null;
        Integer maxTokens = resolveResponseGenerationMaxTokens(responseProfile, policy);

        if (!StringUtils.hasText(context) || RagContextSupport.NO_CONTEXT_MESSAGE.equals(context)) {
            if (aiServiceConfig != null
                && aiServiceConfig.getFeatures() != null
                && Boolean.TRUE.equals(aiServiceConfig.getFeatures().getEnableGeneration())) {
                try {
                    String prompt = buildRagNoContextPrompt(safeQuery, promptPreview);
                    ResponseGenerationTrace response = generatePromptResponse(
                        prompt,
                        "rag",
                        "no_context",
                        LlmPurpose.GENERATION,
                        responseGenerationPath(responseProfile, true),
                        maxTokens,
                        pipelineContext
                    );
                    if (response != null && StringUtils.hasText(response.content())) {
                        return response;
                    }
                } catch (Exception ex) {
                    log.warn("No-context generation failed; falling back to static response: {}", ex.getMessage());
                }
            }
            return new ResponseGenerationTrace(
                RAG_NO_INFO_MESSAGE_PREFIX + safeQuery,
                null,
                null,
                null,
                null
            );
        }

        String safeContext = context != null ? context : "";
        String prompt = buildRagAnswerPrompt(safeQuery, safeContext, promptPreview);
        return generatePromptResponse(
            prompt,
            "rag",
            "answer",
            LlmPurpose.GENERATION,
            responseGenerationPath(responseProfile, false),
            maxTokens,
            pipelineContext
        );
    }

    Map<String, Object> responseGenerationMetadata(ResponseGenerationTrace generationTrace) {
        if (generationTrace == null) {
            return Map.of();
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (generationTrace.processingTimeMs() != null) {
            metadata.put(METADATA_KEY_RESPONSE_GENERATION_PROCESSING_TIME_MS, generationTrace.processingTimeMs());
        }
        if (generationTrace.providerProcessingTimeMs() != null) {
            metadata.put(
                METADATA_KEY_RESPONSE_GENERATION_PROVIDER_PROCESSING_TIME_MS,
                generationTrace.providerProcessingTimeMs()
            );
        }
        if (StringUtils.hasText(generationTrace.model())) {
            metadata.put(METADATA_KEY_RESPONSE_GENERATION_MODEL, generationTrace.model());
        }
        if (StringUtils.hasText(generationTrace.path())) {
            metadata.put(METADATA_KEY_RESPONSE_GENERATION_PATH, generationTrace.path());
        }
        return metadata.isEmpty() ? Map.of() : Collections.unmodifiableMap(metadata);
    }

    record ResponseGenerationTrace(
        String content,
        Long processingTimeMs,
        Long providerProcessingTimeMs,
        String model,
        String path
    ) {}

    ResponseGenerationProfile resolveResponseGenerationProfile(Intent intent) {
        if (intent == null || !intent.requiresGenerationOrDefault(false)) {
            return ResponseGenerationProfile.STANDARD;
        }
        return intent.responseProfileOrDefault(ResponseGenerationProfile.STANDARD);
    }

    Integer resolveResponseGenerationMaxTokens(ResponseGenerationProfile profile,
                                               OrchestrationPolicy policy) {
        OrchestrationPolicy.ResponseGenerationBudgets budgets = policy != null
            ? policy.responseGenerationBudgets()
            : OrchestrationPolicy.ResponseGenerationBudgets.defaults();
        ResponseGenerationProfile safeProfile = profile != null ? profile : ResponseGenerationProfile.STANDARD;
        return switch (safeProfile) {
            case CONCISE -> budgets.conciseMaxTokens() != null
                ? budgets.conciseMaxTokens()
                : DEFAULT_CONCISE_RAG_ANSWER_MAX_TOKENS;
            case DEEP -> budgets.deepMaxTokens() != null
                ? budgets.deepMaxTokens()
                : DEFAULT_DEEP_RAG_ANSWER_MAX_TOKENS;
            case STANDARD -> budgets.standardMaxTokens();
        };
    }

    String responseGenerationPath(ResponseGenerationProfile profile, boolean noContext) {
        ResponseGenerationProfile safeProfile = profile != null ? profile : ResponseGenerationProfile.STANDARD;
        if (noContext) {
            return switch (safeProfile) {
                case CONCISE -> "RAG_NO_CONTEXT_CONCISE";
                case DEEP -> "RAG_NO_CONTEXT_DEEP";
                case STANDARD -> "RAG_NO_CONTEXT";
            };
        }
        return switch (safeProfile) {
            case CONCISE -> "RAG_ANSWER_CONCISE";
            case DEEP -> "RAG_ANSWER_DEEP";
            case STANDARD -> "RAG_ANSWER";
        };
    }

    ResponseGenerationTrace generatePromptResponse(String prompt,
                                                   String entityType,
                                                   String generationType,
                                                   LlmPurpose purpose,
                                                   String path,
                                                   Integer maxTokens,
                                                   PipelineContext pipelineContext) {
        long startNanos = System.nanoTime();
        AIGenerationResponse response;
        List<AIGenerationInputPart> transientInputParts = transientInputParts(pipelineContext);
        if ((maxTokens != null && maxTokens > 0) || !transientInputParts.isEmpty()) {
            response = aiCoreService.generateContent(
                AIGenerationRequest.builder()
                    .entityId("adhoc-" + UUID.randomUUID())
                    .entityType(StringUtils.hasText(entityType) ? entityType : "adhoc")
                    .generationType(StringUtils.hasText(generationType) ? generationType : "text")
                    .prompt(prompt)
                    .maxTokens(maxTokens)
                    .inputParts(transientInputParts)
                    .transientInputPolicy(transientInputParts.isEmpty()
                        ? null
                        : TransientInputPolicy.providerFileUrlDefaults())
                    .authContext(pipelineContext != null && pipelineContext.getOrchestrationContext() != null
                        ? OrchestrationAuthContextResolver.from(pipelineContext.getOrchestrationContext())
                        : null)
                    .build(),
                purpose
            );
            if (response == null || !StringUtils.hasText(response.getContent())) {
                response = aiCoreService.generateTextResponse(prompt, purpose);
            }
        } else {
            response = aiCoreService.generateTextResponse(prompt, purpose);
        }
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        return new ResponseGenerationTrace(
            response != null ? response.getContent() : null,
            elapsedMs,
            response != null ? response.getProcessingTimeMs() : null,
            response != null ? response.getModel() : null,
            path
        );
    }

    List<AIGenerationInputPart> transientInputParts(PipelineContext pipelineContext) {
        if (pipelineContext == null || pipelineContext.getOrchestrationContext() == null
            || pipelineContext.getOrchestrationContext().getTransientInputParts() == null
            || pipelineContext.getOrchestrationContext().getTransientInputParts().isEmpty()) {
            return List.of();
        }
        return List.copyOf(pipelineContext.getOrchestrationContext().getTransientInputParts());
    }

    String buildRagNoContextPrompt(String query, Map<String, String> promptOverlay) {
        if (hasManagedGenerationPromptOverride(promptOverlay)) {
            return renderManagedGenerationPrompt(
                promptTemplateResolver,
                promptRenderer,
                TEMPLATE_FAMILY_RAG_GENERATION,
                TEMPLATE_RAG_NO_CONTEXT_MANAGED,
                Map.of(PLACEHOLDER_QUERY, query),
                promptOverlay
            );
        }
        return promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY_RAG_GENERATION, TEMPLATE_RAG_NO_CONTEXT).template(),
            Map.of(PLACEHOLDER_QUERY, query)
        );
    }

    private String buildRagAnswerPrompt(String query, String context, Map<String, String> promptOverlay) {
        if (hasManagedGenerationPromptOverride(promptOverlay)) {
            return renderManagedGenerationPrompt(
                promptTemplateResolver,
                promptRenderer,
                TEMPLATE_FAMILY_RAG_GENERATION,
                TEMPLATE_RAG_ANSWER_MANAGED,
                Map.of(
                    PLACEHOLDER_QUERY, query,
                    PLACEHOLDER_CONTEXT, context
                ),
                promptOverlay
            );
        }
        return promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY_RAG_GENERATION, TEMPLATE_RAG_ANSWER).template(),
            Map.of(
                PLACEHOLDER_QUERY, query,
                PLACEHOLDER_CONTEXT, context
            )
        );
    }
}
