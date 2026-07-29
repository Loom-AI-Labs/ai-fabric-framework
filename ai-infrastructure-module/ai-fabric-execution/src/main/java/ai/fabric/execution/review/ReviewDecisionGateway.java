package ai.fabric.execution.review;

import ai.fabric.execution.action.ActionOutcomeView;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalDecision;
import ai.fabric.execution.action.ActionProposalDecisionRequest;
import ai.fabric.execution.action.ActionProposalDecisionResult;
import ai.fabric.execution.action.ActionProposalReceipt;
import ai.fabric.execution.action.ActionProposalReceiptRepository;
import ai.fabric.execution.action.ActionProposalReceiptStatus;
import ai.fabric.execution.action.ActionProposalSecurity;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.review.auth.ReviewAuthorizationOperation;
import ai.fabric.execution.review.auth.ReviewAuthorizationRequest;
import ai.fabric.execution.review.auth.ReviewerAuthorization;
import ai.fabric.execution.review.auth.ReviewerAuthorizer;
import ai.fabric.execution.review.auth.ReviewerAuthorizerRegistry;
import ai.fabric.execution.review.continuation.ReviewCorrectionContext;
import ai.fabric.execution.review.continuation.ReviewCorrectionHandlerRegistry;
import ai.fabric.execution.review.continuation.ReviewCorrectionOutcome;
import ai.fabric.execution.review.continuation.ReviewInformationHandler;
import ai.fabric.execution.review.continuation.ReviewInformationHandlerRegistry;
import ai.fabric.execution.review.continuation.ReviewInformationRequestContext;
import ai.fabric.execution.review.continuation.ReviewInformationRequestOutcome;
import ai.fabric.execution.review.continuation.ReviewInformationSubmissionContext;
import ai.fabric.execution.review.continuation.ReviewInformationSubmissionOutcome;
import ai.fabric.execution.review.decision.ReviewDecisionFailure;
import ai.fabric.execution.review.decision.ReviewDecisionRequest;
import ai.fabric.execution.review.decision.ReviewDecisionResult;
import ai.fabric.execution.review.decision.ReviewDecisionType;
import ai.fabric.execution.review.dispatch.ReviewDispatchRequest;
import ai.fabric.execution.review.dispatch.ReviewDispatchResult;
import ai.fabric.execution.review.dispatch.ReviewDispatchStatus;
import ai.fabric.execution.review.dispatch.ReviewTaskDispatcher;
import ai.fabric.execution.review.dispatch.ReviewTaskDispatcherRegistry;
import ai.fabric.execution.review.input.ReviewInformationResult;
import ai.fabric.execution.review.input.ReviewInformationSubmission;
import ai.fabric.execution.review.persistence.ReviewDispatchRecord;
import ai.fabric.execution.review.persistence.ReviewDispatchRepository;
import ai.fabric.execution.review.persistence.ReviewTaskRecord;
import ai.fabric.execution.review.persistence.ReviewTaskRepository;
import ai.fabric.execution.review.policy.RegisteredReviewPolicy;
import ai.fabric.execution.review.policy.ReviewPolicyDefinition;
import ai.fabric.execution.review.policy.ReviewPolicyId;
import ai.fabric.execution.review.policy.ReviewPolicyRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable human-review boundary for existing governed action proposals.
 */
public final class ReviewDecisionGateway {

    private final ReviewTaskRepository taskRepository;
    private final ReviewDispatchRepository dispatchRepository;
    private final ReviewPolicyRegistry policyRegistry;
    private final ReviewerAuthorizerRegistry authorizers;
    private final ReviewTaskDispatcherRegistry dispatchers;
    private final ReviewCorrectionHandlerRegistry correctionHandlers;
    private final ReviewInformationHandlerRegistry informationHandlers;
    private final SpecialistJsonSchemaRegistry schemaRegistry;
    private final SpecialistJsonSchemaValidator schemaValidator;
    private final ActionProposalReceiptRepository receiptRepository;
    private final ActionProposalSecurity actionSecurity;
    private final ActionProposalCoordinator actionCoordinator;
    private final ReviewSecurity security;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration decisionLease;
    private final int maxDispatchAttempts;
    private final int maxDecisionAttempts;

