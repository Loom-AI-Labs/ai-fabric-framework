package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.chat.service.ChatSessionService;
import com.ai.fabric.realapps.agenticresolver.entity.AgenticResolverDemoSession;
import com.ai.fabric.realapps.agenticresolver.repository.AgenticResolverDemoSessionRepository;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bounded public-demo session service. Durable server-side records bind each
 * opaque browser session to application-created scenario accounts; clients
 * never submit account identifiers.
 */
@Service
public class AgenticResolverSessionService {

    private static final Logger log =
        LoggerFactory.getLogger(AgenticResolverSessionService.class);
    private static final String DEFAULT_SCENARIO = "missing-payment";
    private static final List<String> SUPPORTED_SCENARIO_ORDER = List.of(
        "ready-account",
        "missing-payment",
        "missing-address"
    );
    private static final Set<String> SUPPORTED_SCENARIOS = Set.copyOf(
        SUPPORTED_SCENARIO_ORDER
    );
    private static final Map<String, String> ASSESSMENT_PROMPTS = Map.of(
        "ready-account",
        "Review my current account profile against the policies. Can I place an order? Explain the evidence.",
        "missing-payment",
        "Review my current account profile against the policies. What prevents me from placing an order?",
        "missing-address",
        "Review my current account profile against the policies. What prevents me from placing an order?"
    );

    private final AccountResolutionService accountResolutionService;
    private final Clock clock;
    private final Duration ttl;
    private final int maxActive;
    private final ObjectProvider<ChatSessionService> chatSessionService;
    private final AgenticResolverDemoSessionRepository sessionRepository;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public AgenticResolverSessionService(
        AccountResolutionService accountResolutionService,
        Clock clock,
        @Value("${app.agentic-resolver.sessions.ttl:PT6H}") Duration ttl,
        @Value("${app.agentic-resolver.sessions.max-active:500}") int maxActive,
        ObjectProvider<ChatSessionService> chatSessionService,
        ObjectProvider<AgenticResolverDemoSessionRepository> sessionRepository
    ) {
        this.accountResolutionService = accountResolutionService;
        this.clock = clock;
        this.ttl = ttl != null && !ttl.isZero() && !ttl.isNegative()
            ? ttl
            : Duration.ofHours(6);
        this.maxActive = Math.max(1, maxActive);
        this.chatSessionService = chatSessionService;
        this.sessionRepository = sessionRepository != null
            ? sessionRepository.getIfAvailable()
            : null;
    }

    public synchronized SessionView create() {
        cleanupExpired();
        if (activeSessionCount() >= maxActive) {
            throw new SessionCapacityExceededException();
        }
        AccountResolutionService.DemoSession seeded =
            accountResolutionService.createDemoSession(null);
        Map<String, ScenarioBinding> scenarios = new LinkedHashMap<>();
        for (AccountResolutionService.DemoResolverScenario scenario :
            seeded.scenarios()) {
            if (!SUPPORTED_SCENARIOS.contains(scenario.id())) {
                continue;
            }
            scenarios.put(
                scenario.id(),
                new ScenarioBinding(
                    scenario.id(),
                    UUID.fromString(scenario.userId()),
                    scenario.title(),
                    scenario.description(),
                    ASSESSMENT_PROMPTS.get(scenario.id())
                )
            );
        }
        if (scenarios.isEmpty()) {
            throw new IllegalStateException("No resolver scenarios were seeded");
        }
        String activeScenario = scenarios.containsKey(DEFAULT_SCENARIO)
            ? DEFAULT_SCENARIO
            : scenarios.keySet().iterator().next();
        String sessionId = "agentic-resolver-" + UUID.randomUUID();
        Instant now = clock.instant();
        SessionState state = new SessionState(
            sessionId,
            Collections.unmodifiableMap(new LinkedHashMap<>(scenarios)),
            activeScenario,
            now,
            now
        );
        persist(state);
        sessions.put(sessionId, state);
        return view(state);
    }

    public SessionView get(String sessionId) {
        return view(require(sessionId));
    }

