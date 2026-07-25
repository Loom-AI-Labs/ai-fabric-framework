package ai.fabric.relationship.integration.entity;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AIContextDestination;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "products")
@AICapable(entityType = "product")
public class ProductEntity {

    @Id
    @AIIdentity
    private String id;

    @AISearchable(priority = 100, required = true)
    @Column(nullable = false)
    private String name;

    @AISearchable(priority = 80)
    @Column(nullable = false)
    private String color;

    @AISearchable(priority = 70)
    @Column(nullable = false)
    private BigDecimal price;

    @AIContext
    @Column(nullable = false)
    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private BrandEntity brand;

    @AIContext(
        key = "brand",
        destinations = AIContextDestination.API_RESPONSE
    )
    public String getBrandName() {
        return brand == null ? null : brand.getName();
    }

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
