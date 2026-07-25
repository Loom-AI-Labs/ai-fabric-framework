# Multi-Agent Workflow Examples

**Version:** 1.0
**Date:** January 2026
**Purpose:** Practical examples of building multi-agent workflows with AI Fabric Framework

---

## Overview

This document provides **ready-to-use examples** of multi-agent workflows, ranging from simple 2-agent patterns to complex enterprise workflows with 10+ agents.

Each example includes:
- ✅ Complete code implementation
- ✅ Configuration files
- ✅ Test cases
- ✅ Step-by-step explanation
- ✅ Expected output

---

## Table of Contents

1. [Simple Examples (2-3 Agents)](#1-simple-examples-2-3-agents)
2. [Intermediate Examples (4-6 Agents)](#2-intermediate-examples-4-6-agents)
3. [Advanced Examples (7+ Agents)](#3-advanced-examples-7-agents)
4. [Testing Multi-Agent Workflows](#4-testing-multi-agent-workflows)
5. [Configuration Reference](#5-configuration-reference)

---

## 1. Simple Examples (2-3 Agents)

### Example 1.1: Two-Step Information + Action

**Scenario:** User wants to see their subscription details and then cancel it

**Agents Involved:**
1. RAG Agent (retrieve subscription info)
2. Subscription Action Agent (cancel subscription)

#### Implementation

**Step 1: Create Subscription Entity**

```java
// File: src/main/java/com/example/app/domain/Subscription.java

package com.example.app.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.IndexingStrategy;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscriptions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@AICapable(
    entityType = "subscription",
    indexingStrategy = IndexingStrategy.ASYNC
)
public class Subscription {

    @Id
    @GeneratedValue
    @AIIdentity
    private UUID id;

    @Column(nullable = false)
    private String userId;

    @AISearchable(priority = 100, required = true)
    @Column(nullable = false)
    private String planName;

    @AISearchable(priority = 80)
    private String description;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private String status;  // ACTIVE, CANCELLED, EXPIRED

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    private LocalDateTime cancelledAt;
}
```

**Step 2: Create Cancellation Action Handler**

```java
// File: src/main/java/com/example/app/action/CancelSubscriptionActionHandler.java

package com.example.app.action;

import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.ActionHandler;
import com.ai.infrastructure.intent.action.ActionResult;
import com.example.app.domain.Subscription;
import com.example.app.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CancelSubscriptionActionHandler implements ActionHandler {

    private final SubscriptionService subscriptionService;

    @Override
    public AIActionMetaData getActionMetadata() {
        return AIActionMetaData.builder()
            .name("cancel_subscription")
            .description("Cancel user's active subscription")
            .category("subscription-management")
            .parameters(Map.of(
                "subscriptionId", "Optional: Specific subscription ID to cancel",
                "reason", "Optional: Reason for cancellation"
            ))
            .build();
    }

    @Override
    public boolean validateActionAllowed(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        // Check if user has active subscription
        return subscriptionService.hasActiveSubscription(userId);
    }

    @Override
    public String getConfirmationMessage(Map<String, Object> params) {
        return "Are you sure you want to cancel your subscription? " +
               "You will lose access to premium features.";
    }

    @Override
    public ActionResult executeAction(Map<String, Object> params, String userId) {
        try {
            log.info("Cancelling subscription for user: {}", userId);

            String subscriptionId = (String) params.get("subscriptionId");
            String reason = (String) params.getOrDefault("reason", "User requested");

            // Find active subscription
            Subscription subscription = subscriptionId != null
                ? subscriptionService.findById(subscriptionId, userId)
                : subscriptionService.findActiveSubscription(userId);

            if (subscription == null) {
                return ActionResult.builder()
                    .success(false)
                    .errorCode("NO_ACTIVE_SUBSCRIPTION")
                    .message("You don't have an active subscription to cancel")
                    .build();
            }

            // Perform cancellation
            subscription.setStatus("CANCELLED");
            subscription.setCancelledAt(LocalDateTime.now());
            subscriptionService.save(subscription);

            log.info("Successfully cancelled subscription {} for user {}",
                subscription.getId(), userId);

            return ActionResult.builder()
                .success(true)
                .message(String.format(
                    "Your %s subscription has been cancelled. " +
                    "You'll retain access until %s.",
                    subscription.getPlanName(),
                    subscription.getExpiresAt()
                ))
                .data(Map.of(
                    "subscriptionId", subscription.getId().toString(),
                    "planName", subscription.getPlanName(),
                    "accessUntil", subscription.getExpiresAt().toString(),
                    "cancelledAt", subscription.getCancelledAt().toString()
                ))
                .build();

        } catch (Exception e) {
            return handleError(e, userId);
        }
    }

    @Override
    public ActionResult handleError(Exception e, String userId) {
        log.error("Failed to cancel subscription for user {}", userId, e);
        return ActionResult.builder()
            .success(false)
            .errorCode("CANCELLATION_FAILED")
            .message("We couldn't process your cancellation. Please contact support.")
            .data(Map.of("error", e.getMessage()))
            .build();
    }
}
```

**Step 3: Create Service Layer**

```java
// File: src/main/java/com/example/app/service/SubscriptionService.java

package com.example.app.service;

import com.example.app.domain.Subscription;
import com.example.app.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;

    public boolean hasActiveSubscription(String userId) {
        return subscriptionRepository.existsByUserIdAndStatus(userId, "ACTIVE");
    }

    public Subscription findActiveSubscription(String userId) {
        return subscriptionRepository.findByUserIdAndStatus(userId, "ACTIVE")
            .orElse(null);
    }

    public Subscription findById(String subscriptionId, String userId) {
        return subscriptionRepository.findByIdAndUserId(
            UUID.fromString(subscriptionId),
            userId
        ).orElse(null);
    }

    @Transactional
    public Subscription save(Subscription subscription) {
        return subscriptionRepository.save(subscription);
    }
}
```

**Step 4: Configuration**

```yaml
# File: src/main/resources/application.yml

ai:
  enabled: true

  providers:
    llm-provider: openai
    embedding-provider: onnx

  openai:
    api-key: ${OPENAI_API_KEY}

  vector:
    database-type: lucene

  indexing:
    enabled: true
    default-strategy: ASYNC

  orchestration:
    enabled: true
    compound:
      enabled: true
      strategy: sequential  # Execute intents one after another
      max-intents: 5
```

**Step 5: Usage Example**

```java
// File: src/main/java/com/example/app/controller/SubscriptionController.java

package com.example.app.controller;

import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.RAGOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final RAGOrchestrator orchestrator;

    @PostMapping("/assistant")
    public ResponseEntity<OrchestrationResult> handleQuery(
            @RequestBody String query,
            @RequestHeader("User-Id") String userId) {

        // Create orchestration context
        OrchestrationContext context = OrchestrationContext.builder()
            .userId(userId)
            .build();

        // Let orchestrator handle the multi-agent workflow
        OrchestrationResult result = orchestrator.orchestrate(query, context);

        return ResponseEntity.ok(result);
    }
}
```

**Step 6: Test the Workflow**

```java
// File: src/test/java/com/example/app/TwoStepWorkflowTest.java

package com.example.app;

import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultType;
import com.ai.infrastructure.intent.orchestration.RAGOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TwoStepWorkflowTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @Test
    void shouldHandleShowAndCancelSubscription() {
        // User query with TWO intents
        String query = "Show me my subscription details and cancel it";

        OrchestrationContext context = OrchestrationContext.builder()
            .userId("test-user-123")
            .build();

        // Execute multi-agent workflow
        OrchestrationResult result = orchestrator.orchestrate(query, context);

        // Verify compound intent handling
        assertThat(result.getType())
            .isEqualTo(OrchestrationResultType.COMPOUND_HANDLED);

        // Verify 2 child results
        assertThat(result.getChildren()).hasSize(2);

        // First intent: INFORMATION (show subscription)
        OrchestrationResult firstIntent = result.getChildren().get(0);
        assertThat(firstIntent.getType())
            .isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(firstIntent.getMessage())
            .contains("Premium", "$29");

        // Second intent: ACTION (cancel subscription)
        OrchestrationResult secondIntent = result.getChildren().get(1);
        assertThat(secondIntent.getType())
            .isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(secondIntent.getMessage())
            .contains("cancelled");
    }
}
```

#### Execution Flow

```
User Query: "Show me my subscription details and cancel it"
    ↓
┌─────────────────────────────────────────────────────────────┐
│ 1. Security Agent: ✅ No threats detected                   │
├─────────────────────────────────────────────────────────────┤
│ 2. Access Control Agent: ✅ User authenticated              │
├─────────────────────────────────────────────────────────────┤
│ 3. Intent Extraction Agent: Detects COMPOUND intent         │
│    - Intent 1: INFORMATION (show subscription details)      │
│    - Intent 2: ACTION (cancel_subscription)                 │
├─────────────────────────────────────────────────────────────┤
│ 4. Intent Handling Agent: Sequential execution              │
│                                                              │
│    4a. RAG Agent (INFORMATION)                              │
│        - Searches vector DB for user's subscription         │
│        - Returns: "Premium plan, $29/month, renews Feb 1"   │
│                                                              │
│    4b. Cancel Subscription Agent (ACTION)                   │
│        - Validates user has active subscription             │
│        - Requests confirmation                              │
│        - Executes cancellation                              │
│        - Returns: "Subscription cancelled, access until..." │
├─────────────────────────────────────────────────────────────┤
│ 5. Response Agent: Merges both results                      │
└─────────────────────────────────────────────────────────────┘

Final Response:
{
  "type": "COMPOUND_HANDLED",
  "success": true,
  "children": [
    {
      "type": "INFORMATION_PROVIDED",
      "message": "You have Premium subscription at $29/month, renews Feb 1"
    },
    {
      "type": "ACTION_EXECUTED",
      "message": "Your Premium subscription has been cancelled.
                  You'll retain access until Feb 1, 2026."
    }
  ]
}
```

---

### Example 1.2: Retrieve + Calculate (2 Agents)

**Scenario:** User asks for their total spending

**Agents Involved:**
1. Order Retrieval Agent (fetch user orders)
2. Calculation Agent (sum order totals)

#### Implementation

```java
// File: src/main/java/com/example/app/action/GetTotalSpendingActionHandler.java

package com.example.app.action;

import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.ActionHandler;
import com.ai.infrastructure.intent.action.ActionResult;
import com.example.app.domain.Order;
import com.example.app.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetTotalSpendingActionHandler implements ActionHandler {

    private final OrderService orderService;

    @Override
    public AIActionMetaData getActionMetadata() {
        return AIActionMetaData.builder()
            .name("get_total_spending")
            .description("Calculate total spending for user over a time period")
            .category("analytics")
            .parameters(Map.of(
                "timeRange", "Optional: Time range (7d, 30d, 90d, all-time)",
                "status", "Optional: Filter by order status (completed, pending, etc.)"
            ))
            .build();
    }

    @Override
    public boolean validateActionAllowed(String userId) {
        return userId != null && !userId.isBlank();
    }

    @Override
    public ActionResult executeAction(Map<String, Object> params, String userId) {
        try {
            String timeRange = (String) params.getOrDefault("timeRange", "all-time");
            String status = (String) params.get("status");

            log.info("Calculating total spending for user: {}, range: {}",
                userId, timeRange);

            // AGENT 1: Order Retrieval Agent
            LocalDateTime since = parseTimeRange(timeRange);
            List<Order> orders = orderService.getOrders(userId, since, status);

            // AGENT 2: Calculation Agent
            BigDecimal totalSpending = orders.stream()
                .map(Order::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal avgOrderValue = orders.isEmpty()
                ? BigDecimal.ZERO
                : totalSpending.divide(
                    BigDecimal.valueOf(orders.size()),
                    2,
                    BigDecimal.ROUND_HALF_UP
                  );

            return ActionResult.builder()
                .success(true)
                .message(String.format(
                    "You've spent $%.2f across %d orders in the last %s",
                    totalSpending,
                    orders.size(),
                    timeRange
                ))
                .data(Map.of(
                    "totalSpending", totalSpending,
                    "orderCount", orders.size(),
                    "averageOrderValue", avgOrderValue,
                    "timeRange", timeRange,
                    "orders", orders.stream()
                        .map(order -> Map.of(
                            "orderId", order.getId().toString(),
                            "date", order.getCreatedAt().toString(),
                            "total", order.getTotal(),
                            "status", order.getStatus()
                        ))
                        .toList()
                ))
                .build();

        } catch (Exception e) {
            return handleError(e, userId);
        }
    }

    @Override
    public ActionResult handleError(Exception e, String userId) {
        log.error("Failed to calculate spending for user {}", userId, e);
        return ActionResult.builder()
            .success(false)
            .errorCode("CALCULATION_FAILED")
            .message("Unable to calculate your spending. Please try again.")
            .build();
    }

    private LocalDateTime parseTimeRange(String range) {
        return switch (range.toLowerCase()) {
            case "7d" -> LocalDateTime.now().minusDays(7);
            case "30d" -> LocalDateTime.now().minusDays(30);
            case "90d" -> LocalDateTime.now().minusDays(90);
            default -> LocalDateTime.of(2000, 1, 1, 0, 0);  // all-time
        };
    }

    @Override
    public String getConfirmationMessage(Map<String, Object> params) {
        return null;  // No confirmation needed for read-only
    }
}
```

**Usage:**

```java
// Query: "How much have I spent in the last 30 days?"
OrchestrationResult result = orchestrator.orchestrate(query, context);

// Response:
{
  "success": true,
  "message": "You've spent $1,247.50 across 8 orders in the last 30d",
  "data": {
    "totalSpending": 1247.50,
    "orderCount": 8,
    "averageOrderValue": 155.94,
    "timeRange": "30d",
    "orders": [...]
  }
}
```

---

## 2. Intermediate Examples (4-6 Agents)

### Example 2.1: Smart Retention Workflow (5 Agents)

**Scenario:** User wants to cancel, system detects churn risk and offers retention

**Agents Involved:**
1. Intent Extraction Agent
2. Behavior Analytics Agent (detects churn risk)
3. Subscription Retrieval Agent
4. Retention Offer Agent
5. Cancellation Agent (if retention declined)

#### Implementation

```java
// File: src/main/java/com/example/app/action/SmartCancellationActionHandler.java

package com.example.app.action;

import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.ActionHandler;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.example.app.domain.Subscription;
import com.example.app.service.BehaviorAnalysisService;
import com.example.app.service.RetentionService;
import com.example.app.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartCancellationActionHandler implements ActionHandler {

    private final SubscriptionService subscriptionService;
    private final BehaviorAnalysisService behaviorAnalysisService;
    private final RetentionService retentionService;

    @Override
    public AIActionMetaData getActionMetadata() {
        return AIActionMetaData.builder()
            .name("smart_cancel_subscription")
            .description("Cancel subscription with smart retention offers")
            .category("subscription-management")
            .build();
    }

    @Override
    public boolean validateActionAllowed(String userId) {
        return userId != null && subscriptionService.hasActiveSubscription(userId);
    }

    @Override
    public ActionResult executeAction(Map<String, Object> params, String userId) {
        try {
            log.info("Smart cancellation requested for user: {}", userId);

            // AGENT 1: Behavior Analytics Agent
            var behaviorContext = behaviorAnalysisService.analyze(userId);
            double churnRisk = behaviorContext.getChurnRisk();
            String sentiment = behaviorContext.getSentiment();

            log.info("User {} churn risk: {}, sentiment: {}",
                userId, churnRisk, sentiment);

            // AGENT 2: Subscription Retrieval Agent
            Subscription subscription = subscriptionService.findActiveSubscription(userId);
            if (subscription == null) {
                return ActionResult.builder()
                    .success(false)
                    .errorCode("NO_ACTIVE_SUBSCRIPTION")
                    .message("You don't have an active subscription")
                    .build();
            }

            // AGENT 3: Retention Offer Agent (if high churn risk)
            if (churnRisk > 0.7) {
                log.info("HIGH churn risk detected, offering retention deal");

                var retentionOffer = retentionService.createOffer(
                    userId,
                    subscription,
                    churnRisk
                );

                return ActionResult.builder()
                    .success(true)
                    .requiresConfirmation(true)
                    .confirmationMessage(String.format(
                        "Before you cancel, as a valued customer, we'd like to " +
                        "offer you %d%% off for %d months (just $%.2f/month). " +
                        "Would you like to accept this offer instead of cancelling?",
                        retentionOffer.getDiscountPercent(),
                        retentionOffer.getDurationMonths(),
                        retentionOffer.getDiscountedPrice()
                    ))
                    .data(Map.of(
                        "offerId", retentionOffer.getId().toString(),
                        "originalPrice", subscription.getPrice(),
                        "discountedPrice", retentionOffer.getDiscountedPrice(),
                        "discountPercent", retentionOffer.getDiscountPercent(),
                        "durationMonths", retentionOffer.getDurationMonths(),
                        "churnRisk", churnRisk,
                        "sentiment", sentiment
                    ))
                    .build();
            }

            // AGENT 4: Cancellation Agent (if low/medium churn risk)
            log.info("Proceeding with standard cancellation");

            return proceedWithCancellation(subscription, userId);

        } catch (Exception e) {
            return handleError(e, userId);
        }
    }

    private ActionResult proceedWithCancellation(Subscription subscription, String userId) {
        subscription.setStatus("CANCELLED");
        subscription.setCancelledAt(java.time.LocalDateTime.now());
        subscriptionService.save(subscription);

        return ActionResult.builder()
            .success(true)
            .message(String.format(
                "Your %s subscription has been cancelled. " +
                "You'll retain access until %s.",
                subscription.getPlanName(),
                subscription.getExpiresAt()
            ))
            .data(Map.of(
                "subscriptionId", subscription.getId().toString(),
                "planName", subscription.getPlanName(),
                "accessUntil", subscription.getExpiresAt().toString()
            ))
            .build();
    }

    @Override
    public ActionResult handleError(Exception e, String userId) {
        log.error("Smart cancellation failed for user {}", userId, e);
        return ActionResult.builder()
            .success(false)
            .errorCode("CANCELLATION_FAILED")
            .message("Unable to process cancellation. Please contact support.")
            .build();
    }

    @Override
    public String getConfirmationMessage(Map<String, Object> params) {
        return "Are you sure you want to cancel your subscription?";
    }
}
```

**Behavior Analysis Service:**

```java
// File: src/main/java/com/example/app/service/BehaviorAnalysisService.java

package com.example.app.service;

import com.example.app.dto.BehaviorContext;
import com.example.app.repository.UserActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class BehaviorAnalysisService {

    private final UserActivityRepository activityRepository;

    public BehaviorContext analyze(String userId) {
        // Analyze user behavior over last 30 days
        LocalDateTime since = LocalDateTime.now().minusDays(30);

        long loginCount = activityRepository.countLogins(userId, since);
        long featureUsageCount = activityRepository.countFeatureUsage(userId, since);
        long supportTicketCount = activityRepository.countSupportTickets(userId, since);

        // Calculate churn risk (0.0 - 1.0)
        double churnRisk = calculateChurnRisk(
            loginCount,
            featureUsageCount,
            supportTicketCount
        );

        // Determine sentiment
        String sentiment = determineSentiment(supportTicketCount, featureUsageCount);

        return BehaviorContext.builder()
            .userId(userId)
            .churnRisk(churnRisk)
            .sentiment(sentiment)
            .loginCount(loginCount)
            .featureUsageCount(featureUsageCount)
            .supportTicketCount(supportTicketCount)
            .build();
    }

    private double calculateChurnRisk(long logins, long featureUsage, long supportTickets) {
        // Simple scoring algorithm
        double riskScore = 0.0;

        // Low engagement = higher churn risk
        if (logins < 5) riskScore += 0.3;
        else if (logins < 10) riskScore += 0.1;

        if (featureUsage < 10) riskScore += 0.3;
        else if (featureUsage < 25) riskScore += 0.1;

        // Many support tickets = higher churn risk
        if (supportTickets > 5) riskScore += 0.4;
        else if (supportTickets > 2) riskScore += 0.2;

        return Math.min(1.0, riskScore);
    }

    private String determineSentiment(long supportTickets, long featureUsage) {
        if (supportTickets > 5) return "VERY_NEGATIVE";
        if (supportTickets > 2) return "NEGATIVE";
        if (featureUsage > 50) return "POSITIVE";
        if (featureUsage > 25) return "NEUTRAL";
        return "NEGATIVE";
    }
}
```

**Retention Service:**

```java
// File: src/main/java/com/example/app/service/RetentionService.java

package com.example.app.service;

import com.example.app.domain.RetentionOffer;
import com.example.app.domain.Subscription;
import com.example.app.repository.RetentionOfferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RetentionService {

    private final RetentionOfferRepository retentionOfferRepository;

    public RetentionOffer createOffer(String userId,
                                     Subscription subscription,
                                     double churnRisk) {
        // Higher churn risk = better offer
        int discountPercent;
        int durationMonths;

        if (churnRisk > 0.9) {
            discountPercent = 75;  // 75% off
            durationMonths = 6;
        } else if (churnRisk > 0.7) {
            discountPercent = 50;  // 50% off
            durationMonths = 3;
        } else {
            discountPercent = 25;  // 25% off
            durationMonths = 2;
        }

        BigDecimal discountedPrice = subscription.getPrice()
            .multiply(BigDecimal.valueOf(100 - discountPercent))
            .divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);

        RetentionOffer offer = RetentionOffer.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .subscriptionId(subscription.getId())
            .originalPrice(subscription.getPrice())
            .discountedPrice(discountedPrice)
            .discountPercent(discountPercent)
            .durationMonths(durationMonths)
            .churnRisk(churnRisk)
            .status("PENDING")
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusDays(7))
            .build();

        return retentionOfferRepository.save(offer);
    }
}
```

#### Execution Flow

```
User Query: "I want to cancel my subscription"
    ↓
┌─────────────────────────────────────────────────────────────┐
│ 1. Security Agent: ✅ No threats                            │
├─────────────────────────────────────────────────────────────┤
│ 2. Access Control Agent: ✅ Authenticated                   │
├─────────────────────────────────────────────────────────────┤
│ 3. Intent Agent: ACTION (smart_cancel_subscription)         │
├─────────────────────────────────────────────────────────────┤
│ 4. Behavior Analytics Agent:                                │
│    - Analyzes user activity (last 30 days)                  │
│    - Logins: 3 (LOW)                                        │
│    - Feature usage: 8 (LOW)                                 │
│    - Support tickets: 6 (HIGH)                              │
│    → Churn Risk: 0.85 (HIGH)                                │
│    → Sentiment: VERY_NEGATIVE                               │
├─────────────────────────────────────────────────────────────┤
│ 5. Subscription Retrieval Agent:                            │
│    - Finds active subscription: Premium, $29/month          │
├─────────────────────────────────────────────────────────────┤
│ 6. Retention Offer Agent: (triggered by HIGH churn risk)    │
│    - Churn risk > 0.7 → Create special offer               │
│    - Offer: 50% off for 3 months ($14.50/month)            │
│    - Requires user confirmation                             │
└─────────────────────────────────────────────────────────────┘

Response:
{
  "success": true,
  "requiresConfirmation": true,
  "confirmationMessage": "Before you cancel, as a valued customer,
    we'd like to offer you 50% off for 3 months (just $14.50/month).
    Would you like to accept this offer instead of cancelling?",
  "data": {
    "offerId": "offer-uuid-123",
    "originalPrice": 29.00,
    "discountedPrice": 14.50,
    "discountPercent": 50,
    "durationMonths": 3,
    "churnRisk": 0.85,
    "sentiment": "VERY_NEGATIVE"
  }
}

┌─── If User Accepts ──────────────────────────────────────────┐
│ Apply retention offer, update subscription                   │
│ Response: "Great! We've applied 50% discount for 3 months"   │
└──────────────────────────────────────────────────────────────┘

┌─── If User Declines ─────────────────────────────────────────┐
│ 7. Cancellation Agent: Process cancellation                  │
│    Response: "Subscription cancelled, access until Feb 1"    │
└──────────────────────────────────────────────────────────────┘
```

---

### Example 2.2: Order Status with Escalation (6 Agents)

**Scenario:** User asks about delayed order, system detects frustration and auto-escalates

**Agents Involved:**
1. Sentiment Analysis Agent
2. Order Retrieval Agent
3. Shipping Tracker Agent (external API)
4. Escalation Agent (creates priority ticket)
5. Refund Agent (applies compensation)
6. Notification Agent (sends updates)

#### Implementation

```java
// File: src/main/java/com/example/app/action/OrderStatusActionHandler.java

package com.example.app.action;

import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.ActionHandler;
import com.ai.infrastructure.intent.action.ActionResult;
import com.example.app.domain.Order;
import com.example.app.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusActionHandler implements ActionHandler {

    private final OrderService orderService;
    private final SentimentAnalysisService sentimentService;
    private final ShippingTrackerService shippingTrackerService;
    private final EscalationService escalationService;
    private final RefundService refundService;
    private final NotificationService notificationService;

    @Override
    public AIActionMetaData getActionMetadata() {
        return AIActionMetaData.builder()
            .name("check_order_status")
            .description("Check order status with smart escalation for delays")
            .category("order-management")
            .parameters(Map.of(
                "orderId", "Optional: Specific order ID to check"
            ))
            .build();
    }

    @Override
    public boolean validateActionAllowed(String userId) {
        return userId != null && !userId.isBlank();
    }

    @Override
    public ActionResult executeAction(Map<String, Object> params, String userId) {
        try {
            log.info("Checking order status for user: {}", userId);

            // AGENT 1: Sentiment Analysis (from original query context)
            String originalQuery = (String) params.get("_originalQuery");
            double sentimentScore = sentimentService.analyzeSentiment(originalQuery);
            boolean isNegative = sentimentScore < -0.3;

            log.info("Query sentiment: {} (negative: {})", sentimentScore, isNegative);

            // AGENT 2: Order Retrieval Agent
            String orderId = (String) params.get("orderId");
            Order order = orderId != null
                ? orderService.findById(orderId, userId)
                : orderService.findMostRecentOrder(userId);

            if (order == null) {
                return ActionResult.builder()
                    .success(false)
                    .errorCode("NO_ORDERS_FOUND")
                    .message("We couldn't find any recent orders for your account")
                    .build();
            }

            // AGENT 3: Shipping Tracker Agent (external API call)
            var trackingInfo = shippingTrackerService.track(
                order.getTrackingNumber(),
                order.getCarrier()
            );

            // Check if order is delayed
            long daysSinceShipped = ChronoUnit.DAYS.between(
                order.getShippedAt(),
                LocalDateTime.now()
            );
            boolean isDelayed = daysSinceShipped > 7 && !trackingInfo.isDelivered();

            // AGENT 4 & 5: Escalation + Refund (if delayed AND negative sentiment)
            if (isDelayed && isNegative) {
                log.warn("ESCALATION triggered: Order {} delayed {} days, negative sentiment",
                    order.getId(), daysSinceShipped);

                // AGENT 4: Create priority support ticket
                var ticket = escalationService.createPriorityTicket(
                    userId,
                    order,
                    "Delayed order with customer frustration",
                    "HIGH"
                );

                // AGENT 5: Apply automatic compensation refund
                BigDecimal refundAmount = order.getTotal()
                    .multiply(BigDecimal.valueOf(0.15));  // 15% refund

                var refund = refundService.processRefund(
                    order.getId(),
                    refundAmount,
                    "Automatic compensation for delayed order"
                );

                // AGENT 6: Send notifications (async, don't block response)
                CompletableFuture.runAsync(() -> {
                    notificationService.sendEmail(userId, "Order Delay Compensation",
                        buildCompensationEmail(order, refund, ticket));
                    notificationService.sendSMS(userId,
                        String.format("We've applied a $%.2f refund for order %s delay",
                            refundAmount, order.getId()));
                });

                return ActionResult.builder()
                    .success(true)
                    .message(String.format(
                        "I sincerely apologize for the delay with order #%s. " +
                        "Your package was shipped %d days ago and is currently %s. " +
                        "To make this right, I've:\n" +
                        "✅ Applied a $%.2f refund (15%% of order total)\n" +
                        "✅ Created priority support ticket #%s\n" +
                        "✅ Escalated to our fulfillment team\n\n" +
                        "Expected delivery: %s",
                        order.getId(),
                        daysSinceShipped,
                        trackingInfo.getStatus(),
                        refundAmount,
                        ticket.getId(),
                        trackingInfo.getEstimatedDelivery()
                    ))
                    .data(Map.of(
                        "orderId", order.getId().toString(),
                        "trackingNumber", order.getTrackingNumber(),
                        "status", trackingInfo.getStatus(),
                        "daysInTransit", daysSinceShipped,
                        "estimatedDelivery", trackingInfo.getEstimatedDelivery(),
                        "refundApplied", true,
                        "refundAmount", refundAmount,
                        "ticketId", ticket.getId().toString(),
                        "escalated", true
                    ))
                    .build();
            }

            // Normal case: No escalation needed
            return ActionResult.builder()
                .success(true)
                .message(String.format(
                    "Your order #%s is %s. %s",
                    order.getId(),
                    trackingInfo.getStatus(),
                    trackingInfo.isDelivered()
                        ? "It was delivered on " + trackingInfo.getDeliveredAt()
                        : "Expected delivery: " + trackingInfo.getEstimatedDelivery()
                ))
                .data(Map.of(
                    "orderId", order.getId().toString(),
                    "trackingNumber", order.getTrackingNumber(),
                    "carrier", order.getCarrier(),
                    "status", trackingInfo.getStatus(),
                    "estimatedDelivery", trackingInfo.getEstimatedDelivery(),
                    "delivered", trackingInfo.isDelivered()
                ))
                .build();

        } catch (Exception e) {
            return handleError(e, userId);
        }
    }

    @Override
    public ActionResult handleError(Exception e, String userId) {
        log.error("Failed to check order status for user {}", userId, e);
        return ActionResult.builder()
            .success(false)
            .errorCode("ORDER_STATUS_ERROR")
            .message("We're having trouble retrieving your order status. " +
                    "Please try again in a moment.")
            .build();
    }

    @Override
    public String getConfirmationMessage(Map<String, Object> params) {
        return null;  // No confirmation needed
    }

    private String buildCompensationEmail(Order order, Refund refund, Ticket ticket) {
        return String.format("""
            Dear Valued Customer,

            We sincerely apologize for the delay with your order #%s.

            As a gesture of goodwill, we've applied a $%.2f refund to your account.

            We've also created a priority support ticket (#%s) and our fulfillment
            team has been notified to expedite your delivery.

            Thank you for your patience.

            Best regards,
            Customer Support Team
            """, order.getId(), refund.getAmount(), ticket.getId());
    }
}
```

#### Test Case

```java
// File: src/test/java/com/example/app/OrderStatusEscalationTest.java

@SpringBootTest
class OrderStatusEscalationTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @Test
    void shouldEscalateDelayedOrderWithNegativeSentiment() {
        // Frustrated user query
        String query = "I ordered a laptop 2 weeks ago and it STILL hasn't arrived! " +
                      "This is ridiculous!";

        OrchestrationContext context = OrchestrationContext.builder()
            .userId("user-123")
            .build();

        OrchestrationResult result = orchestrator.orchestrate(query, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("apologize", "refund", "priority");

        Map<String, Object> data = result.getData();
        assertThat(data.get("escalated")).isEqualTo(true);
        assertThat(data.get("refundApplied")).isEqualTo(true);
        assertThat(data.get("ticketId")).isNotNull();
    }
}
```

---

## 3. Advanced Examples (7+ Agents)

### Example 3.1: Financial Transfer with Fraud Detection (10 Agents)

**Scenario:** User transfers large amount, system performs comprehensive risk assessment

**Agents Involved:**
1. Intent Extraction Agent
2. Account Validation Agent
3. Balance Check Agent
4. Fraud Detection Agent
5. Behavior Analytics Agent
6. AML Compliance Agent
7. 2FA Verification Agent
8. Transfer Execution Agent
9. Notification Agent
10. Audit Logging Agent

#### Implementation

```java
// File: src/main/java/com/example/app/action/SecureTransferActionHandler.java

package com.example.app.action;

import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.ActionHandler;
import com.ai.infrastructure.intent.action.ActionResult;
import com.example.app.domain.Transfer;
import com.example.app.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecureTransferActionHandler implements ActionHandler {

    private final AccountService accountService;
    private final FraudDetectionService fraudService;
    private final BehaviorAnalysisService behaviorService;
    private final ComplianceService complianceService;
    private final TwoFactorAuthService twoFAService;
    private final TransferService transferService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    @Override
    public AIActionMetaData getActionMetadata() {
        return AIActionMetaData.builder()
            .name("transfer_funds")
            .description("Securely transfer funds between accounts with fraud detection")
            .category("banking")
            .parameters(Map.of(
                "amount", "Amount to transfer (required)",
                "fromAccount", "Source account ID (optional, defaults to primary)",
                "toAccount", "Destination account ID or account number (required)",
                "memo", "Optional transfer memo"
            ))
            .build();
    }

    @Override
    public boolean validateActionAllowed(String userId) {
        return userId != null && !userId.isBlank();
    }

    @Override
    public ActionResult executeAction(Map<String, Object> params, String userId) {
        try {
            // Track workflow state for debugging
            WorkflowState workflow = new WorkflowState();
            workflow.start("secure_transfer");

            // Extract parameters
            BigDecimal amount = new BigDecimal(params.get("amount").toString());
            String toAccountId = (String) params.get("toAccount");
            String memo = (String) params.getOrDefault("memo", "");

            log.info("Transfer request: user={}, amount={}, to={}",
                userId, amount, toAccountId);

            // AGENT 1: Account Validation Agent
            workflow.recordStep("account_validation");
            var fromAccount = accountService.getPrimaryAccount(userId);
            var toAccount = accountService.findAccount(toAccountId, userId);

            if (fromAccount == null) {
                return buildError("NO_SOURCE_ACCOUNT",
                    "Source account not found", workflow);
            }

            if (toAccount == null) {
                return buildError("INVALID_DESTINATION",
                    "Destination account not found", workflow);
            }

            // Security: Verify user owns destination account
            if (!toAccount.getUserId().equals(userId)) {
                log.warn("SECURITY: User {} attempted transfer to account owned by {}",
                    userId, toAccount.getUserId());
                return buildError("UNAUTHORIZED_TRANSFER",
                    "You can only transfer to your own accounts", workflow);
            }

            // AGENT 2: Balance Check Agent
            workflow.recordStep("balance_check");
            if (fromAccount.getBalance().compareTo(amount) < 0) {
                return buildError("INSUFFICIENT_FUNDS",
                    String.format("Insufficient funds. Available: $%.2f",
                        fromAccount.getBalance()), workflow);
            }

            // AGENT 3: Fraud Detection Agent
            workflow.recordStep("fraud_detection");
            var fraudCheck = fraudService.analyzeTransfer(
                userId,
                fromAccount.getId(),
                toAccount.getId(),
                amount
            );

            log.info("Fraud check: risk={}, score={}",
                fraudCheck.getRiskLevel(), fraudCheck.getRiskScore());

            if (fraudCheck.getRiskLevel().equals("HIGH")) {
                log.warn("HIGH RISK transaction blocked for user {}", userId);
                auditService.logBlockedTransfer(userId, amount, "HIGH_FRAUD_RISK");
                return buildError("FRAUD_DETECTED",
                    "This transaction has been flagged for review. " +
                    "Please contact support.", workflow);
            }

            // AGENT 4: Behavior Analytics Agent
            workflow.recordStep("behavior_analysis");
            var behaviorContext = behaviorService.analyze(userId);

            // AGENT 5: AML Compliance Agent
            workflow.recordStep("aml_compliance");
            var complianceCheck = complianceService.checkAML(userId, amount);

            if (!complianceCheck.isCompliant()) {
                log.warn("AML compliance failed for user {}, amount {}", userId, amount);
                return buildError("COMPLIANCE_VIOLATION",
                    "This transaction requires additional verification. " +
                    "Please contact support.", workflow);
            }

            // AGENT 6: 2FA Verification Agent
            // Require 2FA for amounts > $1000 OR medium fraud risk
            boolean requires2FA = amount.compareTo(BigDecimal.valueOf(1000)) > 0 ||
                                 fraudCheck.getRiskLevel().equals("MEDIUM");

            if (requires2FA) {
                workflow.recordStep("2fa_required");

                // Check if 2FA code provided
                String twoFACode = (String) params.get("twoFACode");
                if (twoFACode == null || twoFACode.isBlank()) {
                    // Send 2FA code
                    twoFAService.sendCode(userId);

                    return ActionResult.builder()
                        .success(false)
                        .requiresConfirmation(true)
                        .confirmationMessage(String.format(
                            "Transferring $%.2f requires 2FA verification. " +
                            "Please enter the code sent to your phone ending in ****%s.",
                            amount,
                            fromAccount.getPhoneMask()
                        ))
                        .data(Map.of(
                            "pendingTransferId", UUID.randomUUID().toString(),
                            "amount", amount,
                            "fromAccount", fromAccount.getMaskedNumber(),
                            "toAccount", toAccount.getMaskedNumber(),
                            "requires2FA", true,
                            "workflow", workflow.getSteps()
                        ))
                        .build();
                }

                // Verify 2FA code
                if (!twoFAService.verifyCode(userId, twoFACode)) {
                    return buildError("INVALID_2FA_CODE",
                        "Invalid verification code. Please try again.", workflow);
                }

                workflow.recordStep("2fa_verified");
            }

            // AGENT 7: Transfer Execution Agent
            workflow.recordStep("transfer_execution");
            Transfer transfer = transferService.executeTransfer(
                fromAccount.getId(),
                toAccount.getId(),
                amount,
                memo,
                userId
            );

            log.info("Transfer successful: id={}, amount={}",
                transfer.getId(), transfer.getAmount());

            // AGENT 8: Notification Agent (async)
            workflow.recordStep("notifications");
            CompletableFuture.runAsync(() -> {
                notificationService.sendEmail(userId, "Transfer Confirmation",
                    buildTransferEmail(transfer, fromAccount, toAccount));
                notificationService.sendSMS(userId,
                    String.format("Transfer of $%.2f completed. Ref: %s",
                        amount, transfer.getId()));
                notificationService.sendPushNotification(userId,
                    "Transfer Completed",
                    String.format("$%.2f transferred to %s",
                        amount, toAccount.getName()));
            });

            // AGENT 9: Audit Logging Agent
            workflow.recordStep("audit_logging");
            auditService.logSuccessfulTransfer(
                userId,
                transfer,
                fraudCheck,
                behaviorContext,
                complianceCheck
            );

            workflow.complete();

            return ActionResult.builder()
                .success(true)
                .message(String.format(
                    "Transfer of $%.2f completed successfully.\n\n" +
                    "From: %s (New balance: $%.2f)\n" +
                    "To: %s (New balance: $%.2f)\n" +
                    "Reference: %s",
                    amount,
                    fromAccount.getName(), transfer.getNewSourceBalance(),
                    toAccount.getName(), transfer.getNewDestBalance(),
                    transfer.getId()
                ))
                .data(Map.of(
                    "transferId", transfer.getId().toString(),
                    "amount", amount,
                    "fromAccount", fromAccount.getMaskedNumber(),
                    "toAccount", toAccount.getMaskedNumber(),
                    "newSourceBalance", transfer.getNewSourceBalance(),
                    "newDestBalance", transfer.getNewDestBalance(),
                    "timestamp", transfer.getTimestamp().toString(),
                    "fraudRisk", fraudCheck.getRiskLevel(),
                    "workflow", workflow.getSteps()
                ))
                .build();

        } catch (Exception e) {
            return handleError(e, userId);
        }
    }

    @Override
    public ActionResult handleError(Exception e, String userId) {
        log.error("Transfer failed for user {}", userId, e);
        auditService.logFailedTransfer(userId, e);
        return ActionResult.builder()
            .success(false)
            .errorCode("TRANSFER_FAILED")
            .message("Unable to complete transfer. Please try again or contact support.")
            .data(Map.of("error", e.getMessage()))
            .build();
    }

    @Override
    public String getConfirmationMessage(Map<String, Object> params) {
        BigDecimal amount = new BigDecimal(params.get("amount").toString());
        String toAccount = (String) params.get("toAccount");
        return String.format("Transfer $%.2f to account %s?", amount, toAccount);
    }

    private ActionResult buildError(String code, String message, WorkflowState workflow) {
        return ActionResult.builder()
            .success(false)
            .errorCode(code)
            .message(message)
            .data(Map.of("workflow", workflow.getSteps()))
            .build();
    }

    private String buildTransferEmail(Transfer transfer, Account from, Account to) {
        return String.format("""
            Transfer Confirmation

            Amount: $%.2f
            From: %s (...%s)
            To: %s (...%s)
            Date: %s
            Reference: %s

            New Balance: $%.2f
            """,
            transfer.getAmount(),
            from.getName(), from.getMaskedNumber(),
            to.getName(), to.getMaskedNumber(),
            transfer.getTimestamp(),
            transfer.getId(),
            transfer.getNewSourceBalance()
        );
    }

    // Workflow state tracker for debugging/auditing
    private static class WorkflowState {
        private final List<Map<String, Object>> steps = new ArrayList<>();
        private LocalDateTime startTime;

        void start(String workflowName) {
            this.startTime = LocalDateTime.now();
            steps.add(Map.of(
                "step", "workflow_started",
                "workflow", workflowName,
                "timestamp", startTime.toString()
            ));
        }

        void recordStep(String stepName) {
            steps.add(Map.of(
                "step", stepName,
                "timestamp", LocalDateTime.now().toString(),
                "elapsed_ms", java.time.Duration.between(
                    startTime, LocalDateTime.now()).toMillis()
            ));
        }

        void complete() {
            steps.add(Map.of(
                "step", "workflow_completed",
                "timestamp", LocalDateTime.now().toString(),
                "total_duration_ms", java.time.Duration.between(
                    startTime, LocalDateTime.now()).toMillis()
            ));
        }

        List<Map<String, Object>> getSteps() {
            return steps;
        }
    }
}
```

**Fraud Detection Service:**

```java
// File: src/main/java/com/example/app/service/FraudDetectionService.java

package com.example.app.service;

import com.example.app.dto.FraudCheckResult;
import com.example.app.repository.TransferHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudDetectionService {

    private final TransferHistoryRepository transferHistoryRepository;

    public FraudCheckResult analyzeTransfer(String userId,
                                           UUID fromAccountId,
                                           UUID toAccountId,
                                           BigDecimal amount) {
        double riskScore = 0.0;
        List<String> riskFactors = new ArrayList<>();

        // Factor 1: Amount anomaly detection
        BigDecimal avgTransferAmount = transferHistoryRepository
            .getAverageTransferAmount(userId, LocalDateTime.now().minusDays(90));

        if (avgTransferAmount != null && amount.compareTo(avgTransferAmount.multiply(
            BigDecimal.valueOf(5))) > 0) {
            riskScore += 0.3;
            riskFactors.add("Amount significantly higher than user's typical transfers");
        }

        // Factor 2: Frequency analysis
        long transfersLast24Hours = transferHistoryRepository
            .countTransfers(userId, LocalDateTime.now().minusHours(24));

        if (transfersLast24Hours > 10) {
            riskScore += 0.4;
            riskFactors.add("Unusually high transaction frequency");
        }

        // Factor 3: New recipient account
        boolean isNewRecipient = !transferHistoryRepository
            .hasTransferredToAccount(userId, toAccountId);

        if (isNewRecipient && amount.compareTo(BigDecimal.valueOf(5000)) > 0) {
            riskScore += 0.2;
            riskFactors.add("Large transfer to new recipient");
        }

        // Factor 4: Time-based analysis
        int hour = LocalDateTime.now().getHour();
        if (hour < 6 || hour > 22) {  // Late night/early morning
            riskScore += 0.1;
            riskFactors.add("Transfer during unusual hours");
        }

        // Determine risk level
        String riskLevel;
        if (riskScore >= 0.7) {
            riskLevel = "HIGH";
        } else if (riskScore >= 0.4) {
            riskLevel = "MEDIUM";
        } else {
            riskLevel = "LOW";
        }

        log.info("Fraud check complete: user={}, amount={}, risk={} ({})",
            userId, amount, riskLevel, riskScore);

        return FraudCheckResult.builder()
            .userId(userId)
            .amount(amount)
            .riskScore(riskScore)
            .riskLevel(riskLevel)
            .riskFactors(riskFactors)
            .timestamp(LocalDateTime.now())
            .build();
    }
}
```

#### Execution Flow

```
User Query: "Transfer $50,000 to my savings account"
    ↓
┌──────────────────────────────────────────────────────────────┐
│ 1. Intent Agent: ACTION (transfer_funds)                     │
│    Parameters: {amount: 50000, toAccount: "savings"}         │
├──────────────────────────────────────────────────────────────┤
│ 2. Account Validation Agent:                                 │
│    ✅ Source: Checking account (balance: $75,000)            │
│    ✅ Destination: Savings account (user owns both)          │
├──────────────────────────────────────────────────────────────┤
│ 3. Balance Check Agent:                                      │
│    ✅ Sufficient funds ($75,000 > $50,000)                   │
├──────────────────────────────────────────────────────────────┤
│ 4. Fraud Detection Agent:                                    │
│    - Amount: $50,000 (5x typical transfer of $10,000)        │
│    - Frequency: 2 transfers in last 24h (NORMAL)             │
│    - Recipient: Existing account (LOW risk)                  │
│    - Time: 2:30 PM (NORMAL hours)                            │
│    → Risk Score: 0.30 (MEDIUM)                               │
│    → Risk Factors: ["Amount higher than typical"]            │
├──────────────────────────────────────────────────────────────┤
│ 5. Behavior Analytics Agent:                                 │
│    - Account age: 5 years (TRUSTED)                          │
│    - Transaction history: Excellent                          │
│    - No fraud flags                                          │
├──────────────────────────────────────────────────────────────┤
│ 6. AML Compliance Agent:                                     │
│    - Amount: $50,000 (below $10k reporting threshold)        │
│    - Daily limit: $75,000 (within limit)                     │
│    ✅ COMPLIANT                                               │
├──────────────────────────────────────────────────────────────┤
│ 7. 2FA Verification Agent:                                   │
│    - Amount > $1,000: TRUE → 2FA REQUIRED                    │
│    - Fraud risk: MEDIUM → 2FA REQUIRED                       │
│    → Sending 2FA code to phone ****1234                      │
└──────────────────────────────────────────────────────────────┘

Response (Pending 2FA):
{
  "success": false,
  "requiresConfirmation": true,
  "confirmationMessage": "Transferring $50,000.00 requires 2FA verification.
    Please enter the code sent to your phone ending in ****1234.",
  "data": {
    "pendingTransferId": "pending-uuid-123",
    "amount": 50000.00,
    "fromAccount": "****5678",
    "toAccount": "****9012",
    "requires2FA": true
  }
}

┌─── After User Provides 2FA Code ────────────────────────────┐
│ 8. 2FA Verification Agent:                                   │
│    ✅ Code verified                                          │
├──────────────────────────────────────────────────────────────┤
│ 9. Transfer Execution Agent:                                 │
│    ✅ Transfer ID: TXN-987654                                │
│    ✅ Checking: $75,000 → $25,000                            │
│    ✅ Savings: $25,000 → $75,000                             │
├──────────────────────────────────────────────────────────────┤
│ 10. Notification Agent: (async)                              │
│     - Email confirmation sent                                │
│     - SMS notification sent                                  │
│     - Push notification sent                                 │
├──────────────────────────────────────────────────────────────┤
│ 11. Audit Logging Agent:                                     │
│     - Transaction logged with full workflow trace            │
│     - Fraud check results stored                             │
│     - Compliance check recorded                              │
└──────────────────────────────────────────────────────────────┘

Final Response:
{
  "success": true,
  "message": "Transfer of $50,000.00 completed successfully.

    From: Checking (****5678) - New balance: $25,000.00
    To: Savings (****9012) - New balance: $75,000.00
    Reference: TXN-987654",
  "data": {
    "transferId": "TXN-987654",
    "amount": 50000.00,
    "newSourceBalance": 25000.00,
    "newDestBalance": 75000.00,
    "timestamp": "2026-01-20T14:35:22",
    "fraudRisk": "MEDIUM",
    "workflow": [
      {"step": "workflow_started", "timestamp": "..."},
      {"step": "account_validation", "elapsed_ms": 45},
      {"step": "balance_check", "elapsed_ms": 67},
      {"step": "fraud_detection", "elapsed_ms": 123},
      {"step": "behavior_analysis", "elapsed_ms": 156},
      {"step": "aml_compliance", "elapsed_ms": 178},
      {"step": "2fa_verified", "elapsed_ms": 15234},
      {"step": "transfer_execution", "elapsed_ms": 15345},
      {"step": "notifications", "elapsed_ms": 15367},
      {"step": "audit_logging", "elapsed_ms": 15389},
      {"step": "workflow_completed", "total_duration_ms": 15402}
    ]
  }
}
```

**Agents Used:** 10 agents in coordinated workflow!

---

## 4. Testing Multi-Agent Workflows

### Unit Testing Individual Agents

```java
// File: src/test/java/com/example/app/FraudDetectionAgentTest.java

@SpringBootTest
class FraudDetectionAgentTest {

    @Autowired
    private FraudDetectionService fraudService;

    @MockBean
    private TransferHistoryRepository transferHistoryRepository;

    @Test
    void shouldDetectHighRiskForUnusualAmount() {
        String userId = "user-123";
        BigDecimal amount = BigDecimal.valueOf(50000);
        BigDecimal avgAmount = BigDecimal.valueOf(500);  // Typical: $500

        // Mock historical data
        when(transferHistoryRepository.getAverageTransferAmount(eq(userId), any()))
            .thenReturn(avgAmount);
        when(transferHistoryRepository.countTransfers(eq(userId), any()))
            .thenReturn(2L);
        when(transferHistoryRepository.hasTransferredToAccount(any(), any()))
            .thenReturn(true);

        // Execute fraud detection
        FraudCheckResult result = fraudService.analyzeTransfer(
            userId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            amount
        );

        // Verify
        assertThat(result.getRiskLevel()).isIn("MEDIUM", "HIGH");
        assertThat(result.getRiskScore()).isGreaterThan(0.3);
        assertThat(result.getRiskFactors())
            .anyMatch(factor -> factor.contains("higher than typical"));
    }
}
```

### Integration Testing Multi-Agent Workflows

```java
// File: src/test/java/com/example/app/SecureTransferIntegrationTest.java

@SpringBootTest
@Transactional
class SecureTransferIntegrationTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @BeforeEach
    void setup() {
        // Create test accounts
        accountService.createAccount("user-123", "Checking", BigDecimal.valueOf(75000));
        accountService.createAccount("user-123", "Savings", BigDecimal.valueOf(25000));
    }

    @Test
    void shouldRequire2FAForLargeTransfer() {
        String query = "Transfer $50,000 to my savings account";

        OrchestrationContext context = OrchestrationContext.builder()
            .userId("user-123")
            .build();

        // First attempt (without 2FA code)
        OrchestrationResult result1 = orchestrator.orchestrate(query, context);

        // Should require 2FA
        assertThat(result1.isSuccess()).isFalse();
        assertThat(result1.isRequiresConfirmation()).isTrue();
        assertThat(result1.getConfirmationMessage()).contains("2FA", "verification");

        // Second attempt (with 2FA code)
        OrchestrationContext contextWith2FA = context.toBuilder()
            .metadata(Map.of("twoFACode", "123456"))
            .build();

        OrchestrationResult result2 = orchestrator.orchestrate(query, contextWith2FA);

        // Should succeed
        assertThat(result2.isSuccess()).isTrue();
        assertThat(result2.getMessage()).contains("Transfer", "completed");
        assertThat(result2.getData().get("transferId")).isNotNull();
    }

    @Test
    void shouldBlockHighRiskTransfer() {
        // Simulate suspicious activity
        for (int i = 0; i < 15; i++) {
            transferService.executeTransfer(
                accountService.getPrimaryAccount("user-123").getId(),
                UUID.randomUUID(),
                BigDecimal.valueOf(100),
                "test",
                "user-123"
            );
        }

        String query = "Transfer $60,000 to account 123456789";

        OrchestrationContext context = OrchestrationContext.builder()
            .userId("user-123")
            .build();

        OrchestrationResult result = orchestrator.orchestrate(query, context);

        // Should be blocked for fraud
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("FRAUD_DETECTED");
    }
}
```

---

## 5. Configuration Reference

### Full Multi-Agent Configuration

```yaml
# File: application.yml

spring:
  application:
    name: multi-agent-app

  datasource:
    url: jdbc:postgresql://localhost:5432/multiagent
    username: ${DB_USER}
    password: ${DB_PASSWORD}

  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false

ai:
  enabled: true

  # LLM Provider
  providers:
    llm-provider: openai
    embedding-provider: onnx

  openai:
    api-key: ${OPENAI_API_KEY}
    model: gpt-4-turbo-preview
    timeout: 30000

  # Vector Database
  vector:
    database-type: qdrant

  qdrant:
    host: localhost
    port: 6333
    api-key: ${QDRANT_API_KEY}

  # Indexing Configuration
  indexing:
    enabled: true
    default-strategy: ASYNC
    async:
      queue-capacity: 1000
      worker-threads: 4
      batch-size: 50

  # Orchestration Configuration
  orchestration:
    enabled: true

    compound:
      enabled: true
      strategy: sequential  # or parallel
      max-intents: 5

    action:
      timeout-seconds: 30
      require-confirmation-for-high-risk: true
      high-risk-actions:
        - transfer_funds
        - cancel_subscription
        - delete_account

  # Chat Session Configuration
  chat-session:
    enabled: true
    ttl-minutes: 30
    max-turns: 100
    storage: database  # or redis, memory

  # Behavior Analytics
  behavior:
    enabled: true
    analysis:
      window-days: 30
      min-data-points: 5

  # Security Configuration
  security:
    enabled: true
    threat-detection: true

  # PII Detection
  pii-detection:
    enabled: true
    mode: REDACT  # or DETECT_ONLY, PASS_THROUGH
    detection-direction: INPUT_OUTPUT

  # Compliance
  compliance:
    enabled: true
    frameworks:
      - GDPR
      - HIPAA
      - CCPA

  # Governance
  governance:
    enabled: true
    retention:
      enabled: true
      default-days: 90
      auto-cleanup: true

# Logging
logging:
  level:
    com.example.app: DEBUG
    com.ai.infrastructure: INFO
```

---

## Summary

This document provided **complete, runnable examples** of multi-agent workflows:

### Simple (2-3 Agents)
1. ✅ Show + Cancel Subscription (2 agents)
2. ✅ Retrieve + Calculate Total Spending (2 agents)

### Intermediate (4-6 Agents)
3. ✅ Smart Retention Workflow (5 agents)
4. ✅ Order Status with Escalation (6 agents)

### Advanced (7+ Agents)
5. ✅ Secure Financial Transfer (10 agents)

### Key Patterns Demonstrated

- **Sequential Coordination** - Compound intents
- **Conditional Routing** - Based on behavior/sentiment
- **Risk-Based Workflows** - Fraud detection, churn analysis
- **Async Operations** - Notifications, logging
- **Workflow State Tracking** - For debugging/auditing
- **Human-in-the-Loop** - 2FA, confirmations
- **Multi-Layer Security** - Validation, fraud, compliance

### All Examples Include

✅ Complete code implementations
✅ Service layer examples
✅ Configuration files
✅ Test cases
✅ Execution flow diagrams
✅ Expected outputs

**Next Steps:**
- Copy examples to `/Real_Apps/` folder
- Create standalone demo applications
- Add to documentation

---

**End of Multi-Agent Workflow Examples**
