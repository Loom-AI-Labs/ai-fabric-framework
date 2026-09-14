package ai.fabric.execution.chain;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Typed, non-authoritative proposal from a specialist-chain manager.
 * Runtime policy remains the authority for every proposed transition.
 */
public record SpecialistChainDirective(
    SpecialistChainDirectiveType type,
    List<SpecialistChainTargetRequest> targets,
    String message,
    String reason,
    List<String> supportingResultIds
) {
    public static final int MAX_TARGETS = 8;
    public static final int MAX_MESSAGE_CHARACTERS = 2_000;
    public static final int MAX_REASON_CHARACTERS = 500;
    public static final int MAX_RESULT_ID_CHARACTERS = 200;

    public SpecialistChainDirective(
        SpecialistChainDirectiveType type,
        List<SpecialistChainTargetRequest> targets,
        String message,
        String reason
    ) {
        this(type, targets, message, reason, List.of());
    }

    public SpecialistChainDirective {
        Objects.requireNonNull(type, "type is required");
        targets = targets == null ? List.of() : List.copyOf(targets);
        if (targets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException(
                "targets must not exceed " + MAX_TARGETS
            );
        }
        Set<String> uniqueTargets = new HashSet<>();
        for (SpecialistChainTargetRequest target : targets) {
            SpecialistChainTargetRequest required = Objects.requireNonNull(
                target,
                "target is required"
            );
            if (!uniqueTargets.add(required.targetSpecialist())) {
                throw new IllegalArgumentException(
                    "targets must not contain duplicate specialists"
                );
            }
        }
        message = normalizeOptional(message);
        reason = requireBounded(
            reason,
            "reason",
            MAX_REASON_CHARACTERS
        );
        LinkedHashSet<String> resultIds = new LinkedHashSet<>();
        if (supportingResultIds != null) {
            for (String resultId : supportingResultIds) {
                String safeId = requireBounded(
                    resultId,
                    "supporting result ID",
                    MAX_RESULT_ID_CHARACTERS
                );
                if (!resultIds.add(safeId)) {
                    throw new IllegalArgumentException(
                        "supportingResultIds must not contain duplicates"
                    );
                }
            }
        }
        if (resultIds.size() > MAX_TARGETS) {
            throw new IllegalArgumentException(
                "supportingResultIds must not exceed " + MAX_TARGETS
            );
        }
        supportingResultIds = List.copyOf(resultIds);

        switch (type) {
            case ASK_USER, COMPLETE -> {
                if (!targets.isEmpty()) {
                    throw new IllegalArgumentException(
                        type + " cannot supply targets"
                    );
                }
                message = requireBounded(
                    message,
                    "message",
                    MAX_MESSAGE_CHARACTERS
                );
                if (type == SpecialistChainDirectiveType.ASK_USER
                    && !supportingResultIds.isEmpty()) {
                    throw new IllegalArgumentException(
                        "ASK_USER cannot supply supporting result IDs"
                    );
                }
            }
            case INVOKE_ONE, HANDOFF -> {
                requireTargetCount(type, targets, 1, 1);
                requireNoMessage(type, message);
                requireNoSupportingResults(type, supportingResultIds);
            }
            case INVOKE_PARALLEL -> {
                requireTargetCount(type, targets, 2, MAX_TARGETS);
                requireNoMessage(type, message);
                requireNoSupportingResults(type, supportingResultIds);
            }
        }
    }

    public SpecialistChainTargetRequest requiredSingleTarget() {
        if (type != SpecialistChainDirectiveType.INVOKE_ONE
            && type != SpecialistChainDirectiveType.HANDOFF) {
            throw new IllegalStateException(
                "Only INVOKE_ONE and HANDOFF have one target"
            );
        }
        return targets.getFirst();
    }

    private static void requireTargetCount(
        SpecialistChainDirectiveType type,
        List<SpecialistChainTargetRequest> targets,
        int minimum,
        int maximum
    ) {
        if (targets.size() < minimum || targets.size() > maximum) {
            throw new IllegalArgumentException(
                type + " requires between " + minimum + " and "
                    + maximum + " targets"
            );
        }
    }

    private static void requireNoMessage(
        SpecialistChainDirectiveType type,
        String message
    ) {
        if (message != null) {
            throw new IllegalArgumentException(
                type + " cannot supply a user-facing message"
            );
        }
    }

    private static void requireNoSupportingResults(
        SpecialistChainDirectiveType type,
        List<String> resultIds
    ) {
        if (!resultIds.isEmpty()) {
            throw new IllegalArgumentException(
                type + " cannot supply supporting result IDs"
            );
        }
    }

    private static String requireBounded(
        String value,
        String field,
        int maximum
    ) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maximum + " characters"
            );
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
