# Bounded Conversation Manager Implementation Plan

- **Status:** Implemented and verified; not released
- **Date:** 2026-07-29
- **Framework baseline:** AI Fabric `0.4.0`
- **Code reviewed at:** `83cda9a`
- **Prerequisite:** Plans `0001` through `0011`
- **Target:** Optional agentic-enablement capability; version not assigned
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## 1. Purpose

Implement the first optional conversation manager for genuinely ambiguous
interactive requests without creating an unrestricted agent graph.

The manager is one exact-version AI Fabric specialist. It receives one
backend-approved frozen conversation projection and may propose only:

```text
ASK_USER
INVOKE_SPECIALIST
COMPLETE
```

Deterministic Java code validates and applies the directive. The model cannot
create a target, transition, input mapping, Mode, action, authority, budget,
prompt, provider, or execution graph.

## 2. Why This Step Is Now Valid

Plan `0011` supplied the missing prerequisite:

- one authenticated backend-owned conversation;
- one frozen approved snapshot;
- one explicit dialogue-capable owner;
- one active process-local turn per conversation; and
- validated conversation persistence.

The current Account Resolver also has two exact-version, read-only capability
families already proven through delegation and handoff. The manager proof
registers conversation-isolated variants because its workers must neither own
history nor enter an input-wait state:

- `account-resolver-manager-read@1`; and
- `billing-resolution-manager-advisor@1`.

A manager proof can therefore measure whether model-assisted routing adds value
without inventing new business capabilities.

## 3. Code-Backed Starting Point

| Current code | Reuse or required change |
| --- | --- |
| `DefaultAIInteractiveExecutionGateway` freezes history and owns the active-turn map | Extract one internal turn coordinator shared by direct dialogue and manager execution. Do not create a second concurrency boundary. |
| `ApprovedConversationSnapshot` carries immutable messages, revision, turn ID, and owner | Reuse unchanged for the manager. Worker specialists receive no snapshot or conversation binding. |
| `SpecialistInteractionCapability.DIALOGUE_CAPABLE` marks an eligible owner | Keep the capability. Direct owners record their own validated output; manager specialists defer recording to the manager coordinator. |
| `SpecialistClientFactory` validates typed manifest bindings | Bind the manager to the framework directive DTO and every worker to its registered typed input/output. |
| `SpecialistDelegationGateway` independently authorizes one declared read-only target | Reuse its target invocation and lineage checks after the manager directive also passes the registered manager-plan allowlist. |
| `AIExecutionConversationRecorder` commits a validated turn | Commit exactly once after `ASK_USER`, `COMPLETE`, or successful worker projection. Never persist the internal directive as assistant prose. |
| `CanonicalJsonSupport` and existing process-local replay stores provide payload-checked idempotency patterns | Add a bounded manager-turn replay entry scoped to access, conversation, plan, and request payload. |
| Fixed `ExecutionPlanDefinition` describes deterministic sequential work | Do not overload it. The first manager plan has different semantics and remains a separate `SUPERVISED_BOUNDED` registry. |

## 4. Deliberate First Boundary

Included:

- one interactive request;
- one exact-version manager specialist;
- one frozen backend-owned conversation snapshot;
- one manager model call;
- one of three typed directives;
- zero or one independently authorized read-only worker invocation;
- a closed exact-version target set;
- application-registered typed target input mappers;
- application-registered safe worker-result projectors;
- one process-local active-turn lease;
- one validated external question or response;
- payload-checked process-local replay;
- visible manager, directive, worker, projection, persistence, and provider
  failures; and
- direct-versus-manager quality, latency, and model-call comparison.

Deferred:

- `CONTINUE_CURRENT`, `HANDOFF_TO`, `REQUEST_HUMAN_REVIEW`, and `ESCALATE`;
- manager loops or a second manager synthesis call;
- worker input waits and governed WRITE proposals;
- conditional or parallel branches;
- dynamic target discovery;
- manager-authored worker payloads;
- durable manager state or restart resume;
- distributed conversation leases;
- runtime plan hot reload; and
- unrestricted conversation summaries.

## 5. Public Contracts

Add:

