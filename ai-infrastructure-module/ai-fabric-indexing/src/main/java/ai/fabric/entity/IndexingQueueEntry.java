package ai.fabric.entity;

import ai.fabric.indexing.IndexingActionPlan;
import ai.fabric.indexing.IndexingOperation;
import ai.fabric.indexing.IndexingPriority;
import ai.fabric.indexing.IndexingStatus;
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
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable queue entry for AI indexing tasks.
 */
@Getter
@Setter
@Entity
@Table(
    name = "ai_indexing_queue",
    indexes = {
        @Index(name = "idx_ai_queue_status_strategy", columnList = "status,strategy"),
        @Index(name = "idx_ai_queue_scheduled", columnList = "scheduled_for"),
        @Index(name = "idx_ai_queue_entity", columnList = "entity_type,entity_id")
    }
)
public class IndexingQueueEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "entity_type", nullable = false, length = 128)
    private String entityType;

    @Column(name = "entity_id", length = 128)
    private String entityId;

    @Column(name = "entity_class", nullable = false, length = 256)
    private String entityClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 32)
    private IndexingOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, length = 32)
    private IndexingStrategy strategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IndexingStatus status = IndexingStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 32)
    private IndexingPriority priority;

    @Column(name = "priority_weight", nullable = false)
    private int priorityWeight;

    @Column(name = "generate_embedding", nullable = false)
    private boolean generateEmbedding;

    @Column(name = "index_for_search", nullable = false)
    private boolean indexForSearch;

    @Column(name = "enable_analysis", nullable = false)
    private boolean enableAnalysis;

    @Column(name = "remove_from_search", nullable = false)
    private boolean removeFromSearch;

    @Column(name = "cleanup_embeddings", nullable = false)
    private boolean cleanupEmbeddings;

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 5;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "dead_letter_reason", columnDefinition = "TEXT")
    private String deadLetterReason;

    @Column(name = "processing_node", length = 64)
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

    public void applyActionPlan(IndexingActionPlan plan) {
        this.generateEmbedding = plan.generateEmbedding();
        this.indexForSearch = plan.indexForSearch();
        this.enableAnalysis = plan.enableAnalysis();
        this.removeFromSearch = plan.removeFromSearch();
        this.cleanupEmbeddings = plan.cleanupEmbeddings();
    }

    public IndexingActionPlan toActionPlan() {
        return new IndexingActionPlan(
            generateEmbedding,
            indexForSearch,
            enableAnalysis,
            removeFromSearch,
            cleanupEmbeddings
        );
    }

    public void initialize(IndexingStrategy strategy, IndexingPriority priority, LocalDateTime now) {
        this.strategy = strategy;
        this.priority = priority;
        this.priorityWeight = priority.getWeight();
        this.status = IndexingStatus.PENDING;
        this.requestedAt = now;
        if (this.scheduledFor == null) {
            this.scheduledFor = now;
        }
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String assignProcessingNode() {
        String node = UUID.randomUUID().toString();
        this.processingNode = node;
        return node;
    }
}
