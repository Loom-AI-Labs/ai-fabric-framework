# Durable Read-Only Specialist Job Implementation Plan

- **Status:** Implemented and verified; not released
- **Date:** 2026-07-29
- **Framework baseline:** AI Fabric `0.4.0`
- **Code reviewed at:** `410c117`
- **Prerequisite:** Plans `0001` through `0006`
- **Target:** P3 release slice 5; version not assigned
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## 1. Purpose

Make a bounded machine-triggered specialist analysis survive process restart
without turning AI Fabric into an unrestricted workflow engine.

Plan `0006` proved an application event can invoke a typed, read-only
specialist through `AIExecutionGateway`, but the execution, result, and replay
binding are process-local. This slice adds an optional JDBC execution-state
adapter that:

1. persists a validated request before dispatch;
2. leases work to one bounded worker;
3. safely reclaims abandoned work after restart;
4. preserves scoped idempotency and typed status/result access;
5. encrypts request and result payloads at rest; and
6. retains the existing ephemeral path as the default.

The first durable contract is intentionally limited to terminal, read-only
machine work. Durable user-input waits, action proposals, and human review are
separate state machines and are not smuggled into this table.

## 2. Code-Backed Starting Point

| Current code | Consequence |
| --- | --- |
| `DefaultAIExecutionGateway.submit(...)` persists to `EphemeralExecutionStore` before queueing | Preserve persist-before-dispatch ordering, but make the store selectable. |
| Plan `0006` scopes idempotency to `ExecutionAccessBinding` and fingerprints specialist/input/conversation | Keep those semantics across restart. |
| `SpecialistRegistry` exposes exact specialist content hashes and adapter input/output classes | Pin the definition and decode typed request/result payloads without class names from public input. |
| `SpecialistClient` already provides typed submit/find/cancel | Do not add a second application API. |
| `ExecutionDurability` currently contains only `EPHEMERAL` | Add an explicit `DURABLE` value only for the JDBC path. |
| JDBC action receipts already use protected payloads, keyed fingerprints, optimistic transitions, and application-owned schema migration | Reuse the security and operational pattern, not the action-receipt table or state machine. |
| Action receipt recovery marks uncertain writes for reconciliation rather than replaying them | Restrict this first durable worker to read-only specialists; do not blindly recover writes. |
| Input waits and fixed-plan checkpoints are explicitly ephemeral | Reject those outcomes in durable-job V1 rather than claiming restart-safe continuation. |

## 3. Included Scope

### Framework

- `ExecutionDurability.DURABLE`;
- `ai.execution.async.repository=IN_MEMORY|JDBC`;
- a storage-neutral durable execution repository contract;
- JDBC implementation with optimistic version transitions;
- encrypted request and result envelopes;
- keyed access and idempotency fingerprints without raw identity columns;
- exact specialist ID and content-hash pinning;
- persist-before-dispatch;
- worker ownership, lease expiry, attempt count, and bounded recovery;
- startup and scheduled recovery;
- durable status and cancellation under the original trusted access binding;
- payload-checked replay after restart;
- result retention and optional bounded cleanup;
- visible serialization, schema-change, lease, retry-exhaustion, deadline,
  provider, and persistence failures; and
- the current in-memory behavior as the backward-compatible default.

### Supported V1 durable jobs

A durable submission must:

- use `ExecutionSource.APPLICATION`, `EVENT`, or `SCHEDULED`;
- use a `SERVICE` or `SYSTEM` initiator;
- include an application-owned subject;
- have no conversation binding;
- select an exact registered specialist whose write policy is disabled; and
- complete as success or visible failure without requesting user input or
  active-user confirmation.

The model cannot select durability, storage, retry policy, worker identity,
specialist identity, tenant, subject, scopes, or provider credentials.

### Reference application

- configure the Agentic AI Action Resolver proactive payment event for JDBC
  durable execution;
- expose `DURABLE` in health and event handles;
- prove event replay and status after application restart;
- prove changed payload conflict after restart;
- prove another demo session cannot read the result;
- prove abandoned queued/running read work is recovered once; and
- prove no account mutation or chat conversation is created.

## 4. Excluded Scope

