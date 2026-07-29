# Durable Human Review

## Purpose

AI Fabric can place an existing governed action proposal into a durable,
application-authorized review lifecycle. Use this when a decision crosses an
HTTP request, process, actor, or time boundary.

Examples include:

- a support agent reviewing an AI-proposed account credit;
- a senior operator deciding an escalated refund;
- an approver correcting a bounded proposal before execution; or
- a reviewer requesting typed source information before deciding.

Durable review is not normal chat confirmation:

| Chat confirmation | Durable human review |
| --- | --- |
| The active user decides their current proposal | A separately authenticated reviewer decides later |
| Conversation/request lifecycle | Cross-request and cross-process lifecycle |
| `CONFIRM` or `REJECT` | Policy-bound approve, reject, correct, request-information, or escalate |
| Uses the action receipt directly | Wraps the receipt in a versioned review task |
| No dispatch lifecycle | Persists a task before a separate dispatch receipt |

The model may create and explain a proposal. It cannot choose the review
policy, reviewer, tenant, scopes, dispatcher, decision, escalation target, or
execution authority.

## Dependency

Add the optional execution module:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-execution</artifactId>
  <version>${ai-fabric.version}</version>
</dependency>
```

The module also requires the normal governed-action receipt path. Human review
does not invoke application handlers directly.

## Lifecycle

```text
application-selected specialist
  -> evidence-grounded governed WRITE proposal
  -> encrypted ActionProposalReceipt in PROPOSED
  -> application selects an exact ReviewPolicyId
  -> encrypted review task committed to JDBC
  -> separate dispatch receipt committed
  -> application dispatcher receives safe ReviewTaskView
  -> backend authenticates reviewer into TrustedReviewerContext
  -> current policy, tenant, scopes, separation of duty, and authorizer checked
  -> optimistic decision lease
  -> source receipt and all pinned hashes rechecked
  -> APPROVE or REJECT delegates to ActionProposalCoordinator
  -> current action authority, schema, preflight, and handler revalidated
  -> safe ActionOutcomeView stored and returned
```

The application owns authentication and constructs
`TrustedReviewerContext`. Public JSON must not contain reviewer identity,
tenant, scopes, role, dispatcher, recipient, or authorization decisions.

## Application Extensions

An application registers:

- one or more immutable `ReviewPolicyDefinition` beans;
- a `ReviewerAuthorizer` for current application authorization;
- a `ReviewTaskDispatcher` for a local inbox or external delivery adapter;
- an optional `ReviewCorrectionHandler`;
- an optional `ReviewInformationHandler`; and
- any JSON schemas referenced by correction or information decisions.

Policy registration fails at startup when an allowed decision is missing its
required schema, handler, escalation policy, authorizer, or dispatcher.

The policy contains identifiers and limits, not executable scripts, SQL,
credentials, arbitrary URLs, or identity data.

## Decisions

### Approve

Approval confirms the linked action only through
`ActionProposalCoordinator`. That coordinator rechecks the exact specialist
content hash, effective capability profile, action schema, protected
parameters, current authority, and application preflight before invoking the
registered action.

### Reject

Rejection retires the linked action receipt without a domain mutation.

### Correct

A correction must satisfy the policy's exact JSON schema. The registered
handler creates a new governed action proposal and successor review task. It
cannot edit the original receipt or review history.

### Request Information

The request and response use separate exact schemas. The encrypted response is
accepted only from the original trusted source binding. A valid response moves
the same task back to `WAITING_FOR_REVIEW` and dispatches it again.

### Escalate

Escalation creates one deterministic successor under the policy's registered
higher-authority policy. It does not execute or reject the linked action.

## Configuration

```yaml
ai:
  execution:
    reviews:
      enabled: true
      repository: JDBC
      initialize-schema: false
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

Each secret must contain at least 32 characters. The two review secrets must:

- differ from one another;
- differ from the action-receipt and durable-job secrets;
- remain stable across restart and replicas; and
- stay outside source control, logs, and public configuration.

`IN_MEMORY` is intended for tests. A production profile rejects it unless the
application explicitly acknowledges the non-durable risk.

`initialize-schema=true` is suitable for tests and self-contained demos.
Production applications should own a reviewed Flyway or Liquibase migration.

## PostgreSQL Migration

Adapt ownership, schema name, and migration version to the host application:

```sql
CREATE TABLE ai_review_task (
  task_id VARCHAR(120) PRIMARY KEY,
  policy_name VARCHAR(120) NOT NULL,
  policy_version VARCHAR(120) NOT NULL,
  policy_content_hash VARCHAR(64) NOT NULL,
  review_type VARCHAR(40) NOT NULL,
  source_type VARCHAR(40) NOT NULL,
  source_fingerprint VARCHAR(64) NOT NULL,
  initiator_fingerprint VARCHAR(64) NOT NULL,
  subject_fingerprint VARCHAR(64) NOT NULL,
  tenant_fingerprint VARCHAR(64) NOT NULL,
  deployment_fingerprint VARCHAR(64) NOT NULL,
  idempotency_fingerprint VARCHAR(64) NOT NULL UNIQUE,
  request_fingerprint VARCHAR(64) NOT NULL,
  protected_source TEXT NOT NULL,
  protected_presentation TEXT NOT NULL,
  allowed_decisions TEXT NOT NULL,
  status VARCHAR(40) NOT NULL,
  decision_type VARCHAR(40),
  decision_fingerprint VARCHAR(64),
  reviewer_fingerprint VARCHAR(64),
  protected_decision TEXT,
  protected_result TEXT,
  failure_reason VARCHAR(160),
  successor_task_id VARCHAR(120),
  created_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  terminal_at TIMESTAMP,
  lease_owner VARCHAR(160),
  lease_until TIMESTAMP,
  attempt_count INTEGER NOT NULL,
  version BIGINT NOT NULL
);

CREATE UNIQUE INDEX idx_ai_review_idempotency
  ON ai_review_task (idempotency_fingerprint);

CREATE INDEX idx_ai_review_inbox
  ON ai_review_task (tenant_fingerprint, status, created_at);

CREATE INDEX idx_ai_review_recovery
  ON ai_review_task (status, lease_until, updated_at);

CREATE INDEX idx_ai_review_expiry
  ON ai_review_task (status, expires_at);

CREATE TABLE ai_review_dispatch (
  dispatch_id VARCHAR(120) PRIMARY KEY,
  task_id VARCHAR(120) NOT NULL,
  dispatcher_id VARCHAR(160) NOT NULL,
  attempt_number INTEGER NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL UNIQUE,
  status VARCHAR(40) NOT NULL,
  external_reference VARCHAR(240),
  failure_reason VARCHAR(160),
  created_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP,
  version BIGINT NOT NULL
);

CREATE INDEX idx_ai_review_dispatch_task
  ON ai_review_dispatch (task_id, attempt_number);
```

