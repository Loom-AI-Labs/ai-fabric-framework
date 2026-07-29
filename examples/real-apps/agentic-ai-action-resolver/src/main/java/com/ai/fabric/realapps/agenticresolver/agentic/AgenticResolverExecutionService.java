package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalDecisionRequest;
import ai.fabric.execution.action.ActionProposalDecisionResult;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionResumeResult;
import ai.fabric.execution.gateway.ConversationBinding;
import ai.fabric.execution.plan.AIExecutionCoordinator;
import ai.fabric.execution.plan.PlanExecutionRequest;
import ai.fabric.execution.plan.PlanExecutionResult;
import ai.fabric.execution.plan.PlanExecutionResumeRequest;
import ai.fabric.execution.plan.PlanExecutionResumeResult;
import ai.fabric.execution.plan.PlanExecutionSnapshot;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import ai.fabric.execution.specialist.client.SpecialistResumeInvocation;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.AccountBillingResolutionPlanRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.AccountBillingResolutionPlanResult;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.AccountResolverPlans;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.PlanInputResumeRequest;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AgenticResolverExecutionService {

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
    private final AIExecutionCoordinator executionCoordinator;
    private final ActionProposalCoordinator actionProposalCoordinator;
    private final AgenticResolverSessionService sessionService;
    private final Clock clock;

    public AgenticResolverExecutionService(
        SpecialistClientFactory specialistClientFactory,
        AIExecutionCoordinator executionCoordinator,
        ActionProposalCoordinator actionProposalCoordinator,
        AgenticResolverSessionService sessionService,
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
        this.executionCoordinator = executionCoordinator;
        this.actionProposalCoordinator = actionProposalCoordinator;
        this.sessionService = sessionService;
        this.clock = clock;
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
