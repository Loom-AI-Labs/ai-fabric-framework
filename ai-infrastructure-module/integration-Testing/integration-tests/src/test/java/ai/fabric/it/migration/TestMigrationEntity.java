package ai.fabric.it.migration;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "it_migration_entities")
@AICapable(entityType = "mig-test", migrationRepository = TestMigrationRepository.class)
public class TestMigrationEntity {

    @Id
    @AIIdentity
    @AISearchable(name = "entityId", required = true)
    private String id;

    @Column(name = "created_at")
    @AIContext(
        key = "createdAt",
        dataType = AIContextDataType.DATE,
        destinations = AIContextDestination.VECTOR_METADATA
    )
    private LocalDateTime createdAt;

    public TestMigrationEntity() {
    }

    public TestMigrationEntity(String id, LocalDateTime createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
