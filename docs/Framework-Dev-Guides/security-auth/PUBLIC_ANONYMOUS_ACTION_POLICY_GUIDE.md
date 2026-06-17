# Public Anonymous Action Policy Guide

Status: draft (2026-04-11)

This guide explains how to allow a **limited anonymous public-runtime experience** without opening the full commerce surface.

Use this when:
- the runtime already accepts `PUBLIC_RUNTIME_ANONYMOUS` bearer tokens
- anonymous chat should be allowed
- only a small subset of actions should execute for anonymous users

Related files:
- `Real_Apps/ecommerce-store/src/main/java/com/ai/fabric/realapps/chat/authz/web/AuthzController.java`
- `Platfrom/backend/src/main/resources/bootstrap/ecommerce-demo/rest-connector/actions-routing.yml`
- `ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/pipeline/steps/AccessControlStep.java`
- `ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/pipeline/steps/IntentHandlingStep.java`
- `ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/authz/RemoteHttpEntityAccessPolicy.java`

---

## 1. Two Gates Exist

Anonymous public runtime requests must pass **three different authorization gates**.

### 1.1 Root chat gate

Runtime first checks:
- `resourceId = rag:intent`
- `operationType = READ`

This happens in:
- `AccessControlStep`

If this gate denies, the user sees:
- `Access denied by policy.`

No intent extraction or action execution happens after that.

### 1.2 Action execution gate

If chat admission passes and the model chooses an action, runtime still applies an anonymous action gate before connector execution.

This happens in:
- `IntentHandlingStep`

Current runtime behavior:
- anonymous actions are denied by default
- only actions with `anonymousAllowed: true` in the action contract can continue to handler execution

If this gate denies, the user sees:
- `Action not permitted for anonymous users.`

This gate must stay aligned with the demo authz allowlist and route-level authz configuration.

### 1.3 Connector action execution gate

If the runtime anonymous action gate allows the action, the connector can run a second authz preflight per action.

This happens only when the route has:

```yaml
authz:
  enabled: true
```

This path is enforced by:
- `RestActionExecutionService`

Without per-action authz, allowing anonymous `rag:intent` plus a runtime-allowed action effectively allows the model to attempt that routed action.

---

## 2. Correct Policy Model

Do **not** authorize anonymous callers using legacy `userId`.

Anonymous public runtime requests intentionally have:
- `userId = null` as a compatibility alias

Authorize using canonical verified auth fields instead:
- `subjectId`
- `subjectType`
- `authMode`
- `grantedScopes`
- `resourceId`
- `operationType`
- `authContext.*`

For anonymous public runtime, the important values are usually:
- `subjectType = ANONYMOUS_SESSION`
- `authMode = PUBLIC_RUNTIME_ANONYMOUS`

---

## 3. Recommended Ecommerce Demo Pattern

### 3.1 Allow anonymous root chat

Allow:
- `resourceId = rag:intent`
- `operationType = READ`
- when canonical auth says the caller is anonymous public runtime

This enables:
- product discovery chat
- grounded retrieval
- intent extraction
- safe action selection

### 3.2 Allow only anonymous-safe actions

Recommended default anonymous allowlist for ecommerce demo:
- `list_products`
- `search_products`
- `get_product_details`
- `view_cart`
- `add_to_cart`
- `remove_from_cart`
- `apply_coupon_to_cart`

Keep denied for anonymous by default:
- checkout
- purchase order creation
- orders
- account profile
- addresses
- delivery address change
- shipment tracking
- reviews
- support tickets
- returns
- discounts

Reason:
- catalog and cart are low-risk and session-friendly
- account and order operations imply ownership, identity, or side effects that should stay authenticated

Important:
- this same decision must be aligned across both layers:
  - runtime action contract: `anonymousAllowed: true`
  - connector/app authz policy: allow the corresponding action resource
- if runtime action metadata does not opt in, the request will fail before connector authz runs

---

## 4. Routing Configuration Pattern

Each action that should be policy-checked must have explicit route authz.

Example:

```yaml
list_products:
  method: GET
  path: /api/products/search
  response:
    success-http-status: [200]
    message: "Products"
  authz:
    enabled: true
    resourceId: action:list_products
    operationType: EXECUTE_ACTION
```

Sensitive action example:

```yaml
create_purchase_order:
  method: POST
  path: /api/orders
  response:
    success-http-status: [201]
    message: "Purchase order created."
  authz:
    enabled: true
    resourceId: action:create_purchase_order
    operationType: EXECUTE_ACTION
```

The authz service should then:
- allow anonymous `action:list_products`
- deny anonymous `action:create_purchase_order`

This keeps the policy centralized and explicit.

---

## 5. Scope Note

Public anonymous tokens currently default to chat scopes only:
- `chat:query`
- `chat:suggestions`
- `chat:conversations`

That is enough for:
- entering chat
- reading suggestions
- reading/writing conversation state

If you want **scope-driven anonymous action allowlists**, you must also extend anonymous public token scopes and validate them consistently.

If you only need a demo-safe anonymous subset, resource-based action authz is the simpler and safer pattern.

---

## 6. Apply Flow

After changing anonymous policy behavior:

1. update the authz service
2. update action routing authz blocks
3. publish/apply the deployment again
4. retest from POC using `Public anonymous`

Expected result:
- anonymous chat no longer fails at `rag:intent`
- anonymous-safe actions succeed
- sensitive actions fail with authz denial before upstream execution

---

## 7. Troubleshooting

If anonymous POC still returns `Access denied by policy.`:
- root `rag:intent` is still denied
- check authz service handling of `ANONYMOUS_SESSION` and `PUBLIC_RUNTIME_ANONYMOUS`

If chat works but sensitive actions still execute:
- route-level `authz.enabled` is missing for those actions

If chat works but even allowed guest-safe actions return `Action not permitted for anonymous users.`:
- runtime action metadata does not mark the action with `anonymousAllowed: true`

If public anonymous path does not appear in POC:
- `AI_FABRIC_RUNTIME_PUBLIC_TOKEN_SIGNING_KEY` is missing, or
- deployment public runtime posture was not applied, or
- anonymous bootstrap was not enabled

If action authz never fires:
- connector `authz.enabled` is false globally, or
- per-action `authz.enabled` is false on the route
