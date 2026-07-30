package ai.fabric.execution.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanContractTest {

    @Test
    void copiesDefinitionsAndExposesOnlyAllowlistedOutputs() {
        List<SpecialistPlanStep> steps = new java.util.ArrayList<>();
        steps.add(new SpecialistPlanStep(
            "first",
            ai.fabric.execution.specialist.SpecialistId.of("reader", "1"),
            String.class,
            String.class,
            PlanComponentId.of("first-input", "1")
        ));
        ExecutionPlanDefinition<String, String> definition =
            new ExecutionPlanDefinition<>(
                ExecutionPlanId.of("proof", "1"),
                String.class,
                String.class,
                steps,
                PlanComponentId.of("proof-result", "1"),
                Duration.ofSeconds(10)
            );
        steps.clear();

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("first", "approved");
        PlanStepOutputs approved = new PlanStepOutputs(outputs);
        outputs.put("hidden", "not visible");

        assertThat(definition.steps()).hasSize(1);
        assertThat(approved.stepIds()).containsExactly("first");
        assertThat(approved.require("first", String.class))
            .isEqualTo("approved");
        assertThat(approved.find("hidden", String.class)).isEmpty();
        assertThatThrownBy(() -> approved.require("first", Integer.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is not java.lang.Integer");
    }

    @Test
    void rejectsUnversionedOrInvalidIdentitiesAndDurations() {
        assertThatThrownBy(() -> ExecutionPlanId.of("proof@1", "1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not contain '@'");
        assertThatThrownBy(() -> PlanComponentId.of("mapper", " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version is required");
        assertThatThrownBy(() -> new ExecutionPlanDefinition<>(
            ExecutionPlanId.of("proof", "1"),
            String.class,
            String.class,
            List.of(),
            PlanComponentId.of("result", "1"),
            Duration.ZERO
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximumDuration must be positive");
    }

    @Test
    void rejectsDuplicatePlanComponents() {
        PlanStepInputMapper<String, String> mapper = mapper(
            PlanComponentId.of("input", "1"),
            String.class,
            Map.of()
        );

        assertThatThrownBy(() -> new PlanComponentRegistry(
            List.of(mapper, mapper),
            List.of()
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate plan input mapper");
    }

    @Test
    void copiesAndValidatesBoundedParallelStages() {
        SpecialistPlanStep first = step("first");
        SpecialistPlanStep second = step("second");
        List<SpecialistPlanStep> branches =
            new java.util.ArrayList<>(List.of(first, second));

        ParallelPlanStep parallel = new ParallelPlanStep(
            "independent-readers",
            branches,
            FanInPolicy.ALL_REQUIRED,
            2
        );
        branches.clear();

        assertThat(parallel.branches())
            .containsExactly(first, second);
        assertThat(parallel.fanInPolicy())
            .isEqualTo(FanInPolicy.ALL_REQUIRED);
        assertThatThrownBy(() -> new ParallelPlanStep(
            "one-branch",
            List.of(first),
            FanInPolicy.ALL_REQUIRED,
            1
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least two branches");
        assertThatThrownBy(() -> new ParallelPlanStep(
            "duplicate",
            List.of(first, first),
            FanInPolicy.ALL_REQUIRED,
            2
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate branch first");
        assertThatThrownBy(() -> new ParallelPlanStep(
            "under-provisioned",
            List.of(first, second),
            FanInPolicy.ALL_REQUIRED,
            1
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cover every declared branch");
    }

    private SpecialistPlanStep step(String id) {
        return new SpecialistPlanStep(
            id,
            ai.fabric.execution.specialist.SpecialistId.of(id, "1"),
            String.class,
            String.class,
            PlanComponentId.of(id + "-input", "1")
        );
    }

    private <I> PlanStepInputMapper<String, I> mapper(
        PlanComponentId id,
        Class<I> outputType,
        Map<String, Class<?>> dependencies
    ) {
        return new PlanStepInputMapper<>() {
            @Override
            public PlanComponentId id() {
                return id;
            }

            @Override
            public Class<String> planInputType() {
                return String.class;
            }

            @Override
            public Class<I> stepInputType() {
                return outputType;
            }

            @Override
            public Map<String, Class<?>> requiredStepOutputs() {
                return dependencies;
            }

            @Override
            public I map(
                String planInput,
                PlanStepOutputs approvedOutputs
            ) {
                return outputType.cast(planInput);
            }
        };
    }
}
