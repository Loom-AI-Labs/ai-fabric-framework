# AI Fabric `0.5.x` Live Demo Verification Plan

- **Status:** Source implementation and verification complete; hosted deployment gate pending
- **Date:** 2026-08-04
- **Framework baseline:** AI Fabric `0.5.2`
- **Primary release evidence:** [`0.5.0`](../release-notes/0.5.0.md),
  [`0.5.1`](../release-notes/0.5.1.md), and [`0.5.2`](../release-notes/0.5.2.md)
- **Related product proposal:**
  [LoomAI AI Enablement Product And Deployment Template Proposal](0018-loomai-ai-enablement-product-and-deployment-template-proposal.md)
- **Backend repository:** `Loom-AI-Labs/ai-fabric-framework`
- **Public UI repository:** `Loom-AI-Labs/aifabric`
- **Release requirement:** the shared framework hardening in this implementation must be published
  as the next immutable patch after `0.5.2` before Maven Central-only deployment images are used.

## 1. Purpose

AI Fabric `0.5.x` introduced a bounded specialist execution layer, durable read jobs, governed
write receipts, human review, fixed specialist composition, an application-facing indexing work
status contract, and a corrected trusted retrieval boundary.

The existing public demos mainly prove the earlier framework surfaces:

- RAG and commerce actions;
- ordinary account resolution;
- behavior insight and allowlisted agentic UI;
- tenant-safe retrieval and writes;
- privacy-safe indexing and search; and
- annotation-driven live data synchronization.

Those demos remain valuable, but they do not make the complete `0.5.x` execution contract visible.
This plan adds a focused live verification portfolio without turning every Java interface into a
separate application.

The target portfolio must prove:

```text
manifest-defined typed specialist
  + backend-owned identity and conversation
  + approved evidence and effective capabilities
  + bounded wait, plan, delegation, or handoff
  + governed write or durable read execution
  + explicit persistence, replay, and failure behavior
```

A polished UI is evidence of usability. It is not, by itself, proof of runtime correctness. Every
live demo in this plan therefore requires automated API, security, packaged-runtime, and live
provider canaries behind the visible experience.

### 1.1 Implementation record

The complete source portfolio described by this plan is now implemented across the framework and
public UI repositories:

| Experience | Implemented backend | Implemented UI route |
| --- | --- | --- |
| Agentic AI Action Resolver | `agentic-ai-action-resolver` | `/demos/ai-fabric-agentic-action-resolver` |
| Human Review Operations Desk | `agentic-ai-action-resolver` | `/demos/ai-fabric-agentic-action-resolver/review` |
| Deployment Knowledge Guard | `deployment-knowledge-guard` | `/demos/ai-fabric-deployment-knowledge-guard` |
| Proactive Behavior And Risk Analyst | upgraded `behavior-churn-signals` | `/demos/ai-fabric-behavior-signals` |
| Incident Investigation Room | `incident-investigation-room` | `/demos/ai-fabric-incident-investigation` |
| Live Data Sync Operations | upgraded `ai-fabric-live-data-sync` | `/demos/ai-fabric-live-data-sync` |
| Live MCP Operations Assistant | `mcp-operations-assistant` plus `mcp-operations-reference-server` | `/demos/ai-fabric-mcp-operations` |

Implementation also added the shared framework behavior required by those applications:

- exact, fail-closed MCP `serverRef` selection with lazy client initialization and bounded result
  projection;
- canonical grounding observations for successful read actions;
- manifest grounding through any allowlisted requestable read action;
- preservation of backend-resolved trusted parameters through required-parameter validation; and
- trusted tenant and deployment filters in the RAG search request boundary.

The source implementation is not described as publicly live yet. The new backends and UI bundle
must be committed, released where framework artifacts changed, deployed, and then exercised at
their hosted URLs before the remaining live checkboxes in Section 16 can be closed.

## 2. Non-Negotiable Demo Rules

Every implementation in this plan must follow these rules:

1. AI Fabric and the configured model provider are the only intelligence sources. Do not fabricate
   specialist results in frontend code or deterministic fallback text.
2. Provider, persistence, authorization, validation, retrieval, and MCP failures remain visible.
3. Public request bodies cannot supply principal, subject, tenant, deployment, scopes, specialist
   authority, provider credentials, reviewer identity, or executable trusted parameters.
4. Every browser receives an isolated, server-created demo session and isolated mutable domain
   state.
5. Demo reset deletes only that session's domain, conversation, vector, job, receipt, and review
   state.
6. Expired demo sessions are removed by a bounded cleanup job.
7. No public UI control may restart a shared deployment. Restart claims are proven by a controlled
   deployment canary.
8. Health output reports source commit, build time, AI Fabric version, application version,
   specialist IDs and hashes, provider readiness, and required storage readiness.
9. Durable state uses stable secrets and persistent storage across application restart. Secrets are
   never returned by health or debug endpoints.
10. Every result card uses an application-owned safe projection. Raw model responses, encrypted
    payloads, trusted context, and internal persistence records are never shown.
11. Every demo has an explicit local/mock profile and an explicit live-provider profile. The public
    site uses the live-provider profile where model intelligence is claimed.
