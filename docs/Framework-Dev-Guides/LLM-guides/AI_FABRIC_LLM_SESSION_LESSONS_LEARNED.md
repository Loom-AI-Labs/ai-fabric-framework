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

## Lesson 8: Fix Ambiguous Follow-Ups With App Prompt Overlays First

### User Symptom

The account resolver answered the first turn correctly:

```text
Why can't I place an order?
```

It inspected account readiness, found the missing payment blocker, and explained that updating the
payment method would resolve the issue. But the next user turn:

```text
ok add it
```

was classified as `OUT_OF_SCOPE` and fell back to an unrelated generic message.

### Where It Happened

Real app: `examples/real-apps/ai-fabric-account-resolver`

Prompt path used by the failing live request:

```text
intent-extraction/compound
```

At first, the browser tried to send recent turns as `historyMessages`. That was a useful temporary
debugging signal, but it was the wrong final ownership model. The corrected implementation wires the
real app to AI Fabric `ai-fabric-chat-session`; the browser sends only the current query plus
`conversationId`, while the framework loads and records history server-side.

```text
ConversationEnrichment -> provider-native history messages
ConversationRecording -> chat_sessions/chat_turns
```

### Root Cause

Conversation history existed, but the default intent-extraction prompt did not give the model a
strong enough domain rule for resolver-style follow-ups. Generic rules treated short acknowledgements
such as `ok` as simple conversational turns, while the resolver domain needed to interpret them
against the prior assistant message and the available action catalog.

This was not a core framework bug. The framework already supported app prompt overlays and
provider-native chat history through `ai-fabric-chat-session`. The missing piece was app-owned prompt
policy plus correct app wiring.

### Fix Pattern

Use an app-specific prompt overlay instead of editing framework default prompts or adding frontend
keyword routing.

Configure the overlay in the real app:

```yaml
ai:
  prompts:
    bundle:
      overlays:
        - v1-account-resolver
```

Then add only the prompt files that need domain-specific behavior:

```text
src/main/resources/prompts/intent-extraction/compound/v1-account-resolver/system.md
src/main/resources/prompts/intent-extraction/multi-step/v1-account-resolver/classify.md
```

The resolver follows this order:

```text
prompts/<family>/v1-account-resolver/<name>.md
prompts/<family>/v1/<name>.md
```

So the app can override `compound/system.md` and `multi-step/classify.md` while falling back to the
shared default bundle for everything else.

The overlay should teach the model the app-specific follow-up contract:

- use recent user and assistant turns as conversation context;
- if the previous assistant explained a missing payment method, map `ok add it`, `add it`, `do it`,
  or `update it` to `update_payment_method`;
- omit missing required parameters such as `last4`, so the backend can ask for them;
- do not treat ambiguous follow-ups as confirmation unless the framework has injected a pending
  confirmation section;
- prefer missing-parameter collection over `OUT_OF_SCOPE` when recent resolver context makes a
  supported action plausible.

### Regression Test

Add a focused config/prompt-resolution test in the real app:

- bind `ai.prompts.bundle` from both `application.yml` and `application-prod.yml`;
- assert `candidateVersions()` is `["v1-account-resolver", "v1"]`;
- resolve `intent-extraction/compound/system` and assert the loaded version is
  `v1-account-resolver`;
- resolve a non-overridden prompt such as `intent-extraction/compound/user` and assert it falls back
  to `v1`.

### Live Smoke

After deployment, verify with the real API:

1. Seed demo scenarios.
2. Ask `Why cannot I place an order?` as user `92`.
3. Send `ok add it` with the same `conversationId`; do not send browser-built `historyMessages`.
4. Expected result:

```text
type=CLARIFICATION_REQUIRED
data.action=update_payment_method
missingRequiredParameters=["last4"]
```

Then send `4242` and verify the framework still returns `CONFIRMATION_REQUIRED` before execution.

### What Not To Do

- Do not change the core fallback message to hide bad classification.
- Do not keyword-route `ok add it` in the browser.
- Do not make the browser responsible for prompt history when `ai-fabric-chat-session` is available.
- Do not copy the whole default prompt bundle into the app; override only the needed files.
- Do not execute a mutating action directly from an ambiguous follow-up without the framework
  confirmation gate.

## Lesson 9: Action Results Need User-Facing Projection, Not Raw Payload Dumps