    public SessionView select(String sessionId, String scenarioId) {
        SessionState state = require(sessionId);
        if (scenarioId == null || !state.scenarios.containsKey(scenarioId.trim())) {
            throw new IllegalArgumentException("Unknown resolver scenario");
        }
        state.activeScenarioId = scenarioId.trim();
        state.lastAccessedAt = clock.instant();
        persist(state);
        return view(state);
    }

    public ActiveSession active(String sessionId) {
        SessionState state = require(sessionId);
        ScenarioBinding scenario = state.scenarios.get(state.activeScenarioId);
        state.lastAccessedAt = clock.instant();
        persist(state);
        String ownerId = "demo:" + state.sessionId + ":" + scenario.id;
        return new ActiveSession(
            state.sessionId,
            scenario.id,
            scenario.subjectUserId,
            ownerId,
            "agentic-chat:" + state.sessionId + ":" + scenario.id
        );
    }

    public boolean delete(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        String normalized = sessionId.trim();
        SessionState removed = sessions.remove(normalized);
        if (removed == null && sessionRepository != null) {
            removed = sessionRepository.findById(normalized)
                .map(this::restore)
                .orElse(null);
        }
        if (removed == null) {
            return false;
        }
        if (sessionRepository != null) {
            sessionRepository.deleteById(normalized);
        }
        deleteConversationHistory(removed);
        return true;
    }

    @Scheduled(
        cron = "${app.agentic-resolver.sessions.cleanup-cron:0 */15 * * * *}"
    )
    public int cleanupExpired() {
        Instant cutoff = clock.instant().minus(ttl);
        Map<String, SessionState> expired = new LinkedHashMap<>();
        sessions.values().stream()
            .filter(state -> state.lastAccessedAt.isBefore(cutoff))
            .forEach(state -> expired.put(state.sessionId, state));
        if (sessionRepository != null) {
            sessionRepository.findByLastAccessedAtBefore(cutoff)
                .forEach(record -> expired.putIfAbsent(
                    record.sessionId(),
                    restore(record)
                ));
        }
        int removed = 0;
        for (SessionState state : expired.values()) {
            SessionState cached = sessions.remove(state.sessionId);
            SessionState removedState = cached != null ? cached : state;
            if (sessionRepository != null) {
                sessionRepository.deleteById(state.sessionId);
            }
            removed++;
            deleteConversationHistory(removedState);
        }
        return removed;
    }

    private SessionState require(String sessionId) {
        cleanupExpired();
        if (sessionId == null || sessionId.isBlank()) {
            throw new NoSuchElementException("Agentic resolver session is required");
        }
        String normalized = sessionId.trim();
        if (normalized.length() > 120) {
            throw new NoSuchElementException(
                "Agentic resolver session was not found or expired"
            );
        }
        SessionState state = sessions.get(normalized);
        if (state == null && sessionRepository != null) {
            state = sessionRepository.findById(normalized)
                .map(this::restore)
                .orElse(null);
            if (state != null) {
                sessions.put(normalized, state);
            }
        }
        if (state == null) {
            throw new NoSuchElementException(
                "Agentic resolver session was not found or expired"
            );
        }
        return state;
    }

    private long activeSessionCount() {
        if (sessionRepository == null) {
            return sessions.size();
        }
        return sessionRepository.countByLastAccessedAtGreaterThanEqual(
            clock.instant().minus(ttl)
        );
    }

    private void persist(SessionState state) {
        if (sessionRepository == null) {
            return;
        }
        Map<String, AgenticResolverDemoSession.PersistedScenario> scenarios =
            new LinkedHashMap<>();
        state.scenarios.forEach((id, scenario) ->
            scenarios.put(
                id,
                new AgenticResolverDemoSession.PersistedScenario(
                    scenario.subjectUserId,
                    scenario.title,
                    scenario.description,
                    scenario.suggestedPrompt
                )
            )
        );
        sessionRepository.save(new AgenticResolverDemoSession(
            state.sessionId,
            state.activeScenarioId,
            state.createdAt,
            state.lastAccessedAt,
            scenarios
        ));
    }

