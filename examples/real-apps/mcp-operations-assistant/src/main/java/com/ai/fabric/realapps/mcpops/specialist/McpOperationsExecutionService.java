package com.ai.fabric.realapps.mcpops.specialist;

import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalDecisionRequest;
import ai.fabric.execution.action.ActionProposalDecisionResult;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIInteractiveExecutionGateway;
import ai.fabric.execution.gateway.ConversationBinding;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import com.ai.fabric.realapps.mcpops.service.McpDemoSessionService;
import com.ai.fabric.realapps.mcpops.service.McpInvocationAuditService;
import com.ai.fabric.realapps.mcpops.service.McpOperationsService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class McpOperationsExecutionService {

    private static final Set<String> SCOPES = Set.of(
        "specialist:mcp-operations-specialist@1",
        "action:get_sandbox_service_status",
        "action:list_recent_sandbox_incidents",
        "action:restart_sandbox_service"
    );

    private final SpecialistClient<
        McpOperationsSpecialistInput,
        McpOperationsSpecialistResult
    > client;
    private final AIInteractiveExecutionGateway interactiveGateway;
    private final ActionProposalCoordinator proposals;
    private final McpDemoSessionService sessions;
    private final McpOperationsService operations;
    private final McpInvocationAuditService audits;
    private final ChatSessionService chatSessions;
    private final Clock clock;

    public McpOperationsExecutionService(
        SpecialistClientFactory clients,
        AIInteractiveExecutionGateway interactiveGateway,
        ActionProposalCoordinator proposals,
        McpDemoSessionService sessions,
        McpOperationsService operations,
        McpInvocationAuditService audits,
        ChatSessionService chatSessions,
        Clock clock
    ) {
        this.client = clients.bind(
            McpOperationsSpecialists.OPERATIONS,
            McpOperationsSpecialistInput.class,
            McpOperationsSpecialistResult.class
        );
        this.interactiveGateway = interactiveGateway;
        this.proposals = proposals;
        this.sessions = sessions;
        this.operations = operations;
        this.audits = audits;
        this.chatSessions = chatSessions;
        this.clock = clock;
    }

    public TurnResponse chat(
        String sessionId,
        ChatRequest request,
        String idempotencyKey
    ) {
        String message = requireMessage(request != null ? request.message() : null);
        String requiredKey = requireIdempotencyKey(idempotencyKey);
        McpDemoSessionService.ActiveSession session = sessions.active(sessionId);
        AIExecutionResult<McpOperationsSpecialistResult> execution =
            client.executeInteractive(
                new SpecialistInvocation<>(
                    new McpOperationsSpecialistInput(
                        message,
                        session.serviceName()
                    ),
                    trustedContext(session),
                    new ConversationBinding(
                        session.sessionId(),
                        session.conversationId()
                    ),
                    null,
                    requiredKey
                ),
                interactiveGateway
            );
        return new TurnResponse(
            execution,
            audits.timeline(session.sessionId())
        );
    }

    public DecisionResponse decide(
        String sessionId,
        ActionProposalDecisionRequest request
    ) {
        McpDemoSessionService.ActiveSession session = sessions.active(sessionId);
        ActionProposalDecisionResult decision = proposals.decide(
            request,
            trustedContext(session)
        );
        return new DecisionResponse(
            decision,
            audits.timeline(session.sessionId()),
            operations.currentStatus(session.sessionId())
        );
    }

    public List<ConversationMessage> history(String sessionId) {
        McpDemoSessionService.ActiveSession session = sessions.active(sessionId);
        try {
            return chatSessions.getConversationMessages(
                    session.conversationId(),
                    session.sessionId()
                ).stream()
                .map(message -> new ConversationMessage(
                    message.getRole() != null
                        ? message.getRole().name()
                        : "UNKNOWN",
                    message.getContent()
                ))
                .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private TrustedExecutionContext trustedContext(
        McpDemoSessionService.ActiveSession session
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                session.sessionId(),
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef("sandbox", session.sessionId()),
            ExecutionSource.INTERACTIVE,
            "public-demo",
            "mcp-operations-assistant",
            SCOPES,
            null,
            clock.instant()
        );
    }

    private String requireMessage(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("message is required.");
        }
        String normalized = value.trim();
        if (normalized.length() > 2_000) {
            throw new IllegalArgumentException(
                "message must not exceed 2000 characters."
            );
        }
        return normalized;
    }

    private String requireIdempotencyKey(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Idempotency-Key is required.");
        }
        String normalized = value.trim();
        if (normalized.length() > 200) {
            throw new IllegalArgumentException(
                "Idempotency-Key must not exceed 200 characters."
            );
        }
        return normalized;
    }

    public record ChatRequest(
        @NotBlank @Size(max = 2_000) String message
    ) {
    }

    public record TurnResponse(
        AIExecutionResult<McpOperationsSpecialistResult> execution,
        List<McpInvocationAuditService.AuditView> timeline
    ) {
    }

    public record DecisionResponse(
        ActionProposalDecisionResult decision,
        List<McpInvocationAuditService.AuditView> timeline,
        Map<String, Object> currentStatus
    ) {
    }

    public record ConversationMessage(String role, String content) {
    }
}
