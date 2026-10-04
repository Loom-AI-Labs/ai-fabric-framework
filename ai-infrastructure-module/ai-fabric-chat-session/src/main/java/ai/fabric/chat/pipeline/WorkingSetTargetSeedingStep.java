package ai.fabric.chat.pipeline;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.domain.ChatTurn;
import ai.fabric.dto.Intent;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import ai.fabric.chat.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds resolved targets from the conversation working set when the intent extraction layer indicates a
 * follow-up needs target resolution.
 *
 * <p><strong>Order:</strong> 51 (after IntentExtractionStep (50), before TargetResolutionStep (52))</p>
 */
@Slf4j
@RequiredArgsConstructor
public class WorkingSetTargetSeedingStep implements PipelineStep {

    private static final String STEP_NAME = "WorkingSetTargetSeeding";
    private static final int STEP_ORDER = 51;

    private static final String METADATA_KEY_TARGET_SEEDING = "workingSetTargetSeeding";
    private static final String SESSION_META_KEY_LAST_RESOLVED_TARGETS = "lastResolvedTargets";
    private static final String SESSION_META_KEY_LAST_RESOLVED_TARGETS_TURN_INDEX = "lastResolvedTargetsTurnIndex";

    private static final int WORKING_SET_MAX_TARGETS = 4;
    private static final int PINNED_TARGET_MAX_TARGETS = 8;
    private static final int WORKING_SET_MAX_METADATA_FIELDS = 8;

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
        if (properties == null || !properties.isEnabled()) {
            return context;
        }
        if (context.getResolvedTargets() != null && !context.getResolvedTargets().isEmpty()) {
            return context;
        }

        MultiIntentResponse response = context.getIntentResponse();
        if (response == null || response.getIntents() == null || response.getIntents().isEmpty()) {
            return context;
        }
        if (!anyIntentRequiresTargetResolution(response.getIntents())) {
            return context;
        }

        OrchestrationContext orchContext = context.getOrchestrationContext();
        if (orchContext == null || !orchContext.hasConversation()) {
            return context;
        }
        if (orchContext.getAttachmentsNormalized() != null && !orchContext.getAttachmentsNormalized().isEmpty()) {
            // Request attachments are authoritative; do not seed targets from prior working set.
            return context;
        }

