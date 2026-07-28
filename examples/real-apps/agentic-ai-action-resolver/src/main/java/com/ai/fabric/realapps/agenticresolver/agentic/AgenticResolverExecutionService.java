package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalDecisionRequest;
import ai.fabric.execution.action.ActionProposalDecisionResult;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.ConversationBinding;
import java.time.Clock;
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

    private final AIExecutionGateway executionGateway;
    private final ActionProposalCoordinator actionProposalCoordinator;
    private final AgenticResolverSessionService sessionService;
    private final Clock clock;

    public AgenticResolverExecutionService(
        AIExecutionGateway executionGateway,
        ActionProposalCoordinator actionProposalCoordinator,
        AgenticResolverSessionService sessionService,
        Clock clock
    ) {
        this.executionGateway = executionGateway;
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
        return executionGateway.execute(new AIExecutionRequest<>(
            AccountResolverSpecialistConfiguration.READ_SPECIALIST_ID,
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
        return executionGateway.execute(new AIExecutionRequest<>(
            AccountResolverSpecialistConfiguration.SPECIALIST_ID,
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

    private TrustedExecutionContext trustedContext(
        AgenticResolverSessionService.ActiveSession session,
        ExecutionSource source
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
            source == ExecutionSource.INTERACTIVE
                ? INTERACTIVE_SCOPES
                : READ_SCOPES,
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
}