### User Symptom

After confirming `update_payment_method`, the chat UI showed an `Action Executed` card, but the
visible result field dumped the entire nested action payload:

```text
Result: { "success": true, "message": "...", "data": { "subscriptionId": "...", ... } }
```

The useful fact was small:

```text
Payment method updated and the account is ready to continue.
Card ending in 4242 is verified.
No blockers remain.
```

### Root Cause

The backend returned a rich, valid action result. The UI renderer treated an unknown object as a
generic record and recursively printed every field, including internal IDs, readiness objects, and
policy arrays.

This is a presentation bug, not an action-handler bug. Rich action results are useful for debug,
automation, and documents panels; the main chat bubble should project only user-facing outcome
fields.

### Fix Pattern

Keep the backend result structured, but render a domain-aware summary in the demo UI.

For account resolver actions, the visible card should prefer:

- action display name;
- `message`;
- completed / needs attention;
- account ready / still blocked;
- payment verified status when relevant;
- card ending in `last4`;
- billing address summary;
- refund amount, status, and resolution type;
- remaining blockers, if any.

The visible card should suppress:

- raw `subscriptionId` and backend UUIDs;
- full nested `readiness` objects;
- full `policies` arrays;
- raw JSON for successful normal cases.

Keep raw data available in debug views, API traces, or developer panels, not in the primary chat
response.

### Where To Fix

For the public website demo, first trace the actual import path and then fix the renderer in use.
Account Resolver currently imports the shared framework chat panel, while other demos may use the
max-mode chat panel:

```text
aifabric/src/pages/demos/AIFabricFramework/components/Chat/ActionResultRenderer.tsx
aifabric/src/pages/demos/max-mode/components/ActionResultRenderer.tsx
```

The backend action result contract should not be weakened just to make the UI prettier.

### Regression Check

Build the frontend after changing the renderer:

```text
npm run build
```

Then smoke the live flow:

1. missing-payment scenario;
2. ask why ordering is blocked;
3. follow up with `ok add it`;
4. provide `4242`;
5. confirm;
6. verify the chat card shows a compact resolver summary instead of raw JSON.

### What Not To Do

- Do not remove useful fields from backend action results just because the UI is noisy.
- Do not show raw JSON in the primary user-facing chat bubble for successful actions.
- Do not hide failure/blocker details; summarize them in a small, readable status card.

## Lesson 10: Policy Decisions Belong In The Action Result, Not The UI

### User Symptom

The Account Resolver demo returned:

```text
Billing resolution created with status APPROVED
```

for a small refund, and:

```text
Billing resolution created with status PENDING_REVIEW
```

for a larger refund. The statuses were correct, but the reason was invisible. A framework user could
reasonably ask whether the AI arbitrarily approved or rejected the request.

### Root Cause

The backend service had a deterministic business rule:

```text
REFUND <= $50 -> APPROVED
ACCOUNT_CREDIT <= $100 -> APPROVED
otherwise -> PENDING_REVIEW
```

But the action execution message only exposed the final status. The first attempted fix put the policy
explanation in the frontend renderer. That made the browser look better, but it put domain truth in the
wrong layer. API users, logs, tests, and future UIs would still see a weak action result.

### Fix Pattern

Make the app-owned action result self-explaining. The service/action handler should return both the
outcome and the policy reason:

```text
message = "Billing resolution created with status PENDING_REVIEW. Routed to review because this refund is above the $50 auto-approval limit."
data.policyDecision = "REVIEW_REQUIRED"
data.policyExplanation = "Routed to review because this refund is above the $50 auto-approval limit."
data.autoApprovalLimit = 50.00
```

Then the UI may render a compact card, but it must read the explanation from the backend payload instead
of re-implementing the business rule.

### Where To Fix

For the Account Resolver demo, the source of truth belongs in:

```text
examples/real-apps/ai-fabric-account-resolver/src/main/java/com/subscription/hub/service/AccountResolutionService.java
examples/real-apps/ai-fabric-account-resolver/src/main/java/com/subscription/hub/action/handler/RequestRefundActionHandler.java
```

Tests should prove both layers:

- service tests assert `policyDecision`, `policyExplanation`, and `autoApprovalLimit` for approved and
  pending-review paths;
- action-handler tests assert `ActionResult.message` includes the explanation and `ActionResult.data`
  carries the policy fields.

