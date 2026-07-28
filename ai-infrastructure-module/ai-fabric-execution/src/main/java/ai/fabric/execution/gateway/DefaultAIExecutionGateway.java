package ai.fabric.execution.gateway;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.DefaultSpecialistAuthorityResolver.AuthorityDeniedException;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistOutputMode;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.capability.CapabilityResolutionRequest;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityPolicySupport;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
public final class DefaultAIExecutionGateway implements AIExecutionGateway {

    private static final Logger log =
        LoggerFactory.getLogger(DefaultAIExecutionGateway.class);

    private final SpecialistRegistry specialistRegistry;
    private final Pipeline pipeline;
    private final OrchestrationPolicyResolutionStep policyResolutionStep;
    private final EffectiveCapabilitiesResolver capabilitiesResolver;
    private final AIActionRegistry actionRegistry;
    private final ExecutionCapabilityInventory capabilityInventory;
    private final SpecialistAuthorityResolver authorityResolver;
    private final OrchestrationEvidenceProjector evidenceProjector;
    private final SpecialistOutputFinalizer outputFinalizer;
    private final AIExecutionConversationRecorder conversationRecorder;
    private final AsyncTaskExecutor taskExecutor;
    private final Clock clock;
    private final EphemeralExecutionStore executionStore;

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
        this.specialistRegistry = java.util.Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        this.pipeline = java.util.Objects.requireNonNull(pipeline, "pipeline is required");
        this.policyResolutionStep = java.util.Objects.requireNonNull(
            policyResolutionStep,
            "policyResolutionStep is required"
        );
        this.capabilitiesResolver = java.util.Objects.requireNonNull(
            capabilitiesResolver,
            "capabilitiesResolver is required"
        );
        this.actionRegistry = java.util.Objects.requireNonNull(
            actionRegistry,
            "actionRegistry is required"
        );
        this.capabilityInventory = java.util.Objects.requireNonNull(
            capabilityInventory,
            "capabilityInventory is required"
        );
        this.authorityResolver = java.util.Objects.requireNonNull(
            authorityResolver,
            "authorityResolver is required"
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
        this.taskExecutor = java.util.Objects.requireNonNull(
            taskExecutor,
            "taskExecutor is required"
        );
        this.clock = java.util.Objects.requireNonNull(clock, "clock is required");
        this.executionStore = new EphemeralExecutionStore(clock, resultTtl);
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
        SpecialistDefinition<?, ?> definition;
        try {
            definition = specialistRegistry.require(request.specialistId());
        } catch (Exception ex) {
            EphemeralExecutionStore.Entry rejected = executionStore.create(
                invocationId,
                null,
                request.deadline(),
                ExecutionHandleStatus.REJECTED,
                "SPECIALIST_NOT_FOUND"
            );
            return executionStore.snapshot(rejected).handle();
        }
        Instant deadline = requestedDeadline(request, definition);

        if (request.idempotencyKey() != null
            && executionStore.invocationForIdempotencyKey(request.idempotencyKey()).isPresent()) {
            EphemeralExecutionStore.Entry rejected = executionStore.create(
                invocationId,
                null,
                deadline,
                ExecutionHandleStatus.REJECTED,
                "DUPLICATE_IDEMPOTENCY_KEY"
            );
            return executionStore.snapshot(rejected).handle();
        }

        EphemeralExecutionStore.Entry entry;
        try {
            entry = executionStore.create(
                invocationId,
                request.idempotencyKey(),
                deadline,
                ExecutionHandleStatus.QUEUED,
                null
            );
        } catch (EphemeralExecutionStore.DuplicateIdempotencyKeyException ex) {
            EphemeralExecutionStore.Entry rejected = executionStore.create(
                invocationId(),
                null,
                deadline,
                ExecutionHandleStatus.REJECTED,
                "DUPLICATE_IDEMPOTENCY_KEY"
            );
            return executionStore.snapshot(rejected).handle();
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

    @Override
    public Optional<ExecutionSnapshot> find(String invocationId) {
        if (invocationId == null || invocationId.isBlank()) {
            return Optional.empty();
        }
        return executionStore.find(invocationId.trim()).map(executionStore::snapshot);
    }

    @Override
    public boolean cancel(String invocationId) {
        return findEntry(invocationId).map(executionStore::cancel).orElse(false);
    }

    private <I, O> AIExecutionResult<O> executeInternal(
        String invocationId,
        AIExecutionRequest<I> request
    ) {
        Instant startedAt = clock.instant();
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
            String applicationInput;
            OrchestrationContext orchestrationContext;
            try {
                if (!inputAdapter.inputType().isInstance(request.input())) {
                    throw new IllegalArgumentException(
                        "Input must be " + inputAdapter.inputType().getName()
                    );
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
                request.conversationBinding()
            );
            ConversationPersistencePolicy persistence =
                request.conversationBinding() == null
                    ? ConversationPersistencePolicy.NEVER
                    : ConversationPersistencePolicy.READ_ONLY;
            String conversationInput = null;
            if (request.conversationBinding() != null) {
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

            SpecialistAuthority authority = authorityResolver.resolve(
                definition,
                request.trustedExecutionContext()
            );
            EffectiveCapabilityProfile effective = resolveCapabilities(
                definition,
                preflight,
                authority
            );
            validateStrategy(definition, preflight);

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
                conversationInput
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

            if (request.conversationBinding() != null) {
                recordValidatedConversationTurn(
                    request.conversationBinding(),
                    conversationInput,
                    output,
                    orchestrationResult,
                    outputAdapter,
                    definition,
                    effective
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
        } catch (CapabilityResolutionException ex) {
            return failure(
                invocationId,
                definition.id(),
                AIExecutionStatus.DENIED,
                ex.reason,
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

    private EffectiveCapabilityProfile resolveCapabilities(
        SpecialistDefinition<?, ?> definition,
        PipelineContext preflight,
        SpecialistAuthority authority
    ) {
        RequestedCapabilityProfile requested =
            definition.executionProfile().requestedCapabilities();

        if (!authority.allowedActions().containsAll(requested.visibleActions())) {
            throw new CapabilityResolutionException(
                "ACTION_AUTHORITY_INTERSECTION_FAILED",
                "The trusted caller is not authorized for every requested action."
            );
        }
        if (requested.retrievalEnabled()) {
            if (!authority.allowedVectorSpaces()
                .containsAll(requested.requestedVectorSpaces())) {
                throw new CapabilityResolutionException(
                    "VECTOR_AUTHORITY_INTERSECTION_FAILED",
                    "The trusted caller is not authorized for every requested vector space."
                );
            }
            if (!normalize(capabilityInventory.registeredVectorSpaces())
                .containsAll(requested.requestedVectorSpaces())) {
                throw new CapabilityResolutionException(
                    "VECTOR_SPACE_NOT_REGISTERED",
                    "A requested vector space is not registered in this deployment."
                );
            }
        }

        Set<String> authorizedRegisteredSpaces =
            new LinkedHashSet<>(normalize(capabilityInventory.registeredVectorSpaces()));
        authorizedRegisteredSpaces.retainAll(authority.allowedVectorSpaces());
        EffectiveCapabilityProfile effective = capabilitiesResolver.resolve(
            new CapabilityResolutionRequest(
                requested,
                preflight.getOrchestrationPolicy(),
                actionRegistry.getAllMetadata(),
                authorizedRegisteredSpaces,
                authority.allowedActions(),
                capabilityInventory.deploymentAllowedActions(),
                null
            )
        );
        if (!effective.visibleActions().containsAll(requested.visibleActions())
            || !effective.executableReadActions()
                .containsAll(requested.requestableReadActions())
            || !effective.proposableWriteActions()
                .containsAll(requested.proposableWriteActions())
            || (requested.retrievalEnabled()
                && !effective.effectiveVectorSpaces()
                    .containsAll(requested.requestedVectorSpaces()))) {
            throw new CapabilityResolutionException(
                "EFFECTIVE_CAPABILITY_INTERSECTION_FAILED",
                "Mode, deployment, or authority policy denied a requested capability."
            );
        }
        return effective;
    }

    private void validateStrategy(
        SpecialistDefinition<?, ?> definition,
        PipelineContext preflight
    ) {
        if (definition.executionProfile().strategy() != ExecutionStrategy.BOUNDED_ITERATIVE) {
            return;
        }
        var readPolicy = preflight.getOrchestrationPolicy().readActionResolutionPolicy();
        if (readPolicy == null
            || !readPolicy.enabled()
            || readPolicy.planningMode()
                != ai.fabric.config.OrchestrationProperties
                    .ReadActionResolutionPlanningMode.ITERATIVE) {
            throw new CapabilityResolutionException(
                "ITERATIVE_MODE_REQUIRED",
                "BOUNDED_ITERATIVE requires an iterative read-action Mode."
            );
        }
    }

    private OrchestrationContext bindContext(
        OrchestrationContext context,
        SpecialistDefinition<?, ?> definition,
        ConversationBinding binding
    ) {
        OrchestrationContext.OrchestrationContextBuilder builder = context.toBuilder()
            .mode(definition.executionProfile().mode());
        if (binding != null) {
            builder.userId(binding.userId())
                .conversationId(binding.conversationId());
        }
        return builder.build();
    }

    private <O> void recordValidatedConversationTurn(
        ConversationBinding binding,
        String conversationInput,
        O output,
        OrchestrationResult orchestrationResult,
        SpecialistOutputAdapter<O> outputAdapter,
        SpecialistDefinition<?, ?> definition,
        EffectiveCapabilityProfile effective
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
            conversationRecorder.record(
                binding,
                conversationInput,
                assistantOutput,
                Map.of(
                    "_specialist",
                    definition.id().toString(),
                    "_validated",
                    true,
                    "_effectiveProfileHash",
                    effective.profileHash()
                )
            );
        } catch (Exception ex) {
            throw new ConversationRecordingException(
                "The validated conversation turn could not be persisted.",
                ex
            );
        }
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
        diagnostics.put("mode", effective.mode());
        diagnostics.put("strategy", definition.executionProfile().strategy().name());
        diagnostics.put("effectiveProfileHash", effective.profileHash());
        diagnostics.put("evidenceCount", evidenceCount);
        if (result != null && result.getType() != null) {
            diagnostics.put("orchestrationResultType", result.getType().name());
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

    private Optional<EphemeralExecutionStore.Entry> findEntry(String invocationId) {
        if (invocationId == null || invocationId.isBlank()) {
            return Optional.empty();
        }
        return executionStore.find(invocationId.trim());
    }

    private Set<String> normalize(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        values.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .forEach(normalized::add);
        return Set.copyOf(normalized);
    }

    @SuppressWarnings("unchecked")
    private AIExecutionRequest<Object> castRequest(AIExecutionRequest<?> request) {
        return (AIExecutionRequest<Object>) request;
    }

    private String invocationId() {
        return "exec-" + UUID.randomUUID();
    }

    private static final class CapabilityResolutionException extends RuntimeException {
        private final String reason;

        private CapabilityResolutionException(String reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

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