12. Existing public demos retain their current behavior unless this plan explicitly identifies an
    upgrade.

## 3. Portfolio Decision

### 3.1 Implementation classification

| Experience | Backend decision | UI decision | Current status |
| --- | --- | --- | --- |
| Agentic AI Action Resolver | Promote and harden the existing source app | Create a new UI | Exact-source image, real OpenAI, replay, and restart verified; deployment pending |
| Human Review Operations Desk | Reuse Agentic Resolver backend | Create a separate reviewer UI route | Real OpenAI, durable review, approval, replay, and restart verified on the same image |
| Deployment Knowledge Guard | Create a new real app, borrowing only safe patterns from Tenant Guard | Create a new UI | Exact-source image, real OpenAI, scoped retrieval, and security canaries verified; deployment pending |
| Proactive Behavior And Risk Analyst | Upgrade existing Behavior Signals backend | Upgrade existing UI with durable execution views | Real OpenAI durable analysis and structured agentic UI verified; redeployment pending |
| Incident Investigation Room | Create a new real app | Create a new UI | Real OpenAI composition, delegation, handoff, replay, and branch-failure paths verified; deployment pending |
| Live Data Sync Operations | Upgrade existing Live Data Sync backend | Upgrade existing UI with indexing lifecycle | Exact-source image, real OpenAI, lifecycle, concurrency, and restart verified; redeployment pending |
| Live MCP Operations Assistant | Upgrade existing source app and add a real remote MCP server app | Create a new UI | Real OpenAI plus authenticated remote MCP, replay, restart, and outage paths verified; two-service deployment pending |

### 3.2 Existing public demos kept unchanged

The following public experiences remain available and are not replaced:

- AI Shopping Experience;
- AI Fabric Account Resolver;
- AI Fabric Tenant Guard; and
- AI Fabric Privacy Shield.

The new Agentic AI Action Resolver is deliberately separate from the existing Account Resolver.
Deployment Knowledge Guard is deliberately separate from Tenant Guard. This preserves the simpler
framework stories while making the new specialist runtime independently testable.

### 3.3 Existing source apps reused

- [`agentic-ai-action-resolver`](../../examples/real-apps/agentic-ai-action-resolver/README.md)
  already contains the broad `0.5.x` reference backend and Dockerfile.
- [`mcp-operations-assistant`](../../examples/real-apps/mcp-operations-assistant/README.md) now uses
  the authenticated remote MCP reference server in its live profile. Its deterministic executor is
  restricted to the explicit local smoke profile.
- [`behavior-churn-signals`](../../examples/real-apps/behavior-churn-signals/README.md) already owns
  the behavior-analysis domain and UI scenarios.
- [`ai-fabric-live-data-sync`](../../examples/real-apps/ai-fabric-live-data-sync/README.md) already
  proves annotation-driven create, update, delete, and retrieval behavior.

## 4. Capability Coverage Matrix

| `0.5.x` capability | Primary live proof | Secondary proof |
| --- | --- | --- |
| Exact `name@version` specialist | Agentic AI Action Resolver | Incident Investigation Room |
| YAML/JSON manifest compilation and content hash | Agentic AI Action Resolver | Deployment Knowledge Guard |
| Typed input and output | Agentic AI Action Resolver | All specialist demos |
| Effective capability intersection | Deployment Knowledge Guard | Agentic AI Action Resolver |
| Backend-owned conversation history | Agentic AI Action Resolver | Incident conversation manager |
| Typed input wait and safe resume | Agentic AI Action Resolver | None required |
| Governed write proposal | Agentic AI Action Resolver | MCP Operations Assistant |
| Durable action receipt and replay | Agentic AI Action Resolver | Human Review Operations Desk |
| Durable human review | Human Review Operations Desk | None required |
| Event-triggered specialist | Proactive Behavior And Risk Analyst | Agentic Resolver event canary |
| Durable read job and lease recovery | Proactive Behavior And Risk Analyst | Controlled restart canary |
| Fixed sequential plan | Incident Investigation Room | Agentic Resolver plan lab |
| Bounded parallel plan | Incident Investigation Room | Agentic Resolver plan lab |
| One-level delegation and handoff | Incident Investigation Room | Agentic Resolver API canary |
| Bounded conversation manager | Incident Investigation Room | Agentic Resolver API canary |
| Trusted tenant/deployment retrieval context | Deployment Knowledge Guard | Cross-boundary security canary |
| Public indexing work status | Live Data Sync Operations | Deployment Knowledge Guard indexing panel |
| Real remote MCP execution | Live MCP Operations Assistant | None required |

No demo should claim dynamic model-generated graphs, recursive delegation, unrestricted tool
selection, exactly-once provider calls, durable input waits, or durable composed plans. Those are
not AI Fabric `0.5.2` capabilities.

## 5. Demo 1: Agentic AI Action Resolver

### 5.1 Decision

Promote the existing
[`agentic-ai-action-resolver`](../../examples/real-apps/agentic-ai-action-resolver/README.md) into a
public deployment. Do not copy it again and do not replace the existing non-agentic Account
Resolver.

