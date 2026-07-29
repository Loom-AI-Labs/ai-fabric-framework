# Durable Human Review Implementation Plan

- **Status:** Implemented and verified; not released
- **Date:** 2026-07-29
- **Framework baseline:** AI Fabric `0.4.0`
- **Code reviewed at:** `d2b4ac0`
- **Prerequisite:** Plans `0001` through `0007`
- **Target:** P3 release slice 6; version not assigned
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## 1. Purpose

Add a durable, channel-neutral human-review boundary for work that crosses an
HTTP request, process, actor, or time boundary.

The first proof is an operational review of an AI-proposed, low-risk account
resolution. The proposal remains governed by the existing action receipt. A
review task is persisted before notification, an application-authenticated
reviewer decides it, and approval can advance the linked action only through
the existing `ActionProposalCoordinator`.

This is not chat confirmation:

| Immediate confirmation | Durable review |
| --- | --- |
| Active user confirms their own current proposal | A separately authorized reviewer decides later |
| Conversation/request lifecycle | Cross-request and cross-process lifecycle |
| Uses the proposal receipt directly | Adds a version-bound review task around the receipt |
| `CONFIRM` or `REJECT` | Policy-approved review decisions |
| No review dispatcher | Persist-before-dispatch with a separate delivery receipt |

The LLM may propose and explain. It cannot select the reviewer, policy,
dispatcher, recipient, decision, or authority.

## 2. Code-Backed Starting Point

| Current code | Plan consequence |
| --- | --- |
| `ActionProposalCoordinator` already pins specialist content, effective profile, action schema, parameters, evidence hashes, identity, expiry, and idempotency | Review must reference this receipt rather than copying executable parameters. |
| `ActionProposalCoordinator.decide(...)` re-resolves current capabilities, action metadata, authority, preflight, and outcome projection | Approval must delegate to this path instead of invoking a handler directly. |
| `JdbcActionProposalReceiptRepository` protects executable parameters and uses optimistic transitions | Review gets its own table and state machine; it must not add review columns to action receipts. |
| Plan `0007` adds encrypted JDBC records, keyed access fingerprints, leasing, restart recovery, and retention | Reuse the security and persistence pattern, not the durable read-job table. |
| `TrustedExecutionContext` is backend-owned and excludes reviewer semantics | Add a separate `TrustedReviewerContext`; do not reinterpret the original initiator as the reviewer. |
| The Agentic Resolver already has a billing-policy advisor, governed billing action, and safe action outcome projection | Use a low-risk support credit as the first realistic review proof. |
| Current refund records may become `PENDING_REVIEW`, but that is application domain status | Do not pretend this existing domain flag is the framework review lifecycle. |

## 3. Included Scope

### Framework contracts

- immutable `ReviewPolicyId` and `ReviewPolicyDefinition`;
- `ReviewType` excluding immediate chat confirmation;
- `ReviewDecisionType` for `APPROVE`, `REJECT`, `CORRECT`,
  `REQUEST_INFORMATION`, and `ESCALATE`;
- `ReviewPolicyRegistry` with immutable exact-version definitions and content
  hashes;
- backend-owned `TrustedReviewerContext`;
- application-owned `ReviewerAuthorizer`;
- application-owned registered `ReviewTaskDispatcher`;
- framework-owned `ReviewDecisionGateway`;
- safe `ReviewTaskView`, `ReviewDecisionRequest`, and
  `ReviewDecisionResult`;
- JDBC review-task and dispatch-receipt repositories;
- authenticated encrypted task/decision payloads;
- keyed initiator, subject, tenant, deployment, reviewer, and idempotency
  fingerprints;
- task persistence before dispatch;
- duplicate-safe dispatch and decision;
- optimistic task ownership for decision continuation;
- startup and scheduled recovery;
- expiry, escalation, retention, and optional cleanup;
- exact policy and action-receipt binding, including pinned specialist,
  effective-profile, action-schema, parameter, and evidence hashes;
