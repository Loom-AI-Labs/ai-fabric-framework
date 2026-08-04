package com.ai.fabric.realapps.incident.service;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.ConversationBinding;
import ai.fabric.execution.manager.ConversationManagerGateway;
import ai.fabric.execution.manager.ConversationManagerTurnRequest;
import ai.fabric.execution.manager.ConversationManagerTurnResult;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import com.ai.fabric.realapps.incident.execution.IncidentConversationManagers;
import java.time.Clock;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class IncidentConversationService {

    private static final Set<String> MANAGER_SCOPES = Set.of(
        "specialist:incident-conversation-manager@1",
        "specialist:service-health-reader@1",
        "specialist:change-risk-reader@1"
    );

    private final ConversationManagerGateway gateway;
    private final IncidentSessionService sessions;
    private final Clock clock;

    public IncidentConversationService(
        ConversationManagerGateway gateway,
        IncidentSessionService sessions,
        Clock clock
    ) {
        this.gateway = gateway;
        this.sessions = sessions;
        this.clock = clock;
    }

    public ConversationManagerTurnResult chat(
        String sessionId,
        String question,
        String idempotencyKey
    ) {
        IncidentSessionService.ActiveSession session = sessions.active(sessionId);
        IncidentManagerRequest request = new IncidentManagerRequest(
            question,
            sessions.planRequest(sessionId, question)
        );
        return gateway.execute(new ConversationManagerTurnRequest<>(
            IncidentConversationManagers.INVESTIGATION,
            request,
            trustedContext(session),
            new ConversationBinding(
                session.ownerId(),
                session.conversationId()
            ),
            null,
            requireIdempotencyKey(idempotencyKey)
        ));
    }

    private TrustedExecutionContext trustedContext(
        IncidentSessionService.ActiveSession session
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                session.ownerId(),
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef(
                "incident",
                session.scenario().id()
            ),
            ExecutionSource.INTERACTIVE,
            "public-demo",
            "incident-investigation-room",
            MANAGER_SCOPES,
            null,
            clock.instant()
        );
    }

    private String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 160) {
            throw new IllegalArgumentException(
                "Idempotency-Key must not exceed 160 characters"
            );
        }
        return normalized;
    }
}
