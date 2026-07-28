package ai.fabric.execution.action;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.intent.action.ActionResult;
import org.junit.jupiter.api.Test;

class ActionProposalDecisionContractTest {

    @Test
    void rejectsOversizedPublicReceiptIdentifier() {
        assertThatThrownBy(() -> new ActionProposalDecisionRequest(
                "r".repeat(121),
                ActionProposalDecision.CONFIRM
            ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("120");
    }

    @Test
    void rejectsOversizedReconciliationReceiptIdentifier() {
        assertThatThrownBy(() -> new ActionProposalReconciliation(
                "r".repeat(121),
                ActionProposalReceiptStatus.SUCCEEDED,
                ActionResult.builder()
                    .success(true)
                    .message("Verified")
                    .build()
            ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("120");
    }
}
