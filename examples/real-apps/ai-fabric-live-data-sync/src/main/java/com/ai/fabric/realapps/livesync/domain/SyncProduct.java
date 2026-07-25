package com.ai.fabric.realapps.livesync.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AISearchPreprocessing;
import ai.fabric.indexing.api.IndexingStrategy;
import com.ai.fabric.realapps.livesync.repository.SyncProductRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "sync_product",
    uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "record_key"})
)
@AICapable(
    entityType = SyncProduct.ENTITY_TYPE,
    indexingStrategy = IndexingStrategy.SYNC,
    onCreateStrategy = IndexingStrategy.AUTO,
    onUpdateStrategy = IndexingStrategy.SYNC,
    onDeleteStrategy = IndexingStrategy.SYNC,
    migrationRepository = SyncProductRepository.class
)
public class SyncProduct {

    public static final String ENTITY_TYPE = "sync-product";

    @Id
    @Column(nullable = false, length = 160)
    @AIIdentity
    @AIContext(key = "entityId", dataType = AIContextDataType.ID, priority = 100, required = true)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 80)
    @AIContext(key = "workspaceId", dataType = AIContextDataType.ID, priority = 100, required = true)
    private String workspaceId;

    @Column(name = "record_key", nullable = false, length = 80)
    @AIContext(key = "recordKey", dataType = AIContextDataType.ID, priority = 90, required = true)
    private String recordKey;

    @Column(nullable = false, length = 160)
    @AISearchable(priority = 100, preprocessing = AISearchPreprocessing.NORMALIZE, required = true)
    @AIContext(key = "title", description = "Public product name", priority = 90)
    private String title;

    @Column(nullable = false, length = 600)
    @AISearchable(priority = 80, preprocessing = AISearchPreprocessing.CLEAN, maxLength = 600, required = true)
    private String summary;

    @Column(nullable = false, length = 1200)
    @AISearchable(priority = 70, preprocessing = AISearchPreprocessing.NORMALIZE, maxLength = 1200)
    private String specification;

    @Column(nullable = false, length = 80)
    @AIContext(key = "category", dataType = AIContextDataType.STRING, description = "Product category", priority = 70)
    private String category;

    @Column(nullable = false, precision = 12, scale = 2)
    @AIContext(key = "price", dataType = AIContextDataType.NUMBER, format = "0.00", priority = 70)
    private BigDecimal price;

    @Column(nullable = false, length = 32)
    @AIContext(key = "status", dataType = AIContextDataType.STRING, description = "Catalog publication status", priority = 80)
    private String status;

    @Column(nullable = false)
    @AIContext(key = "revision", dataType = AIContextDataType.NUMBER, priority = 100, required = true)
    private Integer revision;

    @Column(nullable = false)
    @AIContext(key = "updatedAt", dataType = AIContextDataType.DATE, format = "yyyy-MM-dd'T'HH:mm:ss", priority = 50)
    private LocalDateTime updatedAt;
}