### Live Verification

Do not rely only on the browser. Verify the deployed backend directly:

```text
GET /api/account-resolver/health
```

Confirm the deployed `commit` matches the expected backend commit. Then smoke the domain behavior:

```text
POST /api/account-resolver/demo/seed
POST /api/account-resolver/subscriptions/{subscriptionId}/refund
```

Finally smoke the real natural-language path:

```text
POST /api/subscriptions/query
```

Expected confirmation flow:

1. refund request returns `CONFIRMATION_REQUIRED`;
2. `Yes, confirm...` returns `ACTION_EXECUTED`;
3. `message` and action data both include the policy explanation.

### What Not To Do

- Do not encode business-policy decisions only in the frontend.
- Do not make the UI infer approval rules from `status`, `amount`, or `resolutionType` when the backend
  can return the explanation.
- Do not remove structured backend fields to avoid UI noise; fix UI projection separately.
- Do not hard-reset UI history to undo a bad presentation idea. Use surgical `git revert` so unrelated
  fixes, such as confirmation routing, stay intact.

## Lesson 11: Public Demo Sessions Need Isolation And Cleanup

### User Symptom

Multiple people can use the same public demo. If every browser uses shared users such as `91-94`,
one visitor's action can change another visitor's scenario. A browser refresh can also reset everyone
if the UI auto-calls a global seed endpoint.

### Root Cause

The first Account Resolver demo used canonical seeded personas directly. That was fine for local
testing, but not for a public site. Public demos need per-session state because confirmed actions are
real writes against the demo backend.

### Fix Pattern

Create private demo clones for each browser session:

```text
POST /api/account-resolver/demo/sessions
```

The response should return scenario metadata with UUID-backed `userId` and `subscriptionId` values.
The UI should store that response in browser storage and send the UUID user id to the natural-language
endpoint. It should not auto-run a global seed on every page load.

Keep canonical personas only as templates:

```text
91 ready account
92 missing payment
93 missing billing address
94 refund or account credit
```

### Cleanup Pattern

Every public demo clone needs a lifecycle policy. For Account Resolver, cloned users are identifiable
by:

```text
numeric userId > 100
username starts with resolver_user_
createdAt older than the configured TTL
```

Add a scheduled cleanup job that deletes related refund requests and subscriptions before deleting the
expired cloned users. Make the lifecycle configurable:

```text
APP_DEMO_CLEANUP_ENABLED=true
APP_DEMO_CLEANUP_TTL=PT6H
APP_DEMO_CLEANUP_CRON=0 */30 * * * *
```

### Regression Test

Test both sides:

- creating two demo sessions returns different UUID users/subscriptions;
- mutating one session does not mutate the other;
- cleanup deletes only cloned resolver users older than the cutoff;
- canonical users `91-94` are never selected for cleanup.

### What Not To Do

- Do not reseed shared public demo users on page load.
- Do not use global users for write-action demos.
- Do not create unbounded demo clones without a TTL cleanup path.
- Do not clean by broad username prefix alone; also require an ID range and age cutoff.

## Lesson 12: Validate The Deployed Bundle, Not Only The Commit Stamp

### User Symptom

The public site HTML reports the expected commit, but the browser still behaves like the previous UI:

```text
HTML data-commit-sha = new commit
served JS bundle still contains old strings or old endpoints
```

### Root Cause

Static frontend deployments can update wrapper metadata while still serving an old generated asset, or
the deployment platform/CDN can cache an older bundle. A commit stamp in `index.html` is useful, but it
is not sufficient proof that the shipped JavaScript contains the intended code path.

### Fix Pattern

After a frontend deploy, verify both:

```text
curl https://ai-fabric.dev/demos/ai-fabric-account-resolver
curl https://ai-fabric.dev/assets/<served-bundle>.js
```

Then search the served bundle for a build marker or endpoint that only exists in the new code:

```text
/account-resolver/demo/sessions
account-resolver-reopenable-chat-2026-07-04
```

For backend deploys, verify the app health endpoint exposes the expected commit:

```text
GET /api/account-resolver/health
```

### What Not To Do

- Do not assume a pushed frontend commit is live.
- Do not trust only the HTML commit stamp when debugging UI behavior.
- Do not test a live UI fix until the served JS bundle contains the new marker or code path.

## Lesson 13: Chat UX State Is Not AI State

