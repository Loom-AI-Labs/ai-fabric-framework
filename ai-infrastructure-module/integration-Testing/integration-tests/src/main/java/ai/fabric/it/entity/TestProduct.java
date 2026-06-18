package ai.fabric.it.entity;

import ai.fabric.annotation.AICapable;
import ai.fabric.indexing.api.IndexingStrategy;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Test Product Entity for AI Infrastructure Integration Tests
 *
 * This entity represents a product that can be processed by the AI infrastructure.
 * It includes various field types to test different AI processing scenarios.
 *
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Entity
@Table(name = "test_products")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@AICapable(
    entityType = "product",
    indexingStrategy = IndexingStrategy.ASYNC,
    onDeleteStrategy = IndexingStrategy.SYNC
)
public class TestProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String category;

    @Column(length = 100)
    private String brand;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Column(length = 50)
    private String sku;

    @Column
    private Integer stockQuantity;

    @Column
    private Boolean active;

    @Column
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Helper methods for testing
    public String getFullName() {
        return brand != null ? brand + " " + name : name;
    }

    public String getDisplayPrice() {
        return price != null ? "$" + price.toString() : "Price not set";
    }

    public boolean isInStock() {
        return stockQuantity != null && stockQuantity > 0;
    }
}
