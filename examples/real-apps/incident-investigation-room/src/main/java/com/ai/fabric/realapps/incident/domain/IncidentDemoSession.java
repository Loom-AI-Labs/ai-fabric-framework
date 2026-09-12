package com.ai.fabric.realapps.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
    name = "incident_demo_sessions",
    indexes = @Index(
        name = "idx_incident_demo_session_expiry",
        columnList = "expires_at"
    )
)
public class IncidentDemoSession {

    @Id
    @Column(name = "session_id", nullable = false, length = 160)
    private String sessionId;

    @Column(name = "owner_id", nullable = false, length = 160)
    private String ownerId;

    @Column(name = "conversation_id", nullable = false, length = 160)
    private String conversationId;

    @Column(name = "scenario_id", nullable = false, length = 120)
    private String scenarioId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IncidentDemoSession() {}

    public IncidentDemoSession(
        String sessionId,
        String ownerId,
        String conversationId,
        String scenarioId,
        Instant createdAt,
        Instant expiresAt
    ) {
        this.sessionId = requireText(sessionId, "sessionId");
        this.ownerId = requireText(ownerId, "ownerId");
        this.conversationId = requireText(conversationId, "conversationId");
        this.scenarioId = requireText(scenarioId, "scenarioId");
        this.createdAt = java.util.Objects.requireNonNull(
            createdAt,
            "createdAt is required"
        );
        this.expiresAt = java.util.Objects.requireNonNull(
            expiresAt,
            "expiresAt is required"
        );
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException(
                "expiresAt must be after createdAt"
            );
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    private static String requireText(String value, String field) {
        String normalized = java.util.Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
