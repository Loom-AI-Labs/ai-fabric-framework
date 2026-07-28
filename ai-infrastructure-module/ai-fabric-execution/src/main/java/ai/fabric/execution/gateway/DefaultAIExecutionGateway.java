package ai.fabric.execution.gateway;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalPersistenceException;
import ai.fabric.execution.action.ActionProposalValidationException;
import ai.fabric.execution.action.ActionProposalView;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.DefaultSpecialistAuthorityResolver.AuthorityDeniedException;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistOutputMode;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.invocation.ActionProposalCandidate;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityPolicySupport;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
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
public final class DefaultAIExecutionGateway implements AIExecutionGateway {

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
            resultTtl
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

            EffectiveCapabilityProfile effective = resolveCapabilities(
                definition,
                preflight,
                request.trustedExecutionContext()
            );

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
                OrchestrationRequestPurpose.SPECIALIST
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
        ConversationBinding binding
    ) {
        OrchestrationContext.OrchestrationContextBuilder builder = context.toBuilder()
            .mode(definition.executionProfile().mode())
            .specialistInstructions(definition.instructions().render());
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

    @SuppressWarnings("unchecked")
    private AIExecutionRequest<Object> castRequest(AIExecutionRequest<?> request) {
        return (AIExecutionRequest<Object>) request;
    }

    private String invocationId() {
        return "exec-" + UUID.randomUUID();
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
