package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalDecisionRequest;
import ai.fabric.execution.action.ActionProposalDecisionResult;
import ai.fabric.execution.delegation.SpecialistDelegationGateway;
import ai.fabric.execution.delegation.SpecialistDelegationRequest;
import ai.fabric.execution.delegation.SpecialistDelegationResult;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionResumeResult;
import ai.fabric.execution.gateway.AIExecutionFailure;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.gateway.ConversationBinding;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.plan.AIExecutionCoordinator;
import ai.fabric.execution.plan.PlanExecutionRequest;
import ai.fabric.execution.plan.PlanExecutionResult;
import ai.fabric.execution.plan.PlanExecutionResumeRequest;
import ai.fabric.execution.plan.PlanExecutionResumeResult;
import ai.fabric.execution.plan.PlanExecutionSnapshot;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistExecutionSnapshot;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import ai.fabric.execution.specialist.client.SpecialistResumeInvocation;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.AccountBillingResolutionPlanRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.AccountBillingResolutionPlanResult;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.AccountResolverPlans;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.PlanInputResumeRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Service;

@Service
public class AgenticResolverExecutionService {

    private static final Duration COORDINATOR_WAIT_LIMIT =
        Duration.ofSeconds(65);
    private static final long COORDINATOR_POLL_NANOS =
        Duration.ofMillis(25).toNanos();

    private static final Set<String> READ_SCOPES = Set.of(
        "specialist:account-resolver-read@1",
        "action:get_account_profile",
        "vector:account-resolution-policy"
    );
    private static final Set<String> INTERACTIVE_SCOPES = Set.of(
        "specialist:account-resolver@1",
        "action:get_account_profile",
        "action:update_address",
        "vector:account-resolution-policy"
    );
    private static final Set<String> BILLING_ADVISOR_SCOPES = Set.of(
        "specialist:billing-resolution-advisor@1",
        "action:assess_billing_resolution",
        "vector:account-resolution-policy"
    );
    private static final Set<String> ACCOUNT_BILLING_PLAN_SCOPES = Set.of(
        "specialist:account-resolver-read@1",
        "specialist:billing-resolution-advisor@1",
        "action:get_account_profile",
        "action:assess_billing_resolution",
        "vector:account-resolution-policy"
    );
    private static final Set<String> DELEGATION_SCOPES = Set.of(
        "specialist:account-resolution-coordinator@1",
        "specialist:account-resolver-read@1",
        "specialist:billing-resolution-advisor@1",
        "action:get_account_profile",
        "action:assess_billing_resolution",
        "vector:account-resolution-policy"
    );

