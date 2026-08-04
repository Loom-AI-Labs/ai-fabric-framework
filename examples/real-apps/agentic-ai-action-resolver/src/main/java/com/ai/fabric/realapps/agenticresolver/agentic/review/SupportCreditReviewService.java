package com.ai.fabric.realapps.agenticresolver.agentic.review;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.review.ActionReviewRequest;
import ai.fabric.execution.review.ReviewDecisionGateway;
import ai.fabric.execution.review.ReviewTaskCreationResult;
import ai.fabric.execution.review.ReviewTaskDetailView;
import ai.fabric.execution.review.ReviewTaskView;
import ai.fabric.execution.review.TrustedReviewerContext;
import ai.fabric.execution.review.decision.ReviewDecisionFailure;
import ai.fabric.execution.review.decision.ReviewDecisionRequest;
import ai.fabric.execution.review.decision.ReviewDecisionResult;
import ai.fabric.execution.review.input.ReviewInformationResult;
import ai.fabric.execution.review.input.ReviewInformationSubmission;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolverSpecialists;
import com.ai.fabric.realapps.agenticresolver.agentic.AgenticResolverSessionService;
import com.ai.fabric.realapps.agenticresolver.agentic.SupportCreditProposalOutput;
import com.ai.fabric.realapps.agenticresolver.agentic.SupportCreditReviewRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.SupportCreditReviewSubmissionResult;
import com.ai.fabric.realapps.agenticresolver.entity.DemoReviewTaskBinding;
import com.ai.fabric.realapps.agenticresolver.repository.DemoReviewTaskBindingRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
    prefix = "ai.execution.reviews",
    name = "enabled",
    havingValue = "true"
)
public class SupportCreditReviewService {

    private static final Set<String> PROPOSAL_SCOPES = Set.of(
        "specialist:support-credit-proposer@1",
        "action:request_refund",
        "vector:account-resolution-policy"
    );

    private final SpecialistClient<
        SupportCreditReviewRequest,
        SupportCreditProposalOutput
    > proposalClient;
    private final ReviewDecisionGateway reviews;
    private final AgenticResolverSessionService sessions;
    private final DemoReviewTaskBindingRepository taskBindings;
    private final Clock clock;

    public SupportCreditReviewService(
        SpecialistClientFactory clients,
        ReviewDecisionGateway reviews,
        AgenticResolverSessionService sessions,
        DemoReviewTaskBindingRepository taskBindings,
        Clock clock
    ) {
        this.proposalClient = clients.bind(
            AccountResolverSpecialists.SUPPORT_CREDIT_SPECIALIST_ID,
            SupportCreditReviewRequest.class,
            SupportCreditProposalOutput.class
        );
        this.reviews = reviews;
        this.sessions = sessions;
        this.taskBindings = taskBindings;
        this.clock = clock;
    }

    public SupportCreditReviewSubmissionResult propose(
        String sessionId,
        SupportCreditReviewRequest request,
        String idempotencyKey
    ) {
        String key = requireIdempotencyKey(idempotencyKey);
        AgenticResolverSessionService.ActiveSession session =
            sessions.active(sessionId);
        TrustedExecutionContext sourceContext = sourceContext(session);
        AIExecutionResult<SupportCreditProposalOutput> proposal =
            proposalClient.execute(new SpecialistInvocation<>(
                request,
                sourceContext,
                null,
                null,
                key
            ));
        if (proposal.status()
            != AIExecutionStatus.CONFIRMATION_REQUIRED
            || proposal.actionProposal() == null) {
            return new SupportCreditReviewSubmissionResult(
                proposal.invocationId(),
                proposal.status(),
                null,
                false,
                proposal.evidence(),
                proposal.failure(),
                proposal.failure() == null
                    ? new ReviewDecisionFailure(
                        "REVIEW_PROPOSAL_NOT_CREATED",
                        "The AI specialist did not produce a governed action proposal.",
                        false
                    )
                    : null
            );
        }
        ReviewTaskCreationResult review = reviews.createActionReview(
            new ActionReviewRequest(
                proposal.actionProposal().receiptId(),
                SupportCreditReviewPolicies.STANDARD,
                "Review a billing resolution",
                summary(request),
                "support-credit-review:" + key
            ),
            sourceContext
        );
        if (review.task() != null) {
            bindTask(sessionId, review.task().taskId());
        }
        return new SupportCreditReviewSubmissionResult(
            proposal.invocationId(),
            proposal.status(),
            review.task(),
            review.dispatchAccepted(),
            proposal.evidence(),
            proposal.failure(),
            review.failure()
        );
    }

    public List<ReviewTaskView> inbox(
        TrustedReviewerContext reviewer,
        int limit
    ) {
        return reviews.inbox(reviewer, limit);
    }

    public Optional<ReviewTaskDetailView> detail(
        String taskId,
        TrustedReviewerContext reviewer
    ) {
        return reviews.findDetail(taskId, reviewer);
    }

    public ReviewDecisionResult decide(
        ReviewDecisionRequest request,
        TrustedReviewerContext reviewer
    ) {
        return reviews.decide(request, reviewer);
    }

    public ReviewInformationResult provideInformation(
        String sessionId,
        ReviewInformationSubmission submission
    ) {
        return reviews.provideInformation(
            submission,
            sourceContext(sessions.active(sessionId))
        );
    }

    private TrustedExecutionContext sourceContext(
        AgenticResolverSessionService.ActiveSession session
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                "agentic-account-resolver",
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef(
                "account",
                session.subjectUserId().toString()
            ),
            ExecutionSource.APPLICATION,
            "public-demo",
            "agentic-ai-action-resolver",
            PROPOSAL_SCOPES,
            null,
            clock.instant()
        );
    }

    private String summary(SupportCreditReviewRequest request) {
        return "AI proposed "
            + request.resolutionType().name()
            + " for $"
            + request.amount().toPlainString()
            + ". Reason: "
            + request.reason().trim();
    }

    private void bindTask(String sessionId, String taskId) {
        DemoReviewTaskBinding existing = taskBindings.findById(taskId)
            .orElse(null);
        if (existing == null) {
            taskBindings.save(new DemoReviewTaskBinding(
                taskId,
                sessionId,
                clock.instant()
            ));
            return;
        }
        if (!existing.demoSessionId().equals(sessionId)) {
            throw new IllegalStateException(
                "Review task is already bound to another demo session"
            );
        }
    }

    private String requireIdempotencyKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException(
                "Idempotency-Key must contain 1 to 160 characters"
            );
        }
        return normalized;
    }
}
