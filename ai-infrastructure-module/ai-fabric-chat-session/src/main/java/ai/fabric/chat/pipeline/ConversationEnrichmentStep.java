package ai.fabric.chat.pipeline;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.exception.ChatSessionAccessDeniedException;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline step that enriches the processed query with conversation history.
 *
 * <p><strong>Order:</strong> 25 (after access control, before PII detection)</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ConversationEnrichmentStep implements PipelineStep {

    private static final String STEP_NAME = "ConversationEnrichment";
    private static final int STEP_ORDER = 25;

    private static final String METADATA_KEY_CHAT = "chat";
    private static final String ERROR_CODE_ACCESS_DENIED = "ACCESS_DENIED";
    private static final String QUERY_PERSISTENCE_MODE_NEVER_PERSIST = "NEVER_PERSIST";

    private final ChatSessionService chatSessionService;
    private final ChatSessionProperties properties;

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
        if (isConversationPersistenceDisabled(context)) {
            return context;
        }
        if (context.getOrchestrationContext() == null || !context.getOrchestrationContext().hasConversation()) {
            return context;
        }
        if (properties == null || !properties.isEnabled()) {
            return context;
        }

        String conversationId = context.getOrchestrationContext().getConversationId();
        String ownerId = context.getConversationOwnerIdentifier();
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return context;
        }

        try {
            ApprovedConversationSnapshot snapshot = context
                .getOrchestrationContext()
                .getApprovedConversationSnapshot();
            if (snapshot != null) {
                return enrichFromApprovedSnapshot(
                    context,
                    conversationId,
                    ownerId,
                    snapshot
                );
            }

            List<AIChatMessage> historyMessages = chatSessionService.getConversationMessages(conversationId, ownerId);
            if (historyMessages == null) {
                historyMessages = List.of();
            }

            int historyChars = historyMessages.stream()
                .map(AIChatMessage::getContent)
                .filter(StringUtils::hasText)
                .mapToInt(String::length)
                .sum();

            Map<String, Object> chatMeta = new LinkedHashMap<>();
            chatMeta.put("conversationId", conversationId);
            chatMeta.put("historyMessagesCount", historyMessages.size());
            chatMeta.put("historyChars", historyChars);
            chatMeta.put("memoryStrategy", properties.getMemoryStrategy() != null ? properties.getMemoryStrategy().name() : null);
            chatMeta.put("windowSize", properties.getWindowSize());

            return context.toBuilder()
                .historyMessages(historyMessages)
                .metadata(mergeMetadata(context.getMetadata(), Map.of(METADATA_KEY_CHAT, chatMeta)))
                .build();
        } catch (ChatSessionAccessDeniedException ex) {
            OrchestrationResult denied = OrchestrationResult.builder()
                .type(ai.fabric.intent.orchestration.OrchestrationResultType.ERROR)
                .success(false)
                .errorCode(ERROR_CODE_ACCESS_DENIED)
                .message("Access denied to conversation")
                .build();
            return context.terminate(denied);
        } catch (Exception ex) {
            log.warn("Failed to enrich conversation {}: {}", conversationId, ex.getMessage());
            return context;
        }
    }

    private PipelineContext enrichFromApprovedSnapshot(
        PipelineContext context,
        String conversationId,
        String ownerId,
        ApprovedConversationSnapshot snapshot
    ) {
        if (!conversationId.equals(snapshot.conversationId())
            || !ownerId.equals(snapshot.ownerId())) {
            throw new ChatSessionAccessDeniedException(
                "Approved conversation snapshot does not match the bound conversation"
            );
        }

        List<AIChatMessage> historyMessages = snapshot.historyMessages();
        int historyChars = historyMessages.stream()
            .map(AIChatMessage::getContent)
            .filter(StringUtils::hasText)
            .mapToInt(String::length)
            .sum();

        Map<String, Object> chatMeta = new LinkedHashMap<>();
        chatMeta.put("conversationId", conversationId);
        chatMeta.put("historyMessagesCount", historyMessages.size());
        chatMeta.put("historyChars", historyChars);
        chatMeta.put("memoryStrategy", "APPROVED_SNAPSHOT");
        chatMeta.put("snapshotRevision", snapshot.revision());
        chatMeta.put("sourceTurnCount", snapshot.sourceTurnCount());

        return context.toBuilder()
            .historyMessages(historyMessages)
            .metadata(
                mergeMetadata(
                    context.getMetadata(),
                    Map.of(METADATA_KEY_CHAT, chatMeta)
                )
            )
            .build();
    }

    private boolean isConversationPersistenceDisabled(PipelineContext context) {
        if (context != null && context.getOrchestrationRequest() != null
            && context.getOrchestrationRequest().conversationPersistencePolicy()
                == ConversationPersistencePolicy.NEVER) {
            return true;
        }
        Object mode = null;
        if (context != null && context.getOrchestrationContext() != null
            && context.getOrchestrationContext().getMetadata() != null) {
            mode = context.getOrchestrationContext().getMetadata()
                .get(OrchestrationContextMetadataKeys.QUERY_PERSISTENCE_MODE);
        }
        if (mode == null && context != null && context.getMetadata() != null) {
            mode = context.getMetadata().get(OrchestrationContextMetadataKeys.QUERY_PERSISTENCE_MODE);
        }
        return mode != null && QUERY_PERSISTENCE_MODE_NEVER_PERSIST.equalsIgnoreCase(mode.toString().trim());
    }

    private Map<String, Object> mergeMetadata(Map<String, Object> base, Map<String, Object> additions) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base != null && !base.isEmpty()) {
            merged.putAll(base);
        }
        if (additions != null && !additions.isEmpty()) {
            merged.putAll(additions);
        }
        return Collections.unmodifiableMap(merged);
    }
}
