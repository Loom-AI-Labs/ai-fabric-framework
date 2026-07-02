package com.subscription.hub.entity;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.indexing.api.IndexingStrategy;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscriptions")
@AICapable(
    entityType = "subscription",
    autoEmbedding = false,
    indexable = true,
    indexingStrategy = IndexingStrategy.ASYNC
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @AIContext(contextKey = "status")
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @AIContext(contextKey = "startDate")
    @Column(nullable = false)
    private LocalDateTime startDate;

    @AIContext(contextKey = "endDate")
    private LocalDateTime endDate;

    @AIContext(contextKey = "billingCycle")
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private BillingCycle billingCycle;  // MONTHLY, ANNUAL

    @AIContext(contextKey = "churnRiskScore")
    private Double churnRiskScore;  // 0.0-1.0 from Behavior Analysis

    @AIContext(contextKey = "lastActivityDate")
    private LocalDateTime lastActivityDate;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "billing_address_id")
    private Address billingAddress;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "shipping_address_id")
    private Address shippingAddress;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_method_id")
    private PaymentMethod paymentMethod;

    // Note: planId is used directly; plan relationship removed to avoid JPA column mapping conflict
    // If needed, can be added as @ManyToOne with @JoinColumn(name = "plan_id", insertable = false, updatable = false)

    public enum SubscriptionStatus {
        ACTIVE, CANCELLED, PAST_DUE, EXPIRED
    }

    public enum BillingCycle {
        MONTHLY, ANNUAL
    }
}
