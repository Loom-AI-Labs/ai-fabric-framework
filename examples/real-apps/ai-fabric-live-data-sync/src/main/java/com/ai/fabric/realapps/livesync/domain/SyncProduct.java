package com.ai.fabric.realapps.livesync.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AISearchable;
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
    features = {"embedding", "search", "rag"},
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
    @AIContext(contextKey = "entityId", dataType = "id", priority = 10, required = true)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 80)
    @AIContext(contextKey = "workspaceId", dataType = "id", priority = 10, required = true)
    private String workspaceId;

    @Column(name = "record_key", nullable = false, length = 80)
    @AIContext(contextKey = "recordKey", dataType = "id", priority = 9, required = true)
    private String recordKey;

    @Column(nullable = false, length = 160)
    @AISearchable(weight = 2.4, preprocessing = "normalize", required = true, tags = {"primary", "catalog"})
    @AIContext(contextKey = "title", description = "Public product name", priority = 9)
    private String title;

    @Column(nullable = false, length = 600)
    @AISearchable(weight = 1.8, preprocessing = "clean", maxLength = 600, required = true, tags = {"summary"})
    private String summary;

    @Column(nullable = false, length = 1200)
    @AISearchable(weight = 1.5, preprocessing = "normalize", maxLength = 1200, tags = {"specification"})
    private String specification;

    @Column(nullable = false, length = 80)
    @AIContext(contextKey = "category", dataType = "string", description = "Product category", priority = 7)
    private String category;

    @Column(nullable = false, precision = 12, scale = 2)
    @AIContext(contextKey = "price", dataType = "number", format = "0.00", priority = 7)
    private BigDecimal price;

    @Column(nullable = false, length = 32)
    @AIContext(contextKey = "status", dataType = "enum", description = "Catalog publication status", priority = 8)
    private String status;

    @Column(nullable = false)
    @AIContext(contextKey = "revision", dataType = "number", priority = 10, required = true)
    private Integer revision;

    @Column(nullable = false)
    @AIContext(contextKey = "updatedAt", dataType = "date", format = "yyyy-MM-dd'T'HH:mm:ss", priority = 5)
    private LocalDateTime updatedAt;
}
