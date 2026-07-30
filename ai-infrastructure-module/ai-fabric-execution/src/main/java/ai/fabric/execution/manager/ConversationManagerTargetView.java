package ai.fabric.execution.manager;

import ai.fabric.execution.specialist.SpecialistId;
import java.util.Objects;

/**
 * Bounded target description exposed to the manager model.
 */
public record ConversationManagerTargetView(
    String specialist,
    String description
) {
    public static final int MAX_DESCRIPTION_CHARACTERS = 500;

    public ConversationManagerTargetView {
        specialist = Objects.requireNonNull(
            specialist,
            "specialist is required"
        ).trim();
        SpecialistId.parse(specialist);
        description = Objects.requireNonNull(
            description,
            "description is required"
        ).trim();
        if (description.isEmpty()) {
            throw new IllegalArgumentException(
                "description is required"
            );
        }
        if (description.length() > MAX_DESCRIPTION_CHARACTERS) {
            throw new IllegalArgumentException(
                "description must not exceed "
                    + MAX_DESCRIPTION_CHARACTERS + " characters"
            );
        }
    }
}
