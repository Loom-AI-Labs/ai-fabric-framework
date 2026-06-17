# Agentic Application Guide: Building Intelligent, Multi-Step AI Apps

## Table of Contents
1. [What is an Agentic App?](#what-is-an-agentic-app)
2. [Can You Build Multi-Step Agentic Apps?](#can-you-build-multi-step-agentic-apps)
3. [Architecture Overview](#architecture-overview)
4. [Building Single-Step Agentic Apps](#building-single-step-agentic-apps)
5. [Building Multi-Step Agentic Apps](#building-multi-step-agentic-apps)
6. [Advanced Patterns](#advanced-patterns)
7. [Real-World Examples](#real-world-examples)

---

## What is an Agentic App?

An **agentic application** is an AI-powered application that can:

1. **Understand User Intent** from natural language
2. **Make Decisions** about what actions to take
3. **Execute Actions** autonomously on behalf of users
4. **Interact with Business Systems** (databases, APIs, services)
5. **Handle Complex Workflows** (multi-step, conditional, sequential)

### Traditional App vs Agentic App

#### Traditional App:
```
User: "Cancel my subscription"
App: Shows a form → User fills form → User clicks buttons → Confirmation
```

#### Agentic App:
```
User: "Cancel my subscription"
AI Agent:
  1. Understands intent (ACTION: cancel_subscription)
  2. Identifies the subscription automatically
  3. Validates user permissions
  4. Optionally requests confirmation
  5. Executes cancellation
  6. Returns result: "Your Premium subscription has been cancelled. You'll retain access until Jan 31."
```

### Key Characteristics of Agentic Apps

✅ **Intent-Based**: Users express *what* they want, not *how* to do it
✅ **Autonomous**: The AI agent handles the execution
✅ **Context-Aware**: Understands user context, history, and state
✅ **Multi-Modal**: Can handle both questions (INFORMATION) and actions (ACTION)
✅ **Safe**: Built-in validation, permissions, confirmation flows

---

## Can You Build Multi-Step Agentic Apps?

**Yes! The AI Fabric Framework fully supports multi-step agentic workflows.**

### Multi-Step Capabilities

The framework provides **4 layers** of multi-step support:

#### 1. **Compound Intent Handling** (Built-in)
Handle multiple intents in a single query:

```
User: "Show me my subscription details and cancel it"

Framework automatically:
1. Detects COMPOUND intent
2. Extracts two intents:
   - INFORMATION: "show subscription details"
   - ACTION: "cancel subscription"
3. Executes sequentially
4. Merges results
```

#### 2. **Chat Session Management** (Built-in)
Maintain conversation state across multiple turns:

```
Turn 1:
User: "I want to cancel my subscription"
Agent: "I found your Premium subscription. Are you sure?"

Turn 2:
User: "Yes, proceed"
Agent: [Remembers context, executes cancellation]
```

#### 3. **Action Chaining** (Your Implementation)
Execute multiple business actions in sequence:

```java
@Component
public class MultiStepActionHandler implements ActionHandler {

    @Override
    public ActionResult executeAction(String actionName,
                                     Map<String, Object> params,
                                     Intent intent, String userId) {
        return switch (actionName) {
            case "upgrade_and_migrate" -> {
                // Step 1: Upgrade subscription
                var upgrade = upgradeService.upgrade(userId, params);

                // Step 2: Migrate data to new tier
                var migration = migrationService.migrate(userId, upgrade.getNewTier());

                // Step 3: Send welcome email
                emailService.sendUpgradeWelcome(userId);

                yield ActionResult.success("Upgraded and migrated successfully",
                    Map.of("newTier", upgrade.getNewTier(),
                           "itemsMigrated", migration.getCount()));
            }
            default -> ActionResult.failed("UNKNOWN_ACTION", "Unknown action");
        };
    }
}
```

#### 4. **Workflow Orchestration** (Your Implementation)
Build complex, stateful workflows with conditional logic:

```java
public class SubscriptionCancellationWorkflow {

    public WorkflowResult execute(String userId, String reason) {
        // Step 1: Validate eligibility
        if (!canCancel(userId)) {
            return offerAlternatives(userId);
        }

        // Step 2: Check if downgrade is better
        if (reason.contains("expensive")) {
            return offerDowngrade(userId);
        }

        // Step 3: Process cancellation
        var result = cancelSubscription(userId, reason);

        // Step 4: Schedule feedback survey (async)
        scheduleFollowUp(userId, 7); // days

        // Step 5: Update analytics
        analyticsService.trackChurn(userId, reason);

        return WorkflowResult.success(result);
    }
}
```

---

## Architecture Overview

### How the Framework Enables Agentic Apps

```
┌─────────────────────────────────────────────────────────────────┐
│                         USER QUERY                               │
│           "Cancel my subscription and refund last payment"       │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      RAGOrchestrator                             │
│                   (Pipeline Architecture)                        │
└─────────────────────────────┬────────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
        ▼                    ▼                    ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  Security    │    │    Access    │    │     PII      │
│  Analysis    │    │   Control    │    │  Detection   │
│  (Order 10)  │    │  (Order 20)  │    │  (Order 30)  │
└──────────────┘    └──────────────┘    └──────────────┘
                             │
                             ▼
                    ┌──────────────┐
                    │   Intent     │
                    │ Extraction   │
                    │  (Order 50)  │
                    └──────┬───────┘
                           │
          ┌────────────────┼────────────────┐
          │                                 │
          ▼                                 ▼
┌──────────────────┐              ┌──────────────────┐
│ COMPOUND Intent  │              │  SINGLE Intent   │
│                  │              │                  │
│ Intent 1: ACTION │              │  Type: ACTION    │
│ Intent 2: ACTION │              │  or INFORMATION  │
└────────┬─────────┘              └────────┬─────────┘
         │                                 │
         │ Sequential Processing           │
         │                                 │
         ▼                                 ▼
┌─────────────────────────────────────────────────┐
│           Intent Handling Step                  │
│                                                 │
│  ┌──────────────┐      ┌──────────────┐        │
│  │   ACTION     │──────│     Your     │        │
│  │   Intent     │      │ ActionHandler│        │
│  └──────────────┘      └──────────────┘        │
│                                                 │
│  ┌──────────────┐      ┌──────────────┐        │
│  │ INFORMATION  │──────│  RAG Service │        │
│  │   Intent     │      │ (Semantic    │        │
│  └──────────────┘      │  Search)     │        │
│                        └──────────────┘        │
└─────────────────────────────────────────────────┘
                         │
                         ▼
                 ┌──────────────┐
                 │   Response   │
                 │ Sanitization │
                 │   & Merge    │
                 └──────────────┘
                         │
                         ▼
                 ┌──────────────┐
                 │   History    │
                 │ Persistence  │
                 └──────────────┘
```

### Key Components

1. **RAGOrchestrator**: Coordinates the entire pipeline
2. **Intent Extraction**: Multi-step strategy that can detect compound intents
3. **Action Handler Registry**: Routes actions to your business logic
4. **Chat Session Service**: Maintains conversation context
5. **Pipeline Steps**: Security, PII, compliance gates

---

## Building Single-Step Agentic Apps

### Step 1: Define Your Actions

Create action handlers for each business capability:

```java
@Component
public class SubscriptionActionHandler implements ActionHandler {

    @Autowired
    private SubscriptionService subscriptionService;

    @Override
    public AIActionMetaData getActionMetadata() {
        return AIActionMetaData.builder()
            .name("cancel_subscription")
            .description("Cancel a user's active subscription")
            .category("subscription-management")
            .parameters(Map.of(
                "subscriptionId", "Optional: ID of subscription to cancel",
                "reason", "Optional: Reason for cancellation"
            ))
            .build();
    }

    @Override
    public boolean validateActionAllowed(String userId) {
        return subscriptionService.hasActiveSubscription(userId);
    }

    @Override
    public ActionResult executeAction(Map<String, Object> params, String userId) {
        try {
            String subId = (String) params.get("subscriptionId");
            String reason = (String) params.getOrDefault("reason", "User requested");

            Subscription cancelled = subscriptionService.cancel(userId, subId, reason);

            return ActionResult.builder()
                .success(true)
                .message("Your " + cancelled.getPlanName() + " subscription has been cancelled")
                .data(Map.of(
                    "subscriptionId", cancelled.getId(),
                    "accessUntil", cancelled.getExpiresAt(),
                    "refundAmount", cancelled.getRefundAmount()
                ))
                .build();

        } catch (Exception e) {
            return handleError(e, userId);
        }
    }

    @Override
    public String getConfirmationMessage(Map<String, Object> params) {
        return "Are you sure you want to cancel? You'll lose access to premium features.";
    }

    @Override
    public ActionResult handleError(Exception e, String userId) {
        log.error("Cancellation failed for user: {}", userId, e);
        return ActionResult.failed("CANCELLATION_FAILED",
            "We couldn't process your cancellation. Please contact support.");
    }
}
```

### Step 2: Use the Orchestrator

```java
@RestController
@RequestMapping("/api/assistant")
public class AIAssistantController {

    @Autowired
    private RAGOrchestrator orchestrator;

    @PostMapping("/chat")
    public ResponseEntity<OrchestrationResult> chat(
            @RequestBody ChatRequest request,
            @AuthenticationPrincipal User user) {

        OrchestrationContext context = OrchestrationContext.builder()
            .userId(user.getId())
            .sessionId(request.getSessionId())
            .metadata(Map.of("tier", user.getTier()))
            .build();

        OrchestrationResult result = orchestrator.orchestrate(
            request.getMessage(),
            context
        );

        return ResponseEntity.ok(result);
    }
}
```

### Step 3: Test It

```java
@SpringBootTest
class AgenticAppTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @Test
    void shouldCancelSubscription() {
        OrchestrationResult result = orchestrator.orchestrate(
            "Cancel my subscription",
            OrchestrationContext.forUser("user-123")
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getType()).isEqualTo("ACTION");
        assertThat(result.getMessage()).contains("cancelled");
    }
}
```

---

## Building Multi-Step Agentic Apps

### Pattern 1: Compound Intent (Automatic)

The framework **automatically** handles compound intents:

```java
// User query: "Show my subscription and cancel it"
// Framework automatically:
// 1. Detects COMPOUND intent
// 2. Extracts 2 intents (INFORMATION + ACTION)
// 3. Executes sequentially
// 4. Merges results
```

**Configuration:**
```yaml
ai:
  orchestration:
    compound:
      strategy: sequential  # or parallel (for independent actions)
```

### Pattern 2: Conversational Workflows (Chat Sessions)

Maintain state across multiple conversation turns:

```java
@Service
public class ConversationalAgentService {

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private ChatSessionService chatSessionService;

    public OrchestrationResult handleTurn(String message, String userId, String sessionId) {
        // Get conversation history
        String conversationContext = chatSessionService.getConversationContext(sessionId, userId);

        // Build context with history
        OrchestrationContext context = OrchestrationContext.builder()
            .userId(userId)
            .sessionId(sessionId)
            .metadata(Map.of("conversationHistory", conversationContext))
            .build();

        // Process with full context
        OrchestrationResult result = orchestrator.orchestrate(message, context);

        // Record this turn
        chatSessionService.recordTurn(sessionId, userId, message, result.getMessage());

        return result;
    }
}
```

**Example Conversation:**

```
Turn 1:
User: "I'm thinking about cancelling my subscription"
Agent: "I understand you're considering cancellation. Your Premium plan costs $29/month.
        May I ask why you're thinking of cancelling?"

Turn 2:
User: "It's too expensive"
Agent: "I can offer you our Basic plan at $9/month with core features. Would you like to
        downgrade instead of cancelling?"

Turn 3:
User: "Yes, downgrade me"
Agent: [Executes downgrade action] "Done! You've been downgraded to Basic plan at $9/month.
        Your next billing date is Feb 1."
```

### Pattern 3: Multi-Action Workflows

Execute complex multi-step business logic:

```java
@Component
public class AdvancedWorkflowHandler implements ActionHandler {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private AnalyticsService analyticsService;

    @Override
    public AIActionMetaData getActionMetadata() {
        return AIActionMetaData.builder()
            .name("process_refund_and_cancel")
            .description("Process refund for recent payment and cancel subscription")
            .category("advanced-workflows")
            .build();
    }

    @Override
    public ActionResult executeAction(Map<String, Object> params, String userId) {
        try {
            WorkflowState state = new WorkflowState();

            // Step 1: Find recent payment
            Payment recentPayment = paymentService.getRecentPayment(userId);
            if (recentPayment == null) {
                return ActionResult.failed("NO_PAYMENT", "No recent payment found");
            }
            state.recordStep("payment_found", recentPayment.getId());

            // Step 2: Validate refund eligibility (within 30 days)
            if (!recentPayment.isRefundable()) {
                return ActionResult.failed("NOT_REFUNDABLE",
                    "Payment is not eligible for refund (>30 days old)");
            }
            state.recordStep("refund_validated");

            // Step 3: Process refund
            Refund refund = paymentService.processRefund(recentPayment.getId(), userId);
            state.recordStep("refund_processed", refund.getId());

            // Step 4: Cancel subscription
            Subscription cancelled = subscriptionService.cancel(userId, "Refund requested");
            state.recordStep("subscription_cancelled", cancelled.getId());

            // Step 5: Send notifications (async)
            CompletableFuture.runAsync(() -> {
                notificationService.sendRefundConfirmation(userId, refund);
                notificationService.sendCancellationConfirmation(userId, cancelled);
            });
            state.recordStep("notifications_scheduled");

            // Step 6: Track in analytics
            analyticsService.trackChurnWithRefund(userId, cancelled, refund);
            state.recordStep("analytics_recorded");

            return ActionResult.builder()
                .success(true)
                .message(String.format(
                    "Refund of $%.2f processed and subscription cancelled. " +
                    "Refund will appear in 5-7 business days.",
                    refund.getAmount()
                ))
                .data(Map.of(
                    "refundId", refund.getId(),
                    "refundAmount", refund.getAmount(),
                    "subscriptionId", cancelled.getId(),
                    "workflowSteps", state.getSteps()
                ))
                .build();

        } catch (Exception e) {
            return handleError(e, userId);
        }
    }

    // Workflow state tracker for debugging/auditing
    private static class WorkflowState {
        private final List<Map<String, Object>> steps = new ArrayList<>();

        void recordStep(String stepName, Object... data) {
            steps.add(Map.of(
                "step", stepName,
                "timestamp", System.currentTimeMillis(),
                "data", data.length > 0 ? data[0] : null
            ));
        }

        List<Map<String, Object>> getSteps() {
            return steps;
        }
    }
}
```

### Pattern 4: Conditional Multi-Step Workflows

Handle conditional logic based on user context:

```java
@Component
public class SmartCancellationHandler implements ActionHandler {

    @Override
    public ActionResult executeAction(Map<String, Object> params, String userId) {

        // Get user context
        UserContext context = userContextService.getContext(userId);
        Subscription sub = subscriptionService.getActive(userId);

        // Decision tree based on user state
        if (context.isHighValue()) {
            // VIP users: Offer special retention deal
            return offerVIPRetentionDeal(userId, sub);

        } else if (sub.getDaysRemaining() > 15) {
            // Plenty of time left: Offer to pause instead
            return offerPauseOption(userId, sub);

        } else if (context.hasUnusedCredits()) {
            // Has unused credits: Remind and offer extension
            return remindCreditsAndOfferExtension(userId, sub, context);

        } else {
            // Standard cancellation flow
            return processStandardCancellation(userId, sub);
        }
    }

    private ActionResult offerVIPRetentionDeal(String userId, Subscription sub) {
        RetentionOffer offer = retentionService.createVIPOffer(userId);

        return ActionResult.builder()
            .success(true)
            .requiresConfirmation(true)
            .confirmationMessage(String.format(
                "As a valued customer, we'd like to offer you 50%% off for 3 months " +
                "(just $%.2f/month). Accept this offer?",
                offer.getDiscountedPrice()
            ))
            .data(Map.of(
                "offerId", offer.getId(),
                "originalPrice", sub.getPrice(),
                "discountedPrice", offer.getDiscountedPrice(),
                "duration", "3 months"
            ))
            .build();
    }
}
```

### Pattern 5: State Machine Workflows

Complex workflows with explicit state management:

```java
public enum CancellationState {
    INITIATED,
    RETENTION_OFFERED,
    RETENTION_ACCEPTED,
    RETENTION_DECLINED,
    CANCELLATION_CONFIRMED,
    REFUND_PROCESSING,
    COMPLETED
}

@Component
public class StateMachineCancellationHandler {

    public ActionResult processWithState(String userId, String sessionId, String action) {

        // Load current state from session
        CancellationState currentState = loadState(sessionId);

        return switch (currentState) {
            case INITIATED -> {
                // First interaction: Offer retention
                saveState(sessionId, CancellationState.RETENTION_OFFERED);
                yield offerRetention(userId);
            }

            case RETENTION_OFFERED -> {
                if ("accept".equals(action)) {
                    saveState(sessionId, CancellationState.RETENTION_ACCEPTED);
                    yield applyRetentionOffer(userId);
                } else {
                    saveState(sessionId, CancellationState.RETENTION_DECLINED);
                    yield requestCancellationConfirmation(userId);
                }
            }

            case RETENTION_DECLINED -> {
                if ("confirm".equals(action)) {
                    saveState(sessionId, CancellationState.CANCELLATION_CONFIRMED);
                    yield processCancellation(userId);
                } else {
                    yield ActionResult.success("Cancellation aborted");
                }
            }

            case CANCELLATION_CONFIRMED -> {
                saveState(sessionId, CancellationState.REFUND_PROCESSING);
                yield processRefund(userId);
            }

            case REFUND_PROCESSING -> {
                saveState(sessionId, CancellationState.COMPLETED);
                yield finalizeAndCleanup(userId, sessionId);
            }

            default -> ActionResult.failed("INVALID_STATE", "Invalid workflow state");
        };
    }
}
```

---

## Advanced Patterns

### Pattern 6: Parallel Multi-Step Execution

Execute independent actions in parallel:

```java
@Component
public class ParallelWorkflowHandler implements ActionHandler {

    @Override
    public ActionResult executeAction(Map<String, Object> params, String userId) {

        // Execute multiple independent operations in parallel
        CompletableFuture<Void> updateProfile = CompletableFuture.runAsync(() ->
            userService.updatePreferences(userId, params)
        );

        CompletableFuture<Void> sendEmail = CompletableFuture.runAsync(() ->
            emailService.sendConfirmation(userId)
        );

        CompletableFuture<Void> trackAnalytics = CompletableFuture.runAsync(() ->
            analyticsService.trackEvent(userId, "preferences_updated")
        );

        // Wait for all to complete
        try {
            CompletableFuture.allOf(updateProfile, sendEmail, trackAnalytics).join();

            return ActionResult.success("All operations completed successfully");

        } catch (Exception e) {
            return ActionResult.failed("PARALLEL_EXECUTION_FAILED", e.getMessage());
        }
    }
}
```

### Pattern 7: Intent Chaining with Context Passing

Chain multiple intents with shared context:

```java
public class ContextAwareMultiStepHandler {

    public OrchestrationResult executeChain(String userId) {

        SharedContext ctx = new SharedContext();

        // Step 1: Get user info
        User user = userService.getUser(userId);
        ctx.put("user", user);

        // Step 2: Query with context
        OrchestrationResult step1 = orchestrator.orchestrate(
            "Show my active subscriptions",
            buildContext(userId, ctx)
        );
        ctx.put("subscriptions", step1.getData().get("subscriptions"));

        // Step 3: Take action based on step 1 result
        if (hasMultipleSubscriptions(step1)) {
            return orchestrator.orchestrate(
                "Cancel the oldest subscription",
                buildContext(userId, ctx)
            );
        } else {
            return step1;
        }
    }
}
```

---

## Real-World Examples

### Example 1: Customer Support Bot (Multi-Step)

```java
/**
 * Handles: "I can't log in and need a refund"
 *
 * Steps:
 * 1. Detect compound intent (INFORMATION + ACTION)
 * 2. First: Provide login help (INFORMATION)
 * 3. Then: Process refund (ACTION)
 */
@Component
public class SupportBotHandler implements ActionHandler {

    @Override
    public ActionResult executeAction(Map<String, Object> params, String userId) {
        String issue = (String) params.get("issue");

        // Multi-step resolution
        if (issue.contains("login") && issue.contains("refund")) {
            // Step 1: Send login reset link
            authService.sendPasswordReset(userId);

            // Step 2: Process refund
            Refund refund = refundService.processRefund(userId);

            return ActionResult.success(
                "I've sent you a password reset link AND processed your refund of $" +
                refund.getAmount() + ". Check your email for the reset link."
            );
        }

        return ActionResult.failed("UNKNOWN_ISSUE", "Unable to process request");
    }
}
```

### Example 2: E-commerce Order Management

```java
/**
 * Handles: "Track my order and change delivery address"
 *
 * Workflow:
 * 1. Find most recent order
 * 2. Check if address change is still possible
 * 3. Update address if allowed
 * 4. Provide tracking info
 */
@Component
public class OrderManagementHandler implements ActionHandler {

    @Override
    public ActionResult executeAction(Map<String, Object> params, String userId) {

        // Step 1: Find order
        Order order = orderService.getRecentOrder(userId);

        // Step 2: Check if modifiable
        if (!order.isModifiable()) {
            return ActionResult.success(
                "Your order has already shipped and cannot be modified. " +
                "Tracking: " + order.getTrackingNumber()
            );
        }

        // Step 3: Update address
        String newAddress = (String) params.get("newAddress");
        order = orderService.updateAddress(order.getId(), newAddress);

        // Step 4: Return tracking info
        return ActionResult.success(
            "Address updated! Your order will be delivered to: " + newAddress + ". " +
            "Tracking: " + order.getTrackingNumber()
        );
    }
}
```

### Example 3: Financial Services (Stateful Workflow)

```java
/**
 * Complex financial workflow with compliance checks
 *
 * Handles: "Transfer $5000 to my savings account"
 *
 * Workflow:
 * 1. Validate account ownership
 * 2. Check available balance
 * 3. Perform fraud detection
 * 4. Request 2FA confirmation
 * 5. Execute transfer
 * 6. Send notification
 * 7. Update analytics
 */
@Component
public class BankingTransferHandler implements ActionHandler {

    @Override
    public ActionResult executeAction(Map<String, Object> params, String userId) {

        double amount = (Double) params.get("amount");
        String toAccountId = (String) params.get("toAccountId");

        try {
            // Step 1: Validate accounts
            Account fromAccount = accountService.getPrimaryChecking(userId);
            Account toAccount = accountService.getAccount(toAccountId, userId);

            if (!toAccount.getUserId().equals(userId)) {
                return ActionResult.failed("UNAUTHORIZED",
                    "You can only transfer to your own accounts");
            }

            // Step 2: Check balance
            if (fromAccount.getBalance() < amount) {
                return ActionResult.failed("INSUFFICIENT_FUNDS",
                    String.format("Insufficient funds. Available: $%.2f",
                        fromAccount.getBalance()));
            }

            // Step 3: Fraud detection
            FraudCheckResult fraudCheck = fraudService.checkTransfer(userId, amount);
            if (fraudCheck.isHighRisk()) {
                return ActionResult.failed("FRAUD_DETECTED",
                    "This transfer has been flagged for review. Contact support.");
            }

            // Step 4: Require 2FA for large amounts
            if (amount > 1000.0) {
                return ActionResult.builder()
                    .requiresConfirmation(true)
                    .confirmationMessage(String.format(
                        "Transferring $%.2f requires verification. " +
                        "Enter your 2FA code to proceed.",
                        amount
                    ))
                    .data(Map.of(
                        "pendingTransferId", UUID.randomUUID().toString(),
                        "amount", amount,
                        "fromAccount", fromAccount.getMaskedNumber(),
                        "toAccount", toAccount.getMaskedNumber()
                    ))
                    .build();
            }

            // Step 5: Execute transfer
            Transfer transfer = transferService.execute(
                fromAccount.getId(),
                toAccount.getId(),
                amount
            );

            // Step 6: Send notification (async)
            CompletableFuture.runAsync(() ->
                notificationService.sendTransferConfirmation(userId, transfer)
            );

            // Step 7: Analytics
            analyticsService.trackTransfer(userId, amount);

            return ActionResult.success(
                String.format("Transfer of $%.2f completed. New balance: $%.2f",
                    amount, transfer.getNewBalance()),
                Map.of(
                    "transferId", transfer.getId(),
                    "fromAccount", fromAccount.getMaskedNumber(),
                    "toAccount", toAccount.getMaskedNumber(),
                    "amount", amount,
                    "timestamp", transfer.getTimestamp()
                )
            );

        } catch (Exception e) {
            return handleError(e, userId);
        }
    }
}
```

---

## Configuration Guide

### Enable Multi-Step Features

**application.yml:**
```yaml
ai:
  # Core orchestration settings
  orchestration:
    enabled: true

    # Compound intent handling
    compound:
      enabled: true
      strategy: sequential  # sequential | parallel
      max-intents: 5

    # Action execution
    action:
      timeout-seconds: 30
      require-confirmation-for-high-risk: true
      high-risk-actions:
        - cancel_subscription
        - process_refund
        - delete_account
        - transfer_funds

  # Chat session management (for conversational workflows)
  chat-session:
    enabled: true
    ttl-minutes: 30
    max-turns: 50
    storage: database  # database | redis | memory

  # Intent extraction
  intent:
    extraction:
      strategy: multi_step  # single_step | multi_step | progressive
      fallback-enabled: true
```

---

## Best Practices

### ✅ DO:

1. **Keep Actions Focused**: Each action should do ONE thing well
2. **Validate Permissions**: Always check user permissions before execution
3. **Handle Errors Gracefully**: Provide clear, user-friendly error messages
4. **Use Confirmations**: Require confirmation for destructive actions
5. **Track State**: Use chat sessions for multi-turn workflows
6. **Log Everything**: Log all action executions for auditing
7. **Test Thoroughly**: Test both success and failure paths

### ❌ DON'T:

1. **Don't Make Actions Too Complex**: Break complex workflows into smaller actions
2. **Don't Skip Validation**: Always validate inputs and permissions
3. **Don't Leak Sensitive Data**: Be careful with what you return in results
4. **Don't Block on Async Operations**: Use CompletableFuture for non-critical tasks
5. **Don't Forget Error Handling**: Always implement handleError()

---

## Summary

The AI Fabric Framework provides **complete support** for building both simple and complex agentic applications:

✅ **Single-Step Actions**: Simple intent → action execution
✅ **Multi-Step Workflows**: Sequential business logic within actions
✅ **Compound Intents**: Automatic handling of multiple intents in one query
✅ **Conversational Workflows**: Stateful multi-turn conversations with chat sessions
✅ **Conditional Logic**: Decision trees based on user context
✅ **State Machines**: Complex workflows with explicit state management
✅ **Parallel Execution**: Run independent operations concurrently

The framework handles the **hard parts** (security, PII detection, intent extraction, compliance) so you can focus on **business logic**.

---

## Next Steps

1. **Start Simple**: Build a single-action handler first
2. **Add Complexity**: Gradually add multi-step workflows
3. **Enable Chat Sessions**: Add conversational capabilities
4. **Test Thoroughly**: Use the provided test patterns
5. **Monitor & Iterate**: Use analytics to improve your workflows

For more examples, see:
- `/Real_Apps/it-support-action-bot/` - Action-only bot
- `/Real_Apps/sub-management-hub/` - Full agentic app
- `/ai-infrastructure-module/docs/intentExtraction/action-handling/` - Detailed action handling docs
