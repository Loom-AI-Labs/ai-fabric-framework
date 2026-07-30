package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistIdentity;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistInstructions;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.SpecialistWritePolicy;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultAIInteractiveExecutionGatewayTest {

    private static final SpecialistId SPECIALIST_ID =
        SpecialistId.of("account-resolver", "1");
    private static final Instant NOW =
        Instant.parse("2026-07-29T12:00:00Z");

    @Test
    void approvesOneFrozenSnapshotThenUsesTheNormalExecutionGateway() {
        AIExecutionGateway execution = mock(AIExecutionGateway.class);
        AtomicReference<AIExecutionRequest<?>> observed =
            new AtomicReference<>();
        when(execution.submit(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            observed.set(request);
            return handle(ExecutionHandleStatus.RUNNING, null);
        });
        when(execution.find(any(), any())).thenReturn(Optional.of(
            new ExecutionSnapshot(
                handle(ExecutionHandleStatus.SUCCEEDED, null),
                success(SPECIALIST_ID)
            )
        ));
        EphemeralAIExecutionConversationSnapshotRegistry snapshots =
            snapshots();
        DefaultAIInteractiveExecutionGateway gateway = gateway(
            execution,
            definition(SpecialistInteractionCapability.DIALOGUE_CAPABLE),
            snapshotProvider(),
            snapshots
        );

        AIExecutionResult<String> result = gateway.execute(request());

        assertThat(result.succeeded()).isTrue();
        ConversationBinding approved =
            observed.get().conversationBinding();
        assertThat(approved.userId()).isEqualTo("user-1");
        assertThat(approved.conversationId())
            .isEqualTo("conversation-1");
        assertThat(approved.approvedSnapshotToken())
            .startsWith("snapshot-");
        assertThatThrownBy(() -> snapshots.consume(approved))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unavailable");
    }

    @Test
    void rejectsNonInteractiveSpecialistAndCallerOwnedSnapshotToken() {
        AIExecutionGateway execution = mock(AIExecutionGateway.class);
        DefaultAIInteractiveExecutionGateway ineligible = gateway(
            execution,
            definition(SpecialistInteractionCapability.NON_INTERACTIVE),
            snapshotProvider(),
            snapshots()
        );

        AIExecutionResult<String> capabilityFailure =
            ineligible.execute(request());
        AIExecutionRequest<String> forged = new AIExecutionRequest<>(
            SPECIALIST_ID,
            "Inspect my account",
            interactiveContext(),
            new ConversationBinding(
                "user-1",
                "conversation-1",
                "caller-supplied"
            ),
            null,
            "request-1"
        );
        AIExecutionResult<String> tokenFailure =
            ineligible.execute(forged);

        assertThat(capabilityFailure.status())
            .isEqualTo(AIExecutionStatus.INVALID);
        assertThat(capabilityFailure.failure().reason())
            .isEqualTo("DIALOGUE_OWNER_INELIGIBLE");
        assertThat(tokenFailure.failure().reason())
            .isEqualTo("SNAPSHOT_TOKEN_NOT_ACCEPTED");
        verify(execution, never()).submit(any());
    }

    @Test
    void exposesSnapshotFailureWithoutCallingExecution() {
        AIExecutionGateway execution = mock(AIExecutionGateway.class);
        DefaultAIInteractiveExecutionGateway gateway = gateway(
            execution,
            definition(SpecialistInteractionCapability.DIALOGUE_CAPABLE),
            (binding, turnId, owner) -> {
                throw new IllegalStateException("chat store unavailable");
            },
            snapshots()
        );

        AIExecutionResult<String> result = gateway.execute(request());

        assertThat(result.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(result.failure().reason())
            .isEqualTo("CONVERSATION_SNAPSHOT_FAILED");
        assertThat(result.failure().publicMessage())
            .doesNotContain("chat store unavailable");
        verify(execution, never()).submit(any());
    }

    @Test
    void exposesAnIdempotencyConflictWithoutRetryingExecution() {
        AIExecutionGateway execution = mock(AIExecutionGateway.class);
        when(execution.submit(any())).thenReturn(
            handle(
                ExecutionHandleStatus.REJECTED,
                "IDEMPOTENCY_CONFLICT"
            )
        );
        when(execution.find(any(), any())).thenReturn(Optional.empty());
        DefaultAIInteractiveExecutionGateway gateway = gateway(
            execution,
            definition(SpecialistInteractionCapability.DIALOGUE_CAPABLE),
            snapshotProvider(),
            snapshots()
        );

        AIExecutionResult<String> result = gateway.execute(request());

        assertThat(result.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(result.failure().reason())
            .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(result.failure().retryable()).isFalse();
    }

    @Test
    void surfacesTheLatestTerminalHandleWhenNoResultWasStored() {
        AIExecutionGateway execution = mock(AIExecutionGateway.class);
        when(execution.submit(any())).thenReturn(
            handle(ExecutionHandleStatus.RUNNING, null)
        );
        when(execution.find(any(), any())).thenReturn(Optional.of(
            new ExecutionSnapshot(
                handle(ExecutionHandleStatus.REJECTED, "QUEUE_REJECTED"),
                null
            )
        ));
        DefaultAIInteractiveExecutionGateway gateway = gateway(
            execution,
            definition(SpecialistInteractionCapability.DIALOGUE_CAPABLE),
            snapshotProvider(),
            snapshots()
        );

        AIExecutionResult<String> result = gateway.execute(request());

        assertThat(result.status()).isEqualTo(AIExecutionStatus.FAILED);
        assertThat(result.failure().reason()).isEqualTo("QUEUE_REJECTED");
        assertThat(result.failure().retryable()).isTrue();
    }

    @Test
    void preservesTheUnderlyingProviderFailureWithoutFallback() {
        AIExecutionGateway execution = mock(AIExecutionGateway.class);
        when(execution.submit(any())).thenReturn(
            handle(ExecutionHandleStatus.RUNNING, null)
        );
        AIExecutionResult<String> providerFailure =
            new AIExecutionResult<>(
                "invocation-1",
                SPECIALIST_ID,
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
            );
        when(execution.find(any(), any())).thenReturn(Optional.of(
            new ExecutionSnapshot(
                handle(ExecutionHandleStatus.FAILED, "PROVIDER_CALL_FAILED"),
                providerFailure
            )
        ));
        DefaultAIInteractiveExecutionGateway gateway = gateway(
            execution,
            definition(SpecialistInteractionCapability.DIALOGUE_CAPABLE),
            snapshotProvider(),
            snapshots()
        );

        AIExecutionResult<String> result = gateway.execute(request());

        assertThat(result).isSameAs(providerFailure);
        assertThat(result.failure().reason())
            .isEqualTo("PROVIDER_CALL_FAILED");
    }

    @Test
    void permitsOnlyOneActiveTurnForTheSameConversation() throws Exception {
        AIExecutionGateway execution = mock(AIExecutionGateway.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(execution.submit(any())).thenReturn(
            handle(ExecutionHandleStatus.RUNNING, null)
        );
        when(execution.find(any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                    "test execution timed out"
                );
            }
            return Optional.of(new ExecutionSnapshot(
                handle(ExecutionHandleStatus.SUCCEEDED, null),
                success(SPECIALIST_ID)
            ));
        });
        DefaultAIInteractiveExecutionGateway gateway = gateway(
            execution,
            definition(SpecialistInteractionCapability.DIALOGUE_CAPABLE),
            snapshotProvider(),
            snapshots()
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<AIExecutionResult<String>> first =
                executor.submit(() -> gateway.execute(request()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            AIExecutionResult<String> concurrent =
                gateway.execute(request());

            assertThat(concurrent.status())
                .isEqualTo(AIExecutionStatus.DENIED);
            assertThat(concurrent.failure().reason())
                .isEqualTo("CONVERSATION_BUSY");
            assertThat(concurrent.diagnostics())
                .containsEntry("interactiveTurn", true);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).succeeded()).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void permitsDifferentConversationsToRunConcurrently() throws Exception {
        AIExecutionGateway execution = mock(AIExecutionGateway.class);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(execution.submit(any())).thenAnswer(invocation -> {
            AIExecutionRequest<?> request = invocation.getArgument(0);
            return handle(
                "invocation-" + request.conversationBinding()
                    .conversationId(),
                ExecutionHandleStatus.RUNNING,
                null
            );
        });
        when(execution.find(any(), any())).thenAnswer(invocation -> {
            String invocationId = invocation.getArgument(0);
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                    "test execution timed out"
                );
            }
            return Optional.of(new ExecutionSnapshot(
                handle(
                    invocationId,
                    ExecutionHandleStatus.SUCCEEDED,
                    null
                ),
                success(SPECIALIST_ID)
            ));
        });
        DefaultAIInteractiveExecutionGateway gateway = gateway(
            execution,
            definition(SpecialistInteractionCapability.DIALOGUE_CAPABLE),
            snapshotProvider(),
            snapshots()
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<AIExecutionResult<String>> first =
                executor.submit(() -> gateway.execute(request(
                    "conversation-1",
                    "request-1"
                )));
            Future<AIExecutionResult<String>> second =
                executor.submit(() -> gateway.execute(request(
                    "conversation-2",
                    "request-2"
                )));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).succeeded()).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS).succeeded()).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private DefaultAIInteractiveExecutionGateway gateway(
        AIExecutionGateway execution,
        SpecialistDefinition<String, String> definition,
        AIExecutionConversationSnapshotProvider snapshotProvider,
        AIExecutionConversationSnapshotRegistry snapshots
    ) {
        SpecialistRegistry registry = new SpecialistRegistry() {
            @Override
            public Optional<SpecialistDefinition<?, ?>> find(
                SpecialistId id
            ) {
                return definition.id().equals(id)
                    ? Optional.of(definition)
                    : Optional.empty();
            }

            @Override
            public List<SpecialistDefinition<?, ?>> list() {
                return List.of(definition);
            }
        };
        return new DefaultAIInteractiveExecutionGateway(
            execution,
            registry,
            snapshotProvider,
            snapshots,
            canonicalJson(),
            clock()
        );
    }

    private SpecialistDefinition<String, String> definition(
        SpecialistInteractionCapability capability
    ) {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                SPECIALIST_ID,
                "Account Resolver",
                "Explains account blockers"
            ),
            new SpecialistInstructions(
                "Resolve the current account.",
                null
            ),
            new SpecialistExecutionProfile(
                "resolver",
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
            new SpecialistInputAdapter<>() {
                @Override
                public Class<String> inputType() {
                    return String.class;
                }

                @Override
                public void validate(String input) {}

                @Override
                public String renderModelInput(String input) {
                    return input;
                }

                @Override
                public String conversationInput(String input) {
                    return input;
                }

                @Override
                public SpecialistConversationBinding
                    conversationBinding() {
                    return SpecialistConversationBinding.REQUIRED;
                }

                @Override
                public SpecialistInteractionCapability
                    interactionCapability() {
                    return capability;
                }
            },
            new SpecialistOutputAdapter<>() {
                @Override
                public Class<String> outputType() {
                    return String.class;
                }

                @Override
                public String project(
                    OrchestrationResult result,
                    List<AIEvidenceReference> evidence
                ) {
                    return result.getMessage();
                }

                @Override
                public void validate(String output) {}
            }
        );
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

    private EphemeralAIExecutionConversationSnapshotRegistry snapshots() {
        return new EphemeralAIExecutionConversationSnapshotRegistry(
            clock(),
            Duration.ofMinutes(2)
        );
    }

    private AIExecutionRequest<String> request() {
        return request("conversation-1");
    }

    private AIExecutionRequest<String> request(String conversationId) {
        return request(conversationId, "request-1");
    }

    private AIExecutionRequest<String> request(
        String conversationId,
        String idempotencyKey
    ) {
        return new AIExecutionRequest<>(
            SPECIALIST_ID,
            "Inspect my account",
            interactiveContext(),
            new ConversationBinding("user-1", conversationId),
            null,
            idempotencyKey
        );
    }

    private TrustedExecutionContext interactiveContext() {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                "user-1",
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef("account", "account-42"),
            ExecutionSource.INTERACTIVE,
            "tenant-1",
            "resolver-app",
            Set.of("specialist:account-resolver@1"),
            "correlation-1",
            NOW
        );
    }

    private AIExecutionResult<String> success(SpecialistId id) {
        return new AIExecutionResult<>(
            "invocation-1",
            id,
            AIExecutionStatus.SUCCEEDED,
            "Resolved",
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW
        );
    }

    private ExecutionHandle handle(
        ExecutionHandleStatus status,
        String failureReason
    ) {
        return handle("invocation-1", status, failureReason);
    }

    private ExecutionHandle handle(
        String invocationId,
        ExecutionHandleStatus status,
        String failureReason
    ) {
        return new ExecutionHandle(
            invocationId,
            ExecutionDurability.EPHEMERAL,
            status,
            NOW.plusSeconds(45),
            NOW.plusSeconds(300),
            failureReason
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
}
