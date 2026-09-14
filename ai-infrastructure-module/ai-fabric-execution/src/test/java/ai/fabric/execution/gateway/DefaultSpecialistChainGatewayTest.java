package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.execution.chain.RegisteredSpecialistChain;
import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainConversationPolicy;
import ai.fabric.execution.chain.SpecialistChainDefinition;
import ai.fabric.execution.chain.SpecialistChainDirective;
import ai.fabric.execution.chain.SpecialistChainDirectiveType;
import ai.fabric.execution.chain.SpecialistChainExecutionRequest;
import ai.fabric.execution.chain.SpecialistChainExecutionResult;
import ai.fabric.execution.chain.SpecialistChainExecutionStatus;
import ai.fabric.execution.chain.SpecialistChainId;
import ai.fabric.execution.chain.SpecialistChainInputAdapter;
import ai.fabric.execution.chain.SpecialistChainLimits;
import ai.fabric.execution.chain.SpecialistChainManagerInput;
import ai.fabric.execution.chain.SpecialistChainMetrics;
import ai.fabric.execution.chain.SpecialistChainRegistry;
import ai.fabric.execution.chain.SpecialistChainBudgetView;
import ai.fabric.execution.chain.SpecialistChainResultProjection;
import ai.fabric.execution.chain.SpecialistChainResultView;
import ai.fabric.execution.chain.SpecialistChainStepTrace;
import ai.fabric.execution.chain.SpecialistChainTarget;
import ai.fabric.execution.chain.SpecialistChainTargetInputMapper;
import ai.fabric.execution.chain.SpecialistChainTargetRequest;
import ai.fabric.execution.chain.SpecialistChainTargetResultProjector;
import ai.fabric.execution.chain.SpecialistChainTargetView;
import ai.fabric.execution.chain.SpecialistChainWorkerStatus;
import ai.fabric.execution.chain.SpecialistChainWorkerTrace;
import ai.fabric.execution.chain.state.InMemorySpecialistChainExecutionRepository;
import ai.fabric.execution.chain.state.JdbcSpecialistChainExecutionRepository;
import ai.fabric.execution.chain.state.SpecialistChainCheckpoint;
import ai.fabric.execution.chain.state.SpecialistChainCheckpointPhase;
import ai.fabric.execution.chain.state.SpecialistChainExecutionRecord;
import ai.fabric.execution.chain.state.SpecialistChainExecutionRepository;
import ai.fabric.execution.chain.state.SpecialistChainManagerExecutionCheckpoint;
import ai.fabric.execution.chain.state.SpecialistChainPayloadCodec;
import ai.fabric.execution.chain.state.SpecialistChainSecurity;
import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.delegation.SpecialistDelegationGateway;
import ai.fabric.execution.delegation.SpecialistDelegationFailure;
import ai.fabric.execution.delegation.SpecialistDelegationResult;
import ai.fabric.execution.handoff.SpecialistHandoffGateway;
import ai.fabric.execution.handoff.SpecialistHandoffResult;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.support.TaskExecutorAdapter;

@ExtendWith(OutputCaptureExtension.class)
class DefaultSpecialistChainGatewayTest {

    private static final SpecialistChainId CHAIN =
        SpecialistChainId.of("incident-chain", "1");
    private static final SpecialistId MANAGER =
        SpecialistId.of("incident-manager", "1");
    private static final SpecialistId HEALTH =
        SpecialistId.of("health-reader", "1");
    private static final SpecialistId CHANGE =
        SpecialistId.of("change-reader", "1");