The backend already has manifest-defined specialists, typed waits, backend conversation, fixed
plans, delegation, handoff, proactive events, durable read execution, governed write receipts, and
human review. Work here is production-profile hardening, public demo isolation, API projection, and
UI construction rather than rebuilding the runtime proof.

### 5.2 User story

A user asks why an account is blocked. The specialist reads current account state, retrieves policy
evidence, explains the blocker, asks for supported missing information when necessary, proposes one
registered resolution, waits for confirmation, executes once, and reconciles against the current
database state.

### 5.3 Required UI

Create `/demos/ai-fabric-agentic-action-resolver` with:

- isolated scenario users;
- a current account profile panel;
- chat using only the newest user message;
- evidence cards with safe IDs and vector spaces;
- a typed missing-input form generated from the returned response schema;
- a confirmation card that exposes no executable parameters;
- safe action outcome cards;
- invocation timeline showing specialist, version, content hash, status, and replay state;
- an effective-capability inspector for approved Mode, actions, vector spaces, and scopes;
- a manifest inspector showing public metadata, not prompts or secrets;
- reset and refresh controls; and
- a link to the Human Review Operations Desk when a scenario creates review work.

### 5.4 Required scenarios

1. Read-only account diagnosis with policy evidence.
2. Follow-up question resolved from backend conversation history.
3. Missing billing amount returns `WAITING_FOR_INPUT` before provider execution.
4. Malformed resume is rejected; a valid response resumes the original invocation.
5. Reusing the successful resume key returns the same result without another model call.
6. Address update returns `CONFIRMATION_REQUIRED` and performs no mutation before confirmation.
7. Reject preserves the original account.
8. Confirm executes once and the next profile read sees the authoritative update.
9. Replaying the confirmed receipt returns the same safe outcome without a second mutation.
10. A second browser session cannot inspect, resume, confirm, or replay the first session's work.
11. Provider failure is visible and creates no proposal or fabricated answer.

### 5.5 Backend work

- audit every public endpoint for the server-created demo-session boundary;
- provide one stable public response envelope for execution, wait, proposal, and failure states;
- expose safe specialist runtime metadata through demo health;
- ensure receipt and durable-execution secrets are required in the hosted profile;
- keep pending input waits explicitly labelled `EPHEMERAL`;
- add session-scoped reset and cleanup for domain rows, chat sessions, pending waits, and terminal
  demo state where safe;
- keep unresolved receipts and review obligations auditable rather than deleting them blindly; and
- run the app using released Maven Central `0.5.2` artifacts in the source-candidate Docker build.

### 5.6 Acceptance gate

The demo is complete when its UI scenarios, API integration suite, real-provider suite, cross-session
security suite, receipt replay suite, and controlled restart suite all pass against the packaged
deployment.

## 6. Demo 2: Human Review Operations Desk

### 6.1 Decision

Create a separate UI route backed by the Agentic AI Action Resolver deployment. Do not create a
second review backend and do not combine reviewer controls into the customer chat window.

Suggested route:

```text
/demos/ai-fabric-agentic-action-resolver/review
```

### 6.2 User story

A support-credit specialist creates one governed proposal. Application policy routes it to a
durable review task. An authenticated demo reviewer inspects safe evidence and chooses an allowed
decision. Approval still executes through the linked governed action receipt.

### 6.3 Required UI

- review inbox with status, age, assignment, and required reviewer level;
- task detail with specialist/version/hash, safe evidence, proposal summary, and allowed decisions;
- regular and senior reviewer demo identities issued server-side;
- approve, reject, correct, request-information, provide-information, and escalate controls only
  when returned as allowed decisions;
- immutable decision timeline;
- linked receipt status and safe final action outcome;
- explicit expiry, conflict, and recovery states; and
- refresh behavior that reloads state from the backend rather than retaining an optimistic result.

Permanent reviewer API keys must never be shipped to browser JavaScript. The public demo may issue
short-lived, session-bound demo reviewer credentials, but the backend remains responsible for
mapping them to a trusted reviewer context.

### 6.4 Required scenarios

1. Small account credit creates a review task without executing a write.
2. Regular reviewer approves an eligible task and one mutation occurs.
3. Regular reviewer is denied when senior authority is required.
4. Senior reviewer approves an escalated task.
5. Rejection performs no mutation.
6. Correction creates a successor proposal/task without rewriting history.
7. Information request and response survive separate HTTP requests.
8. Identical decision replay returns the stored outcome.
9. Changed decision or reviewer under the same decision key conflicts.
10. A controlled application restart preserves task, delivery, decision, linked receipt, and final
    projection.

## 7. Demo 3: Deployment Knowledge Guard

### 7.1 Decision

Create a new real app and UI. Reuse proven session, seed, vector, and access-policy patterns from
[`tenant-knowledge-portal`](../../examples/real-apps/tenant-knowledge-portal/README.md), but preserve
the current Tenant Guard application and public route unchanged.

Suggested backend directory:

```text
examples/real-apps/deployment-knowledge-guard
```

Suggested UI route:

