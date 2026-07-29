package ai.fabric.execution.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.action.ActionProposalReceiptStatus;
import ai.fabric.execution.action.ActionProposalView;
import ai.fabric.execution.gateway.ExecutionDurability;
import ai.fabric.execution.gateway.AIExecutionFailure;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.input.InputDeliveryTarget;
import ai.fabric.execution.input.NeedsUserInput;
import ai.fabric.execution.input.SpecialistInputResponseContract;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.RegisteredSpecialist;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultSpecialistDelegationGatewayTest {

    private static final Instant NOW = Instant.parse(
        "2026-07-29T12:00:00Z"
    );
    private static final SpecialistId SOURCE_ID =
        SpecialistId.of("account-coordinator", "1");
    private static final SpecialistId TARGET_ID =
        SpecialistId.of("account-checker", "1");

    private final SpecialistRegistry registry = mock(
        SpecialistRegistry.class
    );
    private final SpecialistClientFactory clients = mock(
        SpecialistClientFactory.class
    );
    private final SpecialistClient<String, String> targetClient = mock(
        SpecialistClient.class
    );
    private final TrustedExecutionContext trustedContext =
        TrustedExecutionContext.application(
            "resolver-service",
            new ExecutionSubjectRef("account", "account-7"),
            "tenant-a",
            Set.of("specialist:account-checker@1")
        );

    private RegisteredSpecialist source;
    private RegisteredSpecialist target;
    private DefaultSpecialistDelegationGateway gateway;

    @BeforeEach
    void setUp() {
        source = RegisteredSpecialist.javaDefinition(definition(
            SOURCE_ID,
            SpecialistDelegationPolicy.oneLevel(Set.of(TARGET_ID))
        ));
        target = RegisteredSpecialist.javaDefinition(definition(
            TARGET_ID,
            SpecialistDelegationPolicy.disabled()
        ));
        when(registry.findRegistered(SOURCE_ID))
            .thenReturn(Optional.of(source));
        when(registry.findRegistered(TARGET_ID))
            .thenReturn(Optional.of(target));
        when(clients.bind(TARGET_ID, String.class, String.class))
            .thenReturn(targetClient);
        gateway = new DefaultSpecialistDelegationGateway(
            registry,
            clients,
            new CanonicalJsonSupport(new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(10)
        );
    }

    @Test
    void invokesDeclaredChildWithInheritedContextAndNoConversation() {
        when(targetClient.execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        )).thenReturn(successfulChild());

        SpecialistDelegationResult<String, String> result = gateway.delegate(
            request("inspect the account", "delegation-1"),
            String.class,
            String.class
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(result.depth()).isEqualTo(1);
        assertThat(result.parentInvocationId()).isEqualTo("parent-1");
        assertThat(result.sourceOutput()).isEqualTo("route to account checker");
        assertThat(result.targetExecution().output())
            .isEqualTo("account is ready");
        assertThat(result.targetExecution().diagnostics())
            .containsEntry("delegation", true)
            .containsEntry("delegationDepth", 1)
            .containsEntry("parentInvocationId", "parent-1")
            .containsEntry("sourceSpecialist", SOURCE_ID.toString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<SpecialistInvocation<String>> invocation =
            ArgumentCaptor.forClass(SpecialistInvocation.class);
        verify(targetClient).execute(invocation.capture());
        assertThat(invocation.getValue().trustedExecutionContext())
            .isSameAs(trustedContext);
        assertThat(invocation.getValue().conversationBinding()).isNull();
        assertThat(invocation.getValue().deadline())
            .isEqualTo(NOW.plusSeconds(30));
        assertThat(invocation.getValue().idempotencyKey())
            .startsWith("delegation:");
    }

    @Test
    void deniesUndeclaredStaleAndRecursiveDelegationBeforeBinding() {
        RegisteredSpecialist disabled =
            RegisteredSpecialist.javaDefinition(definition(
                SOURCE_ID,
                SpecialistDelegationPolicy.disabled()
            ));
        when(registry.findRegistered(SOURCE_ID))
            .thenReturn(Optional.of(disabled));

        SpecialistDelegationResult<String, String> undeclared =
            gateway.delegate(
                request(
                    successfulParent(
                        disabled.contentHash(),
                        Map.of()
                    ),
                    "inspect",
                    "undeclared"
                ),
                String.class,
                String.class
            );

        assertThat(undeclared.status()).isEqualTo(AIExecutionStatus.DENIED);
        assertThat(undeclared.failure().reason())
            .isEqualTo("DELEGATION_TARGET_NOT_ALLOWED");

        SpecialistDelegationResult<String, String> stale = gateway.delegate(
            request(
                successfulParent("0".repeat(64), Map.of()),
                "inspect",
                "stale"
            ),
            String.class,
            String.class
        );
        assertThat(stale.failure().reason())
            .isEqualTo("DELEGATION_SOURCE_CHANGED");

        SpecialistDelegationResult<String, String> recursive =
            gateway.delegate(
                request(
                    successfulParent(
                        source.contentHash(),
                        Map.of("delegationDepth", 1)
                    ),
                    "inspect",
                    "recursive"
                ),
                String.class,
                String.class
            );
        assertThat(recursive.failure().reason())
            .isEqualTo("DELEGATION_DEPTH_EXCEEDED");
        verify(clients, never()).bind(
            eq(TARGET_ID),
            eq(String.class),
            eq(String.class)
        );
    }

    @Test
    void replaysIdenticalScopedRequestAndRejectsChangedInput() {
        when(targetClient.execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        )).thenReturn(successfulChild());
        SpecialistDelegationRequest<String, String> request =
            request("inspect", "stable-key");

        SpecialistDelegationResult<String, String> first = gateway.delegate(
            request,
            String.class,
            String.class
        );
        SpecialistDelegationResult<String, String> replay = gateway.delegate(
            request,
            String.class,
            String.class
        );
        SpecialistDelegationResult<String, String> conflict = gateway.delegate(
            request("different input", "stable-key"),
            String.class,
            String.class
        );

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.delegationId()).isEqualTo(first.delegationId());
        assertThat(conflict.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(conflict.failure().reason())
            .isEqualTo("DELEGATION_IDEMPOTENCY_CONFLICT");
        verify(targetClient).execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        );
    }

    @Test
    void keepsTargetFailureVisibleAndRejectsExpiredParent() {
        AIExecutionResult<String> targetFailure = new AIExecutionResult<>(
            "child-failed",
            TARGET_ID,
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
        );
        when(targetClient.execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        )).thenReturn(targetFailure);

        SpecialistDelegationResult<String, String> failed = gateway.delegate(
            request("inspect", "provider-failure"),
            String.class,
            String.class
        );

        assertThat(failed.status()).isEqualTo(AIExecutionStatus.FAILED);
        assertThat(failed.failure().reason()).isEqualTo("PROVIDER_FAILED");
        assertThat(failed.targetExecution()).isNotNull();

        SpecialistDelegationResult<String, String> expired = gateway.delegate(
            request(
                successfulParent(
                    source.contentHash(),
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
            .isEqualTo("DELEGATION_DEADLINE_EXCEEDED");
    }

    @Test
    void rejectsFailedSourceAndMalformedParentDeadlineBeforeBinding() {
        AIExecutionResult<String> failedParent = new AIExecutionResult<>(
            "parent-failed",
            SOURCE_ID,
            AIExecutionStatus.FAILED,
            null,
            List.of(),
            Map.of("specialistContentHash", source.contentHash()),
            new AIExecutionFailure(
                "PROVIDER_FAILED",
                "The configured provider failed.",
                true
            ),
            NOW.minusSeconds(2),
            NOW.minusSeconds(1)
        );

        SpecialistDelegationResult<String, String> failed = gateway.delegate(
            request(failedParent, "inspect", "failed-parent"),
            String.class,
            String.class
        );
        SpecialistDelegationResult<String, String> malformed =
            gateway.delegate(
                request(
                    successfulParent(
                        source.contentHash(),
                        Map.of("executionDeadline", "not-an-instant")
                    ),
                    "inspect",
                    "malformed-deadline"
                ),
                String.class,
                String.class
            );

        assertThat(failed.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(failed.failure().reason())
            .isEqualTo("DELEGATION_SOURCE_NOT_SUCCESSFUL");
        assertThat(malformed.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(malformed.failure().reason())
            .isEqualTo("DELEGATION_PARENT_DEADLINE_INVALID");
        verify(clients, never()).bind(
            eq(TARGET_ID),
            eq(String.class),
            eq(String.class)
        );
    }

    @Test
    void rejectsAndCancelsDelegatedInputWait() {
        AIExecutionResult<String> waiting = waitingChild();
        when(targetClient.execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        )).thenReturn(waiting);

        SpecialistDelegationResult<String, String> result = gateway.delegate(
            request("inspect", "child-wait"),
            String.class,
            String.class
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(result.failure().reason())
            .isEqualTo("DELEGATED_INPUT_WAIT_UNSUPPORTED");
        verify(targetClient).cancel("child-waiting", trustedContext);
    }

    @Test
    void exposesFailureWhenUnsupportedInputWaitCannotBeCancelled() {
        when(targetClient.execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        )).thenReturn(waitingChild());
        doThrow(new IllegalStateException("store unavailable"))
            .when(targetClient)
            .cancel("child-waiting", trustedContext);

        SpecialistDelegationResult<String, String> result = gateway.delegate(
            request("inspect", "child-wait-cancel-failure"),
            String.class,
            String.class
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("DELEGATED_INPUT_WAIT_CANCELLATION_FAILED");
    }

    @Test
    void rejectsDelegatedConfirmationEvenFromMisbehavingReadTarget() {
        AIExecutionResult<String> confirmation = new AIExecutionResult<>(
            "child-confirmation",
            TARGET_ID,
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
        );
        when(targetClient.execute(
            org.mockito.ArgumentMatchers.any(SpecialistInvocation.class)
        )).thenReturn(confirmation);

        SpecialistDelegationResult<String, String> result = gateway.delegate(
            request("inspect", "child-confirmation"),
            String.class,
            String.class
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.DENIED);
        assertThat(result.failure().reason())
            .isEqualTo("DELEGATED_WRITE_PROPOSAL_UNSUPPORTED");
    }

    private SpecialistDelegationRequest<String, String> request(
        String targetInput,
        String idempotencyKey
    ) {
        return request(
            successfulParent(source.contentHash(), Map.of()),
            targetInput,
            idempotencyKey
        );
    }

    private SpecialistDelegationRequest<String, String> request(
        AIExecutionResult<String> parent,
        String targetInput,
        String idempotencyKey
    ) {
        return new SpecialistDelegationRequest<>(
            parent,
            TARGET_ID,
            targetInput,
            trustedContext,
            NOW.plusSeconds(40),
            idempotencyKey
        );
    }

    private AIExecutionResult<String> successfulParent(
        String contentHash,
        Map<String, Object> extraDiagnostics
    ) {
        Map<String, Object> diagnostics = new java.util.LinkedHashMap<>();
        diagnostics.put("specialistContentHash", contentHash);
        diagnostics.put(
            "executionDeadline",
            NOW.plusSeconds(30).toString()
        );
        diagnostics.putAll(extraDiagnostics);
        return new AIExecutionResult<>(
            "parent-1",
            SOURCE_ID,
            AIExecutionStatus.SUCCEEDED,
            "route to account checker",
            List.of(),
            diagnostics,
            null,
            NOW.minusSeconds(2),
            NOW.minusSeconds(1)
        );
    }

    private AIExecutionResult<String> successfulChild() {
        return new AIExecutionResult<>(
            "child-1",
            TARGET_ID,
            AIExecutionStatus.SUCCEEDED,
            "account is ready",
            List.of(),
            Map.of("specialistContentHash", target.contentHash()),
            null,
            NOW,
            NOW.plusSeconds(1)
        );
    }

    private AIExecutionResult<String> waitingChild() {
        return new AIExecutionResult<>(
            "child-waiting",
            TARGET_ID,
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
                "child-waiting",
                TARGET_ID,
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
        SpecialistDelegationPolicy delegationPolicy
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
            delegationPolicy,
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
