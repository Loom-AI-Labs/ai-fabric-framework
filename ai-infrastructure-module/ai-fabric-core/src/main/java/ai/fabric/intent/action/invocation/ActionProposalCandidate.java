package ai.fabric.intent.action.invocation;

import ai.fabric.intent.action.ActionContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Internal, validated write proposal emitted by orchestration.
 *
 * <p>The candidate is deliberately excluded from public serialization. It can
 * only be consumed by a trusted execution coordinator that creates a durable
 * confirmation receipt.</p>
 */
public record ActionProposalCandidate(
    String actionName,
    Map<String, Object> parameters,
    ActionContext actionContext
) {
    public ActionProposalCandidate {
        actionName = requireText(actionName, "actionName");
        parameters = parameters == null || parameters.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        Objects.requireNonNull(actionContext, "actionContext is required");
    }

    @Override
    public String toString() {
        return "ActionProposalCandidate[actionName=%s, parameterCount=%d]"
            .formatted(actionName, parameters.size());
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
