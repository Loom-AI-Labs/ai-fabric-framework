package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.steps.ConfirmationDecisionSupport.ConfirmationResolutionDecision;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class ConfirmationResolutionSupport {

    private static final String TEMPLATE_FAMILY_CONFIRMATION_RESOLUTION = "intent-extraction/confirmation";
    private static final String TEMPLATE_CONFIRMATION_RESOLUTION_SYSTEM = "system";
    private static final String TEMPLATE_CONFIRMATION_RESOLUTION_USER = "user";

    private final AICoreService aiCoreService;
    private final ObjectProvider<ObjectMapper> objectMapperProvider;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;

    ConfirmationResolutionSupport(AICoreService aiCoreService,
                                  ObjectProvider<ObjectMapper> objectMapperProvider,
                                  PromptTemplateResolver promptTemplateResolver,
                                  PromptRenderer promptRenderer) {
        this.aiCoreService = aiCoreService;
        this.objectMapperProvider = objectMapperProvider;
        this.promptTemplateResolver = promptTemplateResolver;
        this.promptRenderer = promptRenderer;
    }

    ConfirmationResolutionOutcome resolve(String actionName,
                                          String confirmationMessage,
                                          String userMessage,
                                          OrchestrationContext context) {
        String safeAction = StringUtils.hasText(actionName) ? actionName.trim() : "";
        String safeUserMessage = StringUtils.hasText(userMessage) ? userMessage.trim() : "";

        String systemPrompt;
        String userPrompt;
        try {
            systemPrompt = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY_CONFIRMATION_RESOLUTION, TEMPLATE_CONFIRMATION_RESOLUTION_SYSTEM).template(),
                Map.of()
            );
            userPrompt = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY_CONFIRMATION_RESOLUTION, TEMPLATE_CONFIRMATION_RESOLUTION_USER).template(),
                Map.of(
                    "action_name", safeAction,
                    "confirmation_message", StringUtils.hasText(confirmationMessage) ? confirmationMessage.trim() : "",
                    "user_query", safeUserMessage
                )
            );
        } catch (Exception ex) {
            return null;
        }

        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("confirm-" + UUID.randomUUID())
            .entityType("confirmation")
            .generationType("confirmation_resolution")
            .systemPrompt(systemPrompt)
            .prompt(userPrompt)
            .maxTokens(120)
            .temperature(0.0d)
            .authContext(context != null ? OrchestrationAuthContextResolver.from(context) : null)
            .build();

        AIGenerationResponse response;
        try {
            response = aiCoreService.generateContent(request, LlmPurpose.ORCHESTRATION);
        } catch (Exception ex) {
            return new ConfirmationResolutionOutcome(
                ConfirmationResolutionDecision.UNKNOWN,
                0.0d,
                Map.of("used", true, "error", "llm_call_failed")
            );
        }

        String content = response != null ? response.getContent() : null;
        ObjectMapper mapper = objectMapperProvider != null ? objectMapperProvider.getIfAvailable() : null;
        ConfirmationResolutionDecision decision = ConfirmationDecisionSupport.parseConfirmationDecision(content, mapper);
        double confidence = ConfirmationDecisionSupport.parseConfirmationConfidence(content, mapper);

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("used", true);
        debug.put("action", safeAction);
        debug.put("decision", decision != null ? decision.name() : "UNKNOWN");
        debug.put("confidence", confidence);
        debug.put("model", response != null ? response.getModel() : null);
        return new ConfirmationResolutionOutcome(
            decision != null ? decision : ConfirmationResolutionDecision.UNKNOWN,
            confidence,
            Collections.unmodifiableMap(debug)
        );
    }

    record ConfirmationResolutionOutcome(
        ConfirmationResolutionDecision decision,
        double confidence,
        Map<String, Object> debugMetadata
    ) {
    }
}
