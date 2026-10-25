package ai.fabric.intent.orchestration.policy;

import ai.fabric.config.OrchestrationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReadActionResolutionPolicyTest {

    @Test
    void defaultsEvidenceItemBudgetToFive() {
        assertThat(OrchestrationPolicy.ReadActionResolutionPolicy.defaults().maxActionEvidenceItems()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 17, 100})
    void preservesEvidenceItemBudgetWithinBounds(int budget) {
        assertThat(policy(budget, true).maxActionEvidenceItems()).isEqualTo(budget);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 101, Integer.MAX_VALUE})
    void rejectsOutOfRangeEvidenceItemBudgetInsteadOfNormalizing(int budget) {
        assertThatIllegalArgumentException().isThrownBy(() -> policy(budget, true))
            .withMessage("maxActionEvidenceItems must be between 1 and 100");
        assertThatIllegalArgumentException().isThrownBy(() -> policy(budget, false))
            .withMessage("maxActionEvidenceItems must be between 1 and 100");
    }

    private OrchestrationPolicy.ReadActionResolutionPolicy policy(int budget, boolean enabled) {
        return new OrchestrationPolicy.ReadActionResolutionPolicy(
            enabled,
            OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
            List.of(),
            false,
            1,
            2,
            2,
            1,
            4_000,
            2_400,
            budget,
            OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT,
            true
        );
    }
}
