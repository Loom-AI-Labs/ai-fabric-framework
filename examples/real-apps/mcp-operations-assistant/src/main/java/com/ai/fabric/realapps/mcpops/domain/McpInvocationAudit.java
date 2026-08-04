package com.ai.fabric.realapps.mcpops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
    name = "mcp_invocation_audit",
    indexes = @Index(
        name = "idx_mcp_audit_session_started",
        columnList = "sessionId,startedAt"
    )
)
public class McpInvocationAudit {

    @Id
    @Column(length = 120, nullable = false, updatable = false)
    private String id;

    @Column(length = 120, nullable = false)
    private String sessionId;

    @Column(length = 100, nullable = false)
    private String actionId;

    @Column(length = 120, nullable = false)
    private String serverRef;

    @Column(length = 120, nullable = false)
    private String toolName;

    @Column(length = 32, nullable = false)
    private String accessMode;

    @Column(length = 40)
    private String serviceName;

    @Column(nullable = false)
    private boolean success;

    @Column(length = 100)
    private String errorCode;

    @Column(nullable = false)
    private long durationMs;

    @Column(nullable = false)
    private Instant startedAt;

    protected McpInvocationAudit() {
    }

    public McpInvocationAudit(
        String id,
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
        this.id = id;
        this.sessionId = sessionId;
        this.actionId = actionId;
        this.serverRef = serverRef;
        this.toolName = toolName;
        this.accessMode = accessMode;
        this.serviceName = serviceName;
        this.success = success;
        this.errorCode = errorCode;
        this.durationMs = durationMs;
        this.startedAt = startedAt;
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getActionId() {
        return actionId;
    }

    public String getServerRef() {
        return serverRef;
    }

    public String getToolName() {
        return toolName;
    }

    public String getAccessMode() {
        return accessMode;
    }

    public String getServiceName() {
        return serviceName;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Instant getStartedAt() {
        return startedAt;
    }
}
