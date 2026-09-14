package ai.fabric.execution.chain;

import java.time.Duration;

/** Low-cardinality metrics for bounded chain execution. */
public interface SpecialistChainMetrics {

    void started(SpecialistChainId chainId, boolean durable);

    void managerDecision(
        SpecialistChainId chainId,
        SpecialistChainDirectiveType type
    );

    void modelCall(
        SpecialistChainId chainId,
        String role,
        String specialist
    );

    void managerCorrection(SpecialistChainId chainId, String reason);

    void workerSelected(
        SpecialistChainId chainId,
        String target,
        boolean parallel
    );

    void parallelGroup(SpecialistChainId chainId, int size, Duration duration);

    void terminal(
        SpecialistChainId chainId,
        SpecialistChainExecutionStatus status,
        Duration duration
    );

    void replayed(SpecialistChainId chainId);

    static SpecialistChainMetrics noop() {
        return NoopSpecialistChainMetrics.INSTANCE;
    }

    enum NoopSpecialistChainMetrics implements SpecialistChainMetrics {
        INSTANCE;

        @Override
        public void started(SpecialistChainId chainId, boolean durable) {}

        @Override
        public void managerDecision(
            SpecialistChainId chainId,
            SpecialistChainDirectiveType type
        ) {}

        @Override
        public void modelCall(
            SpecialistChainId chainId,
            String role,
            String specialist
        ) {}

        @Override
        public void managerCorrection(
            SpecialistChainId chainId,
            String reason
        ) {}

        @Override
        public void workerSelected(
            SpecialistChainId chainId,
            String target,
            boolean parallel
        ) {}

        @Override
        public void parallelGroup(
            SpecialistChainId chainId,
            int size,
            Duration duration
        ) {}

        @Override
        public void terminal(
            SpecialistChainId chainId,
            SpecialistChainExecutionStatus status,
            Duration duration
        ) {}

        @Override
        public void replayed(SpecialistChainId chainId) {}
    }
}
