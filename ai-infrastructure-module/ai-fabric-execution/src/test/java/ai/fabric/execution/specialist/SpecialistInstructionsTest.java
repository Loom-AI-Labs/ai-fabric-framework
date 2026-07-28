package ai.fabric.execution.specialist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SpecialistInstructionsTest {

    @Test
    void rendersBoundedApplicationOwnedInstructions() {
        SpecialistInstructions instructions = new SpecialistInstructions(
            "Assess the current account.",
            "Use only approved evidence."
        );

        assertThat(instructions.render()).isEqualTo(
            "Objective: Assess the current account.\n"
                + "Specialist constraints:\n"
                + "Use only approved evidence."
        );
    }

    @Test
    void rejectsUnboundedObjectiveAndOverlay() {
        assertThatThrownBy(() ->
            new SpecialistInstructions("x".repeat(1_001), null)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("objective must not exceed 1000");

        assertThatThrownBy(() ->
            new SpecialistInstructions("Assess the account.", "x".repeat(8_001))
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("promptOverlay must not exceed 8000");
    }
}
