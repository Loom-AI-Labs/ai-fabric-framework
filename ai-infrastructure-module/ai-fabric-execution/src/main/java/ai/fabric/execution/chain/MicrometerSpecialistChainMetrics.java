package ai.fabric.execution.chain;

import ai.fabric.execution.chain.state.SpecialistChainExecutionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;

/** Micrometer implementation using bounded chain and status tags. */
public final class MicrometerSpecialistChainMetrics
    implements SpecialistChainMetrics {

    private final MeterRegistry registry;

    public MicrometerSpecialistChainMetrics(
        MeterRegistry registry,
        SpecialistChainExecutionRepository repository
    ) {
        this.registry = Objects.requireNonNull(
            registry,
            "registry is required"
        );
        Objects.requireNonNull(repository, "repository is required");
        registry.gauge(
            "ai.fabric.specialist.chain.active",
            repository,
            SpecialistChainExecutionRepository::countActive
        );
    }

    @Override
    public void started(SpecialistChainId chainId, boolean durable) {
        registry.counter(
            "ai.fabric.specialist.chain.started",
            "chain",
            chainId.toString(),
            "durability",
            durable ? "durable" : "ephemeral"
        ).increment();
    }

    @Override
    public void managerDecision(
        SpecialistChainId chainId,
        SpecialistChainDirectiveType type
    ) {
        registry.counter(
            "ai.fabric.specialist.chain.manager.decisions",
            "chain",
            chainId.toString(),
            "directive",
            type.name()
        ).increment();
    }

    @Override
    public void modelCall(
        SpecialistChainId chainId,
        String role,
        String specialist
    ) {
        registry.counter(
            "ai.fabric.specialist.chain.model.calls",
            "chain",
            chainId.toString(),
            "role",
            role,
            "specialist",
            specialist
        ).increment();
    }

    @Override
    public void managerCorrection(
        SpecialistChainId chainId,
        String reason
    ) {
        registry.counter(
            "ai.fabric.specialist.chain.manager.corrections",
            "chain",
            chainId.toString(),
            "reason",
            reason
        ).increment();
    }

    @Override
    public void workerSelected(
        SpecialistChainId chainId,
        String target,
        boolean parallel
    ) {
        registry.counter(
            "ai.fabric.specialist.chain.workers",
            "chain",
            chainId.toString(),
            "target",
            target,
            "parallel",
            Boolean.toString(parallel)
        ).increment();
    }

    @Override
    public void parallelGroup(
        SpecialistChainId chainId,
        int size,
        Duration duration
    ) {
        Timer.builder("ai.fabric.specialist.chain.parallel.duration")
            .tag("chain", chainId.toString())
            .tag("size", Integer.toString(size))
            .register(registry)
            .record(duration);
    }

    @Override
    public void terminal(
        SpecialistChainId chainId,
        SpecialistChainExecutionStatus status,
        Duration duration
    ) {
        Timer.builder("ai.fabric.specialist.chain.duration")
            .tag("chain", chainId.toString())
            .tag("status", status.name())
            .register(registry)
            .record(duration);
        registry.counter(
            "ai.fabric.specialist.chain.completed",
            "chain",
            chainId.toString(),
            "status",
            status.name()
        ).increment();
    }

    @Override
    public void replayed(SpecialistChainId chainId) {
        registry.counter(
            "ai.fabric.specialist.chain.replays",
            "chain",
            chainId.toString()
        ).increment();
    }
}
