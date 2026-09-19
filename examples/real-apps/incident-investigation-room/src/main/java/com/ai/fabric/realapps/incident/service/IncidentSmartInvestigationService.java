package com.ai.fabric.realapps.incident.service;

import ai.fabric.execution.chain.SpecialistChainExecutionRequest;
import ai.fabric.execution.chain.SpecialistChainExecutionResult;
import ai.fabric.execution.chain.SpecialistChainExecutionSnapshot;
import ai.fabric.execution.chain.SpecialistChainGateway;
import ai.fabric.execution.chain.SpecialistChainId;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.ConversationBinding;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import com.ai.fabric.realapps.incident.domain.IncidentSmartInvestigationExecutionView;
import com.ai.fabric.realapps.incident.domain.IncidentSmartInvestigationView;
import com.ai.fabric.realapps.incident.execution.IncidentSpecialistChains;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class IncidentSmartInvestigationService {

    private static final Set<String> CHAIN_SCOPES = Set.of(
        "specialist:incident-chain-manager@3",
        "specialist:service-health-reader@2",
        "specialist:change-risk-reader@2",
        "action:read_service_metrics",
        "action:read_incident_alerts",
        "action:read_recent_deployments",
        "action:read_change_approvals",
        "vector:incident-runbook"
    );

    private final SpecialistChainGateway gateway;
    private final IncidentSessionService sessions;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public IncidentSmartInvestigationService(
        SpecialistChainGateway gateway,
        IncidentSessionService sessions,
        Clock clock,
        ObjectMapper objectMapper
    ) {
        this.gateway = gateway;
        this.sessions = sessions;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public IncidentSmartInvestigationView investigate(
        String sessionId,
        String question,
        String idempotencyKey
    ) {
        return IncidentSmartInvestigationView.from(gateway.execute(
            request(sessionId, question, idempotencyKey)
        ));
    }

    public IncidentSmartInvestigationView investigateDeclarative(
        String sessionId,
        String question,
        String idempotencyKey
    ) {
        return IncidentSmartInvestigationView.from(gateway.execute(
            declarativeRequest(sessionId, question, idempotencyKey)
        ));
    }

    public IncidentSmartInvestigationExecutionView submit(
        String sessionId,
        String question,
        String idempotencyKey
    ) {
        return submit(request(sessionId, question, idempotencyKey));
    }

    public IncidentSmartInvestigationExecutionView submitDeclarative(
        String sessionId,
        String question,
        String idempotencyKey
    ) {
        return submit(declarativeRequest(
            sessionId,
            question,
            idempotencyKey
        ));
    }

    private <I> IncidentSmartInvestigationExecutionView submit(
        SpecialistChainExecutionRequest<I> request
    ) {
        var handle = gateway.submit(request);
        if (!handle.replayed() || !handle.status().terminal()) {
            return IncidentSmartInvestigationExecutionView.from(handle);
        }
        SpecialistChainExecutionSnapshot snapshot = gateway.find(
            handle.executionId(),
            request.trustedExecutionContext()
        ).orElseThrow(() -> new IllegalStateException(
            "The replayed smart investigation state is unavailable"
        ));
        SpecialistChainExecutionResult result = gateway.findResult(
            handle.executionId(),
            request.trustedExecutionContext()
        ).orElseThrow(() -> new IllegalStateException(
            "The replayed smart investigation result is unavailable"
        ));
        return IncidentSmartInvestigationExecutionView.from(
            snapshot,
            result.asReplayed()
        );
    }

    public IncidentSmartInvestigationExecutionView status(
        String sessionId,
        String executionId
    ) {
        IncidentSessionService.ActiveSession session = sessions.active(
            sessionId
        );
        TrustedExecutionContext context = trustedContext(session);
        SpecialistChainExecutionSnapshot snapshot = gateway.find(
            executionId,
            context
        ).orElseThrow(() -> new IllegalArgumentException(
            "Smart investigation execution was not found"
        ));
        SpecialistChainExecutionResult result = snapshot.status().terminal()
            ? gateway.findResult(executionId, context).orElse(null)
            : null;
        if (snapshot.status().terminal() && result == null) {
            throw new IllegalStateException(
                "The terminal smart investigation result could not be verified"
            );
        }
        return IncidentSmartInvestigationExecutionView.from(
            snapshot,
            result
        );
    }

    public IncidentSmartInvestigationExecutionView cancel(
        String sessionId,
        String executionId
    ) {
        IncidentSessionService.ActiveSession session = sessions.active(
            sessionId
        );
        TrustedExecutionContext context = trustedContext(session);
        if (!gateway.cancel(executionId, context)) {
            throw new IllegalArgumentException(
                "Smart investigation execution is missing or already terminal"
            );
        }
        return status(sessionId, executionId);
    }

    private SpecialistChainExecutionRequest<IncidentManagerRequest> request(
        String sessionId,
        String question,
        String idempotencyKey
    ) {
        IncidentSessionService.ActiveSession session = sessions.active(
            sessionId
        );
        IncidentManagerRequest input = new IncidentManagerRequest(
            question,
            sessions.planRequest(sessionId, question)
        );
        return new SpecialistChainExecutionRequest<>(
            IncidentSpecialistChains.SMART_INVESTIGATION,
            input,
            trustedContext(session),
            new ConversationBinding(
                session.ownerId(),
                session.conversationId()
            ),
            null,
            requireIdempotencyKey(idempotencyKey)
        );
    }

    private SpecialistChainExecutionRequest<JsonNode> declarativeRequest(
        String sessionId,
        String question,
        String idempotencyKey
    ) {
        IncidentSessionService.ActiveSession session = sessions.active(
            sessionId
        );
        var plan = sessions.planRequest(sessionId, question);
        ObjectNode input = objectMapper.createObjectNode()
            .put("question", question)
            .put("incidentId", plan.incidentId())
            .put("deploymentId", plan.deploymentId())
            .put("sourceRevision", plan.sourceRevision());
        return chainRequest(
            IncidentSpecialistChains.DECLARATIVE_INVESTIGATION,
            input,
            session,
            idempotencyKey
        );
    }

    private <I> SpecialistChainExecutionRequest<I> chainRequest(
        SpecialistChainId chainId,
        I input,
        IncidentSessionService.ActiveSession session,
        String idempotencyKey
    ) {
        return new SpecialistChainExecutionRequest<>(
            chainId,
            input,
            trustedContext(session),
            new ConversationBinding(
                session.ownerId(),
                session.conversationId()
            ),
            null,
            requireIdempotencyKey(idempotencyKey)
        );
    }

    private TrustedExecutionContext trustedContext(
        IncidentSessionService.ActiveSession session
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                session.ownerId(),
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef("incident", session.scenario().id()),
            ExecutionSource.INTERACTIVE,
            "public-demo",
            session.scenario().deploymentId(),
            CHAIN_SCOPES,
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
