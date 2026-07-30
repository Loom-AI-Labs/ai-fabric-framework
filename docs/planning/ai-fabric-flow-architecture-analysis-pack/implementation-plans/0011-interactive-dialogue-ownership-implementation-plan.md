# Interactive Dialogue Ownership Implementation Plan

- **Status:** Implemented and verified; not released
- **Date:** 2026-07-29
- **Framework baseline:** AI Fabric `0.4.0`
- **Code reviewed at:** `069b96d`
- **Implementation commit:** `81dd7b0`
- **Prerequisite:** Plans `0001` through `0010`
- **Target:** Conversation-manager prerequisite; version not assigned
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## 1. Purpose

Implement the missing interactive-execution boundary required before an
optional conversation manager:

- one backend-owned conversation;
- one frozen, approved history projection for the active turn;
- one exact-version dialogue-owner specialist;
- one active turn per conversation in the supported process-local runtime;
- one execution through the existing `AIExecutionGateway`; and
- at most one validated conversation append from that owner.

The first proof upgrades the existing Agentic Account Resolver `/chat`
endpoint. The caller supplies only the latest typed question and a stable
idempotency key. It does not send history, owner identity, account identity,
authority, Mode, provider, prompt, or a snapshot.

## 2. Why This Precedes A Conversation Manager

The approved conversation-manager design requires a sole dialogue owner and a
frozen approved view. Building a manager first would let model-directed
routing invent its own chat-state semantics.

Plan `0011` does not add manager directives or specialist selection. It proves
the ownership and snapshot primitive with one application-selected specialist.
A later manager may consume this primitive as an ordinary dialogue-capable
specialist.

## 3. Code-Backed Starting Point

| Current code | Consequence |
| --- | --- |
| `ConversationBinding` carries a backend-selected owner and conversation ID | Extend it with an internal opaque snapshot token while preserving the public two-argument constructor. |
| `ChatSessionService` already authorizes access and returns bounded provider-native messages | Add an execution adapter that freezes those approved messages; do not create another conversation store. |
| `ConversationEnrichmentStep` currently reloads live history during pipeline execution | Prefer an internally approved frozen snapshot when present and never accept history from the public request. |
| `DefaultAIExecutionGateway` already validates typed input/output and records only validated turns | Keep it as the only intelligence and persistence path; add snapshot validation and safe dialogue diagnostics. |
| Manifest `conversation` declares binding and validated-turn recording | Add an explicit interaction capability so ordinary workers cannot be assigned dialogue ownership accidentally. |
| The Account Resolver `/chat` endpoint already sends one typed question and binds backend session IDs | Route that method through the new interactive gateway without changing its HTTP response family. |

## 4. Deliberate First Boundary

Included:

- synchronous direct-specialist turns;
- exact-version, explicitly dialogue-capable owner;
- backend-frozen bounded history messages;
- opaque, short-lived snapshot tokens;
- process-local one-active-turn exclusion;
- stable idempotent execution replay through the existing gateway;
- safe snapshot/owner diagnostics;
- validated success-turn persistence through `ChatSessionService`; and
- visible provider, ownership, snapshot, concurrency, and persistence failure.

Deferred:

- a model-driven conversation manager;
- supervised directives or dynamic worker choice;
- interactive fixed plans, delegation, or handoff;
- durable active-turn leases across process restart;
- queued/replaced concurrent turns;
- interactive input-wait resume;
- dialogue-owner transfer;
- conversation sharing across specialists; and
- unrestricted transcript projection.

## 5. Contracts

Add:

- `SpecialistInteractionCapability` with `NON_INTERACTIVE` and
  `DIALOGUE_CAPABLE`;
- `ApprovedConversationSnapshot` as an internal approved projection in
  `OrchestrationContext`;
- `AIExecutionConversationSnapshotProvider`;
- an ephemeral opaque snapshot store;
- `AIInteractiveExecutionGateway`; and
- `DefaultAIInteractiveExecutionGateway`.

The normal `AIExecutionResult<O>` remains the response. Safe diagnostics add:

```text
interactiveTurn=true
interactionTurnId
dialogueOwner=true
dialogueOwnerSpecialist
conversationSnapshotRevision
conversationSnapshotMessageCount
conversationSnapshotTurnCount
```

No transcript, snapshot token, owner ID, conversation ID, prompt, trusted
context, or provider-native payload is added to diagnostics.

## 6. Snapshot And Turn Flow

1. The host resolves the server-owned session, conversation owner, subject,
   tenant, scopes, and exact specialist.
2. The interactive gateway validates the specialist is `DIALOGUE_CAPABLE`,
   records validated turns, accepts a conversation, and has no input
   continuation.
3. It claims the conversation for one synchronous turn.
4. The chat adapter loads the bounded approved history from
   `ChatSessionService` and creates a content-addressed revision.
5. An opaque short-lived token binds that snapshot to the exact conversation
   and dialogue owner.
6. The normal execution gateway resolves the token and places the approved
   snapshot in internal orchestration context.
7. `ConversationEnrichmentStep` uses the frozen messages instead of reloading
   live history.
8. Normal Mode, intent, RAG, action, provider, output, and policy processing
   runs.
9. A validated successful output is recorded through the existing conversation
   recorder with safe turn lineage.
10. The token and active claim are released in a `finally` boundary.

## 7. Security And Correctness Rules

