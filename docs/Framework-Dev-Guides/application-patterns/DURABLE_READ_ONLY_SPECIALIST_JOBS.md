# Durable Read-Only Specialist Jobs

## Purpose

AI Fabric can persist eligible asynchronous specialist work before dispatch,
recover it after process loss, and preserve scoped replay and typed terminal
results across restart.

Use this path for machine-triggered analysis that is safe to execute at least
once, such as:

- assessing an account after a payment-verification event;
- classifying a support incident from application-owned facts;
- producing a scheduled read-only risk summary; or
- enriching a domain record for later human review without mutating it.

Do not use it for a specialist that can write, wait for user input, request
confirmation, or continue a composed plan.

## Contract

Set:

```yaml
ai:
  execution:
    async:
      repository: JDBC
```

An eligible durable submission must:

- use `ExecutionSource.APPLICATION`, `EVENT`, or `SCHEDULED`;
- use a `SERVICE` or `SYSTEM` principal;
- contain an application-owned subject;
- have no conversation binding; and
- select an exact registered specialist with writes disabled.

The host application still owns:

- broker, outbox, scheduler, and redelivery;
- raw event validation;
- authenticated identity, tenant, and subject resolution;
- deterministic mapping to specialist input;
- datasource and schema migration;
- secret management; and
- operational monitoring.

The model cannot choose durability, identity, authority, subject, specialist,
provider credentials, retry policy, or worker ownership.

## Lifecycle

```text
trusted application request
  -> durable eligibility validation
  -> exact specialist content-hash pinning
  -> encrypted request committed to JDBC
  -> bounded worker dispatch
  -> optimistic lease claim
  -> existing AIExecutionGateway specialist pipeline
  -> encrypted typed terminal result
  -> scoped status, cancel, and replay access
```

On startup and at the configured recovery interval, AI Fabric finds queued
rows and `RUNNING` rows whose lease expired. It expires work past its deadline,
fails work that exhausted its attempt limit, and dispatches the remaining
candidates. One optimistic compare-and-set transition wins each lease.

AI Fabric does not automatically retry a terminal provider, grounding,
validation, policy, or domain failure.

## Delivery Semantics

This feature provides:

- durable request-before-dispatch ordering;
- one durable invocation and terminal record;
- payload-checked idempotent replay across restart;
- access-bound status and cancellation;
- bounded attempts, deadlines, retention, and cleanup; and
- at-least-once read execution.

It does not provide exactly-once provider invocation. If a process stops after
calling the provider but before committing the result, another worker may
repeat the read-only analysis after lease expiry.

This is why V1 rejects WRITE-capable specialists. Unknown write outcomes must
use the governed action-receipt and application reconciliation path, not job
recovery.

## Configuration

```yaml
ai:
  execution:
    async:
      repository: JDBC
      initialize-schema: false
      core-pool-size: 2
      max-pool-size: 4
      queue-capacity: 32
      lease-duration: PT2M
      recovery-interval: PT30S
      recovery-batch-size: 50
      max-attempts: 3
      cleanup-enabled: true
      retention: P30D
      encryption-secret: ${AI_EXECUTION_ASYNC_ENCRYPTION_SECRET}
      fingerprint-secret: ${AI_EXECUTION_ASYNC_FINGERPRINT_SECRET}
```

The two secrets must each contain at least 32 characters, differ from one
another, remain stable across restarts and replicas, and never enter source
control or logs.

Choose a lease duration longer than the normal bounded provider call. A short
lease increases harmless but costly duplicate read execution. A very long
lease delays crash recovery.

`initialize-schema=true` is a convenience for tests and self-contained demos.
Production applications should use a reviewed Flyway or Liquibase migration.

## PostgreSQL Migration

Adapt the migration name and ownership to the host application:

```sql
CREATE TABLE ai_specialist_execution (
  invocation_id VARCHAR(120) PRIMARY KEY,
  specialist_name VARCHAR(120) NOT NULL,
  specialist_version VARCHAR(80) NOT NULL,
  specialist_content_hash VARCHAR(64) NOT NULL,
  access_fingerprint VARCHAR(64) NOT NULL,
  idempotency_fingerprint VARCHAR(64) NULL UNIQUE,
  request_fingerprint VARCHAR(64) NOT NULL,
  protected_request TEXT NOT NULL,
  protected_result TEXT NULL,
  status VARCHAR(40) NOT NULL,
  failure_reason VARCHAR(160) NULL,
  deadline TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP NULL,
  expires_at TIMESTAMP NOT NULL,
  lease_owner VARCHAR(160) NULL,
  lease_until TIMESTAMP NULL,
  attempt_count INTEGER NOT NULL,
  version BIGINT NOT NULL
);

CREATE INDEX idx_ai_execution_recovery
  ON ai_specialist_execution (status, lease_until, updated_at);

CREATE INDEX idx_ai_execution_expiry
  ON ai_specialist_execution (completed_at);

CREATE INDEX idx_ai_execution_access
  ON ai_specialist_execution (access_fingerprint);
```

The request and result columns contain authenticated encrypted envelopes.
Identity and raw idempotency values are represented only by keyed
fingerprints in queryable columns.

## Failure Behavior

| Condition | Visible outcome |
| --- | --- |
| Unsupported source, principal, conversation, or write policy | `REJECTED` before persistence |
| Existing key with identical payload | Original invocation handle |
| Existing key with changed payload | `IDEMPOTENCY_CONFLICT` |
| Specialist content changed before execution | Terminal visible failure |
| Protected payload cannot be verified | Terminal visible failure |
| Deadline reached before recovery | `EXPIRED` |
| Recovery attempts exhausted | `FAILED` |
| Provider returns a terminal failure | Stored terminal failure, no automatic retry |
| Worker queue is full | Row remains queued for later recovery |
| Result cannot be verified during lookup | `EXECUTION_RESULT_UNAVAILABLE` |
| Specialist requests input or confirmation | `DURABLE_CONTINUATION_UNSUPPORTED` |

Status and cancellation with a different principal, subject, source, tenant,
or deployment return no execution rather than revealing another caller's job.

## Secret Rotation

The initial protected-payload format has one active key pair. Before rotating
either secret:

1. stop accepting new durable work;
2. allow queued and running work to become terminal;
3. retain or export any terminal evidence required by application policy;
4. expire or remove old rows under the retention policy;
5. rotate both deployment secrets consistently across replicas; and
6. re-enable submission.

Changing a secret while active rows remain makes their payload or access
binding unverifiable.

## Verification

At minimum, a production adoption must prove:

1. request persistence happens before dispatch;
2. a successful typed result survives packaged application restart;
3. identical replay returns the same invocation after restart;
4. changed facts under the same key conflict;
5. another access binding cannot inspect or cancel the job;
6. provider failure remains visible and is not automatically retried;
7. definition drift and payload tampering fail closed; and
8. no domain write or chat conversation is created.

The reference implementation and restart proof are in:

```text
examples/real-apps/agentic-ai-action-resolver
```

The implementation and acceptance evidence are tracked in Plan `0007`:

```text
docs/planning/ai-fabric-flow-architecture-analysis-pack/
  implementation-plans/
  0007-durable-read-only-specialist-job-implementation-plan.md
```
