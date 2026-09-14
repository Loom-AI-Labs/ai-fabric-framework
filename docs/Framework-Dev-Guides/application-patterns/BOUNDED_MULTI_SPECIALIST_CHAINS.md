# Bounded Multi-Specialist Chains

Bounded multi-specialist chains let one application-selected manager consult a
small, closed set of read-only AI Fabric specialists and synthesize one answer.
The manager may select specialists sequentially, select independent specialists
in parallel, ask one clarification question, complete without a worker, or end
with one approved read-only handoff.

This is not recursive agent execution. The manager coordinates the chain and
every worker remains a leaf.

## Choose The Smallest Correct Execution Model

| Application need | AI Fabric API |
| --- | --- |
| One known specialist can answer | `AIExecutionGateway` |
| Known steps always run in a known order | Fixed sequential plan |
| Known independent checks always run | Fixed parallel plan |
| One ambiguous turn needs zero or one worker | Bounded conversation manager |
| Relevant workers depend on the request or earlier projected results | Bounded specialist chain |
| Open-ended graph traversal, cycles, or recursive agents | Use a dedicated graph runtime; this feature is not that runtime |

Do not use a chain merely because more than one specialist exists. Chains add
manager calls, persistence, deadlines, and a larger operational surface.

## Runtime Shape

```text
authenticated application request
  -> exact chain definition selected by application code
  -> exact manager specialist
  -> validated typed directive
  -> zero, one, or bounded approved READ workers
  -> application-approved result projections
  -> bounded manager re-evaluation
  -> grounded answer, clarification, or terminal handoff
```

The model proposes `ASK_USER`, `INVOKE_ONE`, `INVOKE_PARALLEL`, `HANDOFF`, or
`COMPLETE`. Java validation remains the transition authority.

The manager cannot:

- discover an unrestricted specialist catalog;
- construct trusted identity, tenant, deployment, subject, or scopes;
- grant a worker actions, vector spaces, or provider access;
- see raw worker output unless an application projector explicitly includes it;
- invoke a WRITE-capable or dialogue-capable worker;
- allow a worker or handoff successor to start another transition; or
- replace a failed branch with a fabricated success response.

## Public Contracts

The primary contracts are in `ai-fabric-execution`:

```text
SpecialistChainId
SpecialistChainDefinition<I>
SpecialistChainTarget<P, I, O>
SpecialistChainLimits
SpecialistChainInputAdapter<I>
SpecialistChainTargetInputMapper<P, I>
SpecialistChainTargetResultProjector<P, O>
SpecialistChainDirective
SpecialistChainExecutionRequest<I>
SpecialistChainGateway
SpecialistChainExecutionResult
```

Definitions and targets use exact `name@version` identities. The registry
computes a content hash over the manager, workers, component IDs and classes,
target descriptions, policies, and limits. A changed definition cannot resume
old durable work.

Starting with `0.6.1`, a manifest-defined manager or worker content hash also
covers its raw manifest hash and exact resolved prompt, input schema, and output
schema. A prompt-only or schema-only change is therefore definition drift and
cannot resume protected work under the old identity. Drain active manifest-
backed chains, jobs, receipts, and linked reviews before that upgrade, or
deliberately recreate failed-closed work afterward. Java-defined specialist
hashing is unchanged.

## Register Application Boundaries

The application owns three projections:

1. The chain input adapter exposes the current user message and allowlisted
   application context to the manager.
2. Each target input mapper creates the worker's typed request from trusted
   application input plus a non-authoritative manager objective.
3. Each result projector exposes only bounded facts and evidence references
   that the manager may use.

Example shape:

```java
@Bean
SpecialistChainDefinition<IncidentRequest> incidentChain(
    IncidentChainInputAdapter chainInput,
    HealthInputMapper healthInput,
    HealthResultProjector healthResult,
    ChangeInputMapper changeInput,
    ChangeResultProjector changeResult
) {
    return new SpecialistChainDefinition<>(
        SpecialistChainId.of("incident-investigation", "1"),
        SpecialistId.of("incident-chain-manager", "1"),
        IncidentRequest.class,
        chainInput,
        List.of(
            new SpecialistChainTarget<>(
                SpecialistId.of("service-health-reader", "1"),
                "Reads current service metrics and alerts.",
                healthInput,
                healthResult,
                true,  // delegation
                true,  // independent parallel execution
                false  // terminal handoff
            ),
            new SpecialistChainTarget<>(
                SpecialistId.of("change-risk-reader", "1"),
                "Reads recent deployment, approval, and runbook evidence.",
                changeInput,
                changeResult,
                true,
                true,
                false
            )
        ),
        new SpecialistChainLimits(
            Duration.ofSeconds(75),
            4,     // manager decisions, including final completion
            2,     // total worker invocations
            2,     // parallel workers
            1,     // invocations per target in this release
            8_000  // projected result characters
        ),
        SpecialistChainConversationPolicy.REQUIRED
    );
}
```

