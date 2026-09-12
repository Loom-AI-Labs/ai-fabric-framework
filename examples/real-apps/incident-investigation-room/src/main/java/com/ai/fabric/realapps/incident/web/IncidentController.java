package com.ai.fabric.realapps.incident.web;

import com.ai.fabric.realapps.incident.domain.AuthorizedIncidentScope;
import com.ai.fabric.realapps.incident.domain.IncidentEvent;
import com.ai.fabric.realapps.incident.domain.IncidentInvestigationPlanComparison;
import com.ai.fabric.realapps.incident.domain.IncidentManagerTurnView;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRunView;
import com.ai.fabric.realapps.incident.domain.IncidentScenario;
import com.ai.fabric.realapps.incident.domain.IncidentTransitionResponse;
import com.ai.fabric.realapps.incident.execution.IncidentPlans;
import com.ai.fabric.realapps.incident.execution.IncidentSpecialists;
import com.ai.fabric.realapps.incident.service.IncidentConversationService;
import com.ai.fabric.realapps.incident.service.IncidentExecutionService;
import com.ai.fabric.realapps.incident.service.IncidentEventRepository;
import com.ai.fabric.realapps.incident.service.IncidentRunbookIndexService;
import com.ai.fabric.realapps.incident.service.IncidentScenarioCatalog;
import com.ai.fabric.realapps.incident.service.IncidentSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentScenarioCatalog catalog;
    private final IncidentSessionService sessions;
    private final IncidentExecutionService execution;
    private final IncidentConversationService conversations;
    private final IncidentEventRepository events;
    private final IncidentRunbookIndexService runbooks;

    public IncidentController(
        IncidentScenarioCatalog catalog,
        IncidentSessionService sessions,
        IncidentExecutionService execution,
        IncidentConversationService conversations,
        IncidentEventRepository events,
        IncidentRunbookIndexService runbooks
    ) {
        this.catalog = catalog;
        this.sessions = sessions;
        this.execution = execution;
        this.conversations = conversations;
        this.events = events;
        this.runbooks = runbooks;
    }

    @GetMapping("/scenarios")
    public List<IncidentScenario> scenarios() {
        return catalog.all();
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView createSession(
        @Valid @RequestBody CreateSessionRequest request
    ) {
        return sessionView(sessions.create(request.scenarioId()));
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionView session(
        @PathVariable String sessionId,
        @RequestHeader("X-AI-Fabric-Demo-Session") String sessionToken
    ) {
        requireSessionToken(sessionId, sessionToken);
        return sessionView(sessions.active(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/reset")
    public SessionView reset(
        @PathVariable String sessionId,
        @RequestHeader("X-AI-Fabric-Demo-Session") String sessionToken
    ) {
        requireSessionToken(sessionId, sessionToken);
        return sessionView(sessions.reset(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
        @PathVariable String sessionId,
        @RequestHeader("X-AI-Fabric-Demo-Session") String sessionToken
    ) {
        requireSessionToken(sessionId, sessionToken);
        sessions.delete(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/plans/{mode}")
    public IncidentPlanRunView executePlan(
        @PathVariable String sessionId,
        @PathVariable String mode,
        @RequestHeader("X-AI-Fabric-Demo-Session") String sessionToken,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody IncidentQuestionRequest request
    ) {
        requireSessionToken(sessionId, sessionToken);
        if (!"sequential".equalsIgnoreCase(mode)
            && !"parallel".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException(
                "Plan mode must be sequential or parallel"
            );
        }
        return execution.executePlan(
            sessionId,
            mode,
            request.question(),
            idempotencyKey
        );
    }

    @PostMapping("/sessions/{sessionId}/compare")
    public IncidentInvestigationPlanComparison compare(
        @PathVariable String sessionId,
        @RequestHeader("X-AI-Fabric-Demo-Session") String sessionToken,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody IncidentQuestionRequest request
    ) {
        requireSessionToken(sessionId, sessionToken);
        return execution.compare(
            sessionId,
            request.question(),
            idempotencyKey
        );
    }

    @PostMapping("/sessions/{sessionId}/delegations")
    public IncidentTransitionResponse delegate(
        @PathVariable String sessionId,
        @RequestHeader("X-AI-Fabric-Demo-Session") String sessionToken,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody IncidentQuestionRequest request
    ) {
        requireSessionToken(sessionId, sessionToken);
        return execution.delegate(
            sessionId,
            request.question(),
            idempotencyKey
        );
    }

    @PostMapping("/sessions/{sessionId}/handoffs")
    public IncidentTransitionResponse handoff(
        @PathVariable String sessionId,
        @RequestHeader("X-AI-Fabric-Demo-Session") String sessionToken,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody IncidentQuestionRequest request
    ) {
        requireSessionToken(sessionId, sessionToken);
        return execution.handoff(
            sessionId,
            request.question(),
            idempotencyKey
        );
    }

    @PostMapping("/sessions/{sessionId}/manager/turns")
    public IncidentManagerTurnView managerTurn(
        @PathVariable String sessionId,
        @RequestHeader("X-AI-Fabric-Demo-Session") String sessionToken,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody IncidentQuestionRequest request
    ) {
        requireSessionToken(sessionId, sessionToken);
        return conversations.chat(
            sessionId,
            request.question(),
            idempotencyKey
        );
    }

    public record CreateSessionRequest(@NotBlank String scenarioId) {}

    public record IncidentQuestionRequest(@NotBlank String question) {}

    private void requireSessionToken(String sessionId, String sessionToken) {
        if (sessionId == null || sessionToken == null
            || !java.security.MessageDigest.isEqual(
                sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                sessionToken.trim().getBytes(
                    java.nio.charset.StandardCharsets.UTF_8
                )
            )) {
            throw new IllegalArgumentException(
                "Incident demo session access was denied"
            );
        }
    }

    public record SessionView(
        String sessionId,
        IncidentScenario scenario,
        IncidentWorkspaceView workspace,
        java.time.Instant createdAt,
        java.time.Instant expiresAt
    ) {}

    public record IncidentWorkspaceView(
        List<IncidentEvent> candidateEvents,
        int excludedBoundaryEventCount,
        Map<String, String> dataSources,
        IncidentRunbookIndexService.IndexStatus runbooks
    ) {}

    private SessionView sessionView(
        IncidentSessionService.ActiveSession session
    ) {
        AuthorizedIncidentScope scope = new AuthorizedIncidentScope(
            "public-demo",
            session.scenario().id(),
            session.scenario().deploymentId(),
            session.scenario().sourceRevision()
        );
        String changeState = "branch-failure".equals(session.scenario().id())
            ? "UNAVAILABLE"
            : "READY";
        return new SessionView(
            session.sessionId(),
            session.scenario(),
            new IncidentWorkspaceView(
                events.previewAuthorized(scope),
                events.countOutsideBoundary(scope),
                Map.of(
                    "read_service_metrics", "READY",
                    "read_incident_alerts", "READY",
                    "read_recent_deployments", changeState,
                    "read_change_approvals", changeState
                ),
                runbooks.status()
            ),
            session.createdAt(),
            session.expiresAt()
        );
    }
}