    private final SpecialistClient<
        AccountResolutionRequest,
        AccountResolutionResult
    > interactiveClient;
    private final SpecialistClient<
        AccountResolutionRequest,
        AccountResolutionResult
    > readClient;
    private final SpecialistClient<
        BillingResolutionAssessmentRequest,
        BillingResolutionAssessmentResult
    > billingAdvisorClient;
    private final SpecialistClient<
        AccountDelegationCoordinatorRequest,
        AccountDelegationDecision
    > delegationCoordinatorClient;
    private final SpecialistDelegationGateway delegationGateway;
    private final AIExecutionCoordinator executionCoordinator;
    private final ActionProposalCoordinator actionProposalCoordinator;
    private final AgenticResolverSessionService sessionService;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AgenticResolverExecutionService(
        SpecialistClientFactory specialistClientFactory,
        AIExecutionCoordinator executionCoordinator,
        ActionProposalCoordinator actionProposalCoordinator,
        AgenticResolverSessionService sessionService,
        SpecialistDelegationGateway delegationGateway,
        Clock clock
    ) {
        this.interactiveClient = specialistClientFactory.bind(
            AccountResolverSpecialists.SPECIALIST_ID,
            AccountResolutionRequest.class,
            AccountResolutionResult.class
        );
        this.readClient = specialistClientFactory.bind(
            AccountResolverSpecialists.READ_SPECIALIST_ID,
            AccountResolutionRequest.class,
            AccountResolutionResult.class
        );
        this.billingAdvisorClient = specialistClientFactory.bind(
            AccountResolverSpecialists.BILLING_ADVISOR_SPECIALIST_ID,
            BillingResolutionAssessmentRequest.class,
            BillingResolutionAssessmentResult.class
        );
        this.delegationCoordinatorClient = specialistClientFactory.bind(
            AccountResolverSpecialists.DELEGATION_COORDINATOR_ID,
            AccountDelegationCoordinatorRequest.class,
            AccountDelegationDecision.class
        );
        this.delegationGateway = delegationGateway;
        this.executionCoordinator = executionCoordinator;
        this.actionProposalCoordinator = actionProposalCoordinator;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    AgenticResolverExecutionService(
        SpecialistClientFactory specialistClientFactory,
        AIExecutionCoordinator executionCoordinator,
        ActionProposalCoordinator actionProposalCoordinator,
        AgenticResolverSessionService sessionService,
        Clock clock
    ) {
        this(
            specialistClientFactory,
            executionCoordinator,
            actionProposalCoordinator,
            sessionService,
            null,
            clock
        );
    }

    public AIExecutionResult<AccountResolutionResult> evaluate(
        String sessionId,
        AccountResolutionRequest request
    ) {
        return evaluate(sessionId, request, null);
    }

    public AIExecutionResult<AccountResolutionResult> evaluate(
        String sessionId,
        AccountResolutionRequest request,
        String idempotencyKey
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return readClient.execute(new SpecialistInvocation<>(
            request,
            trustedContext(session, ExecutionSource.APPLICATION),
            null,
            null,
            normalizeIdempotencyKey(idempotencyKey)
        ));
    }

    public AIExecutionResult<AccountResolutionResult> chat(
        String sessionId,
        AccountResolutionRequest request
    ) {
        return chat(sessionId, request, null);
    }

    public AIExecutionResult<AccountResolutionResult> chat(
        String sessionId,
        AccountResolutionRequest request,
        String idempotencyKey
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return interactiveClient.execute(new SpecialistInvocation<>(
            request,
            trustedContext(session, ExecutionSource.INTERACTIVE),
            new ConversationBinding(
                session.conversationOwnerId(),
                session.conversationId()
            ),
            null,
            normalizeIdempotencyKey(idempotencyKey)
        ));
    }

    public ActionProposalDecisionResult decide(
        String sessionId,
        ActionProposalDecisionRequest request
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return actionProposalCoordinator.decide(
            request,
            trustedContext(session, ExecutionSource.INTERACTIVE)
        );
    }

    public AIExecutionResult<BillingResolutionAssessmentResult>
    assessBillingResolution(
        String sessionId,
        BillingResolutionAssessmentRequest request,
        String idempotencyKey
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return billingAdvisorClient.execute(new SpecialistInvocation<>(
            request,
            trustedContext(
                session,
                ExecutionSource.APPLICATION,
                BILLING_ADVISOR_SCOPES
            ),
            null,
            null,
            normalizeIdempotencyKey(idempotencyKey)
        ));
    }

    public AIExecutionResumeResult<BillingResolutionAssessmentResult>
    resumeBillingAssessment(
        String sessionId,
        BillingAssessmentResumeRequest request,
        String idempotencyKey
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return billingAdvisorClient.resume(
            new SpecialistResumeInvocation(
                request.invocationId(),
                request.requestId(),
                request.response(),
                trustedContext(
                    session,
                    ExecutionSource.APPLICATION,
                    BILLING_ADVISOR_SCOPES
                ),
                requireIdempotencyKey(idempotencyKey)
            )
        );
    }

    public PlanExecutionResult<AccountResolutionResult>
    executeAccountReadinessPlan(
        String sessionId,
        AccountResolutionRequest request,
        String idempotencyKey
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return executionCoordinator.execute(new PlanExecutionRequest<>(
            AccountResolverPlans.ACCOUNT_READINESS,
            request,
            trustedContext(
                session,
                ExecutionSource.APPLICATION,
                READ_SCOPES
            ),
            null,
            normalizeIdempotencyKey(idempotencyKey)
        ));
    }

    public PlanExecutionResult<AccountBillingResolutionPlanResult>
    executeAccountBillingPlan(
        String sessionId,
        AccountBillingResolutionPlanRequest request,
        String idempotencyKey
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return executionCoordinator.execute(new PlanExecutionRequest<>(
            AccountResolverPlans.ACCOUNT_BILLING_RESOLUTION,
            request,
            trustedContext(
                session,
                ExecutionSource.APPLICATION,
                ACCOUNT_BILLING_PLAN_SCOPES
            ),
            null,
            normalizeIdempotencyKey(idempotencyKey)
        ));
    }

    public AccountDelegationResponse delegateAccountResolution(
        String sessionId,
        AccountDelegationCoordinatorRequest request,
        String idempotencyKey
    ) {
        String requiredIdempotencyKey = requireIdempotencyKey(
            idempotencyKey
        );
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        TrustedExecutionContext trustedContext = trustedContext(
            session,
            ExecutionSource.APPLICATION,
            DELEGATION_SCOPES
        );
        SpecialistInvocation<AccountDelegationCoordinatorRequest> invocation =
            new SpecialistInvocation<>(
                request,
                trustedContext,
                null,
                null,
                requiredIdempotencyKey
            );
        AIExecutionResult<AccountDelegationDecision> coordinator =
            submitCoordinatorAndAwait(invocation);
        if (!coordinator.succeeded()
            || coordinator.output().decision()
                != AccountDelegationDecision.Decision.DELEGATE) {
            return new AccountDelegationResponse(coordinator, null);
        }

        SpecialistId target = SpecialistId.parse(
            coordinator.output().targetSpecialist()
        );
        SpecialistDelegationResult<AccountDelegationDecision, ?> delegated;
        if (target.equals(AccountResolverSpecialists.READ_SPECIALIST_ID)) {
            delegated = delegationGateway.delegate(
                new SpecialistDelegationRequest<>(
                    coordinator,
                    target,
                    new AccountResolutionRequest(request.question()),
                    trustedContext,
                    null,
                    requiredIdempotencyKey
                ),
                AccountResolutionRequest.class,
                AccountResolutionResult.class
            );
        } else if (target.equals(
            AccountResolverSpecialists.BILLING_ADVISOR_SPECIALIST_ID
        )) {
            delegated = delegationGateway.delegate(
                new SpecialistDelegationRequest<>(
                    coordinator,
                    target,
                    new BillingResolutionAssessmentRequest(
                        request.question(),
                        request.resolutionType(),
                        request.amount()
                    ),
                    trustedContext,
                    null,
                    requiredIdempotencyKey
                ),
                BillingResolutionAssessmentRequest.class,
                BillingResolutionAssessmentResult.class
            );
        } else {
            throw new IllegalStateException(
                "Validated coordinator selected an unsupported target"
            );
        }
        return new AccountDelegationResponse(coordinator, delegated);
    }

    private AIExecutionResult<AccountDelegationDecision>
    submitCoordinatorAndAwait(
        SpecialistInvocation<AccountDelegationCoordinatorRequest> invocation
    ) {
        ExecutionHandle submitted =
            delegationCoordinatorClient.submit(invocation);
        long waitDeadline = System.nanoTime()
            + COORDINATOR_WAIT_LIMIT.toNanos();
        while (true) {
            Optional<SpecialistExecutionSnapshot<AccountDelegationDecision>>
                snapshot = delegationCoordinatorClient.find(
                    submitted.invocationId(),
                    invocation.trustedExecutionContext()
                );
            if (snapshot.isPresent()) {
                SpecialistExecutionSnapshot<AccountDelegationDecision> value =
                    snapshot.get();
                if (value.result() != null) {
                    return value.result();
                }
                if (isTerminal(value.handle().status())) {
                    return coordinatorInfrastructureFailure(
                        value.handle(),
                        "The coordinator execution ended without a result."
                    );
                }
            } else if (isTerminal(submitted.status())) {
                return coordinatorInfrastructureFailure(
                    submitted,
                    "The coordinator execution could not be read."
                );
            }

            if (System.nanoTime() >= waitDeadline) {
                delegationCoordinatorClient.cancel(
                    submitted.invocationId(),
                    invocation.trustedExecutionContext()
                );
                return coordinatorInfrastructureFailure(
                    new ExecutionHandle(
                        submitted.invocationId(),
                        submitted.durability(),
                        ExecutionHandleStatus.EXPIRED,
                        submitted.deadline(),
                        submitted.expiresAt(),
                        "COORDINATOR_WAIT_TIMEOUT"
                    ),
                    "The coordinator execution exceeded the application wait limit."
                );
            }
            if (Thread.currentThread().isInterrupted()) {
                delegationCoordinatorClient.cancel(
                    submitted.invocationId(),
                    invocation.trustedExecutionContext()
                );
                Thread.currentThread().interrupt();
                return coordinatorInfrastructureFailure(
                    new ExecutionHandle(
                        submitted.invocationId(),
                        submitted.durability(),
                        ExecutionHandleStatus.CANCELLED,
                        submitted.deadline(),
                        submitted.expiresAt(),
                        "COORDINATOR_WAIT_INTERRUPTED"
                    ),
                    "The coordinator wait was interrupted."
                );
            }
            LockSupport.parkNanos(COORDINATOR_POLL_NANOS);
        }
    }

    private boolean isTerminal(ExecutionHandleStatus status) {
        return status == ExecutionHandleStatus.SUCCEEDED
            || status == ExecutionHandleStatus.FAILED
            || status == ExecutionHandleStatus.CANCELLED
            || status == ExecutionHandleStatus.REJECTED
            || status == ExecutionHandleStatus.EXPIRED;
    }

    private AIExecutionResult<AccountDelegationDecision>
    coordinatorInfrastructureFailure(
        ExecutionHandle handle,
        String publicMessage
    ) {
        String reason = handle.failureReason() != null
            ? handle.failureReason()
            : "COORDINATOR_RESULT_UNAVAILABLE";
        AIExecutionStatus status = switch (handle.status()) {
            case CANCELLED -> AIExecutionStatus.CANCELLED;
            case EXPIRED -> AIExecutionStatus.DEADLINE_EXCEEDED;
            case REJECTED -> AIExecutionStatus.INVALID;
            default -> AIExecutionStatus.FAILED;
        };
        Instant now = clock.instant();
        return new AIExecutionResult<>(
            handle.invocationId(),
            AccountResolverSpecialists.DELEGATION_COORDINATOR_ID,
            status,
            null,
            java.util.List.of(),
            java.util.Map.of("phase", "coordinator_submission"),
            new AIExecutionFailure(
                reason,
                publicMessage,
                status == AIExecutionStatus.DEADLINE_EXCEEDED
            ),
            now,
            now
        );
    }

    public PlanExecutionResumeResult<AccountBillingResolutionPlanResult>
    resumeAccountBillingPlan(
        String sessionId,
        PlanInputResumeRequest request,
        String idempotencyKey
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return executionCoordinator.resume(
            new PlanExecutionResumeRequest(
                request.executionId(),
                request.requestId(),
                request.response(),
                trustedContext(
                    session,
                    ExecutionSource.APPLICATION,
                    ACCOUNT_BILLING_PLAN_SCOPES
                ),
                requireIdempotencyKey(idempotencyKey)
            )
        );
    }

    public Optional<PlanExecutionSnapshot> findPlanExecution(
        String sessionId,
        String executionId
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return executionCoordinator.find(
            executionId,
            trustedContext(
                session,
                ExecutionSource.APPLICATION,
                ACCOUNT_BILLING_PLAN_SCOPES
            )
        );
    }

    public boolean cancelPlanExecution(
        String sessionId,
        String executionId
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return executionCoordinator.cancel(
            executionId,
            trustedContext(
                session,
                ExecutionSource.APPLICATION,
                ACCOUNT_BILLING_PLAN_SCOPES
            )
        );
    }

    private TrustedExecutionContext trustedContext(
        AgenticResolverSessionService.ActiveSession session,
        ExecutionSource source
    ) {
        return trustedContext(
            session,
            source,
            source == ExecutionSource.INTERACTIVE
                ? INTERACTIVE_SCOPES
                : READ_SCOPES
        );
    }

    private TrustedExecutionContext trustedContext(
        AgenticResolverSessionService.ActiveSession session,
        ExecutionSource source,
        Set<String> scopes
    ) {
        ExecutionPrincipal principal = source == ExecutionSource.INTERACTIVE
            ? new ExecutionPrincipal(
                session.conversationOwnerId(),
                ExecutionPrincipalType.END_USER
            )
            : new ExecutionPrincipal(
                "agentic-account-resolver",
                ExecutionPrincipalType.SERVICE
            );
        return new TrustedExecutionContext(
            principal,
            new ExecutionSubjectRef(
                "account",
                session.subjectUserId().toString()
            ),
            source,
            "public-demo",
            "agentic-ai-action-resolver",
            scopes,
            null,
            clock.instant()
        );
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 200) {
            throw new IllegalArgumentException(
                "Idempotency-Key must not exceed 200 characters"
            );
        }
        return normalized;
    }

    private String requireIdempotencyKey(String value) {
        String normalized = normalizeIdempotencyKey(value);
        if (normalized == null) {
            throw new IllegalArgumentException(
                "Idempotency-Key is required to resume an input request"
            );
        }
        return normalized;
    }
}
