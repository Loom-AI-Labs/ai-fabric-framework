# Proactive Event Specialist Execution Implementation Plan

- **Status:** Implemented and verified; not released
- **Date:** 2026-07-29
- **Framework baseline:** AI Fabric `0.4.0`
- **Code reviewed at:** `bf850f3`
- **Prerequisite:** Plans `0001` through `0005`
- **Target:** P3 release slice 4; version not assigned
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## 1. Purpose

Prove that AI Fabric intelligence can start from a real application event
without inventing a user message or chat conversation.

The host application will receive a raw payment-verification failure event,
map it deterministically to the existing exact-version read specialist, submit
it asynchronously under a service principal and `ExecutionSource.EVENT`, and
expose a typed recommendation through a context-protected status endpoint.

This slice also closes two framework gaps discovered while tracing the current
runtime:

1. `SpecialistClient` supports typed synchronous execute/resume but not typed
   asynchronous submit/status/cancel; and
2. `AIExecutionGateway.submit(...)` prevents duplicate model calls but returns
   only a new rejected handle for a duplicate idempotency key, so an
   at-least-once event consumer cannot recover the original execution.

The result is bounded proactive intelligence, not durable workflow execution.

## 2. Code-Backed Starting Point

| Current code | Consequence |
| --- | --- |
| `ExecutionSource` already includes `EVENT` and `SCHEDULED` | Do not add a second trigger vocabulary. |
| `TrustedExecutionContext` rejects end-user principals for non-interactive sources | The event adapter must construct a service principal from backend state. |
| `AIExecutionGateway.submit(...)` uses a bounded executor and `EphemeralExecutionStore` | Reuse it; keep durability explicitly `EPHEMERAL`. |
| `AIExecutionGateway.find/cancel` require the original trusted context | Typed client status and cancellation must preserve this boundary. |
| `SpecialistClientFactory` already converts application DTOs to/from manifest schemas | Typed asynchronous access belongs in this client rather than leaking `JsonNode` to applications. |
| `EphemeralExecutionStore` indexes raw idempotency keys globally and rejects duplicates | Scope keys to `ExecutionAccessBinding`, pin a canonical request fingerprint, replay identical submissions, and reject conflicting payloads. |
| `account-resolver-read@1` is READ-only and returns `AccountResolutionResult` | Use it for the event proof; no new specialist or WRITE is needed. |
| Agentic Resolver demo sessions bind opaque browser sessions to server-created subjects | The public demo adapter can select the subject without accepting a user/account ID in the event body. |

## 3. Included Scope

### Framework

- typed `SpecialistClient.submit(...)`;
- typed `SpecialistClient.find(...)`;
- context-protected `SpecialistClient.cancel(...)`;
- public `SpecialistExecutionSnapshot<O>`;
- manifest and native specialist output conversion for asynchronous results;
- idempotency keys scoped to the complete execution access binding;
- canonical submission fingerprint over exact specialist, typed input, and
  optional conversation binding;
- identical duplicate submission returns the original current handle;
- same scoped key with changed specialist/input/conversation fails visibly as
  `IDEMPOTENCY_CONFLICT`;
- same text key in a different tenant/subject/principal/source/deployment has
  an independent namespace;
- retention and replay remain bounded by the existing result TTL; and
- tests preserving queue, cancellation, expiry, context, and type behavior.

### Reference application

- raw `PaymentVerificationFailedEvent` input with:
  - event ID;
  - provider-safe failure code;
  - attempt number; and
  - occurrence time;
- subject selection from the opaque server-owned demo session;
- deterministic event-to-specialist input mapping;
- service principal `agentic-account-resolver-event-consumer`;
- `ExecutionSource.EVENT`;
- no conversation binding;
- stable framework idempotency key derived from event type/version/event ID;
- asynchronous submission, typed status lookup, and cancellation endpoints;
- read-only account/policy analysis;
- visible provider, queue, validation, and execution failures; and
- health/readiness and README documentation.

## 4. Excluded Scope

- JDBC/JPA execution storage;
- restart recovery;
- worker leasing, retries, dispatch queues, or event brokers;
- durable review tasks or reviewer decisions;
- automatic account mutation;
- action confirmation or WRITE-capable event specialists;
- user/account/tenant/authority fields in public event JSON;
- model-selected specialist routing;
- scheduled execution;
- general event schema registry;
- outbox ownership; and
- claims of exactly-once processing.

Those belong to the P3 durable execution and review plan.

## 5. Typed Asynchronous Client Contract

Extend `SpecialistClient<I, O>`:

```java
ExecutionHandle submit(SpecialistInvocation<I> invocation);

Optional<SpecialistExecutionSnapshot<O>> find(
    String invocationId,
    TrustedExecutionContext trustedExecutionContext
);

boolean cancel(
    String invocationId,
    TrustedExecutionContext trustedExecutionContext
);
```

`SpecialistExecutionSnapshot<O>` contains the existing `ExecutionHandle` and
an optional typed `AIExecutionResult<O>`. Queued/running entries have no
result. Terminal and waiting entries retain the canonical AI Fabric result.

The bound client owns:

- input conversion to the specialist's registered native/schema type;
- output conversion back to the application DTO;
- exact specialist identity; and
- delegation to the existing gateway.

It does not weaken trusted-context checks or expose raw manifest trees.

## 6. Idempotent Submission Semantics

For a non-null idempotency key, the ephemeral store indexes:

```text
ExecutionAccessBinding + normalized idempotency key
```

The entry pins a canonical fingerprint of:

```text
exact specialist ID + adapted input + optional conversation binding
```

The deadline is not fingerprinted because a legitimate redelivery may be
received later with a newly calculated deadline. The trusted access binding is
already part of the key namespace.

Outcomes:

| Condition | Result |
| --- | --- |
| No existing scoped key | Create and submit one execution |
| Same scoped key and same fingerprint | Return the original handle in its current state |
| Same scoped key and different fingerprint | Return `REJECTED` with `IDEMPOTENCY_CONFLICT` |
| Same text key under another access binding | Create an independent execution |
| Original terminal result expired | Key expires with it; later submission is new |

Replay must not invoke the pipeline again. Conflict responses must not reveal
the original invocation ID across an invalid access boundary.

## 7. Reference Event Flow

```text
demo/payment event adapter
  -> resolve opaque demo session to current subject
  -> validate raw event
  -> deterministic map to AccountResolutionRequest
  -> construct service-owned TrustedExecutionContext(EVENT)
  -> typed readClient.submit(...)
  -> bounded AI Fabric execution worker
  -> account profile READ + scoped policy RAG + OpenAI
  -> typed AccountResolutionResult
  -> typed context-protected status endpoint
```

The event body cannot select a specialist, prompt profile, Mode, action,
vector space, subject, tenant, principal, scopes, conversation, or provider.

Suggested endpoints:

```text
POST   /api/agentic-resolver/events/payment-verification-failed
GET    /api/agentic-resolver/events/executions/{invocationId}
DELETE /api/agentic-resolver/events/executions/{invocationId}
```

`POST` may return queued, running, or already completed depending on executor
timing. A duplicate identical event returns the same invocation ID. A changed
payload under the same event ID returns a visible conflict.

## 8. Tests

### Framework

- typed schema-bound submit converts input;
- typed find converts successful output;
- queued/running typed snapshots allow null result;
- native specialist output retains exact type;
- wrong-context find is empty and cancellation is false;
- identical duplicate submission returns the same invocation ID;
- duplicate replay invokes the pipeline once;
- changed payload under one key returns `IDEMPOTENCY_CONFLICT`;
- same text key under another access binding is independent;
- idempotency expires with the terminal result;
- queue rejection remains visible; and
- existing synchronous, resume, input-wait, action, and plan tests remain green.

### Reference application

- event validation;
- no subject/tenant/authority fields in public event DTO;
- deterministic mapping;
- EVENT source and SERVICE principal;
- no conversation binding;
- expected READ scopes only;
- stable event-derived idempotency;
- typed completed recommendation;
- duplicate event replay;
- changed event conflict;
- cross-session status denial;
- cancellation;
- no domain mutation;
- packaged health/readiness; and
- real OpenAI event recommendation.

## 9. Acceptance Gate

This slice is complete only when:

1. applications can submit and read schema-bound specialists without
   `JsonNode`;
2. event execution uses a real service principal and `EVENT` source;
3. the public event body cannot supply trusted identity or capability;
4. identical event delivery never invokes the specialist twice while retained;
5. a changed event under the same ID fails visibly;
6. status and cancellation are access-bound;
7. no chat session or fake user message is created;
8. the specialist remains read-only and performs no business mutation;
9. all deterministic framework and app tests pass with tests enabled;
10. the packaged app exposes the proactive capability in health;
11. real OpenAI proves a typed event recommendation; and
12. documentation states the process-local durability limitation clearly.

All twelve acceptance conditions passed on the implementation candidate.

## 10. Verification Evidence

### Framework

- `mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-execution -am install`
  passed with tests enabled, including 174 `ai-fabric-execution` tests.
- The complete infrastructure reactor was covered with tests enabled. The
  first 32 modules passed before the integration module reported its documented
  local ONNX asset prerequisite. The integration module and the three
  remaining modules then passed with:

```bash
ONNX_MODEL_PATH="$PWD/ai-infrastructure-module/models/embeddings/all-MiniLM-L6-v2.onnx" \
ONNX_TOKENIZER_PATH="$PWD/ai-infrastructure-module/models/embeddings/tokenizer.json" \
mvn -f ai-infrastructure-module/pom.xml install \
  -rf :ai-fabric-integration-tests
```

- The resumed integration reactor passed all four modules:
  `ai-fabric-integration-tests`,
  `relationship-query-integration-tests`,
  `chat-session-integration-tests`, and
  `behavior-integration-tests`.
- No tests were skipped through Maven flags.

### Reference application

- `mvn -f examples/real-apps/pom.xml
  -pl agentic-ai-action-resolver -am package` passed with tests enabled.
- The packaged build passed 12 shared smoke tests and 106 application tests.
- Deterministic Spring integration proved:
  - identical redelivery returns one invocation;
  - changed facts under the same event ID return
    `IDEMPOTENCY_CONFLICT`;
  - another demo session cannot discover the execution;
  - a disabled provider remains a visible failure; and
  - the account state is unchanged.

### Real OpenAI

The packaged boot JAR was run with the private OpenAI credential supplied
outside source control. A fresh payment-verification failure produced:

- a typed `BLOCKED` account assessment;
- one `VERIFIED_PAYMENT_METHOD` blocker;
- four safe evidence references;
- the same successful invocation for exact redelivery;
- `IDEMPOTENCY_CONFLICT` for changed facts under the same event ID;
- `404` for cross-session status lookup; and
- no account mutation.

Health reported OpenAI ready and the event execution boundary as
`EVENT` / `SERVICE` / `EPHEMERAL` with automatic mutation disabled. The
packaged process was stopped cleanly after verification.

## 11. Next Plan

After this proof, implement P3 durable execution and human review:

- execution-state SPI;
- JDBC adapter;
- persist-before-dispatch;
- worker lease/retry/recovery;
- durable typed outcome sink;
- tenant-scoped review task and decision gateway;
- current authority and evidence freshness checks;
- duplicate-safe decision/action receipt; and
- restart and unknown-outcome reconciliation proofs.
