package ai.fabric.intent.orchestration.pipeline;

import ai.fabric.config.DecisionRecoveryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class DecisionRecoveryBudgetTest {
    @Test void admissionDeadlineAndRoundLimitDoNotConsumeTheFinalAnswerSlot() {
        var settings = new DecisionRecoveryProperties();
        var budget = new DecisionRecoveryBudget();
        assertThat(budget.acquire(settings, "routing")).isTrue();
        ReflectionTestUtils.setField(budget, "startedNanos", System.nanoTime() - TimeUnit.SECONDS.toNanos(31));
        assertThat(budget.acquire(settings, "routing")).isFalse();
        assertThat(budget.acquireFinalAnswer(settings)).isTrue();
        assertThat(budget.acquireFinalAnswer(settings)).isFalse();
        var zero = new DecisionRecoveryBudget();
        settings.setMaxRepairRounds(0);
        assertThat(zero.acquire(settings, "routing")).isFalse();
        assertThat(zero.acquireFinalAnswer(settings)).isTrue();
    }

    @Test void stateIsSharedByPipelineCopiesButNotByOtherRequestsOrJson() throws Exception {
        var original = PipelineContext.from("question", ai.fabric.intent.orchestration.OrchestrationContext.forUser("u"));
        var settings = new DecisionRecoveryProperties();
        original.getDecisionRecoveryBudget().acquire(settings, "extraction");
        assertThat(original.toBuilder().build().getDecisionRecoveryBudget()).isSameAs(original.getDecisionRecoveryBudget());
        assertThat(PipelineContext.from("other question", original.getOrchestrationContext()).getDecisionRecoveryBudget())
            .isNotSameAs(original.getDecisionRecoveryBudget());
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(original))
            .doesNotContain("decisionRecoveryBudget");
    }
}
