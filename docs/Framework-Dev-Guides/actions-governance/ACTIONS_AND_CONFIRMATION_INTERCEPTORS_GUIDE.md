# Actions + Confirmation Interceptors (V5) — Developer Guide

This guide explains how to:
- implement **actions** using the new annotation API (`@AIAction`, `@ActionExecute`, …)
- implement **confirmation interceptors** (intent resolvers) for conversation flows like retention offers

This is **greenfield**: the legacy `ActionHandler` API is removed and not supported.

---

## 1) Concepts (What runs where)

### Actions
An “action” is a Spring bean annotated with `@AIAction`. The framework discovers these beans at startup and:
- exposes their metadata to the LLM during intent extraction
- executes them via the orchestration pipeline when the LLM selects an action intent

Each action must declare an explicit **access mode**:
- `READ` (retrieval-only tool)
- `WRITE_ONLY` (mutating tool)
- `READ_WRITE` (both; treated as non-READ for fallback rules)

**READ actions are treated as helper tools:** if a READ action executes successfully but returns an “empty” payload (e.g., `count=0` / empty `results`), the orchestrator can replace that output with a RAG INFORMATION response.

Discovery and execution are backed by:
- `AIActionRegistry` (runtime discovery + lookup)
- `AnnotatedAIActionHandler` (invocation adapter)

### Confirmation interceptors (“intent resolvers”)
When chat sessions are enabled, a dedicated pipeline step resolves confirmation intents (e.g. *yes/no*) into the **correct action execution**.

This is implemented in:
- `ConfirmationResolutionStep` (runs before action execution in the pipeline)
- `AnnotatedConfirmationInterceptorsResolver` (recommended: runs `@OnPendingActionConfirmation` handlers)
- `IntentResolver` SPI (advanced / full control)
- `ConfirmationResolverSupport` (helper base class for manual resolvers)

---

## 2) Enablement (Apps)

### Actions
Nothing special is required beyond normal Spring component scanning:
- `@AIAction` is meta-annotated with `@Component`, so the bean is auto-registered.
- Your app must compile with `-parameters` (recommended) or you must explicitly name parameters with `@Param("name")`.

### Chat confirmations + resolvers
To enable conversation-aware confirmation handling:
1. Include the chat-session module (the app uses it; the core framework remains usable without it).
2. Set:

```yaml
ai:
  chat:
    enabled: true
```

Resolvers are just Spring beans that implement `IntentResolver`.

---

## 3) Writing an Action (Recommended pattern)

**Action results are typed:** `ActionResult.data` must be an `ActionPayload`.
Use `ActionResultContracts.object(...)` for normal key/value payloads, and `ActionResultContracts.list(...)` for list/search payloads.

### Minimal example

```java
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.intent.action.annotation.Param;

@AIAction(
    name = "add_to_cart",
    description = "Add a product SKU to the current cart",
    category = "commerce",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
public class AddToCartAction {

    @ActionExecute
    public ActionResult execute(
        @Param(required = true, description = "Product SKU") String sku,
        @Param(required = true, description = "Quantity", min = 1, max = 100) Integer quantity,
        ActionContext ctx
    ) {
        // business logic…
        return ActionResult.builder()
            .success(true)
            .message("Added to cart")
            .data(ActionResultContracts.object(java.util.Map.of("sku", sku, "quantity", quantity)))
            .build();
    }
}
```

### Returning list/search results (required contract)

If your action returns a **collection-style** result (list/search), use the framework’s list payload contract:
- `_count` and `_items` are reserved framework keys
- you may add additional custom keys (domain-specific) alongside them if needed

```java
import com.ai.infrastructure.intent.action.ActionResultContracts;

return ActionResult.builder()
    .success(true)
    .message("Products")
    .data(ActionResultContracts.list(items)) // adds _count + _items
    .build();
```

This keeps the core orchestrator domain-agnostic and enables deterministic behaviors (like “READ empty result → RAG”).

### Optional: custom confirmation message
If you omit `@ActionConfirmation`, the framework auto-generates a basic confirmation prompt.

```java
import com.ai.infrastructure.intent.action.annotation.ActionConfirmation;

@ActionConfirmation
public String confirm(
    @Param(required = true) String sku,
    @Param(required = true) Integer quantity
) {
    return "Add " + quantity + " × " + sku + " to your cart?";
}
```

### Optional: access control (fail-closed)
Use `@ActionAllowed` to guard actions. This runs **before confirmation and before execution**.

```java
import com.ai.infrastructure.intent.action.annotation.ActionAllowed;

@ActionAllowed
public boolean allowed(ActionContext ctx) {
    return ctx != null && ctx.userId() != null && !ctx.userId().isBlank();
}
```

If this returns `false`, the orchestrator returns `ACTION_DENIED`.

### Optional: “facts for LLM” after action execution
Use `@ActionFacts` to provide post-action structured facts for the LLM (used by post-action generation logic).

```java
import com.ai.infrastructure.intent.action.annotation.ActionFacts;

@ActionFacts
public java.util.Map<String, Object> facts(ActionResult result, ActionContext ctx) {
    return java.util.Map.of(
        "userId", ctx != null ? ctx.userId() : null,
        "success", result != null && result.isSuccess()
    );
}
```