        String conversationId = orchContext.getConversationId();
        String ownerId = context.getConversationOwnerIdentifier();
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return context;
        }

        ChatSession session;
        try {
            session = chatSessionService.getSession(conversationId, ownerId);
        } catch (Exception ex) {
            log.debug("Working-set target seeding skipped: failed to load session {}: {}", conversationId, ex.getMessage());
            return context;
        }

        TargetSeed seed = session != null ? extractPersistedPinnedTargets(session) : TargetSeed.empty();
        if (seed.targets().isEmpty() && session != null) {
            seed = new TargetSeed(extractWorkingSetTargets(session.getTurns()), "WORKING_SET");
        }
        List<ResolvedTarget> targets = seed.targets();
        if (targets.isEmpty()) {
            return context.withMetadata(METADATA_KEY_TARGET_SEEDING, Map.of("seeded", false));
        }

        PipelineContext updated = context.toBuilder()
            .resolvedTargets(targets)
            .pinnedTargetsContext(buildTargetContext(targets))
            .build();

        return updated.withMetadata(METADATA_KEY_TARGET_SEEDING, Map.of(
            "seeded", true,
            "count", targets.size(),
            "source", seed.source()
        ));
    }

    private boolean anyIntentRequiresTargetResolution(List<Intent> intents) {
        if (intents == null || intents.isEmpty()) {
            return false;
        }
        for (Intent intent : intents) {
            if (intent != null && Boolean.TRUE.equals(intent.getRequiresTargetResolution())) {
                return true;
            }
        }
        return false;
    }

    private TargetSeed extractPersistedPinnedTargets(ChatSession session) {
        if (session == null || session.getSessionMetadata() == null || session.getSessionMetadata().isEmpty()) {
            return TargetSeed.empty();
        }

        int reuseWindow = properties.getPinnedTargetReuseWindowTurns();
        if (reuseWindow <= 0) {
            return TargetSeed.empty();
        }

        Map<String, Object> sessionMetadata = session.getSessionMetadata();
        int currentTurnIndex = session.getTurns() != null ? session.getTurns().size() : 0;
        int lastTurnIndex = coerceInt(sessionMetadata.get(SESSION_META_KEY_LAST_RESOLVED_TARGETS_TURN_INDEX), -1);
        if (lastTurnIndex < 0
            || lastTurnIndex > currentTurnIndex
            || (currentTurnIndex - lastTurnIndex) > reuseWindow) {
            return TargetSeed.empty();
        }

        Object rawTargets = sessionMetadata.get(SESSION_META_KEY_LAST_RESOLVED_TARGETS);
        if (!(rawTargets instanceof List<?> storedTargets) || storedTargets.isEmpty()) {
            return TargetSeed.empty();
        }

        int maxTargets = PINNED_TARGET_MAX_TARGETS;
        if (properties.getPinnedTargetPersistence() != null
            && properties.getPinnedTargetPersistence().getMaxTargets() > 0) {
            maxTargets = properties.getPinnedTargetPersistence().getMaxTargets();
        }

        List<ResolvedTarget> targets = new ArrayList<>();
        for (Object storedTarget : storedTargets) {
            if (targets.size() >= maxTargets) {
                break;
            }
            if (!(storedTarget instanceof Map<?, ?> map)) {
                continue;
            }

            String id = coerceString(map.get("id"));
            String vectorSpace = coerceString(map.get("vectorSpace"));
            String contentText = coerceString(map.get("contentText"));
            boolean contentTextTruncated = Boolean.TRUE.equals(map.get("contentTextTruncated"));
            Map<String, String> metadata = safeMetadata(map.get("metadata"));
            if (!StringUtils.hasText(id) && !StringUtils.hasText(contentText) && metadata.isEmpty()) {
                continue;
            }

            targets.add(ResolvedTarget.builder()
                .id(StringUtils.hasText(id) ? id.trim() : null)
                .vectorSpace(StringUtils.hasText(vectorSpace) ? vectorSpace.trim() : null)
                .contentText(StringUtils.hasText(contentText) ? contentText.trim() : null)
                .contentTextTruncated(contentTextTruncated)
                .metadata(metadata)
                .source(coerceTargetSource(map.get("originSource")))
                .build());
        }

        return targets.isEmpty()
            ? TargetSeed.empty()
            : new TargetSeed(Collections.unmodifiableList(targets), "PINNED_TARGETS");
    }

    private List<ResolvedTarget> extractWorkingSetTargets(List<ChatTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }

        for (int i = turns.size() - 1; i >= 0; i--) {
            ChatTurn turn = turns.get(i);
            if (turn == null || turn.getTurnMetadata() == null || turn.getTurnMetadata().isEmpty()) {
                continue;
            }

            Object workingSet = turn.getTurnMetadata().get("_workingSet");
            if (!(workingSet instanceof Map<?, ?> wsMap) || wsMap.isEmpty()) {
                continue;
            }

            Object refsValue = wsMap.get("topDocumentRefs");
            if (!(refsValue instanceof List<?> refs) || refs.isEmpty()) {
                continue;
            }

            List<ResolvedTarget> out = new ArrayList<>();
            for (Object entry : refs) {
                if (out.size() >= WORKING_SET_MAX_TARGETS) {
                    break;
                }
                if (!(entry instanceof Map<?, ?> refMap)) {
                    continue;
                }
                Object id = refMap.get("id");
                if (!(id instanceof String idText) || !StringUtils.hasText(idText)) {
                    continue;
                }
                Object vs = refMap.get("vectorSpace");
                if (!(vs instanceof String vsText) || !StringUtils.hasText(vsText)) {
                    continue;
                }

                Map<String, String> metadata = safeMetadata(refMap.get("metadata"));

                out.add(ResolvedTarget.builder()
                    .id(idText.trim())
                    .vectorSpace(vsText.trim())
                    .metadata(metadata)
                    .source(ResolvedTargetSource.WORKING_SET)
                    .build());
            }

            return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
        }

        return List.of();
    }

    private Map<String, String> safeMetadata(Object value) {
        if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (normalized.size() >= WORKING_SET_MAX_METADATA_FIELDS) {
                break;
            }
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            String item = String.valueOf(entry.getValue());
            if (StringUtils.hasText(key) && StringUtils.hasText(item)) {
                normalized.put(key.trim(), item.trim());
            }
        }
        return normalized.isEmpty() ? Map.of() : Collections.unmodifiableMap(normalized);
    }

    private ResolvedTargetSource coerceTargetSource(Object value) {
        String source = coerceString(value);
        if (StringUtils.hasText(source)) {
            try {
                return ResolvedTargetSource.valueOf(source.trim());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return ResolvedTargetSource.SESSION_METADATA;
    }

    private String buildTargetContext(List<ResolvedTarget> targets) {
        return ai.fabric.intent.orchestration.targets.ResolvedTargetsContextRenderer.renderGrouped(
            "PINNED TARGETS (previously pinned; selected for this target-dependent turn):",
            "target",
            targets,
            List.of(ResolvedTargetSource.ACTION_RESULT_ITEMS, ResolvedTargetSource.REQUEST_ATTACHMENTS),
            Map.of(
                ResolvedTargetSource.ACTION_RESULT_ITEMS, "Action result targets:",
                ResolvedTargetSource.REQUEST_ATTACHMENTS, "User-selected targets:"
            ),
            "Conversation working set:"
        );
    }

    private int coerceInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = coerceString(value);
        if (StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private String coerceString(Object value) {
        return value instanceof String text ? text : value != null ? value.toString() : null;
    }

    private record TargetSeed(List<ResolvedTarget> targets, String source) {
        private static TargetSeed empty() {
            return new TargetSeed(List.of(), "NONE");
        }
    }
}
