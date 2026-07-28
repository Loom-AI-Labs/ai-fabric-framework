# Governed Specialist Writes And Durable Receipts

## Purpose

AI Fabric specialists may propose a registered application write, but they do
not authorize or execute it. Durable action receipts separate model
interpretation from trusted confirmation and application-owned side effects.

Use this capability when all of the following are true:

- a typed `@AIAction` write already exists;
- application authorization can be recalculated from trusted backend context;
- explicit user confirmation is required;
- the action has an application-owned safe result projection;
- unknown outcomes can be reconciled without blind retries; and
- a durable JDBC store is available.

The reference implementation is
`examples/real-apps/agentic-ai-action-resolver`.

## Dependency

Add the execution module and a JDBC-capable Spring dependency:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-execution</artifactId>
  <version>${ai-fabric.version}</version>
</dependency>
```

`spring-jdbc` is optional in `ai-fabric-execution`. A typical Spring Boot JPA
or JDBC starter supplies it. Production applications must also configure a
`DataSource`.

## Capability Model

A write is executable only when it survives every intersection:

```text
registered @AIAction WRITE metadata
  intersect specialist requested write actions
  intersect Mode action policy
  intersect deployment allowed actions
  intersect trusted principal/subject authority
  -> effective proposable write actions
```

Set `writeEnabled=true` on the specialist execution profile and list the action
in `RequestedCapabilityProfile.proposableWriteActions`. This only permits a
proposal. It does not grant confirmation or execution.

Application/service calls may use narrower trusted scopes than interactive
calls even when both use the same specialist definition.

## Required Application Components

### Registered action

The action must:

- use a non-read `ActionAccessMode`;
- require confirmation;
- declare typed parameter metadata;
- resolve the target from trusted `ActionContext`, not model parameters;
- execute through its registered `@ActionExecute` method; and
- provide an authoritative success/failure result.

Do not accept user, account, subscription, tenant, or authorization scope as an
LLM-controlled action parameter when the backend already owns that context.

### Safe outcome projector

Provide one `ActionOutcomeProjector` for every proposable write:

```java
@Component
final class UpdateAddressOutcomeProjector
    implements ActionOutcomeProjector {

    @Override
    public String actionName() {
        return "update_address";
    }

    @Override
    public ActionOutcomeView project(ActionResult result) {
        if (result == null || !result.isSuccess()) {
            return new ActionOutcomeView(
                actionName(),
                "The address could not be updated.",
                Map.of("updated", false)
            );
        }
        return new ActionOutcomeView(
            actionName(),
            "The account address was updated successfully.",
            Map.of("updated", true)
        );
    }
}
```

The projector is a mandatory application boundary. Do not return raw handler
maps, internal IDs, secrets, PII, authorization facts, or database entities.
Missing projectors fail proposal validation. `ActionOutcomeView` accepts only
bounded JSON-safe scalars, maps, and lists and deep-copies the projection
before it is protected and persisted.

Confirmation messages are also stored with the durable receipt. Treat them as
application-visible audit text: keep them concise and never include raw PII,
credentials, tokens, or other secrets.

### Trusted execution context

Build `TrustedExecutionContext` from authenticated backend state. Its principal,
subject, tenant, deployment, and scopes are fingerprinted into the receipt.
Never copy these values from LLM output or public request fields.

## Configuration

Framework receipt support is opt-in:

```yaml
ai:
  execution:
    enabled: true
    capabilities:
      allowed-actions:
        - get_account_profile
        - update_address
    receipts:
      enabled: true
      repository: JDBC
      initialize-schema: true
      ttl: PT10M
      stale-executing-after: PT2M
      recovery-interval: PT1M
      recovery-batch-size: 100
      cleanup-enabled: false
      retention: P90D
      encryption-secret: ${AI_EXECUTION_RECEIPT_ENCRYPTION_SECRET}
      fingerprint-secret: ${AI_EXECUTION_RECEIPT_FINGERPRINT_SECRET}
