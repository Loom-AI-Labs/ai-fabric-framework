package ai.fabric.it.entity;

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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Test entity used to validate v2 annotation-driven indexing (RealAPI integration tests).
 *
 * <p>This entity intentionally includes fields that are NOT present in the YAML-driven
 * {@code ai-entity-config-realapi.yml} for {@code test-product}. The end-to-end test verifies
 * that annotation-based extraction (via {@link ai.fabric.processor.AnnotationFieldScanner})
 * is used to build the content that gets embedded/indexed.</p>
 */
@Entity
@Table(name = "test_products_v2_annotated")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@AICapable(
    entityType = "product",
    indexingStrategy = IndexingStrategy.ASYNC,
    onDeleteStrategy = IndexingStrategy.SYNC
)
public class V2AnnotatedProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Exists only to satisfy non-null requirements and to demonstrate we can override YAML field selection.
    @Column(nullable = false, length = 255)
    private String name;

    /**
     * v2-only searchable field (NOT present in YAML config). This should be the only content that gets embedded.
     */
    @AISearchable(preprocessing = "none")
    @Column(columnDefinition = "TEXT")
    private String internalNotes;

    /**
     * v2-only context field (NOT present in YAML metadata config). Should be stored as metadata, not embedded.
     */
    @AIContext(contextKey = "owner", priority = 100)
    @Column(length = 100)
    private String contextOwner;

    @Column
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
