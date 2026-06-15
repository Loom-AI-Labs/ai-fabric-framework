package ai.fabric.governance.catalog.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
    name = "ai_index_catalog",
    indexes = {
        @Index(name = "idx_ai_index_catalog_type", columnList = "entity_type"),
        @Index(name = "idx_ai_index_catalog_updated", columnList = "indexed_updated_at")
    }
)
public class IndexCatalogEntity {

    @Id
    @Column(name = "catalog_key", nullable = false, length = 300)
    private String key;

    @Column(name = "entity_type", nullable = false, length = 128)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 128)
    private String entityId;

    @Column(name = "vector_id", length = 255)
    private String vectorId;

    @Column(name = "indexed_created_at")
    private LocalDateTime indexedCreatedAt;

    @Column(name = "indexed_updated_at")
    private LocalDateTime indexedUpdatedAt;

    @Lob
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    public static String buildKey(String entityType, String entityId) {
        return (entityType == null ? "" : entityType) + "::" + (entityId == null ? "" : entityId);
    }
}
