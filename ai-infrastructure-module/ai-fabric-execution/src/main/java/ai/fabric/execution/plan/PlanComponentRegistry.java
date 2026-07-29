package ai.fabric.execution.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact-version registry for deterministic plan mappers and aggregators.
 */
public final class PlanComponentRegistry {

    private final Map<PlanComponentId, PlanStepInputMapper<?, ?>> mappers;
    private final Map<PlanComponentId, PlanResultAggregator<?, ?>> aggregators;

    public PlanComponentRegistry(
        List<PlanStepInputMapper<?, ?>> mappers,
        List<PlanResultAggregator<?, ?>> aggregators
    ) {
        this.mappers = index(
            mappers,
            PlanStepInputMapper::id,
            "input mapper"
        );
        this.aggregators = index(
            aggregators,
            PlanResultAggregator::id,
            "result aggregator"
        );
    }

    public Optional<PlanStepInputMapper<?, ?>> findMapper(
        PlanComponentId id
    ) {
        return Optional.ofNullable(mappers.get(id));
    }

    public PlanStepInputMapper<?, ?> requireMapper(PlanComponentId id) {
        return findMapper(id).orElseThrow(() ->
            new PlanComponentNotFoundException(
                "No plan input mapper is registered for " + id
            )
        );
    }

    public Optional<PlanResultAggregator<?, ?>> findAggregator(
        PlanComponentId id
    ) {
        return Optional.ofNullable(aggregators.get(id));
    }

    public PlanResultAggregator<?, ?> requireAggregator(PlanComponentId id) {
        return findAggregator(id).orElseThrow(() ->
            new PlanComponentNotFoundException(
                "No plan result aggregator is registered for " + id
            )
        );
    }

    public List<PlanStepInputMapper<?, ?>> mappers() {
        return List.copyOf(mappers.values());
    }

    public List<PlanResultAggregator<?, ?>> aggregators() {
        return List.copyOf(aggregators.values());
    }

    private static <T> Map<PlanComponentId, T> index(
        List<T> values,
        java.util.function.Function<T, PlanComponentId> id,
        String componentType
    ) {
        Map<PlanComponentId, T> indexed = new LinkedHashMap<>();
        if (values == null) {
            return Map.of();
        }
        for (T value : values) {
            T component = Objects.requireNonNull(
                value,
                componentType + " is required"
            );
            PlanComponentId componentId = Objects.requireNonNull(
                id.apply(component),
                componentType + " id is required"
            );
            if (indexed.putIfAbsent(componentId, component) != null) {
                throw new IllegalArgumentException(
                    "Duplicate plan " + componentType + " " + componentId
                );
            }
        }
        return Map.copyOf(indexed);
    }

    public static final class PlanComponentNotFoundException
        extends RuntimeException {

        public PlanComponentNotFoundException(String message) {
            super(message);
        }
    }
}
