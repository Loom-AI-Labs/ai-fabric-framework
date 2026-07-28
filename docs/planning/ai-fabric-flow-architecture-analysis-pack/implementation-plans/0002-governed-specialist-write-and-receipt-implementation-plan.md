# Governed Specialist Write And Receipt Implementation Plan

- **Status:** Implemented and verified locally and in the packaged runtime; not released
- **Date:** 2026-07-28
- **Prerequisite:** Completed P0/P1 read-only agentic enablement
- **Target:** Next AI Fabric release after `0.4.0`; version not assigned
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## Purpose

Add one specialist-coordinated WRITE without weakening AI Fabric's existing
authorization, confirmation, idempotency, action, or application-ownership
boundaries.

This work remains deliberately separate from the read-only P0/P1 contract. A
model may propose a write, but it never authorizes, confirms, or directly
executes one.

## Required Outcome

The Agentic AI Action Resolver may propose one application-owned account
resolution action. The application must:

1. resolve the current trusted subject and authority;
2. validate the action and typed parameters;
3. create a durable, expiring, profile-pinned receipt;
4. show an application-owned confirmation message;
5. accept confirmation against the receipt, not against free-form model text;
6. execute exactly once through `GovernedActionInvocationService`;
7. persist the authoritative action outcome; and
8. expose a safe projected result without raw internal state.

## Non-Negotiable Boundaries

- No direct `AIActionHandler.executeAction(...)` call outside the governed
  invocation service.
- No account, tenant, action scope, or target accepted from model output or the
  public confirmation request.
- No automatic confirmation by the LLM.
- No blind retry after an unknown write outcome.
- No in-memory-only receipt in a production profile.
- No confirmation token that contains executable parameters.
- No write capability inherited merely because a Mode exposes actions.
- No hidden fallback that reports success after provider, policy, persistence,
  or action failure.

## Receipt Contract

Introduce a versioned `ActionProposalReceipt` owned by AI Fabric execution
semantics:

```text
receiptId
invocationId
specialistId and version
effectiveProfileHash
trusted principal fingerprint
trusted subject type and fingerprint
actionName
validated parameter payload or encrypted reference
parameter schema/version
confirmation message
idempotency key
evidence references or immutable evidence hashes
status
createdAt
expiresAt
confirmedAt
executedAt
authoritative outcome reference
version
```

Required statuses:

```text
PROPOSED
CONFIRMED
EXECUTING
SUCCEEDED
FAILED
OUTCOME_UNKNOWN
REJECTED
EXPIRED
```

Use compare-and-set transitions. A receipt in any terminal state cannot return
to an executable state.

## Storage

The implementation defines a small receipt repository contract and ships:

- an in-memory implementation for deterministic unit tests only; and
- a JDBC implementation for the production reference app.

The JDBC implementation is classpath- and bean-conditional inside
`ai-fabric-execution`. It uses optional `spring-jdbc` support rather than
creating another artifact. Applications without JDBC or without receipt
configuration do not activate it.

The JDBC schema must include:

- unique `receipt_id`;
- unique non-null `idempotency_key`;
- optimistic version;
- indexed status and expiry;
- encrypted or application-safe parameter storage; and
- immutable audit timestamps.

Do not claim restart safety until the JDBC restart and recovery tests pass.

## Confirmation API

The public confirmation request should contain only:

```json
{
  "receiptId": "receipt-...",
  "decision": "CONFIRM | REJECT"
}
```

The server re-resolves:

- authenticated principal;
- trusted subject;
- current specialist registration;
- current action metadata;
- current application authorization; and
- the pinned effective profile.

Any mismatch, expiry, replay, or profile drift denies the request before action
invocation.

## Execution Flow

```text
specialist read/analysis
  -> model proposes an allowlisted WRITE name and typed arguments
  -> application validates proposal against current trusted context
  -> effective capability profile authorizes proposal only
  -> durable receipt created
  -> application returns CONFIRMATION_REQUIRED
  -> user confirms receipt
  -> authority and profile revalidated
  -> receipt moves CONFIRMED -> EXECUTING atomically
  -> GovernedActionInvocationService executes once
  -> authoritative result stored
  -> receipt becomes SUCCEEDED, FAILED, or OUTCOME_UNKNOWN
  -> application-owned safe result projection returned
```

## First Reference Action

The implemented reference action is `update_address`, a low-risk account
operation with application-owned validation and confirmation. Cancellation,
payment mutation, and account-credit writes remain outside this proof.

The reference action must already have:

- typed parameter metadata;
- current application authorization;
- explicit confirmation;
- an idempotent handler or authoritative idempotency boundary;
- safe result projection;
- behavior/audit event;
- deterministic tests; and
- a clear reconciliation path for unknown outcomes.

## Framework Changes

Expected new or extended surfaces:

