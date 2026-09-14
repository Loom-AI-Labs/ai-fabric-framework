package ai.fabric.execution.chain.state;

import ai.fabric.execution.chain.SpecialistChainManagerInput;
import ai.fabric.execution.chain.SpecialistChainResultView;
import ai.fabric.execution.chain.SpecialistChainStepTrace;
import ai.fabric.execution.manager.ConversationManagerContextValue;
import ai.fabric.execution.specialist.SpecialistId;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Protected resumable state containing only application-approved data. */
public record SpecialistChainCheckpoint(
    SpecialistChainCheckpointPhase phase,
    String currentUserMessage,
    List<ConversationManagerContextValue> applicationContext,
    List<SpecialistChainResultView> projectedResults,
    List<SpecialistChainStepTrace> steps,
    int managerDecisionCount,
    int workerInvocationCount,
    int projectedResultCharacters,
    Map<String, Integer> targetInvocationCounts,
    String lastDirectiveHash,
    SpecialistChainManagerExecutionCheckpoint managerExecution,
    String conversationSnapshotRevision,
    long conversationSourceTurnCount
) {
    public SpecialistChainCheckpoint {
        Objects.requireNonNull(phase, "phase is required");
        currentUserMessage = Objects.requireNonNull(
            currentUserMessage,
            "currentUserMessage is required"
        ).trim();
        if (currentUserMessage.isEmpty()) {
            throw new IllegalArgumentException(
                "currentUserMessage is required"
            );
        }
        if (currentUserMessage.length()
            > SpecialistChainManagerInput.MAX_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException(
                "currentUserMessage exceeds the chain-manager limit"
            );
        }
        applicationContext = applicationContext == null
            ? List.of()
            : List.copyOf(applicationContext);
        if (applicationContext.size()
            > SpecialistChainManagerInput.MAX_CONTEXT_VALUES) {
            throw new IllegalArgumentException(
                "applicationContext exceeds the chain-manager limit"
            );
        }
        HashSet<String> contextNames = new HashSet<>();
        applicationContext.forEach(value -> {
            ConversationManagerContextValue required =
                Objects.requireNonNull(
                    value,
                    "application context value is required"
                );
            if (!contextNames.add(required.name())) {
                throw new IllegalArgumentException(
                    "applicationContext contains duplicate name "
                        + required.name()
                );
            }
        });
        projectedResults = projectedResults == null
            ? List.of()
            : List.copyOf(projectedResults);
        if (projectedResults.size()
            > SpecialistChainManagerInput.MAX_RESULTS) {
            throw new IllegalArgumentException(
                "projectedResults exceed the chain-manager limit"
            );
        }
        projectedResults.forEach(result -> Objects.requireNonNull(
            result,
            "projected result is required"
        ));
        steps = steps == null ? List.of() : List.copyOf(steps);
        steps.forEach(step -> Objects.requireNonNull(
            step,
            "chain step is required"
        ));
        if (managerDecisionCount < 0
            || workerInvocationCount < 0
            || projectedResultCharacters < 0
            || conversationSourceTurnCount < 0) {
            throw new IllegalArgumentException(
                "Chain checkpoint counts must not be negative"
            );
        }
        LinkedHashMap<String, Integer> invocationCounts =
            new LinkedHashMap<>();
        if (targetInvocationCounts != null) {
            targetInvocationCounts.forEach((target, count) -> {
                String safeTarget = Objects.requireNonNull(
                    target,
                    "target invocation key is required"
                ).trim();
                if (safeTarget.isEmpty() || count == null || count < 0) {
                    throw new IllegalArgumentException(
                        "Target invocation counts must be non-negative"
                    );
                }
                SpecialistId.parse(safeTarget);
                invocationCounts.put(safeTarget, count);
            });
        }
        if (invocationCounts.size()
            > SpecialistChainManagerInput.MAX_TARGETS) {
            throw new IllegalArgumentException(
                "targetInvocationCounts exceed the chain-manager limit"
            );
        }
        targetInvocationCounts = Collections.unmodifiableMap(
            invocationCounts
        );
        lastDirectiveHash = normalizeOptional(lastDirectiveHash);
        conversationSnapshotRevision = normalizeOptional(
            conversationSnapshotRevision
        );
        if (phase == SpecialistChainCheckpointPhase.READY_FOR_MANAGER
            && managerExecution != null) {
            throw new IllegalArgumentException(
                "READY_FOR_MANAGER cannot retain a manager execution"
            );
        }
        if (phase != SpecialistChainCheckpointPhase.READY_FOR_MANAGER
            && phase != SpecialistChainCheckpointPhase.MANAGER_IN_FLIGHT
            && managerExecution == null) {
            throw new IllegalArgumentException(
                "Accepted and worker phases require manager lineage"
            );
        }
        if (phase == SpecialistChainCheckpointPhase.MANAGER_IN_FLIGHT
            && managerExecution != null) {
            throw new IllegalArgumentException(
                "MANAGER_IN_FLIGHT cannot contain an uncheckpointed result"
            );
        }
    }

    public static SpecialistChainCheckpoint initial(
        String userMessage,
        List<ConversationManagerContextValue> applicationContext,
        String snapshotRevision,
        long sourceTurnCount
    ) {
        return new SpecialistChainCheckpoint(
            SpecialistChainCheckpointPhase.READY_FOR_MANAGER,
            userMessage,
            applicationContext,
            List.of(),
            List.of(),
            0,
            0,
            0,
            Map.of(),
            null,
            null,
            snapshotRevision,
            sourceTurnCount
        );
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
