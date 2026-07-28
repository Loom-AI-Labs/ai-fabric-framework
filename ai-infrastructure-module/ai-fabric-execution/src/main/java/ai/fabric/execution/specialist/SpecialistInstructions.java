package ai.fabric.execution.specialist;

import java.util.Objects;

/**
 * Bounded objective and prompt overlay supplied by application code.
 */
public record SpecialistInstructions(String objective, String promptOverlay) {

    private static final int MAX_OBJECTIVE_CHARACTERS = 1_000;
    private static final int MAX_PROMPT_OVERLAY_CHARACTERS = 8_000;

    public SpecialistInstructions {
        objective = requireText(
            objective,
            "objective",
            MAX_OBJECTIVE_CHARACTERS
        );
        promptOverlay = normalizeOptional(
            promptOverlay,
            "promptOverlay",
            MAX_PROMPT_OVERLAY_CHARACTERS
        );
    }

    public String render() {
        if (promptOverlay == null) {
            return "Objective: " + objective;
        }
        return "Objective: " + objective + "\nSpecialist constraints:\n" + promptOverlay;
    }

    private static String requireText(
        String value,
        String field,
        int maxCharacters
    ) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maxCharacters) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maxCharacters + " characters"
            );
        }
        return normalized;
    }

    private static String normalizeOptional(
        String value,
        String field,
        int maxCharacters
    ) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxCharacters) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maxCharacters + " characters"
            );
        }
        return normalized;
    }
}