- `ConversationManagerId`;
- `ConversationManagerDirectiveType`;
- `ConversationManagerDirective`;
- `ConversationManagerInput`;
- `ConversationManagerTargetView`;
- `ConversationManagerDefinition<I>`;
- `ConversationManagerInputAdapter<I>`;
- `ConversationManagerTarget<I, TI, TO>`;
- `ConversationManagerTargetInputMapper<I, TI>`;
- `ConversationManagerTargetResultProjector<I, TO>`;
- `ConversationManagerRegistry`;
- `ConversationManagerTurnRequest<I>`;
- `ConversationManagerTurnResult`;
- `ConversationManagerTurnStatus`;
- `ConversationManagerFailure`; and
- `ConversationManagerGateway`.

The directive contains only:

```text
type
targetSpecialist
message
reason
```

Rules:

- `ASK_USER` requires a bounded nonblank `message` and no target.
- `COMPLETE` requires a bounded nonblank `message` and no target.
- `INVOKE_SPECIALIST` requires one exact `name@version` target and no
  model-authored target input.
- Every directive requires a bounded reason for safe diagnostics.
- Unknown fields and invalid combinations fail structured-output validation.

## 6. Registered Manager Definition

One immutable application-owned definition declares:

- exact manager ID and version;
- exact manager specialist ID and version;
- typed public application input;
- one stable input-adapter component;
- a bounded list of exact worker targets;
- a description visible to the manager for each target;
- one typed input mapper and one safe result projector per worker;
- maximum duration.

Process-local replay retention is a bounded deployment property rather than a
model-authored or per-manager value.

Startup validation must prove:

1. the manager specialist exists;
2. it is `DIALOGUE_CAPABLE`;
3. it accepts a conversation but does not directly record a routing
   directive;
4. it has no READ/WRITE action authority and no input continuation;
5. its typed output is `ConversationManagerDirective`;
6. every target exists and is exact-version;
7. every target is read-only, non-interactive, and conversation-isolated;
8. every target also appears in the manager specialist's declared delegation
   policy;
9. every typed mapper and projector matches the target binding;
10. target descriptions and counts remain bounded; and
11. the complete definition has a deterministic content hash.

## 7. One-Turn Runtime Flow

```text
authenticated latest-message request
  -> resolve manager plan, trusted context, subject, tenant, and conversation
  -> check manager-turn replay fingerprint
  -> claim the shared active-turn lease
  -> freeze one authorized bounded conversation snapshot
  -> create manager input from latest message, safe app context, and closed targets
  -> invoke the exact manager through the normal AIExecutionGateway
  -> validate ASK_USER | INVOKE_SPECIALIST | COMPLETE
  -> ASK_USER:
       persist one validated question
  -> COMPLETE:
       persist one validated terminal response
  -> INVOKE_SPECIALIST:
       verify target against plan and manager declaration
       map worker input in registered application code
       invoke worker with no conversation
       independently resolve worker authority/capabilities
       project one safe user-facing result in registered application code
       persist that result through the manager-owned turn
  -> release snapshot token and active-turn lease
  -> retain bounded replay result
```

The manager and worker continue through the existing orchestration pipeline.
There is no direct provider call and no deterministic substitute for failed
intelligence.

The closed manager specialist uses
`OrchestrationIntentPolicy.STRUCTURED_OUTPUT_ONLY`. This policy preserves the
normal trusted-context, access-control, capability, validation, sanitization,
and structured-output stages while skipping the ordinary intent-extraction
and intent-handling model paths. One manager turn therefore performs exactly
one manager model stage, not an additional hidden intent call. A selected
worker still runs independently through its own normal specialist pipeline.

## 8. Conversation Recording Rule

Direct dialogue and manager dialogue use the same shared lease and snapshot
provider but differ in who commits output:

| Path | Specialist records itself? | Final recorder |
| --- | --- | --- |
| Direct dialogue owner | Yes | Existing execution gateway |
| Conversation manager | No | Manager gateway after directive/worker validation |
| Worker selected by manager | No conversation | Never |

`DIALOGUE_CAPABLE` therefore remains an eligibility capability. A direct
interactive specialist must have `recordValidatedTurns=true`. A registered
conversation manager must have `recordValidatedTurns=false`, because its
structured directive is internal coordination data.

