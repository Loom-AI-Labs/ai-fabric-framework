# Governed Specialist Write And Receipt Implementation Plan

- **Status:** Proposed; not approved for implementation
- **Date:** 2026-07-28
- **Prerequisite:** Approved P0/P1 read-only agentic enablement
- **Earliest target:** A release after the proposed AI Fabric `0.5.0`
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## Purpose

Add one specialist-coordinated WRITE without weakening AI Fabric's existing
authorization, confirmation, idempotency, action, or application-ownership
boundaries.

This plan is deliberately separate from the read-only P0/P1 release. A model
may propose a write, but it must never authorize, confirm, or directly execute
one.

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

Define a small receipt repository contract and ship:

- an in-memory implementation for deterministic unit tests only; and
- a JDBC implementation for the production reference app.

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

Choose one low-risk, reversible account action only after product review.
`update_address` or an account-credit request below a policy limit is preferable
to cancellation or payment mutation.

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
- JDBC receipt adapter in a separate optional persistence artifact or an
  existing suitable persistence module;
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
- live-data synchronization reflects the authoritative write;
- original Account Resolver remains independently green.

### Real Provider

With a real provider key:

- the LLM may propose only the registered action;
- malformed or extra parameters fail visibly;
- the confirmation receipt remains application-owned;
- hostile instructions cannot bypass confirmation; and
- the final response reflects the authoritative action result.

## Release Gate

Specialist writes remain unapproved until all of these are true:

- [ ] Receipt contract and JDBC implementation are complete.
- [ ] Every state transition is atomic and tested.
- [ ] Confirmation binds principal, subject, specialist version, and profile.
- [ ] Final invocation uses `GovernedActionInvocationService`.
- [ ] Replay and concurrency tests prove at-most-once execution.
- [ ] Unknown outcomes are visible and reconcilable.
- [ ] No sensitive value leaks through logs, diagnostics, or receipts.
- [ ] Packaged Docker and restart tests pass.
- [ ] Real-provider hostile and normal flows pass.
- [ ] Migration, operations, and rollback guidance is complete.

## Decision

This document permits design and review only. It does not approve specialist
WRITE implementation or change the P0/P1 read-only release boundary.