    private SessionState restore(AgenticResolverDemoSession record) {
        Map<String, AgenticResolverDemoSession.PersistedScenario> persisted =
            record.scenarios();
        Map<String, ScenarioBinding> scenarios = new LinkedHashMap<>();
        for (String id : SUPPORTED_SCENARIO_ORDER) {
            AgenticResolverDemoSession.PersistedScenario scenario =
                persisted.get(id);
            if (scenario != null) {
                scenarios.put(id, restoreScenario(id, scenario));
            }
        }
        persisted.forEach((id, scenario) ->
            scenarios.putIfAbsent(id, restoreScenario(id, scenario))
        );
        if (
            scenarios.isEmpty()
                || !scenarios.containsKey(record.activeScenarioId())
        ) {
            throw new IllegalStateException(
                "Persisted resolver session has no valid active scenario"
            );
        }
        return new SessionState(
            record.sessionId(),
            Collections.unmodifiableMap(scenarios),
            record.activeScenarioId(),
            record.createdAt(),
            record.lastAccessedAt()
        );
    }

    private ScenarioBinding restoreScenario(
        String id,
        AgenticResolverDemoSession.PersistedScenario scenario
    ) {
        return new ScenarioBinding(
            id,
            scenario.subjectUserId(),
            scenario.title(),
            scenario.description(),
            scenario.suggestedPrompt()
        );
    }

    private SessionView view(SessionState state) {
        List<ScenarioView> scenarios = state.scenarios.values().stream()
            .map(scenario -> new ScenarioView(
                scenario.id,
                scenario.title,
                scenario.description,
                scenario.suggestedPrompt
            ))
            .toList();
        return new SessionView(
            state.sessionId,
            state.activeScenarioId,
            scenarios,
            state.createdAt,
            state.lastAccessedAt.plus(ttl)
        );
    }

    private void deleteConversationHistory(SessionState state) {
        ChatSessionService service =
            chatSessionService != null ? chatSessionService.getIfAvailable() : null;
        if (service == null || state == null) {
            return;
        }
        for (ScenarioBinding scenario : state.scenarios.values()) {
            try {
                service.deleteConversation(
                    conversationId(state, scenario),
                    conversationOwnerId(state, scenario)
                );
            } catch (Exception ex) {
                log.warn(
                    "Could not delete agentic resolver conversation session={} scenario={}: {}",
                    state.sessionId,
                    scenario.id,
                    ex.getClass().getSimpleName()
                );
            }
        }
    }

    private String conversationOwnerId(
        SessionState state,
        ScenarioBinding scenario
    ) {
        return "demo:" + state.sessionId + ":" + scenario.id;
    }

    private String conversationId(
        SessionState state,
        ScenarioBinding scenario
    ) {
        return "agentic-chat:" + state.sessionId + ":" + scenario.id;
    }

    public record SessionView(
        String sessionId,
        String activeScenarioId,
        List<ScenarioView> scenarios,
        Instant createdAt,
        Instant expiresAt
    ) {}

    public record ScenarioView(
        String id,
        String title,
        String description,
        String suggestedPrompt
    ) {}

    public record ActiveSession(
        String sessionId,
        String scenarioId,
        UUID subjectUserId,
        String conversationOwnerId,
        String conversationId
    ) {}

    private record ScenarioBinding(
        String id,
        UUID subjectUserId,
        String title,
        String description,
        String suggestedPrompt
    ) {}

    private static final class SessionState {
        private final String sessionId;
        private final Map<String, ScenarioBinding> scenarios;
        private volatile String activeScenarioId;
        private final Instant createdAt;
        private volatile Instant lastAccessedAt;

        private SessionState(
            String sessionId,
            Map<String, ScenarioBinding> scenarios,
            String activeScenarioId,
            Instant createdAt,
            Instant lastAccessedAt
        ) {
            this.sessionId = sessionId;
            this.scenarios = scenarios;
            this.activeScenarioId = activeScenarioId;
            this.createdAt = createdAt;
            this.lastAccessedAt = lastAccessedAt;
        }
    }

    public static final class SessionCapacityExceededException
        extends RuntimeException {
        public SessionCapacityExceededException() {
            super("The public demo has reached its active session capacity");
        }
    }
}