1. Public callers cannot provide history or a usable snapshot token.
2. A token is random, process-local, short-lived, one-conversation bound, and
   resolved only by the execution gateway.
3. Snapshot identity is excluded from request idempotency fingerprints; an
   exact retry returns the original execution rather than conflicting because
   the conversation now includes that result.
4. User/assistant content is never logged or exposed in dialogue diagnostics.
5. Only a `DIALOGUE_CAPABLE` exact-version specialist may own the turn.
6. Dialogue capability grants no evidence, action, tenant, or subject scope.
7. One conversation cannot run two supported interactive turns concurrently.
8. Worker, plan, event, delegation, and handoff executions receive no dialogue
   ownership implicitly.
9. Snapshot resolution, history access, output validation, and persistence fail
   visibly.
10. No deterministic answer or hidden model fallback is allowed.

## 8. Reference Application

Update `account-resolver@1` to declare `DIALOGUE_CAPABLE`.

Keep:

```text
POST /api/agentic-resolver/chat
X-AI-Fabric-Demo-Session: <server-created session>
Idempotency-Key: <stable browser request key>
```

The endpoint continues to return `AIExecutionResult<AccountResolutionResult>`.
The app injects `AIInteractiveExecutionGateway`, constructs trusted context
from the current backend session, and sends only `AccountResolutionRequest`.

The proof must show:

- turn one explains the current blocker;
- turn two can interpret a short follow-up using backend history;
- exact replay does not append a duplicate turn;
- the snapshot revision in replay remains the original revision;
- a second active request is rejected with `CONVERSATION_BUSY`;
- another conversation proceeds independently; and
- application state is unchanged for read-only turns.

## 9. Test Matrix

### Manifest and contracts

- missing interaction capability defaults to `NON_INTERACTIVE`;
- dialogue-capable manifests compile and affect the content hash;
- disabled binding, disabled recording, or missing conversation pointers fail;
- unknown interaction values fail strict schema validation; and
- existing manifests remain compatible.

### Snapshot integration

- approved messages are copied immutably and bounded by the chat module;
- revision changes when the approved projection changes;
- owner/conversation mismatch is denied;
- opaque token mismatch, expiry, absence, and reuse fail;
- frozen history bypasses live history loading and live target seeding; and
- no snapshot content or token enters diagnostics.

### Interactive gateway

- eligible owner succeeds through `AIExecutionGateway`;
- non-dialogue specialist, no conversation, no recording, or input
  continuation is rejected;
- one active turn per conversation is enforced;
- different conversations may execute concurrently;
- exact idempotent replay returns the original invocation;
- changed payload under the same key conflicts;
- provider, validation, and persistence failures remain visible; and
- claims/tokens release on success, failure, timeout, and cancellation.

### Reference app

- `/chat` uses the interactive gateway and server-owned binding;
- request JSON contains only the new question;
- packaged manifest reports the owner capability;
- backend chat history contains one turn per successful invocation; and
- real OpenAI uses prior backend history for a short follow-up.

## 10. Verification Gate

The plan is complete because:

1. all core, chat-session, and execution tests passed normally;
2. all Agentic Resolver tests passed normally;
3. a clean packaged app loaded the strict manifests;
4. the packaged execution JAR matched the verified local artifact;
5. real OpenAI proved first-turn, follow-up, replay, and conflict behavior;
6. deterministic tests proved concurrency and fail-closed snapshot behavior;
7. no placeholder, disabled test, hidden fallback, or second chat store exists;
8. `git diff --check` passed; and
9. the guide distinguishes dialogue ownership from authority,
   delegation, handoff, and the later conversation manager.

### 10.1 Recorded Evidence

- The final framework execution reactor passed `1,012` tests with no failures
  or skips: 5 curated-default, 675 core, 59 chat-session, and 273 execution
  tests.
- The final real-app reactor passed 12 smoke-support tests and 125 Agentic AI
  Action Resolver tests.
- A clean packaged application passed all 125 application tests.
- The packaged `ai-fabric-execution` JAR and the locally verified Maven
  artifact both had SHA-256
  `c38749bf905ed385b51d9ed469dcd7e847dc6a93ab31a04c1c37a9f94cf707f7`.
- The packaged smoke profile exposed its intentional provider failure instead
  of fabricating a result. Exact replay returned one invocation, changed
  payload returned `IDEMPOTENCY_CONFLICT`, and caller-supplied history returned
  HTTP 400.
- Packaged real OpenAI produced a grounded `BLOCKED` assessment with
  `VERIFIED_PAYMENT_METHOD` and four policy evidence references. The short
  follow-up used a frozen two-message, one-turn backend snapshot and produced
  the same grounded blocker.
- Exact real-provider replay retained invocation
  `exec-6fa72ef8-a51e-4acb-98dd-91dad0b8072b` and snapshot revision
  `a763597b3451d9ab9841b8a22f9c9f33aa9b2f18a5937424d333c7248616bcdc`.
  Changed input under that key failed with `IDEMPOTENCY_CONFLICT`.

## 11. Next Step

After Plan `0011` is verified, Plan `0012` may implement the first bounded
conversation manager with only `ASK_USER`, `INVOKE_SPECIALIST`, and `COMPLETE`
directives over a closed registered target set. It must compare its routing
quality, latency, and cost with direct Account Resolver routing before broader
adoption.
