package com.ai.fabric.realapps.mcpops.service;

import ai.fabric.chat.service.ChatSessionService;
import com.ai.fabric.realapps.mcpops.domain.McpDemoSession;
import com.ai.fabric.realapps.mcpops.repository.McpDemoSessionRepository;
import com.ai.fabric.realapps.mcpops.repository.McpInvocationAuditRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class McpDemoSessionService {

    public static final Set<String> SERVICES = Set.of(
        "catalog",
        "checkout",
        "payments"
    );

    private final McpDemoSessionRepository sessions;
    private final McpInvocationAuditRepository audits;
    private final ChatSessionService chatSessions;
    private final Clock clock;
    private final Duration ttl;

    public McpDemoSessionService(
        McpDemoSessionRepository sessions,
        McpInvocationAuditRepository audits,
        ChatSessionService chatSessions,
        Clock clock,
        @Value("${app.mcp-operations.sessions.ttl:PT6H}") Duration ttl
    ) {
        this.sessions = sessions;
        this.audits = audits;
        this.chatSessions = chatSessions;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Transactional
    public SessionView create() {
        Instant now = clock.instant();
        McpDemoSession session = new McpDemoSession(
            "mcp-demo-" + UUID.randomUUID(),
            "mcp-conversation-" + UUID.randomUUID(),
            "checkout",
            now,
            now.plus(ttl)
        );
        return view(sessions.save(session));
    }

    @Transactional
    public ActiveSession active(String sessionId) {
        String normalized = requireSessionId(sessionId);
        McpDemoSession session = sessions.findById(normalized)
            .orElseThrow(() -> new IllegalArgumentException(
                "Demo session was not found."
            ));
        Instant now = clock.instant();
        if (!session.getExpiresAt().isAfter(now)) {
            deleteInternal(session);
            throw new IllegalArgumentException(
                "Demo session expired. Start a new session."
            );
        }
        session.touch(now, now.plus(ttl));
        sessions.save(session);
        return activeView(session);
    }

    @Transactional(readOnly = true)
    public SessionView get(String sessionId) {
        McpDemoSession session = sessions.findById(requireSessionId(sessionId))
            .orElseThrow(() -> new IllegalArgumentException(
                "Demo session was not found."
            ));
        return view(session);
    }

    @Transactional
    public SessionView selectService(String sessionId, String serviceName) {
        String normalizedService = normalizeService(serviceName);
        McpDemoSession session = sessions.findById(requireSessionId(sessionId))
            .orElseThrow(() -> new IllegalArgumentException(
                "Demo session was not found."
            ));
        Instant now = clock.instant();
        String previousConversation = session.selectService(
            normalizedService,
            "mcp-conversation-" + UUID.randomUUID(),
            now,
            now.plus(ttl)
        );
        deleteConversationQuietly(sessionId, previousConversation);
        return view(sessions.save(session));
    }

    @Transactional
    public boolean delete(String sessionId) {
        return sessions.findById(requireSessionId(sessionId))
            .map(session -> {
                deleteInternal(session);
                return true;
            })
            .orElse(false);
    }

    @Scheduled(cron = "${app.mcp-operations.sessions.cleanup-cron:0 */15 * * * *}")
    @Transactional
    public void cleanupExpired() {
        Instant now = clock.instant();
        sessions.findAll().stream()
            .filter(session -> !session.getExpiresAt().isAfter(now))
            .forEach(this::deleteInternal);
        audits.deleteByStartedAtBefore(now.minus(ttl));
    }

    public List<String> serviceNames() {
        return SERVICES.stream().sorted().toList();
    }

    private void deleteInternal(McpDemoSession session) {
        deleteConversationQuietly(
            session.getSessionId(),
            session.getConversationId()
        );
        audits.deleteBySessionId(session.getSessionId());
        sessions.delete(session);
    }

    private void deleteConversationQuietly(String ownerId, String conversationId) {
        try {
            chatSessions.deleteConversation(conversationId, ownerId);
        } catch (RuntimeException ignored) {
            // A session may not have produced a validated turn yet.
        }
    }

    private String requireSessionId(String value) {
        if (!StringUtils.hasText(value) || value.length() > 120) {
            throw new IllegalArgumentException("A valid demo session is required.");
        }
        return value.trim();
    }

    private String normalizeService(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("serviceName is required.");
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SERVICES.contains(normalized)) {
            throw new IllegalArgumentException("Unknown sandbox service.");
        }
        return normalized;
    }

    private ActiveSession activeView(McpDemoSession session) {
        return new ActiveSession(
            session.getSessionId(),
            session.getConversationId(),
            session.getServiceName(),
            session.getExpiresAt()
        );
    }

    private SessionView view(McpDemoSession session) {
        return new SessionView(
            session.getSessionId(),
            session.getConversationId(),
            session.getServiceName(),
            serviceNames(),
            session.getCreatedAt(),
            session.getExpiresAt()
        );
    }

    public record ActiveSession(
        String sessionId,
        String conversationId,
        String serviceName,
        Instant expiresAt
    ) {
    }

    public record SessionView(
        String sessionId,
        String conversationId,
        String selectedService,
        List<String> availableServices,
        Instant createdAt,
        Instant expiresAt
    ) {
    }
}