- durable `WAITING_FOR_INPUT`;
- durable fixed-plan checkpoints;
- durable confirmation or WRITE-capable specialist jobs;
- human-review tasks and reviewer decisions;
- automatic business mutation;
- broker, outbox, webhook, or scheduler ownership;
- arbitrary retries of terminal provider failures;
- parallel workers executing one lease concurrently;
- model-selected specialist routing;
- raw identity or unencrypted model payloads in JDBC;
- cross-region consensus;
- exactly-once model invocation; and
- exactly-once business effects.

After a process stops during a provider call, the lease may expire and the
read-only analysis may run again. The stable framework invocation ID,
idempotency binding, attempt count, and final result remain singular.

## 5. Configuration

Default behavior remains process-local:

```yaml
ai:
  execution:
    async:
      repository: IN_MEMORY
```

Enable durable read-only jobs:

```yaml
ai:
  execution:
    async:
      repository: JDBC
      initialize-schema: false
      lease-duration: PT2M
      recovery-interval: PT30S
      recovery-batch-size: 50
      max-attempts: 3
      cleanup-enabled: true
      retention: P30D
      encryption-secret: ${AI_EXECUTION_ASYNC_ENCRYPTION_SECRET}
      fingerprint-secret: ${AI_EXECUTION_ASYNC_FINGERPRINT_SECRET}
```

Production applications own the datasource and Flyway/Liquibase migration.
`initialize-schema=true` is a local/demo/test convenience.

The encryption and fingerprint secrets must:

- each contain at least 32 characters;
- be different from each other;
- remain stable across restarts and replicas; and
- never be committed or logged.

## 6. Durable Record

The JDBC record stores:

```text
invocation ID
exact specialist name/version/content hash
access fingerprint
scoped idempotency fingerprint
canonical request fingerprint
encrypted request envelope
encrypted typed result envelope
status and safe failure reason
deadline and retention expiry
worker lease owner/expiry
attempt count
created/updated/completed timestamps
optimistic version
```

The protected request envelope contains only the server-constructed execution
request needed to recover:

```text
typed input JSON
trusted initiator/subject/source/tenant/deployment/scopes
correlation and authentication time
deadline
raw idempotency key
```

Conversation data is absent because V1 durable jobs reject conversation
binding.

## 7. Lifecycle

```text
submit
  -> validate durable-job policy
  -> encode and protect request
  -> insert QUEUED with scoped idempotency
  -> dispatch bounded worker
  -> atomically claim lease and increment attempt
  -> verify exact specialist content hash
  -> decode typed input
  -> execute existing AI Fabric specialist pipeline
  -> protect and persist typed terminal result
  -> expose typed result through SpecialistClient.find
```

Recovery:

```text
startup/schedule
  -> find QUEUED or lease-expired RUNNING rows
  -> expire rows past deadline
  -> fail rows that exhausted max attempts
  -> dispatch remaining candidates
  -> one worker wins the optimistic lease
```

The worker does not retry a completed provider failure. It only reclaims work
whose process stopped before a terminal result was committed.

## 8. State Transitions

Allowed transitions:

```text
QUEUED -> RUNNING
QUEUED -> CANCELLED | FAILED | EXPIRED
RUNNING -> SUCCEEDED | FAILED | CANCELLED
RUNNING (expired lease) -> RUNNING with next attempt
RUNNING -> FAILED when attempts are exhausted
terminal -> retained -> deleted by policy
```

`WAITING_FOR_INPUT` and `CONFIRMATION_REQUIRED` outcomes become explicit
`FAILED` durable-job results with a dedicated unsupported-boundary reason.
They are not silently treated as terminal success.

## 9. Tests

### Repository and codec

- protected request/result round trip for manifest and Java specialists;
- protected columns do not contain input, subject, tenant, output, or evidence
  text;
- tampering or wrong encryption binding fails visibly;
- exact specialist content hash survives;
- insert and scoped idempotency uniqueness;
- same key under another access binding is independent;
- optimistic claim allows one worker;
- lease expiry permits one reclaim;
- completion requires lease ownership and expected version;
- cancellation is access-bound;
- deadline and max-attempt terminalization;
- retention query and compare-and-delete; and
- schema initialization plus restart reopening.

### Gateway and worker

- default in-memory path remains `EPHEMERAL`;
- JDBC path returns `DURABLE`;
- invalid durable source/principal/subject/conversation/write policy is
  rejected before persistence;
- identical replay returns one invocation before and after restart;
- changed payload returns `IDEMPOTENCY_CONFLICT`;
- exact content-hash change fails closed;
- typed result survives restart;
- cross-context find/cancel is denied;
- queue saturation leaves durable work recoverable;
- abandoned running work is recovered;
- terminal provider failure is not auto-retried; and
- input/confirmation outcomes fail visibly as unsupported in V1.

