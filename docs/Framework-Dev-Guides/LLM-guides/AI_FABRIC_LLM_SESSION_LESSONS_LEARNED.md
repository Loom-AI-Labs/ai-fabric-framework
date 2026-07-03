# AI Fabric LLM Session Lessons Learned

Use this document as a compact reference for LLM-assisted AI Fabric debugging sessions. It captures
real issues found while building and deploying the public real-app demos, the symptoms users saw,
the root cause, and the fix pattern.

## How To Use This Reference

When an LLM session is asked to debug an AI Fabric app:

1. Reproduce the failing HTTP request directly with `curl`.
2. Identify whether the error comes from the app controller, AI Fabric orchestration pipeline, provider
   call, action handler, vector provider, or browser/CORS boundary.
3. Inspect the app-owned integration hooks before changing framework code.
4. Add a focused regression test in the real app or framework module that owns the behavior.
5. Keep framework security fail-closed unless the framework contract itself is wrong.

## Lesson 1: `Pipeline step failed: AccessControl`

### User Symptom

The browser chat UI shows an error card:

```text
Pipeline step failed: AccessControl
```

The response from the natural-language endpoint looks like:

```json
{
  "type": "ERROR",
  "success": false,
  "message": "Pipeline step failed: AccessControl"
}
```

### Where It Happened

Real app: `examples/real-apps/ai-fabric-account-resolver`

Endpoint:

```text
POST /api/subscriptions/query
```

Flow:

```text
NaturalLanguageController
  -> RAGOrchestrator
  -> AccessControlStep
  -> AIAccessControlService
  -> EntityAccessPolicy
```

### Root Cause

AI Fabric's `AIAccessControlService` is intentionally fail-closed. It requires the customer
application to provide an `EntityAccessPolicy` bean. If that app-owned policy hook is missing, the
`AccessControlStep` fails before intent extraction, retrieval, or action execution.

This is the correct framework behavior. AI Fabric should not silently allow orchestration without an
application-owned access decision.

### Fix Pattern

Add an app-owned policy bean. For a public demo, keep it narrow and explicit:

```java
@Configuration(proxyBeanMethods = false)
class DemoAccessControlConfiguration {

    @Bean
    EntityAccessPolicy accountResolverDemoEntityAccessPolicy() {
        return (authContext, entity) -> hasSubject(authContext)
            && "rag:intent".equals(entity.get("resourceId"))
            && "READ".equalsIgnoreCase(String.valueOf(entity.get("operationType")));
    }
}
```

For production apps, replace the demo policy with checks against verified identity, tenant,
deployment, customer, scopes, and resource metadata.

### Regression Test

Add a small unit test for the policy:

- grants `rag:intent` / `READ` when `subjectId` or `sessionId` is present;
- grants anonymous-session demo access only if the demo intends to support anonymous users;
- denies unknown resources;
- denies missing subject/session.

### What Not To Do

- Do not disable or bypass `AccessControlStep` in framework code.
- Do not add a global allow-all policy to a production app.
- Do not assume the problem is an LLM/provider failure; this happens before model execution.

## Lesson 2: Do Not Fake AI Reasoning With Frontend Shortcuts

### User Symptom

The demo appears to understand a natural-language request, but the browser code is secretly routing
by text fragments such as:

```text
if prompt contains "payment" -> run update_payment_method
```

This makes the demo misleading. It bypasses AI Fabric intent extraction, orchestration policy,
read-action planning, RAG, confirmation handling, and action selection.

### Where It Happened

Real app UI: `aifabric/src/pages/demos/AIFabricAccountResolver.tsx`

The bad pattern was a prompt-click handler that mapped words like `payment`, `address`, or `refund`
directly to manual actions.

### Root Cause

The UI was trying to make the demo reliable by short-circuiting the framework. That violates the AI
Fabric philosophy: no hidden shortcuts, no pretending deterministic UI branching is model reasoning,
and no bypassing the framework path that the demo claims to prove.

### Fix Pattern

Natural-language UI controls must send the user's text to the app's AI Fabric orchestration endpoint:

```text
POST /api/subscriptions/query
```

The request should include the current user/session context and, when applicable, the real
orchestration mode:

```json
{
  "query": "Why can't I place an order? If payment is missing, add my Visa ending 4242.",
  "userId": "92",
  "mode": "resolver",
  "position": "resolver"
}
```

Manual action buttons may exist for developer/operator inspection, but they must be visibly labeled
as manual action controls. They must not masquerade as natural-language AI behavior.

