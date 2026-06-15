package ai.fabric.chat.resolver;

import ai.fabric.chat.spi.IntentResolver;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Convenience base class for implementing confirmation-related {@link IntentResolver}s.
 *
 * <p>Intended for both framework-provided resolvers and application-level resolvers.</p>
 */
public abstract class ConfirmationResolverSupport implements IntentResolver {

    static final int DEFAULT_CONFIRMATION_TIMEOUT_MINUTES = 5;

    private final PendingActionStore pendingActionStore;
    private final Duration timeout;

    protected ConfirmationResolverSupport(PendingActionStore pendingActionStore) {
        this(pendingActionStore, Duration.ofMinutes(DEFAULT_CONFIRMATION_TIMEOUT_MINUTES));
    }

    protected ConfirmationResolverSupport(PendingActionStore pendingActionStore, Duration timeout) {
        this.pendingActionStore = Objects.requireNonNull(pendingActionStore, "pendingActionStore");
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(DEFAULT_CONFIRMATION_TIMEOUT_MINUTES);
    }

    protected PendingAction peekPending(PipelineContext context) {
        if (context == null || context.getOrchestrationContext() == null || !context.getOrchestrationContext().hasConversation()) {
            return null;
        }
        return pendingActionStore.peekPendingAction(context.getOrchestrationContext().getConversationId(), context.getIdentifier())
            .orElse(null);
    }

    protected PendingAction popPending(PipelineContext context) {
        if (context == null || context.getOrchestrationContext() == null || !context.getOrchestrationContext().hasConversation()) {
            return null;
        }
        return pendingActionStore.popPendingAction(context.getOrchestrationContext().getConversationId(), context.getIdentifier())
            .orElse(null);
    }

    protected void pushPending(PipelineContext context, PendingAction pendingAction) {
        if (pendingAction == null) {
            return;
        }
        if (context == null || context.getOrchestrationContext() == null || !context.getOrchestrationContext().hasConversation()) {
            return;
        }
        pendingActionStore.pushPendingAction(context.getOrchestrationContext().getConversationId(), context.getIdentifier(), pendingAction);
    }

    protected boolean isExpired(PendingAction pending) {
        if (pending == null) {
            return false;
        }
        Instant createdAt = pending.createdAt();
        if (createdAt == null) {
            return false;
        }
        return createdAt.plus(timeout).isBefore(Instant.now());
    }

    protected boolean hasAnyConfirmationIntent(MultiIntentResponse intentResponse) {
        return intentResponse != null
            && intentResponse.getIntents() != null
            && intentResponse.getIntents().stream().anyMatch(this::isConfirmationIntent);
    }

    protected boolean hasPositiveConfirmation(MultiIntentResponse intentResponse) {
        return intentResponse != null
            && intentResponse.getIntents() != null
            && intentResponse.getIntents().stream().anyMatch(i -> i != null && i.getType() == IntentType.CONFIRMATION_POSITIVE);
    }

    protected boolean hasNegativeConfirmation(MultiIntentResponse intentResponse) {
        return intentResponse != null
            && intentResponse.getIntents() != null
            && intentResponse.getIntents().stream().anyMatch(i -> i != null && i.getType() == IntentType.CONFIRMATION_NEGATIVE);
    }

    protected List<Intent> withoutConfirmationIntents(MultiIntentResponse intentResponse) {
        if (intentResponse == null || intentResponse.getIntents() == null) {
            return List.of();
        }
        return intentResponse.getIntents().stream()
            .filter(Objects::nonNull)
            .filter(intent -> !isConfirmationIntent(intent))
            .collect(Collectors.toList());
    }

    protected boolean isConfirmationIntent(Intent intent) {
        if (intent == null || intent.getType() == null) {
            return false;
        }
        return intent.getType() == IntentType.CONFIRMATION_POSITIVE || intent.getType() == IntentType.CONFIRMATION_NEGATIVE;
    }

    protected PipelineContext markConfirmed(PipelineContext context, String actionName) {
        if (context == null || actionName == null || actionName.isBlank()) {
            return context;
        }
        Set<String> current = context.getConfirmedActions() != null ? context.getConfirmedActions() : Set.of();
        Set<String> updated = current.isEmpty()
            ? Set.of(actionName)
            : java.util.stream.Stream.concat(current.stream(), java.util.stream.Stream.of(actionName)).collect(Collectors.toUnmodifiableSet());

        return context.toBuilder()
            .confirmedActions(updated)
            .build();
    }

    protected PipelineContext markConfirmed(PipelineContext context, PendingAction pending) {
        if (pending == null) {
            return context;
        }
        PipelineContext marked = markConfirmed(context, pending.action());
        Map<String, List<String>> trustedEvidence = pending.trustedEvidenceValuesByKey();
        if (marked == null || trustedEvidence == null || trustedEvidence.isEmpty()) {
            return marked;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(marked.getMetadata() != null ? marked.getMetadata() : Map.of());
        metadata.put(
            PendingAction.TRUSTED_EVIDENCE_METADATA_KEY,
            Collections.unmodifiableMap(new LinkedHashMap<>(trustedEvidence))
        );
        return marked.toBuilder()
            .metadata(Collections.unmodifiableMap(metadata))
            .build();
    }

    protected PendingActionStore store() {
        return pendingActionStore;
    }

    protected Duration timeout() {
        return timeout;
    }

    @Override
    public String toString() {
        return getResolverName();
    }
}
