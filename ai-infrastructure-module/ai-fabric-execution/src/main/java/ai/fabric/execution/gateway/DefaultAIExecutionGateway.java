package ai.fabric.execution.gateway;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalPersistenceException;
import ai.fabric.execution.action.ActionProposalValidationException;
import ai.fabric.execution.action.ActionProposalView;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.gateway.DefaultSpecialistAuthorityResolver.AuthorityDeniedException;
import ai.fabric.execution.input.InputDeliveryTarget;
import ai.fabric.execution.input.NeedsUserInput;
import ai.fabric.execution.input.SpecialistInputContinuation;
import ai.fabric.execution.input.SpecialistInputRequirement;
import ai.fabric.execution.input.SpecialistInputResponseContract;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistOutputMode;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistConversationBinding;
import ai.fabric.execution.specialist.manifest.SpecialistInteractionCapability;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDirection;
import ai.fabric.execution.specialist.manifest.SpecialistManifestMetrics;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.invocation.ActionProposalCandidate;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityPolicySupport;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationIntentPolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * Single-invocation coordinator over the existing AI Fabric pipeline.
 */
public final class DefaultAIExecutionGateway
    implements AIExecutionGateway, AssignedExecutionRunner {

    private static final String EXECUTION_DEADLINE_DIAGNOSTIC =
        "executionDeadline";
    private static final Set<String> VERIFIED_AUTH_METADATA_KEYS = Set.of(
        OrchestrationContextMetadataKeys.SUBJECT_ID,
        OrchestrationContextMetadataKeys.SUBJECT_TYPE,
        OrchestrationContextMetadataKeys.AUTH_MODE,
        OrchestrationContextMetadataKeys.CALLER_TYPE,
        OrchestrationContextMetadataKeys.AUTH_ISSUER,
        OrchestrationContextMetadataKeys.AUTH_AUDIENCES,
        OrchestrationContextMetadataKeys.AUTH_EXPIRES_AT,
        OrchestrationContextMetadataKeys.DEPLOYMENT_ID,
        OrchestrationContextMetadataKeys.CUSTOMER_ID,
        OrchestrationContextMetadataKeys.TENANT_ID,
        OrchestrationContextMetadataKeys.GRANTED_SCOPES,
        OrchestrationContextMetadataKeys.REQUESTED_SCOPES
    );
    private static final Logger log =
        LoggerFactory.getLogger(DefaultAIExecutionGateway.class);

    private final SpecialistRegistry specialistRegistry;
    private final Pipeline pipeline;
    private final OrchestrationPolicyResolutionStep policyResolutionStep;
    private final SpecialistCapabilityResolver specialistCapabilityResolver;
    private final OrchestrationEvidenceProjector evidenceProjector;
    private final SpecialistOutputFinalizer outputFinalizer;
    private final AIExecutionConversationRecorder conversationRecorder;
    private final java.util.function.Supplier<ActionProposalCoordinator>
        actionProposalCoordinator;
    private final AsyncTaskExecutor taskExecutor;
    private final Clock clock;
    private final EphemeralExecutionStore executionStore;
    private final EphemeralInputWaitStore inputWaitStore;
    private final SpecialistManifestMetrics specialistMetrics;
    private final SpecialistJsonSchemaRegistry schemaRegistry;
    private final SpecialistJsonSchemaValidator schemaValidator;
    private final CanonicalJsonSupport canonicalJson;
    private final AIExecutionConversationSnapshotRegistry
        conversationSnapshotRegistry;

    public DefaultAIExecutionGateway(
        SpecialistRegistry specialistRegistry,
        Pipeline pipeline,
        OrchestrationPolicyResolutionStep policyResolutionStep,
        EffectiveCapabilitiesResolver capabilitiesResolver,
        AIActionRegistry actionRegistry,
        ExecutionCapabilityInventory capabilityInventory,
        SpecialistAuthorityResolver authorityResolver,
        OrchestrationEvidenceProjector evidenceProjector,
        SpecialistOutputFinalizer outputFinalizer,
        AIExecutionConversationRecorder conversationRecorder,
        AsyncTaskExecutor taskExecutor,
        Clock clock,
        java.time.Duration resultTtl
    ) {
        this(
            specialistRegistry,
            pipeline,
            policyResolutionStep,
            capabilitiesResolver,
            actionRegistry,
            capabilityInventory,
            authorityResolver,
            evidenceProjector,
            outputFinalizer,
            conversationRecorder,
            () -> null,
            taskExecutor,
            clock,
            resultTtl,
            SpecialistManifestMetrics.noop()
        );
    }

    public DefaultAIExecutionGateway(
        SpecialistRegistry specialistRegistry,
        Pipeline pipeline,
        OrchestrationPolicyResolutionStep policyResolutionStep,
        EffectiveCapabilitiesResolver capabilitiesResolver,
        AIActionRegistry actionRegistry,
        ExecutionCapabilityInventory capabilityInventory,
        SpecialistAuthorityResolver authorityResolver,
        OrchestrationEvidenceProjector evidenceProjector,
        SpecialistOutputFinalizer outputFinalizer,
        AIExecutionConversationRecorder conversationRecorder,
        java.util.function.Supplier<ActionProposalCoordinator>
            actionProposalCoordinator,
        AsyncTaskExecutor taskExecutor,
        Clock clock,
        java.time.Duration resultTtl
    ) {
        this(
            specialistRegistry,
            pipeline,
            policyResolutionStep,
            capabilitiesResolver,
            actionRegistry,
            capabilityInventory,
            authorityResolver,
            evidenceProjector,
            outputFinalizer,
            conversationRecorder,
            actionProposalCoordinator,
            taskExecutor,
            clock,
            resultTtl,
            SpecialistManifestMetrics.noop()
        );
    }

    public DefaultAIExecutionGateway(
        SpecialistRegistry specialistRegistry,
        Pipeline pipeline,
        OrchestrationPolicyResolutionStep policyResolutionStep,
        EffectiveCapabilitiesResolver capabilitiesResolver,
        AIActionRegistry actionRegistry,
        ExecutionCapabilityInventory capabilityInventory,
        SpecialistAuthorityResolver authorityResolver,
        OrchestrationEvidenceProjector evidenceProjector,
        SpecialistOutputFinalizer outputFinalizer,
        AIExecutionConversationRecorder conversationRecorder,
        java.util.function.Supplier<ActionProposalCoordinator>
            actionProposalCoordinator,
        AsyncTaskExecutor taskExecutor,
        Clock clock,
        java.time.Duration resultTtl,
        SpecialistManifestMetrics specialistMetrics
    ) {
        this(
            specialistRegistry,
            pipeline,
            policyResolutionStep,
            capabilitiesResolver,
            actionRegistry,
            capabilityInventory,
            authorityResolver,
            evidenceProjector,
            outputFinalizer,
            conversationRecorder,
            actionProposalCoordinator,
            taskExecutor,
            clock,
            resultTtl,
            specialistMetrics,
            new SpecialistJsonSchemaRegistry(
                List.of(),
                new SpecialistJsonSchemaValidator()
            ),
            new SpecialistJsonSchemaValidator(),
            new CanonicalJsonSupport(
                new com.fasterxml.jackson.databind.ObjectMapper()
            ),
            new AIExecutionProperties.InputWaits()
        );
    }

    public DefaultAIExecutionGateway(
        SpecialistRegistry specialistRegistry,
        Pipeline pipeline,
        OrchestrationPolicyResolutionStep policyResolutionStep,
        EffectiveCapabilitiesResolver capabilitiesResolver,
        AIActionRegistry actionRegistry,
        ExecutionCapabilityInventory capabilityInventory,
        SpecialistAuthorityResolver authorityResolver,
        OrchestrationEvidenceProjector evidenceProjector,
        SpecialistOutputFinalizer outputFinalizer,
        AIExecutionConversationRecorder conversationRecorder,
        java.util.function.Supplier<ActionProposalCoordinator>
            actionProposalCoordinator,
        AsyncTaskExecutor taskExecutor,
        Clock clock,
        java.time.Duration resultTtl,
        SpecialistManifestMetrics specialistMetrics,
        SpecialistJsonSchemaRegistry schemaRegistry,
        SpecialistJsonSchemaValidator schemaValidator,
        CanonicalJsonSupport canonicalJson,
        AIExecutionProperties.InputWaits inputWaitProperties
    ) {
        this(
            specialistRegistry,
            pipeline,
            policyResolutionStep,
            capabilitiesResolver,
            actionRegistry,
            capabilityInventory,
            authorityResolver,
            evidenceProjector,
            outputFinalizer,
            conversationRecorder,
            actionProposalCoordinator,
            taskExecutor,
            clock,
            resultTtl,
            specialistMetrics,
            schemaRegistry,
            schemaValidator,
            canonicalJson,
            inputWaitProperties,
            null
        );
    }

    public DefaultAIExecutionGateway(
        SpecialistRegistry specialistRegistry,
        Pipeline pipeline,
        OrchestrationPolicyResolutionStep policyResolutionStep,
        EffectiveCapabilitiesResolver capabilitiesResolver,
        AIActionRegistry actionRegistry,
        ExecutionCapabilityInventory capabilityInventory,
        SpecialistAuthorityResolver authorityResolver,
        OrchestrationEvidenceProjector evidenceProjector,
        SpecialistOutputFinalizer outputFinalizer,
        AIExecutionConversationRecorder conversationRecorder,
        java.util.function.Supplier<ActionProposalCoordinator>
            actionProposalCoordinator,
        AsyncTaskExecutor taskExecutor,
        Clock clock,
        java.time.Duration resultTtl,
        SpecialistManifestMetrics specialistMetrics,
        SpecialistJsonSchemaRegistry schemaRegistry,
        SpecialistJsonSchemaValidator schemaValidator,
        CanonicalJsonSupport canonicalJson,
        AIExecutionProperties.InputWaits inputWaitProperties,
        AIExecutionConversationSnapshotRegistry conversationSnapshotRegistry
    ) {
        this.specialistRegistry = java.util.Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        this.pipeline = java.util.Objects.requireNonNull(pipeline, "pipeline is required");
        this.policyResolutionStep = java.util.Objects.requireNonNull(
            policyResolutionStep,
            "policyResolutionStep is required"
        );
        this.specialistCapabilityResolver = new SpecialistCapabilityResolver(
            capabilitiesResolver,
            actionRegistry,
            capabilityInventory,
            authorityResolver
        );
        this.evidenceProjector = java.util.Objects.requireNonNull(
            evidenceProjector,
            "evidenceProjector is required"
        );
        this.outputFinalizer = java.util.Objects.requireNonNull(
            outputFinalizer,
            "outputFinalizer is required"
        );
        this.conversationRecorder = conversationRecorder;
        this.actionProposalCoordinator = java.util.Objects.requireNonNull(
            actionProposalCoordinator,
            "actionProposalCoordinator supplier is required"
        );
        this.taskExecutor = java.util.Objects.requireNonNull(
            taskExecutor,
            "taskExecutor is required"
        );
        this.clock = java.util.Objects.requireNonNull(clock, "clock is required");
        this.executionStore = new EphemeralExecutionStore(clock, resultTtl);
        this.inputWaitStore = new EphemeralInputWaitStore(
            clock,
            inputWaitProperties
        );
        this.specialistMetrics = specialistMetrics != null
            ? specialistMetrics
            : SpecialistManifestMetrics.noop();
        this.schemaRegistry = java.util.Objects.requireNonNull(
            schemaRegistry,
            "schemaRegistry is required"
        );
        this.schemaValidator = java.util.Objects.requireNonNull(
            schemaValidator,
            "schemaValidator is required"
        );
        this.canonicalJson = java.util.Objects.requireNonNull(
            canonicalJson,
            "canonicalJson is required"
        );
        this.conversationSnapshotRegistry = conversationSnapshotRegistry;
    }

    @Override
    public <I, O> AIExecutionResult<O> execute(AIExecutionRequest<I> request) {
        String invocationId = invocationId();
        return executeInternal(invocationId, request);
    }

    @Override
    public ExecutionHandle submit(AIExecutionRequest<?> request) {
        java.util.Objects.requireNonNull(request, "request is required");
        String invocationId = invocationId();
        ExecutionAccessBinding accessBinding = ExecutionAccessBinding.from(
            request.trustedExecutionContext()
        );
        SpecialistDefinition<?, ?> definition;
        try {
            definition = specialistRegistry.require(request.specialistId());
        } catch (Exception ex) {
            EphemeralExecutionStore.Entry rejected = executionStore.create(
                invocationId,
                null,
                accessBinding,
                null,
                request.deadline(),
                ExecutionHandleStatus.REJECTED,
                "SPECIALIST_NOT_FOUND"
            );
            return executionStore.snapshot(rejected).handle();
        }
        Instant deadline = requestedDeadline(request, definition);
        String requestFingerprint;
        try {
            requestFingerprint = request.idempotencyKey() == null
                ? null
                : submissionFingerprint(request);
        } catch (RuntimeException ex) {
            return rejectedHandle(
                invocationId,
                accessBinding,
                deadline,
                "IDEMPOTENCY_FINGERPRINT_INVALID"
            );
        }
        ExecutionHandle replay = replayOrConflict(
            invocationId,
            request,
            accessBinding,
            requestFingerprint,
            deadline
        );
        if (replay != null) {
            return replay;
        }

        EphemeralExecutionStore.Entry entry;
        try {
            entry = executionStore.create(
                invocationId,
                request.idempotencyKey(),
                accessBinding,
                requestFingerprint,
                deadline,
                ExecutionHandleStatus.QUEUED,
                null
            );
        } catch (EphemeralExecutionStore.DuplicateIdempotencyKeyException ex) {
            ExecutionHandle raced = replayOrConflict(
                invocationId(),
                request,
                accessBinding,
                requestFingerprint,
                deadline
            );
            return raced != null
                ? raced
                : rejectedHandle(
                    invocationId(),
                    accessBinding,
                    deadline,
                    "IDEMPOTENCY_CONFLICT"
                );
        }

        try {
            var future = taskExecutor.submit(() -> {
                if (!executionStore.markRunning(entry)) {
                    return;
                }
                AIExecutionResult<?> result = executeInternal(
                    invocationId,
                    castRequest(request)
                );
                executionStore.complete(entry, result);
            });
            executionStore.attachFuture(entry, future);
        } catch (RejectedExecutionException ex) {
            executionStore.reject(entry, "QUEUE_CAPACITY_EXCEEDED");
        }
        return executionStore.snapshot(entry).handle();
    }

    private ExecutionHandle replayOrConflict(
        String rejectedInvocationId,
        AIExecutionRequest<?> request,
        ExecutionAccessBinding accessBinding,
        String requestFingerprint,
        Instant deadline
    ) {
        EphemeralExecutionStore.IdempotencyReplay replay =
            executionStore.replay(
                request.idempotencyKey(),
                accessBinding,
                requestFingerprint
            );
        return switch (replay.status()) {
            case MISSING -> null;
            case MATCH -> executionStore.snapshot(replay.entry()).handle();
            case CONFLICT -> rejectedHandle(
                rejectedInvocationId,
                accessBinding,
                deadline,
                "IDEMPOTENCY_CONFLICT"
            );
        };
    }

    private ExecutionHandle rejectedHandle(
        String invocationId,
        ExecutionAccessBinding accessBinding,
        Instant deadline,
        String reason
    ) {
        EphemeralExecutionStore.Entry rejected = executionStore.create(
            invocationId,
            null,
            accessBinding,
            null,
            deadline,
            ExecutionHandleStatus.REJECTED,
            reason
        );
        return executionStore.snapshot(rejected).handle();
    }

    private String submissionFingerprint(AIExecutionRequest<?> request) {
        ConversationBinding binding = request.conversationBinding();
        return canonicalJson.hashValue(new SubmissionFingerprint(
            request.specialistId().toString(),
            request.input(),
            binding == null
                ? null
                : new ConversationIdentity(
                    binding.userId(),
                    binding.conversationId()
                )
        ));
    }

    @Override
    public <O> AIExecutionResumeResult<O> resume(
        AIExecutionResumeRequest request
    ) {
        java.util.Objects.requireNonNull(request, "request is required");
        String responseHash = canonicalJson.hash(request.response());
        EphemeralInputWaitStore.Claim claim = inputWaitStore.claim(
            request,
            responseHash
        );
        return switch (claim.status()) {
            case ACQUIRED -> resumeClaim(request, claim.entry());
            case REPLAYED -> replayed(claim.replayedResult());
            case DENIED -> resumeRejected(
                AIExecutionResumeStatus.DENIED,
                "INPUT_REQUEST_UNAVAILABLE",
                "The input request is not available for this trusted context.",
                false
            );
            case EXPIRED -> resumeRejected(
                AIExecutionResumeStatus.EXPIRED,
                "INPUT_REQUEST_EXPIRED",
                "The input request has expired.",
                false
            );
            case CONFLICT -> resumeRejected(
                AIExecutionResumeStatus.REJECTED,
                "INPUT_RESUME_CONFLICT",
                "The input request was already resumed with different data.",
                false
            );
            case IN_PROGRESS -> resumeRejected(
                AIExecutionResumeStatus.IN_PROGRESS,
                "INPUT_RESUME_IN_PROGRESS",
                "An identical input resume is already in progress.",
                true
            );
            case CANCELLED -> resumeRejected(
                AIExecutionResumeStatus.REJECTED,
                "INPUT_REQUEST_CANCELLED",
                "The input request is no longer active.",
                false
            );
        };
    }

    @Override
    public Optional<ExecutionSnapshot> find(
        String invocationId,
        TrustedExecutionContext trustedExecutionContext
    ) {
        if (invocationId == null || invocationId.isBlank()) {
            return Optional.empty();
        }
        java.util.Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        Optional<ExecutionSnapshot> asynchronous = executionStore
            .find(invocationId.trim(), trustedExecutionContext)
            .map(executionStore::snapshot);
        if (asynchronous.isPresent()) {
            return asynchronous;
        }
        return inputWaitStore
            .findByInvocation(
                invocationId.trim(),
                trustedExecutionContext
            )
            .map(inputWaitStore::snapshot);
    }

    @Override
    public boolean cancel(
        String invocationId,
        TrustedExecutionContext trustedExecutionContext
    ) {
        java.util.Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        boolean inputCancelled = inputWaitStore.cancel(
            invocationId,
            trustedExecutionContext
        );
        boolean executionCancelled = findEntry(
            invocationId,
            trustedExecutionContext
        ).map(executionStore::cancel).orElse(false);
        return inputCancelled || executionCancelled;
    }

    private <I, O> AIExecutionResult<O> executeInternal(
        String invocationId,
        AIExecutionRequest<I> request
    ) {
        return executeInternal(invocationId, request, null, null);
    }

    @Override
    public AIExecutionResult<?> executeAssigned(
        String invocationId,
        AIExecutionRequest<?> request
    ) {
        return executeInternal(
            invocationId,
            castRequest(request)
        );
    }

    @Override
    public Instant resolveDeadline(
        AIExecutionRequest<?> request,
        SpecialistDefinition<?, ?> definition
    ) {
        return requestedDeadline(request, definition);
    }

    private <I, O> AIExecutionResult<O> executeInternal(
        String invocationId,
        AIExecutionRequest<I> request,
        ResumeConstraints resumeConstraints,
        Instant originalStartedAt
    ) {
        AIExecutionResult<O> result = doExecuteInternal(
            invocationId,
            request,
            resumeConstraints,
            originalStartedAt
        );
        specialistRegistry.findRegistered(result.specialistId()).ifPresent(
            registered -> specialistMetrics.recordExecution(
                registered.source(),
                result.status().name()
            )
        );
        return result;
    }

    private <I, O> AIExecutionResult<O> doExecuteInternal(
        String invocationId,
        AIExecutionRequest<I> request,
        ResumeConstraints resumeConstraints,
        Instant originalStartedAt
    ) {
        Instant startedAt = originalStartedAt != null
            ? originalStartedAt
            : clock.instant();
        if (request == null) {
            return failure(
                invocationId,
                null,
                AIExecutionStatus.INVALID,
                "REQUEST_REQUIRED",
                "Execution request is required.",
                false,
                startedAt,
                Map.of()
            );
        }

        SpecialistDefinition<I, O> definition;
        try {
            definition = definition(request);
        } catch (Exception ex) {
            return failure(
                invocationId,
                request.specialistId(),
                AIExecutionStatus.INVALID,
                "SPECIALIST_NOT_FOUND",
                "The requested specialist is not registered.",
                false,
                startedAt,
                Map.of()
            );
        }

        Instant deadline = requestedDeadline(request, definition);
        if (deadline != null && !clock.instant().isBefore(deadline)) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.DEADLINE_EXCEEDED,
                "DEADLINE_EXCEEDED",
                "The execution deadline has elapsed.",
                true,
                startedAt,
                Map.of()
            );
        }

        try {
            SpecialistInputAdapter<I> inputAdapter = definition.inputAdapter();
            SpecialistConversationBinding conversationPolicy =
                inputAdapter.conversationBinding();
            if (conversationPolicy == SpecialistConversationBinding.DISABLED
                && request.conversationBinding() != null) {
                throw new ContractValidationException(
                    "CONVERSATION_BINDING_DISABLED",
                    "This specialist does not accept a conversation binding."
                );
            }
            if (conversationPolicy == SpecialistConversationBinding.REQUIRED
                && request.conversationBinding() == null) {
                throw new ContractValidationException(
                    "CONVERSATION_BINDING_REQUIRED",
                    "This specialist requires a conversation binding."
                );
            }
            String applicationInput;
            OrchestrationContext orchestrationContext;
            try {
                if (!inputAdapter.inputType().isInstance(request.input())) {
                    throw new IllegalArgumentException(
                        "Input must be " + inputAdapter.inputType().getName()
                    );
                }
                Optional<SpecialistInputContinuation<I>> continuation =
                    inputAdapter.inputContinuation();
                if (continuation.isPresent()) {
                    SpecialistInputContinuation<I> extension =
                        continuation.get();
                    if (extension.inputType() != inputAdapter.inputType()) {
                        throw new IllegalArgumentException(
                            "Input continuation type does not match its adapter"
                        );
                    }
                    Optional<SpecialistInputRequirement> requirement =
                        extension.requiredInput(request.input());
                    if (requirement == null) {
                        throw new IllegalArgumentException(
                            "Input continuation returned null instead of Optional"
                        );
                    }
                    if (requirement.isPresent()) {
                        return waitForInput(
                            invocationId,
                            request,
                            definition,
                            inputAdapter,
                            extension,
                            requirement.get(),
                            resumeConstraints,
                            startedAt
                        );
                    }
                }
                inputAdapter.validate(request.input());
                applicationInput = inputAdapter.renderModelInput(request.input());
                if (applicationInput == null || applicationInput.isBlank()) {
                    throw new IllegalArgumentException(
                        "Input adapter returned blank model input"
                    );
                }
                orchestrationContext = inputAdapter.orchestrationContext(request.input());
            } catch (IllegalArgumentException ex) {
                throw new ContractValidationException(
                    "INPUT_VALIDATION_FAILED",
                    ex.getMessage()
                );
            }
            String modelInput = applicationInput.trim();
            if (modelInput.length() > definition.limits().maxInputCharacters()) {
                throw new ContractValidationException(
                    "INPUT_LIMIT_EXCEEDED",
                    "Rendered input exceeds specialist limit"
                );
            }

            if (orchestrationContext == null) {
                orchestrationContext = OrchestrationContext.builder().build();
            }
            orchestrationContext = bindContext(
                orchestrationContext,
                definition,
                request.conversationBinding(),
                request.trustedExecutionContext()
            );
            ConversationPersistencePolicy persistence =
                request.conversationBinding() == null
                    ? ConversationPersistencePolicy.NEVER
                    : ConversationPersistencePolicy.READ_ONLY;
            String conversationInput = null;
            if (request.conversationBinding() != null
                && inputAdapter.recordValidatedTurns()) {
                conversationInput = inputAdapter.conversationInput(request.input());
                if (conversationInput == null || conversationInput.isBlank()) {
                    throw new ContractValidationException(
                        "CONVERSATION_INPUT_REQUIRED",
                        "The input adapter must provide safe conversation input."
                    );
                }
                if (conversationRecorder == null) {
                    throw new ConversationRecordingException(
                        "Conversation persistence is unavailable in this deployment."
                    );
                }
            }

            OrchestrationRequest preflightRequest = new OrchestrationRequest(
                modelInput,
                orchestrationContext,
                request.trustedExecutionContext(),
                persistence
            );
            PipelineContext preflight = policyResolutionStep.process(
                PipelineContext.from(preflightRequest)
            );
            if (preflight.isShouldTerminate()) {
                return pipelineFailure(
                    invocationId,
                    definition,
                    preflight.getEarlyTerminationResult(),
                    startedAt,
                    Map.of("phase", "policy_resolution")
                );
            }

            EffectiveCapabilityProfile effective = resolveCapabilities(
                definition,
                preflight,
                request.trustedExecutionContext()
            );
            if (resumeConstraints != null
                && !resumeConstraints.effectiveProfileHash().equals(
                    effective.profileHash()
                )) {
                return failure(
                    invocationId,
                    definition.id(),
                    AIExecutionStatus.DENIED,
                    "EFFECTIVE_PROFILE_CHANGED",
                    "The effective specialist profile changed while waiting.",
                    false,
                    startedAt,
                    Map.of()
                );
            }

            OrchestrationContext effectiveContext = preflight.getOrchestrationContext()
                .toBuilder()
                .orchestrationPolicy(EffectiveCapabilityPolicySupport.constrain(
                    preflight.getOrchestrationPolicy(),
                    effective
                ))
                .effectiveCapabilityProfile(effective)
                .build();
            OrchestrationRequest orchestrationRequest = new OrchestrationRequest(
                modelInput,
                effectiveContext,
                request.trustedExecutionContext(),
                persistence,
                effective,
                conversationInput,
                definition.instructions().render(),
                OrchestrationRequestPurpose.SPECIALIST,
                definition.outputAdapter().orchestrationIntentPolicy()
            );
            OrchestrationResult orchestrationResult = pipeline.execute(orchestrationRequest);
            if (deadline != null && !clock.instant().isBefore(deadline)) {
                return failure(
                    invocationId,
                    definition.id(),
                    AIExecutionStatus.DEADLINE_EXCEEDED,
                    "DEADLINE_EXCEEDED",
                    "The execution exceeded its deadline.",
                    true,
                    startedAt,
                    diagnostics(definition, effective, null, 0)
                );
            }
            ActionProposalCandidate proposalCandidate =
                extractProposalCandidate(orchestrationResult);
            if (proposalCandidate != null) {
                ActionProposalCoordinator proposalCoordinator =
                    actionProposalCoordinator.get();
                if (proposalCoordinator == null) {
                    return failure(
                        invocationId,
                        definition.id(),
                        AIExecutionStatus.FAILED,
                        "ACTION_PROPOSAL_COORDINATOR_UNAVAILABLE",
                        "Durable action proposal support is unavailable.",
                        false,
                        startedAt,
                        diagnostics(
                            definition,
                            effective,
                            orchestrationResult,
                            0
                        )
                    );
                }
                String defaultVectorSpace =
                    effective.effectiveVectorSpaces().size() == 1
                        ? effective.effectiveVectorSpaces().iterator().next()
                        : null;
                List<AIEvidenceReference> proposalEvidence =
                    evidenceProjector.projectStrict(
                        orchestrationResult,
                        effective.effectiveVectorSpaces(),
                        defaultVectorSpace,
                        definition.limits().maxEvidenceReferences()
                    );
                ActionProposalView proposal =
                    proposalCoordinator.propose(
                        invocationId,
                        definition,
                        proposalCandidate,
                        request.trustedExecutionContext(),
                        effective,
                        request.idempotencyKey(),
                        proposalEvidence
                    );
                Map<String, Object> proposalDiagnostics =
                    new LinkedHashMap<>(
                        diagnostics(
                            definition,
                            effective,
                            orchestrationResult,
                            proposalEvidence.size()
                        )
                    );
                proposalDiagnostics.put("actionProposal", true);
                return new AIExecutionResult<>(
                    invocationId,
                    definition.id(),
                    AIExecutionStatus.CONFIRMATION_REQUIRED,
                    null,
                    proposalEvidence,
                    Map.copyOf(proposalDiagnostics),
                    null,
                    startedAt,
                    clock.instant(),
                    proposal
                );
            }
            if (orchestrationResult == null || !orchestrationResult.isSuccess()) {
                return pipelineFailure(
                    invocationId,
                    definition,
                    orchestrationResult,
                    startedAt,
                    diagnostics(definition, effective, orchestrationResult, 0)
                );
            }

            String defaultVectorSpace = effective.effectiveVectorSpaces().size() == 1
                ? effective.effectiveVectorSpaces().iterator().next()
                : null;
            List<AIEvidenceReference> evidence = evidenceProjector.projectStrict(
                orchestrationResult,
                effective.effectiveVectorSpaces(),
                defaultVectorSpace,
                definition.limits().maxEvidenceReferences()
            );
            SpecialistOutputAdapter<O> outputAdapter = definition.outputAdapter();
            try {
                outputAdapter.validateGrounding(orchestrationResult, evidence);
            } catch (IllegalArgumentException ex) {
                throw new ContractValidationException(
                    "GROUNDING_VALIDATION_FAILED",
                    ex.getMessage()
                );
            }
            SpecialistOutputFinalization<O> finalization = null;
            O output;
            if (outputAdapter.outputMode()
                == SpecialistOutputMode.STRUCTURED_GENERATION) {
                try {
                    finalization = outputFinalizer.finalizeOutput(
                        definition,
                        applicationInput,
                        effectiveContext,
                        orchestrationResult,
                        evidence
                    );
                    output = finalization.output();
                } catch (SpecialistOutputFinalizationException ex) {
                    Map<String, Object> failureDiagnostics =
                        new LinkedHashMap<>(
                            diagnostics(
                                definition,
                                effective,
                                orchestrationResult,
                                evidence.size()
                            )
                        );
                    failureDiagnostics.putAll(ex.diagnostics());
                    return failure(
                        invocationId,
                        definition.id(),
                        AIExecutionStatus.FAILED,
                        ex.reason(),
                        ex.getMessage(),
                        ex.retryable(),
                        startedAt,
                        failureDiagnostics
                    );
                }
            } else {
                try {
                    output = outputAdapter.project(orchestrationResult, evidence);
                } catch (IllegalArgumentException ex) {
                    throw new ContractValidationException(
                        "OUTPUT_SCHEMA_VALIDATION_FAILED",
                        ex.getMessage()
                    );
                } catch (Exception ex) {
                    throw new OutputProjectionException(
                        "The specialist output could not be projected."
                    );
                }
            }
            try {
                if (output == null || !outputAdapter.outputType().isInstance(output)) {
                    throw new IllegalArgumentException(
                        "Output must be " + outputAdapter.outputType().getName()
                    );
                }
                outputAdapter.validateFinalOutput(
                    output,
                    orchestrationResult,
                    evidence
                );
                output = outputAdapter.normalizeFinalOutput(
                    output,
                    orchestrationResult,
                    evidence
                );
                if (output == null || !outputAdapter.outputType().isInstance(output)) {
                    throw new IllegalArgumentException(
                        "Normalized output must be "
                            + outputAdapter.outputType().getName()
                    );
                }
                outputAdapter.validateFinalOutput(
                    output,
                    orchestrationResult,
                    evidence
                );
                int outputCharacters =
                    outputAdapter.serializedOutputCharacters(output);
                if (outputCharacters > definition.limits().maxOutputCharacters()) {
                    throw new IllegalArgumentException(
                        "Serialized output exceeds specialist limit"
                    );
                }
            } catch (IllegalArgumentException ex) {
                throw new ContractValidationException(
                    "OUTPUT_VALIDATION_FAILED",
                    ex.getMessage()
                );
            }
            if (deadline != null && !clock.instant().isBefore(deadline)) {
                return failure(
                    invocationId,
                    definition.id(),
                    AIExecutionStatus.DEADLINE_EXCEEDED,
                    "DEADLINE_EXCEEDED",
                    "The execution exceeded its deadline.",
                    true,
                    startedAt,
                    diagnostics(
                        definition,
                        effective,
                        orchestrationResult,
                        evidence.size()
                    )
                );
            }

            if (request.conversationBinding() != null
                && inputAdapter.recordValidatedTurns()) {
                recordValidatedConversationTurn(
                    invocationId,
                    request.conversationBinding(),
                    conversationInput,
                    output,
                    orchestrationResult,
                    outputAdapter,
                    definition,
                    effective,
                    orchestrationContext
                );
            }

            Map<String, Object> diagnostics = new LinkedHashMap<>(
                diagnostics(
                    definition,
                    effective,
                    orchestrationResult,
                    evidence.size()
                )
            );
            if (finalization != null) {
                diagnostics.putAll(finalization.diagnostics());
            } else {
                diagnostics.put(
                    "outputMode",
                    SpecialistOutputMode.DIRECT_PROJECTION.name()
                );
            }
            if (deadline != null) {
                diagnostics.put(
                    EXECUTION_DEADLINE_DIAGNOSTIC,
                    deadline.toString()
                );
            }
            addDialogueDiagnostics(diagnostics, orchestrationContext);
            log.info(
                "AI execution {} specialist={} status=SUCCEEDED profileHash={} evidenceCount={}",
                invocationId,
                definition.id(),
                effective.profileHash(),
                evidence.size()
            );
            return new AIExecutionResult<>(
                invocationId,
                definition.id(),
                AIExecutionStatus.SUCCEEDED,
                output,
                evidence,
                diagnostics,
                null,
                startedAt,
                clock.instant()
            );
        } catch (AuthorityDeniedException ex) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.DENIED,
                ex.reason(),
                ex.getMessage(),
                false,
                startedAt,
                Map.of()
            );
        } catch (SpecialistCapabilityResolutionException ex) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.DENIED,
                ex.reason(),
                ex.getMessage(),
                false,
                startedAt,
                Map.of()
            );
        } catch (OrchestrationEvidenceProjector.EvidencePolicyException ex) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.DENIED,
                ex.reason(),
                ex.getMessage(),
                false,
                startedAt,
                Map.of()
            );
        } catch (ActionProposalValidationException ex) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.DENIED,
                ex.reason(),
                ex.getMessage(),
                false,
                startedAt,
                Map.of()
            );
        } catch (ActionProposalPersistenceException ex) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.FAILED,
                ex.reason(),
                ex.getMessage(),
                true,
                startedAt,
                Map.of()
            );
        } catch (EphemeralInputWaitStore.InputWaitStoreException ex) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.FAILED,
                ex.reason(),
                ex.getMessage(),
                retryable(ex.reason()),
                startedAt,
                Map.of()
            );
        } catch (ContractValidationException ex) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.INVALID,
                ex.reason,
                ex.getMessage(),
                false,
                startedAt,
                Map.of()
            );
        } catch (OutputProjectionException ex) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.FAILED,
                "OUTPUT_PROJECTION_FAILED",
                ex.getMessage(),
                false,
                startedAt,
                Map.of()
            );
        } catch (ConversationRecordingException ex) {
            Throwable rootCause = rootCause(ex);
            log.warn(
                "AI execution {} specialist={} could not persist its validated conversation turn; cause={}",
                invocationId,
                definition.id(),
                rootCause.getClass().getSimpleName()
            );
            log.debug(
                "Validated conversation persistence failure for invocation {}",
                invocationId,
                ex
            );
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.FAILED,
                "CONVERSATION_RECORDING_FAILED",
                ex.getMessage(),
                false,
                startedAt,
                Map.of()
            );
        } catch (IllegalArgumentException ex) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.INVALID,
                "INPUT_OR_OUTPUT_VALIDATION_FAILED",
                ex.getMessage(),
                false,
                startedAt,
                Map.of()
            );
        } catch (Exception ex) {
            log.warn(
                "AI execution {} specialist={} failed: {}",
                invocationId,
                definition.id(),
                ex.getClass().getSimpleName()
            );
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.FAILED,
                "EXECUTION_FAILED",
                "AI execution failed.",
                false,
                startedAt,
                Map.of()
            );
        }
    }

    private <I, O> AIExecutionResult<O> waitForInput(
        String invocationId,
        AIExecutionRequest<I> request,
        SpecialistDefinition<I, O> definition,
        SpecialistInputAdapter<I> inputAdapter,
        SpecialistInputContinuation<I> continuation,
        SpecialistInputRequirement requirement,
        ResumeConstraints resumeConstraints,
        Instant startedAt
    ) {
        if (!continuation.responseSchemas().contains(
                requirement.responseSchemaId()
            )) {
            throw new ContractValidationException(
                "INPUT_RESPONSE_SCHEMA_NOT_DECLARED",
                "The input continuation requested an undeclared response schema."
            );
        }
        SpecialistSchemaDefinition responseSchema = schemaRegistry.require(
            requirement.responseSchemaId(),
            SpecialistSchemaDirection.INPUT
        );
        OrchestrationContext orchestrationContext;
        try {
            orchestrationContext = inputAdapter.orchestrationContext(
                request.input()
            );
        } catch (IllegalArgumentException ex) {
            throw new ContractValidationException(
                "INPUT_VALIDATION_FAILED",
                ex.getMessage()
            );
        }
        if (orchestrationContext == null) {
            orchestrationContext = OrchestrationContext.builder().build();
        }
        orchestrationContext = bindContext(
            orchestrationContext,
            definition,
            request.conversationBinding(),
            request.trustedExecutionContext()
        );
        String preflightInput =
            "Specialist input requirement: " + requirement.purposeCode();
        PipelineContext preflight = policyResolutionStep.process(
            PipelineContext.from(new OrchestrationRequest(
                preflightInput,
                orchestrationContext,
                request.trustedExecutionContext(),
                ConversationPersistencePolicy.NEVER
            ))
        );
        if (preflight.isShouldTerminate()) {
            return pipelineFailure(
                invocationId,
                definition,
                preflight.getEarlyTerminationResult(),
                startedAt,
                Map.of("phase", "input_wait_policy_resolution")
            );
        }
        EffectiveCapabilityProfile effective = resolveCapabilities(
            definition,
            preflight,
            request.trustedExecutionContext()
        );
        if (resumeConstraints != null
            && !resumeConstraints.effectiveProfileHash().equals(
                effective.profileHash()
            )) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.DENIED,
                "EFFECTIVE_PROFILE_CHANGED",
                "The effective specialist profile changed while waiting.",
                false,
                startedAt,
                Map.of()
            );
        }
        RegisteredSpecialist registered =
            specialistRegistry.requireRegistered(definition.id());
        if (resumeConstraints != null
            && !resumeConstraints.specialistContentHash().equals(
                registered.contentHash()
            )) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.DENIED,
                "SPECIALIST_CONTENT_CHANGED",
                "The specialist content changed while waiting.",
                false,
                startedAt,
                Map.of()
            );
        }
        I inputSnapshot;
        try {
            inputSnapshot = continuation.snapshot(request.input());
            if (inputSnapshot == null
                || !inputAdapter.inputType().isInstance(inputSnapshot)) {
                throw new IllegalArgumentException(
                    "Input continuation returned an invalid snapshot"
                );
            }
        } catch (IllegalArgumentException ex) {
            throw new ContractValidationException(
                "INPUT_SNAPSHOT_FAILED",
                ex.getMessage()
            );
        }
        AIExecutionRequest<I> retainedRequest = new AIExecutionRequest<>(
            request.specialistId(),
            inputSnapshot,
            request.trustedExecutionContext(),
            request.conversationBinding(),
            request.deadline(),
            request.idempotencyKey()
        );
        int requestNumber = resumeConstraints == null
            ? 1
            : resumeConstraints.nextRequestNumber();
        EphemeralInputWaitStore.Entry entry = inputWaitStore.create(
            invocationId,
            retainedRequest,
            inputSnapshot,
            continuation,
            requirement,
            responseSchema,
            registered,
            effective.profileHash(),
            startedAt,
            requestNumber
        );
        NeedsUserInput needsUserInput = new NeedsUserInput(
            entry.requestId(),
            invocationId,
            definition.id(),
            requirement.purposeCode(),
            requirement.safeQuestion(),
            new SpecialistInputResponseContract(
                responseSchema.id(),
                responseSchema.spec().schema()
            ),
            request.trustedExecutionContext().source()
                    == ai.fabric.execution.context.ExecutionSource.INTERACTIVE
                && request.conversationBinding() != null
                    ? InputDeliveryTarget.DIALOGUE_OWNER
                    : InputDeliveryTarget.HOST_APPLICATION,
            ExecutionDurability.EPHEMERAL,
            entry.createdAt(),
            entry.expiresAt(),
            entry.maxAttempts()
        );
        Map<String, Object> waitDiagnostics = new LinkedHashMap<>(
            diagnostics(definition, effective, null, 0)
        );
        waitDiagnostics.put("inputWait", true);
        waitDiagnostics.put(
            "inputWaitDurability",
            ExecutionDurability.EPHEMERAL.name()
        );
        waitDiagnostics.put(
            "inputPurpose",
            requirement.purposeCode()
        );
        AIExecutionResult<O> result = new AIExecutionResult<>(
            invocationId,
            definition.id(),
            AIExecutionStatus.WAITING_FOR_INPUT,
            null,
            List.of(),
            Map.copyOf(waitDiagnostics),
            null,
            startedAt,
            clock.instant(),
            null,
            needsUserInput
        );
        inputWaitStore.attachWaitingResult(entry, result);
        log.info(
            "AI execution {} specialist={} status=WAITING_FOR_INPUT purpose={} durability={}",
            invocationId,
            definition.id(),
            requirement.purposeCode(),
            ExecutionDurability.EPHEMERAL
        );
        return result;
    }

    @SuppressWarnings("unchecked")
    private <O> AIExecutionResumeResult<O> resumeClaim(
        AIExecutionResumeRequest request,
        EphemeralInputWaitStore.Entry entry
    ) {
        RegisteredSpecialist registered = specialistRegistry
            .findRegistered(request.specialistId())
            .orElse(null);
        if (registered == null
            || !registered.contentHash().equals(
                entry.specialistContentHash()
            )) {
            return terminalResumeFailure(
                entry,
                AIExecutionStatus.DENIED,
                "SPECIALIST_CONTENT_CHANGED",
                "The specialist content changed while waiting."
            );
        }
        SpecialistInputAdapter<Object> inputAdapter =
            (SpecialistInputAdapter<Object>) registered.definition()
                .inputAdapter();
        SpecialistInputContinuation<Object> continuation = inputAdapter
            .inputContinuation()
            .orElse(null);
        if (continuation == null
            || !continuation.id().equals(entry.continuation().id())
            || !continuation.responseSchemas().contains(
                entry.requirement().responseSchemaId()
            )) {
            return terminalResumeFailure(
                entry,
                AIExecutionStatus.DENIED,
                "INPUT_CONTINUATION_CHANGED",
                "The specialist input continuation changed while waiting."
            );
        }
        SpecialistSchemaDefinition currentSchema;
        try {
            currentSchema = schemaRegistry.require(
                entry.requirement().responseSchemaId(),
                SpecialistSchemaDirection.INPUT
            );
        } catch (RuntimeException ex) {
            return terminalResumeFailure(
                entry,
                AIExecutionStatus.DENIED,
                "INPUT_RESPONSE_SCHEMA_CHANGED",
                "The input response schema changed while waiting."
            );
        }
        if (!canonicalJson.write(currentSchema.spec().schema()).equals(
                canonicalJson.write(entry.responseSchema().spec().schema())
            )) {
            return terminalResumeFailure(
                entry,
                AIExecutionStatus.DENIED,
                "INPUT_RESPONSE_SCHEMA_CHANGED",
                "The input response schema changed while waiting."
            );
        }
        Object resumedInput;
        try {
            schemaValidator.validate(
                entry.responseSchema(),
                request.response()
            );
            resumedInput = continuation.resume(
                entry.inputSnapshot(),
                entry.requirement(),
                request.response()
            );
            if (resumedInput == null
                || !inputAdapter.inputType().isInstance(resumedInput)) {
                throw new IllegalArgumentException(
                    "Input continuation returned an invalid resumed input"
                );
            }
        } catch (IllegalArgumentException ex) {
            boolean waiting = inputWaitStore.rejectAttempt(entry);
            return resumeRejected(
                AIExecutionResumeStatus.REJECTED,
                waiting
                    ? "INPUT_RESPONSE_INVALID"
                    : "INPUT_RESPONSE_ATTEMPTS_EXHAUSTED",
                waiting
                    ? "The input response does not satisfy the required contract."
                    : "The input request is no longer active after repeated invalid responses.",
                waiting
            );
        }

        AIExecutionRequest<Object> resumedRequest = new AIExecutionRequest<>(
            entry.originalRequest().specialistId(),
            resumedInput,
            request.trustedExecutionContext(),
            entry.originalRequest().conversationBinding(),
            entry.originalRequest().deadline(),
            entry.originalRequest().idempotencyKey()
        );
        Optional<EphemeralExecutionStore.Entry> asynchronous =
            executionStore.find(
                entry.invocationId(),
                request.trustedExecutionContext()
            );
        if (asynchronous.isPresent()
            && !executionStore.markResuming(
                asynchronous.get(),
                requestedDeadline(
                    resumedRequest,
                    registered.definition()
                )
            )) {
            return terminalResumeFailure(
                entry,
                AIExecutionStatus.CANCELLED,
                "INPUT_RESUME_CANCELLED",
                "The execution was cancelled before it could resume."
            );
        }
        AIExecutionResult<O> result = executeInternal(
            entry.invocationId(),
            resumedRequest,
            new ResumeConstraints(
                entry.specialistContentHash(),
                entry.effectiveProfileHash(),
                entry.requestNumber() + 1
            ),
            entry.startedAt()
        );
        inputWaitStore.complete(entry, result);
        asynchronous.ifPresent(value ->
            executionStore.complete(value, result)
        );
        return AIExecutionResumeResult.resumed(result);
    }

    private <O> AIExecutionResumeResult<O> terminalResumeFailure(
        EphemeralInputWaitStore.Entry entry,
        AIExecutionStatus status,
        String reason,
        String message
    ) {
        AIExecutionResult<O> result = failure(
            entry.invocationId(),
            entry.originalRequest().specialistId(),
            status,
            reason,
            message,
            false,
            entry.startedAt(),
            Map.of()
        );
        inputWaitStore.complete(entry, result);
        executionStore.find(entry.invocationId()).ifPresent(value ->
            executionStore.complete(value, result)
        );
        return AIExecutionResumeResult.resumed(result);
    }

    @SuppressWarnings("unchecked")
    private <O> AIExecutionResumeResult<O> replayed(
        AIExecutionResult<?> result
    ) {
        return AIExecutionResumeResult.replayed(
            (AIExecutionResult<O>) result
        );
    }

    private <O> AIExecutionResumeResult<O> resumeRejected(
        AIExecutionResumeStatus status,
        String reason,
        String message,
        boolean retryable
    ) {
        return AIExecutionResumeResult.rejected(
            status,
            reason,
            message,
            retryable
        );
    }

    private EffectiveCapabilityProfile resolveCapabilities(
        SpecialistDefinition<?, ?> definition,
        PipelineContext preflight,
        ai.fabric.execution.context.TrustedExecutionContext trustedContext
    ) {
        return specialistCapabilityResolver.resolve(
            definition,
            preflight,
            trustedContext
        );
    }

    private ActionProposalCandidate extractProposalCandidate(
        OrchestrationResult result
    ) {
        if (!isProposalEnvelope(result)) {
            return null;
        }
        List<ActionProposalCandidate> candidates = new ArrayList<>();
        collectProposalCandidates(
            result,
            candidates,
            java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>()
            )
        );
        if (candidates.size() > 1) {
            throw new ActionProposalValidationException(
                "MULTIPLE_ACTION_PROPOSALS",
                "A specialist execution may create only one write proposal."
            );
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private boolean isProposalEnvelope(OrchestrationResult result) {
        if (result == null || result.getType() == null) {
            return false;
        }
        boolean supportedTopLevel =
            result.getType() == OrchestrationResultType.CONFIRMATION_REQUIRED
                || (result.getType() == OrchestrationResultType.COMPOUND_HANDLED
                    && result.isSuccess());
        return supportedTopLevel && !containsHardFailure(
            result.getChildren(),
            java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>()
            )
        );
    }

    private boolean containsHardFailure(
        List<OrchestrationResult> results,
        Set<OrchestrationResult> visited
    ) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        for (OrchestrationResult result : results) {
            if (result == null || !visited.add(result)) {
                continue;
            }
            OrchestrationResultType type = result.getType();
            boolean pending = type == OrchestrationResultType.CONFIRMATION_REQUIRED
                || type == OrchestrationResultType.CLARIFICATION_REQUIRED;
            if (type == OrchestrationResultType.ERROR
                || type == OrchestrationResultType.ACTION_DENIED
                || (!result.isSuccess() && !pending)) {
                return true;
            }
            if (containsHardFailure(result.getChildren(), visited)) {
                return true;
            }
        }
        return false;
    }

    private void collectProposalCandidates(
        OrchestrationResult result,
        List<ActionProposalCandidate> candidates,
        Set<OrchestrationResult> visited
    ) {
        if (result == null || !visited.add(result)) {
            return;
        }
        if (result.getActionProposalCandidate() != null) {
            if (result.getType()
                    != OrchestrationResultType.CONFIRMATION_REQUIRED
                || result.isSuccess()) {
                throw new ActionProposalValidationException(
                    "ACTION_PROPOSAL_ENVELOPE_INVALID",
                    "A write proposal must be emitted as a pending confirmation."
                );
            }
            candidates.add(result.getActionProposalCandidate());
        }
        if (result.getChildren() != null) {
            for (OrchestrationResult child : result.getChildren()) {
                collectProposalCandidates(child, candidates, visited);
            }
        }
    }

    private OrchestrationContext bindContext(
        OrchestrationContext context,
        SpecialistDefinition<?, ?> definition,
        ConversationBinding binding,
        TrustedExecutionContext trustedContext
    ) {
        OrchestrationContext.OrchestrationContextBuilder builder = context.toBuilder()
            .userId(null)
            .sessionId(null)
            .userId(trustedIdentifier(trustedContext))
            .mode(definition.executionProfile().mode())
            .metadata(bindTrustedMetadata(context, trustedContext))
            .specialistInstructions(definition.instructions().render());
        if (binding != null) {
            builder.userId(binding.userId())
                .conversationId(binding.conversationId());
            SpecialistInteractionCapability capability = definition
                .inputAdapter()
                .interactionCapability();
            if (capability
                    == SpecialistInteractionCapability.DIALOGUE_CAPABLE
                && binding.approvedSnapshotToken() == null) {
                throw new ContractValidationException(
                    "DIALOGUE_OWNER_GATEWAY_REQUIRED",
                    "Dialogue-capable conversation execution requires the interactive gateway."
                );
            }
            if (binding.approvedSnapshotToken() != null) {
                if (capability
                    != SpecialistInteractionCapability.DIALOGUE_CAPABLE) {
                    throw new ContractValidationException(
                        "DIALOGUE_OWNER_INELIGIBLE",
                        "The specialist is not eligible to own dialogue."
                    );
                }
                if (conversationSnapshotRegistry == null) {
                    throw new ContractValidationException(
                        "CONVERSATION_SNAPSHOT_UNAVAILABLE",
                        "Approved conversation snapshot support is unavailable."
                    );
                }
                ApprovedConversationSnapshot snapshot;
                try {
                    snapshot = conversationSnapshotRegistry.consume(binding);
                } catch (IllegalArgumentException ex) {
                    throw new ContractValidationException(
                        "CONVERSATION_SNAPSHOT_INVALID",
                        "The approved conversation snapshot is invalid or expired."
                    );
                }
                if (!definition.id().toString().equals(
                    snapshot.dialogueOwnerSpecialist()
                )) {
                    throw new ContractValidationException(
                        "DIALOGUE_OWNER_MISMATCH",
                        "The approved dialogue owner does not match the specialist."
                    );
                }
                builder.approvedConversationSnapshot(snapshot);
            }
        }
        return builder.build();
    }

    private Map<String, Object> bindTrustedMetadata(
        OrchestrationContext context,
        TrustedExecutionContext trustedContext
    ) {
        Map<String, Object> metadata = context.getMetadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(context.getMetadata());
        VERIFIED_AUTH_METADATA_KEYS.forEach(metadata::remove);

        AIAccessSubjectContext trustedAuth =
            OrchestrationAuthContextResolver.from(trustedContext);
        putVerifiedMetadata(
            metadata,
            OrchestrationContextMetadataKeys.SUBJECT_ID,
            trustedAuth.getSubjectId()
        );
        putVerifiedMetadata(
            metadata,
            OrchestrationContextMetadataKeys.SUBJECT_TYPE,
            trustedAuth.getSubjectType()
        );
        putVerifiedMetadata(
            metadata,
            OrchestrationContextMetadataKeys.AUTH_MODE,
            trustedAuth.getAuthMode()
        );
        putVerifiedMetadata(
            metadata,
            OrchestrationContextMetadataKeys.CALLER_TYPE,
            trustedAuth.getCallerType()
        );
        putVerifiedMetadata(
            metadata,
            OrchestrationContextMetadataKeys.AUTH_ISSUER,
            trustedAuth.getIssuer()
        );
        putVerifiedMetadata(
            metadata,
            OrchestrationContextMetadataKeys.AUTH_AUDIENCES,
            trustedAuth.getAudiences()
        );
        putVerifiedMetadata(
            metadata,
            OrchestrationContextMetadataKeys.AUTH_EXPIRES_AT,
            trustedAuth.getExpiresAt()
        );
        putVerifiedMetadata(
            metadata,
            OrchestrationContextMetadataKeys.DEPLOYMENT_ID,
            trustedAuth.getDeploymentId()
        );
        putVerifiedMetadata(
            metadata,
            OrchestrationContextMetadataKeys.CUSTOMER_ID,
            trustedAuth.getCustomerId()
        );
        putVerifiedMetadata(
            metadata,
            OrchestrationContextMetadataKeys.TENANT_ID,
            trustedAuth.getTenantId()
        );
        putVerifiedMetadata(
            metadata,
            OrchestrationContextMetadataKeys.GRANTED_SCOPES,
            trustedAuth.getGrantedScopes()
        );
        return metadata;
    }

    private void putVerifiedMetadata(
        Map<String, Object> metadata,
        String key,
        Object value
    ) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private String trustedIdentifier(TrustedExecutionContext trustedContext) {
        var subject = trustedContext.subject();
        return subject != null
            ? subject.subjectId()
            : trustedContext.initiator().principalId();
    }

    private <O> void recordValidatedConversationTurn(
        String invocationId,
        ConversationBinding binding,
        String conversationInput,
        O output,
        OrchestrationResult orchestrationResult,
        SpecialistOutputAdapter<O> outputAdapter,
        SpecialistDefinition<?, ?> definition,
        EffectiveCapabilityProfile effective,
        OrchestrationContext orchestrationContext
    ) {
        String assistantOutput = outputAdapter.conversationOutput(
            output,
            orchestrationResult
        );
        if (assistantOutput == null || assistantOutput.isBlank()) {
            throw new ConversationRecordingException(
                "Validated specialist output did not provide conversation text."
            );
        }
        try {
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("_specialist", definition.id().toString());
            metadata.put("_validated", true);
            metadata.put(
                "_effectiveProfileHash",
                effective.profileHash()
            );
            metadata.put("_executionInvocationId", invocationId);
            ApprovedConversationSnapshot snapshot =
                orchestrationContext != null
                    ? orchestrationContext.getApprovedConversationSnapshot()
                    : null;
            if (snapshot != null) {
                metadata.put("_dialogueOwner", true);
                metadata.put(
                    "_interactionTurnId",
                    snapshot.interactionTurnId()
                );
                metadata.put(
                    "_conversationSnapshotRevision",
                    snapshot.revision()
                );
            }
            conversationRecorder.record(
                binding,
                conversationInput,
                assistantOutput,
                Map.copyOf(metadata)
            );
        } catch (Exception ex) {
            throw new ConversationRecordingException(
                "The validated conversation turn could not be persisted.",
                ex
            );
        }
    }

    private void addDialogueDiagnostics(
        Map<String, Object> diagnostics,
        OrchestrationContext orchestrationContext
    ) {
        ApprovedConversationSnapshot snapshot =
            orchestrationContext != null
                ? orchestrationContext.getApprovedConversationSnapshot()
                : null;
        if (snapshot == null) {
            return;
        }
        diagnostics.put("interactiveTurn", true);
        diagnostics.put("interactionTurnId", snapshot.interactionTurnId());
        diagnostics.put("dialogueOwner", true);
        diagnostics.put(
            "dialogueOwnerSpecialist",
            snapshot.dialogueOwnerSpecialist()
        );
        diagnostics.put(
            "conversationSnapshotRevision",
            snapshot.revision()
        );
        diagnostics.put(
            "conversationSnapshotMessageCount",
            snapshot.historyMessages().size()
        );
        diagnostics.put(
            "conversationSnapshotTurnCount",
            snapshot.sourceTurnCount()
        );
    }

    @SuppressWarnings("unchecked")
    private <I, O> SpecialistDefinition<I, O> definition(AIExecutionRequest<I> request) {
        return (SpecialistDefinition<I, O>) specialistRegistry.require(
            request.specialistId()
        );
    }

    private AIExecutionResult pipelineFailure(
        String invocationId,
        SpecialistDefinition<?, ?> definition,
        OrchestrationResult result,
        Instant startedAt,
        Map<String, Object> diagnostics
    ) {
        String reason = result != null && result.getErrorCode() != null
            ? result.getErrorCode()
            : result != null && result.getType() != null
                ? result.getType().name()
                : "PIPELINE_FAILED";
        String message = result != null && result.getMessage() != null
            ? result.getMessage()
            : "AI orchestration failed.";
        AIExecutionStatus status =
            result != null && result.getType() == OrchestrationResultType.ACTION_DENIED
                ? AIExecutionStatus.DENIED
                : AIExecutionStatus.FAILED;
        return failure(
            invocationId,
            definition.id(),
            status,
            reason,
            message,
            retryable(reason),
            startedAt,
            diagnostics
        );
    }

    private Map<String, Object> diagnostics(
        SpecialistDefinition<?, ?> definition,
        EffectiveCapabilityProfile effective,
        OrchestrationResult result,
        int evidenceCount
    ) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("specialist", definition.id().toString());
        specialistRegistry.findRegistered(definition.id()).ifPresent(
            registered -> {
                diagnostics.put(
                    "specialistSource",
                    registered.source().name()
                );
                diagnostics.put(
                    "specialistContentHash",
                    registered.contentHash()
                );
            }
        );
        diagnostics.put("mode", effective.mode());
        diagnostics.put("strategy", definition.executionProfile().strategy().name());
        diagnostics.put("effectiveProfileHash", effective.profileHash());
        diagnostics.put("evidenceCount", evidenceCount);
        OrchestrationIntentPolicy intentPolicy =
            definition.outputAdapter().orchestrationIntentPolicy();
        if (intentPolicy != OrchestrationIntentPolicy.MODEL_DIRECTED) {
            diagnostics.put("orchestrationIntentPolicy", intentPolicy.name());
        }
        if (result != null && result.getType() != null) {
            diagnostics.put("orchestrationResultType", result.getType().name());
        }
        if (result != null
            && result.getMetadata() != null
            && result.getMetadata().get("intentPolicy") instanceof Map<?, ?>
                adjustment) {
            diagnostics.put(
                "intentPolicyAdjustment",
                Map.copyOf(adjustment)
            );
        }
        return Map.copyOf(diagnostics);
    }

    private <O> AIExecutionResult<O> failure(
        String invocationId,
        ai.fabric.execution.specialist.SpecialistId specialistId,
        AIExecutionStatus status,
        String reason,
        String message,
        boolean retryable,
        Instant startedAt,
        Map<String, Object> diagnostics
    ) {
        ai.fabric.execution.specialist.SpecialistId safeId = specialistId != null
            ? specialistId
            : ai.fabric.execution.specialist.SpecialistId.of("unknown", "unknown");
        return new AIExecutionResult<>(
            invocationId,
            safeId,
            status,
            null,
            List.of(),
            diagnostics,
            new AIExecutionFailure(reason, safeMessage(message), retryable),
            startedAt,
            clock.instant()
        );
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "AI execution failed." : message;
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private boolean retryable(String reason) {
        String normalized = reason != null ? reason.toUpperCase(Locale.ROOT) : "";
        return normalized.contains("PROVIDER")
            || normalized.contains("TIMEOUT")
            || normalized.contains("UNAVAILABLE");
    }

    private Instant requestedDeadline(
        AIExecutionRequest<?> request,
        SpecialistDefinition<?, ?> definition
    ) {
        Instant specialistDeadline = definition != null
            ? clock.instant().plus(definition.limits().maxDuration())
            : null;
        if (request.deadline() == null) {
            return specialistDeadline;
        }
        if (specialistDeadline == null || request.deadline().isBefore(specialistDeadline)) {
            return request.deadline();
        }
        return specialistDeadline;
    }

    private Optional<EphemeralExecutionStore.Entry> findEntry(
        String invocationId,
        TrustedExecutionContext trustedExecutionContext
    ) {
        if (invocationId == null || invocationId.isBlank()) {
            return Optional.empty();
        }
        return executionStore.find(
            invocationId.trim(),
            trustedExecutionContext
        );
    }

    @SuppressWarnings("unchecked")
    private AIExecutionRequest<Object> castRequest(AIExecutionRequest<?> request) {
        return (AIExecutionRequest<Object>) request;
    }

    private String invocationId() {
        return "exec-" + UUID.randomUUID();
    }

    private record SubmissionFingerprint(
        String specialistId,
        Object input,
        ConversationIdentity conversation
    ) {}

    private record ConversationIdentity(
        String userId,
        String conversationId
    ) {}

    private record ResumeConstraints(
        String specialistContentHash,
        String effectiveProfileHash,
        int nextRequestNumber
    ) {}

    private static final class ContractValidationException extends RuntimeException {
        private final String reason;

        private ContractValidationException(String reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    private static final class OutputProjectionException extends RuntimeException {
        private OutputProjectionException(String message) {
            super(message);
        }
    }

    private static final class ConversationRecordingException
        extends RuntimeException {

        private ConversationRecordingException(String message) {
            super(message);
        }

        private ConversationRecordingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
