package ai.fabric.execution.action;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.ExecutionCapabilityInventory;
import ai.fabric.execution.gateway.SpecialistAuthority;
import ai.fabric.execution.gateway.SpecialistCapabilityResolver;
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
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.invocation.ActionConfirmationState;
import ai.fabric.intent.action.invocation.ActionInvocationFailure;
import ai.fabric.intent.action.invocation.ActionProposalCandidate;
import ai.fabric.intent.action.invocation.GovernedActionInvocation;
import ai.fabric.intent.action.invocation.GovernedActionInvocationOutcome;
import ai.fabric.intent.action.invocation.GovernedActionInvocationService;
import ai.fabric.intent.action.invocation.GovernedActionInvocationStatus;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.capability.DefaultEffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class ActionProposalTestFixture {

    static final String ACTION = "update_address";
    static final SpecialistId SPECIALIST_ID =
        SpecialistId.of("account-resolver", "1");
    static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");

    final MutableClock clock = new MutableClock(NOW);
    final ActionProposalReceiptRepository repository;
    final ActionProposalSecurity security = new ActionProposalSecurity(
        new ObjectMapper(),
        "test-encryption-secret-with-at-least-32-characters",
        "test-fingerprint-secret-with-at-least-32-characters"
    );
    final AIActionMetaData metadata = metadata();
    final SpecialistDefinition<String, String> definition = definition();
    final TrustedExecutionContext trustedContext = trusted(
        "principal-1",
        "account-1",
        "tenant-1"
    );
    final AtomicReference<SpecialistAuthority> authority =
        new AtomicReference<>(new SpecialistAuthority(
            Set.of(ACTION),
            Set.of()
        ));
    final AtomicInteger confirmedInvocations = new AtomicInteger();
    final AtomicReference<String> preflightSubject = new AtomicReference<>();
    final AtomicReference<GovernedActionInvocationOutcome> preflightOutcome =
        new AtomicReference<>();
    final AtomicReference<GovernedActionInvocationOutcome> confirmedOutcome =
        new AtomicReference<>(executedOutcome());
    final AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
    final SpecialistRegistry specialistRegistry = mock(SpecialistRegistry.class);
    final OrchestrationPolicyResolutionStep policyResolutionStep =
        mock(OrchestrationPolicyResolutionStep.class);
    final SpecialistCapabilityResolver capabilityResolver;
    final EffectiveCapabilityProfile effectiveProfile;
    final ActionProposalCoordinator coordinator;

    ActionProposalTestFixture() {
        this(new InMemoryActionProposalReceiptRepository());
    }

    ActionProposalTestFixture(
        ActionProposalReceiptRepository repository
    ) {
        this.repository = repository;
        preflightOutcome.set(confirmationOutcome());
        when(actionRegistry.findMetadata(ACTION))
            .thenReturn(Optional.of(metadata));
        when(actionRegistry.getAllMetadata()).thenReturn(List.of(metadata));
        when(specialistRegistry.find(SPECIALIST_ID))
            .thenReturn(Optional.of(definition));
        when(policyResolutionStep.process(any())).thenAnswer(invocation -> {
            PipelineContext context = invocation.getArgument(0);
            return context.toBuilder()
                .orchestrationPolicy(policy())
                .build();
        });

        ExecutionCapabilityInventory inventory =
            new ExecutionCapabilityInventory() {
                @Override
                public Set<String> registeredVectorSpaces() {
                    return Set.of();
                }

                @Override
                public Set<String> deploymentAllowedActions() {
                    return Set.of(ACTION);
                }
            };
        capabilityResolver = new SpecialistCapabilityResolver(
            new DefaultEffectiveCapabilitiesResolver(),
            actionRegistry,
            inventory,
            (ignored, context) -> authority.get()
        );
        effectiveProfile = resolveProfile(trustedContext);
        coordinator = new ActionProposalCoordinator(
            repository,
            security,
            new ActionProposalValidator(),
            new ActionOutcomeProjectorRegistry(List.of(projector())),
            specialistRegistry,
            actionRegistry,
            policyResolutionStep,
            capabilityResolver,
            invocationService(),
            ActionProposalMetrics.noop(),
            clock,
            Duration.ofMinutes(10)
        );
    }

    ActionProposalView propose() {
        return propose("public-idempotency-key");
    }

    ActionProposalView propose(String idempotencyKey) {
        return coordinator.propose(
            "invocation-1",
            definition,
            candidate(),
            trustedContext,
            effectiveProfile,
            idempotencyKey,
            List.of(new AIEvidenceReference(
                "policy-address",
                "A validated billing address is required.",
                0.98,
                "policy",
                null,
                "account-resolution-policy",
                Map.of("policyCode", "ADDRESS_REQUIRED")
            ))
        );
    }

    ActionProposalCandidate candidate() {
        Map<String, Object> parameters = Map.of(
            "addressType",
            "BILLING",
            "streetAddress",
            "1 Main Street",
            "city",
            "London",
            "state",
            "London",
            "postalCode",
            "SW1A 1AA",
            "country",
            "GB"
        );
        return new ActionProposalCandidate(
            ACTION,
            parameters,
            new ActionContext(
                OrchestrationContext.forUser("model-supplied-user"),
                null,
                parameters
            )
        );
    }

    TrustedExecutionContext trusted(
        String principalId,
        String subjectId,
        String tenantId
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                principalId,
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef("account", subjectId),
            ExecutionSource.INTERACTIVE,
            tenantId,
            "test-deployment",
            Set.of(
                "specialist:account-resolver@1",
                "action:" + ACTION
            ),
            "correlation-1",
            NOW
        );
    }

    EffectiveCapabilityProfile resolveProfile(
        TrustedExecutionContext context
    ) {
        OrchestrationContext orchestrationContext =
            OrchestrationContext.builder()
                .userId(context.subject().subjectId())
                .position("resolver")
                .mode("resolver")
                .build();
        OrchestrationRequest request = new OrchestrationRequest(
            "Resolve current capabilities.",
            orchestrationContext,
            context,
            ConversationPersistencePolicy.NEVER
        );
        PipelineContext preflight = policyResolutionStep.process(
            PipelineContext.from(request)
        );
        return capabilityResolver.resolve(definition, preflight, context);
    }

    static ActionProposalReceipt receipt(
        ActionProposalReceiptStatus status,
        Instant updatedAt
    ) {
        Instant createdAt = updatedAt.minusSeconds(30);
        Instant confirmedAt = status == ActionProposalReceiptStatus.PROPOSED
            ? null
            : createdAt.plusSeconds(5);
        Instant executingAt =
            status == ActionProposalReceiptStatus.EXECUTING
                || status.terminal()
                ? createdAt.plusSeconds(10)
                : null;
        Instant terminalAt = status.terminal() ? updatedAt : null;
        return new ActionProposalReceipt(
            "receipt-" + status.name().toLowerCase(),
            "invocation-" + status.name().toLowerCase(),
            SPECIALIST_ID,
            "profile-hash",
            "principal-fingerprint",
            "account",
            "subject-fingerprint",
            "tenant-fingerprint",
            "deployment-fingerprint",
            ACTION,
            "v1.protected-parameters",
            "parameter-hash",
            "schema-hash",
            "Update the billing address?",
            "idempotency-" + status.name().toLowerCase(),
            List.of("evidence-hash"),
            status,
            createdAt,
            createdAt.plus(Duration.ofMinutes(10)),
            confirmedAt,
            executingAt,
            terminalAt,
            terminalAt,
            null,
            null,
            updatedAt,
            0
        );
    }

    private GovernedActionInvocationService invocationService() {
        return invocation -> {
            if (invocation.confirmationState()
                != ActionConfirmationState.CONFIRMED) {
                preflightSubject.set(
                    invocation.actionContext().userId()
                );
                return preflightOutcome.get();
            }
            confirmedInvocations.incrementAndGet();
            return confirmedOutcome.get();
        };
    }

    private EffectiveCapabilityProfile resolveProfile() {
        return resolveProfile(trustedContext);
    }

    private OrchestrationPolicy policy() {
        return new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            "resolver",
            "resolver",
            null,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            OrchestrationPolicy.RagBudgets.defaults()
        );
    }

    private ActionOutcomeProjector projector() {
        return new ActionOutcomeProjector() {
            @Override
            public String actionName() {
                return ACTION;
            }

            @Override
            public ActionOutcomeView project(ActionResult result) {
                return new ActionOutcomeView(
                    ACTION,
                    result != null && result.isSuccess()
                        ? "Address updated."
                        : "Address update failed.",
                    Map.of(
                        "updated",
                        result != null && result.isSuccess(),
                        "addressType",
                        "BILLING"
                    )
                );
            }
        };
    }

    private GovernedActionInvocationOutcome confirmationOutcome() {
        ActionResult result = ActionResult.builder()
            .success(false)
            .message("Update the billing address?")
            .errorCode("CONFIRMATION_REQUIRED")
            .build();
        return new GovernedActionInvocationOutcome(
            GovernedActionInvocationStatus.CONFIRMATION_REQUIRED,
            result,
            new ActionInvocationFailure(
                "CONFIRMATION_REQUIRED",
                result.getMessage(),
                false
            )
        );
    }

    private GovernedActionInvocationOutcome executedOutcome() {
        ActionResult result = ActionResult.builder()
            .success(true)
            .message("Updated")
            .data(ActionResultContracts.object(Map.of(
                "subscriptionId",
                "internal-subscription-id",
                "streetAddress",
                "1 Main Street",
                "addressType",
                "BILLING"
            )))
            .build();
        return new GovernedActionInvocationOutcome(
            GovernedActionInvocationStatus.EXECUTED,
            result,
            null
        );
    }

    private AIActionMetaData metadata() {
        Map<String, AIActionParamSchema> schemas = Map.of(
            "addressType",
            AIActionParamSchema.builder()
                .name("addressType")
                .type(AIActionParamType.STRING)
                .allowedValues(List.of("BILLING", "SHIPPING"))
                .build(),
            "streetAddress",
            requiredString("streetAddress"),
            "city",
            requiredString("city"),
            "state",
            requiredString("state"),
            "postalCode",
            requiredString("postalCode"),
            "country",
            requiredString("country")
        );
        return AIActionMetaData.builder()
            .name(ACTION)
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .confirmationRequired(true)
            .parameterSchemas(schemas)
            .requiredParameters(Set.of(
                "streetAddress",
                "city",
                "state",
                "postalCode",
                "country"
            ))
            .build();
    }

    private AIActionParamSchema requiredString(String name) {
        return AIActionParamSchema.builder()
            .name(name)
            .type(AIActionParamType.STRING)
            .required(true)
            .build();
    }

    private SpecialistDefinition<String, String> definition() {
        RequestedCapabilityProfile capabilities =
            new RequestedCapabilityProfile(
                false,
                Set.of(),
                Set.of(ACTION),
                Set.of(),
                Set.of(ACTION)
            );
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                SPECIALIST_ID,
                "Account Resolver",
                "Resolves the current account"
            ),
            new SpecialistInstructions(
                "Resolve the current account",
                "Never bypass confirmation."
            ),
            new SpecialistExecutionProfile(
                "resolver",
                capabilities,
                ExecutionStrategy.SINGLE_PASS,
                true
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
                    return result != null ? result.getMessage() : null;
                }

                @Override
                public void validate(String output) {}
            }
        );
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;

        MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
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
            return instant.get();
        }
    }
}
