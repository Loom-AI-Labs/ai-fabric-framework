package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SharedInteractiveTurnCoordinatorTest {

    private static final SpecialistId DIRECT =
        SpecialistId.of("direct-dialogue", "1");
    private static final SpecialistId MANAGER =
        SpecialistId.of("conversation-manager", "1");
    private static final Instant NOW =
        Instant.parse("2026-07-29T12:00:00Z");

    @Test
    void directAndCoordinatedPathsContendOnOneConversation()
        throws Exception {
        SharedInteractiveTurnCoordinator coordinator = coordinator();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<
                SharedInteractiveTurnCoordinator.CoordinatedTurn<String>
            > manager = executor.submit(() -> coordinator.coordinate(
                MANAGER,
                context(),
                binding("conversation-1"),
                "manager-request",
                SharedInteractiveTurnCoordinator
                    .RecordingPolicy.COORDINATED,
                turn -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                "test coordination timed out"
                            );
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                            "test coordination was interrupted",
                            ex
                        );
                    }
                    return turn.snapshot().revision();
                }
            ));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            var direct = coordinator.coordinate(
                DIRECT,
                context(),
                binding("conversation-1"),
                "direct-request",
                SharedInteractiveTurnCoordinator.RecordingPolicy.DIRECT,
                turn -> "unexpected"
            );

            assertThat(direct.succeeded()).isFalse();
            assertThat(direct.failure().reason())
                .isEqualTo("CONVERSATION_BUSY");
            assertThat(direct.activeTurn()).isTrue();
            release.countDown();
            assertThat(manager.get(5, TimeUnit.SECONDS).value())
                .isEqualTo("a".repeat(64));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void independentConversationsAndRecordingPoliciesAreAccepted() {
        SharedInteractiveTurnCoordinator coordinator = coordinator();

        var direct = coordinator.coordinate(
            DIRECT,
            context(),
            binding("direct-conversation"),
            "direct-request",
            SharedInteractiveTurnCoordinator.RecordingPolicy.DIRECT,
            turn -> turn.snapshot().conversationId()
        );
        var manager = coordinator.coordinate(
            MANAGER,
            context(),
            binding("manager-conversation"),
            "manager-request",
            SharedInteractiveTurnCoordinator.RecordingPolicy.COORDINATED,
            turn -> turn.snapshot().conversationId()
        );

        assertThat(direct.value()).isEqualTo("direct-conversation");
        assertThat(manager.value()).isEqualTo("manager-conversation");
    }

    @Test
    void releasesTheConversationClaimWhenTurnWorkThrows() {
        SharedInteractiveTurnCoordinator coordinator = coordinator();

        assertThatThrownBy(() -> coordinator.coordinate(
            MANAGER,
            context(),
            binding("conversation-1"),
            "failed-request",
            SharedInteractiveTurnCoordinator.RecordingPolicy.COORDINATED,
            turn -> {
                throw new IllegalStateException("simulated failure");
            }
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("simulated failure");

        var retry = coordinator.coordinate(
            MANAGER,
            context(),
            binding("conversation-1"),
            "retry-request",
            SharedInteractiveTurnCoordinator.RecordingPolicy.COORDINATED,
            turn -> "released"
        );

        assertThat(retry.succeeded()).isTrue();
        assertThat(retry.value()).isEqualTo("released");
    }

    private SharedInteractiveTurnCoordinator coordinator() {
        SpecialistDefinition<String, String> direct =
            definition(DIRECT, true);
        SpecialistDefinition<String, String> manager =
            definition(MANAGER, false);
        SpecialistRegistry registry = new SpecialistRegistry() {
            @Override
            public Optional<SpecialistDefinition<?, ?>> find(
                SpecialistId id
            ) {
                if (DIRECT.equals(id)) {
                    return Optional.of(direct);
                }
                if (MANAGER.equals(id)) {
                    return Optional.of(manager);
                }
                return Optional.empty();
            }

            @Override
            public List<SpecialistDefinition<?, ?>> list() {
                return List.of(direct, manager);
            }
        };
        AIExecutionConversationSnapshotProvider provider =
            (binding, turnId, owner) ->
                new ApprovedConversationSnapshot(
                    turnId,
                    binding.userId(),
                    binding.conversationId(),
                    owner.toString(),
                    "a".repeat(64),
                    0,
                    List.of(),
                    NOW
                );
        return new SharedInteractiveTurnCoordinator(
            registry,
            provider,
            new EphemeralAIExecutionConversationSnapshotRegistry(
                clock(),
                Duration.ofMinutes(2)
            ),
            new CanonicalJsonSupport(
                new ObjectMapper().findAndRegisterModules()
            )
        );
    }

    private SpecialistDefinition<String, String> definition(
        SpecialistId id,
        boolean recordTurns
    ) {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(id, id.name(), "Test dialogue owner."),
            new SpecialistInstructions("Answer one turn.", null),
            new SpecialistExecutionProfile(
                "test",
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
                public SpecialistConversationBinding
                    conversationBinding() {
                    return SpecialistConversationBinding.REQUIRED;
                }

                @Override
                public boolean recordValidatedTurns() {
                    return recordTurns;
                }

                @Override
                public SpecialistInteractionCapability
                    interactionCapability() {
                    return SpecialistInteractionCapability.DIALOGUE_CAPABLE;
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

    private ConversationBinding binding(String conversationId) {
        return new ConversationBinding("user-1", conversationId);
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
            Set.of(),
            "correlation-1",
            NOW
        );
    }

    private Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
