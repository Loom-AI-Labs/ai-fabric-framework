package ai.fabric.execution.action;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.SpecialistCapabilityResolutionException;
import ai.fabric.execution.gateway.SpecialistCapabilityResolver;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.invocation.ActionConfirmationState;
import ai.fabric.intent.action.invocation.ActionProposalCandidate;
import ai.fabric.intent.action.invocation.GovernedActionInvocation;
import ai.fabric.intent.action.invocation.GovernedActionInvocationOutcome;
import ai.fabric.intent.action.invocation.GovernedActionInvocationService;
import ai.fabric.intent.action.invocation.GovernedActionInvocationStatus;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityPolicySupport;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable proposal, confirmation, execution, and reconciliation boundary.
 */
public final class ActionProposalCoordinator {

    private final ActionProposalReceiptRepository repository;
    private final ActionProposalSecurity security;
    private final ActionProposalValidator validator;
    private final ActionOutcomeProjectorRegistry projectors;
    private final SpecialistRegistry specialistRegistry;
    private final AIActionRegistry actionRegistry;
    private final OrchestrationPolicyResolutionStep policyResolutionStep;
    private final SpecialistCapabilityResolver capabilityResolver;
    private final GovernedActionInvocationService invocationService;
    private final ActionProposalMetrics metrics;
    private final Clock clock;
    private final Duration receiptTtl;

    public ActionProposalCoordinator(
        ActionProposalReceiptRepository repository,
        ActionProposalSecurity security,
        ActionProposalValidator validator,
        ActionOutcomeProjectorRegistry projectors,
        SpecialistRegistry specialistRegistry,
        AIActionRegistry actionRegistry,
        OrchestrationPolicyResolutionStep policyResolutionStep,
        SpecialistCapabilityResolver capabilityResolver,
        GovernedActionInvocationService invocationService,
        ActionProposalMetrics metrics,
        Clock clock,
        Duration receiptTtl
    ) {
        this.repository = Objects.requireNonNull(
            repository,
            "repository is required"
        );
        this.security = Objects.requireNonNull(security, "security is required");
        this.validator = Objects.requireNonNull(
            validator,
            "validator is required"
        );
        this.projectors = Objects.requireNonNull(
            projectors,
            "projectors is required"
        );
        this.specialistRegistry = Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        this.actionRegistry = Objects.requireNonNull(
            actionRegistry,
            "actionRegistry is required"
        );
        this.policyResolutionStep = Objects.requireNonNull(
            policyResolutionStep,
            "policyResolutionStep is required"
        );
        this.capabilityResolver = Objects.requireNonNull(
            capabilityResolver,
            "capabilityResolver is required"
        );
        this.invocationService = Objects.requireNonNull(
            invocationService,
            "invocationService is required"
        );
        this.metrics = metrics != null ? metrics : ActionProposalMetrics.noop();
        this.clock = Objects.requireNonNull(clock, "clock is required");
        if (receiptTtl == null
            || receiptTtl.isZero()
            || receiptTtl.isNegative()) {
            throw new IllegalArgumentException("receiptTtl must be positive");
        }
        this.receiptTtl = receiptTtl;
    }

