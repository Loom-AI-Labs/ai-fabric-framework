package ai.fabric.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

/**
 * Durable per-entity ordering tombstone retained after vector deletion.
 */
@Entity
@Table(name = "ai_indexing_entity_state")
public class IndexingEntityState {

    @Id
    @Column(name = "state_key", length = 64)
    private String stateKey;

    @Column(name = "entity_type", nullable = false, length = 128)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 512)
    private String entityId;

    @Column(name = "last_applied_work_id", nullable = false)
    private long lastAppliedWorkId;

    @Column(name = "last_source_version")
    private Long lastSourceVersion;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private long version;

    protected IndexingEntityState() {
    }

    public IndexingEntityState(
        String stateKey,
        String entityType,
        String entityId,
        LocalDateTime updatedAt
    ) {
        this.stateKey = stateKey;
        this.entityType = entityType;
        this.entityId = entityId;
        this.updatedAt = updatedAt;
    }

    public String getStateKey() {
        return stateKey;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public long getLastAppliedWorkId() {
        return lastAppliedWorkId;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Long getLastSourceVersion() {
        return lastSourceVersion;
    }

    public void markApplied(long workId, Long sourceVersion, LocalDateTime now) {
        this.lastAppliedWorkId = workId;
        if (sourceVersion != null) {
            this.lastSourceVersion = sourceVersion;
        }
        this.updatedAt = now;
    }
}
