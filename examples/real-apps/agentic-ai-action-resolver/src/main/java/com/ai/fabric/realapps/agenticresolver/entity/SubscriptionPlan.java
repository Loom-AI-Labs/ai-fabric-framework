package com.ai.fabric.realapps.agenticresolver.entity;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.IndexingStrategy;
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
    indexingStrategy = IndexingStrategy.ASYNC
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPlan {

    @Id
    @AIIdentity
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @AISearchable(priority = 100, required = true)
    @Column(nullable = false, unique = true)
    private String name;  // "Pro Plan", "Enterprise Plan"

    @AISearchable(priority = 80)
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
