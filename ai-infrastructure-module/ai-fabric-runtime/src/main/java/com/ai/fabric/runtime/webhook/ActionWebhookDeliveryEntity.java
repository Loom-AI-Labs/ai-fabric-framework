package com.ai.fabric.runtime.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(
    name = "runtime_action_webhook_delivery",
    indexes = {
        @Index(name = "idx_runtime_webhook_delivery_dispatch", columnList = "status,nextAttemptAt"),
        @Index(name = "idx_runtime_webhook_delivery_claimed", columnList = "status,claimedAt"),
        @Index(name = "idx_runtime_webhook_delivery_created", columnList = "createdAt")
    }
)
public class ActionWebhookDeliveryEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RETRY_PENDING = "RETRY_PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(length = 128, nullable = false)
    private String actionName;

    @Column(length = 128, nullable = false)
    private String eventType;

    @Column(length = 128, nullable = false)
    private String targetRef;

    @Column(length = 128, nullable = false)
    private String urlSecretRef;

    @Column(length = 128)
    private String signingSecretRef;

    @Column(nullable = false)
    private Integer timeoutMs;

    @Column(nullable = false)
    private Integer maxAttempts;

    @Column(nullable = false)
    private Integer attemptCount;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 128)
    private String deploymentId;

    @Column(length = 128)
    private String conversationId;

    @Column(length = 128)
    private String requestId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payloadJson;

    @Column(length = 4000)
    private String lastError;

    private Integer lastStatusCode;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private Instant claimedAt;

    private Instant deliveredAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getActionName() {
        return actionName;
    }

    public void setActionName(String actionName) {
        this.actionName = actionName;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getTargetRef() {
        return targetRef;
    }

    public void setTargetRef(String targetRef) {
        this.targetRef = targetRef;
    }

    public String getUrlSecretRef() {
        return urlSecretRef;
    }

    public void setUrlSecretRef(String urlSecretRef) {
        this.urlSecretRef = urlSecretRef;
    }

    public String getSigningSecretRef() {
        return signingSecretRef;
    }

    public void setSigningSecretRef(String signingSecretRef) {
        this.signingSecretRef = signingSecretRef;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(Integer maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(String deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Integer getLastStatusCode() {
        return lastStatusCode;
    }

    public void setLastStatusCode(Integer lastStatusCode) {
        this.lastStatusCode = lastStatusCode;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