The manager gateway records:

- the input adapter's safe latest user message;
- one nonblank validated external message;
- manager and optional worker invocation IDs;
- manager-plan ID and content hash;
- selected exact target when present;
- snapshot revision and interaction turn ID; and
- no transcript, prompt, token, identity, authority, or provider-native
  payload.

## 9. Idempotency And Concurrency

The manager turn key is scoped to:

- trusted execution access binding;
- conversation owner and conversation ID;
- exact manager-plan ID and content hash; and
- stable caller idempotency key.

The request fingerprint includes the validated application input and deadline
but excludes the snapshot revision. Exact retry returns the original result
without another manager call, worker call, or conversation append. Changed
input under the same scoped key returns `IDEMPOTENCY_CONFLICT`.

Direct interactive calls and manager calls must share one active-turn map so
they cannot append competing responses to the same conversation. Different
conversations may proceed independently.

## 10. Reference Application Proof

Add a separate endpoint to the copied Agentic AI Action Resolver:

```text
POST /api/agentic-resolver/manager/chat
X-AI-Fabric-Demo-Session: <server-created session>
Idempotency-Key: <stable request key>
```

The request contains only the current typed question and optional typed billing
facts already defined by the application. It contains no history, target,
specialist, Mode, action, provider, prompt, identity, authority, or snapshot.

Register:

```text
account-conversation-manager@1
  -> account-resolver-manager-read@1
  -> billing-resolution-manager-advisor@1
```

Required cases:

- account-readiness question invokes `account-resolver-manager-read@1`;
- complete billing facts invoke `billing-resolution-manager-advisor@1`;
- incomplete billing assessment returns one useful `ASK_USER` question;
- unsupported request returns `COMPLETE` without a worker;
- invented target is rejected without fallback;
- worker provider/grounding failure remains visible;
- replay and conflict behave correctly;
- the manager directive is absent from chat history;
- the successful worker result is present exactly once; and
- direct `/chat` behavior remains unchanged.

## 11. Evaluation Gate

The manager is optional and should not become the default merely because it
exists. Compare it with direct application selection and the existing
delegation route:

| Measure | Required evidence |
| --- | --- |
| Route correctness | Supported prompts select the intended closed target. |
| Clarification usefulness | Missing billing facts produce one relevant question. |
| Unsupported handling | Unsupported requests complete without target invocation. |
| Latency | Record manager and total elapsed milliseconds. |
| Model calls | Record one manager call plus zero or one worker call. |
| Added value | Ambiguous inputs improve over requiring the caller to select a route. |
| Safety | Invented targets, authority gaps, and capability widening fail closed. |

No fallback may convert a failed manager call into deterministic routing. The
comparison may recommend direct routing for clear product paths.

## 12. Test Matrix

### Contracts and registry

- directive invariant tests for all valid and invalid combinations;
- immutable, bounded input and target views;
- duplicate manager IDs and duplicate targets;
- unknown manager or worker;
- manager is not dialogue-capable;
- manager incorrectly records directives;
- manager has actions or input continuation;
- worker is WRITE-capable, dialogue-capable, or conversation-recording;
- target missing from manager delegation allowlist;
- typed manager, mapper, worker, and projector mismatch; and
- deterministic content hash changes for every semantic field.

### Shared turn coordinator

- direct and manager paths contend on one conversation;
- independent conversations proceed;
- snapshot owner and conversation mismatch fail;
- token absence, expiry, and reuse fail;
- claims/tokens release on success, failure, interruption, and exception; and
- existing direct interactive diagnostics and behavior remain unchanged.

### Manager gateway

- `ASK_USER`, `COMPLETE`, and successful worker invocation;
- unapproved and malformed directive rejection;
- independent worker authorization and effective profile;
- manager and worker provider failures remain visible;
- target mapper and result projector failures remain visible;
- exactly one validated append;
- no append on failure;
- exact replay and changed-payload conflict;
- deadline, cancellation/interruption, and conversation busy;
- no transcript/token leakage in result or diagnostics; and
- bounded replay cleanup.

### Reference application

