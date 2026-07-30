package ai.fabric.execution.gateway;

import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistConversationBinding;
import ai.fabric.execution.specialist.manifest.SpecialistInteractionCapability;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Owns the single process-local lease and frozen snapshot for interactive
 * execution paths.
 */
public final class SharedInteractiveTurnCoordinator {

    private final SpecialistRegistry specialistRegistry;
    private final AIExecutionConversationSnapshotProvider snapshotProvider;
    private final AIExecutionConversationSnapshotRegistry snapshotRegistry;
    private final CanonicalJsonSupport canonicalJson;
    private final ConcurrentMap<String, String> activeTurns =
        new ConcurrentHashMap<>();

    public SharedInteractiveTurnCoordinator(
        SpecialistRegistry specialistRegistry,
        AIExecutionConversationSnapshotProvider snapshotProvider,
        AIExecutionConversationSnapshotRegistry snapshotRegistry,
        CanonicalJsonSupport canonicalJson
    ) {
        this.specialistRegistry = Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        this.snapshotProvider = Objects.requireNonNull(
            snapshotProvider,
            "snapshotProvider is required"
        );
        this.snapshotRegistry = Objects.requireNonNull(
            snapshotRegistry,
            "snapshotRegistry is required"
        );
        this.canonicalJson = Objects.requireNonNull(
            canonicalJson,
            "canonicalJson is required"
        );
    }

    <T> CoordinatedTurn<T> coordinate(
        SpecialistId dialogueOwner,
        TrustedExecutionContext trustedContext,
        ConversationBinding binding,
        String idempotencyKey,
        RecordingPolicy recordingPolicy,
        TurnWork<T> work
    ) {
        Objects.requireNonNull(dialogueOwner, "dialogueOwner is required");
        Objects.requireNonNull(trustedContext, "trustedContext is required");
        Objects.requireNonNull(
            recordingPolicy,
            "recordingPolicy is required"
        );
        Objects.requireNonNull(work, "work is required");

        AIExecutionFailure requestFailure = validateRequest(
            trustedContext,
            binding,
            idempotencyKey
        );
        if (requestFailure != null) {
            return CoordinatedTurn.failed(requestFailure, false);
        }

        SpecialistDefinition<?, ?> definition;
        try {
            definition = specialistRegistry.require(dialogueOwner);
        } catch (RuntimeException ex) {
            return CoordinatedTurn.failed(
                new AIExecutionFailure(
                    "SPECIALIST_NOT_FOUND",
                    "The requested dialogue owner is not registered.",
                    false
                ),
                false
            );
        }
        AIExecutionFailure eligibilityFailure = validateEligibility(
            definition,
            recordingPolicy
        );
        if (eligibilityFailure != null) {
            return CoordinatedTurn.failed(eligibilityFailure, false);
        }

        String conversationKey = conversationKey(
            trustedContext,
            binding
        );
        String turnId = turnId(
            conversationKey,
            dialogueOwner,
            idempotencyKey
        );
        String active = activeTurns.putIfAbsent(
            conversationKey,
            turnId
        );
        if (active != null) {
            return CoordinatedTurn.failed(
                new AIExecutionFailure(
                    "CONVERSATION_BUSY",
                    "Another interactive turn is active for this conversation.",
                    true
                ),
                true
            );
        }

        ConversationBinding approvedBinding = null;
        try {
            ApprovedConversationSnapshot snapshot;
            try {
                snapshot = snapshotProvider.capture(
                    binding,
                    turnId,
                    dialogueOwner
                );
                approvedBinding = snapshotRegistry.approve(
                    binding,
                    snapshot
                );
            } catch (RuntimeException ex) {
                return CoordinatedTurn.failed(
                    new AIExecutionFailure(
                        "CONVERSATION_SNAPSHOT_FAILED",
                        "The approved conversation snapshot could not be prepared.",
                        false
                    ),
                    false
                );
            }
            T value = work.run(new ApprovedInteractiveTurn(
                definition,
                approvedBinding,
                snapshot
            ));
            return CoordinatedTurn.succeeded(value);
        } finally {
            snapshotRegistry.release(approvedBinding);
            activeTurns.remove(conversationKey, turnId);
        }
    }

