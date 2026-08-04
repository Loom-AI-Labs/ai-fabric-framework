package com.ai.fabric.realapps.incident.web;

import ai.fabric.execution.manager.ConversationManagerTurnResult;
import ai.fabric.execution.plan.PlanExecutionResult;
import com.ai.fabric.realapps.incident.domain.IncidentAssessment;
import com.ai.fabric.realapps.incident.domain.IncidentPlanComparison;
import com.ai.fabric.realapps.incident.domain.IncidentScenario;
import com.ai.fabric.realapps.incident.domain.IncidentTransitionResponse;
import com.ai.fabric.realapps.incident.execution.IncidentPlans;
import com.ai.fabric.realapps.incident.execution.IncidentSpecialists;
import com.ai.fabric.realapps.incident.service.IncidentConversationService;
import com.ai.fabric.realapps.incident.service.IncidentExecutionService;
import com.ai.fabric.realapps.incident.service.IncidentScenarioCatalog;
import com.ai.fabric.realapps.incident.service.IncidentSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
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

    public IncidentController(
        IncidentScenarioCatalog catalog,
        IncidentSessionService sessions,
        IncidentExecutionService execution,
        IncidentConversationService conversations
    ) {
        this.catalog = catalog;
        this.sessions = sessions;
        this.execution = execution;
        this.conversations = conversations;
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
        return SessionView.from(sessions.create(request.scenarioId()));
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionView session(
        @PathVariable String sessionId,
        @RequestHeader("X-AI-Fabric-Demo-Session") String sessionToken
    ) {
        requireSessionToken(sessionId, sessionToken);
        return SessionView.from(sessions.active(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/reset")
    public SessionView reset(
        @PathVariable String sessionId,
        @RequestHeader("X-AI-Fabric-Demo-Session") String sessionToken
    ) {
        requireSessionToken(sessionId, sessionToken);
        return SessionView.from(sessions.reset(sessionId));
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
    public PlanExecutionResult<IncidentAssessment> executePlan(
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
    public IncidentPlanComparison compare(
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
    public ConversationManagerTurnResult managerTurn(
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
        java.time.Instant createdAt,
        java.time.Instant expiresAt
    ) {
        static SessionView from(IncidentSessionService.ActiveSession session) {
            return new SessionView(
                session.sessionId(),
                session.scenario(),
                session.createdAt(),
                session.expiresAt()
            );
        }
    }
}