    public ActionProposalView propose(
        String invocationId,
        SpecialistDefinition<?, ?> definition,
        ActionProposalCandidate candidate,
        TrustedExecutionContext trustedContext,
        EffectiveCapabilityProfile effectiveProfile,
        String requestedIdempotencyKey,
        List<AIEvidenceReference> evidence
    ) {
        Objects.requireNonNull(definition, "definition is required");
        Objects.requireNonNull(candidate, "candidate is required");
        Objects.requireNonNull(trustedContext, "trustedContext is required");
        Objects.requireNonNull(effectiveProfile, "effectiveProfile is required");
        SpecialistDefinition<?, ?> registeredDefinition = specialistRegistry
            .find(definition.id())
            .orElseThrow(() -> new ActionProposalValidationException(
                "SPECIALIST_VERSION_NOT_REGISTERED",
                "The specialist version is not registered."
            ));
        if (!registeredDefinition.executionProfile().writeEnabled()) {
            throw new ActionProposalValidationException(
                "SPECIALIST_WRITE_DISABLED",
                "This specialist is not allowed to propose write actions."
            );
        }
        ResolvedCapabilities currentCapabilities = resolveCapabilities(
            registeredDefinition,
            trustedContext,
            "Validate specialist action proposal."
        );
        if (!currentCapabilities.effectiveProfile().profileHash()
            .equals(effectiveProfile.profileHash())) {
            throw new ActionProposalValidationException(
                "EFFECTIVE_PROFILE_CHANGED",
                "The effective specialist profile changed before the proposal could be persisted."
            );
        }
        EffectiveCapabilityProfile currentProfile =
            currentCapabilities.effectiveProfile();

        AIActionMetaData metadata = actionRegistry
            .findMetadata(candidate.actionName())
            .orElse(null);
        validator.validateAction(
            metadata,
            candidate.actionName(),
            currentProfile
        );
        validator.validateParameters(metadata, candidate.parameters());
        projectors.require(candidate.actionName());

        GovernedActionInvocationOutcome preflight = invocationService.invoke(
            new GovernedActionInvocation(
                candidate.actionName(),
                candidate.parameters(),
                trustedProposalActionContext(
                    candidate,
                    currentCapabilities
                ),
                trustedContext,
                currentProfile,
                ActionConfirmationState.NOT_CONFIRMED,
                evidence
            )
        );
        if (preflight.status()
            != GovernedActionInvocationStatus.CONFIRMATION_REQUIRED) {
            throw new ActionProposalValidationException(
                failureReason(preflight, "ACTION_PROPOSAL_PREFLIGHT_DENIED"),
                failureMessage(
                    preflight,
                    "The action proposal is not allowed for the current account."
                )
            );
        }

        String confirmationMessage = confirmationMessage(preflight);
        String requestedKey = normalizeIdempotencyKey(
            requestedIdempotencyKey,
            invocationId
        );
        String idempotencyKey = security.idempotencyFingerprint(
            trustedContext,
            definition.id(),
            requestedKey
        );
        String parameterHash = security.canonicalHash(candidate.parameters());
        ActionProposalReceipt existing;
        try {
            existing = repository
                .findByIdempotencyKey(idempotencyKey)
                .orElse(null);
        } catch (RuntimeException ex) {
            throw proposalPersistenceFailure(ex);
        }
        if (existing != null) {
            verifyDuplicate(
                existing,
                registeredDefinition,
                candidate,
                trustedContext,
                currentProfile,
                parameterHash
            );
            metrics.record(
                "proposal_replayed",
                existing.actionName(),
                existing.status()
            );
            return existing.publicView();
        }

        Instant now = clock.instant();
        String receiptId = "action-receipt-" + UUID.randomUUID();
        ActionProposalReceipt receipt = new ActionProposalReceipt(
            receiptId,
            requireText(invocationId, "invocationId"),
            registeredDefinition.id(),
            currentProfile.profileHash(),
            security.principalFingerprint(trustedContext),
            trustedSubjectType(trustedContext),
            security.subjectFingerprint(trustedContext),
            security.tenantFingerprint(trustedContext),
            security.deploymentFingerprint(trustedContext),
            candidate.actionName(),
            security.protect(candidate.parameters(), parameterBinding(receiptId)),
            parameterHash,
            validator.schemaHash(metadata, security),
            confirmationMessage,
            idempotencyKey,
            security.evidenceHashes(evidence),
            ActionProposalReceiptStatus.PROPOSED,
            now,
            now.plus(receiptTtl),
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            0
        );
        try {
            repository.create(receipt);
        } catch (ActionProposalReceiptRepository.DuplicateReceiptException ex) {
            ActionProposalReceipt raced;
            try {
                raced = repository
                    .findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> proposalPersistenceFailure(ex));
            } catch (ActionProposalPersistenceException persistenceFailure) {
                throw persistenceFailure;
            } catch (RuntimeException persistenceFailure) {
                throw proposalPersistenceFailure(persistenceFailure);
            }
            verifyDuplicate(
                raced,
                registeredDefinition,
                candidate,
                trustedContext,
                currentProfile,
                parameterHash
            );
            metrics.record(
                "proposal_replayed",
                raced.actionName(),
                raced.status()
            );
            return raced.publicView();
        } catch (RuntimeException ex) {
            throw proposalPersistenceFailure(ex);
        }
        metrics.record("proposed", receipt.actionName(), receipt.status());
        return receipt.publicView();
    }

    public ActionProposalDecisionResult decide(
        ActionProposalDecisionRequest request,
        TrustedExecutionContext trustedContext
    ) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(trustedContext, "trustedContext is required");
        try {
            ActionProposalReceipt receipt = loadReceipt(request.receiptId());
            if (receipt == null || !identityMatches(receipt, trustedContext)) {
                return unavailable(request.receiptId());
            }
            if (request.decision() == ActionProposalDecision.REJECT) {
                return reject(receipt);
            }
            return confirm(receipt, trustedContext);
        } catch (ActionProposalPersistenceException ex) {
            return storeUnavailable(request.receiptId());
        }
    }

    public ActionProposalDecisionResult reconcile(
        ActionProposalReconciliation reconciliation,
        TrustedExecutionContext trustedContext
    ) {
        Objects.requireNonNull(reconciliation, "reconciliation is required");
        Objects.requireNonNull(trustedContext, "trustedContext is required");
        ActionProposalReceipt receipt;
        try {
            receipt = loadReceipt(reconciliation.receiptId());
        } catch (ActionProposalPersistenceException ex) {
            return storeUnavailable(reconciliation.receiptId());
        }
        if (receipt == null || !identityMatches(receipt, trustedContext)) {
            return unavailable(reconciliation.receiptId());
        }
        if (receipt.status() != ActionProposalReceiptStatus.OUTCOME_UNKNOWN) {
            return terminalResult(receipt);
        }
        try {
            CurrentAction current = resolveCurrent(receipt, trustedContext);
            boolean expectedSuccess =
                reconciliation.finalStatus()
                    == ActionProposalReceiptStatus.SUCCEEDED;
            if (reconciliation.authoritativeResult().isSuccess()
                != expectedSuccess) {
                return failure(
                    receipt,
                    "RECONCILIATION_RESULT_MISMATCH",
                    "The authoritative result does not match the reconciliation status.",
                    false
                );
            }
            ActionOutcomeView outcome = current.projector().project(
                reconciliation.authoritativeResult()
            );
            validateProjectedOutcome(receipt, outcome);
            ActionProposalReceipt reconciled = receipt.reconciled(
                reconciliation.finalStatus(),
                clock.instant(),
                protectOutcome(receipt, outcome),
                expectedSuccess ? null : "ACTION_FAILED"
            );
            if (!compareAndSetReceipt(receipt, reconciled)) {
                return terminalResult(reload(receipt.receiptId()));
            }
            metrics.record(
                "reconciled",
                reconciled.actionName(),
                reconciled.status()
            );
            return terminalResult(reconciled);
        } catch (ActionProposalPersistenceException ex) {
            return storeUnavailable(reconciliation.receiptId());
        } catch (RuntimeException ex) {
            return failure(
                receipt,
                "RECONCILIATION_FAILED",
                "The unknown action outcome could not be reconciled.",
                false
            );
        }
    }

    private ActionProposalDecisionResult confirm(
        ActionProposalReceipt initial,
        TrustedExecutionContext trustedContext
    ) {
        ActionProposalReceipt receipt = expireIfNeeded(initial);
        if (receipt.status().terminal()) {
            metrics.record(
                "decision_replayed",
                receipt.actionName(),
                receipt.status()
            );
            return terminalResult(receipt);
        }
        if (receipt.status() == ActionProposalReceiptStatus.EXECUTING) {
            return failure(
                receipt,
                "ACTION_EXECUTION_IN_PROGRESS",
                "The confirmed action is still executing.",
                true
            );
        }

        CurrentAction current;
        try {
            current = resolveCurrent(receipt, trustedContext);
            GovernedActionInvocationOutcome preflight = invocationService.invoke(
                invocation(current, receipt, ActionConfirmationState.NOT_CONFIRMED)
            );
            if (preflight.status()
                != GovernedActionInvocationStatus.CONFIRMATION_REQUIRED) {
                return failBeforeExecution(
                    receipt,
                    failureReason(preflight, "ACTION_CONFIRMATION_DENIED"),
                    failureMessage(
                        preflight,
                        "The action is no longer allowed for the current account."
                    )
                );
            }
        } catch (SpecialistCapabilityResolutionException ex) {
            return failBeforeExecution(receipt, ex.reason(), ex.getMessage());
        } catch (ActionProposalValidationException ex) {
            return failBeforeExecution(receipt, ex.reason(), ex.getMessage());
        } catch (RuntimeException ex) {
            return failBeforeExecution(
                receipt,
                "ACTION_CONFIRMATION_VALIDATION_FAILED",
                "The action could not be safely revalidated."
            );
        }

        if (receipt.status() == ActionProposalReceiptStatus.PROPOSED) {
            ActionProposalReceipt confirmed = receipt.confirmed(clock.instant());
            if (!compareAndSetReceipt(receipt, confirmed)) {
                return confirm(reload(receipt.receiptId()), trustedContext);
            }
            receipt = confirmed;
            metrics.record("confirmed", receipt.actionName(), receipt.status());
        }
        if (receipt.status() != ActionProposalReceiptStatus.CONFIRMED) {
            return terminalResult(receipt);
        }

        ActionProposalReceipt executing = receipt.executing(clock.instant());
        if (!compareAndSetReceipt(receipt, executing)) {
            return confirm(reload(receipt.receiptId()), trustedContext);
        }
        metrics.record("executing", executing.actionName(), executing.status());

        GovernedActionInvocationOutcome invocationOutcome;
        try {
            invocationOutcome = invocationService.invoke(
                invocation(current, executing, ActionConfirmationState.CONFIRMED)
            );
        } catch (RuntimeException ex) {
            return completeUnknown(
                executing,
                "ACTION_INVOCATION_OUTCOME_UNKNOWN"
            );
        }
        return completeExecution(executing, current, invocationOutcome);
    }

    private ActionProposalDecisionResult reject(
        ActionProposalReceipt initial
    ) {
        ActionProposalReceipt receipt = expireIfNeeded(initial);
        if (receipt.status().terminal()) {
            metrics.record(
                "decision_replayed",
                receipt.actionName(),
                receipt.status()
            );
            return terminalResult(receipt);
        }
        if (receipt.status() != ActionProposalReceiptStatus.PROPOSED) {
            return failure(
                receipt,
                "ACTION_ALREADY_CONFIRMED",
                "A confirmed action can no longer be rejected.",
                false
            );
        }
        ActionProposalReceipt rejected = receipt.rejected(clock.instant());
        if (!compareAndSetReceipt(receipt, rejected)) {
            return reject(reload(receipt.receiptId()));
        }
        metrics.record("rejected", rejected.actionName(), rejected.status());
        return terminalResult(rejected);
    }

    private ActionProposalDecisionResult completeExecution(
        ActionProposalReceipt executing,
        CurrentAction current,
        GovernedActionInvocationOutcome invocationOutcome
    ) {
        if (invocationOutcome == null
            || invocationOutcome.status()
                == GovernedActionInvocationStatus.OUTCOME_UNKNOWN) {
            return completeUnknown(executing, failureReason(
                invocationOutcome,
                "ACTION_OUTCOME_UNKNOWN"
            ));
        }

        ActionProposalReceiptStatus finalStatus;
        String failureReason = null;
        if (invocationOutcome.status()
            == GovernedActionInvocationStatus.EXECUTED) {
            finalStatus = ActionProposalReceiptStatus.SUCCEEDED;
        } else {
            finalStatus = ActionProposalReceiptStatus.FAILED;
            failureReason = failureReason(
                invocationOutcome,
                "ACTION_EXECUTION_FAILED"
            );
        }

        ActionOutcomeView outcome;
        try {
            outcome = current.projector().project(
                invocationOutcome.actionResult()
            );
            validateProjectedOutcome(executing, outcome);
        } catch (RuntimeException ex) {
            return completeUnknown(
                executing,
                "ACTION_OUTCOME_PROJECTION_FAILED"
            );
        }

        ActionProposalReceipt completed = executing.completed(
            finalStatus,
            clock.instant(),
            protectOutcome(executing, outcome),
            failureReason
        );
        try {
            if (!repository.compareAndSet(executing, completed)) {
                return completePersistenceUnknown(executing);
            }
        } catch (RuntimeException ex) {
            return completePersistenceUnknown(executing);
        }
        metrics.record("completed", completed.actionName(), completed.status());
        return terminalResult(completed);
    }

    private ActionProposalDecisionResult completeUnknown(
        ActionProposalReceipt executing,
        String reason
    ) {
        ActionOutcomeView unknown = unknownOutcome(executing.actionName());
        ActionProposalReceipt completed = executing.completed(
            ActionProposalReceiptStatus.OUTCOME_UNKNOWN,
            clock.instant(),
            protectOutcome(executing, unknown),
            reason
        );
        try {
            if (!repository.compareAndSet(executing, completed)) {
                return completePersistenceUnknown(executing);
            }
        } catch (RuntimeException ex) {
            return completePersistenceUnknown(executing);
        }
        metrics.record(
            "outcome_unknown",
            completed.actionName(),
            completed.status()
        );
        return terminalResult(completed);
    }

    private ActionProposalDecisionResult completePersistenceUnknown(
        ActionProposalReceipt executing
    ) {
        metrics.record(
            "outcome_persistence_failed",
            executing.actionName(),
            ActionProposalReceiptStatus.OUTCOME_UNKNOWN
        );
        return new ActionProposalDecisionResult(
            executing.receiptId(),
            ActionProposalReceiptStatus.OUTCOME_UNKNOWN,
            unknownOutcome(executing.actionName()),
            new ActionProposalFailure(
                "ACTION_OUTCOME_PERSISTENCE_FAILED",
                "The action may have completed, but its outcome could not be persisted. Reconciliation is required.",
                false
            )
        );
    }

    private CurrentAction resolveCurrent(
        ActionProposalReceipt receipt,
        TrustedExecutionContext trustedContext
    ) {
        if (!identityMatches(receipt, trustedContext)) {
            throw new ActionProposalValidationException(
                "RECEIPT_IDENTITY_MISMATCH",
                "The receipt is not available for this trusted context."
            );
        }
        SpecialistDefinition<?, ?> definition = specialistRegistry
            .find(receipt.specialistId())
            .orElseThrow(() -> new ActionProposalValidationException(
                "SPECIALIST_VERSION_NOT_REGISTERED",
                "The specialist version used by this receipt is no longer registered."
            ));
        if (!definition.executionProfile().writeEnabled()) {
            throw new ActionProposalValidationException(
                "SPECIALIST_WRITE_DISABLED",
                "The specialist is no longer allowed to propose writes."
            );
        }

        ResolvedCapabilities currentCapabilities = resolveCapabilities(
            definition,
            trustedContext,
            "Revalidate confirmed specialist action."
        );
        EffectiveCapabilityProfile effective =
            currentCapabilities.effectiveProfile();
        if (!effective.profileHash().equals(receipt.effectiveProfileHash())) {
            throw new ActionProposalValidationException(
                "EFFECTIVE_PROFILE_CHANGED",
                "The effective specialist profile changed after this proposal."
            );
        }

        AIActionMetaData metadata = actionRegistry
            .findMetadata(receipt.actionName())
            .orElse(null);
        validator.validateAction(metadata, receipt.actionName(), effective);
        if (!validator.schemaHash(metadata, security)
            .equals(receipt.parameterSchemaHash())) {
            throw new ActionProposalValidationException(
                "ACTION_SCHEMA_CHANGED",
                "The action contract changed after this proposal."
            );
        }
        Map<String, Object> parameters = security.unprotect(
            receipt.protectedParameters(),
            parameterBinding(receipt.receiptId())
        );
        if (!security.canonicalHash(parameters).equals(receipt.parameterHash())) {
            throw new ActionProposalValidationException(
                "ACTION_PARAMETERS_TAMPERED",
                "The protected action parameters could not be verified."
            );
        }
        validator.validateParameters(metadata, parameters);
        ActionOutcomeProjector projector = projectors.require(
            receipt.actionName()
        );

        return new CurrentAction(
            definition,
            effective,
            parameters,
            new ActionContext(
                currentCapabilities.effectiveContext(),
                currentCapabilities.pipelineContext(),
                parameters
            ),
            projector
        );
    }

    private ActionContext trustedProposalActionContext(
        ActionProposalCandidate candidate,
        ResolvedCapabilities currentCapabilities
    ) {
        return new ActionContext(
            currentCapabilities.effectiveContext(),
            currentCapabilities.pipelineContext(),
            candidate.parameters()
        );
    }

    private ResolvedCapabilities resolveCapabilities(
        SpecialistDefinition<?, ?> definition,
        TrustedExecutionContext trustedContext,
        String modelInput
    ) {
        if (trustedContext.subject() == null) {
            throw new ActionProposalValidationException(
                "TRUSTED_SUBJECT_REQUIRED",
                "Specialist write proposals require a trusted subject."
            );
        }
        OrchestrationContext baseContext = OrchestrationContext.builder()
            .userId(trustedContext.subject().subjectId())
            .position(definition.executionProfile().mode())
            .mode(definition.executionProfile().mode())
            .build();
        OrchestrationRequest preflightRequest = new OrchestrationRequest(
            modelInput,
            baseContext,
            trustedContext,
            ConversationPersistencePolicy.NEVER,
            null,
            null,
            null,
            OrchestrationRequestPurpose.SPECIALIST
        );
        PipelineContext preflight = policyResolutionStep.process(
            PipelineContext.from(preflightRequest)
        );
        if (preflight.isShouldTerminate()) {
            throw new ActionProposalValidationException(
                "ORCHESTRATION_POLICY_DENIED",
                "Current orchestration policy denied the action."
            );
        }
        EffectiveCapabilityProfile effective = capabilityResolver.resolve(
            definition,
            preflight,
            trustedContext
        );
        OrchestrationContext effectiveContext = preflight
            .getOrchestrationContext()
            .toBuilder()
            .orchestrationPolicy(EffectiveCapabilityPolicySupport.constrain(
                preflight.getOrchestrationPolicy(),
                effective
            ))
            .effectiveCapabilityProfile(effective)
            .build();
        OrchestrationRequest actionRequest = new OrchestrationRequest(
            modelInput,
            effectiveContext,
            trustedContext,
            ConversationPersistencePolicy.NEVER,
            effective,
            null,
            null,
            OrchestrationRequestPurpose.SPECIALIST
        );
        PipelineContext actionPipeline = PipelineContext.from(actionRequest)
            .toBuilder()
            .orchestrationPolicy(effectiveContext.getOrchestrationPolicy())
            .effectiveCapabilityProfile(effective)
            .build();
        return new ResolvedCapabilities(
            effective,
            effectiveContext,
            actionPipeline
        );
    }

    private GovernedActionInvocation invocation(
        CurrentAction current,
        ActionProposalReceipt receipt,
        ActionConfirmationState confirmationState
    ) {
        TrustedExecutionContext trustedContext = current
            .actionContext()
            .pipelineContext()
            .getOrchestrationRequest()
            .trustedExecutionContext();
        return new GovernedActionInvocation(
            receipt.actionName(),
            current.parameters(),
            current.actionContext(),
            trustedContext,
            current.effectiveProfile(),
            confirmationState,
            List.of()
        );
    }

    private ActionProposalDecisionResult failBeforeExecution(
        ActionProposalReceipt receipt,
        String reason,
        String message
    ) {
        if (receipt.status() != ActionProposalReceiptStatus.PROPOSED
            && receipt.status() != ActionProposalReceiptStatus.CONFIRMED) {
            return terminalResult(receipt);
        }
        ActionProposalReceipt failed = receipt.failedBeforeExecution(
            clock.instant(),
            reason
        );
        if (!compareAndSetReceipt(receipt, failed)) {
            return terminalResult(reload(receipt.receiptId()));
        }
        metrics.record("denied", failed.actionName(), failed.status());
        return failure(failed, reason, message, false);
    }

    private ActionProposalReceipt expireIfNeeded(
        ActionProposalReceipt receipt
    ) {
        if ((receipt.status() != ActionProposalReceiptStatus.PROPOSED
                && receipt.status() != ActionProposalReceiptStatus.CONFIRMED)
            || receipt.expiresAt().isAfter(clock.instant())) {
            return receipt;
        }
        ActionProposalReceipt expired = receipt.expired(clock.instant());
        if (compareAndSetReceipt(receipt, expired)) {
            metrics.record("expired", expired.actionName(), expired.status());
            return expired;
        }
        return reload(receipt.receiptId());
    }

    private ActionProposalDecisionResult terminalResult(
        ActionProposalReceipt receipt
    ) {
        ActionOutcomeView outcome;
        try {
            outcome = readOutcome(receipt);
        } catch (ActionProposalValidationException ex) {
            return outcomeUnavailable(receipt);
        }
        ActionProposalFailure failure = switch (receipt.status()) {
            case FAILED -> new ActionProposalFailure(
                receipt.failureReason() != null
                    ? receipt.failureReason()
                    : "ACTION_FAILED",
                "The confirmed action did not complete successfully.",
                false
            );
            case OUTCOME_UNKNOWN -> new ActionProposalFailure(
                receipt.failureReason() != null
                    ? receipt.failureReason()
                    : "ACTION_OUTCOME_UNKNOWN",
                "The action outcome is unknown and requires reconciliation.",
                false
            );
            case REJECTED -> new ActionProposalFailure(
                "USER_REJECTED",
                "The action proposal was rejected.",
                false
            );
            case EXPIRED -> new ActionProposalFailure(
                "RECEIPT_EXPIRED",
                "The action proposal expired before confirmation.",
                false
            );
            default -> null;
        };
        return new ActionProposalDecisionResult(
            receipt.receiptId(),
            receipt.status(),
            outcome,
            failure
        );
    }

    private ActionProposalDecisionResult unavailable(String receiptId) {
        metrics.record("unavailable", "unknown", null);
        return new ActionProposalDecisionResult(
            receiptId,
            null,
            null,
            new ActionProposalFailure(
                "RECEIPT_NOT_AVAILABLE",
                "The action receipt was not found or is not available for this session.",
                false
            )
        );
    }

    private ActionProposalDecisionResult storeUnavailable(String receiptId) {
        metrics.record("store_unavailable", "unknown", null);
        return new ActionProposalDecisionResult(
            receiptId,
            null,
            null,
            new ActionProposalFailure(
                "RECEIPT_STORE_UNAVAILABLE",
                "The action receipt store is temporarily unavailable. No new execution was started.",
                true
            )
        );
    }

    private ActionProposalDecisionResult failure(
        ActionProposalReceipt receipt,
        String reason,
        String message,
        boolean retryable
    ) {
        ActionOutcomeView outcome;
        try {
            outcome = readOutcome(receipt);
        } catch (ActionProposalValidationException ex) {
            return outcomeUnavailable(receipt);
        }
        return new ActionProposalDecisionResult(
            receipt.receiptId(),
            receipt.status(),
            outcome,
            new ActionProposalFailure(reason, message, retryable)
        );
    }

    private ActionOutcomeView readOutcome(ActionProposalReceipt receipt) {
        if (receipt.protectedOutcome() == null) {
            if (receipt.status() == ActionProposalReceiptStatus.SUCCEEDED
                || receipt.status() == ActionProposalReceiptStatus.OUTCOME_UNKNOWN
                || (receipt.status() == ActionProposalReceiptStatus.FAILED
                    && receipt.executionStartedAt() != null)) {
                throw new ActionProposalValidationException(
                    "ACTION_OUTCOME_UNAVAILABLE",
                    "The persisted action outcome is unavailable."
                );
            }
            return null;
        }
        try {
            Map<String, Object> payload = security.unprotect(
                receipt.protectedOutcome(),
                outcomeBinding(receipt.receiptId())
            );
            Object rawData = payload.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = rawData instanceof Map<?, ?>
                ? (Map<String, Object>) rawData
                : Map.of();
            return new ActionOutcomeView(
                String.valueOf(payload.get("actionName")),
                String.valueOf(payload.get("message")),
                data
            );
        } catch (RuntimeException ex) {
            throw new ActionProposalValidationException(
                "ACTION_OUTCOME_UNAVAILABLE",
                "The persisted action outcome could not be verified."
            );
        }
    }

    private ActionProposalDecisionResult outcomeUnavailable(
        ActionProposalReceipt receipt
    ) {
        metrics.record(
            "outcome_unavailable",
            receipt.actionName(),
            ActionProposalReceiptStatus.OUTCOME_UNKNOWN
        );
        return new ActionProposalDecisionResult(
            receipt.receiptId(),
            ActionProposalReceiptStatus.OUTCOME_UNKNOWN,
            unknownOutcome(receipt.actionName()),
            new ActionProposalFailure(
                "ACTION_OUTCOME_UNAVAILABLE",
                "The persisted action outcome could not be verified. Restore the configured receipt key and reconcile before relying on this result.",
                false
            )
        );
    }

    private String protectOutcome(
        ActionProposalReceipt receipt,
        ActionOutcomeView outcome
    ) {
        return security.protect(
            Map.of(
                "actionName",
                outcome.actionName(),
                "message",
                outcome.message(),
                "data",
                outcome.data()
            ),
            outcomeBinding(receipt.receiptId())
        );
    }

    private ActionOutcomeView unknownOutcome(String actionName) {
        return new ActionOutcomeView(
            actionName,
            "The action may have completed, but its authoritative outcome is not yet known.",
            Map.of("requiresReconciliation", true)
        );
    }

    private void validateProjectedOutcome(
        ActionProposalReceipt receipt,
        ActionOutcomeView outcome
    ) {
        if (outcome == null
            || !ai.fabric.intent.action.AIActionNames.normalize(
                receipt.actionName()
            ).equals(ai.fabric.intent.action.AIActionNames.normalize(
                outcome.actionName()
            ))) {
            throw new ActionProposalValidationException(
                "ACTION_OUTCOME_PROJECTION_INVALID",
                "The action outcome projector returned an invalid result."
            );
        }
    }

    private void verifyDuplicate(
        ActionProposalReceipt existing,
        SpecialistDefinition<?, ?> definition,
        ActionProposalCandidate candidate,
        TrustedExecutionContext trustedContext,
        EffectiveCapabilityProfile effectiveProfile,
        String parameterHash
    ) {
        boolean same = existing.specialistId().equals(definition.id())
            && ai.fabric.intent.action.AIActionNames.normalize(
                existing.actionName()
            ).equals(ai.fabric.intent.action.AIActionNames.normalize(
                candidate.actionName()
            ))
            && existing.effectiveProfileHash().equals(
                effectiveProfile.profileHash()
            )
            && existing.parameterHash().equals(parameterHash)
            && identityMatches(existing, trustedContext);
        if (!same) {
            throw new ActionProposalValidationException(
                "IDEMPOTENCY_KEY_CONFLICT",
                "The idempotency key is already bound to another action proposal."
            );
        }
    }

    private boolean identityMatches(
        ActionProposalReceipt receipt,
        TrustedExecutionContext context
    ) {
        if (context == null || context.subject() == null) {
            return false;
        }
        return receipt.subjectType().equals(context.subject().subjectType())
            && security.sameFingerprint(
                receipt.principalFingerprint(),
                security.principalFingerprint(context)
            )
            && security.sameFingerprint(
                receipt.subjectFingerprint(),
                security.subjectFingerprint(context)
            )
            && security.sameFingerprint(
                receipt.tenantFingerprint(),
                security.tenantFingerprint(context)
            )
            && security.sameFingerprint(
                receipt.deploymentFingerprint(),
                security.deploymentFingerprint(context)
            );
    }

    private ActionProposalReceipt reload(String receiptId) {
        ActionProposalReceipt receipt = loadReceipt(receiptId);
        if (receipt == null) {
            throw receiptStoreUnavailable(new IllegalStateException(
                "Action receipt disappeared during transition"
            ));
        }
        return receipt;
    }

    private String failureReason(
        GovernedActionInvocationOutcome outcome,
        String fallback
    ) {
        String reason = outcome != null
            && outcome.publicFailure() != null
            && outcome.publicFailure().reason() != null
            ? outcome.publicFailure().reason()
            : fallback;
        String normalized = reason != null ? reason.trim() : "";
        return normalized.isEmpty() || normalized.length() > 160
            ? fallback
            : normalized;
    }

    private String failureMessage(
        GovernedActionInvocationOutcome outcome,
        String fallback
    ) {
        if (outcome != null
            && outcome.publicFailure() != null
            && outcome.publicFailure().publicMessage() != null
            && !outcome.publicFailure().publicMessage().isBlank()) {
            return outcome.publicFailure().publicMessage();
        }
        ActionResult result = outcome != null ? outcome.actionResult() : null;
        return result != null
            && result.getMessage() != null
            && !result.getMessage().isBlank()
            ? result.getMessage()
            : fallback;
    }

    private String confirmationMessage(
        GovernedActionInvocationOutcome outcome
    ) {
        String message = failureMessage(
            outcome,
            "Please confirm to proceed."
        );
        if (message.length() > 1000) {
            throw new ActionProposalValidationException(
                "ACTION_CONFIRMATION_MESSAGE_INVALID",
                "The application confirmation message exceeds 1000 characters."
            );
        }
        return message;
    }

    private String normalizeIdempotencyKey(
        String requested,
        String invocationId
    ) {
        String normalized =
            requested == null ? null : requested.trim();
        if (normalized == null || normalized.isEmpty()) {
            normalized = "action:" + requireText(invocationId, "invocationId");
        }
        if (normalized.length() > 200) {
            throw new ActionProposalValidationException(
                "IDEMPOTENCY_KEY_TOO_LONG",
                "The idempotency key exceeds 200 characters."
            );
        }
        return normalized;
    }

    private String trustedSubjectType(TrustedExecutionContext context) {
        if (context.subject() == null) {
            throw new ActionProposalValidationException(
                "TRUSTED_SUBJECT_REQUIRED",
                "Specialist write proposals require a trusted subject."
            );
        }
        return context.subject().subjectType();
    }

    private String parameterBinding(String receiptId) {
        return receiptId + ":parameters";
    }

    private String outcomeBinding(String receiptId) {
        return receiptId + ":outcome";
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private ActionProposalPersistenceException proposalPersistenceFailure(
        RuntimeException cause
    ) {
        return new ActionProposalPersistenceException(
            "ACTION_RECEIPT_PERSISTENCE_FAILED",
            "The action proposal could not be persisted. No action was executed.",
            cause
        );
    }

    private ActionProposalReceipt loadReceipt(String receiptId) {
        try {
            return repository.findById(receiptId).orElse(null);
        } catch (RuntimeException cause) {
            throw receiptStoreUnavailable(cause);
        }
    }

    private boolean compareAndSetReceipt(
        ActionProposalReceipt expected,
        ActionProposalReceipt updated
    ) {
        try {
            return repository.compareAndSet(expected, updated);
        } catch (RuntimeException cause) {
            throw receiptStoreUnavailable(cause);
        }
    }

    private ActionProposalPersistenceException receiptStoreUnavailable(
        RuntimeException cause
    ) {
        return new ActionProposalPersistenceException(
            "RECEIPT_STORE_UNAVAILABLE",
            "The action receipt store is temporarily unavailable.",
            cause
        );
    }

    private record CurrentAction(
        SpecialistDefinition<?, ?> definition,
        EffectiveCapabilityProfile effectiveProfile,
        Map<String, Object> parameters,
        ActionContext actionContext,
        ActionOutcomeProjector projector
    ) {}

    private record ResolvedCapabilities(
        EffectiveCapabilityProfile effectiveProfile,
        OrchestrationContext effectiveContext,
        PipelineContext pipelineContext
    ) {}
}
