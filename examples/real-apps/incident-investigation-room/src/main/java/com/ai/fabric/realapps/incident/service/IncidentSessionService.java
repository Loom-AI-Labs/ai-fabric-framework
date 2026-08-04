package com.ai.fabric.realapps.incident.service;

import ai.fabric.chat.service.ChatSessionService;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import com.ai.fabric.realapps.incident.domain.IncidentScenario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class IncidentSessionService {

    private final IncidentScenarioCatalog catalog;
    private final ChatSessionService chatSessions;
    private final Clock clock;
    private final Duration ttl;
    private final int maxActive;
    private final Map<String, ActiveSession> sessions =
        new ConcurrentHashMap<>();

    public IncidentSessionService(
        IncidentScenarioCatalog catalog,
        ChatSessionService chatSessions,
        Clock clock,
        @Value("${app.incident.sessions.ttl:PT4H}") Duration ttl,
        @Value("${app.incident.sessions.max-active:500}") int maxActive
    ) {
        this.catalog = catalog;
        this.chatSessions = chatSessions;
        this.clock = clock;
        this.ttl = ttl;
        this.maxActive = maxActive;
    }

    public ActiveSession create(String scenarioId) {
        removeExpired();
        if (sessions.size() >= maxActive) {
            throw new IllegalStateException(
                "The public incident demo has reached its active-session limit"
            );
        }
        IncidentScenario scenario = catalog.require(scenarioId);
        Instant now = clock.instant();
        String sessionId = "incident-session-" + UUID.randomUUID();
        ActiveSession session = new ActiveSession(
            sessionId,
            "incident-owner-" + UUID.randomUUID(),
            "incident-conversation-" + UUID.randomUUID(),
            scenario,
            now,
            now.plus(ttl)
        );
        sessions.put(sessionId, session);
        return session;
    }

    public ActiveSession active(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Demo session ID is required");
        }
        ActiveSession session = sessions.get(sessionId.trim());
        if (session == null || !session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(sessionId.trim());
            throw new IllegalArgumentException(
                "Incident demo session is missing or expired"
            );
        }
        return session;
    }

    public ActiveSession reset(String sessionId) {
        ActiveSession current = active(sessionId);
        remove(current);
        return create(current.scenario().id());
    }

    public void delete(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            ActiveSession current = sessions.get(sessionId.trim());
            if (current != null) {
                remove(current);
            }
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
        sessions.values().stream()
            .filter(session -> !session.expiresAt().isAfter(now))
            .map(ActiveSession::sessionId)
            .sorted(Comparator.naturalOrder())
            .map(sessions::get)
            .filter(java.util.Objects::nonNull)
            .forEach(this::remove);
    }

    private void remove(ActiveSession session) {
        chatSessions.deleteConversation(
            session.conversationId(),
            session.ownerId()
        );
        sessions.remove(session.sessionId(), session);
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
