package com.ai.fabric.realapps.chat.catalog.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AISearchable;
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
    @AIContext(
        contextKey = "id",
        dataType = "id",
        description = "Internal product identifier"
    )
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @AISearchable(weight = 1.6)
    @AIContext(
        contextKey = "sku",
        dataType = "id",
        description = "Product SKU"
    )
    @Column(nullable = false, unique = true)
    private String sku;

    @AISearchable(weight = 2.0)
    @Column(nullable = false)
    private String name;

    @AISearchable(weight = 1.7, maxLength = 20000)
    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @AISearchable(weight = 1.2)
    @AIContext(description = "Product category (e.g., Laptops, Headphones)")
    private String category;

    @AISearchable(weight = 1.1)
    @AIContext(description = "Comma-separated tags")
    @Column(columnDefinition = "TEXT")
    private String tags;

    @AIContext(description = "Public URL for the product image (not included in embeddings)")
    @Column(columnDefinition = "TEXT")
    private String imageUrl;

    @AISearchable(weight = 1.0)
    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    @AISearchable(weight = 0.6)
    @Column(nullable = false)
    private String currency = "USD";

    @AISearchable(weight = 0.5)
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