```text
/demos/ai-fabric-deployment-knowledge-guard
```

### 7.2 User story

An authenticated operator asks about one deployment's health, release, indexing state, incidents,
and runbooks. A read-only exact-version specialist receives only server-verified tenant,
deployment, subject, and scopes. RAG returns approved evidence from that boundary.

### 7.3 Data model

Use two tenants with two deployments each and intentionally overlapping titles:

- deployment status snapshots;
- release/change records;
- safe incident summaries;
- runbook sections; and
- indexing work summaries.

The model must not infer authorization from prompt text. Tenant and deployment are metadata filters
constructed from `TrustedExecutionContext` and verified after retrieval.

### 7.4 Required UI

- server-assigned operator, tenant, deployment, and scope panel;
- deployment inventory and indexed-evidence proof;
- read-only specialist chat;
- evidence lineage with safe document IDs, source type, vector space, and revision;
- a security canary panel for cross-tenant, cross-deployment, spoofed-metadata, and missing-scope
  attempts;
- provider and retrieval failure cards; and
- health proof for AI Fabric `0.5.2`, specialist hash, provider, and vector readiness.

The attack panel submits test intent, not alternate trusted identity. The backend constructs the
hostile adapter metadata internally and proves that verified context replaces or removes it.

### 7.5 Required scenarios

1. Same question against two deployments returns different, correctly scoped evidence.
2. Overlapping document titles do not cause cross-tenant evidence.
3. Spoofed adapter tenant/deployment fields are removed and canonical values are restored.
4. Missing tenant, deployment, specialist, vector, or action scope fails closed.
5. Unsupported safe metadata remains available while reserved identity metadata cannot leak.
6. Provider failure remains visible and does not return cached or fabricated intelligence.
7. Cross-deployment evidence IDs never reach the generation request or public answer.

This is the primary live regression proof for the security correction documented in
[`0.5.2`](../release-notes/0.5.2.md).

## 8. Demo 4: Proactive Behavior And Risk Analyst

### 8.1 Decision

Upgrade the deployed
[`behavior-churn-signals`](../../examples/real-apps/behavior-churn-signals/README.md) backend and its
existing UI. Preserve current raw-event recording, insight display, positive/negative recovery
scenarios, and allowlisted agentic UI composition.

### 8.2 New runtime behavior

Replace or supplement the synchronous analysis path with an application-owned raw event adapter
that submits a read-only specialist through `DurableAIExecutionGateway`.

```text
raw application event
  -> server-owned user/tenant mapping
  -> previous approved insight + events since that insight
  -> exact behavior-risk-analyst@1 specialist
  -> encrypted durable read job
  -> worker lease and real provider call
  -> typed insight snapshot
  -> allowlisted component-planning request
```

The event body contains event facts only. It cannot select a user, specialist, provider, tenant,
scope, prompt, or action. Analysis remains read-only and never applies a retention offer
automatically.

### 8.3 Required UI upgrade

- keep the raw event timeline with newest events first;
- show an explicit `Run user behavior analysis` action;
- display durable invocation ID and lifecycle status;
- distinguish previous insight, newly considered events, and resulting insight;
- show replay versus new execution;
- show queued, leased/processing, completed, failed, cancelled, and expired states;
- retain the existing agentic home preview using only allowlisted component names;
- add positive and negative event packs without coupling them to deterministic insight labels; and
- keep reset under the full-page loading state.

### 8.4 Required scenarios

1. Raw negative events produce one durable analysis without an automatic write.
2. Exact event redelivery returns the original invocation and result.
3. Changed facts under the same event ID return `IDEMPOTENCY_CONFLICT`.
4. Positive events submitted after a prior insight are analysed as previous insight plus new events.
5. The resulting component plan changes only when the new typed insight supports it.
6. Another session cannot inspect or cancel the job.
7. A queued job survives controlled restart.
8. An expired lease is recovered without creating a second durable invocation record.
9. Provider failure remains a failed analysis and does not preserve an old result as if it were new.

## 9. Demo 5: Incident Investigation Room

### 9.1 Decision

Create a new backend and UI. This is the focused proof for bounded specialist composition. Do not
turn Agentic Resolver into a generic graph visualizer.

Suggested backend directory:

```text
examples/real-apps/incident-investigation-room
```

Suggested UI route:

```text
/demos/ai-fabric-incident-investigation
```

### 9.2 User story

An operator investigates a deployment regression. Independent specialists inspect service health
and recent changes/runbooks. AI Fabric runs a fixed application-declared plan and returns a
deterministically aggregated, evidence-linked incident assessment.

### 9.3 Specialist design

Use a closed catalogue such as:

```text
service-health-reader@1
change-risk-reader@1
incident-intake@1
incident-conversation-manager@1
```

Register two equivalent plans:

```text
incident-investigation-sequential@1
incident-investigation-parallel@1
```

Both plans invoke the same two read-only specialists over the same immutable source revision. The
parallel plan uses `ALL_REQUIRED`; one failed branch fails the whole plan and produces no partial
fan-in answer.

### 9.4 Required UI

