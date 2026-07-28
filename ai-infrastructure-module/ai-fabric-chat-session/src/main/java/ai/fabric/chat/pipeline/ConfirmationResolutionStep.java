package ai.fabric.chat.pipeline;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.chat.spi.IntentResolver;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * Pipeline step that resolves confirmation-related intents against pending actions.
 *
 * <p><strong>Order:</strong> 57 (after VectorSpaceResolutionStep (55), before IntentHandlingStep (60))</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ConfirmationResolutionStep implements PipelineStep {

    private static final String STEP_NAME = "ConfirmationResolution";
    private static final int STEP_ORDER = 57;

    private final ChatSessionService chatSessionService;
    private final ChatSessionProperties properties;
    private final List<IntentResolver> resolvers;

    @Override
    public String getStepName() {
        return STEP_NAME;
    }

    @Override
    public int getOrder() {
        return STEP_ORDER;
    }

    @Override
    public PipelineContext process(PipelineContext context) {
        if (context == null || context.isShouldTerminate()) {
            return context;
        }
        if (properties == null || !properties.isEnabled()) {
            return context;
        }
        if (context.getOrchestrationContext() == null || !context.getOrchestrationContext().hasConversation()) {
            return context;
        }

        MultiIntentResponse intentResponse = context.getIntentResponse();
        if (intentResponse == null || intentResponse.getIntents() == null || intentResponse.getIntents().isEmpty()) {
            return context;
        }

        String conversationId = context.getOrchestrationContext().getConversationId();
        String ownerId = context.getConversationOwnerIdentifier();
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return context;
        }

        Map<String, Object> sessionMetadata = Map.of();
        try {
            ChatSession session = chatSessionService.getSession(conversationId, ownerId);
            if (session != null && session.getSessionMetadata() != null) {
                sessionMetadata = session.getSessionMetadata();
            }
        } catch (Exception ex) {
            log.debug("Failed to load session metadata for conversation {}: {}", conversationId, ex.getMessage());
            return context;
        }

        if (resolvers == null || resolvers.isEmpty()) {
            return context;
        }

        List<IntentResolver> sorted = resolvers.stream()
            .sorted(Comparator.comparingInt(IntentResolver::getPriority))
            .toList();

        for (IntentResolver resolver : sorted) {
            try {
                if (resolver != null && resolver.canResolve(intentResponse, sessionMetadata, context)) {
                    return resolver.resolve(intentResponse, sessionMetadata, context);
                }
            } catch (Exception ex) {
                log.warn("Resolver {} failed for conversation {}: {}", resolver != null ? resolver.getResolverName() : "unknown", conversationId, ex.getMessage());
            }
        }

        return context;
    }
}
