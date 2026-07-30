package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.delegation.SpecialistDelegationGateway;
import ai.fabric.execution.delegation.SpecialistDelegationResult;
import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerContextValue;
import ai.fabric.execution.manager.ConversationManagerDefinition;
import ai.fabric.execution.manager.ConversationManagerDirective;
import ai.fabric.execution.manager.ConversationManagerDirectiveType;
import ai.fabric.execution.manager.ConversationManagerId;
import ai.fabric.execution.manager.ConversationManagerInput;
import ai.fabric.execution.manager.ConversationManagerInputAdapter;
import ai.fabric.execution.manager.ConversationManagerRegistry;
import ai.fabric.execution.manager.ConversationManagerTarget;
import ai.fabric.execution.manager.ConversationManagerTargetInputMapper;
import ai.fabric.execution.manager.ConversationManagerTargetResultProjector;
import ai.fabric.execution.manager.ConversationManagerTurnRequest;
import ai.fabric.execution.manager.ConversationManagerTurnStatus;
import ai.fabric.execution.manager.RegisteredConversationManager;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistDelegationPolicy;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistIdentity;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistInstructions;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.SpecialistWritePolicy;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistConversationBinding;
import ai.fabric.execution.specialist.manifest.SpecialistInteractionCapability;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultConversationManagerGatewayTest {

    private static final ConversationManagerId MANAGER_ID =
        ConversationManagerId.of("account-conversation", "1");
    private static final SpecialistId MANAGER =
        SpecialistId.of("account-manager", "1");
    private static final SpecialistId WORKER =
        SpecialistId.of("account-read", "1");
    private static final Instant NOW =
        Instant.parse("2026-07-29T12:00:00Z");

    @Test
    void recordsOneManagerQuestionWithoutExposingTheDirective() {
        Fixture fixture = fixture();
        when(fixture.managerClient.execute(any())).thenReturn(
            managerSuccess(new ConversationManagerDirective(
                ConversationManagerDirectiveType.ASK_USER,
                null,
                "Which billing issue should I inspect?",
                "The request is ambiguous."
            ))
        );

        var result = fixture.gateway.execute(request("Help me", "request-1"));

        assertThat(result.status())
            .isEqualTo(ConversationManagerTurnStatus.ASKED_USER);
        assertThat(result.message())
            .isEqualTo("Which billing issue should I inspect?");
        assertThat(result.selectedTarget()).isNull();
        verify(fixture.recorder).record(
            eq(binding()),
            eq("Help me"),
            eq("Which billing issue should I inspect?"),
            anyMap()
        );
        verify(fixture.delegation, never())
            .delegate(any(), any(), any());
    }

    @Test
    void recordsOneDirectManagerCompletion() {
        Fixture fixture = fixture();
        when(fixture.managerClient.execute(any())).thenReturn(
            managerSuccess(new ConversationManagerDirective(
                ConversationManagerDirectiveType.COMPLETE,
                null,
                "This request is outside the resolver scope.",
                "No approved worker can answer it."
            ))
        );

        var result = fixture.gateway.execute(
            request("Write a poem", "request-1")
        );

        assertThat(result.status())
            .isEqualTo(ConversationManagerTurnStatus.COMPLETED);
        assertThat(result.message())
            .isEqualTo("This request is outside the resolver scope.");
        verify(fixture.recorder).record(
            eq(binding()),
            eq("Write a poem"),
            eq("This request is outside the resolver scope."),
            anyMap()
        );
    }

    @Test
    void invokesAndProjectsOneApprovedReadOnlyWorker() {
        Fixture fixture = fixture();
        ConversationManagerDirective directive =
            new ConversationManagerDirective(
                ConversationManagerDirectiveType.INVOKE_SPECIALIST,
                WORKER.toString(),
                null,
                "Current account evidence is required."
            );
        AIExecutionResult<String> workerExecution = workerSuccess(
            "Payment verification is missing."
        );
        when(fixture.managerClient.execute(any()))
            .thenReturn(managerSuccess(directive));
        when(fixture.delegation.delegate(
            any(),
            eq(String.class),
            eq(String.class)
        )).thenReturn(new SpecialistDelegationResult<>(
            "delegation-1",
            "manager-invocation-1",
            MANAGER,
            WORKER,
            1,
            AIExecutionStatus.SUCCEEDED,
            directive,
            workerExecution,
            null,
            false,
            NOW,
            NOW
        ));

        var result = fixture.gateway.execute(
            request("Why is checkout blocked?", "request-1")
        );

        assertThat(result.status())
            .isEqualTo(ConversationManagerTurnStatus.SPECIALIST_RESULT);
        assertThat(result.message())
            .isEqualTo("Payment verification is missing.");
        assertThat(result.selectedTarget()).isEqualTo(WORKER);
        assertThat(result.workerInvocationId())
            .isEqualTo("worker-invocation-1");
        verify(fixture.recorder).record(
            eq(binding()),
            eq("Why is checkout blocked?"),
            eq("Payment verification is missing."),
            anyMap()
        );
    }

    @Test
    void rejectsAnInventedTargetWithoutFallbackOrRecording() {
        Fixture fixture = fixture();
        when(fixture.managerClient.execute(any())).thenReturn(
            managerSuccess(new ConversationManagerDirective(
                ConversationManagerDirectiveType.INVOKE_SPECIALIST,
                "invented-worker@1",
                null,
                "The model invented a target."
            ))
        );

        var result = fixture.gateway.execute(request("Help", "request-1"));

        assertThat(result.status())
            .isEqualTo(ConversationManagerTurnStatus.DENIED);
        assertThat(result.failure().reason())
            .isEqualTo("MANAGER_TARGET_NOT_ALLOWED");
        verify(fixture.delegation, never())
            .delegate(any(), any(), any());
        verify(fixture.recorder, never())
            .record(any(), any(), any(), anyMap());
    }

    @Test
    void preservesProviderFailureWithoutRecordingOrFallback() {
        Fixture fixture = fixture();
        when(fixture.managerClient.execute(any())).thenReturn(
            new AIExecutionResult<>(
                "manager-invocation-1",
                MANAGER,
                AIExecutionStatus.FAILED,
                null,
                List.of(),
                Map.of("provider", "openai"),
                new AIExecutionFailure(
                    "PROVIDER_CALL_FAILED",
                    "The configured provider call failed.",
                    true
                ),
                NOW,
                NOW
            )
        );

        var result = fixture.gateway.execute(request("Help", "request-1"));

        assertThat(result.status())
            .isEqualTo(ConversationManagerTurnStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("PROVIDER_CALL_FAILED");
        verify(fixture.recorder, never())
            .record(any(), any(), any(), anyMap());
    }

    @Test
    void exposesMapperAndProjectorFailuresWithoutRecording() {
        @SuppressWarnings("unchecked")
        ConversationManagerTargetInputMapper<String, String> failingMapper =
            mock(ConversationManagerTargetInputMapper.class);
        when(failingMapper.id()).thenReturn(
            ConversationManagerComponentId.of("failing-input", "1")
        );
        when(failingMapper.managerRequestType()).thenReturn(String.class);
        when(failingMapper.targetInputType()).thenReturn(String.class);
        when(failingMapper.map(any())).thenThrow(
            new IllegalArgumentException("unsafe mapping")
        );
        Fixture mapperFixture = fixture(
            managerPlan(failingMapper, targetProjector()),
            clock(),
            Duration.ofMinutes(5)
        );
        ConversationManagerDirective directive = invokeWorkerDirective();
        when(mapperFixture.managerClient.execute(any())).thenReturn(
            managerSuccess(directive)
        );

        var mapperResult = mapperFixture.gateway.execute(
            request("Inspect my account", "mapper-failure")
        );

        assertThat(mapperResult.status())
            .isEqualTo(ConversationManagerTurnStatus.INVALID);
        assertThat(mapperResult.failure().reason())
            .isEqualTo("MANAGER_TARGET_INPUT_INVALID");
        verify(mapperFixture.delegation, never())
            .delegate(any(), any(), any());
        verify(mapperFixture.recorder, never())
            .record(any(), any(), any(), anyMap());

        @SuppressWarnings("unchecked")
        ConversationManagerTargetResultProjector<String, String>
            failingProjector = mock(
                ConversationManagerTargetResultProjector.class
            );
        when(failingProjector.id()).thenReturn(
            ConversationManagerComponentId.of("failing-result", "1")
        );
        when(failingProjector.managerRequestType()).thenReturn(String.class);
        when(failingProjector.targetOutputType()).thenReturn(String.class);
        when(failingProjector.project(any(), any())).thenThrow(
            new IllegalArgumentException("unsafe projection")
        );
        Fixture projectorFixture = fixture(
            managerPlan(targetMapper(), failingProjector),
            clock(),
            Duration.ofMinutes(5)
        );
        when(projectorFixture.managerClient.execute(any())).thenReturn(
            managerSuccess(directive)
        );
        when(projectorFixture.delegation
            .<ConversationManagerDirective, String, String>delegate(
            any(),
            eq(String.class),
            eq(String.class)
        )).thenReturn(delegationSuccess(
            directive,
            workerSuccess("internal worker result")
        ));

        var projectorResult = projectorFixture.gateway.execute(
            request("Inspect my account", "projector-failure")
        );

        assertThat(projectorResult.status())
            .isEqualTo(ConversationManagerTurnStatus.INVALID);
        assertThat(projectorResult.failure().reason())
            .isEqualTo("MANAGER_TARGET_PROJECTION_INVALID");
        verify(projectorFixture.recorder, never())
            .record(any(), any(), any(), anyMap());
    }

    @Test
    void replaysExactlyAndRejectsChangedPayload() {
        Fixture fixture = fixture();
        when(fixture.managerClient.execute(any())).thenReturn(
            managerSuccess(new ConversationManagerDirective(
                ConversationManagerDirectiveType.COMPLETE,
                null,
                "Done.",
                "No worker is needed."
            ))
        );

        var first = fixture.gateway.execute(
            request("First message", "request-1")
        );
        var replay = fixture.gateway.execute(
            request("First message", "request-1")
        );
        var conflict = fixture.gateway.execute(
            request("Changed message", "request-1")
        );

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.turnId()).isEqualTo(first.turnId());
        assertThat(conflict.status())
            .isEqualTo(ConversationManagerTurnStatus.INVALID);
        assertThat(conflict.failure().reason())
            .isEqualTo("MANAGER_IDEMPOTENCY_CONFLICT");
        verify(fixture.managerClient).execute(any());
        verify(fixture.recorder).record(
            any(),
            any(),
            any(),
            anyMap()
        );
    }

    @Test
    void enforcesDeadlineAndThreadInterruptionWithoutInvocation() {
        Fixture deadlineFixture = fixture();
        ConversationManagerTurnRequest<String> expired =
            new ConversationManagerTurnRequest<>(
                MANAGER_ID,
                "Inspect my account",
                context(),
                binding(),
                NOW,
                "expired-request"
            );

        var deadlineResult = deadlineFixture.gateway.execute(expired);

        assertThat(deadlineResult.status())
            .isEqualTo(ConversationManagerTurnStatus.DEADLINE_EXCEEDED);
        verify(deadlineFixture.managerClient, never()).execute(any());
        verify(deadlineFixture.recorder, never())
            .record(any(), any(), any(), anyMap());

        Fixture cancellationFixture = fixture();
        Thread.currentThread().interrupt();
        try {
            var cancelled = cancellationFixture.gateway.execute(
                request("Inspect my account", "cancelled-request")
            );

            assertThat(cancelled.status())
                .isEqualTo(ConversationManagerTurnStatus.CANCELLED);
            assertThat(cancelled.failure().reason())
                .isEqualTo("MANAGER_TURN_CANCELLED");
            verify(cancellationFixture.managerClient, never()).execute(any());
            verify(cancellationFixture.recorder, never())
                .record(any(), any(), any(), anyMap());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void expiresOnlyCompletedReplayEntriesAfterTheConfiguredTtl() {
        MutableClock mutableClock = new MutableClock(NOW);
        Fixture fixture = fixture(
            managerPlan(),
            mutableClock,
            Duration.ofMinutes(5)
        );
        when(fixture.managerClient.execute(any())).thenReturn(
            managerSuccess(new ConversationManagerDirective(
                ConversationManagerDirectiveType.COMPLETE,
                null,
                "Done.",
                "No worker is needed."
            ))
        );

        var first = fixture.gateway.execute(
            request("Inspect my account", "expiring-request")
        );
        mutableClock.advance(Duration.ofMinutes(6));
        var afterExpiry = fixture.gateway.execute(
            request("Inspect my account", "expiring-request")
        );

        assertThat(first.replayed()).isFalse();
        assertThat(afterExpiry.replayed()).isFalse();
        assertThat(afterExpiry.turnId()).isNotEqualTo(first.turnId());
        verify(fixture.managerClient, times(2)).execute(any());
        verify(fixture.recorder, times(2))
            .record(any(), any(), any(), anyMap());
    }

    @Test
    void rejectsNewWorkWhenTheBoundedReplayCapacityIsExhausted() {
        Fixture fixture = fixture(
            managerPlan(),
            clock(),
            1,
            Duration.ofMinutes(5)
        );
        when(fixture.managerClient.execute(any())).thenReturn(
            managerSuccess(new ConversationManagerDirective(
                ConversationManagerDirectiveType.COMPLETE,
                null,
                "Done.",
                "No worker is needed."
            ))
        );

        var first = fixture.gateway.execute(
            request("Inspect my account", "capacity-request-1")
        );
        var rejected = fixture.gateway.execute(
            request("Inspect my account", "capacity-request-2")
        );

        assertThat(first.succeeded()).isTrue();
        assertThat(rejected.status())
            .isEqualTo(ConversationManagerTurnStatus.FAILED);
        assertThat(rejected.failure().reason())
            .isEqualTo("MANAGER_CAPACITY_EXCEEDED");
        assertThat(rejected.failure().retryable()).isTrue();
        verify(fixture.managerClient).execute(any());
        verify(fixture.recorder).record(
            any(),
            any(),
            any(),
            anyMap()
        );
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        return fixture(
            managerPlan(),
            clock(),
            100,
            Duration.ofMinutes(5)
        );
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(
        ConversationManagerDefinition<String> definition,
        Clock fixtureClock,
        Duration resultTtl
    ) {
        return fixture(definition, fixtureClock, 100, resultTtl);
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(
        ConversationManagerDefinition<String> definition,
        Clock fixtureClock,
        int maxActive,
        Duration resultTtl
    ) {
        SpecialistDefinition<
            ConversationManagerInput,
            ConversationManagerDirective
        > managerDefinition = managerDefinition();
        SpecialistRegistry specialists = specialistRegistry(
            managerDefinition
        );
        SharedInteractiveTurnCoordinator coordinator =
            new SharedInteractiveTurnCoordinator(
                specialists,
                snapshotProvider(),
                new EphemeralAIExecutionConversationSnapshotRegistry(
                    clock(),
                    Duration.ofMinutes(2)
                ),
                canonicalJson()
            );
        RegisteredConversationManager registered =
            new RegisteredConversationManager(
                definition,
                "b".repeat(64)
            );
        ConversationManagerRegistry managers =
            new ConversationManagerRegistry() {
                @Override
                public Optional<RegisteredConversationManager> find(
                    ConversationManagerId id
                ) {
                    return MANAGER_ID.equals(id)
                        ? Optional.of(registered)
                        : Optional.empty();
                }

                @Override
                public List<RegisteredConversationManager> list() {
                    return List.of(registered);
                }
            };
        SpecialistClientFactory clients =
            mock(SpecialistClientFactory.class);
        SpecialistClient<
            ConversationManagerInput,
            ConversationManagerDirective
        > managerClient = mock(SpecialistClient.class);
        when(clients.bind(
            MANAGER,
            ConversationManagerInput.class,
            ConversationManagerDirective.class
        )).thenReturn(managerClient);
        SpecialistDelegationGateway delegation =
            mock(SpecialistDelegationGateway.class);
        AIExecutionConversationRecorder recorder =
            mock(AIExecutionConversationRecorder.class);
        DefaultConversationManagerGateway gateway =
            new DefaultConversationManagerGateway(
                managers,
                clients,
                delegation,
                recorder,
                coordinator,
                canonicalJson(),
                fixtureClock,
                maxActive,
                resultTtl
            );
        return new Fixture(
            gateway,
            managerClient,
            delegation,
            recorder
        );
    }

    private ConversationManagerDefinition<String> managerPlan() {
        return managerPlan(targetMapper(), targetProjector());
    }

    private ConversationManagerDefinition<String> managerPlan(
        ConversationManagerTargetInputMapper<String, String> inputMapper,
        ConversationManagerTargetResultProjector<String, String>
            resultProjector
    ) {
        return new ConversationManagerDefinition<>(
            MANAGER_ID,
            MANAGER,
            String.class,
            new ConversationManagerInputAdapter<>() {
                @Override
                public ConversationManagerComponentId id() {
                    return ConversationManagerComponentId.of(
                        "manager-input",
                        "1"
                    );
                }

                @Override
                public Class<String> inputType() {
                    return String.class;
                }

                @Override
                public String currentUserMessage(String input) {
                    return input;
                }

                @Override
                public List<ConversationManagerContextValue>
                    applicationContext(String input) {
                    return List.of(new ConversationManagerContextValue(
                        "account",
                        "current"
                    ));
                }
            },
            List.of(new ConversationManagerTarget<>(
                WORKER,
                "Read the current account.",
                inputMapper,
                resultProjector
            )),
            Duration.ofSeconds(30)
        );
    }

    private ConversationManagerTargetInputMapper<String, String>
        targetMapper() {
        return new ConversationManagerTargetInputMapper<>() {
            @Override
            public ConversationManagerComponentId id() {
                return ConversationManagerComponentId.of(
                    "target-input",
                    "1"
                );
            }

            @Override
            public Class<String> managerRequestType() {
                return String.class;
            }

            @Override
            public Class<String> targetInputType() {
                return String.class;
            }

            @Override
            public String map(String request) {
                return request;
            }
        };
    }

    private ConversationManagerTargetResultProjector<String, String>
        targetProjector() {
        return new ConversationManagerTargetResultProjector<>() {
            @Override
            public ConversationManagerComponentId id() {
                return ConversationManagerComponentId.of(
                    "target-result",
                    "1"
                );
            }

            @Override
            public Class<String> managerRequestType() {
                return String.class;
            }

            @Override
            public Class<String> targetOutputType() {
                return String.class;
            }

            @Override
            public String project(
                String request,
                AIExecutionResult<String> targetExecution
            ) {
                return targetExecution.output();
            }
        };
    }

    private SpecialistDefinition<
        ConversationManagerInput,
        ConversationManagerDirective
    > managerDefinition() {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                MANAGER,
                "Account manager",
                "Routes one bounded account question."
            ),
            new SpecialistInstructions("Select one directive.", null),
            new SpecialistExecutionProfile(
                "manager",
                new RequestedCapabilityProfile(
                    false,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of()
                ),
                ExecutionStrategy.SINGLE_PASS,
                SpecialistWritePolicy.DISABLED
            ),
            SpecialistLimits.defaults(),
            SpecialistDelegationPolicy.oneLevel(Set.of(WORKER)),
            new SpecialistInputAdapter<>() {
                @Override
                public Class<ConversationManagerInput> inputType() {
                    return ConversationManagerInput.class;
                }

                @Override
                public void validate(ConversationManagerInput input) {}

                @Override
                public String renderModelInput(
                    ConversationManagerInput input
                ) {
                    return input.currentUserMessage();
                }

                @Override
                public String conversationInput(
                    ConversationManagerInput input
                ) {
                    return input.currentUserMessage();
                }

                @Override
                public SpecialistConversationBinding
                    conversationBinding() {
                    return SpecialistConversationBinding.REQUIRED;
                }

                @Override
                public boolean recordValidatedTurns() {
                    return false;
                }

                @Override
                public SpecialistInteractionCapability
                    interactionCapability() {
                    return SpecialistInteractionCapability.DIALOGUE_CAPABLE;
                }
            },
            new SpecialistOutputAdapter<>() {
                @Override
                public Class<ConversationManagerDirective> outputType() {
                    return ConversationManagerDirective.class;
                }

                @Override
                public ConversationManagerDirective project(
                    OrchestrationResult result,
                    List<AIEvidenceReference> evidence
                ) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void validate(
                    ConversationManagerDirective output
                ) {}
            }
        );
    }

    private SpecialistRegistry specialistRegistry(
        SpecialistDefinition<?, ?> manager
    ) {
        return new SpecialistRegistry() {
            @Override
            public Optional<SpecialistDefinition<?, ?>> find(
                SpecialistId id
            ) {
                return MANAGER.equals(id)
                    ? Optional.of(manager)
                    : Optional.empty();
            }

            @Override
            public List<SpecialistDefinition<?, ?>> list() {
                return List.of(manager);
            }
        };
    }

    private AIExecutionConversationSnapshotProvider snapshotProvider() {
        return (binding, turnId, owner) ->
            new ApprovedConversationSnapshot(
                turnId,
                binding.userId(),
                binding.conversationId(),
                owner.toString(),
                "a".repeat(64),
                2,
                List.of(),
                NOW
            );
    }

    private ConversationManagerTurnRequest<String> request(
        String input,
        String idempotencyKey
    ) {
        return new ConversationManagerTurnRequest<>(
            MANAGER_ID,
            input,
            context(),
            binding(),
            null,
            idempotencyKey
        );
    }

    private ConversationBinding binding() {
        return new ConversationBinding("user-1", "conversation-1");
    }

    private TrustedExecutionContext context() {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                "user-1",
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef("account", "account-42"),
            ExecutionSource.INTERACTIVE,
            "tenant-1",
            "resolver-app",
            Set.of("specialist:account-manager@1"),
            "correlation-1",
            NOW
        );
    }

    private AIExecutionResult<ConversationManagerDirective>
        managerSuccess(ConversationManagerDirective directive) {
        return new AIExecutionResult<>(
            "manager-invocation-1",
            MANAGER,
            AIExecutionStatus.SUCCEEDED,
            directive,
            List.of(),
            Map.of("specialistContentHash", "c".repeat(64)),
            null,
            NOW,
            NOW
        );
    }

    private AIExecutionResult<String> workerSuccess(String output) {
        return new AIExecutionResult<>(
            "worker-invocation-1",
            WORKER,
            AIExecutionStatus.SUCCEEDED,
            output,
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW
        );
    }

    private ConversationManagerDirective invokeWorkerDirective() {
        return new ConversationManagerDirective(
            ConversationManagerDirectiveType.INVOKE_SPECIALIST,
            WORKER.toString(),
            null,
            "Current account evidence is required."
        );
    }

    private SpecialistDelegationResult<ConversationManagerDirective, String>
        delegationSuccess(
            ConversationManagerDirective directive,
            AIExecutionResult<String> workerExecution
        ) {
        return new SpecialistDelegationResult<>(
            "delegation-1",
            "manager-invocation-1",
            MANAGER,
            WORKER,
            1,
            AIExecutionStatus.SUCCEEDED,
            directive,
            workerExecution,
            null,
            false,
            NOW,
            NOW
        );
    }

    private CanonicalJsonSupport canonicalJson() {
        return new CanonicalJsonSupport(
            new ObjectMapper().findAndRegisterModules()
        );
    }

    private Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private record Fixture(
        DefaultConversationManagerGateway gateway,
        SpecialistClient<
            ConversationManagerInput,
            ConversationManagerDirective
        > managerClient,
        SpecialistDelegationGateway delegation,
        AIExecutionConversationRecorder recorder
    ) {}
}