- incident and deployment selector backed by an isolated seed;
- sequential/parallel segmented control;
- plan timeline with declared steps, specialist versions, invocation IDs, evidence, and duration;
- side-by-side parity comparison for typed outcomes;
- delegation lab showing one closed target selection;
- handoff lab showing predecessor and successor lineage;
- conversation-manager chat that can ask one question, invoke one approved worker, or complete;
- explicit child failure and cancellation state; and
- no editable graph, arbitrary specialist picker, or unrestricted prompt box for topology.

### 9.5 Required scenarios

1. Sequential and parallel plans return semantically equivalent typed results.
2. Parallel execution is measured, not promised to be faster for every call.
3. One branch failure cancels outstanding work and returns no synthetic partial result.
4. The coordinator delegates only to an exact allowlisted read-only target.
5. Handoff transfers typed responsibility but no conversation, pending action, or hidden context.
6. Recursive delegation/handoff and a second transition are rejected.
7. Conversation manager uses backend history and only `ASK_USER`, `INVOKE_SPECIALIST`, or
   `COMPLETE`.
8. Replayed manager input does not append a second turn or invoke a second worker.

## 10. Demo 6: Live Data Sync Operations

### 10.1 Decision

Upgrade the existing deployed
[`ai-fabric-live-data-sync`](../../examples/real-apps/ai-fabric-live-data-sync/README.md) backend and
UI. Do not create a second indexing demo.

### 10.2 New runtime behavior

Every accepted create, update, and delete response should retain and expose the safe opaque
`metadata.indexingWorkId`. The app uses `IndexingWorkQuery` to project public
`IndexingWorkStatus`; it never exposes an indexing queue entity or stored payload.

### 10.3 Required UI upgrade

- source entity editor and existing vector proof;
- indexing work timeline linked to each source mutation;
- lifecycle cards for `COMMIT_PENDING`, `PENDING`, `PROCESSING`, `COMPLETED`, `SUPERSEDED`, and
  `DEAD_LETTER`;
- retry count, bounded safe error code, correlation ID, and timestamps;
- explicit message that `INDEXING_RETRYABLE` means accepted source data with unfinished derived
  work;
- before/after grounded answer comparison; and
- operator guidance for terminal success, superseded work, and dead-letter review.

### 10.4 Required scenarios

1. Create reaches `COMPLETED`, then appears in grounded retrieval.
2. Update reaches `COMPLETED`, then the answer reflects the new source revision.
3. Delete reaches `COMPLETED`, then the deleted evidence is unavailable.
4. Two rapid updates make older work `SUPERSEDED` without restoring stale content.
5. A controlled provider failure leaves durable pending/retryable work.
6. Retry recovery completes the original work ID rather than requiring blind resubmission.
7. Exhausted retries become `DEAD_LETTER` with no payload or worker identity leakage.

Vector existence is supporting evidence only. The public indexing work status is the authoritative
completion contract for the submitted work.

## 11. Demo 7: Live MCP Operations Assistant

### 11.1 Decision

Upgrade the existing source-only
[`mcp-operations-assistant`](../../examples/real-apps/mcp-operations-assistant/README.md) and create a
new authenticated remote MCP reference server. Create a new UI and deploy both services.

The local deterministic executor remains useful for unit and offline smoke profiles, but the live
profile is wired only to the authenticated remote server and has no local fallback.

Suggested companion backend directory:

```text
examples/real-apps/mcp-operations-reference-server
```

Suggested UI route:

```text
/demos/ai-fabric-mcp-operations
```

### 11.2 User story

An operator asks for sandbox service status through a remote MCP read tool, then requests a
controlled sandbox restart through a remote write tool. AI Fabric binds the exact server and tool,
projects arguments, requires confirmation, records a receipt, invokes the remote tool, and returns
a safe mapped result.

### 11.3 Required remote tools

Use isolated demo resources only:

```text
get_sandbox_service_status     READ
list_recent_sandbox_incidents  READ
restart_sandbox_service        WRITE
```

The MCP server must not expose shell, arbitrary HTTP, SQL, unrestricted deployment operations, or
real production credentials.

### 11.4 Required UI

- isolated sandbox service inventory;
- assistant conversation and evidence/tool timeline;
- exact server reference, safe tool name, access mode, and bounded timing;
- projected public arguments, excluding trusted runtime values;
- confirmation card for the restart tool;
- receipt and safe tool outcome;
- MCP connection health and explicit unavailable-server state; and
- a server-binding canary that proves a duplicate tool name on another server cannot be selected.

### 11.5 Required scenarios

1. Authenticated remote read tool executes without write confirmation.
2. Remote write tool cannot execute before AI Fabric confirmation.
3. Reject produces no MCP write call.
4. Confirm executes one sandbox mutation and replay does not execute it twice.
5. Unresolved `serverRef` fails closed even when another server exposes the same tool name.
6. Missing MCP authentication remains visible.
7. Oversized, malformed, or unsafe MCP result content is rejected or safely bounded.
8. Caller-supplied tenant, deployment, authority, and trusted arguments are ignored or rejected.
9. MCP outage does not fall back to a local deterministic executor in the live profile.

