package com.ai.fabric.realapps.cloudvector.domain;

import com.ai.infrastructure.annotation.AICapable;
import com.ai.infrastructure.annotation.AIContext;
import com.ai.infrastructure.annotation.AISearchable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "kb_article")
@AICapable(entityType = "kb-article")
public class KnowledgeBaseArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @AISearchable(weight = 2.0)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    @AISearchable(weight = 1.8)
    private String content;

    @AIContext(description = "Knowledge base category (e.g., auth, billing, troubleshooting)")
    private String category;

    @Column(columnDefinition = "TEXT")
    @AIContext(description = "Comma-separated tags for filtering and context")
    private String tags;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