---

## 4) Parameter binding & validation rules

Parameters are extracted from the LLM’s `actionParams` and converted to your method’s types.

### Supported “context injection”
You can declare any of these parameters without `@Param`:
- `ActionContext`
- `OrchestrationContext`
- `PipelineContext`

### `@Param` validation
Validation is enforced by the argument binder:
- `required = true` → missing/empty values are rejected
- `pattern = "..."` → regex validation (string form)
- `allowedValues = {...}` → enum-style validation (case-insensitive)
- `min` / `max` → numeric range validation

**Important:** required parameters should be declared on the `@ActionExecute` method so the framework can:
- show them in action metadata (for LLM selection)
- return `CLARIFICATION_REQUIRED` before asking for confirmation

---

## 5) Confirmation interceptors (recommended: annotations)

### When should you write an interceptor?
Write an app interceptor when you need conversation-specific control, for example:
- a retention offer (“instead of cancelling, apply 10% discount?”)
- multi-step confirmations across several actions
- special handling for compound confirmations (“yes and also …”)

### Recommended API: `@AIConfirmationInterceptors`
This removes most of the boilerplate of writing a full `IntentResolver` class.

Create a Spring bean annotated with `@AIConfirmationInterceptors`, then add one or more handler methods annotated with:
- `@OnPendingActionConfirmation(pendingActions=..., confirmation=...)`

Handler method signature:
- input: `ConfirmationInterceptionContext`
- output: `InterceptionDecision`

Key helpers on `ConfirmationInterceptionContext`:
- Stack ops: `peekPending()`, `popPending()`, `pushPending()`
- Loop guard stored in pending-action params: `onceParam = "_myFlag"` on the annotation
- Decision helpers:
  - `promptAction(name, params)` → returns an ACTION intent (not auto-confirmed)
  - `executeAction(name, params)` → returns an ACTION intent and marks it confirmed for this request
  - `reply(text)` → returns an INFORMATION direct reply (no RAG, no generation)

### Example: retention offer (cancel → offer → accept/reject)

```java
import com.ai.infrastructure.chat.annotation.AIConfirmationInterceptors;
import com.ai.infrastructure.chat.annotation.OnPendingActionConfirmation;
import com.ai.infrastructure.chat.interception.ConfirmationInterceptionContext;
import com.ai.infrastructure.chat.interception.InterceptionDecision;
import com.ai.infrastructure.dto.IntentType;

@AIConfirmationInterceptors
public class CancellationRetentionOffer {

    private static final String CANCEL = "cancel_purchase_order";
    private static final String OFFER = "offer_order_discount";

    // When user confirms cancellation, route them into the retention offer (only once).
    @OnPendingActionConfirmation(
        pendingActions = {CANCEL},
        confirmation = IntentType.CONFIRMATION_POSITIVE,
        onceParam = "_retentionOfferOffered"
    )
    public InterceptionDecision offer(ConfirmationInterceptionContext ctx) {
        return ctx.promptAction(OFFER, java.util.Map.of("discountPercent", 10));
    }

    // If they accept the offer: execute it and clear the original cancellation from the stack.
    @OnPendingActionConfirmation(pendingActions = {OFFER}, confirmation = IntentType.CONFIRMATION_POSITIVE)
    public InterceptionDecision accept(ConfirmationInterceptionContext ctx) {
        ctx.popPending(); // pop OFFER
        ctx.popPending(); // pop CANCEL (if it’s underneath)
        return ctx.executeAction(OFFER, java.util.Map.of("discountPercent", 10));
    }

    // If they reject the offer: execute the original cancellation (already confirmed earlier).
    @OnPendingActionConfirmation(pendingActions = {OFFER}, confirmation = IntentType.CONFIRMATION_NEGATIVE)
    public InterceptionDecision reject(ConfirmationInterceptionContext ctx) {
        ctx.popPending(); // pop OFFER
        return ctx.executeAction(CANCEL, java.util.Map.of());
    }
}
```

### Advanced API: implement `IntentResolver` directly
If you need full control (custom matching logic, compound confirmations, etc.), implement `IntentResolver`.
For convenience, extend:
- `ConfirmationResolverSupport`

---

## 6) Testing guidance

### Unit tests (actions)
- Validate your action’s business behavior by calling the `@ActionExecute` method directly.
- If you need registry/discovery coverage, use `AIActionRegistryTest` patterns (Spring context + `@AIAction` beans).

### Integration tests (resolvers + chat)
- Use `@SpringBootTest` with minimal component scanning.
- Avoid scanning the entire `com.ai.infrastructure.it` package; broad scanning can unintentionally pick up test actions and cause duplicate names at startup.

---

## References
- Change plan: `changes/DEVELOPER_EXPERIENCE_ACTIONS_AND_INTENT_RESOLVERS_CHANGE_PLAN.md`
- DX improvement plan: `ai-infrastructure-module/docs/DEVELOPER_EXPERIENCE_IMPROVEMENT_PLAN.md`
