package dev.aifabric.examples.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionResumeResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.gateway.DefaultAIExecutionCoordinator;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.plan.ExecutionPlanDefinition;
import ai.fabric.execution.plan.ExecutionPlanId;
import ai.fabric.execution.plan.ExecutionPlanRegistry;
import ai.fabric.execution.plan.PlanComponentRegistry;
import ai.fabric.execution.plan.PlanExecutionRequest;
import ai.fabric.execution.plan.PlanExecutionResult;
import ai.fabric.execution.plan.PlanExecutionStatus;
import ai.fabric.execution.plan.PlanResultAggregator;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.plan.RegisteredExecutionPlan;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistExecutionSnapshot;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import ai.fabric.execution.specialist.client.SpecialistResumeInvocation;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aifabric.examples.consumer.SupportPlanConfiguration.AccountSnapshot;
import dev.aifabric.examples.consumer.SupportPlanConfiguration.PolicySnapshot;
import dev.aifabric.examples.consumer.SupportPlanConfiguration.SupportDecision;
import dev.aifabric.examples.consumer.SupportPlanConfiguration.SupportRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class PublicParallelPlanRuntimeTest {

    private final ThreadPoolTaskExecutor executor = executor();

    @AfterEach
    void shutdownExecutor() {
        executor.shutdown();
    }

    @Test
    void executesPublicParallelContractWithAtomicTypedFanIn() {
        SupportPlanConfiguration configuration =
            new SupportPlanConfiguration();
        ExecutionPlanDefinition<SupportRequest, SupportDecision> plan =
            configuration.parallelSupportPlan();
        CountDownLatch started = new CountDownLatch(2);
        SpecialistClientFactory clients = clients(started);
        RegisteredExecutionPlan registered = new RegisteredExecutionPlan(
            plan,
            CanonicalJsonSupport.sha256("external-consumer-parallel-plan")
        );
        ExecutionPlanRegistry plans = registry(registered);
        AIExecutionProperties.Plans properties =
            new AIExecutionProperties.Plans();
        properties.setParallelEnabled(true);
        properties.setMaxParallelBranches(2);

        DefaultAIExecutionCoordinator coordinator =
            new DefaultAIExecutionCoordinator(
                plans,
                new PlanComponentRegistry(
                    List.of(
                        configuration.accountInputMapper(),
                        configuration.policyInputMapper()
                    ),
                    List.<PlanResultAggregator<?, ?>>of(
                        configuration.supportResultAggregator()
                    )
                ),
                mock(AIExecutionGateway.class),
                clients,
                executor,
                new CanonicalJsonSupport(new ObjectMapper()),
                Clock.systemUTC(),
                properties
            );

        PlanExecutionResult<SupportDecision> result = coordinator.execute(
            PlanExecutionRequest.synchronous(
                SupportPlanConfiguration.PARALLEL_PLAN,
                new SupportRequest("Can this account continue?"),
                trustedContext()
            )
        );

        assertThat(result.status())
            .isEqualTo(PlanExecutionStatus.SUCCEEDED);
        assertThat(result.output())
            .isEqualTo(new SupportDecision(
                false,
                "A verified payment method is required."
            ));
        assertThat(result.steps())
            .extracting("stepId")
            .containsExactly("account-state", "policy-state");
        assertThat(result.steps())
            .extracting("parallelGroupId")
            .containsOnly("independent-readers");
        assertThat(result.steps())
            .extracting("sourceRevision")
            .doesNotContainNull()
            .containsOnly(result.steps().get(0).sourceRevision());
        assertThat(overlap(result)).isTrue();
    }

    private SpecialistClientFactory clients(CountDownLatch started) {
        return new SpecialistClientFactory() {
            @Override
            public <I, O> SpecialistClient<I, O> bind(
                SpecialistId specialistId,
                Class<I> inputType,
                Class<O> outputType
            ) {
                return new SpecialistClient<>() {
                    @Override
                    public SpecialistId specialistId() {
                        return specialistId;
                    }

                    @Override
                    public AIExecutionResult<O> execute(
                        SpecialistInvocation<I> invocation
                    ) {
                        Instant startedAt = Instant.now();
                        started.countDown();
                        awaitBoth(started);
                        Object output = specialistId.equals(
                            SupportPlanConfiguration.ACCOUNT_READER
                        )
                            ? new AccountSnapshot("ACTIVE", false)
                            : new PolicySnapshot(
                                true,
                                "A verified payment method is required."
                            );
                        Instant completedAt = Instant.now();
                        return new AIExecutionResult<>(
                            "consumer-" + specialistId.name(),
                            specialistId,
                            AIExecutionStatus.SUCCEEDED,
                            outputType.cast(output),
                            List.of(),
                            Map.of(),
                            null,
                            startedAt,
                            completedAt
                        );
                    }

                    @Override
                    public AIExecutionResumeResult<O> resume(
                        SpecialistResumeInvocation invocation
                    ) {
                        throw new UnsupportedOperationException(
                            "The consumer proof has no input waits"
                        );
                    }

                    @Override
                    public ExecutionHandle submit(
                        SpecialistInvocation<I> invocation
                    ) {
                        throw new UnsupportedOperationException(
                            "The consumer proof is synchronous"
                        );
                    }

                    @Override
                    public Optional<SpecialistExecutionSnapshot<O>> find(
                        String invocationId,
                        TrustedExecutionContext trustedExecutionContext
                    ) {
                        return Optional.empty();
                    }

                    @Override
                    public boolean cancel(
                        String invocationId,
                        TrustedExecutionContext trustedExecutionContext
                    ) {
                        return false;
                    }
                };
            }
        };
    }

    private void awaitBoth(CountDownLatch started) {
        try {
            if (!started.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                    "Parallel branches did not overlap"
                );
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Consumer proof was interrupted",
                ex
            );
        }
    }

    private ExecutionPlanRegistry registry(
        RegisteredExecutionPlan registered
    ) {
        return new ExecutionPlanRegistry() {
            @Override
            public Optional<RegisteredExecutionPlan> find(
                ExecutionPlanId id
            ) {
                return registered.id().equals(id)
                    ? Optional.of(registered)
                    : Optional.empty();
            }

            @Override
            public List<RegisteredExecutionPlan> list() {
                return List.of(registered);
            }
        };
    }

    private boolean overlap(PlanExecutionResult<?> result) {
        return result.steps().get(0).startedAt().isBefore(
            result.steps().get(1).completedAt()
        ) && result.steps().get(1).startedAt().isBefore(
            result.steps().get(0).completedAt()
        );
    }

    private TrustedExecutionContext trustedContext() {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                "consumer-service",
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef("account", "account-1"),
            ExecutionSource.APPLICATION,
            "tenant-1",
            "consumer-deployment",
            Set.of("specialist:*"),
            "consumer-correlation",
            Instant.now()
        );
    }

    private ThreadPoolTaskExecutor executor() {
        ThreadPoolTaskExecutor taskExecutor =
            new ThreadPoolTaskExecutor();
        taskExecutor.setThreadNamePrefix("consumer-plan-");
        taskExecutor.setCorePoolSize(2);
        taskExecutor.setMaxPoolSize(2);
        taskExecutor.setQueueCapacity(0);
        taskExecutor.setWaitForTasksToCompleteOnShutdown(false);
        taskExecutor.initialize();
        return taskExecutor;
    }
}