    private AIExecutionFailure validateRequest(
        TrustedExecutionContext trustedContext,
        ConversationBinding binding,
        String idempotencyKey
    ) {
        if (trustedContext.source() != ExecutionSource.INTERACTIVE) {
            return new AIExecutionFailure(
                "INTERACTIVE_SOURCE_REQUIRED",
                "Interactive execution requires an authenticated user turn.",
                false
            );
        }
        if (binding == null) {
            return new AIExecutionFailure(
                "CONVERSATION_BINDING_REQUIRED",
                "Interactive execution requires a backend conversation binding.",
                false
            );
        }
        if (binding.approvedSnapshotToken() != null) {
            return new AIExecutionFailure(
                "SNAPSHOT_TOKEN_NOT_ACCEPTED",
                "Conversation snapshot approval is owned by the backend gateway.",
                false
            );
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new AIExecutionFailure(
                "INTERACTIVE_IDEMPOTENCY_KEY_REQUIRED",
                "Interactive execution requires an idempotency key.",
                false
            );
        }
        return null;
    }

    private AIExecutionFailure validateEligibility(
        SpecialistDefinition<?, ?> definition,
        RecordingPolicy recordingPolicy
    ) {
        var input = definition.inputAdapter();
        if (input.interactionCapability()
            != SpecialistInteractionCapability.DIALOGUE_CAPABLE) {
            return new AIExecutionFailure(
                "DIALOGUE_OWNER_INELIGIBLE",
                "The requested specialist is not eligible to own dialogue.",
                false
            );
        }
        if (input.conversationBinding()
            == SpecialistConversationBinding.DISABLED) {
            return new AIExecutionFailure(
                "DIALOGUE_OWNER_CONVERSATION_INVALID",
                "The dialogue owner must accept validated conversation turns.",
                false
            );
        }
        if (input.recordValidatedTurns()
            != recordingPolicy.recordValidatedTurns()) {
            return new AIExecutionFailure(
                "DIALOGUE_OWNER_RECORDING_INVALID",
                recordingPolicy == RecordingPolicy.DIRECT
                    ? "A direct dialogue owner must record validated turns."
                    : "A coordinated dialogue owner must defer turn recording.",
                false
            );
        }
        if (input.inputContinuation().isPresent()) {
            return new AIExecutionFailure(
                "INTERACTIVE_INPUT_WAIT_UNSUPPORTED",
                "Interactive input continuation is not supported by this execution boundary.",
                false
            );
        }
        return null;
    }

    private String conversationKey(
        TrustedExecutionContext trustedContext,
        ConversationBinding binding
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put(
            "access",
            ExecutionAccessBinding.from(trustedContext)
        );
        value.put("userId", binding.userId());
        value.put("conversationId", binding.conversationId());
        return canonicalJson.hashValue(value);
    }

    private String turnId(
        String conversationKey,
        SpecialistId dialogueOwner,
        String idempotencyKey
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("conversationKey", conversationKey);
        value.put("specialist", dialogueOwner.toString());
        value.put("idempotencyKey", idempotencyKey);
        return "turn-" + canonicalJson.hashValue(value).substring(0, 32);
    }

    enum RecordingPolicy {
        DIRECT(true),
        COORDINATED(false);

        private final boolean recordValidatedTurns;

        RecordingPolicy(boolean recordValidatedTurns) {
            this.recordValidatedTurns = recordValidatedTurns;
        }

        boolean recordValidatedTurns() {
            return recordValidatedTurns;
        }
    }

    @FunctionalInterface
    interface TurnWork<T> {
        T run(ApprovedInteractiveTurn turn);
    }

    record ApprovedInteractiveTurn(
        SpecialistDefinition<?, ?> definition,
        ConversationBinding approvedBinding,
        ApprovedConversationSnapshot snapshot
    ) {
        ApprovedInteractiveTurn {
            Objects.requireNonNull(definition, "definition is required");
            Objects.requireNonNull(
                approvedBinding,
                "approvedBinding is required"
            );
            Objects.requireNonNull(snapshot, "snapshot is required");
        }
    }

    record CoordinatedTurn<T>(
        T value,
        AIExecutionFailure failure,
        boolean activeTurn
    ) {
        CoordinatedTurn {
            if ((value == null) == (failure == null)) {
                throw new IllegalArgumentException(
                    "A coordinated turn requires exactly one value or failure"
                );
            }
        }

        static <T> CoordinatedTurn<T> succeeded(T value) {
            return new CoordinatedTurn<>(
                Objects.requireNonNull(value, "value is required"),
                null,
                false
            );
        }

        static <T> CoordinatedTurn<T> failed(
            AIExecutionFailure failure,
            boolean activeTurn
        ) {
            return new CoordinatedTurn<>(
                null,
                Objects.requireNonNull(failure, "failure is required"),
                activeTurn
            );
        }

        boolean succeeded() {
            return failure == null;
        }
    }
}