    @Test
    void completesWithoutStartingAWorker() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-1",
                    directive(
                        SpecialistChainDirectiveType.COMPLETE,
                        List.of(),
                        "No specialist is needed."
                    )
                )
            );

            var result = fixture.gateway.execute(
                request("Explain the available investigation scope", "r1")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.COMPLETED);
            assertThat(result.message()).isEqualTo("No specialist is needed.");
            assertThat(result.steps()).hasSize(1);
            assertThat(result.projectedResults()).isEmpty();
            verify(fixture.delegation, never())
                .delegate(any(), any(), any());
        }
    }

    @Test
    void discardsALateManagerResultAndReplaysTheSameDeadlineFailure()
        throws Exception {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenAnswer(call -> {
                Thread.sleep(250L);
                return managerSuccess(
                    "manager-too-late",
                    directive(
                        SpecialistChainDirectiveType.COMPLETE,
                        List.of(),
                        "This late answer must not be returned."
                    )
                );
            });
            SpecialistChainExecutionRequest<String> request = request(
                "Investigate within the deadline",
                "manager-deadline",
                Instant.now().plusMillis(120L)
            );

            SpecialistChainExecutionResult first = fixture.gateway.execute(
                request
            );
            SpecialistChainExecutionResult replay = fixture.gateway.execute(
                request
            );

            assertThat(first.status())
                .isEqualTo(SpecialistChainExecutionStatus.DEADLINE_EXCEEDED);
            assertThat(first.message()).isNull();
            assertThat(first.failure().reason())
                .isEqualTo("CHAIN_DEADLINE_EXCEEDED");
            assertThat(replay.status()).isEqualTo(first.status());
            assertThat(replay.failure()).isEqualTo(first.failure());
            assertThat(replay.executionId()).isEqualTo(first.executionId());
            assertThat(replay.replayed()).isTrue();
            verify(fixture.managerClient).execute(any());
        }
    }

    @Test
    void discardsALateWorkerResultWithoutFinalSynthesis() throws Exception {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-before-worker-timeout",
                    invokeOne(HEALTH, "Inspect health evidence.")
                )
            );
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                Thread.sleep(250L);
                var delegationRequest = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                return delegationSuccess(
                    delegationRequest.sourceExecution(),
                    delegationRequest.targetSpecialistId(),
                    "This late worker result must be discarded."
                );
            });

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request(
                    "Inspect health before the deadline",
                    "worker-deadline",
                    Instant.now().plusMillis(120L)
                )
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.DEADLINE_EXCEEDED);
            assertThat(result.projectedResults()).isEmpty();
            assertThat(result.failure().reason())
                .isEqualTo("CHAIN_DEADLINE_EXCEEDED");
            verify(fixture.managerClient).execute(any());
            verify(fixture.delegation).delegate(any(), any(), any());
        }
    }

    @Test
    void adaptivelyInvokesTwoWorkersThenSynthesizes() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenAnswer(call -> {
                SpecialistChainManagerInput input = managerInput(call);
                return switch (input.completedResults().size()) {
                    case 0 -> {
                        assertThat(input.approvedTargets())
                            .extracting(SpecialistChainTargetView::specialist)
                            .containsExactly(
                                HEALTH.toString(),
                                CHANGE.toString()
                            );
                        yield managerSuccess(
                            "manager-1",
                            invokeOne(HEALTH, "Inspect health evidence.")
                        );
                    }
                    case 1 -> {
                        assertThat(input.approvedTargets())
                            .extracting(SpecialistChainTargetView::specialist)
                            .containsExactly(CHANGE.toString());
                        yield managerSuccess(
                            "manager-2",
                            invokeOne(CHANGE, "Health points to a change.")
                        );
                    }
                    default -> {
                        assertThat(input.approvedTargets()).isEmpty();
                        yield groundedManagerSuccess(
                            "manager-3",
                            "The deployment explains the health regression.",
                            input
                        );
                    }
                };
            });
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                SpecialistId target = request.targetSpecialistId();
                return delegationSuccess(
                    request.sourceExecution(),
                    target,
                    target.equals(HEALTH)
                        ? "Checkout error rate is elevated."
                        : "Release r-17 preceded the regression."
                );
            });

            var result = fixture.gateway.execute(
                request("Investigate checkout latency", "r2")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.COMPLETED);
            assertThat(result.projectedResults())
                .extracting(value -> value.specialist())
                .containsExactly(HEALTH.toString(), CHANGE.toString());
            assertThat(result.steps())
                .extracting(step -> step.directiveType())
                .containsExactly(
                    SpecialistChainDirectiveType.INVOKE_ONE,
                    SpecialistChainDirectiveType.INVOKE_ONE,
                    SpecialistChainDirectiveType.COMPLETE
                );
            verify(fixture.managerClient, times(3)).execute(any());
            verify(fixture.delegation, times(2))
                .delegate(any(), any(), any());
        }
    }

    @Test
    void runsIndependentWorkersInParallelAndPreservesDefinitionOrder() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenAnswer(call -> {
                SpecialistChainManagerInput input = managerInput(call);
                if (input.completedResults().isEmpty()) {
                    assertThat(input.approvedTargets()).hasSize(2);
                    return managerSuccess(
                        "manager-1",
                        new SpecialistChainDirective(
                            SpecialistChainDirectiveType.INVOKE_PARALLEL,
                            List.of(
                                target(CHANGE, "Inspect changes."),
                                target(HEALTH, "Inspect health.")
                            ),
                            null,
                            "Both independent evidence sets are required."
                        )
                    );
                }
                assertThat(input.approvedTargets()).isEmpty();
                return groundedManagerSuccess(
                    "manager-2",
                    "Both evidence sets support the conclusion.",
                    input
                );
            });
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximumActive = new AtomicInteger();
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                int current = active.incrementAndGet();
                maximumActive.accumulateAndGet(current, Math::max);
                try {
                    Thread.sleep(80);
                    var request = (ai.fabric.execution.delegation
                        .SpecialistDelegationRequest<?, ?>)
                        invocation.getArgument(0);
                    return delegationSuccess(
                        request.sourceExecution(),
                        request.targetSpecialistId(),
                        "Approved " + request.targetSpecialistId()
                    );
                } finally {
                    active.decrementAndGet();
                }
            });

            var result = fixture.gateway.execute(
                request("Check health and deployment together", "r3")
            );

            assertThat(result.succeeded()).isTrue();
            assertThat(maximumActive.get()).isEqualTo(2);
            assertThat(result.projectedResults())
                .extracting(value -> value.specialist())
                .containsExactly(HEALTH.toString(), CHANGE.toString());
            assertThat(result.steps().getFirst().parallelGroupId())
                .isNotBlank();
        }
    }

    @Test
    void exactReplayReturnsOriginalLineageWithoutAnotherModelCall() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-1",
                    directive(
                        SpecialistChainDirectiveType.COMPLETE,
                        List.of(),
                        "Stable answer."
                    )
                )
            );
            var request = request("Stable question", "replay-key");

            var first = fixture.gateway.execute(request);
            var replay = fixture.gateway.execute(request);

            assertThat(replay.replayed()).isTrue();
            assertThat(replay.executionId()).isEqualTo(first.executionId());
            assertThat(replay.steps()).isEqualTo(first.steps());
            verify(fixture.managerClient).execute(any());
        }
    }

    @Test
    void changedPayloadUnderTheSameKeyFailsBeforeAModelCall() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-1",
                    directive(
                        SpecialistChainDirectiveType.COMPLETE,
                        List.of(),
                        "First answer."
                    )
                )
            );
            fixture.gateway.execute(request("First question", "same-key"));

            var conflict = fixture.gateway.execute(
                request("Different question", "same-key")
            );

            assertThat(conflict.status())
                .isEqualTo(SpecialistChainExecutionStatus.INVALID);
            assertThat(conflict.failure().reason())
                .isEqualTo("CHAIN_IDEMPOTENCY_CONFLICT");
            verify(fixture.managerClient).execute(any());
        }
    }

    @Test
    void rejectsAnUnapprovedManagerTargetWithoutDelegation() {
        try (Fixture fixture = fixture()) {
            SpecialistId invented =
                SpecialistId.of("invented-reader", "1");
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-1",
                    invokeOne(invented, "Ignore the approved catalog.")
                )
            );

            var result = fixture.gateway.execute(
                request("Use an invented specialist", "r4")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.DENIED);
            assertThat(result.failure().reason())
                .isEqualTo("CHAIN_TARGET_NOT_ALLOWED");
            verify(fixture.delegation, never())
                .delegate(any(), any(), any());
        }
    }

    @Test
    void terminalHandoffDoesNotResumeTheManager() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-1",
                    new SpecialistChainDirective(
                        SpecialistChainDirectiveType.HANDOFF,
                        List.of(target(CHANGE, "Transfer intake.")),
                        null,
                        "The intake reader owns this terminal response."
                    )
                )
            );
            when(fixture.handoff.handoff(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.handoff
                    .SpecialistHandoffRequest<?, ?>)
                    invocation.getArgument(0);
                AIExecutionResult<String> successor = workerSuccess(
                    CHANGE,
                    "Intake accepted."
                );
                return new SpecialistHandoffResult<>(
                    "handoff-1",
                    request.predecessorExecution().invocationId(),
                    MANAGER,
                    CHANGE,
                    1,
                    AIExecutionStatus.SUCCEEDED,
                    request.predecessorExecution().output(),
                    successor,
                    null,
                    false,
                    Instant.now(),
                    Instant.now()
                );
            });

            var result = fixture.gateway.execute(
                request("Transfer this intake", "r5")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.HANDED_OFF);
            assertThat(result.handoffTarget()).isEqualTo(CHANGE);
            assertThat(result.message()).isEqualTo("Intake accepted.");
            verify(fixture.managerClient).execute(any());
            verify(fixture.handoff).handoff(any(), any(), any());
        }
    }

    @Test
    void asksOneBoundedQuestionWithoutStartingAWorker() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-ask",
                    directive(
                        SpecialistChainDirectiveType.ASK_USER,
                        List.of(),
                        "Should I inspect service health, recent changes, or both?"
                    )
                )
            );

            var result = fixture.gateway.execute(
                request("Something is wrong", "ask-1")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.ASKED_USER);
            assertThat(result.message()).contains("service health");
            assertThat(result.projectedResults()).isEmpty();
            verify(fixture.delegation, never())
                .delegate(any(), any(), any());
        }
    }

    @Test
    void repeatedTargetFailsAsNoProgressWithoutASecondWorkerCall() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any()))
                .thenReturn(
                    managerSuccess(
                        "manager-repeat-1",
                        invokeOne(HEALTH, "Inspect health.")
                    ),
                    managerSuccess(
                        "manager-repeat-2",
                        invokeOne(HEALTH, "Inspect health again.")
                    )
                );
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                return delegationSuccess(
                    request.sourceExecution(),
                    request.targetSpecialistId(),
                    "Health projection"
                );
            });

            var result = fixture.gateway.execute(
                request("Inspect health twice", "repeat-1")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.INVALID);
            assertThat(result.failure().reason()).isEqualTo("CHAIN_NO_PROGRESS");
            verify(fixture.delegation).delegate(any(), any(), any());
        }
    }

    @Test
    void correctsOneRecoverableDirectiveWithoutRepeatingWorkerWork() {
        try (Fixture fixture = fixture()) {
            AtomicInteger managerCalls = new AtomicInteger();
            List<String> managerKeys = new java.util.ArrayList<>();
            when(fixture.managerClient.execute(any())).thenAnswer(call -> {
                @SuppressWarnings("unchecked")
                SpecialistInvocation<SpecialistChainManagerInput> invocation =
                    (SpecialistInvocation<SpecialistChainManagerInput>)
                        call.getArgument(0);
                SpecialistChainManagerInput input = invocation.input();
                managerKeys.add(invocation.idempotencyKey());
                return switch (managerCalls.getAndIncrement()) {
                    case 0 -> managerSuccess(
                        "manager-correction-1",
                        invokeOne(HEALTH, "Inspect health.")
                    );
                    case 1 -> {
                        assertThat(input.previousDirectiveFeedback()).isNull();
                        assertThat(input.approvedTargets())
                            .extracting(SpecialistChainTargetView::specialist)
                            .containsExactly(CHANGE.toString());
                        yield managerSuccess(
                            "manager-correction-2",
                            invokeOne(HEALTH, "Repeat completed work.")
                        );
                    }
                    default -> {
                        assertThat(input.previousDirectiveFeedback())
                            .contains("REQUIRED CORRECTION")
                            .contains("no longer eligible")
                            .contains(CHANGE.toString());
                        yield groundedManagerSuccess(
                            "manager-correction-3",
                            "Health evidence is sufficient.",
                            input
                        );
                    }
                };
            });
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                return delegationSuccess(
                    request.sourceExecution(),
                    request.targetSpecialistId(),
                    "Approved health projection."
                );
            });

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request("Inspect health once", "correct-directive-1")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.COMPLETED);
            assertThat(result.projectedResults()).hasSize(1);
            assertThat(managerKeys).hasSize(3).doesNotHaveDuplicates();
            verify(fixture.managerClient, times(3)).execute(any());
            verify(fixture.delegation).delegate(any(), any(), any());
        }
    }

    @Test
    void correctionRequiresCompletionWhenNoTargetRemainsEligible() {
        try (Fixture fixture = fixture()) {
            AtomicInteger managerCalls = new AtomicInteger();
            when(fixture.managerClient.execute(any())).thenAnswer(call -> {
                @SuppressWarnings("unchecked")
                SpecialistInvocation<SpecialistChainManagerInput> invocation =
                    (SpecialistInvocation<SpecialistChainManagerInput>)
                        call.getArgument(0);
                SpecialistChainManagerInput input = invocation.input();
                return switch (managerCalls.getAndIncrement()) {
                    case 0 -> managerSuccess(
                        "manager-empty-correction-1",
                        invokeOne(HEALTH, "Inspect health.")
                    );
                    case 1 -> managerSuccess(
                        "manager-empty-correction-2",
                        invokeOne(CHANGE, "Inspect change risk.")
                    );
                    case 2 -> managerSuccess(
                        "manager-empty-correction-3",
                        invokeOne(CHANGE, "Repeat completed work.")
                    );
                    default -> {
                        assertThat(input.approvedTargets()).isEmpty();
                        assertThat(input.completedResults()).hasSize(2);
                        assertThat(input.previousDirectiveFeedback())
                            .contains("REQUIRED CORRECTION")
                            .contains("no specialist remains eligible")
                            .contains("Return COMPLETE now")
                            .contains("supportingResultIds exactly");
                        yield groundedManagerSuccess(
                            "manager-empty-correction-4",
                            "Health and change evidence are complete.",
                            input
                        );
                    }
                };
            });
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                return delegationSuccess(
                    request.sourceExecution(),
                    request.targetSpecialistId(),
                    "Approved projected result."
                );
            });

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request("Inspect health and change risk", "empty-correction-1")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.COMPLETED);
            assertThat(result.projectedResults()).hasSize(2);
            verify(fixture.managerClient, times(4)).execute(any());
            verify(fixture.delegation, times(2)).delegate(any(), any(), any());
        }
    }

    @Test
    void managerCorrectionUsesFreshApprovalForFrozenConversationSnapshot() {
        ConversationBinding plainBinding = new ConversationBinding(
            "user-1",
            "conversation-1"
        );
        ApprovedConversationSnapshot snapshot =
            new ApprovedConversationSnapshot(
                "turn-1",
                plainBinding.userId(),
                plainBinding.conversationId(),
                MANAGER.toString(),
                "c".repeat(64),
                0,
                List.of(),
                Instant.now()
            );
        ConversationBinding initialBinding = new ConversationBinding(
            plainBinding.userId(),
            plainBinding.conversationId(),
            "snapshot-initial"
        );
        SharedInteractiveTurnCoordinator coordinator = mock(
            SharedInteractiveTurnCoordinator.class
        );
        AIExecutionConversationRecorder recorder = mock(
            AIExecutionConversationRecorder.class
        );
        @SuppressWarnings("unchecked")
        SpecialistDefinition<?, ?> dialogueOwner = mock(
            SpecialistDefinition.class
        );
        when(coordinator.coordinate(
            eq(MANAGER),
            any(),
            eq(plainBinding),
            eq("conversation-correction-1"),
            eq(SharedInteractiveTurnCoordinator.RecordingPolicy.COORDINATED),
            any()
        )).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            SharedInteractiveTurnCoordinator.TurnWork<Boolean> work =
                (SharedInteractiveTurnCoordinator.TurnWork<Boolean>)
                    call.getArgument(5);
            Boolean value = work.run(
                new SharedInteractiveTurnCoordinator.ApprovedInteractiveTurn(
                    dialogueOwner,
                    initialBinding,
                    snapshot
                )
            );
            return SharedInteractiveTurnCoordinator.CoordinatedTurn
                .succeeded(value);
        });
        AtomicInteger approvals = new AtomicInteger();
        when(coordinator.approveInvocation(plainBinding, snapshot))
            .thenAnswer(ignored -> new ConversationBinding(
                plainBinding.userId(),
                plainBinding.conversationId(),
                "snapshot-follow-up-" + approvals.incrementAndGet()
            ));

        SpecialistChainDefinition<String> definition = definition(
            defaultLimits(),
            List.of(
                chainTarget(HEALTH, false),
                chainTarget(CHANGE, true)
            ),
            SpecialistChainConversationPolicy.REQUIRED
        );
        try (Fixture fixture = fixture(
            new InMemorySpecialistChainExecutionRepository(),
            false,
            "a".repeat(64),
            "b".repeat(64),
            definition,
            coordinator,
            recorder
        )) {
            AtomicInteger managerCalls = new AtomicInteger();
            List<String> approvalTokens = new java.util.ArrayList<>();
            when(fixture.managerClient.execute(any())).thenAnswer(call -> {
                @SuppressWarnings("unchecked")
                SpecialistInvocation<SpecialistChainManagerInput> invocation =
                    (SpecialistInvocation<SpecialistChainManagerInput>)
                        call.getArgument(0);
                approvalTokens.add(
                    invocation.conversationBinding().approvedSnapshotToken()
                );
                return switch (managerCalls.getAndIncrement()) {
                    case 0 -> managerSuccess(
                        "manager-conversation-1",
                        invokeOne(HEALTH, "Inspect health.")
                    );
                    case 1 -> managerSuccess(
                        "manager-conversation-2",
                        directive(
                            SpecialistChainDirectiveType.COMPLETE,
                            List.of(),
                            "Health evidence is sufficient."
                        )
                    );
                    default -> groundedManagerSuccess(
                        "manager-conversation-3",
                        "Health evidence is sufficient.",
                        invocation.input()
                    );
                };
            });
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                return delegationSuccess(
                    request.sourceExecution(),
                    request.targetSpecialistId(),
                    "Approved health projection."
                );
            });
            SpecialistChainExecutionRequest<String> request =
                new SpecialistChainExecutionRequest<>(
                    CHAIN,
                    "Inspect health once",
                    interactiveContext(),
                    plainBinding,
                    null,
                    "conversation-correction-1"
                );

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.COMPLETED);
            assertThat(approvalTokens)
                .containsExactly(
                    "snapshot-initial",
                    "snapshot-follow-up-1",
                    "snapshot-follow-up-2"
                )
                .doesNotHaveDuplicates();
            verify(coordinator, times(2)).approveInvocation(
                plainBinding,
                snapshot
            );
            verify(fixture.delegation).delegate(any(), any(), any());
        }
    }

    @Test
    void requiredParallelFailureIsAttributedAndNeverSynthesized() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-parallel-failure",
                    new SpecialistChainDirective(
                        SpecialistChainDirectiveType.INVOKE_PARALLEL,
                        List.of(
                            target(HEALTH, "Inspect health."),
                            target(CHANGE, "Inspect changes.")
                        ),
                        null,
                        "Both branches are required."
                    )
                )
            );
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                return request.targetSpecialistId().equals(CHANGE)
                    ? delegationFailure(
                        request.sourceExecution(),
                        CHANGE,
                        "CHANGE_SOURCE_UNAVAILABLE",
                        "The approved change source is unavailable."
                    )
                    : delegationSuccess(
                        request.sourceExecution(),
                        HEALTH,
                        "Health is degraded."
                    );
            });

            var result = fixture.gateway.execute(
                request("Inspect both required branches", "failure-1")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.FAILED);
            assertThat(result.message()).isNull();
            assertThat(result.failure().reason())
                .isEqualTo("CHANGE_SOURCE_UNAVAILABLE");
            assertThat(result.failure().publicMessage())
                .isEqualTo("The approved change source is unavailable.");
            assertThat(result.steps()).hasSize(1);
            verify(fixture.managerClient).execute(any());
        }
    }

    @Test
    void requiredParallelFailureCancelsAnOutstandingSibling() throws Exception {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-parallel-cancel",
                    new SpecialistChainDirective(
                        SpecialistChainDirectiveType.INVOKE_PARALLEL,
                        List.of(
                            target(HEALTH, "Inspect health."),
                            target(CHANGE, "Inspect changes.")
                        ),
                        null,
                        "Both branches are required."
                    )
                )
            );
            CountDownLatch healthStarted = new CountDownLatch(1);
            AtomicBoolean healthInterrupted = new AtomicBoolean();
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                if (request.targetSpecialistId().equals(HEALTH)) {
                    healthStarted.countDown();
                    try {
                        Thread.sleep(5_000L);
                    } catch (InterruptedException ex) {
                        healthInterrupted.set(true);
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", ex);
                    }
                    return delegationSuccess(
                        request.sourceExecution(),
                        HEALTH,
                        "Late health result"
                    );
                }
                assertThat(healthStarted.await(2, TimeUnit.SECONDS)).isTrue();
                return delegationFailure(
                    request.sourceExecution(),
                    CHANGE,
                    "CHANGE_SOURCE_UNAVAILABLE",
                    "The approved change source is unavailable."
                );
            });

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request("Cancel the sibling on required failure", "failure-cancel")
            );

            for (int attempt = 0;
                 attempt < 100 && !healthInterrupted.get();
                 attempt++) {
                Thread.sleep(10L);
            }
            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.FAILED);
            assertThat(result.failure().reason())
                .isEqualTo("CHANGE_SOURCE_UNAVAILABLE");
            assertThat(healthInterrupted).isTrue();
            verify(fixture.managerClient).execute(any());
        }
    }

    @Test
    void failsVisiblyWhenTheManagerProviderThrows() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenThrow(
                new IllegalStateException("provider unavailable")
            );

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request("Investigate provider failure", "manager-failure")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.FAILED);
            assertThat(result.failure().reason())
                .isEqualTo("CHAIN_MANAGER_INVOCATION_FAILED");
            assertThat(result.message()).isNull();
            verify(fixture.delegation, never())
                .delegate(any(), any(), any());
        }
    }

    @Test
    void providerFailureLogsDoNotExposeUserOrTrustedContextPayloads(
        CapturedOutput output
    ) {
        try (Fixture fixture = fixture()) {
            String sensitiveUserText = "private-incident-detail-4f3c";
            when(fixture.managerClient.execute(any())).thenThrow(
                new IllegalStateException(
                    "provider-payload-must-not-be-logged-8a1d"
                )
            );

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request(sensitiveUserText, "safe-log-check")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.FAILED);
            assertThat(result.failure().reason())
                .isEqualTo("CHAIN_MANAGER_INVOCATION_FAILED");
            assertThat(output.getAll())
                .doesNotContain(sensitiveUserText)
                .doesNotContain("provider-payload-must-not-be-logged-8a1d")
                .doesNotContain("tenant-1")
                .doesNotContain("incident-42");
        }
    }

    @Test
    void failsBeforeWorkerInvocationWhenTheApplicationMapperFails() {
        SpecialistChainTarget<String, String, String> failingTarget =
            chainTarget(HEALTH, false, true, false);
        try (Fixture fixture = fixture(definition(
            defaultLimits(),
            List.of(failingTarget, chainTarget(CHANGE, true))
        ))) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-mapper-failure",
                    invokeOne(HEALTH, "Inspect health evidence.")
                )
            );

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request("Exercise mapper failure", "mapper-failure")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.INVALID);
            assertThat(result.failure().reason())
                .isEqualTo("CHAIN_TARGET_INPUT_INVALID");
            assertThat(result.message()).isNull();
            verify(fixture.delegation, never())
                .delegate(any(), any(), any());
        }
    }

    @Test
    void failsWithoutRawOutputWhenTheApplicationProjectorFails() {
        SpecialistChainTarget<String, String, String> failingTarget =
            chainTarget(HEALTH, false, false, true);
        try (Fixture fixture = fixture(definition(
            defaultLimits(),
            List.of(failingTarget, chainTarget(CHANGE, true))
        ))) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-projector-failure",
                    invokeOne(HEALTH, "Inspect health evidence.")
                )
            );
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                return delegationSuccess(
                    request.sourceExecution(),
                    request.targetSpecialistId(),
                    "raw-provider-output-must-not-escape"
                );
            });

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request("Exercise projector failure", "projector-failure")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.FAILED);
            assertThat(result.failure().reason())
                .isEqualTo("CHAIN_RESULT_PROJECTION_INVALID");
            assertThat(result.failure().publicMessage())
                .doesNotContain("raw-provider-output-must-not-escape");
            assertThat(result.projectedResults()).isEmpty();
            verify(fixture.managerClient).execute(any());
        }
    }

    @Test
    void enforcesTheProjectedResultCharacterBudget() {
        SpecialistChainLimits limits = new SpecialistChainLimits(
            Duration.ofSeconds(10),
            3,
            1,
            1,
            1,
            12
        );
        try (Fixture fixture = fixture(definition(
            limits,
            List.of(chainTarget(HEALTH, false))
        ))) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-character-budget",
                    invokeOne(HEALTH, "Inspect health evidence.")
                )
            );
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                return delegationSuccess(
                    request.sourceExecution(),
                    request.targetSpecialistId(),
                    "This projection exceeds twelve characters."
                );
            });

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request("Exercise character budget", "character-budget")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.FAILED);
            assertThat(result.failure().reason())
                .isEqualTo("CHAIN_PROJECTED_RESULT_BUDGET_EXCEEDED");
            assertThat(result.projectedResults()).isEmpty();
            verify(fixture.managerClient).execute(any());
        }
    }

    @Test
    void reservesTheLastManagerDecisionForCompletion() {
        SpecialistChainLimits limits = new SpecialistChainLimits(
            Duration.ofSeconds(10),
            2,
            2,
            1,
            1,
            8_000
        );
        try (Fixture fixture = fixture(definition(
            limits,
            List.of(
                chainTarget(HEALTH, false),
                chainTarget(CHANGE, true)
            )
        ))) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-decision-budget-1",
                    invokeOne(HEALTH, "Inspect health evidence.")
                ),
                managerSuccess(
                    "manager-decision-budget-2",
                    invokeOne(CHANGE, "Spend the reserved completion decision.")
                )
            );
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                return delegationSuccess(
                    request.sourceExecution(),
                    request.targetSpecialistId(),
                    "Approved health result."
                );
            });

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request("Exercise decision budget", "decision-budget")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.INVALID);
            assertThat(result.failure().reason())
                .isEqualTo("CHAIN_BUDGET_EXCEEDED");
            verify(fixture.managerClient, times(2)).execute(any());
            verify(fixture.delegation).delegate(any(), any(), any());
        }
    }

    @Test
    void exposesInitialPersistenceFailureWithoutCallingTheManager() {
        SpecialistChainExecutionRepository repository =
            mock(SpecialistChainExecutionRepository.class);
        when(repository.findByIdempotencyFingerprint(any()))
            .thenReturn(Optional.empty());
        when(repository.create(any())).thenThrow(
            new IllegalStateException("database unavailable")
        );
        try (Fixture fixture = fixture(repository, true)) {
            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request("Exercise persistence failure", "state-failure")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.FAILED);
            assertThat(result.failure().reason())
                .isEqualTo("CHAIN_STATE_UNAVAILABLE");
            verify(fixture.managerClient, never()).execute(any());
            verify(fixture.delegation, never())
                .delegate(any(), any(), any());
        }
    }

    @Test
    void activeReplayDoesNotDuplicateWorkAndCancellationInterruptsIt()
        throws Exception {
        try (Fixture fixture = fixture()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            when(fixture.managerClient.execute(any())).thenAnswer(invocation -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", ex);
                }
                return managerSuccess(
                    "manager-cancel",
                    directive(
                        SpecialistChainDirectiveType.COMPLETE,
                        List.of(),
                        "Should not complete."
                    )
                );
            });
            var request = request("Long investigation", "cancel-1");

            var first = fixture.gateway.submit(request);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var replay = fixture.gateway.submit(request);

            assertThat(replay.executionId()).isEqualTo(first.executionId());
            assertThat(replay.replayed()).isTrue();
            assertThat(fixture.gateway.cancel(first.executionId(), context()))
                .isTrue();
            release.countDown();
            for (int attempt = 0; attempt < 100 && !interrupted.get(); attempt++) {
                Thread.sleep(10L);
            }
            assertThat(interrupted).isTrue();
            var result = fixture.gateway.findResult(
                first.executionId(),
                context()
            ).orElseThrow();
            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.CANCELLED);
            assertThat(result.message()).isNull();
            verify(fixture.managerClient).execute(any());
        }
    }

    @Test
    void executionLookupIsScopedToTrustedIdentityAndTenant() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-scope",
                    directive(
                        SpecialistChainDirectiveType.COMPLETE,
                        List.of(),
                        "Scoped answer."
                    )
                )
            );
            var result = fixture.gateway.execute(
                request("Scoped question", "scope-1")
            );

            TrustedExecutionContext otherTenant = new TrustedExecutionContext(
                context().initiator(),
                context().subject(),
                context().source(),
                "tenant-2",
                context().deploymentId(),
                context().grantedScopes(),
                context().correlationId(),
                context().authenticatedAt()
            );
            assertThat(fixture.gateway.find(result.executionId(), otherTenant))
                .isEmpty();
            assertThat(fixture.gateway.findResult(
                result.executionId(),
                otherTenant
            )).isEmpty();
        }
    }

    @Test
    void rejectsFinalAnswerThatOmitsCompletedWorkerAttribution() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-grounding-1",
                    invokeOne(HEALTH, "Inspect health evidence.")
                ),
                managerSuccess(
                    "manager-grounding-2",
                    directive(
                        SpecialistChainDirectiveType.COMPLETE,
                        List.of(),
                        "Unsupported terminal answer."
                    )
                )
            );
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                return delegationSuccess(
                    request.sourceExecution(),
                    request.targetSpecialistId(),
                    "Approved health result."
                );
            });

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request("Inspect health", "missing-grounding")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.INVALID);
            assertThat(result.failure().reason())
                .isEqualTo("CHAIN_FINAL_GROUNDING_INVALID");
            assertThat(result.message()).isNull();
        }
    }

    @Test
    void rejectsInventedFinalResultAttributionWithoutWorkerWork() {
        try (Fixture fixture = fixture()) {
            when(fixture.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-invented-grounding",
                    new SpecialistChainDirective(
                        SpecialistChainDirectiveType.COMPLETE,
                        List.of(),
                        "Invented grounded answer.",
                        "The manager claims unsupported evidence.",
                        List.of("invented-result")
                    )
                )
            );

            SpecialistChainExecutionResult result = fixture.gateway.execute(
                request("Answer directly", "invented-grounding")
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.INVALID);
            assertThat(result.failure().reason())
                .isEqualTo("CHAIN_FINAL_GROUNDING_INVALID");
            verify(fixture.delegation, never())
                .delegate(any(), any(), any());
        }
    }

    @Test
    void jdbcTerminalReplaySurvivesGatewayRestartWithoutModelWork() {
        JdbcDataSource dataSource = dataSource("terminal-replay");
        SpecialistChainExecutionRequest<String> stableRequest = request(
            "Stable durable question",
            "durable-replay"
        );
        String executionId;
        try (Fixture first = fixture(
            new JdbcSpecialistChainExecutionRepository(dataSource, true),
            true
        )) {
            when(first.managerClient.execute(any())).thenReturn(
                managerSuccess(
                    "manager-durable",
                    directive(
                        SpecialistChainDirectiveType.COMPLETE,
                        List.of(),
                        "Durable answer."
                    )
                )
            );
            SpecialistChainExecutionResult result = first.gateway.execute(
                stableRequest
            );
            executionId = result.executionId();
            assertThat(result.durable()).isTrue();
        }

        try (Fixture restarted = fixture(
            new JdbcSpecialistChainExecutionRepository(dataSource, false),
            true
        )) {
            SpecialistChainExecutionResult replay = restarted.gateway.execute(
                stableRequest
            );

            assertThat(replay.executionId()).isEqualTo(executionId);
            assertThat(replay.message()).isEqualTo("Durable answer.");
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.durable()).isTrue();
            verify(restarted.managerClient, never()).execute(any());
            verify(restarted.delegation, never())
                .delegate(any(), any(), any());
        }
    }

    @Test
    void recoveryResumesAcceptedDirectiveWithoutRepeatingManagerDecision()
        throws Exception {
        JdbcDataSource dataSource = dataSource("accepted-directive");
        try (Fixture fixture = fixture(
            new JdbcSpecialistChainExecutionRepository(dataSource, true),
            true
        )) {
            SpecialistChainDirective savedDirective = invokeOne(
                HEALTH,
                "Inspect health evidence."
            );
            persistExpiredRunning(
                fixture,
                "chain-recover-accepted",
                "recover-accepted",
                checkpoint(
                    SpecialistChainCheckpointPhase.DIRECTIVE_ACCEPTED,
                    List.of(),
                    List.of(),
                    1,
                    0,
                    Map.of(),
                    managerCheckpoint("manager-saved", savedDirective)
                )
            );
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var request = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                return delegationSuccess(
                    request.sourceExecution(),
                    request.targetSpecialistId(),
                    "Recovered health evidence."
                );
            });
            when(fixture.managerClient.execute(any())).thenAnswer(call ->
                groundedManagerSuccess(
                    "manager-after-worker",
                    "Recovered conclusion.",
                    managerInput(call)
                )
            );

            assertThat(fixture.gateway.recover().dispatched()).isOne();
            SpecialistChainExecutionResult result = awaitResult(
                fixture,
                "chain-recover-accepted"
            );

            assertThat(result.succeeded()).isTrue();
            assertThat(result.projectedResults()).hasSize(1);
            assertThat(result.steps())
                .extracting(SpecialistChainStepTrace::directiveType)
                .containsExactly(
                    SpecialistChainDirectiveType.INVOKE_ONE,
                    SpecialistChainDirectiveType.COMPLETE
                );
            verify(fixture.delegation).delegate(any(), any(), any());
            verify(fixture.managerClient).execute(any());
        }
    }

    @Test
    void recoveryPreservesCompletedWorkerBeforeNextManagerDecision()
        throws Exception {
        JdbcDataSource dataSource = dataSource("completed-worker");
        try (Fixture fixture = fixture(
            new JdbcSpecialistChainExecutionRepository(dataSource, true),
            true
        )) {
            SpecialistChainResultView resultView = resultView(
                HEALTH,
                "worker-health",
                "Persisted health evidence.",
                "result-health"
            );
            SpecialistChainStepTrace priorStep = completedStep(
                SpecialistChainDirectiveType.INVOKE_ONE,
                "manager-before-crash",
                null,
                List.of(workerTrace(HEALTH, resultView))
            );
            persistExpiredRunning(
                fixture,
                "chain-recover-worker",
                "recover-worker",
                checkpoint(
                    SpecialistChainCheckpointPhase.READY_FOR_MANAGER,
                    List.of(resultView),
                    List.of(priorStep),
                    1,
                    1,
                    Map.of(HEALTH.toString(), 1),
                    null
                )
            );
            when(fixture.managerClient.execute(any())).thenAnswer(call ->
                groundedManagerSuccess(
                    "manager-resumed",
                    "Persisted evidence was retained.",
                    managerInput(call)
                )
            );

            fixture.gateway.recover();
            SpecialistChainExecutionResult recovered = awaitResult(
                fixture,
                "chain-recover-worker"
            );

            assertThat(recovered.message())
                .isEqualTo("Persisted evidence was retained.");
            assertThat(recovered.projectedResults())
                .containsExactly(resultView);
            verify(fixture.delegation, never())
                .delegate(any(), any(), any());
            verify(fixture.managerClient).execute(any());
        }
    }

    @Test
    void recoveryPreservesCompletedParallelGroupWithoutRepeatingBranches()
        throws Exception {
        JdbcDataSource dataSource = dataSource("completed-parallel");
        try (Fixture fixture = fixture(
            new JdbcSpecialistChainExecutionRepository(dataSource, true),
            true
        )) {
            SpecialistChainResultView health = resultView(
                HEALTH,
                "worker-health",
                "Persisted health evidence.",
                "result-health"
            );
            SpecialistChainResultView change = resultView(
                CHANGE,
                "worker-change",
                "Persisted change evidence.",
                "result-change"
            );
            SpecialistChainStepTrace parallelStep = completedStep(
                SpecialistChainDirectiveType.INVOKE_PARALLEL,
                "manager-parallel-before-crash",
                "parallel-persisted",
                List.of(
                    workerTrace(HEALTH, health),
                    workerTrace(CHANGE, change)
                )
            );
            persistExpiredRunning(
                fixture,
                "chain-recover-parallel",
                "recover-parallel",
                checkpoint(
                    SpecialistChainCheckpointPhase.READY_FOR_MANAGER,
                    List.of(health, change),
                    List.of(parallelStep),
                    1,
                    2,
                    Map.of(HEALTH.toString(), 1, CHANGE.toString(), 1),
                    null
                )
            );
            when(fixture.managerClient.execute(any())).thenAnswer(call ->
                groundedManagerSuccess(
                    "manager-after-parallel",
                    "Both persisted branches were synthesized.",
                    managerInput(call)
                )
            );

            fixture.gateway.recover();
            SpecialistChainExecutionResult recovered = awaitResult(
                fixture,
                "chain-recover-parallel"
            );

            assertThat(recovered.projectedResults())
                .containsExactly(health, change);
            assertThat(recovered.succeeded()).isTrue();
            assertThat(recovered.steps().getFirst().parallelGroupId())
                .isEqualTo("parallel-persisted");
            verify(fixture.delegation, never())
                .delegate(any(), any(), any());
            verify(fixture.managerClient).execute(any());
        }
    }

    @Test
    void recoveryNeverRepeatsWorkerWhoseOutcomeIsUncertain()
        throws Exception {
        JdbcDataSource dataSource = dataSource("uncertain-worker");
        try (Fixture fixture = fixture(
            new JdbcSpecialistChainExecutionRepository(dataSource, true),
            true
        )) {
            SpecialistChainDirective directive = invokeOne(
                HEALTH,
                "Inspect health evidence."
            );
            persistExpiredRunning(
                fixture,
                "chain-recover-uncertain",
                "recover-uncertain",
                checkpoint(
                    SpecialistChainCheckpointPhase.WORKERS_IN_FLIGHT,
                    List.of(),
                    List.of(),
                    1,
                    0,
                    Map.of(),
                    managerCheckpoint("manager-before-worker", directive)
                )
            );

            fixture.gateway.recover();
            SpecialistChainExecutionResult recovered = awaitResult(
                fixture,
                "chain-recover-uncertain"
            );

            assertThat(recovered.status())
                .isEqualTo(SpecialistChainExecutionStatus.FAILED);
            assertThat(recovered.failure().reason())
                .isEqualTo("CHAIN_WORKER_OUTCOME_UNCERTAIN");
            assertThat(recovered.failure().publicMessage())
                .contains("was not rerun");
            verify(fixture.managerClient, never()).execute(any());
            verify(fixture.delegation, never())
                .delegate(any(), any(), any());
        }
    }

    @Test
    void recoveryRejectsAChangedChainDefinitionBeforeAnyModelWork()
        throws Exception {
        JdbcDataSource dataSource = dataSource("changed-definition");
        try (Fixture original = fixture(
            new JdbcSpecialistChainExecutionRepository(dataSource, true),
            true
        )) {
            persistExpiredRunning(
                original,
                "chain-recover-definition-change",
                "recover-definition-change",
                SpecialistChainCheckpoint.initial(
                    "Recover persisted investigation",
                    List.of(),
                    null,
                    0
                )
            );
        }

        try (Fixture changed = fixture(
            new JdbcSpecialistChainExecutionRepository(dataSource, false),
            true,
            "c".repeat(64),
            "b".repeat(64)
        )) {
            assertThat(changed.gateway.recover().dispatched()).isOne();
            SpecialistChainExecutionResult result = awaitResult(
                changed,
                "chain-recover-definition-change"
            );

            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.INVALID);
            assertThat(result.failure().reason())
                .isEqualTo("CHAIN_DEFINITION_CHANGED");
            verify(changed.managerClient, never()).execute(any());
            verify(changed.delegation, never())
                .delegate(any(), any(), any());
        }
    }

    @Test
    void recoveryPassesTrustedContextToCurrentWorkerAuthorization()
        throws Exception {
        JdbcDataSource dataSource = dataSource("current-authority");
        try (Fixture fixture = fixture(
            new JdbcSpecialistChainExecutionRepository(dataSource, true),
            true
        )) {
            SpecialistChainDirective savedDirective = invokeOne(
                HEALTH,
                "Inspect health evidence."
            );
            persistExpiredRunning(
                fixture,
                "chain-recover-authority",
                "recover-authority",
                checkpoint(
                    SpecialistChainCheckpointPhase.DIRECTIVE_ACCEPTED,
                    List.of(),
                    List.of(),
                    1,
                    0,
                    Map.of(),
                    managerCheckpoint("manager-saved", savedDirective)
                )
            );
            AtomicBoolean trustedContextSeen = new AtomicBoolean();
            when(fixture.delegation.delegate(
                any(),
                eq(String.class),
                eq(String.class)
            )).thenAnswer(invocation -> {
                var delegationRequest = (ai.fabric.execution.delegation
                    .SpecialistDelegationRequest<?, ?>)
                    invocation.getArgument(0);
                TrustedExecutionContext recoveredContext =
                    delegationRequest.trustedExecutionContext();
                trustedContextSeen.set(
                    "tenant-1".equals(recoveredContext.tenantId())
                        && "investigation-room".equals(
                            recoveredContext.deploymentId()
                        )
                        && "service-1".equals(
                            recoveredContext.initiator().principalId()
                        )
                        && "incident-42".equals(
                            recoveredContext.subject().subjectId()
                        )
                );
                return delegationFailure(
                    delegationRequest.sourceExecution(),
                    delegationRequest.targetSpecialistId(),
                    "SPECIALIST_ACCESS_DENIED",
                    "Current authority no longer permits this specialist."
                );
            });

            fixture.gateway.recover();
            SpecialistChainExecutionResult result = awaitResult(
                fixture,
                "chain-recover-authority"
            );

            assertThat(trustedContextSeen).isTrue();
            assertThat(result.status())
                .isEqualTo(SpecialistChainExecutionStatus.FAILED);
            assertThat(result.failure().reason())
                .isEqualTo("SPECIALIST_ACCESS_DENIED");
            verify(fixture.managerClient, never()).execute(any());
            verify(fixture.delegation).delegate(any(), any(), any());
        }
    }

    private Fixture fixture() {
        return fixture(
            new InMemorySpecialistChainExecutionRepository(),
            false
        );
    }

    private Fixture fixture(SpecialistChainDefinition<String> definition) {
        return fixture(
            new InMemorySpecialistChainExecutionRepository(),
            false,
            "a".repeat(64),
            "b".repeat(64),
            definition
        );
    }

    private Fixture fixture(
        SpecialistChainExecutionRepository repository,
        boolean durable
    ) {
        return fixture(
            repository,
            durable,
            "a".repeat(64),
            "b".repeat(64)
        );
    }

    private Fixture fixture(
        SpecialistChainExecutionRepository repository,
        boolean durable,
        String chainContentHash,
        String managerContentHash
    ) {
        return fixture(
            repository,
            durable,
            chainContentHash,
            managerContentHash,
            definition()
        );
    }

    private Fixture fixture(
        SpecialistChainExecutionRepository repository,
        boolean durable,
        String chainContentHash,
        String managerContentHash,
        SpecialistChainDefinition<String> definition
    ) {
        return fixture(
            repository,
            durable,
            chainContentHash,
            managerContentHash,
            definition,
            null,
            null
        );
    }

    private Fixture fixture(
        SpecialistChainExecutionRepository repository,
        boolean durable,
        String chainContentHash,
        String managerContentHash,
        SpecialistChainDefinition<String> definition,
        SharedInteractiveTurnCoordinator turnCoordinator,
        AIExecutionConversationRecorder conversationRecorder
    ) {
        ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();
        CanonicalJsonSupport canonicalJson =
            new CanonicalJsonSupport(objectMapper);
        SpecialistClientFactory clientFactory =
            mock(SpecialistClientFactory.class);
        @SuppressWarnings("unchecked")
        SpecialistClient<
            ai.fabric.execution.chain.SpecialistChainManagerInput,
            SpecialistChainDirective
        > managerClient = mock(SpecialistClient.class);
        when(clientFactory.bind(
            eq(MANAGER),
            eq(ai.fabric.execution.chain.SpecialistChainManagerInput.class),
            eq(SpecialistChainDirective.class)
        )).thenReturn(managerClient);
        SpecialistDelegationGateway delegation =
            mock(SpecialistDelegationGateway.class);
        SpecialistHandoffGateway handoff =
            mock(SpecialistHandoffGateway.class);
        RegisteredSpecialistChain registered =
            new RegisteredSpecialistChain(
                definition,
                chainContentHash,
                managerContentHash
            );
        SpecialistChainRegistry registry = new SpecialistChainRegistry() {
            @Override
            public Optional<RegisteredSpecialistChain> find(
                SpecialistChainId id
            ) {
                return CHAIN.equals(id)
                    ? Optional.of(registered)
                    : Optional.empty();
            }

            @Override
            public List<RegisteredSpecialistChain> list() {
                return List.of(registered);
            }
        };
        SpecialistChainSecurity security = new SpecialistChainSecurity(
            objectMapper,
            "encryption-secret-that-is-long-enough-123",
            "fingerprint-secret-that-is-long-enough-456"
        );
        var codec = new SpecialistChainPayloadCodec(
            objectMapper,
            registry,
            security
        );
        ExecutorService executor = Executors.newFixedThreadPool(4);
        AIExecutionProperties.SpecialistChains properties =
            new AIExecutionProperties().getSpecialistChains();
        properties.setAllowEphemeral(!durable);
        var gateway = new DefaultSpecialistChainGateway(
            registry,
            clientFactory,
            delegation,
            handoff,
            conversationRecorder,
            turnCoordinator,
            repository,
            codec,
            security,
            new TaskExecutorAdapter(executor),
            canonicalJson,
            Clock.systemUTC(),
            SpecialistChainMetrics.noop(),
            properties,
            durable
        );
        return new Fixture(
            gateway,
            managerClient,
            delegation,
            handoff,
            executor,
            repository,
            codec,
            security,
            registered
        );
    }

    private JdbcDataSource dataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:specialist-chain-" + name
                + ";DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");
        return dataSource;
    }

    private void persistExpiredRunning(
        Fixture fixture,
        String executionId,
        String key,
        SpecialistChainCheckpoint checkpoint
    ) {
        Instant now = Instant.now();
        SpecialistChainExecutionRequest<String> request =
            new SpecialistChainExecutionRequest<>(
                CHAIN,
                "Recover persisted investigation",
                context(),
                null,
                now.plusSeconds(30),
                key
            );
        SpecialistChainExecutionRecord queued =
            SpecialistChainExecutionRecord.queued(
                executionId,
                CHAIN,
                fixture.registered.contentHash(),
                MANAGER,
                fixture.registered.managerContentHash(),
                fixture.security.accessFingerprint(context()),
                fixture.security.idempotencyFingerprint(
                    context(),
                    CHAIN,
                    key
                ),
                fixture.security.canonicalHash(
                    Map.of("request", request.input(), "key", key)
                ),
                fixture.codec.protectRequest(executionId, request),
                fixture.codec.protectCheckpoint(executionId, checkpoint),
                request.deadline(),
                now.minusSeconds(5),
                Duration.ofMinutes(5)
            );
        SpecialistChainExecutionRecord expired = queued.claimed(
            "crashed-gateway",
            now.minusSeconds(4),
            now.minusSeconds(3)
        );
        fixture.repository.create(expired);
    }

    private SpecialistChainCheckpoint checkpoint(
        SpecialistChainCheckpointPhase phase,
        List<SpecialistChainResultView> results,
        List<SpecialistChainStepTrace> steps,
        int managerDecisions,
        int workerInvocations,
        Map<String, Integer> targetCounts,
        SpecialistChainManagerExecutionCheckpoint managerExecution
    ) {
        int characters = results.stream()
            .mapToInt(result -> result.summary().length())
            .sum();
        return new SpecialistChainCheckpoint(
            phase,
            "Recover persisted investigation",
            List.of(),
            results,
            steps,
            managerDecisions,
            workerInvocations,
            characters,
            targetCounts,
            managerExecution == null ? null : "d".repeat(64),
            managerExecution,
            null,
            0
        );
    }

    private SpecialistChainManagerExecutionCheckpoint managerCheckpoint(
        String invocationId,
        SpecialistChainDirective directive
    ) {
        Instant startedAt = Instant.now().minusMillis(20);
        return new SpecialistChainManagerExecutionCheckpoint(
            invocationId,
            directive,
            startedAt,
            startedAt.plusMillis(10)
        );
    }

    private SpecialistChainResultView resultView(
        SpecialistId specialist,
        String invocationId,
        String summary,
        String resultId
    ) {
        return new SpecialistChainResultView(
            resultId,
            specialist.toString(),
            invocationId,
            summary,
            Map.of(),
            List.of("evidence-" + resultId),
            "c".repeat(64),
            Instant.now().minusSeconds(2)
        );
    }

    private SpecialistChainWorkerTrace workerTrace(
        SpecialistId specialist,
        SpecialistChainResultView result
    ) {
        return new SpecialistChainWorkerTrace(
            specialist.toString(),
            "DELEGATION",
            result.workerInvocationId(),
            result.resultId(),
            SpecialistChainWorkerStatus.SUCCEEDED,
            result.evidenceReferenceIds(),
            null,
            result.completedAt().minusMillis(20),
            result.completedAt()
        );
    }

    private SpecialistChainStepTrace completedStep(
        SpecialistChainDirectiveType type,
        String managerInvocationId,
        String parallelGroupId,
        List<SpecialistChainWorkerTrace> workers
    ) {
        Instant startedAt = workers.getFirst().startedAt().minusMillis(10);
        return new SpecialistChainStepTrace(
            0,
            managerInvocationId,
            type,
            "Persisted approved manager decision.",
            parallelGroupId,
            workers,
            new SpecialistChainBudgetView(3, 1, 1, 7_000, 8_000),
            startedAt,
            workers.getLast().completedAt()
        );
    }

    private SpecialistChainExecutionResult awaitResult(
        Fixture fixture,
        String executionId
    ) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            Optional<SpecialistChainExecutionResult> result =
                fixture.gateway.findResult(executionId, context());
            if (result.isPresent()) {
                return result.orElseThrow();
            }
            Thread.sleep(10L);
        }
        throw new AssertionError(
            "Timed out waiting for chain result " + executionId
        );
    }

    private SpecialistChainDefinition<String> definition() {
        return definition(
            defaultLimits(),
            List.of(
                chainTarget(HEALTH, false),
                chainTarget(CHANGE, true)
            )
        );
    }

    private SpecialistChainDefinition<String> definition(
        SpecialistChainLimits limits,
        List<SpecialistChainTarget<String, ?, ?>> targets
    ) {
        return definition(
            limits,
            targets,
            SpecialistChainConversationPolicy.DISABLED
        );
    }

    private SpecialistChainDefinition<String> definition(
        SpecialistChainLimits limits,
        List<SpecialistChainTarget<String, ?, ?>> targets,
        SpecialistChainConversationPolicy conversationPolicy
    ) {
        return new SpecialistChainDefinition<>(
            CHAIN,
            MANAGER,
            String.class,
            inputAdapter(),
            targets,
            limits,
            conversationPolicy
        );
    }

    private SpecialistChainLimits defaultLimits() {
        return new SpecialistChainLimits(
            Duration.ofSeconds(10),
            4,
            3,
            2,
            1,
            8_000
        );
    }

    private SpecialistChainInputAdapter<String> inputAdapter() {
        return new SpecialistChainInputAdapter<>() {
            @Override
            public SpecialistChainComponentId id() {
                return SpecialistChainComponentId.of("chain-input", "1");
            }

            @Override
            public Class<String> inputType() {
                return String.class;
            }

            @Override
            public String currentUserMessage(String input) {
                return input;
            }
        };
    }

    private SpecialistChainTarget<String, String, String> chainTarget(
        SpecialistId specialist,
        boolean handoff
    ) {
        return chainTarget(specialist, handoff, false, false);
    }

    private SpecialistChainTarget<String, String, String> chainTarget(
        SpecialistId specialist,
        boolean handoff,
        boolean failMapping,
        boolean failProjection
    ) {
        return new SpecialistChainTarget<>(
            specialist,
            "Reads approved incident evidence.",
            new SpecialistChainTargetInputMapper<>() {
                @Override
                public SpecialistChainComponentId id() {
                    return SpecialistChainComponentId.of(
                        specialist.name() + "-input",
                        "1"
                    );
                }

                @Override
                public Class<String> chainRequestType() {
                    return String.class;
                }

                @Override
                public Class<String> targetInputType() {
                    return String.class;
                }

                @Override
                public String map(
                    String chainRequest,
                    SpecialistChainTargetRequest targetRequest
                ) {
                    if (failMapping) {
                        throw new IllegalArgumentException(
                            "application mapper rejected input"
                        );
                    }
                    return chainRequest + "\nObjective: "
                        + targetRequest.objective();
                }
            },
            new SpecialistChainTargetResultProjector<>() {
                @Override
                public SpecialistChainComponentId id() {
                    return SpecialistChainComponentId.of(
                        specialist.name() + "-result",
                        "1"
                    );
                }

                @Override
                public Class<String> chainRequestType() {
                    return String.class;
                }

                @Override
                public Class<String> targetOutputType() {
                    return String.class;
                }

                @Override
                public SpecialistChainResultProjection project(
                    String request,
                    AIExecutionResult<String> execution
                ) {
                    if (failProjection) {
                        throw new IllegalArgumentException(
                            "application projector rejected output"
                        );
                    }
                    return SpecialistChainResultProjection.summary(
                        execution.output()
                    );
                }
            },
            true,
            true,
            handoff
        );
    }

    private SpecialistChainExecutionRequest<String> request(
        String input,
        String key
    ) {
        return request(input, key, null);
    }

    private SpecialistChainExecutionRequest<String> request(
        String input,
        String key,
        Instant deadline
    ) {
        return new SpecialistChainExecutionRequest<>(
            CHAIN,
            input,
            context(),
            null,
            deadline,
            key
        );
    }

    private TrustedExecutionContext context() {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                "service-1",
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef("incident", "incident-42"),
            ExecutionSource.APPLICATION,
            "tenant-1",
            "investigation-room",
            Set.of("specialist:incident-manager@1"),
            "correlation-1",
            Instant.now()
        );
    }

    private TrustedExecutionContext interactiveContext() {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                "user-1",
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef("incident", "incident-42"),
            ExecutionSource.INTERACTIVE,
            "tenant-1",
            "investigation-room",
            Set.of("specialist:incident-manager@1"),
            "correlation-1",
            Instant.now()
        );
    }

    private SpecialistChainDirective invokeOne(
        SpecialistId target,
        String reason
    ) {
        return new SpecialistChainDirective(
            SpecialistChainDirectiveType.INVOKE_ONE,
            List.of(target(target, reason)),
            null,
            reason
        );
    }

    private SpecialistChainTargetRequest target(
        SpecialistId specialist,
        String objective
    ) {
        return new SpecialistChainTargetRequest(
            specialist.toString(),
            objective
        );
    }

    private SpecialistChainDirective directive(
        SpecialistChainDirectiveType type,
        List<SpecialistChainTargetRequest> targets,
        String message
    ) {
        return new SpecialistChainDirective(
            type,
            targets,
            message,
            "The approved state supports this decision."
        );
    }

    private AIExecutionResult<SpecialistChainDirective> managerSuccess(
        String invocationId,
        SpecialistChainDirective directive
    ) {
        Instant now = Instant.now();
        return new AIExecutionResult<>(
            invocationId,
            MANAGER,
            AIExecutionStatus.SUCCEEDED,
            directive,
            List.of(),
            Map.of("specialistContentHash", "b".repeat(64)),
            null,
            now,
            now
        );
    }

    @SuppressWarnings("unchecked")
    private SpecialistChainManagerInput managerInput(
        org.mockito.invocation.InvocationOnMock invocation
    ) {
        return ((SpecialistInvocation<SpecialistChainManagerInput>)
            invocation.getArgument(0)).input();
    }

    private AIExecutionResult<SpecialistChainDirective>
        groundedManagerSuccess(
            String invocationId,
            String message,
            SpecialistChainManagerInput input
        ) {
        return managerSuccess(
            invocationId,
            new SpecialistChainDirective(
                SpecialistChainDirectiveType.COMPLETE,
                List.of(),
                message,
                "Every approved worker projection supports the final response.",
                input.completedResults().stream()
                    .map(SpecialistChainResultView::resultId)
                    .toList()
            )
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SpecialistDelegationResult delegationSuccess(
        AIExecutionResult<?> source,
        SpecialistId target,
        String output
    ) {
        Instant now = Instant.now();
        AIExecutionResult<String> worker = workerSuccess(target, output);
        return new SpecialistDelegationResult<>(
            "delegation-" + target.name(),
            source.invocationId(),
            MANAGER,
            target,
            1,
            AIExecutionStatus.SUCCEEDED,
            source.output(),
            worker,
            null,
            false,
            now,
            now
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SpecialistDelegationResult delegationFailure(
        AIExecutionResult<?> source,
        SpecialistId target,
        String reason,
        String message
    ) {
        Instant now = Instant.now();
        return new SpecialistDelegationResult<>(
            "delegation-" + target.name(),
            source.invocationId(),
            MANAGER,
            target,
            1,
            AIExecutionStatus.FAILED,
            source.output(),
            null,
            new SpecialistDelegationFailure(reason, message, true),
            false,
            now,
            now
        );
    }

    private AIExecutionResult<String> workerSuccess(
        SpecialistId specialist,
        String output
    ) {
        Instant now = Instant.now();
        return new AIExecutionResult<>(
            "worker-" + specialist.name(),
            specialist,
            AIExecutionStatus.SUCCEEDED,
            output,
            List.of(),
            Map.of(),
            null,
            now,
            now
        );
    }

    private record Fixture(
        DefaultSpecialistChainGateway gateway,
        SpecialistClient<
            ai.fabric.execution.chain.SpecialistChainManagerInput,
            SpecialistChainDirective
        > managerClient,
        SpecialistDelegationGateway delegation,
        SpecialistHandoffGateway handoff,
        ExecutorService executor,
        SpecialistChainExecutionRepository repository,
        SpecialistChainPayloadCodec codec,
        SpecialistChainSecurity security,
        RegisteredSpecialistChain registered
    ) implements AutoCloseable {
        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
