package com.ai.fabric.realapps.chat.reviews.domain;

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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "review")
@AICapable(
    entityType = "review",
    indexingStrategy = IndexingStrategy.ASYNC,
    onCreateStrategy = IndexingStrategy.SYNC,
    onUpdateStrategy = IndexingStrategy.ASYNC,
    onDeleteStrategy = IndexingStrategy.SYNC
)
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @AIContext(description = "User id who wrote the review")
    @Column(nullable = false)
    private String userId;

    @AIContext(description = "Reviewed product id")
    private Long productId;

    @AIContext(description = "Reviewed product sku")
    private String sku;

    @Column(nullable = false)
    private Integer rating;

    @AISearchable(weight = 2.0, maxLength = 20000)
    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;

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
