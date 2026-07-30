package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.execution.action.ActionProposalView;
import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.input.InputDeliveryTarget;
import ai.fabric.execution.input.NeedsUserInput;
import ai.fabric.execution.input.SpecialistInputResponseContract;
import ai.fabric.execution.plan.ExecutionPlanDefinition;
import ai.fabric.execution.plan.ExecutionPlanId;
import ai.fabric.execution.plan.ExecutionPlanRegistry;
import ai.fabric.execution.plan.FanInPolicy;
import ai.fabric.execution.plan.ParallelPlanStep;
import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanComponentRegistry;
import ai.fabric.execution.plan.PlanExecutionRequest;
import ai.fabric.execution.plan.PlanExecutionResult;
import ai.fabric.execution.plan.PlanExecutionStatus;
import ai.fabric.execution.plan.PlanResultAggregator;
import ai.fabric.execution.plan.PlanStage;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.plan.PlanStepOutputs;
import ai.fabric.execution.plan.RegisteredExecutionPlan;
import ai.fabric.execution.plan.SpecialistPlanStep;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistExecutionSnapshot;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import ai.fabric.execution.specialist.client.SpecialistResumeInvocation;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class DefaultAIExecutionCoordinatorParallelTest {

    private static final Instant NOW = Instant.parse(
        "2026-07-29T10:00:00Z"
    );
    private static final ExecutionPlanId PLAN_ID =
        ExecutionPlanId.of("parallel-account-assessment", "1");
    private static final SpecialistId PROFILE =
        SpecialistId.of("profile-reader", "1");
    private static final SpecialistId POLICY =
        SpecialistId.of("policy-reader", "1");
    private static final PlanComponentId PROFILE_MAPPER =
        PlanComponentId.of("profile-input", "1");
    private static final PlanComponentId POLICY_MAPPER =
        PlanComponentId.of("policy-input", "1");
    private static final PlanComponentId AGGREGATOR =
        PlanComponentId.of("parallel-result", "1");
    private static final String PARALLEL_GROUP = "independent-readers";

    private final List<ThreadPoolTaskExecutor> executors =
        new ArrayList<>();

    @AfterEach
    void shutdownExecutors() {
        executors.forEach(ThreadPoolTaskExecutor::shutdown);
    }

    @Test
    void overlapsIndependentBranchesAndCommitsInDeclarationOrder()
        throws Exception {
        AIExecutionGateway gateway = mock(AIExecutionGateway.class);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<SpecialistId> completionOrder =
            Collections.synchronizedList(new ArrayList<>());
        when(gateway.execute(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            started.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release timed out");
            }
            if (request.specialistId().equals(PROFILE)) {
                Thread.sleep(75);
                completionOrder.add(PROFILE);
                return success("profile-invocation", PROFILE, 7);
            }
            completionOrder.add(POLICY);
            return success("policy-invocation", POLICY, true);
        });
        DefaultAIExecutionCoordinator coordinator = coordinator(
            gateway,
            executor(2)
        );
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<PlanExecutionResult<ParallelOutput>> pending =
                caller.submit(() -> coordinator.execute(request()));

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            PlanExecutionResult<ParallelOutput> result =
                pending.get(3, TimeUnit.SECONDS);

            assertThat(result.status())
                .isEqualTo(PlanExecutionStatus.SUCCEEDED);
            assertThat(result.output())
                .isEqualTo(new ParallelOutput(7, true));
            assertThat(completionOrder)
                .containsExactly(POLICY, PROFILE);
            assertThat(result.steps())
                .extracting("stepId")
                .containsExactly("profile", "policy");
            assertThat(result.steps())
                .extracting("parallelGroupId")
                .containsOnly(PARALLEL_GROUP);
            assertThat(result.steps())
                .extracting("sourceRevision")
                .doesNotContainNull()
                .allSatisfy(value ->
                    assertThat((String) value).hasSize(64)
                );
            assertThat(result.diagnostics())
                .containsEntry("completedSteps", 2)
                .containsEntry("stepCount", 2);
            verify(gateway, times(2)).execute(any());
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void allRequiredFailureCancelsSiblingAndCommitsNoPartialOutput()
        throws Exception {
        AIExecutionGateway gateway = mock(AIExecutionGateway.class);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(gateway.execute(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            started.countDown();
            if (request.specialistId().equals(PROFILE)) {
                try {
                    blocked.await();
                    return success("profile-invocation", PROFILE, 7);
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cancelled", ex);
                }
            }
            if (!started.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("parallel start timed out");
            }
            return failure(
                "policy-invocation",
                POLICY,
                "POLICY_UNAVAILABLE"
            );
        });
        DefaultAIExecutionCoordinator coordinator = coordinator(
            gateway,
            executor(2)
        );

        PlanExecutionResult<ParallelOutput> result =
            coordinator.execute(request());

        assertThat(result.status())
            .isEqualTo(PlanExecutionStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("POLICY_UNAVAILABLE");
        assertThat(result.failure().stepId()).isEqualTo("policy");
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.find(result.executionId(), trusted()))
            .get()
            .satisfies(snapshot ->
                assertThat(snapshot.completedSteps()).isZero()
            );
    }

    @Test
    void deadlineCancelsOutstandingBranchAndCommitsNoPartialOutput()
        throws Exception {
        AIExecutionGateway gateway = mock(AIExecutionGateway.class);
        CountDownLatch policyStarted = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(gateway.execute(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            if (request.specialistId().equals(PROFILE)) {
                return success("profile-invocation", PROFILE, 7);
            }
            policyStarted.countDown();
            try {
                blocked.await();
                return success("policy-invocation", POLICY, true);
            } catch (InterruptedException ex) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw ex;
            }
        });
        DefaultAIExecutionCoordinator coordinator = coordinator(
            gateway,
            executor(2),
            Clock.systemUTC(),
            Duration.ofMillis(250)
        );

        PlanExecutionResult<ParallelOutput> result =
            coordinator.execute(request());

        assertThat(policyStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.status())
            .isEqualTo(PlanExecutionStatus.DEADLINE_EXCEEDED);
        assertThat(result.failure().reason())
            .isEqualTo("PLAN_DEADLINE_EXCEEDED");
        assertThat(result.output()).isNull();
        assertThat(result.steps())
            .extracting("stepId")
            .containsExactly("profile");
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.find(result.executionId(), trusted()))
            .get()
            .satisfies(snapshot ->
                assertThat(snapshot.completedSteps()).isZero()
            );
    }

    @Test
    void failsClosedWhenParallelBranchRequestsInputOrWriteConfirmation() {
        AIExecutionGateway waitingGateway = mock(AIExecutionGateway.class);
        when(waitingGateway.execute(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            return request.specialistId().equals(PROFILE)
                ? waiting("profile-invocation", PROFILE)
                : success("policy-invocation", POLICY, true);
        });

        PlanExecutionResult<ParallelOutput> waiting = coordinator(
            waitingGateway,
            executor(2)
        ).execute(request());

        assertThat(waiting.status())
            .isEqualTo(PlanExecutionStatus.FAILED);
        assertThat(waiting.failure().reason())
            .isEqualTo("PLAN_PARALLEL_INPUT_WAIT_UNSUPPORTED");
        assertThat(waiting.needsUserInput()).isNull();

        AIExecutionGateway writeGateway = mock(AIExecutionGateway.class);
        when(writeGateway.execute(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            return request.specialistId().equals(PROFILE)
                ? confirmation("profile-invocation", PROFILE)
                : success("policy-invocation", POLICY, true);
        });

        PlanExecutionResult<ParallelOutput> write = coordinator(
            writeGateway,
            executor(2)
        ).execute(request());

        assertThat(write.status()).isEqualTo(PlanExecutionStatus.FAILED);
        assertThat(write.failure().reason())
            .isEqualTo("PLAN_PARALLEL_WRITE_PROPOSAL_UNSUPPORTED");
        assertThat(write.output()).isNull();
    }

    @Test
    void exposesExecutorCapacityRejectionWithoutSequentialFallback()
        throws Exception {
        AIExecutionGateway gateway = mock(AIExecutionGateway.class);
        CountDownLatch blocked = new CountDownLatch(1);
        when(gateway.execute(any())).thenAnswer(invocation -> {
            blocked.await();
            AIExecutionRequest<?> request = invocation.getArgument(0);
            return request.specialistId().equals(PROFILE)
                ? success("profile-invocation", PROFILE, 7)
                : success("policy-invocation", POLICY, true);
        });

        PlanExecutionResult<ParallelOutput> result = coordinator(
            gateway,
            executor(1)
        ).execute(request());

        assertThat(result.status()).isEqualTo(PlanExecutionStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("PLAN_PARALLEL_CAPACITY_REJECTED");
        assertThat(result.failure().retryable()).isTrue();
        assertThat(result.steps()).isEmpty();
        verify(gateway, atMost(1)).execute(any());
        blocked.countDown();
    }

    private DefaultAIExecutionCoordinator coordinator(
        AIExecutionGateway gateway,
        ThreadPoolTaskExecutor executor
    ) {
        return coordinator(
            gateway,
            executor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofSeconds(30)
        );
    }

    private DefaultAIExecutionCoordinator coordinator(
        AIExecutionGateway gateway,
        ThreadPoolTaskExecutor executor,
        Clock clock,
        Duration maximumDuration
    ) {
        ExecutionPlanDefinition<ParallelInput, ParallelOutput> definition =
            definition(maximumDuration);
        RegisteredExecutionPlan registered = new RegisteredExecutionPlan(
            definition,
            CanonicalJsonSupport.sha256("parallel-account-assessment")
        );
        ExecutionPlanRegistry registry = new ExecutionPlanRegistry() {
            @Override
            public Optional<RegisteredExecutionPlan> find(
                ExecutionPlanId id
            ) {
                return PLAN_ID.equals(id)
                    ? Optional.of(registered)
                    : Optional.empty();
            }

            @Override
            public List<RegisteredExecutionPlan> list() {
                return List.of(registered);
            }
        };
        AIExecutionProperties.Plans properties =
            new AIExecutionProperties.Plans();
        properties.setParallelEnabled(true);
        properties.setMaxParallelBranches(2);
        return new DefaultAIExecutionCoordinator(
            registry,
            new PlanComponentRegistry(
                List.of(mapper(PROFILE_MAPPER), mapper(POLICY_MAPPER)),
                List.of(aggregator())
            ),
            gateway,
            forwardingClientFactory(gateway),
            executor,
            new CanonicalJsonSupport(new ObjectMapper()),
            clock,
            properties
        );
    }

    private ExecutionPlanDefinition<ParallelInput, ParallelOutput>
        definition() {
        return definition(Duration.ofSeconds(30));
    }

    private ExecutionPlanDefinition<ParallelInput, ParallelOutput> definition(
        Duration maximumDuration
    ) {
        return new ExecutionPlanDefinition<>(
            PLAN_ID,
            ParallelInput.class,
            ParallelOutput.class,
            List.<PlanStage>of(new ParallelPlanStep(
                PARALLEL_GROUP,
                List.of(
                    new SpecialistPlanStep(
                        "profile",
                        PROFILE,
                        String.class,
                        Integer.class,
                        PROFILE_MAPPER
                    ),
                    new SpecialistPlanStep(
                        "policy",
                        POLICY,
                        String.class,
                        Boolean.class,
                        POLICY_MAPPER
                    )
                ),
                FanInPolicy.ALL_REQUIRED,
                2
            )),
            AGGREGATOR,
            maximumDuration
        );
    }

    private PlanStepInputMapper<ParallelInput, String> mapper(
        PlanComponentId id
    ) {
        return new PlanStepInputMapper<>() {
            @Override
            public PlanComponentId id() {
                return id;
            }

            @Override
            public Class<ParallelInput> planInputType() {
                return ParallelInput.class;
            }

            @Override
            public Class<String> stepInputType() {
                return String.class;
            }

            @Override
            public String map(
                ParallelInput planInput,
                PlanStepOutputs approvedOutputs
            ) {
                assertThat(approvedOutputs.size()).isZero();
                return planInput.question();
            }
        };
    }

    private PlanResultAggregator<ParallelInput, ParallelOutput>
        aggregator() {
        return new PlanResultAggregator<>() {
            @Override
            public PlanComponentId id() {
                return AGGREGATOR;
            }

            @Override
            public Class<ParallelInput> planInputType() {
                return ParallelInput.class;
            }

            @Override
            public Class<ParallelOutput> outputType() {
                return ParallelOutput.class;
            }

            @Override
            public Map<String, Class<?>> requiredStepOutputs() {
                return Map.of(
                    "profile",
                    Integer.class,
                    "policy",
                    Boolean.class
                );
            }

            @Override
            public ParallelOutput aggregate(
                ParallelInput planInput,
                PlanStepOutputs approvedOutputs
            ) {
                return new ParallelOutput(
                    approvedOutputs.require("profile", Integer.class),
                    approvedOutputs.require("policy", Boolean.class)
                );
            }
        };
    }

    private SpecialistClientFactory forwardingClientFactory(
        AIExecutionGateway gateway
    ) {
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
                        return gateway.execute(new AIExecutionRequest<>(
                            specialistId,
                            inputType.cast(invocation.input()),
                            invocation.trustedExecutionContext(),
                            invocation.conversationBinding(),
                            invocation.deadline(),
                            invocation.idempotencyKey()
                        ));
                    }

                    @Override
                    public AIExecutionResumeResult<O> resume(
                        SpecialistResumeInvocation invocation
                    ) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public ExecutionHandle submit(
                        SpecialistInvocation<I> invocation
                    ) {
                        throw new UnsupportedOperationException();
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

    private ThreadPoolTaskExecutor executor(int workers) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("parallel-plan-test-");
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(0);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        executors.add(executor);
        return executor;
    }

    private PlanExecutionRequest<ParallelInput> request() {
        return PlanExecutionRequest.synchronous(
            PLAN_ID,
            new ParallelInput("assess account"),
            trusted()
        );
    }

    private TrustedExecutionContext trusted() {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                "service-a",
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef("account", "account-1"),
            ExecutionSource.APPLICATION,
            "tenant",
            "deployment",
            Set.of("specialist:*"),
            "correlation",
            NOW
        );
    }

    private <O> AIExecutionResult<O> success(
        String invocationId,
        SpecialistId specialistId,
        O output
    ) {
        return new AIExecutionResult<>(
            invocationId,
            specialistId,
            AIExecutionStatus.SUCCEEDED,
            output,
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW.plusMillis(10)
        );
    }

    private AIExecutionResult<?> failure(
        String invocationId,
        SpecialistId specialistId,
        String reason
    ) {
        return new AIExecutionResult<>(
            invocationId,
            specialistId,
            AIExecutionStatus.FAILED,
            null,
            List.of(),
            Map.of(),
            new AIExecutionFailure(
                reason,
                "The parallel reader is unavailable.",
                true
            ),
            NOW,
            NOW.plusMillis(10)
        );
    }

    private AIExecutionResult<?> waiting(
        String invocationId,
        SpecialistId specialistId
    ) {
        NeedsUserInput wait = new NeedsUserInput(
            "parallel-input-request",
            invocationId,
            specialistId,
            "MISSING_INPUT",
            "Provide missing input.",
            new SpecialistInputResponseContract(
                new SpecialistSchemaId("parallel-input", "1"),
                Map.of("type", "object")
            ),
            InputDeliveryTarget.HOST_APPLICATION,
            ExecutionDurability.EPHEMERAL,
            NOW,
            NOW.plusSeconds(20),
            1
        );
        return new AIExecutionResult<>(
            invocationId,
            specialistId,
            AIExecutionStatus.WAITING_FOR_INPUT,
            null,
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW.plusMillis(10),
            null,
            wait
        );
    }

    private AIExecutionResult<?> confirmation(
        String invocationId,
        SpecialistId specialistId
    ) {
        return new AIExecutionResult<>(
            invocationId,
            specialistId,
            AIExecutionStatus.CONFIRMATION_REQUIRED,
            null,
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW.plusMillis(10),
            mock(ActionProposalView.class)
        );
    }

    private record ParallelInput(String question) {}

    private record ParallelOutput(int profileScore, boolean policyAllows) {}
}
