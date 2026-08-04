package com.ai.fabric.realapps.mcpops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "mcp_demo_session")
public class McpDemoSession {

    @Id
    @Column(length = 120, nullable = false, updatable = false)
    private String sessionId;

    @Column(length = 120, nullable = false)
    private String conversationId;

    @Column(length = 40, nullable = false)
    private String serviceName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastTouchedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Version
    private long persistenceVersion;

    protected McpDemoSession() {
    }

    public McpDemoSession(
        String sessionId,
        String conversationId,
        String serviceName,
        Instant createdAt,
        Instant expiresAt
    ) {
        this.sessionId = sessionId;
        this.conversationId = conversationId;
        this.serviceName = serviceName;
        this.createdAt = createdAt;
        this.lastTouchedAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void touch(Instant now, Instant newExpiry) {
        this.lastTouchedAt = now;
        this.expiresAt = newExpiry;
    }

    public String selectService(
        String selectedService,
        String newConversationId,
        Instant now,
        Instant newExpiry
    ) {
        String previousConversationId = this.conversationId;
        this.serviceName = selectedService;
        this.conversationId = newConversationId;
        touch(now, newExpiry);
        return previousConversationId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastTouchedAt() {
        return lastTouchedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
