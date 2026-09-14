package ai.fabric.execution.chain.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.execution.chain.RegisteredSpecialistChain;
import ai.fabric.execution.chain.SpecialistChainConversationPolicy;
import ai.fabric.execution.chain.SpecialistChainDefinition;
import ai.fabric.execution.chain.SpecialistChainDirectiveType;
import ai.fabric.execution.chain.SpecialistChainExecutionRequest;
import ai.fabric.execution.chain.SpecialistChainExecutionResult;
import ai.fabric.execution.chain.SpecialistChainExecutionStatus;
import ai.fabric.execution.chain.SpecialistChainId;
import ai.fabric.execution.chain.SpecialistChainInputAdapter;
import ai.fabric.execution.chain.SpecialistChainLimits;
import ai.fabric.execution.chain.SpecialistChainRegistry;
import ai.fabric.execution.chain.SpecialistChainStepTrace;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.ConversationBinding;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpecialistChainPayloadCodecTest {

    private static final String EXECUTION_ID = "chain-exec-1";
    private static final SpecialistChainId CHAIN_ID =
        SpecialistChainId.of("incident-investigation", "1");
    private static final SpecialistId MANAGER_ID =
        SpecialistId.of("incident-manager", "1");
    private static final String HASH = CanonicalJsonSupport.sha256("chain");
    private static final Instant NOW =
        Instant.parse("2026-09-13T12:00:00Z");
    private static final ObjectMapper OBJECT_MAPPER =
        new ObjectMapper().findAndRegisterModules();

    private final SpecialistChainSecurity security =
        new SpecialistChainSecurity(
            OBJECT_MAPPER,
            "chain-test-encryption-secret-at-least-32-characters",
            "chain-test-fingerprint-secret-at-least-32-characters"
        );
    private final SpecialistChainPayloadCodec codec =
        new SpecialistChainPayloadCodec(
            OBJECT_MAPPER,
            registry(String.class),
            security
        );

    @Test
    void requestRoundTripPreservesTrustedBoundaryWithoutPlaintext() {
        SpecialistChainExecutionRequest<String> request = request();

        String protectedRequest = codec.protectRequest(
            EXECUTION_ID,
            request
        );
        SpecialistChainExecutionRecord record = queued(protectedRequest);
        SpecialistChainExecutionRequest<Object> restored =
            codec.unprotectRequest(record);

        assertThat(protectedRequest)
            .startsWith("v1.")
            .doesNotContain(
                "Investigate checkout",
                "tenant-private",
                "principal-private",
                "conversation-private",
                "request-private"
            );
        assertThat(restored.input()).isEqualTo("Investigate checkout");
        assertThat(restored.chainId()).isEqualTo(CHAIN_ID);
        assertThat(restored.idempotencyKey()).isEqualTo("request-private");
        assertThat(restored.deadline()).isEqualTo(NOW.plusSeconds(90));
        assertThat(restored.conversationBinding())
            .isEqualTo(new ConversationBinding(
                "user-private",
                "conversation-private"
            ));
        assertThat(restored.trustedExecutionContext())
            .isEqualTo(request.trustedExecutionContext());
    }

    @Test
    void bindingAndTamperChecksFailClosed() {
        String protectedRequest = codec.protectRequest(
            EXECUTION_ID,
            request()
        );
        SpecialistChainExecutionRecord original = queued(protectedRequest);
        SpecialistChainExecutionRecord rebound = copy(
            original,
            "different-execution",
            protectedRequest
        );
        SpecialistChainExecutionRecord tampered = copy(
            original,
            EXECUTION_ID,
            tamperCiphertext(protectedRequest)
        );

        assertThatThrownBy(() -> codec.unprotectRequest(rebound))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> codec.unprotectRequest(tampered))
            .isInstanceOfAny(
                IllegalArgumentException.class,
                IllegalStateException.class
            );
    }

    @Test
    void checkpointAndTerminalResultSurviveCodecRestart() {
        SpecialistChainCheckpoint checkpoint =
            SpecialistChainCheckpoint.initial(
                "Investigate checkout",
                List.of(),
                "snapshot-1",
                3
            );
        SpecialistChainExecutionResult result =
            new SpecialistChainExecutionResult(
                EXECUTION_ID,
                CHAIN_ID,
                HASH,
                SpecialistChainExecutionStatus.COMPLETED,
                "Checkout is degraded.",
                null,
                List.of(),
                List.of(new SpecialistChainStepTrace(
                    0,
                    "manager-call-1",
                    SpecialistChainDirectiveType.COMPLETE,
                    "Approved evidence is sufficient.",
                    null,
                    List.of(),
                    new ai.fabric.execution.chain.SpecialistChainBudgetView(
                        3,
                        2,
                        2,
                        8_000,
                        60_000
                    ),
                    NOW,
                    NOW.plusSeconds(1)
                )),
                "snapshot-1",
                3,
                null,
                false,
                true,
                NOW,
                NOW.plusSeconds(1)
            );
        String protectedRequest = codec.protectRequest(
            EXECUTION_ID,
            request()
        );
        String protectedCheckpoint = codec.protectCheckpoint(
            EXECUTION_ID,
            checkpoint
        );
        SpecialistChainExecutionRecord running =
            SpecialistChainExecutionRecord.queued(
                EXECUTION_ID,
                CHAIN_ID,
                HASH,
                MANAGER_ID,
                HASH,
                CanonicalJsonSupport.sha256("access"),
                CanonicalJsonSupport.sha256("idempotency"),
                CanonicalJsonSupport.sha256("request"),
                protectedRequest,
                protectedCheckpoint,
                NOW.plusSeconds(90),
                NOW,
                Duration.ofDays(1)
            ).claimed(
                "worker-1",
                NOW,
                NOW.plusSeconds(30)
            );
        String protectedResult = codec.protectResult(
            EXECUTION_ID,
            result
        );
        SpecialistChainExecutionRecord completed = running.completed(
            SpecialistChainExecutionStatus.COMPLETED,
            protectedResult,
            null,
            NOW.plusSeconds(1),
            Duration.ofDays(1)
        );
        SpecialistChainPayloadCodec restarted =
            new SpecialistChainPayloadCodec(
                OBJECT_MAPPER,
                registry(String.class),
                security
            );

        assertThat(protectedCheckpoint)
            .doesNotContain("Investigate checkout", "snapshot-1");
        assertThat(protectedResult)
            .doesNotContain("Checkout is degraded", "manager-call-1");
        assertThat(restarted.unprotectCheckpoint(completed))
            .isEqualTo(checkpoint);
        assertThat(restarted.unprotectResult(completed)).isEqualTo(result);
    }

    @Test
    void fingerprintsAreScopedAndDoNotExposeIdentifiers() {
        TrustedExecutionContext context = request()
            .trustedExecutionContext();

        String access = security.accessFingerprint(context);
        String idempotency = security.idempotencyFingerprint(
            context,
            CHAIN_ID,
            "request-private"
        );

        assertThat(access)
            .hasSize(64)
            .doesNotContain("tenant-private", "principal-private");
        assertThat(idempotency)
            .hasSize(64)
            .doesNotContain("request-private", "tenant-private");
        assertThat(security.idempotencyFingerprint(
            context,
            CHAIN_ID,
            "another-request"
        )).isNotEqualTo(idempotency);
    }

    private SpecialistChainExecutionRequest<String> request() {
        return new SpecialistChainExecutionRequest<>(
            CHAIN_ID,
            "Investigate checkout",
            new TrustedExecutionContext(
                new ExecutionPrincipal(
                    "principal-private",
                    ExecutionPrincipalType.END_USER
                ),
                new ExecutionSubjectRef("incident", "incident-private"),
                ExecutionSource.INTERACTIVE,
                "tenant-private",
                "deployment-private",
                Set.of("specialist:incident-manager@1"),
                "correlation-private",
                NOW.minusSeconds(5)
            ),
            new ConversationBinding(
                "user-private",
                "conversation-private"
            ),
            NOW.plusSeconds(90),
            "request-private"
        );
    }

    private SpecialistChainExecutionRecord queued(String protectedRequest) {
        return SpecialistChainExecutionRecord.queued(
            EXECUTION_ID,
            CHAIN_ID,
            HASH,
            MANAGER_ID,
            HASH,
            CanonicalJsonSupport.sha256("access"),
            CanonicalJsonSupport.sha256("idempotency"),
            CanonicalJsonSupport.sha256("request"),
            protectedRequest,
            codec.protectCheckpoint(
                EXECUTION_ID,
                SpecialistChainCheckpoint.initial(
                    "Investigate checkout",
                    List.of(),
                    null,
                    0
                )
            ),
            NOW.plusSeconds(90),
            NOW,
            Duration.ofDays(1)
        );
    }

    private SpecialistChainExecutionRecord copy(
        SpecialistChainExecutionRecord source,
        String executionId,
        String protectedRequest
    ) {
        return new SpecialistChainExecutionRecord(
            executionId,
            source.chainId(),
            source.chainContentHash(),
            source.managerSpecialistId(),
            source.managerContentHash(),
            source.accessFingerprint(),
            source.idempotencyFingerprint(),
            source.requestFingerprint(),
            protectedRequest,
            source.protectedCheckpoint(),
            source.protectedResult(),
            source.status(),
            source.failureReason(),
            source.nextDecisionIndex(),
            source.deadline(),
            source.createdAt(),
            source.updatedAt(),
            source.completedAt(),
            source.expiresAt(),
            source.leaseOwner(),
            source.leaseUntil(),
            source.attemptCount(),
            source.version()
        );
    }

    private String tamperCiphertext(String protectedPayload) {
        int payloadStart = protectedPayload.indexOf('.') + 1;
        int index = payloadStart + (protectedPayload.length() - payloadStart) / 2;
        char original = protectedPayload.charAt(index);
        char replacement = original == 'A' ? 'B' : 'A';
        return protectedPayload.substring(0, index)
            + replacement
            + protectedPayload.substring(index + 1);
    }

    @SuppressWarnings("unchecked")
    private SpecialistChainRegistry registry(Class<?> inputType) {
        RegisteredSpecialistChain registered = mock(
            RegisteredSpecialistChain.class
        );
        SpecialistChainDefinition<Object> definition = mock(
            SpecialistChainDefinition.class
        );
        when(registered.id()).thenReturn(CHAIN_ID);
        doReturn(definition).when(registered).definition();
        when(definition.inputType()).thenReturn((Class<Object>) inputType);
        return new SpecialistChainRegistry() {
            @Override
            public Optional<RegisteredSpecialistChain> find(
                SpecialistChainId id
            ) {
                return CHAIN_ID.equals(id)
                    ? Optional.of(registered)
                    : Optional.empty();
            }

            @Override
            public List<RegisteredSpecialistChain> list() {
                return List.of(registered);
            }
        };
    }
}