## 12. Shared Backend Requirements

### 12.1 Persistence

Use each application's existing test-friendly persistence locally. Hosted durability claims require
persistent JDBC storage across application restart. Applications own Flyway or Liquibase migrations
and disable framework schema initialization in the hosted profile.

At minimum, durable demos must preserve the applicable tables for:

- `ai_specialist_execution`;
- `ai_action_proposal_receipt`;
- `ai_review_task`;
- `ai_review_dispatch`;
- chat-session state; and
- application domain truth.

The database remains application infrastructure. It is not an LLM memory substitute and is not a
second vector catalogue.

### 12.2 Provider configuration

Live specialist demos use real configured provider calls. Keys are supplied only through protected
deployment environment variables. At minimum:

```text
OPENAI_ENABLED=true
OPENAI_API_KEY=<protected>
OPENAI_MODEL=<supported chat model>
OPENAI_EMBEDDING_MODEL=<supported embedding model>
```

If Claude is added as an optional generation provider, RAG still needs a separately configured
embedding provider. Provider switching must not alter trusted context, capabilities, confirmation,
receipt, or review behavior.

### 12.3 Public health contract

Every backend exposes `/api/demo/health` with safe fields equivalent to:

```json
{
  "status": "UP",
  "applicationVersion": "...",
  "sourceCommit": "...",
  "buildTime": "...",
  "aiFabricVersion": "0.5.2",
  "provider": {
    "generation": "openai",
    "embeddings": "openai",
    "ready": true
  },
  "specialists": [
    {"id": "example@1", "contentHash": "...", "ready": true}
  ],
  "storage": {
    "domain": "UP",
    "vector": "UP",
    "execution": "UP"
  }
}
```

Do not return environment values, endpoints containing credentials, encryption keys, fingerprint
keys, reviewer secrets, database URLs, prompt text, or provider-native errors.

### 12.4 Demo session lifecycle

Each public UI uses an opaque backend session ID stored in browser session storage. Backend state is
bound to that opaque session and a generated demo principal. Session reset and scheduled cleanup
must be tested for cross-session safety and unresolved durable obligations.

## 13. Shared UI Requirements

All new UI experiences should use the established AI Fabric demo shell and the reusable AI Fabric
chat UI where its transport matches the endpoint. Domain-specific components are registered as
allowlisted renderers rather than embedded in the generic chat component.

Required shared behavior:

- full-page loader while creating or resetting a demo session;
- visible backend version/readiness before interaction;
- responsive desktop and mobile layout;
- explicit loading, empty, success, denied, failed, expired, cancelled, and replay states;
- no raw JSON as the primary user experience;
- optional developer inspector for safe typed payloads;
- tooltips for unfamiliar controls;
- dismissible video introduction when a walkthrough exists;
- `About this demo` page with modules, annotations, manifests, providers, storage, request flow,
  architecture, security boundary, and source/Docker paths;
- scenario reset and guided test cases; and
- accessible keyboard and focus behavior for confirmation and review controls.

## 14. Verification Strategy

### 14.1 Test layers

Every changed or new backend requires:

1. contract and DTO tests;
2. manifest compilation and startup-validation tests;
3. adapter, validator, projector, and capability-intersection tests;
4. controller and cross-session authorization tests;
5. persistence integration tests;
6. packaged-runtime tests using released AI Fabric artifacts;
7. real-provider tests with no fallback;
8. restart/replay tests where durability is claimed; and
9. public live API canaries after deployment.

Every UI requires:

1. component tests for all returned runtime states;
2. transport contract tests;
3. session/reset tests;
4. confirmation and review accessibility tests;
5. Playwright desktop/mobile workflows;
6. screenshots for primary states; and
7. live smoke tests against the deployed backend.

### 14.2 Shared security canaries

Run these against every applicable live deployment:

- second-session access to invocation, wait, receipt, review, or evidence;
- spoofed subject, tenant, deployment, scopes, specialist, provider, and action;
- missing exact specialist scope;
- missing vector-space or action scope;
- unsupported evidence metadata;
- changed payload under a retained idempotency key;
- malformed structured output;
- provider timeout/failure;
- persistence unavailable; and
- stale UI replay after session reset.

### 14.3 Controlled restart proof

The deployment pipeline, not a public browser, performs restart proof:

1. create the durable job, receipt, or review task;
2. record safe identity and state;
3. restart or redeploy the packaged application without deleting persistent storage;
4. resume, decide, or replay through the public application contract;
5. verify the original identity and terminal result;
6. verify no second write occurred; and
7. attach the canary result to release evidence.

## 15. Delivery Order

### Phase 0: Shared readiness

1. Freeze the public response shapes needed by the new UIs.
2. Extract shared UI execution, receipt, evidence, review, and lifecycle components.
3. Standardize demo health, session reset, cleanup, and build metadata.
4. Add a reusable live-canary runner and evidence format.

### Phase 1: Agentic resolver and review

1. Harden and deploy Agentic AI Action Resolver.
2. Build its main UI and About page.
3. Build Human Review Operations Desk on the same backend.
4. Complete real-provider, receipt, review, and restart gates.