- latest-message-only controller contract;
- server-owned session, subject, and conversation;
- manager manifest strict schema;
- exact two-target registration;
- all four required route outcomes;
- direct route regression; and
- packaged real OpenAI route, clarification, completion, replay, and conflict.

## 13. Verification Gate

The plan is complete only when:

1. core, chat-session, and execution tests pass normally;
2. all Agentic Resolver tests pass normally;
3. a clean packaged app loads the strict manager manifest and definition;
4. packaged framework JARs match the locally verified artifacts;
5. real OpenAI proves both worker routes, one clarification, and one terminal
   completion;
6. replay, conflict, shared-turn exclusion, and exactly-one-recording are
   proven;
7. direct dialogue remains unchanged;
8. no placeholder, disabled test, hidden fallback, second chat store, or
   parallel provider path exists;
9. evaluation records added latency/model calls rather than hiding them; and
10. `git diff --check` passes.

## 14. Recorded Implementation Evidence

The implementation completed the gate on 2026-07-30:

- the normal framework execution reactor passed 1,044 tests with no failures
  or skips: 5 curated-default, 677 core, 59 chat-session, and 303 execution
  tests;
- the clean real-app package passed 12 shared smoke tests and 135 Agentic AI
  Action Resolver tests with no failures or skips;
- focused coverage proves all directive invariants, startup validation,
  shared-turn exclusion, release on failure, exact replay, changed-payload
  conflict, fail-closed replay-capacity enforcement, one validated append,
  independent worker authorization, and safe failure projection;
- the packaged core JAR and verified local artifact shared SHA-256
  `b2543edcc887209513060e4ec0d4246fcfa2ecc524296bc4e79dd319ffd0c9a4`;
- the packaged execution JAR and verified local artifact shared SHA-256
  `82271608dc71598365a2c8b56d5e8fa3697d58bf965b3d2fc8bf31067d47d6a1`;
- packaged health loaded nine strict manifest specialists, the exact
  two-target manager definition, manager content hash
  `ba12bfe8c82d2dedd588960f9ec5790b13bae04da28bb2a6a6b7a6c982a62a8f`,
  and manifest registry hash
  `be25241d4f8b85aa66a9bcdeea04ff09a3ea78808b73f062efca488706c47742`;
- real OpenAI selected `account-resolver-manager-read@1` for account
  readiness and `billing-resolution-manager-advisor@1` for a complete
  account-credit assessment;
- incomplete billing facts returned focused `ASK_USER` questions, while an
  unsupported poem returned `COMPLETE` without a worker;
- a first account-readiness route took 9.91 seconds wall time for one manager
  plus one worker invocation, a billing route took 9.39 seconds for the same
  two-stage shape, and manager-only clarification/completion took 1.42 and
  1.51 seconds respectively;
- a follow-up manager turn consumed one previously committed backend-owned
  turn and still selected the narrow account-read worker;
- exact replay preserved the manager and worker invocation IDs and did not
  append again; changed input under the retained key returned
  `MANAGER_IDEMPOTENCY_CONFLICT`; and
- the application worker mapper was hardened after live testing so ambiguous
  follow-up prose cannot widen the selected account worker's registered,
  current-account readiness task.

There is no disabled test, fallback route, second conversation store, direct
provider client, manager-authored worker payload, or manager WRITE path.

## 15. Delivery Slices

1. [x] Add contracts, registry, startup validation, and deterministic tests.
2. [x] Extract the shared interactive turn coordinator without behavioral
   change to direct dialogue.
3. [x] Add the bounded manager gateway and one-recording behavior.
4. [x] Add the Account Resolver manager specialist, target mappings,
   endpoint, health diagnostics, and deterministic acceptance tests.
5. [x] Run the full normal-test gate, clean package parity, and real OpenAI
   comparison.
6. [ ] Commit the implementation, then pin its exact commit into this plan and
   the Loom AI release-candidate notes.

## 16. Explicit Next Boundary

After real usage evidence, a later plan may consider one second manager turn
for synthesis or controlled `HANDOFF_TO`. It must not add loops, review,
parallelism, or writes merely because the directive vocabulary can be
expanded.