```

Both secrets are required and must contain at least 32 characters. Use
different random values, keep them in a secret manager, and keep them stable
across replicas and restarts.

`IN_MEMORY` is intended only for deterministic tests. Use `JDBC` in production.
An active `prod` or `production` profile fails startup with `IN_MEMORY` unless
`ai.execution.receipts.allow-in-production=true` explicitly acknowledges the
non-durable risk.
If receipt support is enabled with JDBC and no `DataSource` is available,
startup fails instead of silently disabling governed writes.

Enable Spring scheduling in the host application when periodic recovery is
required:

```java
@SpringBootApplication
@EnableScheduling
public class Application {
}
```

Startup recovery runs whenever receipt support starts. `@EnableScheduling` is
required for subsequent fixed-delay recovery and optional retention cleanup;
the execution module does not silently change the application's global
scheduling policy.

For production-owned migrations, set `initialize-schema=false` after creating
the `ai_action_proposal_receipt` table through Flyway or Liquibase. The schema
in `JdbcActionProposalReceiptRepository` is the reference contract.

## Public API

A model-generated write candidate is internal and Jackson-hidden. After
validation and durable persistence, callers receive only:

```json
{
  "status": "CONFIRMATION_REQUIRED",
  "actionProposal": {
    "receiptId": "action-receipt-...",
    "actionName": "update_address",
    "confirmationMessage": "Are you sure?",
    "status": "PROPOSED",
    "createdAt": "...",
    "expiresAt": "..."
  }
}
```

The gateway recognizes a candidate only inside an explicit successful
confirmation envelope. A failed compound orchestration result cannot smuggle a
proposal through one of its child results.

For retryable proposal requests, pass an opaque transport request ID through
`AIExecutionRequest.idempotencyKey`. Bound its length before provider work.
The receipt layer HMAC-scopes it to the trusted identity, subject, tenant,
deployment, and specialist; it must never be used as authority.

Expose an application endpoint that accepts only:

```json
{
  "receiptId": "action-receipt-...",
  "decision": "CONFIRM"
}
```

Do not accept action name, parameters, identity, tenant, scopes, or target on
the decision endpoint. Rebuild the same trusted execution context on every
decision.

## State And At-Most-Once Semantics

```text
PROPOSED -> CONFIRMED -> EXECUTING -> SUCCEEDED
                                    -> FAILED
                                    -> OUTCOME_UNKNOWN
