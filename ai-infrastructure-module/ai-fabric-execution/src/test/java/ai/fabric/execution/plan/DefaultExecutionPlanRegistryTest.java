package ai.fabric.execution.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistIdentity;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistInstructions;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.SpecialistWritePolicy;
import ai.fabric.execution.specialist.client.DefaultSpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultExecutionPlanRegistryTest {

    private static final SpecialistId FIRST = SpecialistId.of("first", "1");
    private static final SpecialistId SECOND = SpecialistId.of("second", "1");

    @Test
    void validatesAndFingerprintsASequentialTypedPlan() {
        SpecialistRegistry specialists = registry(
            definition(FIRST, String.class, Integer.class, false),
            definition(SECOND, Long.class, Boolean.class, false)
        );
        PlanStepInputMapper<String, String> firstMapper = mapper(
            PlanComponentId.of("first-input", "1"),
            String.class,
            Map.of(),
            (input, outputs) -> input
        );
        PlanStepInputMapper<String, Long> secondMapper = mapper(
            PlanComponentId.of("second-input", "1"),
            Long.class,
            Map.of("first", Integer.class),
            (input, outputs) ->
                outputs.require("first", Integer.class).longValue()
        );
        PlanResultAggregator<String, String> aggregator = aggregator(
            PlanComponentId.of("result", "1"),
            Map.of("first", Integer.class, "second", Boolean.class)
        );
        ExecutionPlanDefinition<String, String> definition =
            plan(firstMapper.id(), secondMapper.id(), aggregator.id());

        DefaultExecutionPlanRegistry firstRegistry =
            new DefaultExecutionPlanRegistry(
                List.of(definition),
                specialists,
                clientFactory(specialists),
                new PlanComponentRegistry(
                    List.of(firstMapper, secondMapper),
                    List.of(aggregator)
                ),
                4,
                Duration.ofMinutes(1)
            );
        DefaultExecutionPlanRegistry secondRegistry =
            new DefaultExecutionPlanRegistry(
                List.of(definition),
                specialists,
                clientFactory(specialists),
                new PlanComponentRegistry(
                    List.of(firstMapper, secondMapper),
                    List.of(aggregator)
                ),
                4,
                Duration.ofMinutes(1)
            );

        assertThat(firstRegistry.require(definition.id()).contentHash())
            .hasSize(64)
            .isEqualTo(
                secondRegistry.require(definition.id()).contentHash()
            );
    }

    @Test
    void rejectsFutureOrWronglyTypedMapperDependencies() {
        SpecialistRegistry specialists = registry(
            definition(FIRST, String.class, Integer.class, false),
            definition(SECOND, Long.class, Boolean.class, false)
        );
        PlanStepInputMapper<String, String> firstMapper = mapper(
            PlanComponentId.of("first-input", "1"),
            String.class,
            Map.of("second", Boolean.class),
            (input, outputs) -> input
        );
        PlanStepInputMapper<String, Long> secondMapper = mapper(
            PlanComponentId.of("second-input", "1"),
            Long.class,
            Map.of(),
            (input, outputs) -> 1L
        );
        PlanResultAggregator<String, String> aggregator = aggregator(
            PlanComponentId.of("result", "1"),
            Map.of("first", Integer.class)
        );

        assertThatThrownBy(() -> new DefaultExecutionPlanRegistry(
            List.of(plan(firstMapper.id(), secondMapper.id(), aggregator.id())),
            specialists,
            clientFactory(specialists),
            new PlanComponentRegistry(
                List.of(firstMapper, secondMapper),
                List.of(aggregator)
            ),
            4,
            Duration.ofMinutes(1)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("references unavailable step output second");
    }

    @Test
    void rejectsMapperOutputThatDoesNotMatchSpecialistInput() {
        SpecialistRegistry specialists = registry(
            definition(FIRST, String.class, Integer.class, false),
            definition(SECOND, Long.class, Boolean.class, false)
        );
        PlanStepInputMapper<String, Integer> firstMapper = mapper(
            PlanComponentId.of("first-input", "1"),
            Integer.class,
            Map.of(),
            (input, outputs) -> 1
        );
        PlanStepInputMapper<String, Long> secondMapper = mapper(
            PlanComponentId.of("second-input", "1"),
            Long.class,
            Map.of("first", Integer.class),
            (input, outputs) -> 1L
        );
        PlanResultAggregator<String, String> aggregator = aggregator(
            PlanComponentId.of("result", "1"),
            Map.of("second", Boolean.class)
        );

        assertThatThrownBy(() -> new DefaultExecutionPlanRegistry(
            List.of(plan(firstMapper.id(), secondMapper.id(), aggregator.id())),
            specialists,
            clientFactory(specialists),
            new PlanComponentRegistry(
                List.of(firstMapper, secondMapper),
                List.of(aggregator)
            ),
            4,
            Duration.ofMinutes(1)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("specialist input must be java.lang.String");
    }

    @Test
    void rejectsWriteCapableSpecialist() {
        SpecialistRegistry specialists = registry(
            definition(FIRST, String.class, Integer.class, true),
            definition(SECOND, Long.class, Boolean.class, false)
        );
        PlanStepInputMapper<String, String> firstMapper = mapper(
            PlanComponentId.of("first-input", "1"),
            String.class,
            Map.of(),
            (input, outputs) -> input
        );
        PlanStepInputMapper<String, Long> secondMapper = mapper(
            PlanComponentId.of("second-input", "1"),
            Long.class,
            Map.of("first", Integer.class),
            (input, outputs) -> 1L
        );
        PlanResultAggregator<String, String> aggregator = aggregator(
            PlanComponentId.of("result", "1"),
            Map.of("second", Boolean.class)
        );

        assertThatThrownBy(() -> new DefaultExecutionPlanRegistry(
            List.of(plan(firstMapper.id(), secondMapper.id(), aggregator.id())),
            specialists,
            clientFactory(specialists),
            new PlanComponentRegistry(
                List.of(firstMapper, secondMapper),
                List.of(aggregator)
            ),
            4,
            Duration.ofMinutes(1)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("WRITE-capable specialist first@1");
    }

    @Test
    void validatesOptInIndependentParallelBranchesAndHashesTopology() {
        SpecialistRegistry specialists = registry(
            definition(FIRST, String.class, Integer.class, false),
            definition(SECOND, Long.class, Boolean.class, false)
        );
        PlanStepInputMapper<String, String> firstMapper = mapper(
            PlanComponentId.of("first-input", "1"),
            String.class,
            Map.of(),
            (input, outputs) -> input
        );
        PlanStepInputMapper<String, Long> secondMapper = mapper(
            PlanComponentId.of("second-input", "1"),
            Long.class,
            Map.of(),
            (input, outputs) -> 1L
        );
        PlanResultAggregator<String, String> aggregator = aggregator(
            PlanComponentId.of("result", "1"),
            Map.of("first", Integer.class, "second", Boolean.class)
        );
        ExecutionPlanDefinition<String, String> twoWorkers = parallelPlan(
            firstMapper.id(),
            secondMapper.id(),
            aggregator.id(),
            2
        );
        ExecutionPlanDefinition<String, String> threeSlots = parallelPlan(
            firstMapper.id(),
            secondMapper.id(),
            aggregator.id(),
            3
        );
        PlanComponentRegistry components = new PlanComponentRegistry(
            List.of(firstMapper, secondMapper),
            List.of(aggregator)
        );

        assertThatThrownBy(() -> new DefaultExecutionPlanRegistry(
            List.of(twoWorkers),
            specialists,
            clientFactory(specialists),
            components,
            4,
            Duration.ofMinutes(1)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parallel plans are disabled");

        DefaultExecutionPlanRegistry twoWorkerRegistry =
            new DefaultExecutionPlanRegistry(
                List.of(twoWorkers),
                specialists,
                clientFactory(specialists),
                components,
                4,
                Duration.ofMinutes(1),
                true,
                4
            );
        DefaultExecutionPlanRegistry threeSlotRegistry =
            new DefaultExecutionPlanRegistry(
                List.of(threeSlots),
                specialists,
                clientFactory(specialists),
                components,
                4,
                Duration.ofMinutes(1),
                true,
                4
            );

        assertThat(twoWorkerRegistry.require(twoWorkers.id()).contentHash())
            .hasSize(64)
            .isNotEqualTo(
                threeSlotRegistry.require(threeSlots.id()).contentHash()
            );
    }

    @Test
    void rejectsParallelSiblingDependenciesAndBranchCeiling() {
        SpecialistRegistry specialists = registry(
            definition(FIRST, String.class, Integer.class, false),
            definition(SECOND, Long.class, Boolean.class, false)
        );
        PlanStepInputMapper<String, String> firstMapper = mapper(
            PlanComponentId.of("first-input", "1"),
            String.class,
            Map.of(),
            (input, outputs) -> input
        );
        PlanStepInputMapper<String, Long> dependentSecond = mapper(
            PlanComponentId.of("second-input", "1"),
            Long.class,
            Map.of("first", Integer.class),
            (input, outputs) -> 1L
        );
        PlanResultAggregator<String, String> aggregator = aggregator(
            PlanComponentId.of("result", "1"),
            Map.of("first", Integer.class, "second", Boolean.class)
        );
        ExecutionPlanDefinition<String, String> plan = parallelPlan(
            firstMapper.id(),
            dependentSecond.id(),
            aggregator.id(),
            2
        );
        PlanComponentRegistry components = new PlanComponentRegistry(
            List.of(firstMapper, dependentSecond),
            List.of(aggregator)
        );

        assertThatThrownBy(() -> new DefaultExecutionPlanRegistry(
            List.of(plan),
            specialists,
            clientFactory(specialists),
            components,
            4,
            Duration.ofMinutes(1),
            true,
            4
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("references unavailable step output first");

        PlanStepInputMapper<String, Long> independentSecond = mapper(
            dependentSecond.id(),
            Long.class,
            Map.of(),
            (input, outputs) -> 1L
        );
        assertThatThrownBy(() -> new DefaultExecutionPlanRegistry(
            List.of(plan),
            specialists,
            clientFactory(specialists),
            new PlanComponentRegistry(
                List.of(firstMapper, independentSecond),
                List.of(aggregator)
            ),
            4,
            Duration.ofMinutes(1),
            true,
            1
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("branch ceiling of 1");
    }

    @Test
    void rejectsDuplicateStepsAndDeploymentLimitViolations() {
        SpecialistRegistry specialists = registry(
            definition(FIRST, String.class, Integer.class, false)
        );
        PlanStepInputMapper<String, String> mapper = mapper(
            PlanComponentId.of("first-input", "1"),
            String.class,
            Map.of(),
            (input, outputs) -> input
        );
        PlanResultAggregator<String, String> aggregator = aggregator(
            PlanComponentId.of("result", "1"),
            Map.of("first", Integer.class)
        );
        ExecutionPlanDefinition<String, String> duplicate =
            new ExecutionPlanDefinition<>(
                ExecutionPlanId.of("duplicate", "1"),
                String.class,
                String.class,
                List.of(
                    new SpecialistPlanStep(
                        "first",
                        FIRST,
                        String.class,
                        Integer.class,
                        mapper.id()
                    ),
                    new SpecialistPlanStep(
                        "first",
                        FIRST,
                        String.class,
                        Integer.class,
                        mapper.id()
                    )
                ),
                aggregator.id(),
                Duration.ofSeconds(10)
            );

        assertThatThrownBy(() -> new DefaultExecutionPlanRegistry(
            List.of(duplicate),
            specialists,
            clientFactory(specialists),
            new PlanComponentRegistry(
                List.of(mapper),
                List.of(aggregator)
            ),
            4,
            Duration.ofMinutes(1)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate step first");

        assertThatThrownBy(() -> new DefaultExecutionPlanRegistry(
            List.of(new ExecutionPlanDefinition<>(
                ExecutionPlanId.of("too-slow", "1"),
                String.class,
                String.class,
                List.of(new SpecialistPlanStep(
                    "first",
                    FIRST,
                    String.class,
                    Integer.class,
                    mapper.id()
                )),
                aggregator.id(),
                Duration.ofMinutes(2)
            )),
            specialists,
            clientFactory(specialists),
            new PlanComponentRegistry(
                List.of(mapper),
                List.of(aggregator)
            ),
            1,
            Duration.ofMinutes(1)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximumDuration exceeds");
    }

    private ExecutionPlanDefinition<String, String> plan(
        PlanComponentId firstMapper,
        PlanComponentId secondMapper,
        PlanComponentId aggregator
    ) {
        return new ExecutionPlanDefinition<>(
            ExecutionPlanId.of("proof", "1"),
            String.class,
            String.class,
            List.of(
                new SpecialistPlanStep(
                    "first",
                    FIRST,
                    String.class,
                    Integer.class,
                    firstMapper
                ),
                new SpecialistPlanStep(
                    "second",
                    SECOND,
                    Long.class,
                    Boolean.class,
                    secondMapper
                )
            ),
            aggregator,
            Duration.ofSeconds(30)
        );
    }

    private ExecutionPlanDefinition<String, String> parallelPlan(
        PlanComponentId firstMapper,
        PlanComponentId secondMapper,
        PlanComponentId aggregator,
        int maximumConcurrency
    ) {
        return new ExecutionPlanDefinition<>(
            ExecutionPlanId.of("parallel-proof", "1"),
            String.class,
            String.class,
            List.<PlanStage>of(new ParallelPlanStep(
                "independent-readers",
                List.of(
                    new SpecialistPlanStep(
                        "first",
                        FIRST,
                        String.class,
                        Integer.class,
                        firstMapper
                    ),
                    new SpecialistPlanStep(
                        "second",
                        SECOND,
                        Long.class,
                        Boolean.class,
                        secondMapper
                    )
                ),
                FanInPolicy.ALL_REQUIRED,
                maximumConcurrency
            )),
            aggregator,
            Duration.ofSeconds(30)
        );
    }

    private <I, O> SpecialistDefinition<I, O> definition(
        SpecialistId id,
        Class<I> inputType,
        Class<O> outputType,
        boolean writeEnabled
    ) {
        RequestedCapabilityProfile capabilities =
            new RequestedCapabilityProfile(
                false,
                Set.of(),
                writeEnabled ? Set.of("write") : Set.of(),
                Set.of(),
                writeEnabled ? Set.of("write") : Set.of()
            );
        return new SpecialistDefinition<>(
            new SpecialistIdentity(id, id.name(), "Test specialist"),
            new SpecialistInstructions("Test", null),
            new SpecialistExecutionProfile(
                "test",
                capabilities,
                ExecutionStrategy.SINGLE_PASS,
                writeEnabled
                    ? SpecialistWritePolicy.CONFIRMATION_RECEIPT_REQUIRED
                    : SpecialistWritePolicy.DISABLED
            ),
            SpecialistLimits.defaults(),
            new SpecialistInputAdapter<>() {
                @Override
                public Class<I> inputType() {
                    return inputType;
                }

                @Override
                public void validate(I input) {}

                @Override
                public String renderModelInput(I input) {
                    return input.toString();
                }

                @Override
                public OrchestrationContext orchestrationContext(I input) {
                    return OrchestrationContext.builder().build();
                }
            },
            new SpecialistOutputAdapter<>() {
                @Override
                public Class<O> outputType() {
                    return outputType;
                }

                @Override
                public O project(
                    ai.fabric.intent.orchestration.OrchestrationResult result,
                    List<ai.fabric.evidence.AIEvidenceReference> evidence
                ) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void validate(O output) {}
            }
        );
    }

    private SpecialistRegistry registry(
        SpecialistDefinition<?, ?>... definitions
    ) {
        Map<SpecialistId, RegisteredSpecialist> values =
            new LinkedHashMap<>();
        for (SpecialistDefinition<?, ?> definition : definitions) {
            values.put(
                definition.id(),
                new RegisteredSpecialist(
                    definition,
                    SpecialistDefinitionSource.JAVA,
                    ai.fabric.execution.specialist.manifest.CanonicalJsonSupport
                        .sha256(definition.id().toString()),
                    "test:" + definition.id(),
                    Map.of()
                )
            );
        }
        return new SpecialistRegistry() {
            @Override
            public Optional<SpecialistDefinition<?, ?>> find(SpecialistId id) {
                return Optional.ofNullable(values.get(id))
                    .map(RegisteredSpecialist::definition);
            }

            @Override
            public List<SpecialistDefinition<?, ?>> list() {
                return values.values().stream()
                    .map(RegisteredSpecialist::definition)
                    .toList();
            }

            @Override
            public Optional<RegisteredSpecialist> findRegistered(
                SpecialistId id
            ) {
                return Optional.ofNullable(values.get(id));
            }
        };
    }

    private SpecialistClientFactory clientFactory(
        SpecialistRegistry specialists
    ) {
        return new DefaultSpecialistClientFactory(
            specialists,
            mock(AIExecutionGateway.class),
            new ObjectMapper()
        );
    }

    private <I> PlanStepInputMapper<String, I> mapper(
        PlanComponentId id,
        Class<I> outputType,
        Map<String, Class<?>> dependencies,
        MapperFunction<I> function
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
                return function.map(planInput, approvedOutputs);
            }
        };
    }

    private PlanResultAggregator<String, String> aggregator(
        PlanComponentId id,
        Map<String, Class<?>> dependencies
    ) {
        return new PlanResultAggregator<>() {
            @Override
            public PlanComponentId id() {
                return id;
            }

            @Override
            public Class<String> planInputType() {
                return String.class;
            }

            @Override
            public Class<String> outputType() {
                return String.class;
            }

            @Override
            public Map<String, Class<?>> requiredStepOutputs() {
                return dependencies;
            }

            @Override
            public String aggregate(
                String planInput,
                PlanStepOutputs approvedOutputs
            ) {
                return planInput;
            }
        };
    }

    @FunctionalInterface
    private interface MapperFunction<I> {
        I map(String input, PlanStepOutputs outputs);
    }
}
