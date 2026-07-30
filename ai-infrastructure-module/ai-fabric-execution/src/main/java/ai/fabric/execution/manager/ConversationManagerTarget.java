package ai.fabric.execution.manager;

import ai.fabric.execution.specialist.SpecialistId;
import java.util.Objects;

/**
 * One exact-version worker plus application-owned typed boundaries.
 */
public record ConversationManagerTarget<P, I, O>(
    SpecialistId specialistId,
    String description,
    ConversationManagerTargetInputMapper<P, I> inputMapper,
    ConversationManagerTargetResultProjector<P, O> resultProjector
) {
    public ConversationManagerTarget {
        Objects.requireNonNull(
            specialistId,
            "specialistId is required"
        );
        description = Objects.requireNonNull(
            description,
            "description is required"
        ).trim();
        if (description.isEmpty()) {
            throw new IllegalArgumentException(
                "description is required"
            );
        }
        if (description.length()
            > ConversationManagerTargetView.MAX_DESCRIPTION_CHARACTERS) {
            throw new IllegalArgumentException(
                "description must not exceed "
                    + ConversationManagerTargetView
                        .MAX_DESCRIPTION_CHARACTERS
                    + " characters"
            );
        }
        Objects.requireNonNull(inputMapper, "inputMapper is required");
        Objects.requireNonNull(
            resultProjector,
            "resultProjector is required"
        );
    }

    public ConversationManagerTargetView view() {
        return new ConversationManagerTargetView(
            specialistId.toString(),
            description
        );
    }
}
