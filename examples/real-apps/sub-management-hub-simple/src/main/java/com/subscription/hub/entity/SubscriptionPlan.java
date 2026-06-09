package com.subscription.hub.entity;

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
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;  // "Pro Plan", "Enterprise Plan"

    @Column(columnDefinition = "TEXT")
    private String description;  // Full plan description

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal annualPrice;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PlanTier tier;  // BASIC, PRO, ENTERPRISE

    @ElementCollection
    @CollectionTable(name = "plan_features", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "feature")
    private List<String> features;  // ["Unlimited storage", "Priority support", ...]

    private Integer maxUsers;

    private Integer storageGB;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    public enum PlanTier {
        BASIC, PRO, ENTERPRISE
    }
}