PROPOSED -> REJECTED
PROPOSED | CONFIRMED -> EXPIRED
OUTCOME_UNKNOWN -> SUCCEEDED | FAILED through reconciliation
```

Transitions use receipt status plus optimistic version in a compare-and-set
update. Only the process that moves `CONFIRMED` to `EXECUTING` invokes the
handler. Terminal receipts never return to an executable state.

The receipt revalidates:

- trusted identity fingerprints;
- specialist ID and version;
- current effective capability profile;
- action registration, access mode, and confirmation requirement;
- action parameter schema hash;
- encrypted parameter integrity;
- expiry; and
- current authority.

## Persistence Failure Semantics

- Proposal-store failure:
  `FAILED / ACTION_RECEIPT_PERSISTENCE_FAILED`, retryable. No action ran.
- Decision-store read or pre-execution transition failure:
  `RECEIPT_STORE_UNAVAILABLE`, retryable. No new action ran.
- Explicit application rejection returned as a failed `ActionResult`:
  terminal `FAILED` with an application-safe projection.
- Invocation exception after `EXECUTING`:
  `OUTCOME_UNKNOWN`, not retryable.
- Authoritative outcome persistence failure:
  `OUTCOME_UNKNOWN`, not retryable.
- Corrupt or undecryptable stored outcome:
  `ACTION_OUTCOME_UNAVAILABLE`, not retryable.

Never turn these outcomes into a generic success response. In particular, do
not retry an `OUTCOME_UNKNOWN` write. Query the application system of record
and reconcile it.

## Recovery And Retention

`ActionProposalRecoveryService` runs at startup and on the configured fixed
delay:

- stale `PROPOSED` and `CONFIRMED` receipts become `EXPIRED`;
- stale `EXECUTING` receipts become `OUTCOME_UNKNOWN`;
- no action handler is invoked by recovery;
- optional cleanup deletes only old `SUCCEEDED`, `FAILED`, `REJECTED`, and
  `EXPIRED` receipts.

`OUTCOME_UNKNOWN` is excluded from automatic cleanup.

Choose retention based on audit, support, and privacy requirements. Cleanup is
disabled by framework default. If enabled, monitor deletion metrics and retain
authoritative domain audit records separately.

## Secret Rotation

The `v1` protected-payload envelope supports one active encryption/fingerprint
key pair. Rotation is therefore an operational drain, not an online dual-key
operation:

1. disable new write proposals;
2. allow `PROPOSED` and `CONFIRMED` receipts to complete or expire;
3. reconcile every `OUTCOME_UNKNOWN` receipt;
4. retain or export required terminal audit evidence;
5. rotate both deployment secrets consistently across all replicas;
6. restart and verify health before re-enabling writes.

Rotating keys while live receipts remain makes their payload or identity
fingerprints unverifiable. Do not hide that failure or delete unknown receipts
to make the system look healthy.

## Rollback

To stop new governed writes:

1. remove the write from specialist requested capabilities;
2. remove it from deployment `allowed-actions`;
3. remove it from trusted authority scopes;
4. deploy and verify that effective profiles expose no proposable writes;
5. preserve receipt support until all existing receipts are terminal or
   reconciled.

Only then may receipt auto-configuration be disabled. Do not drop the table as
part of an application rollback.

## Observability

With Micrometer, inspect:

```text
ai.fabric.execution.action.receipts
```

Tags:

- `event`
- `action`
- `status`

Do not add principal, subject, tenant, receipt ID, idempotency key, or parameter
values as metric tags.

Alert on:

- `store_unavailable`
- `outcome_persistence_failed`
- `outcome_unknown`
- `recovered_unknown`
- repeated validation denial
- abnormal expiry growth

## Verification Matrix

Minimum deterministic proof:

- proposal does not mutate domain state;
- missing/unknown/extra parameters fail before persistence;
- cross-principal, subject, tenant, and deployment decisions are unavailable;
- profile, specialist version, schema, and authority drift deny execution;
- rejection and expiry never execute;
- concurrent confirmations execute at most once;
- terminal replay never re-executes;
- explicit handler rejection is safely projected and persisted as `FAILED`;
- unexpected write-handler exception becomes `OUTCOME_UNKNOWN`;
- store failure is visible;
- completion persistence failure becomes unknown;
- recovery marks stale execution unknown without retry;
- reconciliation is authoritative;
- retention preserves unknown receipts;
- public JSON and logs contain no raw protected parameters.

Minimum packaged proof:

- Docker image builds with tests enabled;
- JDBC receipt survives process restart;
- confirmation after restart executes once;
- health reports receipt durability and recovery settings;
- original non-agentic app remains green.

Minimum real-provider proof:

- explicit complete request creates only the registered proposal;
- missing or extra parameters fail visibly;
- hostile text cannot change subject or bypass confirmation;
- rejection leaves state unchanged;
- confirmation changes authoritative state once;
- follow-up read reflects the new state.

## Troubleshooting

`ACTION_PROPOSAL_COORDINATOR_UNAVAILABLE`:
receipt support is disabled or auto-configuration lacks a repository,
projector, or other required bean.

`ACTION_RECEIPT_PERSISTENCE_FAILED`:
the proposal was not durably acknowledged. Restore the database before asking
the user to propose again.

`RECEIPT_NOT_AVAILABLE`:
the receipt is absent, expired from retention, or does not belong to the
current trusted identity. The response is intentionally indistinguishable.

`EFFECTIVE_PROFILE_CHANGED` or `ACTION_SCHEMA_CHANGED`:
the deployment changed after proposal creation. Let the user create a fresh
proposal under the current contract.

`ACTION_OUTCOME_UNKNOWN`:
do not confirm again as a retry strategy. Reconcile against the application
system of record.

`ACTION_OUTCOME_UNAVAILABLE` after restart:
verify that the same encryption secret is configured and that receipt data was
not corrupted.

`INTENT_PROVIDER_FAILED`:
the provider could not complete specialist intent analysis. The public result,
logs, and extraction diagnostics expose only the stable failure category, not
the provider SDK's raw error body.