### User Symptom

After closing the chat panel, the user cannot reopen it to see old messages, or a Smart Suggestion
card stays visible with no way to dismiss it.

### Root Cause

The chat panel, bottom composer, smart suggestion card, documents panel, and action cards are browser
presentation state. If the UI treats these as fixed render output with no local state, the AI result may
be correct while the experience feels broken.

### Fix Pattern

Keep AI results immutable, but add local UI controls:

- focusing/clicking the composer opens or reopens the chat panel;
- closing the chat panel should not clear `chatMessages`;
- the bottom composer should remain reachable when the history panel is open;
- Smart Suggestion cards should have a dismiss button that hides the card locally;
- dismissing a suggestion should not delete the message, result payload, documents, or action history.

### What Not To Do

- Do not clear chat history just because the panel closes.
- Do not mutate backend results to hide a noisy UI element.
- Do not make suggestions permanent when they are advisory UI, not required workflow state.

## Lesson 14: Payment Updates Must Not Send Raw Card Data Through The LLM Path

### User Symptom

The Account Resolver asks for `last4`, and a user asks why it does not request full card details.

### Root Cause

The app-owned action schema only exposes `last4` because the demo `PaymentMethod` model stores safe
payment metadata:

```text
payment type
provider
last4
verified
```

AI Fabric asks for `last4` because the action handler registered it as the required user-supplied
parameter. This is an app contract, not framework magic.

### Fix Pattern

Do not route raw card number, CVV, or full payment details through chat, LLM prompts, action traces, or
logs. A production-style flow should be:

1. AI detects that a payment method is missing.
2. UI opens a secure payment-provider form.
3. Stripe, Adyen, or another provider returns a safe token/reference.
4. The app action receives the token/reference plus safe metadata such as brand and `last4`.
5. The backend stores only provider-safe payment metadata and verification status.

For a public demo, `last4` is acceptable as a simulation of a stored or tokenized payment method. If
the UI visually asks for card details, it should still exchange them outside the LLM path and pass only
a safe token/reference into the action.

### What Not To Do

- Do not ask the model to collect full card numbers or CVV.
- Do not store raw card details in demo entities.
- Do not log card details through action payloads, prompt history, documents, or debug panels.
- Do not make the framework responsible for PCI-style payment capture; that belongs to the app and
  payment provider integration.

## Lesson 15: Use `ai-fabric-chat-session` Instead Of App-Local Chat Memory

### User Symptom

After adding chat history to improve follow-up behavior, the implementation accidentally moved
conversation ownership into the demo app:

```text
resolver_conversation_turns
ResolverConversationMemoryService
browser historyMessages
```

That made the demo work in a narrow path, but it duplicated a framework capability that already
exists.

### Where It Happened

Real app backend:

```text
examples/real-apps/ai-fabric-account-resolver
```

Frontend:

```text
aifabric/src/pages/demos/AIFabricAccountResolver.tsx
```

Framework module that should be used:

```text
ai-infrastructure-module/ai-fabric-chat-session
```

### Root Cause

The session focused on the visible symptom, missing follow-up context, and initially added an
app-local persistence path. That was the wrong abstraction boundary. AI Fabric already provides:

- `ConversationEnrichmentStep` to load bounded provider-native history;
- `ConversationRecordingStep` to persist sanitized turns after orchestration;
- `ChatSessionService` for owner-scoped sessions;
- JPA storage through `chat_sessions` and `chat_turns`;
- pending-action and pinned-target context integration.

The Account Resolver app was not using it because it lacked:

- the `ai-fabric-chat-session` dependency;
- `ai.chat.enabled=true`;
- an app-owned `ChatSessionAccessControlPolicy`.

### Fix Pattern

Wire the real app to the framework module:

```xml
<dependency>
    <groupId>io.github.loom-ai-labs</groupId>
    <artifactId>ai-fabric-chat-session</artifactId>
    <version>${ai-fabric.version}</version>
</dependency>
```

Enable chat-session in both local and prod config:

```yaml
ai:
  chat:
    enabled: true
    auto-create-sessions: true
    window-size: 8
    max-context-chars: 4000
    pinned-target-reuse-window-turns: 3
```

Provide the app-owned access policy:

```java
@Bean
ChatSessionAccessControlPolicy accountResolverChatSessionAccessControlPolicy() {
    return new ChatSessionAccessControlPolicy() {
        @Override
        public boolean canCreateConversation(String ownerId) {
            return StringUtils.hasText(ownerId);
        }

        @Override
        public boolean canAccessConversation(String ownerId, String conversationId) {
            return StringUtils.hasText(ownerId) && StringUtils.hasText(conversationId);
        }

        @Override
        public boolean canRecordTurn(String ownerId, String conversationId) {
            return StringUtils.hasText(ownerId) && StringUtils.hasText(conversationId);
        }

        @Override
        public boolean canDeleteConversation(String ownerId, String conversationId) {
            return StringUtils.hasText(ownerId) && StringUtils.hasText(conversationId);
        }
    };
}
```

Then keep the natural-language controller thin:

- set `conversationId`;
- set `userId` or `sessionId`;
- set `mode`/`position`;
- call `RAGOrchestrator`.

The controller should not inject app-specific chat-history metadata. The browser should not send
prior turns. The UI request should look like:

```json
{
  "query": "ok add it",
  "userId": "92",
  "sessionId": "demo-session-id",
  "conversationId": "resolver-demo-session-id-missing-payment-123",
  "mode": "resolver",
  "position": "resolver"
}
```

### Regression Tests

Add focused tests proving the wiring rather than retesting the whole framework:

- app POM includes `ai-fabric-chat-session`;
- `application.yml` and `application-prod.yml` bind `ai.chat.enabled=true`;
- the natural-language controller passes `conversationId` but does not add `resolverChatHistory`
  metadata;
- full app tests still pass after removing any custom history bridge.

### Live Smoke

For backend deployment, verify startup/health exposes the expected commit and the pipeline contains:

```text
ConversationEnrichment
ConversationRecording
```

For frontend deployment, verify the served JS bundle contains the current build marker:

```text
account-resolver-server-chat-session-2026-07-04
```

Then use the live browser or API to send two turns with the same `conversationId`. The second request
must not include `historyMessages`; the backend should still have enough context from
`ai-fabric-chat-session`.

### What Not To Do

- Do not add app-local chat-turn tables when the framework module can satisfy the need.
- Do not send browser-built `historyMessages` for production/demo behavior.
- Do not let UI memory become the model's source of truth.
- Do not bypass the required `ChatSessionAccessControlPolicy`; chat memory is user/session scoped.
- Do not confuse chat panel UI state with framework conversation state.

## Lesson 16: Make Non-Chat AI Demo Provider Posture Explicit

### User Symptom

The Behavior Signals demo looked useful, but it was unclear whether insight generation was backed by
a live LLM or by the local deterministic provider. That made the demo hard to explain:

```text
Is this real AI behavior analysis, or just a scripted page?
```

### Where It Happened

Real app:

```text
examples/real-apps/behavior-churn-signals
```

Public UI:

```text
aifabric/src/pages/demos/AIFabricBehaviorSignals.tsx
```

### Root Cause

The app correctly used AI Fabric's behavior module:

```text
ExternalEventProvider
  -> BehaviorAnalysisService
  -> AICoreService
  -> structured BehaviorInsights
```

But the default provider was intentionally no-key and deterministic:

```yaml
ai.providers.llm-provider: behavior-local
```

That is excellent for local development and CI, but a public demo must say so. Otherwise users may
assume the UI is faking intelligence, or assume the app is using a live provider when it is not.

### Fix Pattern

Expose a demo health endpoint that reports runtime truth:

```text
GET /api/behavior-demo/health
```

Include:

```json
{
  "provider": "behavior-local",
  "providerMode": "deterministic-local",
  "behaviorEnabled": true,
  "behaviorMode": "LIGHT",
  "commit": "...",
  "aiFabricVersion": "0.3.2"
}
```

Then make the UI show the posture directly:

```text
Offline deterministic provider
```

or:

```text
Live LLM provider
```

The deterministic provider can stay, but it must still travel through AI Fabric services and the UI
must not pretend it is a live external model.

### Public Demo State Pattern

Behavior Signals is not a chat app, but it still writes demo state when users inject signals or
preview actions. Use the same public-demo lifecycle rules:

- create session-cloned users such as `behavior-demo-user-<sessionId>-user-1001`;
- seed real behavior events for those cloned users;
- analyze cloned users through `BehaviorAnalysisService`;
- keep action policy explanations in backend result data;
- render only the useful action fields in the UI, not raw JSON;
- clean old session clones with a TTL job.

