package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.ConversationBinding;
import ai.fabric.execution.manager.ConversationManagerGateway;
import ai.fabric.execution.manager.ConversationManagerTurnRequest;
import ai.fabric.execution.manager.ConversationManagerTurnResult;
import java.time.Clock;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AccountConversationManagerService {

    private static final Set<String> MANAGER_SCOPES = Set.of(
        "specialist:account-conversation-manager@1",
        "specialist:account-resolver-manager-read@1",
        "specialist:billing-resolution-manager-advisor@1",
        "action:get_account_profile",
        "action:assess_billing_resolution",
        "vector:account-resolution-policy"
    );

    private final ConversationManagerGateway managerGateway;
    private final AgenticResolverSessionService sessionService;
    private final Clock clock;

    public AccountConversationManagerService(
        ConversationManagerGateway managerGateway,
        AgenticResolverSessionService sessionService,
        Clock clock
    ) {
        this.managerGateway = managerGateway;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    public ConversationManagerTurnResult chat(
        String sessionId,
        AccountDelegationCoordinatorRequest request,
        String idempotencyKey
    ) {
        AgenticResolverSessionService.ActiveSession session =
            sessionService.active(sessionId);
        return managerGateway.execute(
            new ConversationManagerTurnRequest<>(
                AccountConversationManagers.ACCOUNT_RESOLUTION,
                request,
                trustedContext(session),
                new ConversationBinding(
                    session.conversationOwnerId(),
                    session.conversationId()
                ),
                null,
                requireIdempotencyKey(idempotencyKey)
            )
        );
    }

    private TrustedExecutionContext trustedContext(
        AgenticResolverSessionService.ActiveSession session
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                session.conversationOwnerId(),
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef(
                "account",
                session.subjectUserId().toString()
            ),
            ExecutionSource.INTERACTIVE,
            "public-demo",
            "agentic-ai-action-resolver",
            MANAGER_SCOPES,
            null,
            clock.instant()
        );
    }

    private String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "Idempotency-Key is required"
            );
        }
        String normalized = value.trim();
        if (normalized.length() > 200) {
            throw new IllegalArgumentException(
                "Idempotency-Key must not exceed 200 characters"
            );
        }
        return normalized;
    }
}
