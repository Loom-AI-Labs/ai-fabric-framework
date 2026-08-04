package com.ai.fabric.realapps.deploymentguard.service;

import com.ai.fabric.realapps.deploymentguard.domain.DeploymentContext;
import com.ai.fabric.realapps.deploymentguard.domain.DeploymentKnowledgeCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DeploymentGuardSessionService {

    private final DeploymentKnowledgeCatalog catalog;
    private final Clock clock;
    private final Duration ttl;
    private final int maxActive;
    private final Map<String, DemoSession> sessions = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public DeploymentGuardSessionService(
        DeploymentKnowledgeCatalog catalog,
        @Value("${app.deployment-guard.sessions.ttl:PT4H}") Duration ttl,
        @Value("${app.deployment-guard.sessions.max-active:500}") int maxActive
    ) {
        this(catalog, Clock.systemUTC(), ttl, maxActive);
    }

    DeploymentGuardSessionService(
        DeploymentKnowledgeCatalog catalog,
        Clock clock,
        Duration ttl,
        int maxActive
    ) {
        this.catalog = catalog;
        this.clock = clock;
        this.ttl = ttl;
        this.maxActive = maxActive;
    }

    public SessionView create() {
        cleanup();
        if (sessions.size() >= maxActive) {
            throw new IllegalStateException("Demo session capacity reached");
        }
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        DemoSession session = new DemoSession(
            id,
            "demo-operator-" + id.substring(0, 8),
            catalog.contexts().getFirst().id(),
            now,
            now.plus(ttl)
        );
        sessions.put(id, session);
        return view(session);
    }

    public SessionView require(String sessionId) {
        return view(active(sessionId));
    }

    public SessionView selectContext(String sessionId, String contextId) {
        catalog.requireContext(contextId);
        DemoSession current = active(sessionId);
        DemoSession updated = new DemoSession(
            current.id(),
            current.operatorId(),
            contextId,
            current.createdAt(),
            clock.instant().plus(ttl)
        );
        sessions.put(sessionId, updated);
        return view(updated);
    }

    public ActiveSession activeSession(String sessionId) {
        DemoSession session = active(sessionId);
        return new ActiveSession(
            session.id(),
            session.operatorId(),
            catalog.requireContext(session.contextId()),
            session.expiresAt()
        );
    }

    public void delete(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId.trim());
        }
    }

    @Scheduled(cron = "${app.deployment-guard.sessions.cleanup-cron:0 */15 * * * *}")
    public void cleanup() {
        Instant now = clock.instant();
        sessions.values().removeIf(session -> !session.expiresAt().isAfter(now));
    }

    private DemoSession active(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Demo session header is required");
        }
        DemoSession session = sessions.get(sessionId.trim());
        if (session == null || !session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(sessionId.trim());
            throw new IllegalArgumentException("Demo session is missing or expired");
        }
        return session;
    }

    private SessionView view(DemoSession session) {
        DeploymentContext activeContext = catalog.requireContext(session.contextId());
        List<ContextOption> options = catalog.contexts().stream()
            .map(context -> new ContextOption(
                context.id(),
                context.label(),
                context.environment()
            ))
            .toList();
        return new SessionView(
            session.id(),
            session.operatorId(),
            activeContext.id(),
            activeContext.label(),
            activeContext.environment(),
            session.expiresAt(),
            options
        );
    }

    private record DemoSession(
        String id,
        String operatorId,
        String contextId,
        Instant createdAt,
        Instant expiresAt
    ) {}

    public record ActiveSession(
        String sessionId,
        String operatorId,
        DeploymentContext context,
        Instant expiresAt
    ) {}

    public record SessionView(
        String sessionId,
        String operatorId,
        String activeContextId,
        String activeContextLabel,
        String environment,
        Instant expiresAt,
        List<ContextOption> contexts
    ) {}

    public record ContextOption(String id, String label, String environment) {}
}