Each adapter, mapper, and projector has an exact
`SpecialistChainComponentId`. Keep these IDs stable for equivalent behavior and
increment their versions when semantics change.

The Incident Investigation Room contains complete implementations:

```text
examples/real-apps/incident-investigation-room/
  src/main/java/.../execution/IncidentSpecialistChainConfiguration.java
  src/main/java/.../execution/IncidentChainInputAdapter.java
  src/main/java/.../execution/IncidentServiceChainInputMapper.java
  src/main/java/.../execution/IncidentServiceChainResultProjector.java
  src/main/java/.../execution/IncidentChangeChainInputMapper.java
  src/main/java/.../execution/IncidentChangeChainResultProjector.java
  src/main/resources/ai-specialists/incident-chain-manager-v3.yml
```

## Manager Directive Contract

The manager specialist must return the exact
`SpecialistChainDirective` JSON contract:

```json
{
  "type": "COMPLETE",
  "targets": [],
  "message": "The deployment coincides with the observed regression.",
  "reason": "Both approved investigations are complete.",
  "supportingResultIds": ["result-health", "result-change"]
}
```

Its JSON Schema must:

- reject unknown properties;
- require exactly `type`, `targets`, `message`, `reason`, and
  `supportingResultIds`;
- enumerate all five directive types;
- enumerate exactly the chain's registered target IDs; and
- bound and deduplicate supporting result IDs.

Runtime validation still runs when a provider advertises structured-output
enforcement.

For `COMPLETE`, `supportingResultIds` must equal the set of all projected
worker-result IDs available to that manager decision. Omitting a consulted
worker or inventing an ID fails with `CHAIN_GROUNDING_INVALID`. This is exact
structural attribution. It does not use keyword matching and does not claim to
semantically prove every sentence in the model's prose.

## Execute, Observe, And Cancel

The host authenticates first, derives `TrustedExecutionContext`, chooses the
chain, and supplies one scoped idempotency key:

```java
SpecialistChainExecutionRequest<IncidentRequest> request =
    new SpecialistChainExecutionRequest<>(
        SpecialistChainId.of("incident-investigation", "1"),
        incidentRequest,
        trustedContext,
        conversationBinding,
        deadline,
        idempotencyKey
    );

SpecialistChainExecutionResult result = chainGateway.execute(request);
```

For asynchronous UI flows:

```java
SpecialistChainExecutionHandle handle = chainGateway.submit(request);

Optional<SpecialistChainExecutionSnapshot> current =
    chainGateway.find(handle.executionId(), trustedContext);

Optional<SpecialistChainExecutionResult> terminal =
    chainGateway.findResult(handle.executionId(), trustedContext);

boolean cancelled = chainGateway.cancel(handle.executionId(), trustedContext);
```

Status, result lookup, and cancellation are access-scoped to the original
trusted principal, subject, source, tenant, and deployment fingerprints. An
unrelated caller receives no execution details.

## Conversation Ownership

When a definition uses `REQUIRED` or `OPTIONAL` conversation policy, the
application supplies a backend-authorized `ConversationBinding`. AI Fabric:

- claims the conversation through the shared interactive-turn coordinator;
- freezes one bounded approved history snapshot;
- rejects concurrent owners;
- passes the same approved snapshot revision throughout the chain;
- gives workers no conversation access; and
- records only the final validated external turn.

The browser sends only the new user message. It must not send history,
identity, tenant, scopes, or a worker catalog.

## Configuration

Chains are disabled by default. Production-oriented durable configuration:

```yaml
ai:
  execution:
    enabled: true
    specialist-chains:
      enabled: true
      max-active: 250
      max-duration: PT2M
      max-manager-decisions: 4
      max-worker-invocations: 4
      max-parallel-workers: 3
      max-invocations-per-target: 1
      max-projected-result-characters: 12000
      durable-enabled: true
      allow-ephemeral: false
      initialize-schema: false
      lease-duration: PT2M
      recovery-interval: PT30S
      recovery-batch-size: 50
      max-attempts: 3
      cleanup-enabled: true
      retention: P30D
      encryption-secret: ${AI_SPECIALIST_CHAIN_ENCRYPTION_SECRET}
      fingerprint-secret: ${AI_SPECIALIST_CHAIN_FINGERPRINT_SECRET}
```

Deployment ceilings constrain every registered definition. A definition may
narrow those values and cannot widen them.

Durable mode requires:

- a `DataSource`;
- Spring JDBC on the runtime classpath;
- two different stable secrets of at least 32 characters; and
- the `ai_specialist_chain_execution` schema.

`initialize-schema=true` is convenient for tests and self-contained demos.
Production applications should own a reviewed Flyway or Liquibase migration,
then use `initialize-schema=false`. The authoritative schema is kept beside the
JDBC repository implementation and exercised against H2 by repository and
restart tests. Validate its SQL types against the selected production
database.

PostgreSQL-compatible first-release migration:

```sql
CREATE TABLE ai_specialist_chain_execution (
  execution_id VARCHAR(120) PRIMARY KEY,
  chain_name VARCHAR(120) NOT NULL,
  chain_version VARCHAR(80) NOT NULL,
  chain_content_hash VARCHAR(64) NOT NULL,
  manager_name VARCHAR(120) NOT NULL,
  manager_version VARCHAR(80) NOT NULL,
  manager_content_hash VARCHAR(64) NOT NULL,
  access_fingerprint VARCHAR(64) NOT NULL,
  idempotency_fingerprint VARCHAR(64) NOT NULL UNIQUE,
  request_fingerprint VARCHAR(64) NOT NULL,
  protected_request TEXT NOT NULL,
  protected_checkpoint TEXT NOT NULL,
  protected_result TEXT NULL,
  status VARCHAR(40) NOT NULL,
  failure_reason VARCHAR(160) NULL,
  next_decision_index INTEGER NOT NULL,
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

CREATE INDEX idx_ai_chain_recovery
  ON ai_specialist_chain_execution (status, lease_until, updated_at);
CREATE INDEX idx_ai_chain_expiry
  ON ai_specialist_chain_execution (completed_at);
CREATE INDEX idx_ai_chain_access
  ON ai_specialist_chain_execution (access_fingerprint);
```

Treat this table as framework execution state. Do not join business reporting
queries to its protected payload columns.

Explicit process-local development mode is available only with both:

```yaml
durable-enabled: false
allow-ephemeral: true
```

The runtime refuses to start if chains are enabled without either durable
storage or that explicit ephemeral acknowledgement.

## Durable Replay And Recovery

The JDBC repository persists encrypted requests, checkpoints, and terminal
results. It stores keyed fingerprints rather than raw identity and uses
optimistic record versions plus worker leases.

Checkpoints are written before and after manager and worker boundaries.
Recovery follows these rules:

- a completed worker projection is never invoked again;
- an accepted directive can resume without repeating its manager decision;
- completed parallel projections resume in stable target order;
- changed chain or manager hashes fail with `CHAIN_DEFINITION_CHANGED`;
- current worker authorization is evaluated again with recovered trusted
  context;
- terminal exact replay returns the same result and lineage with
  `replayed=true`;
- changed work under the same scoped key fails with
  `CHAIN_IDEMPOTENCY_CONFLICT`; and
- an operation that was in flight when the process stopped is reported as an
  uncertain outcome and is not rerun blindly.

READ-only worker eligibility makes discarded late results safe from business
side effects, but AI Fabric still does not claim exactly-once provider calls.

Cleanup selects only terminal rows older than `retention`. Optimistic deletion
prevents deleting a changed row, and active queued/running rows are never
eligible.

## Parallel And Handoff Rules

`INVOKE_PARALLEL` accepts only targets marked `parallelEligible=true`. Every
branch is mapped before any worker starts. Branches cannot see sibling results,
run through the existing bounded executor, and use `ALL_REQUIRED` fan-in. One
failed branch cancels outstanding work and prevents manager synthesis.

