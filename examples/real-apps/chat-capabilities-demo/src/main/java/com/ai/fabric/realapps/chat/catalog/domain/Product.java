package com.ai.fabric.realapps.chat.catalog.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.IndexingStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "product")
@AICapable(
    entityType = "product",
    indexingStrategy = IndexingStrategy.ASYNC,
    onCreateStrategy = IndexingStrategy.SYNC,
    onUpdateStrategy = IndexingStrategy.ASYNC,
    onDeleteStrategy = IndexingStrategy.SYNC
)
public class Product {

    @Id
    @AIIdentity
    @AIContext(
        key = "id",
        dataType = AIContextDataType.ID,
        description = "Internal product identifier"
    )
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @AISearchable(priority = 80)
    @AIContext(
        key = "sku",
        dataType = AIContextDataType.ID,
        description = "Product SKU"
    )
    @Column(nullable = false, unique = true)
    private String sku;

    @AISearchable(priority = 100)
    @Column(nullable = false)
    private String name;

    @AISearchable(priority = 90, maxLength = 20000)
    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @AISearchable(priority = 70)
    @AIContext(description = "Product category (e.g., Laptops, Headphones)")
    private String category;

    @AISearchable(priority = 60)
    @AIContext(description = "Comma-separated tags")
    @Column(columnDefinition = "TEXT")
    private String tags;

    @AIContext(description = "Public URL for the product image (not included in embeddings)")
    @Column(columnDefinition = "TEXT")
    private String imageUrl;

    @AISearchable(priority = 60)
    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    @AISearchable(priority = 40)
    @Column(nullable = false)
    private String currency = "USD";

    @AISearchable(priority = 30)
    @Column(nullable = false)
    private Integer inStockQty = 100;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
