package com.ai.fabric.realapps.behavior.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_behavior_analysis_job")
public class BehaviorAnalysisJob {

    @Id
    private UUID id;

    @Column(name = "invocation_id", nullable = false, unique = true, length = 120)
    private String invocationId;

    @Column(name = "session_id", nullable = false, length = 180)
    private String sessionId;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "execution_source", nullable = false, length = 24)
    private String executionSource;

    @Column(name = "previous_insight_json", columnDefinition = "CLOB")
    private String previousInsightJson;

    @Column(name = "considered_events_json", nullable = false, columnDefinition = "CLOB")
    private String consideredEventsJson;

    @Column(name = "considered_event_count", nullable = false)
    private int consideredEventCount;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    protected BehaviorAnalysisJob() {}

    public BehaviorAnalysisJob(
        String invocationId,
        String sessionId,
        String userId,
        String idempotencyKey,
        String executionSource,
        String previousInsightJson,
        String consideredEventsJson,
        int consideredEventCount,
        Instant submittedAt
    ) {
        this.id = UUID.randomUUID();
        this.invocationId = invocationId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.executionSource = executionSource;
        this.previousInsightJson = previousInsightJson;
        this.consideredEventsJson = consideredEventsJson;
        this.consideredEventCount = consideredEventCount;
        this.submittedAt = submittedAt;
    }

    public UUID getId() { return id; }
    public String getInvocationId() { return invocationId; }
    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getExecutionSource() { return executionSource; }
    public String getPreviousInsightJson() { return previousInsightJson; }
    public String getConsideredEventsJson() { return consideredEventsJson; }
    public int getConsideredEventCount() { return consideredEventCount; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getAppliedAt() { return appliedAt; }

    public void markApplied(Instant when) {
        if (appliedAt == null) {
            appliedAt = when;
        }
    }
}