### What Not To Do

- Do not route natural-language prompts with keyword matching in the frontend.
- Do not hide action execution behind scenario chips that look like AI prompts.
- Do not call a demo "AI Fabric reasoning" if the browser has already chosen the action.

## Lesson 3: `position` Is Not An Orchestration `mode`

### User Symptom

The UI sends:

```json
{
  "position": "resolver"
}
```

but the framework still behaves as the default orchestration profile.

### Root Cause

In AI Fabric, `position` is UI/context metadata. It does not become a named orchestration mode by
itself. A mode must be configured under `ai.orchestration.modes`, and either requested by the client
or set as the server-side default mode.

### Fix Pattern

Define the app-owned mode using the real typed framework properties:

```yaml
ai:
  orchestration:
    default-mode: resolver
    strict-mode-routing: true
    modes:
      resolver:
        actions-enabled: true
        actions-preferred: true
        retrieval-enabled: true
        retrieval-allowlist-required: true
        information-mode: DETERMINISTIC_RAG_GENERATE
```

Then make the UI request the mode explicitly:

```json
{
  "mode": "resolver",
  "position": "resolver"
}
```

### Regression Test

Add a test that binds the actual YAML through Spring Boot's `Binder` into
`OrchestrationProperties`. Assert enum values and nested properties, for example:

- `defaultMode == "resolver"`
- `planningMode == ITERATIVE`
- `ragCooperationMode == PARALLEL_ACTIONS_AND_RAG`
- allowlisted vector spaces match the configured evidence domains

This catches misspelled YAML fields and invalid enum values before deployment.

## Lesson 4: RAG Is Only Real If Evidence Is Indexed

### User Symptom

The app says RAG is enabled, but the model cannot reliably see policy facts.

### Root Cause

Turning on retrieval in a mode does not create knowledge. The app must register a valid vector space
and seed the documents that the mode is allowed to retrieve.

For the Account Resolver demo, policies were visible in the UI, but they were not automatically a
RAG knowledge base until the app indexed them.

### Fix Pattern

1. Register the vector space in `ai-entity-config.yml`:

```yaml
ai-entities:
  account-resolution-policy:
    entity-type: "account-resolution-policy"
    auto-embedding: true
    indexable: true
    enable-search: true
```

2. Scope the resolver mode to the evidence domains:

```yaml
rag:
  fanout-enabled: true
  retrieval-vector-spaces-allowlist:
    - account-resolution-policy
    - subscription-plan
```

3. Index app-owned policy facts through `RAGProvider.indexContent(...)` at startup or seed time.

### Regression Test

Test both sides:

- the allowed vector spaces are known entity types in `AIEntityConfigurationLoader`;
- the policy indexer calls `RAGProvider.indexContent("account-resolution-policy", ...)` with the
  policy title, description, action name, and confirmation metadata.

## Lesson 5: Use Iterative Read Planning Carefully

### User Symptom

The app needs maximum reasoning ability for resolver-style flows, but read planning must not wander.

### Root Cause

`ITERATIVE` lets the read-action planner perform more than one planning round. This can improve
reasoning when the first read result suggests a follow-up. But without tight policy controls, it can
increase latency, cost, and accidental tool use.

### Fix Pattern

Use `ITERATIVE` only with explicit bounds and an allowlist:

```yaml
read-action-resolution:
  enabled: true
  planning-mode: ITERATIVE
  require-allowlist: true
  allowed-read-actions:
    - inspect_account_readiness
  max-iterations: 2
  max-actions-per-iteration: 1
  max-total-actions: 2
  max-parallel-actions: 1
  rag-cooperation-mode: PARALLEL_ACTIONS_AND_RAG
  require-grounding-eligible: true
```

For Account Resolver, this means the model has more room to reason, but it can only use the approved
read action `inspect_account_readiness` and policy RAG. It cannot invent or call arbitrary read
tools.

### What Not To Do

- Do not enable `ITERATIVE` with no action allowlist.
- Do not raise iteration/action limits just to hide orchestration uncertainty.
- Do not use iterative read planning as a substitute for app-owned readiness APIs and policies.

## Lesson 6: RAG Mode Config Still Needs The RAG Module On The Classpath

### User Symptom

The orchestration policy says retrieval is enabled and the mode allowlists vector spaces, but the
live response includes:

```text
RAG module is not enabled (no RAGProvider bean present).
```

The response may still look partially smart because read-action resolution works, but policy or plan
retrieval does not actually run.

### Root Cause