- current reviewer authorization and action revalidation at decision time;
  and
- governed action execution only through the existing action receipt.

### First supported review source

V1 creates an `OPERATIONAL_REVIEW` task around one existing
`ActionProposalReceipt` in `PROPOSED` state.

The review task stores an encrypted reference to the receipt and the original
trusted execution context. It never duplicates or publicly exposes executable
action parameters.

### Decision behavior

- `APPROVE`: claim the decision durably, revalidate policy/reviewer/source,
  then call the linked action receipt with `CONFIRM`.
- `REJECT`: claim durably, call the linked action receipt with `REJECT`, and
  retain the terminal review decision.
- `CORRECT`: validate a policy-bound typed correction and invoke a registered
  application correction handler. The handler must create a successor
  proposal or safe corrected result; it may not rewrite the original receipt.
- `REQUEST_INFORMATION`: create a typed, bounded review-information request
  and keep the task non-terminal until authorized information is supplied.
- `ESCALATE`: create one deterministic successor task under a registered
  higher-authority policy and close the original as escalated.
- expiry: close an untouched task without changing or executing its action
  receipt.

Each optional decision is rejected at policy registration unless its required
schema, continuation handler, or escalation policy is registered.

### Reference application

The Agentic AI Action Resolver will add:

- a support-credit recommendation that remains an AI proposal;
- one application-selected `support-credit-review@1` policy;
- a local review-inbox dispatcher for the demo UI/API;
- an application reviewer authorizer with tenant, role/scope, and
  separation-of-duty checks;
- safe reviewer inbox, task-detail, decide, information, and reset endpoints;
- approval that executes only the linked governed billing action;
- rejection with no domain mutation;
- correction that creates a revised proposal rather than rewriting history;
- information request and bounded response;
- escalation to `support-credit-senior-review@1`;
- restart-safe tasks, decisions, dispatch receipts, and action outcomes; and
- visible provider, review, authorization, stale-source, dispatch, and action
  failures with no fallback.

## 4. Explicitly Excluded

- treating normal chat `yes`/`no` as a review decision;
- reviewer identity, role, tenant, or scopes in public JSON;
- model-selected review policy, dispatcher, recipient, or reviewer;
- email, Slack, Teams, webhook, or third-party workflow ownership;
- unrestricted reviewer search or assignment;
- generic executable scripts in policy configuration;
- hidden action-parameter rewrite;
- retroactive rejection of a committed action;
- blind retry of an unknown action outcome;
- exactly-once notification delivery;
- exactly-once external action effects;
- dynamic escalation graphs;
- post-outcome compensation in this first proof; and
- merging review tasks, action receipts, durable read jobs, chat history,
  input waits, or plan checkpoints into one generic table.

## 5. Module Shape

Keep the implementation in `ai-fabric-execution`:

```text
ai.fabric.execution.review
  policy/
  dispatch/
  persistence/
  decision/
  input/
```

Do not create another Maven module. Human review depends directly on specialist
identity, action receipts, trusted execution context, and governed action
invocation already owned by this module.

## 6. Review Policy

An immutable Java policy initially contains:

```text
policy ID and version
review type
allowed decisions
reviewer authorizer ID
dispatcher ID
required reviewer scopes
separation-of-duty flag
task TTL
safe title/summary limits
optional correction schema and handler references
optional information-request schema, information-response schema, and handler
references
optional escalation policy ID
```

Policies are application-selected beans and are validated into one immutable
registry at startup. The model and public request cannot select a policy.

Configuration/manifest authoring can be considered only after this Java
contract and reference proof are stable. A policy must never contain scripts,
SQL, arbitrary endpoints, credentials, identity, or executable authority.

## 7. Durable Records

### Review task

