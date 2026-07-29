package ai.fabric.execution.specialist;

import java.util.Objects;

/**
 * Existing strongly typed Java output contract.
 */
public record JavaTypeOutputContract(
    Class<?> outputType,
    String promptInstructions
) implements SpecialistOutputContract {

    public JavaTypeOutputContract {
        Objects.requireNonNull(outputType, "outputType is required");
        promptInstructions = requireText(
            promptInstructions,
            "promptInstructions"
        );
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
