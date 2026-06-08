package com.ai.infrastructure.chat.pipeline;

import com.ai.infrastructure.chat.config.ChatSessionProperties;
import com.ai.infrastructure.chat.domain.ChatSession;
import com.ai.infrastructure.chat.domain.ChatTurn;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import com.ai.infrastructure.intent.orchestration.targets.ResolvedTarget;
import com.ai.infrastructure.intent.orchestration.targets.ResolvedTargetSource;
import com.ai.infrastructure.chat.service.ChatSessionService;
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

    private static final int WORKING_SET_MAX_TARGETS = 4;
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
        String ownerId = context.getIdentifier();
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

        List<ResolvedTarget> targets = session != null ? extractWorkingSetTargets(session.getTurns()) : List.of();
        if (targets.isEmpty()) {
            return context.withMetadata(METADATA_KEY_TARGET_SEEDING, Map.of("seeded", false));
        }

        PipelineContext updated = context.toBuilder()
            .resolvedTargets(targets)
            .build();

        return updated.withMetadata(METADATA_KEY_TARGET_SEEDING, Map.of(
            "seeded", true,
            "count", targets.size()
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

                Map<String, String> metadata = Map.of();
                Object meta = refMap.get("metadata");
                if (meta instanceof Map<?, ?> metaMap && !metaMap.isEmpty()) {
                    Map<String, String> normalized = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> metaEntry : metaMap.entrySet()) {
                        if (normalized.size() >= WORKING_SET_MAX_METADATA_FIELDS) {
                            break;
                        }
                        if (metaEntry == null || metaEntry.getKey() == null || metaEntry.getValue() == null) {
                            continue;
                        }
                        String key = String.valueOf(metaEntry.getKey());
                        String value = String.valueOf(metaEntry.getValue());
                        if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
                            continue;
                        }
                        normalized.put(key, value);
                    }
                    if (!normalized.isEmpty()) {
                        metadata = Collections.unmodifiableMap(normalized);
                    }
                }

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
}
