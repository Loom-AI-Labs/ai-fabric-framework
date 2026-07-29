package ai.fabric.execution.plan;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated plan plus its deterministic content fingerprint.
 */
public record RegisteredExecutionPlan(
    ExecutionPlanDefinition<?, ?> definition,
    String contentHash
) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public RegisteredExecutionPlan {
        Objects.requireNonNull(definition, "definition is required");
        contentHash = Objects.requireNonNull(
            contentHash,
            "contentHash is required"
        ).trim();
        if (!SHA_256.matcher(contentHash).matches()) {
            throw new IllegalArgumentException(
                "contentHash must be a lowercase SHA-256 value"
            );
        }
    }

    public ExecutionPlanId id() {
        return definition.id();
    }
}