### Regression Tests

Add focused tests for:

- provider posture in `/api/behavior-demo/health`;
- session creation clones all scenarios;
- session reset requires `confirm=true`;
- deterministic provider distinguishes cancellation, expansion, onboarding friction, release
  regression, and silent churn;
- action policy caps or explanations are returned by the backend service.

### What Not To Do

- Do not call a deterministic provider "live AI".
- Do not fake behavior insight in frontend code.
- Do not use one shared public user set for write-action demos.
- Do not put retention/discount policy truth only in UI labels.
- Do not leave public demo clones without cleanup.

## Lesson 17: Deterministic Governance Demos Still Need Backend Proof

### User Symptom

The Tenant Guard demo is intentionally deterministic and no-key, but that can make the UI look like
it is merely displaying preselected cards:

```text
Is tenant isolation actually enforced by the backend, or is the page just showing separate columns?
```

### Where It Happened

Real app:

```text
examples/real-apps/tenant-knowledge-portal
```

Public UI:

```text
aifabric/src/pages/demos/AIFabricTenantGuard.tsx
```

### Root Cause

Tenant Guard is not an LLM reasoning demo. It proves governance boundaries: tenant-scoped retrieval,
catalog visibility, write-action gating, confirmation, and tenant deletion evidence. Those rules must
come from the backend service, not from frontend labels or static assumptions.

The demo also mutates state when a visitor deletes tenant evidence. Without a session id, one browser's
deletion can affect another visitor's proof state.

### Fix Pattern

Keep the demo deterministic, but make backend truth explicit:

- return a backend-generated `boundaryProof` checklist from `/api/tenant-guard-demo/dashboard`;
- include `policyDecision` and `policyExplanation` in action result data;
- include deletion `message`, `policyDecision`, deleted ids, and remaining tenant ids in deletion
  responses;
- pass `sessionId` on dashboard, compare, action, reset, and delete endpoints;
- keep per-session document maps with a TTL cleanup window;
- expose `/api/demo/health` and render the deployed commit/build metadata in the UI.

### What Not To Do

- Do not add a fake LLM layer just to make a governance demo feel "AI".
- Do not make the browser decide whether tenant boundaries passed.
- Do not let a public delete/write experiment mutate every visitor's shared data.
- Do not hide deterministic provider posture; say clearly that this demo proves AI Fabric guardrails,
  not live LLM generation.

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
- If a vague follow-up such as `ok add it` becomes `OUT_OF_SCOPE`, inspect prompt overlays and
  `ai-fabric-chat-session` wiring before changing framework code.
- If an action executes but the UI looks bad, inspect the renderer before changing the backend action
  contract.
- If an action executes with the right status but unclear reasoning, inspect the app service/action
  result. The backend should explain the domain policy decision before the UI decorates it.

### Demo Appears Smart But Results Feel Too Perfect

Check the browser code before changing framework code:

- prompt chips must call the natural-language endpoint, not manual action handlers;
- `Run scenario` must send the scenario prompt through orchestration, not call a preselected action;
- manual action panels must be visibly separate from chat/prompt UX;
- the request payload should include the intended `mode` when the demo depends on a named mode.
- the request payload should not carry browser-built chat history when server-side chat-session is
  enabled.

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
- A UI explanation is not domain truth; action results must carry business-policy outcomes and reasons.
- `position` can describe UI context, but `mode` must be explicitly configured/requested.
- RAG is a contract between mode policy, vector-space config, and indexed evidence.
- More intelligence still needs bounds: use allowlists, max iterations, and post-action evidence.
- App prompt overlays are the right place for app-specific LLM behavior when the framework already
  supports the needed mechanism.
- Backend action results should stay structured; user-facing UIs should project the best fields for
  the current workflow.
- Public write-action demos need per-session state and a cleanup lifecycle.
- A deployed commit stamp is not enough; verify the served JS bundle or backend health commit.
- Chat panel visibility, dismissed suggestions, and composer focus are UI state, not AI state.
- Payment capture belongs outside the LLM/action text path; pass only safe tokens or metadata.
- Conversation memory belongs in `ai-fabric-chat-session` when available; the browser sends the new
  turn and stable conversation identifiers, not prior prompt history.
