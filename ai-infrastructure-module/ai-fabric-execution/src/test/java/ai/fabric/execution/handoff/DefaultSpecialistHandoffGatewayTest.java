package ai.fabric.execution.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.execution.action.ActionProposalReceiptStatus;
import ai.fabric.execution.action.ActionProposalView;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.delegation.DefaultSpecialistHandoffGateway;
import ai.fabric.execution.gateway.AIExecutionFailure;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.gateway.ExecutionDurability;
import ai.fabric.execution.input.InputDeliveryTarget;
import ai.fabric.execution.input.NeedsUserInput;
import ai.fabric.execution.input.SpecialistInputResponseContract;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistDelegationPolicy;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistHandoffPolicy;
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
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultSpecialistHandoffGatewayTest {

    private static final Instant NOW = Instant.parse(
        "2026-07-29T15:00:00Z"
    );
    private static final SpecialistId PREDECESSOR_ID =
        SpecialistId.of("account-intake", "1");
    private static final SpecialistId SUCCESSOR_ID =
        SpecialistId.of("account-checker", "1");

    private final SpecialistRegistry registry = mock(
        SpecialistRegistry.class
    );
    private final SpecialistClientFactory clients = mock(
        SpecialistClientFactory.class
    );
    private final SpecialistClient<String, String> successorClient = mock(
        SpecialistClient.class
    );
    private final TrustedExecutionContext trustedContext =
        TrustedExecutionContext.application(
            "resolver-service",
            new ExecutionSubjectRef("account", "account-7"),
            "tenant-a",
            Set.of("specialist:account-checker@1")
        );

    private RegisteredSpecialist predecessor;
    private RegisteredSpecialist successor;
    private DefaultSpecialistHandoffGateway gateway;

    @BeforeEach
    void setUp() {
        predecessor = RegisteredSpecialist.javaDefinition(definition(
            PREDECESSOR_ID,
            SpecialistHandoffPolicy.oneLevel(Set.of(SUCCESSOR_ID))
        ));
        successor = RegisteredSpecialist.javaDefinition(definition(
            SUCCESSOR_ID,
            SpecialistHandoffPolicy.disabled()
        ));
        when(registry.findRegistered(PREDECESSOR_ID))
            .thenReturn(Optional.of(predecessor));
        when(registry.findRegistered(SUCCESSOR_ID))
            .thenReturn(Optional.of(successor));
        when(clients.bind(SUCCESSOR_ID, String.class, String.class))
            .thenReturn(successorClient);
        gateway = new DefaultSpecialistHandoffGateway(
            registry,
            clients,
            new CanonicalJsonSupport(new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(10)
        );
    }

    @Test
    void createsIndependentlyAuthorizedSuccessorWithoutConversation() {
        when(successorClient.execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        )).thenReturn(successfulSuccessor());

        SpecialistHandoffResult<String, String> result = gateway.handoff(
            request("inspect the account", "handoff-1"),
            String.class,
            String.class
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(result.depth()).isEqualTo(1);
        assertThat(result.predecessorInvocationId())
            .isEqualTo("predecessor-1");
        assertThat(result.predecessorOutput())
            .isEqualTo("transfer to account checker");
        assertThat(result.successorExecution().output())
            .isEqualTo("account is ready");
        assertThat(result.successorExecution().diagnostics())
            .containsEntry("handoff", true)
            .containsEntry("handoffDepth", 1)
            .containsEntry(
                "predecessorInvocationId",
                "predecessor-1"
            )
            .containsEntry(
                "predecessorSpecialist",
                PREDECESSOR_ID.toString()
            )
            .doesNotContainKeys(
                "delegation",
                "delegationId",
                "parentInvocationId"
            );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<SpecialistInvocation<String>> invocation =
            ArgumentCaptor.forClass(SpecialistInvocation.class);
        verify(successorClient).execute(invocation.capture());
        assertThat(invocation.getValue().trustedExecutionContext())
            .isSameAs(trustedContext);
        assertThat(invocation.getValue().conversationBinding()).isNull();
        assertThat(invocation.getValue().deadline())
            .isEqualTo(NOW.plusSeconds(30));
        assertThat(invocation.getValue().idempotencyKey())
            .startsWith("handoff:");
    }

    @Test
    void deniesUndeclaredStaleAndAlreadyRelatedPredecessor() {
        RegisteredSpecialist disabled =
            RegisteredSpecialist.javaDefinition(definition(
                PREDECESSOR_ID,
                SpecialistHandoffPolicy.disabled()
            ));
        when(registry.findRegistered(PREDECESSOR_ID))
            .thenReturn(Optional.of(disabled));

        SpecialistHandoffResult<String, String> undeclared = gateway.handoff(
            request(
                successfulPredecessor(disabled.contentHash(), Map.of()),
                "inspect",
                "undeclared"
            ),
            String.class,
            String.class
        );
        assertThat(undeclared.failure().reason())
            .isEqualTo("HANDOFF_TARGET_NOT_ALLOWED");

        SpecialistHandoffResult<String, String> stale = gateway.handoff(
            request(
                successfulPredecessor("0".repeat(64), Map.of()),
                "inspect",
                "stale"
            ),
            String.class,
            String.class
        );
        assertThat(stale.failure().reason())
            .isEqualTo("HANDOFF_SOURCE_CHANGED");

        SpecialistHandoffResult<String, String> delegatedChild =
            gateway.handoff(
                request(
                    successfulPredecessor(
                        predecessor.contentHash(),
                        Map.of("delegationDepth", 1)
                    ),
                    "inspect",
                    "delegated-child"
                ),
                String.class,
                String.class
            );
        SpecialistHandoffResult<String, String> successorChain =
            gateway.handoff(
                request(
                    successfulPredecessor(
                        predecessor.contentHash(),
                        Map.of("handoffDepth", 1)
                    ),
                    "inspect",
                    "successor-chain"
                ),
                String.class,
                String.class
            );
        assertThat(delegatedChild.failure().reason())
            .isEqualTo("HANDOFF_DEPTH_EXCEEDED");
        assertThat(successorChain.failure().reason())
            .isEqualTo("HANDOFF_DEPTH_EXCEEDED");
        verify(clients, never()).bind(
            eq(SUCCESSOR_ID),
            eq(String.class),
            eq(String.class)
        );
    }

    @Test
    void replaysExactWorkAndRejectsChangedInput() {
        when(successorClient.execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        )).thenReturn(successfulSuccessor());
        SpecialistHandoffRequest<String, String> request =
            request("inspect", "stable-key");

        SpecialistHandoffResult<String, String> first = gateway.handoff(
            request,
            String.class,
            String.class
        );
        SpecialistHandoffResult<String, String> replay = gateway.handoff(
            request,
            String.class,
            String.class
        );
        SpecialistHandoffResult<String, String> conflict = gateway.handoff(
            request("different input", "stable-key"),
            String.class,
            String.class
        );

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.handoffId()).isEqualTo(first.handoffId());
        assertThat(replay.successorExecution().invocationId())
            .isEqualTo(first.successorExecution().invocationId());
        assertThat(conflict.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(conflict.failure().reason())
            .isEqualTo("HANDOFF_IDEMPOTENCY_CONFLICT");
        verify(successorClient).execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        );
    }

    @Test
    void preservesSuccessorFailureAndRejectsExpiredPredecessor() {
        when(successorClient.execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        )).thenReturn(new AIExecutionResult<>(
            "successor-failed",
            SUCCESSOR_ID,
            AIExecutionStatus.FAILED,
            null,
            List.of(),
            Map.of(),
            new AIExecutionFailure(
                "PROVIDER_FAILED",
                "The configured provider failed.",
                true
            ),
            NOW,
            NOW.plusSeconds(1)
        ));

        SpecialistHandoffResult<String, String> failed = gateway.handoff(
            request("inspect", "provider-failure"),
            String.class,
            String.class
        );
        assertThat(failed.status()).isEqualTo(AIExecutionStatus.FAILED);
        assertThat(failed.failure().reason()).isEqualTo("PROVIDER_FAILED");
        assertThat(failed.successorExecution()).isNotNull();

        SpecialistHandoffResult<String, String> expired = gateway.handoff(
            request(
                successfulPredecessor(
                    predecessor.contentHash(),
                    Map.of(
                        "executionDeadline",
                        NOW.minusSeconds(1).toString()
                    )
                ),
                "inspect",
                "expired"
            ),
            String.class,
            String.class
        );
        assertThat(expired.status())
            .isEqualTo(AIExecutionStatus.DEADLINE_EXCEEDED);
        assertThat(expired.failure().reason())
            .isEqualTo("HANDOFF_DEADLINE_EXCEEDED");
    }

    @Test
    void rejectsFailedPredecessorAndMalformedDeadlineBeforeBinding() {
        AIExecutionResult<String> failedPredecessor =
            new AIExecutionResult<>(
                "predecessor-failed",
                PREDECESSOR_ID,
                AIExecutionStatus.FAILED,
                null,
                List.of(),
                Map.of(
                    "specialistContentHash",
                    predecessor.contentHash()
                ),
                new AIExecutionFailure(
                    "PROVIDER_FAILED",
                    "The configured provider failed.",
                    true
                ),
                NOW.minusSeconds(2),
                NOW.minusSeconds(1)
            );

        SpecialistHandoffResult<String, String> failed = gateway.handoff(
            request(failedPredecessor, "inspect", "failed-predecessor"),
            String.class,
            String.class
        );
        SpecialistHandoffResult<String, String> malformed = gateway.handoff(
            request(
                successfulPredecessor(
                    predecessor.contentHash(),
                    Map.of("executionDeadline", "not-an-instant")
                ),
                "inspect",
                "malformed-deadline"
            ),
            String.class,
            String.class
        );

        assertThat(failed.failure().reason())
            .isEqualTo("HANDOFF_SOURCE_NOT_SUCCESSFUL");
        assertThat(malformed.failure().reason())
            .isEqualTo("HANDOFF_PREDECESSOR_DEADLINE_INVALID");
        verify(clients, never()).bind(
            eq(SUCCESSOR_ID),
            eq(String.class),
            eq(String.class)
        );
    }

    @Test
    void cancelsUnsupportedSuccessorInputWait() {
        when(successorClient.execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        )).thenReturn(waitingSuccessor());

        SpecialistHandoffResult<String, String> result = gateway.handoff(
            request("inspect", "successor-wait"),
            String.class,
            String.class
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(result.failure().reason())
            .isEqualTo("HANDOFF_INPUT_WAIT_UNSUPPORTED");
        verify(successorClient).cancel(
            "successor-waiting",
            trustedContext
        );
    }

    @Test
    void exposesInputWaitCancellationFailure() {
        when(successorClient.execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        )).thenReturn(waitingSuccessor());
        doThrow(new IllegalStateException("store unavailable"))
            .when(successorClient)
            .cancel("successor-waiting", trustedContext);

        SpecialistHandoffResult<String, String> result = gateway.handoff(
            request("inspect", "successor-cancel-failure"),
            String.class,
            String.class
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("HANDOFF_INPUT_WAIT_CANCELLATION_FAILED");
    }

    @Test
    void rejectsSuccessorWriteProposal() {
        when(successorClient.execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        )).thenReturn(new AIExecutionResult<>(
            "successor-confirmation",
            SUCCESSOR_ID,
            AIExecutionStatus.CONFIRMATION_REQUIRED,
            null,
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW.plusSeconds(1),
            new ActionProposalView(
                "receipt-1",
                "update_account",
                "Update this account?",
                ActionProposalReceiptStatus.PROPOSED,
                NOW,
                NOW.plusSeconds(30)
            )
        ));

        SpecialistHandoffResult<String, String> result = gateway.handoff(
            request("inspect", "successor-confirmation"),
            String.class,
            String.class
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.DENIED);
        assertThat(result.failure().reason())
            .isEqualTo("HANDOFF_WRITE_PROPOSAL_UNSUPPORTED");
    }

    private SpecialistHandoffRequest<String, String> request(
        String successorInput,
        String idempotencyKey
    ) {
        return request(
            successfulPredecessor(predecessor.contentHash(), Map.of()),
            successorInput,
            idempotencyKey
        );
    }

    private SpecialistHandoffRequest<String, String> request(
        AIExecutionResult<String> source,
        String successorInput,
        String idempotencyKey
    ) {
        return new SpecialistHandoffRequest<>(
            source,
            SUCCESSOR_ID,
            successorInput,
            trustedContext,
            NOW.plusSeconds(40),
            idempotencyKey
        );
    }

    private AIExecutionResult<String> successfulPredecessor(
        String contentHash,
        Map<String, Object> extraDiagnostics
    ) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("specialistContentHash", contentHash);
        diagnostics.put(
            "executionDeadline",
            NOW.plusSeconds(30).toString()
        );
        diagnostics.putAll(extraDiagnostics);
        return new AIExecutionResult<>(
            "predecessor-1",
            PREDECESSOR_ID,
            AIExecutionStatus.SUCCEEDED,
            "transfer to account checker",
            List.of(),
            diagnostics,
            null,
            NOW.minusSeconds(2),
            NOW.minusSeconds(1)
        );
    }

    private AIExecutionResult<String> successfulSuccessor() {
        return new AIExecutionResult<>(
            "successor-1",
            SUCCESSOR_ID,
            AIExecutionStatus.SUCCEEDED,
            "account is ready",
            List.of(),
            Map.of("specialistContentHash", successor.contentHash()),
            null,
            NOW,
            NOW.plusSeconds(1)
        );
    }

    private AIExecutionResult<String> waitingSuccessor() {
        return new AIExecutionResult<>(
            "successor-waiting",
            SUCCESSOR_ID,
            AIExecutionStatus.WAITING_FOR_INPUT,
            null,
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW.plusSeconds(1),
            null,
            new NeedsUserInput(
                "input-1",
                "successor-waiting",
                SUCCESSOR_ID,
                "MISSING_AMOUNT",
                "What amount should be assessed?",
                new SpecialistInputResponseContract(
                    new SpecialistSchemaId("amount-response", "1"),
                    Map.of("type", "object")
                ),
                InputDeliveryTarget.HOST_APPLICATION,
                ExecutionDurability.EPHEMERAL,
                NOW,
                NOW.plusSeconds(30),
                2
            )
        );
    }

    private SpecialistDefinition<String, String> definition(
        SpecialistId id,
        SpecialistHandoffPolicy handoffPolicy
    ) {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                id,
                id.name(),
                "Test specialist " + id.name()
            ),
            new SpecialistInstructions("Return a validated result", null),
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
            SpecialistDelegationPolicy.disabled(),
            handoffPolicy,
            new SpecialistInputAdapter<>() {
                @Override
                public Class<String> inputType() {
                    return String.class;
                }

                @Override
                public void validate(String input) {
                    if (input.isBlank()) {
                        throw new IllegalArgumentException(
                            "input is required"
                        );
                    }
                }

                @Override
                public String renderModelInput(String input) {
                    return input;
                }

                @Override
                public OrchestrationContext orchestrationContext(
                    String input
                ) {
                    return OrchestrationContext.builder().build();
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
                    List<ai.fabric.evidence.AIEvidenceReference> evidence
                ) {
                    return result.getMessage();
                }

                @Override
                public void validate(String output) {
                    if (output == null || output.isBlank()) {
                        throw new IllegalArgumentException(
                            "output is required"
                        );
                    }
                }
            }
        );
    }
}
