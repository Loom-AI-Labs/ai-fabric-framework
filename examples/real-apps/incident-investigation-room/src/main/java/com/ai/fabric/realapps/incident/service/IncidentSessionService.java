package com.ai.fabric.realapps.incident.service;

import ai.fabric.chat.service.ChatSessionService;
import com.ai.fabric.realapps.incident.domain.IncidentDemoSession;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import com.ai.fabric.realapps.incident.domain.IncidentScenario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IncidentSessionService {

    private final IncidentScenarioCatalog catalog;
    private final ChatSessionService chatSessions;
    private final IncidentDemoSessionRepository repository;
    private final Clock clock;
    private final Duration ttl;
    private final int maxActive;

    public IncidentSessionService(
        IncidentScenarioCatalog catalog,
        ChatSessionService chatSessions,
        IncidentDemoSessionRepository repository,
        Clock clock,
        @Value("${app.incident.sessions.ttl:PT4H}") Duration ttl,
        @Value("${app.incident.sessions.max-active:500}") int maxActive
    ) {
        this.catalog = catalog;
        this.chatSessions = chatSessions;
        this.repository = repository;
        this.clock = clock;
        this.ttl = ttl;
        this.maxActive = maxActive;
    }

    public ActiveSession create(String scenarioId) {
        removeExpired();
        Instant now = clock.instant();
        if (repository.countByExpiresAtAfter(now) >= maxActive) {
            throw new IllegalStateException(
                "The public incident demo has reached its active-session limit"
            );
        }
        IncidentScenario scenario = catalog.require(scenarioId);
        IncidentDemoSession stored = new IncidentDemoSession(
            "incident-session-" + UUID.randomUUID(),
            "incident-owner-" + UUID.randomUUID(),
            "incident-conversation-" + UUID.randomUUID(),
            scenario.id(),
            now,
            now.plus(ttl)
        );
        return toActive(repository.save(stored));
    }

    public ActiveSession active(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Demo session ID is required");
        }
        IncidentDemoSession stored = repository.findById(sessionId.trim())
            .orElse(null);
        if (stored == null || !stored.getExpiresAt().isAfter(clock.instant())) {
            if (stored != null) {
                remove(stored);
            }
            throw new IllegalArgumentException(
                "Incident demo session is missing or expired"
            );
        }
        return toActive(stored);
    }

    public ActiveSession reset(String sessionId) {
        ActiveSession current = active(sessionId);
        delete(current.sessionId());
        return create(current.scenario().id());
    }

    public void delete(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            repository.findById(sessionId.trim()).ifPresent(this::remove);
        }
    }

    public IncidentPlanRequest planRequest(
        String sessionId,
        String question
    ) {
        IncidentScenario scenario = active(sessionId).scenario();
        return new IncidentPlanRequest(
            question,
            scenario.id(),
            scenario.deploymentId(),
            scenario.sourceRevision(),
            scenario.serviceEvidence(),
            scenario.changeEvidence(),
            scenario.failingBranch()
        );
    }

    @Scheduled(cron = "${app.incident.sessions.cleanup-cron:0 */15 * * * *}")
    void removeExpired() {
        Instant now = clock.instant();
        repository.findByExpiresAtLessThanEqual(now).forEach(this::remove);
    }

    private void remove(IncidentDemoSession session) {
        chatSessions.deleteConversation(
            session.getConversationId(),
            session.getOwnerId()
        );
        repository.delete(session);
    }

    private ActiveSession toActive(IncidentDemoSession stored) {
        return new ActiveSession(
            stored.getSessionId(),
            stored.getOwnerId(),
            stored.getConversationId(),
            catalog.require(stored.getScenarioId()),
            stored.getCreatedAt(),
            stored.getExpiresAt()
        );
    }

    public record ActiveSession(
        String sessionId,
        String ownerId,
        String conversationId,
        IncidentScenario scenario,
        Instant createdAt,
        Instant expiresAt
    ) {}
}