This phase provides the highest coverage because the backend already exists.

### Phase 2: Trusted retrieval security

1. Build Deployment Knowledge Guard.
2. Prove tenant/deployment/scope propagation on `0.5.2`.
3. Publish cross-boundary live canary results.

### Phase 3: Upgrade existing public demos

1. Upgrade Behavior Signals with durable event execution.
2. Upgrade Live Data Sync with `IndexingWorkQuery` lifecycle visibility.
3. Preserve all existing scenarios and run regression suites.

### Phase 4: Bounded composition

1. Build Incident Investigation Room.
2. Prove sequential/parallel parity and failure behavior.
3. Add delegation, handoff, and conversation-manager scenarios.

### Phase 5: Real MCP

1. Harden exact MCP server selection to fail closed.
2. Build and deploy the authenticated remote reference server.
3. Upgrade MCP Operations Assistant to the real live profile.
4. Add confirmed remote write, outage, and duplicate-tool canaries.

MCP remains last in the deployment order because it requires two independently healthy services,
protected authentication, and a network boundary in addition to the assistant runtime.

### 15.1 Verification evidence captured on 2026-08-04

Framework reactor verification passed with tests enabled for the changed dependency chain:

```bash
mvn -B --no-transfer-progress -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-actions-connector,ai-fabric-execution,ai-fabric-rag -am test
```

This passed the default curated prompts, Core, Chat Session, Execution, Actions Connector, Memory
Vector, and RAG modules. Notable totals include 682 Core tests and 51 RAG tests, with zero failures,
errors, or skipped tests.

The complete affected real-app reactor passed with tests enabled:

```bash
mvn -B --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl agentic-ai-action-resolver,deployment-knowledge-guard,behavior-churn-signals,incident-investigation-room,ai-fabric-live-data-sync,mcp-operations-assistant,mcp-operations-reference-server \
  -am test
```

The final per-app proof includes 139 Agentic Resolver tests, 44 Behavior Signals tests, 14 Live
Data Sync tests, 8 Incident Investigation tests, 9 MCP Assistant tests, 5 MCP Reference Server
tests, 4 Deployment Guard integration tests, and 13 shared smoke-support tests.

The unchanged public demo regression reactor also passed for Shopping, Account Resolver, Tenant
Guard, and Privacy Shield:

```bash
mvn -B --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl chat-capabilities-demo,ai-fabric-account-resolver,tenant-knowledge-portal,privacy-first-customer-facing-support \
  -am test
```

Packaged Docker images were built and booted for Agentic Resolver, Deployment Knowledge Guard,
Behavior Signals, Incident Investigation, Live Data Sync, MCP Operations Assistant, and the MCP
reference server. Their safe health contracts reported the expected AI Fabric version, specialist
IDs and hashes, plan IDs where applicable, provider readiness, storage posture, and no-fallback
policy. Source-candidate images were used where this change set modifies shared framework code;
Live Data Sync and Behavior Signals also proved their Maven Central `0.5.2` consumer builds.
The final Behavior Signals image ran with the smoke profile and reported `status=UP`, the exact
`behavior-risk-analyst@1` manifest hash, ready domain and execution storage, `DURABLE` execution,
`automaticWrites=false`, `liveFallbackEnabled=false`, and an honest
`realProviderSelected=false` local-provider posture. Separate health tests prove that enabling the
public `require-real-ai` gate while selecting the local provider reports the deployment unhealthy.

The Agentic Resolver image then passed the complete real OpenAI account-resolution and review
path. The specialist diagnosed missing payment and address state from current account evidence,
requested typed missing input, proposed confirmation-gated writes, executed a confirmed mutation
once, and projected the authoritative post-action account state. Receipt replay did not execute a
second write. A support-credit case created durable review work; reviewer approval executed through
the linked receipt, and receipt and review state survived application restart.

Deployment Knowledge Guard passed real OpenAI retrieval against two approved tenant/deployment
contexts with intentionally overlapping evidence titles. Northstar queries returned only
Northstar evidence, switching the server-approved context returned only Orbit evidence, and a
direct cross-deployment evidence read was rejected. Cross-tenant, cross-deployment, and spoofed
identity canaries failed grounding validation without exposing evidence; removing the specialist
vector scope returned a direct denial. This verification also caught and corrected a missing
application `EntityAccessPolicy`, ensuring the normal path and hostile canaries use the same strict
access-control boundary.

Incident Investigation Room passed real OpenAI sequential and bounded-parallel plans over the same
source revision. The plans produced equivalent typed assessments, while an injected required
branch failure produced no synthetic partial answer. Closed-catalogue delegation, one-level
handoff, bounded conversation-manager routing, and idempotent replay all passed.

