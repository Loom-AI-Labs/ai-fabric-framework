package com.ai.fabric.realapps.agenticresolver.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable server-side binding between an opaque public demo session and its
 * application-owned scenario subjects.
 */
@Entity
@Table(
    name = "agentic_resolver_demo_sessions",
    indexes = @Index(
        name = "idx_agentic_demo_session_last_accessed",
        columnList = "last_accessed_at"
    )
)
public class AgenticResolverDemoSession {

    @Id
    @Column(name = "session_id", length = 120, nullable = false)
    private String sessionId;

    @Column(name = "active_scenario_id", length = 80, nullable = false)
    private String activeScenarioId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_accessed_at", nullable = false)
    private Instant lastAccessedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "agentic_resolver_demo_session_scenarios",
        joinColumns = @JoinColumn(name = "session_id", nullable = false)
    )
    @MapKeyColumn(name = "scenario_id", length = 80)
    private Map<String, PersistedScenario> scenarios = new LinkedHashMap<>();

    protected AgenticResolverDemoSession() {}

    public AgenticResolverDemoSession(
        String sessionId,
        String activeScenarioId,
        Instant createdAt,
        Instant lastAccessedAt,
        Map<String, PersistedScenario> scenarios
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.activeScenarioId = Objects.requireNonNull(
            activeScenarioId,
            "activeScenarioId"
        );
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.lastAccessedAt = Objects.requireNonNull(
            lastAccessedAt,
            "lastAccessedAt"
        );
        this.scenarios = new LinkedHashMap<>(
            Objects.requireNonNull(scenarios, "scenarios")
        );
    }

    public String sessionId() {
        return sessionId;
    }

    public String activeScenarioId() {
        return activeScenarioId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastAccessedAt() {
        return lastAccessedAt;
    }

    public Map<String, PersistedScenario> scenarios() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(scenarios));
    }

    @Embeddable
    public static class PersistedScenario {

        @Column(name = "subject_user_id", nullable = false)
        private UUID subjectUserId;

        @Column(name = "title", length = 160, nullable = false)
        private String title;

        @Column(name = "description", length = 1000, nullable = false)
        private String description;

        @Column(name = "suggested_prompt", length = 1000, nullable = false)
        private String suggestedPrompt;

        protected PersistedScenario() {}

        public PersistedScenario(
            UUID subjectUserId,
            String title,
            String description,
            String suggestedPrompt
        ) {
            this.subjectUserId = Objects.requireNonNull(
                subjectUserId,
                "subjectUserId"
            );
            this.title = Objects.requireNonNull(title, "title");
            this.description = Objects.requireNonNull(
                description,
                "description"
            );
            this.suggestedPrompt = Objects.requireNonNull(
                suggestedPrompt,
                "suggestedPrompt"
            );
        }

        public UUID subjectUserId() {
            return subjectUserId;
        }

        public String title() {
            return title;
        }

        public String description() {
            return description;
        }

        public String suggestedPrompt() {
            return suggestedPrompt;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PersistedScenario that)) {
                return false;
            }
            return Objects.equals(subjectUserId, that.subjectUserId)
                && Objects.equals(title, that.title)
                && Objects.equals(description, that.description)
                && Objects.equals(suggestedPrompt, that.suggestedPrompt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                subjectUserId,
                title,
                description,
                suggestedPrompt
            );
        }
    }
}
