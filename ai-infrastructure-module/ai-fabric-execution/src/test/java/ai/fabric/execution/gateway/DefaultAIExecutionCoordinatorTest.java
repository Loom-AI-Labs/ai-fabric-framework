package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanComponentRegistry;
import ai.fabric.execution.plan.PlanExecutionRequest;
import ai.fabric.execution.plan.PlanExecutionResult;
import ai.fabric.execution.plan.PlanExecutionResumeRequest;
import ai.fabric.execution.plan.PlanExecutionResumeStatus;
import ai.fabric.execution.plan.PlanExecutionStatus;
import ai.fabric.execution.plan.PlanResultAggregator;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultAIExecutionCoordinatorTest {

    private static final Instant NOW = Instant.parse(
        "2026-07-29T10:00:00Z"
    );
    private static final ExecutionPlanId PLAN_ID =
        ExecutionPlanId.of("account-billing", "1");
    private static final SpecialistId FIRST =
        SpecialistId.of("account-state", "1");
    private static final SpecialistId SECOND =
        SpecialistId.of("billing-path", "1");
    private static final PlanComponentId FIRST_MAPPER =
        PlanComponentId.of("account-state-input", "1");
    private static final PlanComponentId SECOND_MAPPER =
        PlanComponentId.of("billing-path-input", "1");
    private static final PlanComponentId AGGREGATOR =
        PlanComponentId.of("account-billing-result", "1");

    private AIExecutionGateway gateway;
    private AtomicInteger firstVisibleOutputs;
    private DefaultAIExecutionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        gateway = mock(AIExecutionGateway.class);
        firstVisibleOutputs = new AtomicInteger(-1);
        ExecutionPlanDefinition<PlanInput, PlanOutput> definition =
            definition();
        RegisteredExecutionPlan registered = new RegisteredExecutionPlan(
            definition,
            CanonicalJsonSupport.sha256("account-billing-plan")
        );
        ExecutionPlanRegistry plans = registry(registered);
        PlanComponentRegistry components = new PlanComponentRegistry(
            List.of(firstMapper(), secondMapper()),
            List.of(aggregator())
        );
        AIExecutionProperties.Plans properties =
            new AIExecutionProperties.Plans();
        properties.setMaxSteps(4);
        properties.setMaxDuration(java.time.Duration.ofMinutes(1));
        coordinator = new DefaultAIExecutionCoordinator(
            plans,
            components,
            gateway,
            forwardingClientFactory(gateway),
            new CanonicalJsonSupport(new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC),
            properties
        );
    }

    @Test
    void executesStepsInOrderWithIndependentGatewayRequests() {
        List<AIExecutionRequest<?>> requests = new ArrayList<>();
        when(gateway.execute(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            requests.add(request);
            if (request.specialistId().equals(FIRST)) {
                return success("first-invocation", FIRST, 7);
            }
            assertThat(request.input()).isEqualTo(
                new BillingInput("refund", 7)
            );
            return success("second-invocation", SECOND, true);
        });

        PlanExecutionResult<PlanOutput> result = coordinator.execute(
            PlanExecutionRequest.synchronous(
                PLAN_ID,
                new PlanInput("refund"),
                trusted("service-a", "account-1")
            )
        );

        assertThat(result.status()).isEqualTo(
            PlanExecutionStatus.SUCCEEDED
        );
        assertThat(result.output()).isEqualTo(
            new PlanOutput(7, true, "refund")
        );
        assertThat(result.steps()).extracting("stepId")
            .containsExactly("account-state", "billing-path");
        assertThat(firstVisibleOutputs).hasValue(0);
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.conversationBinding()).isNull();
            assertThat(request.deadline()).isEqualTo(
                NOW.plusSeconds(30)
            );
            assertThat(request.trustedExecutionContext())
                .isEqualTo(trusted("service-a", "account-1"));
            assertThat(request.idempotencyKey())
                .startsWith("plan-step-")
                .hasSize(74);
        });
        assertThat(requests.get(0).idempotencyKey())
            .isNotEqualTo(requests.get(1).idempotencyKey());
    }

    @Test
    void resumesWaitingSecondStepWithoutRerunningFirstAndReplaysResult() {
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        when(gateway.execute(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            if (request.specialistId().equals(FIRST)) {
                firstCalls.incrementAndGet();
                return success("first-invocation", FIRST, 9);
            }
            secondCalls.incrementAndGet();
            return waiting("second-invocation", SECOND, "amount-request");
        });
        when(gateway.resume(any())).thenAnswer(invocation ->
            AIExecutionResumeResult.resumed(
                success("second-invocation", SECOND, true)
            )
        );
        TrustedExecutionContext context = trusted(
            "service-a",
            "account-1"
        );

        PlanExecutionResult<PlanOutput> waiting = coordinator.execute(
            PlanExecutionRequest.synchronous(
                PLAN_ID,
                new PlanInput("refund"),
                context
            )
        );

        assertThat(waiting.status()).isEqualTo(
            PlanExecutionStatus.WAITING_FOR_INPUT
        );
        assertThat(waiting.needsUserInput().stepId())
            .isEqualTo("billing-path");
        assertThat(waiting.steps()).extracting("status")
            .containsExactly(
                AIExecutionStatus.SUCCEEDED,
                AIExecutionStatus.WAITING_FOR_INPUT
            );

        PlanExecutionResumeRequest resume = new PlanExecutionResumeRequest(
            waiting.executionId(),
            "amount-request",
            new AmountResponse(75),
            context,
            "resume-1"
        );
        var resumed = coordinator.<PlanOutput>resume(resume);

        assertThat(resumed.status()).isEqualTo(
            PlanExecutionResumeStatus.RESUMED
        );
        assertThat(resumed.executionResult().status()).isEqualTo(
            PlanExecutionStatus.SUCCEEDED
        );
        assertThat(resumed.executionResult().output()).isEqualTo(
            new PlanOutput(9, true, "refund")
        );
        assertThat(firstCalls).hasValue(1);
        assertThat(secondCalls).hasValue(1);
        verify(gateway, times(1)).resume(any());

        var replay = coordinator.<PlanOutput>resume(resume);
        assertThat(replay.status()).isEqualTo(
            PlanExecutionResumeStatus.REPLAYED
        );
        assertThat(replay.executionResult())
            .isEqualTo(resumed.executionResult());
        verify(gateway, times(1)).resume(any());

        var conflict = coordinator.<PlanOutput>resume(
            new PlanExecutionResumeRequest(
                waiting.executionId(),
                "amount-request",
                new AmountResponse(80),
                context,
                "resume-1"
            )
        );
        assertThat(conflict.status()).isEqualTo(
            PlanExecutionResumeStatus.REJECTED
        );
        assertThat(conflict.failure().reason())
            .isEqualTo("PLAN_INPUT_RESUME_CONFLICT");
    }

    @Test
    void deniesCrossContextResumeAndKeepsRetryableInvalidInputWaiting() {
        when(gateway.execute(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            return request.specialistId().equals(FIRST)
                ? success("first-invocation", FIRST, 3)
                : waiting(
                    "second-invocation",
                    SECOND,
                    "amount-request"
                );
        });
        when(gateway.resume(any())).thenReturn(
            AIExecutionResumeResult.rejected(
                AIExecutionResumeStatus.REJECTED,
                "INPUT_RESPONSE_INVALID",
                "The input response does not satisfy the required contract.",
                true
            )
        );
        TrustedExecutionContext owner = trusted(
            "service-a",
            "account-1"
        );
        PlanExecutionResult<PlanOutput> waiting = coordinator.execute(
            PlanExecutionRequest.synchronous(
                PLAN_ID,
                new PlanInput("refund"),
                owner
            )
        );
        JsonNodeFactory json = new JsonNodeFactory();

        var denied = coordinator.<PlanOutput>resume(
            new PlanExecutionResumeRequest(
                waiting.executionId(),
                "amount-request",
                json.amount(75),
                trusted("service-b", "account-2"),
                "cross-context"
            )
        );

        assertThat(denied.status()).isEqualTo(
            PlanExecutionResumeStatus.DENIED
        );
        verify(gateway, never()).resume(any());

        var invalid = coordinator.<PlanOutput>resume(
            new PlanExecutionResumeRequest(
                waiting.executionId(),
                "amount-request",
                json.text("invalid"),
                owner,
                "invalid-response"
            )
        );
        assertThat(invalid.status()).isEqualTo(
            PlanExecutionResumeStatus.REJECTED
        );
        assertThat(invalid.failure().retryable()).isTrue();
        assertThat(coordinator.find(waiting.executionId(), owner))
            .get()
            .extracting("status")
            .isEqualTo(PlanExecutionStatus.WAITING_FOR_INPUT);
    }

    @Test
    void cancellationClosesPlanAndCancelsActiveChild() {
        when(gateway.execute(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            return request.specialistId().equals(FIRST)
                ? success("first-invocation", FIRST, 4)
                : waiting(
                    "second-invocation",
                    SECOND,
                    "amount-request"
                );
        });
        when(gateway.cancel(any(), any())).thenReturn(true);
        TrustedExecutionContext context = trusted(
            "service-a",
            "account-1"
        );
        PlanExecutionResult<PlanOutput> waiting = coordinator.execute(
            PlanExecutionRequest.synchronous(
                PLAN_ID,
                new PlanInput("refund"),
                context
            )
        );

        assertThat(coordinator.cancel(waiting.executionId(), context))
            .isTrue();
        verify(gateway).cancel("second-invocation", context);
        assertThat(coordinator.find(waiting.executionId(), context))
            .get()
            .satisfies(snapshot -> {
                assertThat(snapshot.status()).isEqualTo(
                    PlanExecutionStatus.CANCELLED
                );
                assertThat(snapshot.result().failure().reason())
                    .isEqualTo("PLAN_CANCELLED");
            });

        var resume = coordinator.<PlanOutput>resume(
            new PlanExecutionResumeRequest(
                waiting.executionId(),
                "amount-request",
                new ObjectMapper().createObjectNode().put("amount", 50),
                context,
                "after-cancel"
            )
        );
        assertThat(resume.status()).isEqualTo(
            PlanExecutionResumeStatus.REJECTED
        );
    }

    @Test
    void failsClosedWhenAChildUnexpectedlyRequestsWriteConfirmation() {
        when(gateway.execute(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            if (request.specialistId().equals(FIRST)) {
                return success("first-invocation", FIRST, 2);
            }
            return confirmation("second-invocation", SECOND);
        });

        PlanExecutionResult<PlanOutput> result = coordinator.execute(
            PlanExecutionRequest.synchronous(
                PLAN_ID,
                new PlanInput("refund"),
                trusted("service-a", "account-1")
            )
        );

        assertThat(result.status()).isEqualTo(PlanExecutionStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("PLAN_WRITE_PROPOSAL_UNSUPPORTED");
        assertThat(result.steps()).last()
            .extracting("status")
            .isEqualTo(AIExecutionStatus.CONFIRMATION_REQUIRED);
    }

    @Test
    void failsClosedWhenAChildReturnsTheWrongApplicationOutputType() {
        when(gateway.execute(any())).thenReturn(
            success("first-invocation", FIRST, "wrong-output")
        );

        PlanExecutionResult<PlanOutput> result = coordinator.execute(
            PlanExecutionRequest.synchronous(
                PLAN_ID,
                new PlanInput("refund"),
                trusted("service-a", "account-1")
            )
        );

        assertThat(result.status()).isEqualTo(PlanExecutionStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("PLAN_STEP_OUTPUT_TYPE_INVALID");
        assertThat(result.failure().stepId())
            .isEqualTo("account-state");
        verify(gateway, times(1)).execute(any());
    }

    @Test
    void terminatesAndReplaysPlanWhenTheChildInputWaitHasExpired() {
        when(gateway.execute(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            return request.specialistId().equals(FIRST)
                ? success("first-invocation", FIRST, 6)
                : waiting(
                    "second-invocation",
                    SECOND,
                    "amount-request"
                );
        });
        when(gateway.resume(any())).thenReturn(
            AIExecutionResumeResult.rejected(
                AIExecutionResumeStatus.EXPIRED,
                "INPUT_REQUEST_EXPIRED",
                "The input request has expired.",
                false
            )
        );
        TrustedExecutionContext context = trusted(
            "service-a",
            "account-1"
        );
        PlanExecutionResult<PlanOutput> waiting = coordinator.execute(
            PlanExecutionRequest.synchronous(
                PLAN_ID,
                new PlanInput("refund"),
                context
            )
        );
        PlanExecutionResumeRequest request =
            new PlanExecutionResumeRequest(
                waiting.executionId(),
                "amount-request",
                new ObjectMapper().createObjectNode().put("amount", 75),
                context,
                "expired-resume"
            );

        var expired = coordinator.<PlanOutput>resume(request);

        assertThat(expired.status())
            .isEqualTo(PlanExecutionResumeStatus.RESUMED);
        assertThat(expired.executionResult().status())
            .isEqualTo(PlanExecutionStatus.DEADLINE_EXCEEDED);
        assertThat(expired.executionResult().failure().reason())
            .isEqualTo("INPUT_REQUEST_EXPIRED");
        assertThat(coordinator.find(waiting.executionId(), context))
            .get()
            .extracting("status")
            .isEqualTo(PlanExecutionStatus.DEADLINE_EXCEEDED);

        var replay = coordinator.<PlanOutput>resume(request);
        assertThat(replay.status())
            .isEqualTo(PlanExecutionResumeStatus.REPLAYED);
        assertThat(replay.executionResult())
            .isEqualTo(expired.executionResult());
        verify(gateway, times(1)).resume(any());
    }

    @Test
    void rejectsInteractiveSourceBeforeAnyExecution() {
        TrustedExecutionContext interactive = new TrustedExecutionContext(
            new ExecutionPrincipal(
                "user-1",
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef("account", "account-1"),
            ExecutionSource.INTERACTIVE,
            "tenant",
            "deployment",
            Set.of(),
            null,
            NOW
        );

        assertThatThrownBy(() -> PlanExecutionRequest.synchronous(
            PLAN_ID,
            new PlanInput("refund"),
            interactive
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("require dialogue ownership");
        verify(gateway, never()).execute(any());
    }

    private ExecutionPlanDefinition<PlanInput, PlanOutput> definition() {
        return new ExecutionPlanDefinition<>(
            PLAN_ID,
            PlanInput.class,
            PlanOutput.class,
            List.of(
                new SpecialistPlanStep(
                    "account-state",
                    FIRST,
                    String.class,
                    Integer.class,
                    FIRST_MAPPER
                ),
                new SpecialistPlanStep(
                    "billing-path",
                    SECOND,
                    BillingInput.class,
                    Boolean.class,
                    SECOND_MAPPER
                )
            ),
            AGGREGATOR,
            java.time.Duration.ofSeconds(30)
        );
    }

    private SpecialistClientFactory forwardingClientFactory(
        AIExecutionGateway executionGateway
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
                        return executionGateway.execute(
                            new AIExecutionRequest<>(
                                specialistId,
                                inputType.cast(invocation.input()),
                                invocation.trustedExecutionContext(),
                                invocation.conversationBinding(),
                                invocation.deadline(),
                                invocation.idempotencyKey()
                            )
                        );
                    }

                    @Override
                    public AIExecutionResumeResult<O> resume(
                        SpecialistResumeInvocation invocation
                    ) {
                        return executionGateway.resume(
                            new AIExecutionResumeRequest(
                                specialistId,
                                invocation.invocationId(),
                                invocation.requestId(),
                                new ObjectMapper().valueToTree(
                                    invocation.response()
                                ),
                                invocation.trustedExecutionContext(),
                                invocation.idempotencyKey()
                            )
                        );
                    }

                    @Override
                    public ExecutionHandle submit(
                        SpecialistInvocation<I> invocation
                    ) {
                        return executionGateway.submit(
                            new AIExecutionRequest<>(
                                specialistId,
                                inputType.cast(invocation.input()),
                                invocation.trustedExecutionContext(),
                                invocation.conversationBinding(),
                                invocation.deadline(),
                                invocation.idempotencyKey()
                            )
                        );
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public Optional<SpecialistExecutionSnapshot<O>> find(
                        String invocationId,
                        TrustedExecutionContext trustedExecutionContext
                    ) {
                        return executionGateway.find(
                            invocationId,
                            trustedExecutionContext
                        ).map(snapshot -> new SpecialistExecutionSnapshot<>(
                            snapshot.handle(),
                            (AIExecutionResult<O>) snapshot.result()
                        ));
                    }

                    @Override
                    public boolean cancel(
                        String invocationId,
                        TrustedExecutionContext trustedExecutionContext
                    ) {
                        return executionGateway.cancel(
                            invocationId,
                            trustedExecutionContext
                        );
                    }
                };
            }
        };
    }

    private PlanStepInputMapper<PlanInput, String> firstMapper() {
        return new PlanStepInputMapper<>() {
            @Override
            public PlanComponentId id() {
                return FIRST_MAPPER;
            }

            @Override
            public Class<PlanInput> planInputType() {
                return PlanInput.class;
            }

            @Override
            public Class<String> stepInputType() {
                return String.class;
            }

            @Override
            public String map(
                PlanInput planInput,
                PlanStepOutputs approvedOutputs
            ) {
                firstVisibleOutputs.set(approvedOutputs.size());
                return planInput.question();
            }
        };
    }

    private PlanStepInputMapper<PlanInput, BillingInput> secondMapper() {
        return new PlanStepInputMapper<>() {
            @Override
            public PlanComponentId id() {
                return SECOND_MAPPER;
            }

            @Override
            public Class<PlanInput> planInputType() {
                return PlanInput.class;
            }

            @Override
            public Class<BillingInput> stepInputType() {
                return BillingInput.class;
            }

            @Override
            public Map<String, Class<?>> requiredStepOutputs() {
                return Map.of("account-state", Integer.class);
            }

            @Override
            public BillingInput map(
                PlanInput planInput,
                PlanStepOutputs approvedOutputs
            ) {
                return new BillingInput(
                    planInput.question(),
                    approvedOutputs.require(
                        "account-state",
                        Integer.class
                    )
                );
            }
        };
    }

    private PlanResultAggregator<PlanInput, PlanOutput> aggregator() {
        return new PlanResultAggregator<>() {
            @Override
            public PlanComponentId id() {
                return AGGREGATOR;
            }

            @Override
            public Class<PlanInput> planInputType() {
                return PlanInput.class;
            }

            @Override
            public Class<PlanOutput> outputType() {
                return PlanOutput.class;
            }

            @Override
            public Map<String, Class<?>> requiredStepOutputs() {
                return Map.of(
                    "account-state",
                    Integer.class,
                    "billing-path",
                    Boolean.class
                );
            }

            @Override
            public PlanOutput aggregate(
                PlanInput planInput,
                PlanStepOutputs approvedOutputs
            ) {
                return new PlanOutput(
                    approvedOutputs.require(
                        "account-state",
                        Integer.class
                    ),
                    approvedOutputs.require(
                        "billing-path",
                        Boolean.class
                    ),
                    planInput.question()
                );
            }
        };
    }

    private ExecutionPlanRegistry registry(
        RegisteredExecutionPlan plan
    ) {
        return new ExecutionPlanRegistry() {
            @Override
            public Optional<RegisteredExecutionPlan> find(
                ExecutionPlanId id
            ) {
                return plan.id().equals(id)
                    ? Optional.of(plan)
                    : Optional.empty();
            }

            @Override
            public List<RegisteredExecutionPlan> list() {
                return List.of(plan);
            }
        };
    }

    private TrustedExecutionContext trusted(
        String principal,
        String subject
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                principal,
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef("account", subject),
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

    private AIExecutionResult<?> waiting(
        String invocationId,
        SpecialistId specialistId,
        String requestId
    ) {
        NeedsUserInput wait = new NeedsUserInput(
            requestId,
            invocationId,
            specialistId,
            "MISSING_AMOUNT",
            "What amount should be assessed?",
            new SpecialistInputResponseContract(
                new SpecialistSchemaId("amount-response", "1"),
                Map.of("type", "object")
            ),
            InputDeliveryTarget.HOST_APPLICATION,
            ExecutionDurability.EPHEMERAL,
            NOW,
            NOW.plusSeconds(20),
            3
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
        ActionProposalView proposal = mock(ActionProposalView.class);
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
            proposal
        );
    }

    private record PlanInput(String question) {}

    private record BillingInput(String question, int accountScore) {}

    private record AmountResponse(int amount) {}

    private record PlanOutput(
        int accountScore,
        boolean billingApproved,
        String question
    ) {}

    private static final class JsonNodeFactory {
        private final ObjectMapper objectMapper = new ObjectMapper();

        JsonNode text(String value) {
            return objectMapper.getNodeFactory().textNode(value);
        }

        JsonNode amount(int value) {
            return objectMapper.createObjectNode().put("amount", value);
        }
    }
}