`ai-fabric-starter` does not include `ai-fabric-rag`. This is intentional modularity. An app that
wants retrieval must explicitly depend on the RAG module. Config like this is not enough by itself:

```yaml
ai:
  orchestration:
    modes:
      resolver:
        retrieval-enabled: true
```

Without `ai-fabric-rag`, no `RAGProvider` bean is created, policy indexers that depend on
`ObjectProvider<RAGProvider>` skip indexing, and the orchestration pipeline cannot retrieve evidence.

### Fix Pattern

Add the RAG module to the real app:

```xml
<dependency>
    <groupId>io.github.loom-ai-labs</groupId>
    <artifactId>ai-fabric-rag</artifactId>
    <version>${ai-fabric.version}</version>
</dependency>
```

Then redeploy and verify:

1. startup logs show `RAGService created as default RAGProvider implementation`;
2. app debug/components endpoint, if present, confirms `RAGProvider`;
3. policy indexer logs that it indexed policy documents;
4. orchestration response no longer says `no RAGProvider bean present`;
5. retrieved documents appear for policy/plan questions.

### Regression Test

Add a small packaging/dependency test for demo apps whose release story depends on RAG, so the POM
cannot accidentally drop `ai-fabric-rag`.

## Lesson 7: Do Not Expose Context-Owned Action Parameters To The Model

### Symptom

The account resolver demo asked for backend-owned fields such as `subscriptionId`, or the model
invented a different `userId` when calling `inspect_account_readiness`.

### Root Cause

The app action method exposed context-owned values as `@Param` method arguments. AI Fabric correctly
published those parameters in the action schema, so the model treated them as fields it could fill.

### Fix Pattern

Keep model-visible action schemas limited to values the user should actually provide. Resolve current
user, active subscription, tenant, session, cart, or account state from `ActionContext` and app services
inside the handler.

For example:

- `inspect_account_readiness` should take no prompt-visible parameters and use `context.userId()`;
- `update_payment_method` should ask only for the card detail the user must supply, such as `last4`;
- app tests should assert action metadata exposes only user-supplied parameters.

## Quick Triage Checklist

### Orchestration Error Before Any AI Response

Check the `message` field:

- `Pipeline step failed: AccessControl`: app probably lacks `EntityAccessPolicy` or policy threw.
- `Pipeline step failed: IntentExtraction`: inspect LLM/provider output, structured JSON parsing, and
  extraction repair.
- `Pipeline step failed: VectorSpaceResolution`: inspect entity type/vector-space config.
- `Pipeline step failed: ResponseSanitization`: inspect unsafe output or policy metadata.

### Browser Shows API Offline

Check in this order:

1. `GET /actuator/health`
2. target route from `curl`
3. `Origin: https://ai-fabric.dev` CORS response
4. frontend bundle contains the intended backend URL
5. deployed frontend environment variables do not override the default URL

### Actions Do Not Execute

Separate these paths:

- Natural-language orchestration: `/query` endpoints go through the pipeline and access policy.
- Manual action endpoint: `/actions/execute` usually reaches `AIActionRegistry` and action handlers
  directly.
- Confirmation flows may intentionally return a non-success response until `confirmed=true`.

### Demo Appears Smart But Results Feel Too Perfect

Check the browser code before changing framework code:

- prompt chips must call the natural-language endpoint, not manual action handlers;
- `Run scenario` must send the scenario prompt through orchestration, not call a preselected action;
- manual action panels must be visibly separate from chat/prompt UX;
- the request payload should include the intended `mode` when the demo depends on a named mode.

### RAG Claims But No Policy Evidence

Check in this order:

1. the app POM includes `ai-fabric-rag`;
2. a `RAGProvider` bean exists at runtime;
3. the vector space exists in `ai-entity-config.yml`;
4. documents are actually indexed through `RAGProvider` or the app's indexing flow;
5. the orchestration mode allowlists that vector space;
6. a typed config test proves the YAML binds to `OrchestrationProperties`;
7. a policy-indexing test proves the app seeded the documents.

## Principles

- Framework-owned security must fail closed.
- Real apps must provide the app-owned policy hooks that production users would also provide.
- Demo policies should be visibly labeled and narrowly scoped.
- Every fix should include a reproducible command, code evidence, and a regression test.
- A UI shortcut is not AI Fabric intelligence.
- `position` can describe UI context, but `mode` must be explicitly configured/requested.
- RAG is a contract between mode policy, vector-space config, and indexed evidence.
- More intelligence still needs bounds: use allowlists, max iterations, and post-action evidence.