The same image then passed a real OpenAI canary with the public provider gate enabled. Health
reported `generation=openai`, `realProviderRequired=true`, and `realProviderSelected=true`. The
first durable analysis projected a billing-cancellation account as `CHURNING`, churn risk `0.90`,
and `RAPIDLY_DECLINING`. After five new recovery events, the next invocation received that previous
insight plus only the five fresh events and projected `NEUTRAL`, churn risk `0.50`, and `IMPROVING`.
No customer offer executed automatically. Agentic UI generation reported
`source=llm`, model `gpt-4o-mini-2024-07-18`, one structured-output attempt, and only allowlisted
component types. Re-submission without new events failed safely with `ANALYSIS_NOT_READY` while the
original invocation remained readable by its opaque id.

The MCP path received a real OpenAI and authenticated Streamable HTTP canary, not only a mock run:

- remote status and incident reads used the exact configured server and tools;
- restart returned confirmation before any mutation;
- rejection performed no write;
- confirmation performed exactly one remote mutation;
- replay before and after assistant restart performed no second write;
- chat, receipt, and safe outcome survived restart on persistent H2 state;
- a duplicate-tool binding canary failed closed with `MCP_TOOL_NOT_AVAILABLE`;
- missing authentication and server outage remained explicit and never selected the local executor.

Live Data Sync passed a real OpenAI and OpenAI-embedding lifecycle canary in an exact-source Docker
image. A fresh workspace contained six source rows, six vectors, and six aligned revisions. Before
mutation, the LLM answered that NovaBook Air had an 18-hour battery from synchronized evidence.
After a tracked JPA update completed, revision 2 replaced the vector content and the LLM answered
26 hours. Deleting the opened-electronics policy reduced source and vector totals to five and
removed that record from both search and chat evidence. Controlled work reached `SUPERSEDED`; a
retry recovered the same work ID at retry count one; exhausted retries reached `DEAD_LETTER` after
three attempts with operator review required. All source, vector, update-receipt, retry-receipt, and
dead-letter state survived application restart. Five overlapping update/search cycles then ended
at revision 7 with aligned 31-hour evidence and no Lucene closed-reader exception.

The local portfolio run exposed and fixed real integration defects rather than merely confirming
the happy path:

- Lucene searches now hold ref-counted reader leases while concurrent writes refresh the shared
  searcher;
- trusted tenant and deployment identity is preserved at the RAG request boundary while spoofable
  caller metadata is removed;
- Deployment Knowledge Guard now registers a strict application access policy for normal reads;
  and
- its security lab includes an explicit missing-scope canary.

The public UI repository passed focused ESLint, its production build, course catalogue validation,
and all 73 Vitest tests. Desktop and mobile Playwright verification covered the MCP read,
confirmation, confirmed receipt, and binding-canary states without console errors or horizontal
overflow. The remaining browser work is the post-deployment live suite against hosted backends.

## 16. Completion Checklist

### 16.1 Source and local verification complete

- [x] Agentic AI Action Resolver remains separate from Account Resolver in backend and UI source.
- [x] Typed wait/resume, confirmation, receipt, replay, and cross-session denial have automated
  coverage.
- [x] Human Review Operations Desk implements the supported decisions and durable linked receipts.
- [x] Deployment Knowledge Guard proves the `0.5.2` tenant/deployment retrieval boundary locally.
- [x] Behavior Signals uses durable event execution without automatic writes or hidden fallback.
- [x] Incident Investigation proves fixed sequential/parallel composition and bounded routing.
- [x] Live Data Sync exposes the complete public indexing work lifecycle.
- [x] MCP Operations uses a real authenticated remote server and exact server/tool binding.
- [x] New public UI routes have About pages, guided scenarios, reset behavior, cleanup contracts,
  and versioned health.
- [x] Provider, MCP, validation, and retrieval failures remain visible in source and test profiles.
- [x] Existing Shopping, Account Resolver, Tenant Guard, and Privacy Shield regressions remain green.

### 16.2 Hosted release gate still required

- [ ] Publish the shared framework hardening as the next immutable patch after `0.5.2`.
- [ ] Deploy Agentic AI Action Resolver separately from Account Resolver.
- [ ] Deploy Deployment Knowledge Guard and Incident Investigation Room.
- [ ] Redeploy the upgraded Behavior Signals and Live Data Sync applications.
- [ ] Deploy MCP Operations Assistant and its authenticated reference server as two services.
- [ ] Deploy the public UI bundle with the new routes and backend URLs.
- [ ] Repeat typed wait/resume, review, retrieval-boundary, indexing, composition, and MCP canaries
  against hosted URLs with real provider configuration and no fallback.
- [ ] Run controlled hosted restart proof for every public durability claim, including review and
  queued behavior execution, and attach evidence to the release record.
- [ ] Add the hosted canary commands and protected dependency list to CI and release documentation.

## 17. Final Recommendation

Start with the existing Agentic AI Action Resolver backend and expose it through two focused UI
experiences: customer resolution and human review. It offers the fastest credible proof of the
largest `0.5.x` capability set.

Next build Deployment Knowledge Guard because it validates the security-sensitive `0.5.2` trusted
retrieval correction. Then upgrade Behavior Signals and Live Data Sync, build the focused incident
composition proof, and finish with a genuinely remote MCP deployment.

This sequence proves product value and framework correctness together while avoiding duplicate
backends, overloaded demo pages, and claims for functionality AI Fabric does not currently support.