`HANDOFF` is terminal. The exact target must be present in both the chain target
policy and the manager specialist's handoff policy. The existing handoff
gateway independently authorizes the successor. The manager does not resume,
and the successor cannot hand off or delegate again.

## Failure Semantics

| Failure | Observable behavior |
| --- | --- |
| Invalid structured directive | `INVALID`; no proposed worker starts |
| Unknown or unapproved target | `DENIED`; no fallback route |
| Repeated target or unchanged directive | `CHAIN_NO_PROGRESS` |
| Decision, worker, parallel, or projection limit | `CHAIN_BUDGET_EXCEEDED` or the specific budget code |
| Manager provider failure | `CHAIN_MANAGER_INVOCATION_FAILED` |
| Required worker failure | Chain fails with attributable branch reason |
| Mapper failure | `CHAIN_TARGET_INPUT_INVALID`; no worker starts |
| Projector failure | `CHAIN_RESULT_PROJECTION_INVALID`; raw output is withheld |
| Final attribution mismatch | `CHAIN_GROUNDING_INVALID` |
| Deadline | Active work is cancelled and late output discarded |
| Interrupted execution | Interrupt is preserved and status is `CANCELLED` |
| State write uncertainty | Execution stops; work is not rerun blindly |

There is no keyword router, canned answer, or deterministic provider fallback
on any of these paths.

## Observability

Terminal results and snapshots expose a safe trace:

- chain ID and content hash;
- manager invocation ID and typed directive per decision;
- safe selection reason;
- sequential or parallel group ID;
- exact worker IDs, invocation IDs, and statuses;
- approved evidence-reference IDs;
- remaining bounded budgets;
- timestamps, replay flag, durable flag, and terminal status; and
- safe failure codes.

The trace is not chain-of-thought. Logs contain execution IDs, definition IDs,
failure classes, and reason codes, not user input, trusted identity, raw
provider payloads, credentials, or unprojected evidence.

Manager directives use the normal specialist structured-output finalizer. By
default, one malformed or validator-rejected directive can receive one fresh
shape-correction attempt from the same approved manager input. Configure the
total with `ai.execution.output-finalization.max-attempts` (`1` through `3`,
default `2`). The correction never echoes the rejected payload, does not retry
provider call errors, and cannot bypass chain directive validation.

Micrometer metrics cover active/terminal chains, selected workers, decision and
model-call counts, parallel group size/duration, outcomes, replay, budget
failures, and repository depth.

## Reference Verification

Run the chain-focused framework suite:

```bash
mvn -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-execution \
  -Dtest='ai.fabric.execution.chain.**,DefaultSpecialistChainGatewayTest,AIExecutionSpecialistChainAutoConfigurationTest' \
  test
```

Run the complete deterministic real-app proof:

```bash
mvn -f examples/real-apps/pom.xml \
  -pl incident-investigation-room -am clean verify
```

After installing the candidate framework and real-app reactor, run the
packaged Docker smoke:

```bash
.github/scripts/smoke-incident-specialist-chain-docker.sh
```

Run the real-provider matrix only with a private key:

```bash
export OPENAI_API_KEY='<secret>'
mvn -f examples/real-apps/pom.xml \
  -pl incident-investigation-room -am \
  -Dtest=IncidentInvestigationRealApiIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

Tests run normally. Do not use Maven test-skipping flags.

The `0.6.0` release source was verified with all 383 execution-module tests,
all 14 keyed Incident OpenAI scenarios, a PostgreSQL restart/replay Docker
smoke, and desktop/mobile browser canaries. The `0.6.1` hardening proof adds
385 execution-module tests and seven keyed Account Resolver OpenAI scenarios.
See the [`0.6.0` release notes](../../release-notes/0.6.0.md) and
[`0.6.1` release notes](../../release-notes/0.6.1.md) for exact source versus
post-publication gates and the manifest-hash migration requirement.

## Release Boundary

The first release is intentionally limited to:

- one centralized exact-version manager;
- a closed exact-version target catalog;
- read-only, non-interactive leaf workers;
- adaptive sequential and bounded independent parallel selection;
- `ALL_REQUIRED` fan-in;
- one terminal read-only handoff;
- exact projected-result attribution;
- durable JDBC replay and recovery; and
- explicit visible failures.

Recursive workers, arbitrary graphs, partial-success fan-in, WRITE-capable
workers, dynamic specialist discovery, cross-chain shared memory, and semantic
claim verification require separate designs and evidence.
