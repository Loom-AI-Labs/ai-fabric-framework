package dev.aifabric.examples.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.execution.plan.ExecutionPlanDefinition;
import ai.fabric.execution.plan.ParallelPlanStep;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
        "ai.enabled=false",
        "ai.execution.enabled=false",
        "spring.main.web-application-type=none"
    }
)
class AgenticExecutionConsumerApplicationTest {

    @Autowired
    private Map<String, ExecutionPlanDefinition<?, ?>> plans;

    @Test
    void standaloneSpringBootConsumerLoadsPublicPlanDeclarations() {
        assertThat(plans)
            .containsKeys("sequentialSupportPlan", "parallelSupportPlan");

        ExecutionPlanDefinition<?, ?> parallel =
            plans.get("parallelSupportPlan");
        assertThat(parallel.id())
            .isEqualTo(SupportPlanConfiguration.PARALLEL_PLAN);
        assertThat(parallel.steps())
            .singleElement()
            .isInstanceOf(ParallelPlanStep.class);
    }
}