    public ReviewDecisionGateway(
        ReviewTaskRepository taskRepository,
        ReviewDispatchRepository dispatchRepository,
        ReviewPolicyRegistry policyRegistry,
        ReviewerAuthorizerRegistry authorizers,
        ReviewTaskDispatcherRegistry dispatchers,
        ReviewCorrectionHandlerRegistry correctionHandlers,
        ReviewInformationHandlerRegistry informationHandlers,
        SpecialistJsonSchemaRegistry schemaRegistry,
        SpecialistJsonSchemaValidator schemaValidator,
        ActionProposalReceiptRepository receiptRepository,
        ActionProposalSecurity actionSecurity,
        ActionProposalCoordinator actionCoordinator,
        ReviewSecurity security,
        ObjectMapper objectMapper,
        Clock clock,
        Duration decisionLease,
        int maxDispatchAttempts,
        int maxDecisionAttempts
    ) {
        this.taskRepository = Objects.requireNonNull(
            taskRepository,
            "taskRepository is required"
        );
        this.dispatchRepository = Objects.requireNonNull(
            dispatchRepository,
            "dispatchRepository is required"
        );
        this.policyRegistry = Objects.requireNonNull(
            policyRegistry,
            "policyRegistry is required"
        );
        this.authorizers = Objects.requireNonNull(
            authorizers,
            "authorizers is required"
        );
        this.dispatchers = Objects.requireNonNull(
            dispatchers,
            "dispatchers is required"
        );
        this.correctionHandlers = Objects.requireNonNull(
            correctionHandlers,
            "correctionHandlers is required"
        );
        this.informationHandlers = Objects.requireNonNull(
            informationHandlers,
            "informationHandlers is required"
        );
        this.schemaRegistry = Objects.requireNonNull(
            schemaRegistry,
            "schemaRegistry is required"
        );
        this.schemaValidator = Objects.requireNonNull(
            schemaValidator,
            "schemaValidator is required"
        );
        this.receiptRepository = Objects.requireNonNull(
            receiptRepository,
            "receiptRepository is required"
        );
        this.actionSecurity = Objects.requireNonNull(
            actionSecurity,
            "actionSecurity is required"
        );
        this.actionCoordinator = Objects.requireNonNull(
            actionCoordinator,
            "actionCoordinator is required"
        );
        this.security = Objects.requireNonNull(
            security,
            "security is required"
        );
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper is required"
        ).copy();
        this.clock = Objects.requireNonNull(clock, "clock is required");
        if (decisionLease == null
            || decisionLease.isZero()
            || decisionLease.isNegative()) {
            throw new IllegalArgumentException(
                "decisionLease must be positive"
            );
        }
        if (maxDecisionAttempts < 1) {
            throw new IllegalArgumentException(
                "maxDecisionAttempts must be positive"
            );
        }
        this.decisionLease = decisionLease;
        if (maxDispatchAttempts < 1) {
            throw new IllegalArgumentException(
                "maxDispatchAttempts must be positive"
            );
        }
        this.maxDispatchAttempts = maxDispatchAttempts;
        this.maxDecisionAttempts = maxDecisionAttempts;
    }

    public ReviewTaskCreationResult createActionReview(
        ActionReviewRequest request,
        TrustedExecutionContext sourceContext
    ) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(
            sourceContext,
            "sourceContext is required"
        );
        RegisteredReviewPolicy policy = policyRegistry.require(
            request.policyId()
        );
        validateRegisteredExtensions(policy.definition());

        ActionProposalReceipt receipt = receiptRepository
            .findById(request.receiptId())
            .orElse(null);
        if (receipt == null || !sourceIdentityMatches(
                receipt,
                sourceContext
            )) {
            return creationFailure(
                "REVIEW_SOURCE_NOT_AVAILABLE",
                "The action proposal is not available for review."
            );
        }
        Instant now = clock.instant();
        if (receipt.status() != ActionProposalReceiptStatus.PROPOSED
            || !now.isBefore(receipt.expiresAt())) {
            return creationFailure(
                "REVIEW_SOURCE_NOT_PROPOSED",
                "Only a current proposed action can enter review."
            );
        }
        Instant expiresAt = now.plus(policy.definition().taskTtl());
        if (expiresAt.isAfter(receipt.expiresAt())) {
            return creationFailure(
                "REVIEW_SOURCE_TTL_TOO_SHORT",
                "The action receipt expires before this review policy. Increase the receipt TTL or shorten the review policy."
            );
        }

        String idempotencyFingerprint =
            security.idempotencyFingerprint(
                sourceContext,
                policy.id(),
                request.idempotencyKey()
            );
        ReviewTaskRecord existing = taskRepository
            .findByIdempotencyFingerprint(idempotencyFingerprint)
            .orElse(null);
        String sourceFingerprint = security.sourceFingerprint(receipt);
        String requestFingerprint = security.creationFingerprint(
            policy.id(),
            sourceFingerprint,
            request.title(),
            request.summary()
        );
        if (existing != null) {
            verifyCreationReplay(
                existing,
                policy,
                sourceFingerprint,
                requestFingerprint,
                sourceContext
            );
            return existingCreationResult(existing, policy);
        }

        String taskId = "review-task-" + UUID.randomUUID();
        ReviewTaskRecord task = new ReviewTaskRecord(
            taskId,
            policy.id(),
            policy.contentHash(),
            policy.definition().type(),
            ReviewSourceType.ACTION_PROPOSAL,
            sourceFingerprint,
            security.initiatorFingerprint(sourceContext),
            security.subjectFingerprint(sourceContext),
            security.tenantFingerprint(sourceContext),
            security.deploymentFingerprint(sourceContext),
            idempotencyFingerprint,
            requestFingerprint,
            security.protectSource(
                taskId,
                receipt.receiptId(),
                sourceContext
            ),
            security.protectPresentation(
                taskId,
                request.title(),
                request.summary()
            ),
            policy.definition().allowedDecisions(),
            ReviewTaskStatus.WAITING_FOR_REVIEW,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            expiresAt,
            now,
            null,
            null,
            null,
            0,
            0
        );
        try {
            taskRepository.create(task);
        } catch (ReviewTaskRepository.DuplicateTaskException ex) {
            ReviewTaskRecord raced = taskRepository
                .findByIdempotencyFingerprint(idempotencyFingerprint)
                .orElseThrow(() -> ex);
            verifyCreationReplay(
                raced,
                policy,
                sourceFingerprint,
                requestFingerprint,
                sourceContext
            );
            return existingCreationResult(raced, policy);
        }
        return dispatch(task, policy, 1);
    }

    public Optional<ReviewTaskView> find(
        String taskId,
        TrustedReviewerContext reviewer
    ) {
        Objects.requireNonNull(reviewer, "reviewer is required");
        ReviewTaskRecord task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !canAccess(task, reviewer, null)) {
            return Optional.empty();
        }
        return Optional.of(publicView(task));
    }

    public Optional<ReviewTaskDetailView> findDetail(
        String taskId,
        TrustedReviewerContext reviewer
    ) {
        Objects.requireNonNull(reviewer, "reviewer is required");
        ReviewTaskRecord task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !canAccess(task, reviewer, null)) {
            return Optional.empty();
        }
        return Optional.of(publicDetail(task));
    }

    public List<ReviewTaskView> inbox(
        TrustedReviewerContext reviewer,
        int limit
    ) {
        Objects.requireNonNull(reviewer, "reviewer is required");
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException(
                "limit must be between 1 and 200"
            );
        }
        String tenant = security.tenantFingerprint(reviewer);
        List<ReviewTaskRecord> candidates = new ArrayList<>();
        candidates.addAll(taskRepository.findByTenantAndStatus(
            tenant,
            ReviewTaskStatus.WAITING_FOR_REVIEW,
            limit
        ));
        candidates.addAll(taskRepository.findByTenantAndStatus(
            tenant,
            ReviewTaskStatus.WAITING_FOR_INFORMATION,
            limit
        ));
        return candidates.stream()
            .sorted(java.util.Comparator.comparing(
                ReviewTaskRecord::createdAt
            ))
            .filter(task -> canAccess(task, reviewer, null))
            .limit(limit)
            .map(this::publicView)
            .toList();
    }

    public ReviewDecisionResult decide(
        ReviewDecisionRequest request,
        TrustedReviewerContext reviewer
    ) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(reviewer, "reviewer is required");
        ReviewTaskRecord task = taskRepository
            .findById(request.taskId())
            .orElse(null);
        if (task == null || !canAccess(
                task,
                reviewer,
                request.decision()
            )) {
            return unavailable(request.taskId());
        }
        RegisteredReviewPolicy policy = currentPolicy(task);
        if (policy == null) {
            return failure(
                task,
                "REVIEW_POLICY_CHANGED",
                "The pinned review policy is no longer available.",
                false
            );
        }
        Instant now = clock.instant();
        task = expireIfNeeded(task, now);
        if (task.status().terminal()) {
            if (task.decisionType() != null
                && (task.decisionType() != request.decision()
                    || !security.sameFingerprint(
                        task.decisionFingerprint(),
                        security.decisionFingerprint(
                            task.taskId(),
                            request
                        )
                    )
                    || !security.sameFingerprint(
                        task.reviewerFingerprint(),
                        security.reviewerFingerprint(reviewer)
                    ))) {
                return failure(
                    task,
                    "REVIEW_DECISION_CONFLICT",
                    "This review task already has another terminal decision or decision identity.",
                    false
                );
            }
            return terminalResult(task);
        }
        if (task.status() == ReviewTaskStatus.WAITING_FOR_INFORMATION) {
            return failure(
                task,
                "REVIEW_INFORMATION_PENDING",
                "The requested information must be supplied before another decision.",
                false
            );
        }
        if (!task.allowedDecisions().contains(request.decision())) {
            return failure(
                task,
                "REVIEW_DECISION_NOT_ALLOWED",
                "This decision is not allowed by the pinned review policy.",
                false
            );
        }
        try {
            validateDecisionPayload(policy.definition(), request);
        } catch (IllegalArgumentException ex) {
            return failure(
                task,
                "REVIEW_DECISION_PAYLOAD_INVALID",
                "The decision response does not satisfy the pinned review contract.",
                false
            );
        }

        String decisionFingerprint = security.decisionFingerprint(
            task.taskId(),
            request
        );
        String reviewerFingerprint =
            security.reviewerFingerprint(reviewer);
        if (task.status() == ReviewTaskStatus.DECIDING) {
            return resumeOrReport(
                task,
                request,
                reviewer,
                decisionFingerprint,
                reviewerFingerprint,
                now
            );
        }
        if (request.expectedVersion() != task.version()) {
            return failure(
                task,
                "REVIEW_VERSION_CONFLICT",
                "The review task changed. Reload it before deciding.",
                true
            );
        }

        String workerId = "review-worker-" + UUID.randomUUID();
        ReviewTaskRecord claimed = task.claim(
            request.decision(),
            decisionFingerprint,
            reviewerFingerprint,
            security.protectDecision(task.taskId(), request, reviewer),
            workerId,
            now,
            now.plus(decisionLease)
        );
        if (!taskRepository.compareAndSet(task, claimed)) {
            return decide(request, reviewer);
        }
        return continueDecision(
            claimed,
            new ReviewSecurity.ReviewDecisionEnvelope(
                request.decisionId(),
                request.decision(),
                request.expectedVersion(),
                request.response(),
                reviewer
            )
        );
    }

    public ReviewInformationResult provideInformation(
        ReviewInformationSubmission submission,
        TrustedExecutionContext sourceContext
    ) {
        Objects.requireNonNull(submission, "submission is required");
        Objects.requireNonNull(
            sourceContext,
            "sourceContext is required"
        );
        ReviewTaskRecord task = taskRepository
            .findById(submission.taskId())
            .orElse(null);
        if (task == null || !sourceAccessMatches(task, sourceContext)) {
            return informationFailure(
                null,
                "REVIEW_TASK_NOT_AVAILABLE",
                "The review task is not available."
            );
        }
        if (task.status() != ReviewTaskStatus.WAITING_FOR_INFORMATION) {
            Map<String, Object> stored = unprotectResult(task);
            if (submission.submissionId().equals(
                    stored.get("submissionId")
                )) {
                return new ReviewInformationResult(
                    publicView(task),
                    stringValue(stored.get("message")),
                    null
                );
            }
            return informationFailure(
                task,
                "REVIEW_NOT_WAITING_FOR_INFORMATION",
                "This review task is not waiting for information."
            );
        }
        if (submission.expectedVersion() != task.version()) {
            return informationFailure(
                task,
                "REVIEW_VERSION_CONFLICT",
                "The review task changed. Reload it before responding."
            );
        }
        RegisteredReviewPolicy policy = currentPolicy(task);
        if (policy == null) {
            return informationFailure(
                task,
                "REVIEW_POLICY_CHANGED",
                "The pinned review policy is no longer available."
            );
        }
        try {
            schemaValidator.validate(
                schemaRegistry.require(
                    policy.definition().informationResponseSchemaId(),
                    ai.fabric.execution.specialist.manifest
                        .SpecialistSchemaDirection.INPUT
                ),
                submission.response()
            );
        } catch (IllegalArgumentException ex) {
            return informationFailure(
                task,
                "REVIEW_INFORMATION_PAYLOAD_INVALID",
                "The supplied information does not satisfy the pinned review contract."
            );
        }
        ReviewSecurity.ReviewSourceEnvelope source =
            security.unprotectSource(
                task.taskId(),
                task.protectedSource()
            );
        Map<String, Object> pending = unprotectResult(task);
        JsonNode requested = objectMapper.valueToTree(
            pending.get("requestedInformation")
        );
        ReviewInformationHandler handler = informationHandlers.require(
            policy.definition().informationHandlerId()
        );
        ReviewInformationSubmissionOutcome outcome;
        try {
            outcome = handler.receiveInformation(
                new ReviewInformationSubmissionContext(
                    publicView(task),
                    requested,
                    submission.response(),
                    source.context()
                )
            );
        } catch (RuntimeException ex) {
            return informationFailure(
                task,
                "REVIEW_INFORMATION_CONTINUATION_FAILED",
                "The supplied information could not be processed safely."
            );
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("requestedInformation", requested);
        stored.put("suppliedInformation", submission.response());
        stored.put("submissionId", submission.submissionId());
        stored.put("message", outcome.publicMessage());
        ReviewTaskRecord waiting = task.informationProvided(
            security.protectResult(task.taskId(), stored),
            clock.instant()
        );
        if (!taskRepository.compareAndSet(task, waiting)) {
            return provideInformation(submission, sourceContext);
        }
        dispatch(waiting, policy, nextDispatchAttempt(waiting.taskId()));
        return new ReviewInformationResult(
            publicView(waiting),
            outcome.publicMessage(),
            null
        );
    }

    ReviewDecisionResult recover(ReviewTaskRecord task, String workerId) {
        Instant now = clock.instant();
        if (task.status() != ReviewTaskStatus.DECIDING
            || task.leaseUntil() == null
            || task.leaseUntil().isAfter(now)) {
            return failure(
                task,
                "REVIEW_NOT_RECOVERABLE",
                "The review task does not require recovery.",
                false
            );
        }
        if (task.attemptCount() >= maxDecisionAttempts) {
            ReviewTaskRecord failed = task.failed(
                "REVIEW_DECISION_ATTEMPTS_EXHAUSTED",
                now
            );
            taskRepository.compareAndSet(task, failed);
            return terminalResult(reload(task.taskId()));
        }
        ReviewTaskRecord claimed = task.reclaim(
            workerId,
            now,
            now.plus(decisionLease)
        );
        if (!taskRepository.compareAndSet(task, claimed)) {
            return failure(
                reload(task.taskId()),
                "REVIEW_RECOVERY_RACED",
                "Another worker recovered this review task.",
                true
            );
        }
        ReviewSecurity.ReviewDecisionEnvelope decision =
            security.unprotectDecision(
                claimed.taskId(),
                claimed.protectedDecision()
            );
        return continueDecision(claimed, decision);
    }

    DispatchRecoveryResult recoverDispatch(ReviewTaskRecord task) {
        if (task.status() != ReviewTaskStatus.WAITING_FOR_REVIEW) {
            return new DispatchRecoveryResult(false, false);
        }
        RegisteredReviewPolicy policy = currentPolicy(task);
        if (policy == null) {
            return new DispatchRecoveryResult(false, false);
        }
        List<ReviewDispatchRecord> existing =
            dispatchRepository.findByTaskId(task.taskId());
        if (!existing.isEmpty()) {
            ReviewDispatchRecord latest = existing.getLast();
            if (latest.status() == ReviewDispatchStatus.ACCEPTED
                || (latest.status() == ReviewDispatchStatus.FAILED
                    && latest.attemptNumber() >= maxDispatchAttempts)) {
                return new DispatchRecoveryResult(false, false);
            }
        }
        ReviewTaskCreationResult result = existingCreationResult(task, policy);
        return new DispatchRecoveryResult(
            true,
            result.dispatchAccepted()
        );
    }

    private ReviewDecisionResult continueDecision(
        ReviewTaskRecord task,
        ReviewSecurity.ReviewDecisionEnvelope decision
    ) {
        RegisteredReviewPolicy policy = currentPolicy(task);
        if (policy == null || !canAccess(
                task,
                decision.reviewer(),
                decision.decision()
            )) {
            return failClaimed(
                task,
                "REVIEW_AUTHORIZATION_REVOKED",
                "Reviewer authorization or the pinned policy changed."
            );
        }
        ReviewSecurity.ReviewSourceEnvelope source;
        ActionProposalReceipt receipt;
        try {
            source = security.unprotectSource(
                task.taskId(),
                task.protectedSource()
            );
            receipt = receiptRepository
                .findById(source.receiptId())
                .orElse(null);
        } catch (RuntimeException ex) {
            return failure(
                task,
                "REVIEW_SOURCE_STORE_UNAVAILABLE",
                "The action source is temporarily unavailable.",
                true
            );
        }
        if (receipt == null
            || !security.sourceFingerprint(receipt).equals(
                task.sourceFingerprint()
            )
            || !sourceIdentityMatches(receipt, source.context())) {
            return failClaimed(
                task,
                "REVIEW_SOURCE_CHANGED",
                "The reviewed action source changed or is no longer available."
            );
        }
        try {
            return switch (decision.decision()) {
                case APPROVE -> advanceAction(
                    task,
                    source.context(),
                    ActionProposalDecision.CONFIRM,
                    ReviewTaskStatus.APPROVED
                );
                case REJECT -> advanceAction(
                    task,
                    source.context(),
                    ActionProposalDecision.REJECT,
                    ReviewTaskStatus.REJECTED
                );
                case CORRECT -> correct(
                    task,
                    policy,
                    source,
                    decision
                );
                case REQUEST_INFORMATION -> requestInformation(
                    task,
                    policy,
                    source,
                    decision
                );
                case ESCALATE -> escalate(
                    task,
                    policy,
                    source,
                    decision
                );
            };
        } catch (RuntimeException ex) {
            return failure(
                task,
                "REVIEW_CONTINUATION_FAILED",
                "The durable review continuation failed and can be recovered safely.",
                true
            );
        }
    }

    private ReviewDecisionResult advanceAction(
        ReviewTaskRecord task,
        TrustedExecutionContext sourceContext,
        ActionProposalDecision actionDecision,
        ReviewTaskStatus finalStatus
    ) {
        ReviewSecurity.ReviewSourceEnvelope source =
            security.unprotectSource(
                task.taskId(),
                task.protectedSource()
            );
        ActionProposalDecisionResult action = actionCoordinator.decide(
            new ActionProposalDecisionRequest(
                source.receiptId(),
                actionDecision
            ),
            sourceContext
        );
        ActionProposalReceiptStatus expected =
            actionDecision == ActionProposalDecision.CONFIRM
                ? ActionProposalReceiptStatus.SUCCEEDED
                : ActionProposalReceiptStatus.REJECTED;
        if (action.status() == expected) {
            String result = action.outcome() == null
                ? null
                : protectOutcome(task.taskId(), action.outcome());
            ReviewTaskRecord completed = task.completed(
                finalStatus,
                result,
                null,
                clock.instant()
            );
            return complete(task, completed, action.outcome());
        }
        if (action.status() == ActionProposalReceiptStatus.EXECUTING
            || (action.failure() != null && action.failure().retryable())) {
            return failure(
                task,
                action.failure() == null
                    ? "ACTION_EXECUTION_IN_PROGRESS"
                    : action.failure().reason(),
                action.failure() == null
                    ? "The governed action is still being resolved."
                    : action.failure().publicMessage(),
                true
            );
        }
        String reason = action.failure() != null
            ? action.failure().reason()
            : "REVIEW_ACTION_DID_NOT_COMPLETE";
        String message = action.failure() != null
            ? action.failure().publicMessage()
            : "The governed action did not reach the required terminal state.";
        String result = action.outcome() == null
            ? null
            : protectOutcome(task.taskId(), action.outcome());
        ReviewTaskRecord failed = task.failed(
            reason,
            result,
            clock.instant()
        );
        if (!taskRepository.compareAndSet(task, failed)) {
            return terminalResult(reload(task.taskId()));
        }
        return new ReviewDecisionResult(
            publicView(failed),
            action.outcome(),
            null,
            new ReviewDecisionFailure(reason, message, false)
        );
    }

    private ReviewDecisionResult correct(
        ReviewTaskRecord task,
        RegisteredReviewPolicy policy,
        ReviewSecurity.ReviewSourceEnvelope source,
        ReviewSecurity.ReviewDecisionEnvelope decision
    ) {
        ReviewDecisionResult retirementFailure = retireOriginalProposal(
            task,
            source
        );
        if (retirementFailure != null) {
            return retirementFailure;
        }
        ReviewCorrectionOutcome outcome = correctionHandlers
            .require(policy.definition().correctionHandlerId())
            .correct(new ReviewCorrectionContext(
                publicView(task),
                requireResponse(decision.response(), "correction"),
                source.context(),
                decision.reviewer()
            ));
        String successorTaskId = null;
        ActionOutcomeView safeResult = outcome.safeResult();
        if (outcome.successorReceiptId() != null) {
            ReviewSecurity.ReviewPresentation presentation =
                security.unprotectPresentation(
                    task.taskId(),
                    task.protectedPresentation()
                );
            ReviewTaskCreationResult successor = createActionReview(
                new ActionReviewRequest(
                    outcome.successorReceiptId(),
                    task.policyId(),
                    bounded("Corrected: " + presentation.title(), 160),
                    presentation.summary(),
                    "correction:" + task.taskId() + ":"
                        + decision.decisionId()
                ),
                source.context()
            );
            if (successor.task() == null) {
                return failClaimed(
                    task,
                    successor.failure().reason(),
                    successor.failure().publicMessage()
                );
            }
            successorTaskId = successor.task().taskId();
        }
        String protectedResult = safeResult == null
            ? null
            : protectOutcome(task.taskId(), safeResult);
        ReviewTaskRecord completed = task.completed(
            ReviewTaskStatus.CORRECTED,
            protectedResult,
            successorTaskId,
            clock.instant()
        );
        return complete(task, completed, safeResult);
    }

    private ReviewDecisionResult retireOriginalProposal(
        ReviewTaskRecord task,
        ReviewSecurity.ReviewSourceEnvelope source
    ) {
        ActionProposalDecisionResult retired = actionCoordinator.decide(
            new ActionProposalDecisionRequest(
                source.receiptId(),
                ActionProposalDecision.REJECT
            ),
            source.context()
        );
        if (retired.status() == ActionProposalReceiptStatus.REJECTED) {
            return null;
        }
        if (retired.status() == ActionProposalReceiptStatus.EXECUTING
            || (retired.failure() != null
                && retired.failure().retryable())) {
            return failure(
                task,
                retired.failure() == null
                    ? "ACTION_EXECUTION_IN_PROGRESS"
                    : retired.failure().reason(),
                retired.failure() == null
                    ? "The original action proposal is still being resolved."
                    : retired.failure().publicMessage(),
                true
            );
        }
        return failClaimed(
            task,
            retired.failure() == null
                ? "REVIEW_ORIGINAL_PROPOSAL_NOT_RETIRED"
                : retired.failure().reason(),
            retired.failure() == null
                ? "The original action proposal could not be retired safely."
                : retired.failure().publicMessage()
        );
    }

    private ReviewDecisionResult requestInformation(
        ReviewTaskRecord task,
        RegisteredReviewPolicy policy,
        ReviewSecurity.ReviewSourceEnvelope source,
        ReviewSecurity.ReviewDecisionEnvelope decision
    ) {
        JsonNode response = requireResponse(
            decision.response(),
            "information request"
        );
        ReviewInformationRequestOutcome outcome = informationHandlers
            .require(policy.definition().informationHandlerId())
            .requestInformation(new ReviewInformationRequestContext(
                publicView(task),
                response,
                source.context(),
                decision.reviewer()
            ));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestedInformation", response);
        result.put("message", outcome.publicMessage());
        ReviewTaskRecord waiting = task.waitingForInformation(
            security.protectResult(task.taskId(), result),
            clock.instant()
        );
        if (!taskRepository.compareAndSet(task, waiting)) {
            return terminalResult(reload(task.taskId()));
        }
        return new ReviewDecisionResult(
            publicView(waiting),
            null,
            null,
            null
        );
    }

    private ReviewDecisionResult escalate(
        ReviewTaskRecord task,
        RegisteredReviewPolicy policy,
        ReviewSecurity.ReviewSourceEnvelope source,
        ReviewSecurity.ReviewDecisionEnvelope decision
    ) {
        ReviewPolicyId escalationPolicy =
            policy.definition().escalationPolicyId();
        ReviewSecurity.ReviewPresentation presentation =
            security.unprotectPresentation(
                task.taskId(),
                task.protectedPresentation()
            );
        ReviewTaskCreationResult successor = createActionReview(
            new ActionReviewRequest(
                source.receiptId(),
                escalationPolicy,
                bounded("Escalated: " + presentation.title(), 160),
                presentation.summary(),
                "escalation:" + task.taskId() + ":"
                    + decision.decisionId()
            ),
            source.context()
        );
        if (successor.task() == null) {
            return failClaimed(
                task,
                successor.failure().reason(),
                successor.failure().publicMessage()
            );
        }
        ReviewTaskRecord completed = task.completed(
            ReviewTaskStatus.ESCALATED,
            null,
            successor.task().taskId(),
            clock.instant()
        );
        return complete(task, completed, null);
    }

    private ReviewDecisionResult resumeOrReport(
        ReviewTaskRecord task,
        ReviewDecisionRequest request,
        TrustedReviewerContext reviewer,
        String decisionFingerprint,
        String reviewerFingerprint,
        Instant now
    ) {
        if (!security.sameFingerprint(
                task.decisionFingerprint(),
                decisionFingerprint
            )
            || !security.sameFingerprint(
                task.reviewerFingerprint(),
                reviewerFingerprint
            )) {
            return failure(
                task,
                "REVIEW_DECISION_CONFLICT",
                "Another decision already owns this review task.",
                false
            );
        }
        if (task.leaseUntil() != null
            && !task.leaseUntil().isAfter(now)) {
            return recover(
                task,
                "review-worker-" + UUID.randomUUID()
            );
        }
        return failure(
            task,
            "REVIEW_DECISION_IN_PROGRESS",
            "This review decision is already being processed.",
            true
        );
    }

    private ReviewTaskCreationResult dispatch(
        ReviewTaskRecord task,
        RegisteredReviewPolicy policy,
        int attempt
    ) {
        String dispatchId = "review-dispatch-" + UUID.randomUUID();
        String idempotencyKey = task.taskId()
            + ":"
            + policy.definition().dispatcherId()
            + ":"
            + attempt;
        ReviewDispatchRecord pending = new ReviewDispatchRecord(
            dispatchId,
            task.taskId(),
            policy.definition().dispatcherId(),
            attempt,
            idempotencyKey,
            ReviewDispatchStatus.PENDING,
            null,
            null,
            clock.instant(),
            null,
            0
        );
        try {
            dispatchRepository.create(pending);
        } catch (ReviewDispatchRepository.DuplicateDispatchException ex) {
            ReviewDispatchRecord existing = dispatchRepository
                .findByTaskId(task.taskId())
                .stream()
                .filter(item ->
                    item.idempotencyKey().equals(idempotencyKey)
                )
                .findFirst()
                .orElseThrow(() -> ex);
            return existing.status() == ReviewDispatchStatus.PENDING
                ? deliver(task, policy, existing)
                : dispatchCreationResult(task, existing);
        }
        return deliver(task, policy, pending);
    }

    private ReviewTaskCreationResult deliver(
        ReviewTaskRecord task,
        RegisteredReviewPolicy policy,
        ReviewDispatchRecord pending
    ) {
        ReviewTaskDispatcher dispatcher = dispatchers.require(
            policy.definition().dispatcherId()
        );
        ReviewDispatchResult result;
        try {
            result = dispatcher.dispatch(new ReviewDispatchRequest(
                pending.dispatchId(),
                publicView(task),
                pending.idempotencyKey()
            ));
        } catch (RuntimeException ex) {
            result = ReviewDispatchResult.failed(
                "REVIEW_DISPATCHER_FAILED"
            );
        }
        ReviewDispatchRecord completed = pending.completed(
            result.accepted()
                ? ReviewDispatchStatus.ACCEPTED
                : ReviewDispatchStatus.FAILED,
            result.externalReference(),
            result.failureReason(),
            clock.instant()
        );
        if (!dispatchRepository.compareAndSet(pending, completed)) {
            completed = dispatchRepository
                .findById(pending.dispatchId())
                .orElse(completed);
        }
        return dispatchCreationResult(task, completed);
    }

    private ReviewTaskCreationResult existingCreationResult(
        ReviewTaskRecord task,
        RegisteredReviewPolicy policy
    ) {
        List<ReviewDispatchRecord> existing =
            dispatchRepository.findByTaskId(task.taskId());
        if (existing.isEmpty()) {
            return dispatch(task, policy, 1);
        }
        ReviewDispatchRecord latest = existing.getLast();
        if (latest.status() == ReviewDispatchStatus.PENDING) {
            return deliver(task, policy, latest);
        }
        if (latest.status() == ReviewDispatchStatus.FAILED
            && latest.attemptNumber() < maxDispatchAttempts) {
            return dispatch(task, policy, latest.attemptNumber() + 1);
        }
        return dispatchCreationResult(task, latest);
    }

    private ReviewTaskCreationResult dispatchCreationResult(
        ReviewTaskRecord task,
        ReviewDispatchRecord dispatch
    ) {
        if (dispatch.status() == ReviewDispatchStatus.ACCEPTED) {
            return new ReviewTaskCreationResult(
                publicView(task),
                true,
                null
            );
        }
        return new ReviewTaskCreationResult(
            publicView(task),
            false,
            new ReviewDecisionFailure(
                dispatch.failureReason() == null
                    ? "REVIEW_DISPATCH_PENDING"
                    : dispatch.failureReason(),
                dispatch.status() == ReviewDispatchStatus.PENDING
                    ? "The review task is durable, but delivery is still pending."
                    : "The review task is durable, but delivery failed.",
                true
            )
        );
    }

    private boolean canAccess(
        ReviewTaskRecord task,
        TrustedReviewerContext reviewer,
        ReviewDecisionType decision
    ) {
        RegisteredReviewPolicy policy = currentPolicy(task);
        if (policy == null
            || !security.sameFingerprint(
                task.tenantFingerprint(),
                security.tenantFingerprint(reviewer)
            )
            || !reviewer.grantedScopes().containsAll(
                policy.definition().requiredReviewerScopes()
            )) {
            return false;
        }
        if (decision != null
            && policy.definition().separationOfDuty()) {
            ReviewSecurity.ReviewSourceEnvelope source;
            try {
                source = security.unprotectSource(
                    task.taskId(),
                    task.protectedSource()
                );
            } catch (RuntimeException ex) {
                return false;
            }
            if (samePrincipal(
                    source.context(),
                    reviewer
                )) {
                return false;
            }
        }
        ReviewerAuthorizer authorizer = authorizers.require(
            policy.definition().reviewerAuthorizerId()
        );
        ReviewerAuthorization authorization = authorizer.authorize(
            new ReviewAuthorizationRequest(
                publicView(task),
                decision == null
                    ? ReviewAuthorizationOperation.VIEW
                    : ReviewAuthorizationOperation.DECIDE,
                decision
            ),
            reviewer
        );
        return authorization != null && authorization.allowed();
    }

    private boolean samePrincipal(
        TrustedExecutionContext source,
        TrustedReviewerContext reviewer
    ) {
        return source.initiator().principalType()
                == reviewer.reviewer().principalType()
            && source.initiator().principalId().equals(
                reviewer.reviewer().principalId()
            );
    }

    private boolean sourceAccessMatches(
        ReviewTaskRecord task,
        TrustedExecutionContext context
    ) {
        if (context.subject() == null) {
            return false;
        }
        return security.sameFingerprint(
                task.initiatorFingerprint(),
                security.initiatorFingerprint(context)
            )
            && security.sameFingerprint(
                task.subjectFingerprint(),
                security.subjectFingerprint(context)
            )
            && security.sameFingerprint(
                task.tenantFingerprint(),
                security.tenantFingerprint(context)
            )
            && security.sameFingerprint(
                task.deploymentFingerprint(),
                security.deploymentFingerprint(context)
            );
    }

    private boolean sourceIdentityMatches(
        ActionProposalReceipt receipt,
        TrustedExecutionContext context
    ) {
        if (context.subject() == null
            || !receipt.subjectType().equals(
                context.subject().subjectType()
            )) {
            return false;
        }
        return actionSecurity.sameFingerprint(
                receipt.principalFingerprint(),
                actionSecurity.principalFingerprint(context)
            )
            && actionSecurity.sameFingerprint(
                receipt.subjectFingerprint(),
                actionSecurity.subjectFingerprint(context)
            )
            && actionSecurity.sameFingerprint(
                receipt.tenantFingerprint(),
                actionSecurity.tenantFingerprint(context)
            )
            && actionSecurity.sameFingerprint(
                receipt.deploymentFingerprint(),
                actionSecurity.deploymentFingerprint(context)
            );
    }

    private void validateDecisionPayload(
        ReviewPolicyDefinition policy,
        ReviewDecisionRequest request
    ) {
        if (request.decision() == ReviewDecisionType.CORRECT) {
            schemaValidator.validate(
                schemaRegistry.require(
                    policy.correctionSchemaId(),
                    ai.fabric.execution.specialist.manifest
                        .SpecialistSchemaDirection.INPUT
                ),
                requireResponse(request.response(), "correction")
            );
        }
        if (request.decision()
            == ReviewDecisionType.REQUEST_INFORMATION) {
            schemaValidator.validate(
                schemaRegistry.require(
                    policy.informationRequestSchemaId(),
                    ai.fabric.execution.specialist.manifest
                        .SpecialistSchemaDirection.INPUT
                ),
                requireResponse(
                    request.response(),
                    "information request"
                )
            );
        }
    }

    private JsonNode requireResponse(JsonNode response, String purpose) {
        if (response == null) {
            throw new IllegalArgumentException(
                purpose + " requires a typed response"
            );
        }
        return response;
    }

    private void validateRegisteredExtensions(
        ReviewPolicyDefinition policy
    ) {
        authorizers.require(policy.reviewerAuthorizerId());
        dispatchers.require(policy.dispatcherId());
        if (policy.correctionHandlerId() != null) {
            correctionHandlers.require(policy.correctionHandlerId());
        }
        if (policy.informationHandlerId() != null) {
            informationHandlers.require(policy.informationHandlerId());
        }
    }

    private RegisteredReviewPolicy currentPolicy(ReviewTaskRecord task) {
        RegisteredReviewPolicy policy = policyRegistry
            .find(task.policyId())
            .orElse(null);
        return policy != null
            && policy.contentHash().equals(task.policyContentHash())
                ? policy
                : null;
    }

    private ReviewTaskRecord expireIfNeeded(
        ReviewTaskRecord task,
        Instant now
    ) {
        if ((task.status() == ReviewTaskStatus.WAITING_FOR_REVIEW
            || task.status() == ReviewTaskStatus.WAITING_FOR_INFORMATION)
            && !task.expiresAt().isAfter(now)) {
            ReviewTaskRecord expired = task.expired(now);
            if (taskRepository.compareAndSet(task, expired)) {
                return expired;
            }
            return reload(task.taskId());
        }
        return task;
    }

    private ReviewDecisionResult complete(
        ReviewTaskRecord expected,
        ReviewTaskRecord completed,
        ActionOutcomeView outcome
    ) {
        if (!taskRepository.compareAndSet(expected, completed)) {
            return terminalResult(reload(expected.taskId()));
        }
        return new ReviewDecisionResult(
            publicView(completed),
            outcome,
            completed.successorTaskId(),
            null
        );
    }

    private ReviewDecisionResult failClaimed(
        ReviewTaskRecord task,
        String reason,
        String message
    ) {
        ReviewTaskRecord failed = task.failed(reason, clock.instant());
        if (!taskRepository.compareAndSet(task, failed)) {
            return terminalResult(reload(task.taskId()));
        }
        return new ReviewDecisionResult(
            publicView(failed),
            outcome(failed),
            failed.successorTaskId(),
            new ReviewDecisionFailure(reason, message, false)
        );
    }

    private ReviewDecisionResult terminalResult(ReviewTaskRecord task) {
        return new ReviewDecisionResult(
            publicView(task),
            outcome(task),
            task.successorTaskId(),
            task.status() == ReviewTaskStatus.FAILED
                ? new ReviewDecisionFailure(
                    task.failureReason() == null
                        ? "REVIEW_FAILED"
                        : task.failureReason(),
                    "The review could not be completed safely.",
                    false
                )
                : null
        );
    }

    private ReviewDecisionResult failure(
        ReviewTaskRecord task,
        String reason,
        String message,
        boolean retryable
    ) {
        return new ReviewDecisionResult(
            task == null ? null : publicView(task),
            task == null ? null : outcome(task),
            task == null ? null : task.successorTaskId(),
            new ReviewDecisionFailure(reason, message, retryable)
        );
    }

    private ReviewDecisionResult unavailable(String taskId) {
        return failure(
            null,
            "REVIEW_TASK_NOT_AVAILABLE",
            "The review task is not available.",
            false
        );
    }

    private ReviewTaskCreationResult creationFailure(
        String reason,
        String message
    ) {
        return new ReviewTaskCreationResult(
            null,
            false,
            new ReviewDecisionFailure(reason, message, false)
        );
    }

    private ReviewInformationResult informationFailure(
        ReviewTaskRecord task,
        String reason,
        String message
    ) {
        return new ReviewInformationResult(
            task == null ? null : publicView(task),
            null,
            new ReviewDecisionFailure(reason, message, false)
        );
    }

    private ReviewTaskView publicView(ReviewTaskRecord task) {
        ReviewSecurity.ReviewPresentation presentation =
            security.unprotectPresentation(
                task.taskId(),
                task.protectedPresentation()
            );
        return new ReviewTaskView(
            task.taskId(),
            task.policyId(),
            task.reviewType(),
            presentation.title(),
            presentation.summary(),
            task.allowedDecisions(),
            task.status(),
            task.createdAt(),
            task.expiresAt(),
            task.version()
        );
    }

    private ReviewTaskDetailView publicDetail(ReviewTaskRecord task) {
        Map<String, Object> result = unprotectResult(task);
        return new ReviewTaskDetailView(
            publicView(task),
            jsonValue(result.get("requestedInformation")),
            jsonValue(result.get("suppliedInformation")),
            stringValue(result.get("message")),
            outcome(task),
            task.successorTaskId(),
            task.failureReason()
        );
    }

    private String protectOutcome(
        String taskId,
        ActionOutcomeView outcome
    ) {
        return security.protectResult(
            taskId,
            Map.of("outcome", outcome)
        );
    }

    private ActionOutcomeView outcome(ReviewTaskRecord task) {
        if (task.protectedResult() == null) {
            return null;
        }
        Object value = unprotectResult(task).get("outcome");
        return value == null
            ? null
            : objectMapper.convertValue(value, ActionOutcomeView.class);
    }

    private Map<String, Object> unprotectResult(ReviewTaskRecord task) {
        if (task.protectedResult() == null) {
            return Map.of();
        }
        return security.unprotectResult(
            task.taskId(),
            task.protectedResult()
        );
    }

    private int nextDispatchAttempt(String taskId) {
        return dispatchRepository.findByTaskId(taskId).stream()
            .mapToInt(ReviewDispatchRecord::attemptNumber)
            .max()
            .orElse(0) + 1;
    }

    private void verifyCreationReplay(
        ReviewTaskRecord existing,
        RegisteredReviewPolicy policy,
        String sourceFingerprint,
        String requestFingerprint,
        TrustedExecutionContext sourceContext
    ) {
        if (!existing.policyId().equals(policy.id())
            || !existing.policyContentHash().equals(policy.contentHash())
            || !existing.sourceFingerprint().equals(sourceFingerprint)
            || !existing.requestFingerprint().equals(requestFingerprint)
            || !sourceAccessMatches(existing, sourceContext)) {
            throw new IllegalArgumentException(
                "Review idempotency key is bound to another source or policy"
            );
        }
    }

    private ReviewTaskRecord reload(String taskId) {
        return taskRepository.findById(taskId).orElseThrow(() ->
            new IllegalStateException(
                "Review task disappeared during an optimistic transition"
            )
        );
    }

    private String bounded(String value, int maxLength) {
        return value.length() <= maxLength
            ? value
            : value.substring(0, maxLength);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private JsonNode jsonValue(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    record DispatchRecoveryResult(boolean attempted, boolean accepted) {}
}
