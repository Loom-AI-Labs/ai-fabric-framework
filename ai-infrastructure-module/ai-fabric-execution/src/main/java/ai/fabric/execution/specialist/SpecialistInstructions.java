package ai.fabric.execution.specialist;

import java.util.Objects;

/**
 * Bounded objective and prompt overlay supplied by application code.
 */
public record SpecialistInstructions(String objective, String promptOverlay) {
    public SpecialistInstructions {
        objective = requireText(objective, "objective");
        promptOverlay = normalizeOptional(promptOverlay);
    }

    public String render() {
        if (promptOverlay == null) {
            return "Objective: " + objective;
        }
        return "Objective: " + objective + "\nSpecialist constraints:\n" + promptOverlay;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
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