The framework intentionally does not require a foreign key. Review dispatch
can be integrated with an application-owned delivery system, and cleanup
removes dispatch history before deleting a retained terminal task.

## Public API Boundary

AI Fabric provides Java contracts, not an unauthenticated review controller.
The application should expose only:

- safe inbox rows from `ReviewTaskView`;
- authorized detail after `findDetail`;
- a decision ID, decision type, expected version, and policy-shaped response;
- a source-bound information submission; and
- safe outcome data from the application's `ActionOutcomeProjector`.

With Spring Boot 4, keep HTTP request/response DTOs independent of a specific
Jackson implementation. Convert plain maps into the framework's internal
`JsonNode` decision contract at the application boundary.

Do not expose:

- the linked action receipt ID;
- executable parameters;
- raw principal, subject, tenant, or reviewer identifiers;
- keyed fingerprints;
- encrypted payloads;
- credentials or authority scopes; or
- raw handler results.

## Durability And Recovery

The framework provides:

- task persistence before dispatch;
- a separate dispatch receipt with bounded retries;
- optimistic decision ownership;
- exact decision replay bound to reviewer and expected task version;
- recovery of expired decision leases;
- expiry of untouched tasks;
- retention cleanup of terminal tasks and dispatch history; and
- no blind retry of an unknown action outcome.

Dispatch acceptance is not reviewer approval. A dispatch failure leaves the
same review task durable and available for recovery.

If the process stops after a decision lease is acquired, startup recovery
replays the protected decision through the same idempotent continuation. If
the linked write reaches `OUTCOME_UNKNOWN`, the review exposes failure and
does not execute the write again.

## Failure Behavior

| Condition | Visible behavior |
| --- | --- |
| Wrong tenant, scope, actor, or separation of duty | Task is not disclosed |
| Policy version or content changed | Decision fails closed |
| Receipt, specialist, profile, schema, parameters, or evidence fingerprint changed | Source is rejected |
| Current action authority or application preflight fails | Governed action is not executed |
| Invalid correction/information JSON | Task remains waiting |
| Dispatch rejected or unavailable | Task remains durable; bounded recovery retries delivery |
| Decision continuation fails before a known outcome | Lease recovery retries the same protected decision |
| Action outcome is unknown | Terminal visible failure; no blind replay |
| Task expires | Review closes; action proposal is not executed or rejected |
| Secrets change while rows remain | Protected state becomes unverifiable |

## Operations

Monitor at least:

- waiting tasks older than the expected review service level;
- failed and exhausted dispatch attempts;
- `DECIDING` tasks with expired leases;
- terminal `FAILED` tasks grouped by safe failure reason;
- tasks nearing action-receipt expiry;
- retained terminal row growth; and
- governed action outcomes, especially `OUTCOME_UNKNOWN`.

The host application owns notification delivery, reviewer assignment,
workforce identity, audit export, and operational dashboards.

## Rollout

1. Add the execution module and production migration.
2. Configure stable, independent review secrets.
3. Register one policy, authorizer, and dispatcher.
4. Start with one already-governed action proposal.
5. Verify separation of duty and tenant isolation.
6. Exercise approve and reject before enabling optional decisions.
7. Run a packaged restart test with a pending task and a terminal replay.
8. Enable retention cleanup only after audit requirements are agreed.

## Rollback

Disable new review creation with:

```yaml
ai:
  execution:
    reviews:
      enabled: false
```

Before disabling, drain or explicitly close waiting tasks. Do not drop review
or action-receipt tables while pending, deciding, unknown, or audit-retained
records exist. Keep the old secrets available until all protected records have
expired, been exported, or been removed under approved retention.

Disabling durable review does not disable normal chat confirmation or other
specialist execution features.

## Reference Proof

The complete support-credit example is in:

```text
examples/real-apps/agentic-ai-action-resolver
```

It proves:

- a real provider creates the proposal;
- the application chooses the review policy;
- regular and senior reviewer identities come from backend API-key mapping;
- approval executes one governed billing action;
- rejection produces no mutation;
- correction creates a successor;
- information is typed and source-bound;
- escalation moves to the senior inbox;
- task, dispatch, decision, and action state survive restart; and
- public responses omit receipt, account, subscription, and refund IDs.

Implementation evidence is tracked in Plan `0008`:

```text
docs/planning/ai-fabric-flow-architecture-analysis-pack/
  implementation-plans/
  0008-durable-human-review-implementation-plan.md
```
