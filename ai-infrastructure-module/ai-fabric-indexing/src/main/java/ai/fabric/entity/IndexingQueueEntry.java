package ai.fabric.entity;

import ai.fabric.indexing.IndexingStatus;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

/**
 * Durable, class-free indexing work item.
 */
@Entity
@Table(
    name = "ai_indexing_queue",
    indexes = {
        @Index(name = "idx_ai_queue_status_strategy", columnList = "status,strategy,scheduled_for"),
        @Index(name = "idx_ai_queue_entity_order", columnList = "entity_type,entity_id,id"),
        @Index(name = "idx_ai_queue_dependency", columnList = "depends_on_work_id")
    }
)
public class IndexingQueueEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 128)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 512)
    private String entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_type", nullable = false, length = 32)
    private AIIndexWorkType workType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_operation", nullable = false, length = 32)
    private AIProcessOperation sourceOperation;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, length = 32)
    private IndexingStrategy strategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IndexingStatus status = IndexingStatus.PENDING;

    @Column(name = "payload_schema_version", nullable = false)
    private int payloadSchemaVersion;

    @Column(name = "descriptor_hash", nullable = false, length = 64)
    private String descriptorHash;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "depends_on_work_id")
    private Long dependsOnWorkId;

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Lob
    @Column(name = "result_payload", columnDefinition = "TEXT")
    private String resultPayload;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    @Column(name = "dead_letter_reason", length = 256)
    private String deadLetterReason;

    @Column(name = "processing_node", length = 128)
    private String processingNode;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "scheduled_for", nullable = false)
    private LocalDateTime scheduledFor;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "visibility_timeout_until")
    private LocalDateTime visibilityTimeoutUntil;

    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private long version;

    public Long getId() {
        return id;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public AIIndexWorkType getWorkType() {
        return workType;
    }

    public void setWorkType(AIIndexWorkType workType) {
        this.workType = workType;
    }

    public AIProcessOperation getSourceOperation() {
        return sourceOperation;
    }

    public void setSourceOperation(AIProcessOperation sourceOperation) {
        this.sourceOperation = sourceOperation;
    }

    public IndexingStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(IndexingStrategy strategy) {
        this.strategy = strategy;
    }

    public IndexingStatus getStatus() {
        return status;
    }

    public void setStatus(IndexingStatus status) {
        this.status = status;
    }

    public int getPayloadSchemaVersion() {
        return payloadSchemaVersion;
    }

    public void setPayloadSchemaVersion(int payloadSchemaVersion) {
        this.payloadSchemaVersion = payloadSchemaVersion;
    }

    public String getDescriptorHash() {
        return descriptorHash;
    }

    public void setDescriptorHash(String descriptorHash) {
        this.descriptorHash = descriptorHash;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getPayload() {
        return payload;
    }

    public Long getDependsOnWorkId() {
        return dependsOnWorkId;
    }

    public void setDependsOnWorkId(Long dependsOnWorkId) {
        this.dependsOnWorkId = dependsOnWorkId;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getResultPayload() {
        return resultPayload;
    }

    public void setResultPayload(String resultPayload) {
        this.resultPayload = resultPayload;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getDeadLetterReason() {
        return deadLetterReason;
    }

    public void setDeadLetterReason(String deadLetterReason) {
        this.deadLetterReason = deadLetterReason;
    }

    public String getProcessingNode() {
        return processingNode;
    }

    public void setProcessingNode(String processingNode) {
        this.processingNode = processingNode;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public LocalDateTime getScheduledFor() {
        return scheduledFor;
    }

    public void setScheduledFor(LocalDateTime scheduledFor) {
        this.scheduledFor = scheduledFor;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getVisibilityTimeoutUntil() {
        return visibilityTimeoutUntil;
    }

    public void setVisibilityTimeoutUntil(LocalDateTime visibilityTimeoutUntil) {
        this.visibilityTimeoutUntil = visibilityTimeoutUntil;
    }

    public LocalDateTime getLastErrorAt() {
        return lastErrorAt;
    }

    public void setLastErrorAt(LocalDateTime lastErrorAt) {
        this.lastErrorAt = lastErrorAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
