package com.subscription.hub.entity;

import com.ai.infrastructure.annotation.AICapable;
import com.ai.infrastructure.annotation.AIContext;
import com.ai.infrastructure.annotation.AISearchable;
import com.ai.infrastructure.indexing.api.IndexingStrategy;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "subscription_plans")
@AICapable(
    entityType = "subscription-plan",
    autoEmbedding = true,
    indexable = true,
    enableRecommendations = true,
    indexingStrategy = IndexingStrategy.ASYNC
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @AISearchable(weight = 2.0)
    @Column(nullable = false, unique = true)
    private String name;  // "Pro Plan", "Enterprise Plan"

    @AISearchable(weight = 1.5)
    @Column(columnDefinition = "TEXT")
    private String description;  // Full plan description

    @AIContext
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyPrice;

    @AIContext
    @Column(precision = 10, scale = 2)
    private BigDecimal annualPrice;

    @AIContext
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PlanTier tier;  // BASIC, PRO, ENTERPRISE

    @AIContext
    @ElementCollection
    @CollectionTable(name = "plan_features", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "feature")
    private List<String> features;  // ["Unlimited storage", "Priority support", ...]

    @AIContext
    private Integer maxUsers;

    @AIContext
    private Integer storageGB;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    public enum PlanTier {
        BASIC, PRO, ENTERPRISE
    }
}