```text
task ID
policy ID and content hash
review type
source type and source fingerprint
keyed initiator/subject/tenant/deployment fingerprints
scoped idempotency fingerprint
encrypted source and safe-presentation envelope
allowed decisions snapshot
status
decision type/fingerprint and keyed reviewer fingerprint
encrypted decision and continuation result
expiry, timestamps, and retention
decision lease owner/expiry and attempt count
successor task ID
optimistic version
```

### Dispatch receipt

```text
dispatch ID
task ID
dispatcher ID
attempt number
idempotency key
accepted/failed status
safe external reference
safe failure reason
created/completed timestamps
```

Delivery acceptance is never a decision.

## 8. State Machine

```text
WAITING_FOR_REVIEW
  -> DECIDING
  -> APPROVED | REJECTED | CORRECTED
  -> WAITING_FOR_INFORMATION
  -> ESCALATED
  -> EXPIRED | FAILED

WAITING_FOR_INFORMATION
  -> WAITING_FOR_REVIEW
  -> ESCALATED | EXPIRED | FAILED

DECIDING after restart
  -> recover same protected decision
  -> repeat only idempotent framework/application continuation
  -> one optimistic terminal transition wins
```

If approval advances an action receipt to `OUTCOME_UNKNOWN`, the review result
must expose that state. It must not claim success or retry the action.

## 9. Security And Freshness

Creation verifies:

- receipt exists and matches the trusted original context;
- receipt remains `PROPOSED` and unexpired;
- specialist, effective profile, action schema, parameter hash, evidence
  hashes, and the protected receipt source binding are captured;
- policy and dispatcher are registered; and
- idempotency is scoped to the original access binding and source.

Decision verifies:

- trusted reviewer tenant matches the task;
- application `ReviewerAuthorizer` allows view and the exact decision;
- separation of duty rejects the original initiator;
- required reviewer scopes are present;
- task is waiting, unexpired, and at the expected version;
- policy content hash still matches;
- linked action receipt and pinned hashes still match;
- current action authorization and application preflight still accept the
  authoritative target; and
- decision payload satisfies its policy-bound schema.

Approval then lets `ActionProposalCoordinator` perform its own current
authority, specialist, effective-profile, action-schema, preflight, and
handler revalidation.

V1 does not claim a generic domain source-version validator. Applications
whose writes depend on an entity revision must enforce that revision in the
registered action authorization/preflight or system-of-record write
condition. A reusable review freshness extension remains a separate design
candidate.

## 10. Configuration

```yaml
ai:
  execution:
    reviews:
      enabled: true
      repository: JDBC
      initialize-schema: false
      default-ttl: P7D
      decision-lease-duration: PT2M
      recovery-interval: PT30S
      recovery-batch-size: 50
      max-dispatch-attempts: 3
      max-decision-attempts: 3
      cleanup-enabled: true
      retention: P90D
      encryption-secret: ${AI_EXECUTION_REVIEW_ENCRYPTION_SECRET}
      fingerprint-secret: ${AI_EXECUTION_REVIEW_FINGERPRINT_SECRET}
```

Production owns the datasource and Flyway/Liquibase migration.
`initialize-schema=true` is only for tests and self-contained demos.

The review key pair must be distinct, stable across restart/replicas, at least
32 characters each, and absent from logs/source control.

## 11. Tests

### Policy and contracts

- immutable IDs, allowed states, and public-data bounds;
- unknown policy/dispatcher/authorizer/handler rejected at startup;
- invalid decision requirements rejected at startup;
- no immediate `CONFIRMATION` review type;
- public decision has no identity, tenant, scopes, dispatcher, or recipient;
  and
- trusted reviewer context is constructed separately.

### JDBC and security

- encrypted task/decision/result round trip;
- no raw identity, receipt ID, parameters, summary, corrections, or reviewer
  reason in query columns;
- wrong key, tampering, or binding mismatch fails visibly;
- task insert and scoped idempotency uniqueness;
- one decision lease winner;
- lease-expired recovery;
- dispatch receipts separate from task decisions;
- retention cleanup with optimistic delete; and
- reopen the same database after restart.

