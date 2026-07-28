package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.chat.service.ChatSessionService;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Bounded public-demo session map. The map is the authority linking a browser session to
 * server-created scenario accounts; clients never submit account identifiers.
 */
@Service
public class AgenticResolverSessionService {

    private static final Logger log =
        LoggerFactory.getLogger(AgenticResolverSessionService.class);
    private static final String DEFAULT_SCENARIO = "missing-payment";
    private static final Set<String> READ_ONLY_SCENARIOS = Set.of(
        "ready-account",
        "missing-payment",
        "missing-address"
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
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public AgenticResolverSessionService(
        AccountResolutionService accountResolutionService,
        Clock clock,
        @Value("${app.agentic-resolver.sessions.ttl:PT6H}") Duration ttl,
        @Value("${app.agentic-resolver.sessions.max-active:500}") int maxActive,
        ObjectProvider<ChatSessionService> chatSessionService
    ) {
        this.accountResolutionService = accountResolutionService;
        this.clock = clock;
        this.ttl = ttl != null && !ttl.isZero() && !ttl.isNegative()
            ? ttl
            : Duration.ofHours(6);
        this.maxActive = Math.max(1, maxActive);
        this.chatSessionService = chatSessionService;
    }

    public synchronized SessionView create() {
        cleanupExpired();
        if (sessions.size() >= maxActive) {
            throw new SessionCapacityExceededException();
        }
        AccountResolutionService.DemoSession seeded =
            accountResolutionService.createDemoSession(null);
        Map<String, ScenarioBinding> scenarios = new LinkedHashMap<>();
        for (AccountResolutionService.DemoResolverScenario scenario :
            seeded.scenarios()) {
            if (!READ_ONLY_SCENARIOS.contains(scenario.id())) {
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
        return view(state);
    }

    public ActiveSession active(String sessionId) {
        SessionState state = require(sessionId);
        ScenarioBinding scenario = state.scenarios.get(state.activeScenarioId);
        state.lastAccessedAt = clock.instant();
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
        SessionState removed = sessions.remove(sessionId.trim());
        if (removed == null) {
            return false;
        }
        deleteConversationHistory(removed);
        return true;
    }

    @Scheduled(
        cron = "${app.agentic-resolver.sessions.cleanup-cron:0 */15 * * * *}"
    )
    public int cleanupExpired() {
        Instant cutoff = clock.instant().minus(ttl);
        List<SessionState> expired = new ArrayList<>();
        sessions.values().stream()
            .filter(state -> state.lastAccessedAt.isBefore(cutoff))
            .forEach(expired::add);
        int removed = 0;
        for (SessionState state : expired) {
            if (sessions.remove(state.sessionId, state)) {
                removed++;
                deleteConversationHistory(state);
            }
        }
        return removed;
    }

    private SessionState require(String sessionId) {
        cleanupExpired();
        if (sessionId == null || sessionId.isBlank()) {
            throw new NoSuchElementException("Agentic resolver session is required");
        }
        SessionState state = sessions.get(sessionId.trim());
        if (state == null) {
            throw new NoSuchElementException(
                "Agentic resolver session was not found or expired"
            );
        }
        return state;
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