- receipt contract and status model in `ai-fabric-execution`;
- receipt repository SPI;
- conditional JDBC receipt support in `ai-fabric-execution`;
- specialist write proposal validator;
- confirmation and rejection service;
- receipt-aware governed invocation coordinator;
- safe action outcome projector; and
- metrics for proposal, confirmation, denial, replay, execution, expiry, and
  unknown outcome.

Do not add a general workflow graph or event broker.

## Test Matrix

### Contract And Unit

- valid proposal creates one receipt;
- action outside the effective profile is denied;
- READ-only specialist cannot create a receipt;
- parameters are schema-validated before persistence;
- confirmation message comes from registered application metadata;
- expiry and state transitions are deterministic;
- receipt IDs and idempotency keys are unique;
- sensitive parameters never appear in logs or public diagnostics.

### Security

- cross-account receipt confirmation denied;
- cross-tenant confirmation denied;
- model-supplied subject ignored;
- tampered action name or parameters denied;
- profile-hash mismatch denied;
- expired receipt denied;
- replayed confirmation cannot execute twice;
- concurrent confirmation executes at most once;
- rejected receipt cannot execute;
- authority revoked between proposal and confirmation denies execution.

### Persistence And Recovery

- receipt survives process restart;
- `PROPOSED` and `CONFIRMED` recovery is deterministic;
- stale `EXECUTING` receipt becomes `OUTCOME_UNKNOWN`, not retried blindly;
- authoritative action result reconciles an unknown receipt;
- cleanup retains required audit evidence.

### Reference App

- proposal and confirmation through HTTP;
- rejection flow;
- idempotent replay;
- visible provider failure before proposal;
- visible persistence failure;
- visible handler failure;
- account state changes only after confirmation;
- the authoritative account-profile read reflects the write while policy
  vectors remain unchanged; address PII is intentionally not indexed;
- original Account Resolver remains independently green.

### Real Provider

With a real provider key:

- the LLM may propose only the registered action;
- malformed or extra parameters fail visibly;
- the confirmation receipt remains application-owned;
- hostile instructions cannot bypass confirmation; and
- the final response reflects the authoritative action result.

## Verification Evidence

Verification completed on 2026-07-28:

- source-based Docker build ran tests normally across all 15 selected framework
  reactor modules: 1,175 tests, zero failures or errors;
- packaged real-app build ran 12 smoke-support tests and 79
  `agentic-ai-action-resolver` tests, zero failures or errors;
- the unchanged original Account Resolver ran its 49 tests plus the 12
  smoke-support tests successfully;
- receipt contract tests cover validation, identity binding, encrypted
  persistence, optimistic transitions, rejection, expiry, concurrency,
  idempotency, cleanup, stale execution, `OUTCOME_UNKNOWN`, and reconciliation;
- the packaged JDBC receipt and opaque demo-session binding survived a restart
  between proposal and confirmation;
- a second packaged restart returned the byte-identical terminal response for
  replay and did not execute the action again;
- real OpenAI read flow returned `BLOCKED`, one deterministic billing-address
  blocker, four policy evidence items, and no write proposal;
- real OpenAI write flow produced only an `update_address` proposal, required an
  application-owned confirmation, returned a safe projected result, and changed
  the next authoritative read to `READY` with zero blockers;
- real OpenAI hostile, malformed/extra-parameter, rejection, post-action,
  idempotent replay, and cross-session isolation cases passed;
- an invalid-provider packaged run returned `INTENT_PROVIDER_FAILED`, was
  retryable, created no receipt, and exposed no native provider message or key;
- packaged logs contain neither synthetic address input nor provider keys; and
- `ai-fabric-account-resolver` has no tracked changes from this implementation.

Real-provider proof validates normal and hostile model behavior. Deterministic
fault-injection tests separately prove persistence, policy, handler,
projection, recovery, and unknown-outcome branches without depending on a
provider producing a particular failure.

## Release Gate

The implementation gate is complete. Publication, version assignment, and
release approval remain separate decisions:

- [x] Receipt contract and JDBC implementation are complete.
- [x] Every state transition is atomic and tested.
- [x] Confirmation binds principal, subject, specialist version, and profile.
- [x] Final invocation uses `GovernedActionInvocationService`.
- [x] Replay and concurrency tests prove at-most-once execution.
- [x] Unknown outcomes are visible and reconcilable.
- [x] No sensitive value leaks through logs, diagnostics, or receipts.
- [x] Packaged Docker and restart tests pass.
- [x] Real-provider hostile and normal flows pass.
- [x] Migration, operations, and rollback guidance is complete.

## Decision

Implementation was approved after the P0/P1 proof and is now complete against
this plan. It adds an opt-in governed specialist WRITE contract without
changing the default read-only boundary. It has not been versioned, committed
as a completed release, pushed, or published by this verification step.