### Reference application

- packaged health reports JDBC durable execution;
- event submit/status/cancel remain typed;
- restart preserves successful result;
- restart preserves replay and conflict;
- cross-session lookup remains `404`;
- no domain mutation;
- no chat session; and
- real OpenAI produces one typed durable recommendation.

## 10. Acceptance Gate

This slice is complete only when:

1. the existing ephemeral path and tests remain green;
2. JDBC state is committed before task dispatch;
3. request and result payloads are encrypted at rest;
4. raw identity and idempotency values are not query columns;
5. exact specialist content is pinned and rechecked;
6. one active lease owns execution;
7. queued and abandoned read-only work recover after restart;
8. identical replay returns the same invocation across restart;
9. changed payload conflicts across restart;
10. status and cancellation remain access-bound;
11. unsupported waits/writes fail visibly;
12. packaged restart proof passes with tests enabled;
13. real OpenAI returns a typed durable result; and
14. docs make at-least-once read execution and all exclusions explicit.

## 11. Implementation Evidence

Implemented framework components:

- `ExecutionDurability.DURABLE` and selectable
  `ai.execution.async.repository=IN_MEMORY|JDBC`;
- `DurableExecutionRepository` and `JdbcDurableExecutionRepository`;
- `DurableExecutionPayloadCodec` with authenticated encryption and
  binding-aware decode;
- `DurableExecutionSecurity` for keyed access, idempotency, and payload
  fingerprints;
- `DurableExecutionSubmissionPolicy` for the read-only machine-job boundary;
- `DurableAIExecutionGateway` for persist-before-dispatch, leased execution,
  status/cancel authorization, replay, expiry, and recovery;
- `AIExecutionJdbcStateAutoConfiguration` for datasource-backed selection,
  secret validation, startup recovery, scheduled recovery, and cleanup; and
- `AssignedExecutionRunner` so recovery invokes the established specialist
  pipeline with the persisted invocation ID.

Reference application evidence:

- the proactive payment-verification event uses JDBC durable execution;
- health reports `DURABLE` and durable-state readiness;
- demo and production configuration require independent stable async
  encryption and fingerprint secrets; and
- the existing input-wait, fixed-plan, chat-session, and action-receipt state
  machines remain separate.

Automated verification, with tests enabled:

```text
mvn -f ai-infrastructure-module/pom.xml \
  -pl :ai-fabric-execution -am install

924 tests passed:
  ai-fabric-curated-prompts  5
  ai-fabric-core           671
  ai-fabric-chat            56
  ai-fabric-execution      192

mvn -f examples/real-apps/pom.xml \
  -pl :agentic-ai-action-resolver -am package

118 tests passed:
  real-app-smoke-support             12
  agentic-ai-action-resolver        106
```

The framework tests cover encrypted-at-rest payloads, authenticated binding,
wrong-key/tamper rejection, lease-owner compare-and-set, restart replay,
payload conflicts, access isolation, cancellation, content-hash drift,
unsupported continuation outcomes, deadlines, attempt exhaustion, and
terminal provider failure behavior.

Packaged real-provider verification used the built application JAR, a
file-backed H2 database, stable secrets, and OpenAI:

1. submitted a service-owned payment-verification event;
2. observed a typed grounded `SUCCEEDED` result from
   `account-resolver-read@1`;
3. stopped and restarted the packaged application against the same database;
4. retrieved the same successful result after restart;
5. replayed identical event facts and received the same invocation;
6. changed facts under the same event ID and received
   `IDEMPOTENCY_CONFLICT`; and
7. proved another session received `404` for that invocation.

No framework fallback fabricated a result, no account state was mutated, no
chat conversation was created, and no credential was logged or committed.
All acceptance-gate items above passed.

## 12. Next Plan

After durable terminal jobs are approved, implement durable human review as a
separate plan:

- versioned review policy profiles;
- persisted review tasks before dispatch;
- dispatcher delivery receipts separate from decisions;
- trusted reviewer context and application-owned authorization;
- approve, reject, correct, request-information, escalate, and expire;
- current authority and evidence-freshness revalidation;
- governed action invocation only after approval;
- duplicate-safe decision, resume, and action receipts; and
- unknown-outcome reconciliation without blind write replay.