### Coordinator

- task persists before dispatcher call;
- dispatch failure leaves the same waiting task;
- duplicate dispatch uses the same task and dispatch idempotency;
- cross-tenant view/decision denied without disclosure;
- separation-of-duty and missing-scope denial;
- changed policy, receipt binding, pinned receipt hash, schema, or expiry
  denied;
- duplicate same decision replayed;
- conflicting or stale decision rejected;
- approval uses `ActionProposalCoordinator` and cannot invoke a handler
  directly;
- rejection produces no domain mutation;
- correction creates a successor, preserving original receipt;
- request-information round trip is bounded and typed;
- escalation creates one deterministic higher-policy task;
- crash after decision claim resumes safely; and
- unknown action outcome remains visible and unretried.

### Reference application

- low-risk support-credit proposal creates one durable review task;
- review inbox is reviewer-authorized and tenant-scoped;
- approve executes one governed billing action;
- reject leaves refund/credit records unchanged;
- correct produces a revised receipt/task;
- request information returns to review after valid response;
- escalate appears only in the senior reviewer inbox;
- restart preserves pending and terminal tasks;
- another demo session/reviewer cannot inspect or decide;
- real OpenAI produces the proposal, not an application-authored fake; and
- no provider/review failure fallback hides an error.

## 12. Acceptance Gate

This slice is complete only when:

1. review remains distinct from active-user confirmation;
2. a task is durable before any dispatch;
3. dispatcher delivery and reviewer decision are separate records;
4. public payloads cannot supply reviewer identity or authority;
5. task, policy, source, proposal, evidence, and schema versions are pinned;
6. reviewer authorization and separation of duty are current at decision time;
7. approve/reject/correct/request-information/escalate/expire have tested
   state semantics;
8. approval reaches an action only through the governed receipt path;
9. duplicate and restart decision recovery is safe;
10. receipt drift or current authority/preflight failure fails closed;
11. unknown write outcomes are never blindly replayed;
12. package/restart tests pass with tests enabled;
13. real OpenAI produces one genuine reviewed proposal; and
14. docs include migrations, operations, rollback, and Loom AI adoption.

## 13. Delivery Order

1. contracts and policy registry;
2. encrypted JDBC task and dispatch repositories;
3. create/list/find/dispatch lifecycle;
4. reviewer authorization and approve/reject;
5. correction, information request, and escalation;
6. decision recovery, expiry, and cleanup;
7. Agentic Resolver support-credit proof;
8. packaged restart and real OpenAI verification;
9. migration/operations docs and release-note update.

## 14. Verification Evidence

Final verification on 2026-07-29 completed with tests enabled and no skips:

- the execution reactor passed 952 tests: 5 curated-default, 671 core, 56
  chat-session, and 220 execution tests;
- the packaged reference-app reactor passed 12 shared smoke-support and 111
  Agentic Resolver tests;
- focused review security, JDBC, continuation, and auto-configuration
  coverage passed 31 tests;
- application acceptance covered approval, rejection, correction with a
  successor, typed information response, escalation, expiry, authorization,
  safe HTTP projection, cleanup, and idempotent replay;
- a packaged application used real OpenAI to create a genuine governed
  support-credit proposal and dispatched review task;
- the pending task survived a process restart and approval executed the
  registered `request_refund` action once;
- after another restart, the exact version-bound decision returned the same
  safe outcome; and
- the authoritative file-backed database contained exactly one refund/credit
  mutation after replay.

`git diff --check` passed, and the review implementation contains no TODO,
placeholder, unsupported-operation, dummy, or empty implementation.

## 15. Next Plan

After durable human review is approved, choose the next feature only from a
measured product need:

- one-level declared delegation;
- explicit specialist handoff; or
- bounded read-only parallel fan-out/fan-in.

Do not begin dynamic manager routing or a general workflow graph before one of
those narrower products proves its value.
