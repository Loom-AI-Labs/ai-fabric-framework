package ai.fabric.chat.pipeline;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.exception.ChatSessionAccessDeniedException;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.actiondraft.ActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import ai.fabric.chat.domain.ChatSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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

    private static final String SESSION_META_KEY_LAST_RESOLVED_TARGETS = "lastResolvedTargets";
    private static final String SESSION_META_KEY_LAST_RESOLVED_TARGETS_TURN_INDEX = "lastResolvedTargetsTurnIndex";

    private final ChatSessionService chatSessionService;
    private final ChatSessionProperties properties;
    private final PendingActionStore pendingActionStore;
    private final ActionDraftStore actionDraftStore;

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
            PipelineContext seeded = seedResolvedTargetsFromSession(context, conversationId, ownerId);
            if (seeded != null) {
                context = seeded;
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

    private PipelineContext seedResolvedTargetsFromSession(PipelineContext context, String conversationId, String ownerId) {
        if (context == null) {
            return null;
        }

        // Only reuse targets when the current request does not include new attachments.
        if (context.getOrchestrationContext() == null
            || (context.getOrchestrationContext().getAttachmentsNormalized() != null
                && !context.getOrchestrationContext().getAttachmentsNormalized().isEmpty())) {
            return context;
        }

        if (context.getResolvedTargets() != null && !context.getResolvedTargets().isEmpty()) {
            return context;
        }

        int reuseWindow = properties != null ? properties.getPinnedTargetReuseWindowTurns() : 0;
        if (reuseWindow <= 0) {
            return context;
        }

        ChatSession session;
        try {
            session = chatSessionService.getSession(conversationId, ownerId);
        } catch (Exception ex) {
            return context;
        }

        Map<String, Object> metadata = session != null ? session.getSessionMetadata() : null;
        if (metadata == null || metadata.isEmpty()) {
            return context;
        }

        int currentTurnIndex = session.getTurns() != null ? session.getTurns().size() : 0;
        int lastTurnIndex = coerceInt(metadata.get(SESSION_META_KEY_LAST_RESOLVED_TARGETS_TURN_INDEX), -1);
        if (lastTurnIndex >= 0 && (currentTurnIndex - lastTurnIndex) > reuseWindow) {
            return context;
        }

        Object rawTargets = metadata.get(SESSION_META_KEY_LAST_RESOLVED_TARGETS);
        if (!(rawTargets instanceof List<?> list) || list.isEmpty()) {
            return context;
        }

        int maxTargets = 8;
        if (properties != null
            && properties.getPinnedTargetPersistence() != null
            && properties.getPinnedTargetPersistence().getMaxTargets() > 0) {
            maxTargets = properties.getPinnedTargetPersistence().getMaxTargets();
        }

        List<ResolvedTarget> resolved = new ArrayList<>();
        for (Object item : list) {
            if (resolved.size() >= maxTargets) {
                break;
            }
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }

            String id = coerceString(map.get("id"));
            String vectorSpace = coerceString(map.get("vectorSpace"));
            String contentText = coerceString(map.get("contentText"));
            boolean contentTextTruncated = Boolean.TRUE.equals(map.get("contentTextTruncated"));
            ResolvedTargetSource originSource = ResolvedTargetSource.SESSION_METADATA;
            String originSourceText = coerceString(map.get("originSource"));
            if (StringUtils.hasText(originSourceText)) {
                try {
                    originSource = ResolvedTargetSource.valueOf(originSourceText.trim());
                } catch (IllegalArgumentException ignored) {
                }
            }

            Map<String, String> meta = Map.of();
            Object rawMeta = map.get("metadata");
            if (rawMeta instanceof Map<?, ?> rawMap && !rawMap.isEmpty()) {
                LinkedHashMap<String, String> safe = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    String key = String.valueOf(entry.getKey());
                    String value = String.valueOf(entry.getValue());
                    if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
                        safe.put(key, value);
                    }
                }
                if (!safe.isEmpty()) {
                    meta = Collections.unmodifiableMap(safe);
                }
            }

            boolean hasId = StringUtils.hasText(id);
            boolean hasContentText = StringUtils.hasText(contentText);
            boolean hasMetadata = meta != null && !meta.isEmpty();
            if (!hasId && !hasContentText && !hasMetadata) {
                continue;
            }

            resolved.add(ResolvedTarget.builder()
                .id(hasId ? id.trim() : null)
                .vectorSpace(StringUtils.hasText(vectorSpace) ? vectorSpace.trim() : null)
                .contentText(StringUtils.hasText(contentText) ? contentText.trim() : null)
                .contentTextTruncated(contentTextTruncated)
                .metadata(meta)
                .source(originSource)
                .build());
        }

        if (resolved.isEmpty()) {
            return context;
        }

        return context.toBuilder()
            .resolvedTargets(Collections.unmodifiableList(resolved))
            .pinnedTargetsContext(buildPreviouslyPinnedTargetsContext(resolved))
            .build();
    }

    private String buildPreviouslyPinnedTargetsContext(List<ResolvedTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        return ai.fabric.intent.orchestration.targets.ResolvedTargetsContextRenderer.renderGrouped(
            "PINNED TARGETS (previously pinned; not current UI selection):",
            "target",
            targets,
            List.of(ResolvedTargetSource.ACTION_RESULT_ITEMS, ResolvedTargetSource.REQUEST_ATTACHMENTS),
            Map.of(
                ResolvedTargetSource.ACTION_RESULT_ITEMS, "Write Result (latest):",
                ResolvedTargetSource.REQUEST_ATTACHMENTS, "User Selection (attachments):"
            ),
            "Other:"
        );
    }

    private int coerceInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private String coerceString(Object value) {
        if (value instanceof String str) {
            return str;
        }
        return value != null ? value.toString() : null;
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
