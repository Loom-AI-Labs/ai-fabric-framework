package com.ai.fabric.realapps.mcpops.service;

import com.ai.fabric.realapps.mcpops.domain.McpInvocationAudit;
import com.ai.fabric.realapps.mcpops.repository.McpInvocationAuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class McpInvocationAuditService {

    private final McpInvocationAuditRepository repository;
    private final Clock clock;

    public McpInvocationAuditService(
        McpInvocationAuditRepository repository,
        Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void record(
        String sessionId,
        String actionId,
        String serverRef,
        String toolName,
        String accessMode,
        String serviceName,
        boolean success,
        String errorCode,
        long durationMs,
        Instant startedAt
    ) {
        repository.save(new McpInvocationAudit(
            "mcp-audit-" + UUID.randomUUID(),
            safe(sessionId, "unknown-session"),
            safe(actionId, "unknown-action"),
            safe(serverRef, "unresolved"),
            safe(toolName, "unresolved"),
            safe(accessMode, "UNKNOWN"),
            safe(serviceName, null),
            success,
            safe(errorCode, null),
            Math.max(0, durationMs),
            startedAt != null ? startedAt : clock.instant()
        ));
    }

    @Transactional(readOnly = true)
    public List<AuditView> timeline(String sessionId) {
        return repository.findTop50BySessionIdOrderByStartedAtDesc(sessionId)
            .stream()
            .map(this::view)
            .toList();
    }

    @Transactional(readOnly = true)
    public long successfulWrites(String sessionId) {
        return repository.countBySessionIdAndActionIdAndSuccessTrue(
            sessionId,
            McpOperationsService.RESTART_ACTION
        );
    }

    private AuditView view(McpInvocationAudit audit) {
        return new AuditView(
            audit.getId(),
            audit.getActionId(),
            audit.getServerRef(),
            audit.getToolName(),
            audit.getAccessMode(),
            audit.getServiceName(),
            audit.isSuccess(),
            audit.getErrorCode(),
            audit.getDurationMs(),
            audit.getStartedAt()
        );
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    public record AuditView(
        String id,
        String actionId,
        String serverRef,
        String toolName,
        String accessMode,
        String serviceName,
        boolean success,
        String errorCode,
        long durationMs,
        Instant startedAt
    ) {
    }
}
