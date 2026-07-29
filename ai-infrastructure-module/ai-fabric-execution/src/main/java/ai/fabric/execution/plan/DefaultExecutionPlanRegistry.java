package ai.fabric.execution.plan;

import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Startup-validating registry for fixed sequential plans.
 */
public final class DefaultExecutionPlanRegistry
    implements ExecutionPlanRegistry {

    private final Map<ExecutionPlanId, RegisteredExecutionPlan> plans;

    public DefaultExecutionPlanRegistry(
        List<ExecutionPlanDefinition<?, ?>> definitions,
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory specialistClientFactory,
        PlanComponentRegistry componentRegistry,
        int maxSteps,
        Duration maxDuration
    ) {
        Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        Objects.requireNonNull(
            specialistClientFactory,
            "specialistClientFactory is required"
        );
        Objects.requireNonNull(
            componentRegistry,
            "componentRegistry is required"
        );
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        if (maxDuration == null
            || maxDuration.isZero()
            || maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
        Map<ExecutionPlanId, RegisteredExecutionPlan> validated =
            new LinkedHashMap<>();
        if (definitions != null) {
            for (ExecutionPlanDefinition<?, ?> definition : definitions) {
                RegisteredExecutionPlan registered = validate(
                    Objects.requireNonNull(
                        definition,
                        "plan definition is required"
                    ),
                    specialistRegistry,
                    specialistClientFactory,
                    componentRegistry,
                    maxSteps,
                    maxDuration
                );
                if (validated.putIfAbsent(
                        registered.id(),
                        registered
                    ) != null) {
                    throw new IllegalArgumentException(
                        "Duplicate execution plan " + registered.id()
                    );
                }
            }
        }
        this.plans = Map.copyOf(validated);
    }

    @Override
    public Optional<RegisteredExecutionPlan> find(ExecutionPlanId id) {
        return Optional.ofNullable(plans.get(id));
    }

    @Override
    public List<RegisteredExecutionPlan> list() {
        return plans.values().stream()
            .sorted(Comparator.comparing(value -> value.id().toString()))
            .toList();
    }

    private RegisteredExecutionPlan validate(
        ExecutionPlanDefinition<?, ?> definition,
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory specialistClientFactory,
        PlanComponentRegistry componentRegistry,
        int maxSteps,
        Duration maxDuration
    ) {
        if (definition.steps().isEmpty()) {
            throw invalid(definition, "must declare at least one step");
        }
        if (definition.steps().size() > maxSteps) {
            throw invalid(
                definition,
                "exceeds the deployment maximum of " + maxSteps + " steps"
            );
        }
        if (definition.maximumDuration().compareTo(maxDuration) > 0) {
            throw invalid(
                definition,
                "maximumDuration exceeds the deployment ceiling"
            );
        }

        Set<String> stepIds = new HashSet<>();
        Map<String, Class<?>> precedingOutputs = new LinkedHashMap<>();
        List<String> fingerprintSteps = new ArrayList<>();
        for (SpecialistPlanStep step : definition.steps()) {
            if (!stepIds.add(step.id())) {
                throw invalid(
                    definition,
                    "declares duplicate step " + step.id()
                );
            }
            SpecialistDefinition<?, ?> specialist;
            try {
                specialist = specialistRegistry.require(step.specialistId());
            } catch (RuntimeException ex) {
                throw invalid(
                    definition,
                    "references unknown specialist " + step.specialistId()
                );
            }
            if (specialist.executionProfile().writeEnabled()) {
                throw invalid(
                    definition,
                    "references WRITE-capable specialist "
                        + step.specialistId()
                );
            }
            PlanStepInputMapper<?, ?> mapper;
            try {
                mapper = componentRegistry.requireMapper(
                    step.inputMapperId()
                );
            } catch (RuntimeException ex) {
                throw invalid(
                    definition,
                    "references unknown input mapper " + step.inputMapperId()
                );
            }
            requireSameType(
                definition,
                "mapper " + mapper.id() + " plan input",
                definition.inputType(),
                mapper.planInputType()
            );
            requireSameType(
                definition,
                "mapper " + mapper.id() + " specialist input",
                step.inputType(),
                mapper.stepInputType()
            );
            try {
                bindStep(specialistClientFactory, step);
            } catch (RuntimeException ex) {
                throw invalid(
                    definition,
                    "step " + step.id()
                        + " has an incompatible typed specialist binding: "
                        + ex.getMessage()
                );
            }
            Map<String, Class<?>> dependencies = normalizedDependencies(
                definition,
                "mapper " + mapper.id(),
                mapper.requiredStepOutputs()
            );
            validateDependencies(
                definition,
                "mapper " + mapper.id(),
                dependencies,
                precedingOutputs
            );
            Class<?> outputType = step.outputType();
            precedingOutputs.put(step.id(), outputType);
            fingerprintSteps.add(String.join(
                "|",
                step.id(),
                step.specialistId().toString(),
                specialistRegistry.requireRegistered(
                    step.specialistId()
                ).contentHash(),
                mapper.id().toString(),
                mapper.getClass().getName(),
                mapper.planInputType().getName(),
                mapper.stepInputType().getName(),
                dependencyDeclaration(dependencies),
                step.inputType().getName(),
                outputType.getName()
            ));
        }

        PlanResultAggregator<?, ?> aggregator;
        try {
            aggregator = componentRegistry.requireAggregator(
                definition.aggregatorId()
            );
        } catch (RuntimeException ex) {
            throw invalid(
                definition,
                "references unknown result aggregator "
                    + definition.aggregatorId()
            );
        }
        requireSameType(
            definition,
            "aggregator " + aggregator.id() + " plan input",
            definition.inputType(),
            aggregator.planInputType()
        );
        requireSameType(
            definition,
            "aggregator " + aggregator.id() + " plan output",
            definition.outputType(),
            aggregator.outputType()
        );
        Map<String, Class<?>> aggregateDependencies = normalizedDependencies(
            definition,
            "aggregator " + aggregator.id(),
            aggregator.requiredStepOutputs()
        );
        validateDependencies(
            definition,
            "aggregator " + aggregator.id(),
            aggregateDependencies,
            precedingOutputs
        );

        String declaration = String.join(
            "\n",
            definition.id().toString(),
            definition.inputType().getName(),
            definition.outputType().getName(),
            definition.maximumDuration().toString(),
            String.join("\n", fingerprintSteps),
            aggregator.id().toString(),
            aggregator.getClass().getName(),
            dependencyDeclaration(aggregateDependencies)
        );
        return new RegisteredExecutionPlan(
            definition,
            CanonicalJsonSupport.sha256(declaration)
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void bindStep(
        SpecialistClientFactory specialistClientFactory,
        SpecialistPlanStep step
    ) {
        specialistClientFactory.bind(
            step.specialistId(),
            (Class) step.inputType(),
            (Class) step.outputType()
        );
    }

    private Map<String, Class<?>> normalizedDependencies(
        ExecutionPlanDefinition<?, ?> definition,
        String owner,
        Map<String, Class<?>> dependencies
    ) {
        if (dependencies == null || dependencies.isEmpty()) {
            return Map.of();
        }
        Map<String, Class<?>> normalized = new LinkedHashMap<>();
        dependencies.forEach((stepId, type) -> {
            if (stepId == null || stepId.isBlank()) {
                throw invalid(
                    definition,
                    owner + " declares a blank output dependency"
                );
            }
            if (type == null) {
                throw invalid(
                    definition,
                    owner + " declares a null output type for " + stepId
                );
            }
            String id = stepId.trim();
            if (normalized.putIfAbsent(id, type) != null) {
                throw invalid(
                    definition,
                    owner + " declares duplicate output dependency " + id
                );
            }
        });
        return Map.copyOf(normalized);
    }

    private void validateDependencies(
        ExecutionPlanDefinition<?, ?> definition,
        String owner,
        Map<String, Class<?>> dependencies,
        Map<String, Class<?>> availableOutputs
    ) {
        dependencies.forEach((stepId, expectedType) -> {
            Class<?> actualType = availableOutputs.get(stepId);
            if (actualType == null) {
                throw invalid(
                    definition,
                    owner + " references unavailable step output " + stepId
                );
            }
            requireSameType(
                definition,
                owner + " output dependency " + stepId,
                actualType,
                expectedType
            );
        });
    }

    private void requireSameType(
        ExecutionPlanDefinition<?, ?> definition,
        String relationship,
        Class<?> expected,
        Class<?> actual
    ) {
        if (!expected.equals(actual)) {
            throw invalid(
                definition,
                relationship + " must be " + expected.getName()
                    + " but was " + actual.getName()
            );
        }
    }

    private String dependencyDeclaration(Map<String, Class<?>> dependencies) {
        Map<String, String> sorted = new TreeMap<>();
        dependencies.forEach((stepId, type) ->
            sorted.put(stepId, type.getName())
        );
        return sorted.toString();
    }

    private IllegalArgumentException invalid(
        ExecutionPlanDefinition<?, ?> definition,
        String reason
    ) {
        return new IllegalArgumentException(
            "Execution plan " + definition.id() + " " + reason
        );
    }
}
