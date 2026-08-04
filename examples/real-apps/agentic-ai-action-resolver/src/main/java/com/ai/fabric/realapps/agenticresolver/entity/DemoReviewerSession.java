package com.ai.fabric.realapps.agenticresolver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * Durable hash-only credential for the public human-review demonstration.
 */
@Entity
@Table(
    name = "agentic_resolver_demo_reviewer_sessions",
    indexes = {
        @Index(
            name = "idx_agentic_reviewer_demo_session",
            columnList = "demo_session_id"
        ),
        @Index(
            name = "idx_agentic_reviewer_expires",
            columnList = "expires_at"
        )
    }
)
public class DemoReviewerSession {

    @Id
    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "demo_session_id", length = 120, nullable = false)
    private String demoSessionId;

    @Column(name = "reviewer_role", length = 20, nullable = false)
    private String reviewerRole;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected DemoReviewerSession() {}

    public DemoReviewerSession(
        String tokenHash,
        String demoSessionId,
        String reviewerRole,
        Instant createdAt,
        Instant expiresAt
    ) {
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.demoSessionId = Objects.requireNonNull(
            demoSessionId,
            "demoSessionId"
        );
        this.reviewerRole = Objects.requireNonNull(
            reviewerRole,
            "reviewerRole"
        );
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public String tokenHash() {
        return tokenHash;
    }

    public String demoSessionId() {
        return demoSessionId;
    }

    public String reviewerRole() {
        return reviewerRole;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
