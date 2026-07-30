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
import ai.fabric.execution.gateway.AIInteractiveExecutionGateway;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.handoff.SpecialistHandoffGateway;
import ai.fabric.execution.handoff.SpecialistHandoffRequest;
import ai.fabric.execution.handoff.SpecialistHandoffResult;
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

    private static final Duration SPECIALIST_WAIT_LIMIT =
        Duration.ofSeconds(65);
    private static final long SPECIALIST_POLL_NANOS =
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
    private static final Set<String> HANDOFF_SCOPES = Set.of(
        "specialist:account-resolution-intake@1",
        "specialist:account-resolver-read@1",
        "specialist:billing-resolution-advisor@1",
        "action:get_account_profile",
        "action:assess_billing_resolution",
        "vector:account-resolution-policy"
    );

    private final SpecialistClient<
        AccountResolutionRequest,
        AccountResolutionResult
    > readClient;
    private final SpecialistClient<
        AccountResolutionRequest,
        AccountResolutionResult
    > dialogueClient;
    private final SpecialistClient<
        BillingResolutionAssessmentRequest,
        BillingResolutionAssessmentResult
    > billingAdvisorClient;
    private final SpecialistClient<
        AccountDelegationCoordinatorRequest,
        AccountDelegationDecision
    > delegationCoordinatorClient;
    private final SpecialistClient<
        AccountHandoffIntakeRequest,
        AccountHandoffDecision
    > handoffIntakeClient;
    private final SpecialistDelegationGateway delegationGateway;
    private final SpecialistHandoffGateway handoffGateway;
    private final AIInteractiveExecutionGateway interactiveExecutionGateway;
    private final AIExecutionCoordinator executionCoordinator;
    private final ActionProposalCoordinator actionProposalCoordinator;
    private final AgenticResolverSessionService sessionService;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AgenticResolverExecutionService(
        SpecialistClientFactory specialistClientFactory,
        AIInteractiveExecutionGateway interactiveExecutionGateway,
        AIExecutionCoordinator executionCoordinator,
        ActionProposalCoordinator actionProposalCoordinator,
        AgenticResolverSessionService sessionService,
        SpecialistDelegationGateway delegationGateway,
        SpecialistHandoffGateway handoffGateway,
        Clock clock
    ) {
        this.readClient = specialistClientFactory.bind(
            AccountResolverSpecialists.READ_SPECIALIST_ID,
            AccountResolutionRequest.class,
            AccountResolutionResult.class
        );
        this.dialogueClient = specialistClientFactory.bind(
            AccountResolverSpecialists.SPECIALIST_ID,
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
        this.handoffIntakeClient = specialistClientFactory.bind(
            AccountResolverSpecialists.HANDOFF_INTAKE_ID,
            AccountHandoffIntakeRequest.class,
            AccountHandoffDecision.class
        );
        this.delegationGateway = delegationGateway;
        this.handoffGateway = handoffGateway;
        this.interactiveExecutionGateway = interactiveExecutionGateway;
        this.executionCoordinator = executionCoordinator;
        this.actionProposalCoordinator = actionProposalCoordinator;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    AgenticResolverExecutionService(
        SpecialistClientFactory specialistClientFactory,
        AIInteractiveExecutionGateway interactiveExecutionGateway,
        AIExecutionCoordinator executionCoordinator,
        ActionProposalCoordinator actionProposalCoordinator,
        AgenticResolverSessionService sessionService,
        SpecialistDelegationGateway delegationGateway,
        Clock clock
    ) {
        this(
            specialistClientFactory,
            interactiveExecutionGateway,
            executionCoordinator,
            actionProposalCoordinator,
            sessionService,
            delegationGateway,
            null,
            clock
        );
    }

    AgenticResolverExecutionService(
        SpecialistClientFactory specialistClientFactory,
        AIInteractiveExecutionGateway interactiveExecutionGateway,
        AIExecutionCoordinator executionCoordinator,
        ActionProposalCoordinator actionProposalCoordinator,
        AgenticResolverSessionService sessionService,
        Clock clock
    ) {
        this(
            specialistClientFactory,
            interactiveExecutionGateway,
            executionCoordinator,
            actionProposalCoordinator,
            sessionService,
            null,
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
        AccountResolutionRequest request,
        String idempotencyKey
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return dialogueClient.executeInteractive(
            new SpecialistInvocation<>(
                request,
                trustedContext(session, ExecutionSource.INTERACTIVE),
                new ConversationBinding(
                    session.conversationOwnerId(),
                    session.conversationId()
                ),
                null,
                requireIdempotencyKey(idempotencyKey)
            ),
            interactiveExecutionGateway
        );
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
        return executeAccountBillingPlan(
            AccountResolverPlans.ACCOUNT_BILLING_RESOLUTION,
            sessionId,
            request,
            idempotencyKey
        );
    }

    public PlanExecutionResult<AccountBillingResolutionPlanResult>
    executeIndependentSequentialBillingPlan(
        String sessionId,
        AccountBillingResolutionPlanRequest request,
        String idempotencyKey
    ) {
        return executeAccountBillingPlan(
            AccountResolverPlans.ACCOUNT_BILLING_INDEPENDENT_SEQUENTIAL,
            sessionId,
            request,
            idempotencyKey
        );
    }

    public PlanExecutionResult<AccountBillingResolutionPlanResult>
    executeIndependentParallelBillingPlan(
        String sessionId,
        AccountBillingResolutionPlanRequest request,
        String idempotencyKey
    ) {
        return executeAccountBillingPlan(
            AccountResolverPlans.ACCOUNT_BILLING_INDEPENDENT_PARALLEL,
            sessionId,
            request,
            idempotencyKey
        );
    }

    private PlanExecutionResult<AccountBillingResolutionPlanResult>
    executeAccountBillingPlan(
        ai.fabric.execution.plan.ExecutionPlanId planId,
        String sessionId,
        AccountBillingResolutionPlanRequest request,
        String idempotencyKey
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return executionCoordinator.execute(new PlanExecutionRequest<>(
            planId,
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
            submitSpecialistAndAwait(
                delegationCoordinatorClient,
                invocation,
                AccountResolverSpecialists.DELEGATION_COORDINATOR_ID,
                "COORDINATOR",
                "coordinator"
            );
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

    public AccountHandoffResponse handoffAccountResolution(
        String sessionId,
        AccountHandoffIntakeRequest request,
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
            HANDOFF_SCOPES
        );
        SpecialistInvocation<AccountHandoffIntakeRequest> invocation =
            new SpecialistInvocation<>(
                request,
                trustedContext,
                null,
                null,
                requiredIdempotencyKey
            );
        AIExecutionResult<AccountHandoffDecision> predecessor =
            submitSpecialistAndAwait(
                handoffIntakeClient,
                invocation,
                AccountResolverSpecialists.HANDOFF_INTAKE_ID,
                "INTAKE",
                "intake"
            );
        if (!predecessor.succeeded()
            || predecessor.output().decision()
                != AccountHandoffDecision.Decision.HANDOFF) {
            return new AccountHandoffResponse(predecessor, null);
        }

        SpecialistId target = SpecialistId.parse(
            predecessor.output().targetSpecialist()
        );
        SpecialistHandoffResult<AccountHandoffDecision, ?> handoff;
        if (target.equals(AccountResolverSpecialists.READ_SPECIALIST_ID)) {
            handoff = handoffGateway.handoff(
                new SpecialistHandoffRequest<>(
                    predecessor,
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
            handoff = handoffGateway.handoff(
                new SpecialistHandoffRequest<>(
                    predecessor,
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
                "Validated intake selected an unsupported successor"
            );
        }
        return new AccountHandoffResponse(predecessor, handoff);
    }

    private <I, O> AIExecutionResult<O> submitSpecialistAndAwait(
        SpecialistClient<I, O> client,
        SpecialistInvocation<I> invocation,
        SpecialistId specialistId,
        String roleCode,
        String roleLabel
    ) {
        ExecutionHandle submitted = client.submit(invocation);
        long waitDeadline = System.nanoTime()
            + SPECIALIST_WAIT_LIMIT.toNanos();
        while (true) {
            Optional<SpecialistExecutionSnapshot<O>> snapshot = client.find(
                submitted.invocationId(),
                invocation.trustedExecutionContext()
            );
            if (snapshot.isPresent()) {
                SpecialistExecutionSnapshot<O> value = snapshot.get();
                if (value.result() != null) {
                    return value.result();
                }
                if (isTerminal(value.handle().status())) {
                    return specialistInfrastructureFailure(
                        value.handle(),
                        specialistId,
                        roleCode,
                        roleLabel,
                        "The " + roleLabel
                            + " execution ended without a result."
                    );
                }
            } else if (isTerminal(submitted.status())) {
                return specialistInfrastructureFailure(
                    submitted,
                    specialistId,
                    roleCode,
                    roleLabel,
                    "The " + roleLabel
                        + " execution could not be read."
                );
            }

            if (System.nanoTime() >= waitDeadline) {
                client.cancel(
                    submitted.invocationId(),
                    invocation.trustedExecutionContext()
                );
                return specialistInfrastructureFailure(
                    new ExecutionHandle(
                        submitted.invocationId(),
                        submitted.durability(),
                        ExecutionHandleStatus.EXPIRED,
                        submitted.deadline(),
                        submitted.expiresAt(),
                        roleCode + "_WAIT_TIMEOUT"
                    ),
                    specialistId,
                    roleCode,
                    roleLabel,
                    "The " + roleLabel
                        + " execution exceeded the application wait limit."
                );
            }
            if (Thread.currentThread().isInterrupted()) {
                client.cancel(
                    submitted.invocationId(),
                    invocation.trustedExecutionContext()
                );
                Thread.currentThread().interrupt();
                return specialistInfrastructureFailure(
                    new ExecutionHandle(
                        submitted.invocationId(),
                        submitted.durability(),
                        ExecutionHandleStatus.CANCELLED,
                        submitted.deadline(),
                        submitted.expiresAt(),
                        roleCode + "_WAIT_INTERRUPTED"
                    ),
                    specialistId,
                    roleCode,
                    roleLabel,
                    "The " + roleLabel + " wait was interrupted."
                );
            }
            LockSupport.parkNanos(SPECIALIST_POLL_NANOS);
        }
    }

    private boolean isTerminal(ExecutionHandleStatus status) {
        return status == ExecutionHandleStatus.SUCCEEDED
            || status == ExecutionHandleStatus.FAILED
            || status == ExecutionHandleStatus.CANCELLED
            || status == ExecutionHandleStatus.REJECTED
            || status == ExecutionHandleStatus.EXPIRED;
    }

    private <O> AIExecutionResult<O> specialistInfrastructureFailure(
        ExecutionHandle handle,
        SpecialistId specialistId,
        String roleCode,
        String roleLabel,
        String publicMessage
    ) {
        String reason = handle.failureReason() != null
            ? handle.failureReason()
            : roleCode + "_RESULT_UNAVAILABLE";
        AIExecutionStatus status = switch (handle.status()) {
            case CANCELLED -> AIExecutionStatus.CANCELLED;
            case EXPIRED -> AIExecutionStatus.DEADLINE_EXCEEDED;
            case REJECTED -> AIExecutionStatus.INVALID;
            default -> AIExecutionStatus.FAILED;
        };
        Instant now = clock.instant();
        return new AIExecutionResult<>(
            handle.invocationId(),
            specialistId,
            status,
            null,
            java.util.List.of(),
            java.util.Map.of(
                "phase",
                roleLabel + "_submission"
            ),
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
                "Idempotency-Key is required"
            );
        }
        return normalized;
    }
}
