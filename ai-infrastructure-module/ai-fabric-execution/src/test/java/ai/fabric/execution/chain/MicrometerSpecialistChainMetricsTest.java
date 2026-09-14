package ai.fabric.execution.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.execution.chain.state.SpecialistChainExecutionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MicrometerSpecialistChainMetricsTest {

    private static final SpecialistChainId CHAIN =
        SpecialistChainId.of("incident-investigation", "1");

    @Test
    void recordsLifecycleDecisionsWorkersAndModelCalls() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SpecialistChainExecutionRepository repository = mock(
            SpecialistChainExecutionRepository.class
        );
        when(repository.countActive()).thenReturn(3L);
        MicrometerSpecialistChainMetrics metrics =
            new MicrometerSpecialistChainMetrics(registry, repository);

        metrics.started(CHAIN, true);
        metrics.managerDecision(
            CHAIN,
            SpecialistChainDirectiveType.INVOKE_PARALLEL
        );
        metrics.modelCall(CHAIN, "manager", "incident-manager@1");
        metrics.modelCall(CHAIN, "worker", "health-reader@1");
        metrics.managerCorrection(CHAIN, "CHAIN_NO_PROGRESS");
        metrics.workerSelected(CHAIN, "health-reader@1", true);
        metrics.parallelGroup(CHAIN, 2, Duration.ofMillis(25));
        metrics.terminal(
            CHAIN,
            SpecialistChainExecutionStatus.COMPLETED,
            Duration.ofMillis(40)
        );
        metrics.replayed(CHAIN);

        assertThat(registry.get("ai.fabric.specialist.chain.active")
            .gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("ai.fabric.specialist.chain.started")
            .tags("chain", CHAIN.toString(), "durability", "durable")
            .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(
            "ai.fabric.specialist.chain.manager.decisions"
        ).tags(
            "chain", CHAIN.toString(),
            "directive", "INVOKE_PARALLEL"
        ).counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ai.fabric.specialist.chain.model.calls")
            .tags(
                "chain", CHAIN.toString(),
                "role", "manager",
                "specialist", "incident-manager@1"
            ).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(
            "ai.fabric.specialist.chain.manager.corrections"
        ).tags(
            "chain", CHAIN.toString(),
            "reason", "CHAIN_NO_PROGRESS"
        ).counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ai.fabric.specialist.chain.model.calls")
            .tags(
                "chain", CHAIN.toString(),
                "role", "worker",
                "specialist", "health-reader@1"
            ).counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ai.fabric.specialist.chain.workers")
            .tags(
                "chain", CHAIN.toString(),
                "target", "health-reader@1",
                "parallel", "true"
            ).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(
            "ai.fabric.specialist.chain.parallel.duration"
        ).tags("chain", CHAIN.toString(), "size", "2")
            .timer().count()).isEqualTo(1L);
        assertThat(registry.get("ai.fabric.specialist.chain.duration")
            .tags("chain", CHAIN.toString(), "status", "COMPLETED")
            .timer().count()).isEqualTo(1L);
        assertThat(registry.get("ai.fabric.specialist.chain.completed")
            .tags("chain", CHAIN.toString(), "status", "COMPLETED")
            .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ai.fabric.specialist.chain.replays")
            .tag("chain", CHAIN.toString())
            .counter().count()).isEqualTo(1.0);
    }
}
