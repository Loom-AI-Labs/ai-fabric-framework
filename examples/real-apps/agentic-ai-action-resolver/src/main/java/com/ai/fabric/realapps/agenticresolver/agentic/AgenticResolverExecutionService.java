package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.ConversationBinding;
import java.time.Clock;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AgenticResolverExecutionService {

    private static final Set<String> SPECIALIST_SCOPES = Set.of(
        "specialist:account-resolver@1",
        "action:get_account_profile",
        "vector:account-resolution-policy"
    );

    private final AIExecutionGateway executionGateway;
    private final AgenticResolverSessionService sessionService;
    private final Clock clock;

    public AgenticResolverExecutionService(
        AIExecutionGateway executionGateway,
        AgenticResolverSessionService sessionService,
        Clock clock
    ) {
        this.executionGateway = executionGateway;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    public AIExecutionResult<AccountResolutionResult> evaluate(
        String sessionId,
        AccountResolutionRequest request
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return executionGateway.execute(new AIExecutionRequest<>(
            AccountResolverSpecialistConfiguration.SPECIALIST_ID,
            request,
            trustedContext(session, ExecutionSource.APPLICATION),
            null,
            null,
            null
        ));
    }

    public AIExecutionResult<AccountResolutionResult> chat(
        String sessionId,
        AccountResolutionRequest request
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
            null
        ));
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
            SPECIALIST_SCOPES,
            null,
            clock.instant()
        );
    }
}
