package com.ai.fabric.realapps.agenticresolver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/** Binds a durable AI Fabric review task to one public demo session. */
@Entity
@Table(
    name = "agentic_resolver_demo_review_tasks",
    indexes = @Index(
        name = "idx_agentic_review_task_demo_session",
        columnList = "demo_session_id"
    )
)
public class DemoReviewTaskBinding {

    @Id
    @Column(name = "task_id", length = 120, nullable = false)
    private String taskId;

    @Column(name = "demo_session_id", length = 120, nullable = false)
    private String demoSessionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DemoReviewTaskBinding() {}

    public DemoReviewTaskBinding(
        String taskId,
        String demoSessionId,
        Instant createdAt
    ) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.demoSessionId = Objects.requireNonNull(
            demoSessionId,
            "demoSessionId"
        );
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public String taskId() {
        return taskId;
    }

    public String demoSessionId() {
        return demoSessionId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
