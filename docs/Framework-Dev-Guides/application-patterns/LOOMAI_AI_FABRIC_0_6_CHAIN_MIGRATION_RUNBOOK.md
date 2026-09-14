# LoomAI AI Fabric 0.6 Chain Migration Runbook

## Purpose

This runbook lets LoomAI adopt the bounded multi-specialist chains introduced
in AI Fabric `0.6.0` through the current `0.6.1` patch release, without
replacing existing specialist, plan, action, RAG, or chat behavior.

The capability is opt-in. Upgrade first with chains disabled, prove all
existing LoomAI flows, then enable one read-only canary chain.

## Adoption Decision

Use a chain only when the set of useful specialists depends on request meaning
or on a previous projected result. Keep:

- direct execution for one known specialist;
- fixed plans for known topology;
- the current bounded conversation manager for zero-or-one-worker turns; and
- actions, reviews, receipts, and reconciliation in their existing state
  machines.

The first LoomAI chain must be read-only. Do not place deployment mutations,
configuration writes, secret changes, rollbacks, or action confirmations in a
chain worker.

## Phase 1: Upgrade With The Feature Off

1. Import the published `0.6.1` BOM after Maven Central verification.
2. Keep `ai.execution.specialist-chains.enabled=false`.
3. Compile the entire platform and every deployed application.
4. Run existing indexing, Data Sync, RAG, chat, action, receipt, review,
   specialist, plan, delegation, and handoff tests.
5. Verify deployed health reports the expected AI Fabric version and immutable
   application commit.
6. Choose the structured-output attempt budget explicitly. AI Fabric `0.6.1`
   defaults `ai.execution.output-finalization.max-attempts` to `2`; use `1` if
   LoomAI must preserve the prior single-generation behavior and cost profile.

No chain table or chain secrets are needed while the feature remains disabled.

## Phase 2: Choose One Canary

Recommended first proof: a read-only deployment investigation that may need a
deployment-health specialist, a configuration-evidence specialist, or both.

The canary must have:

- one exact-version manager;
- two or fewer exact-version read-only workers;
- server-owned tenant, deployment, subject, and scopes;
- exact worker input types and output types;
- application-owned mappers and safe projectors;
- no worker conversation access;
- no worker writes;
- one small decision/worker/deadline budget; and
- objective routing cases for no worker, one worker, and both workers.

Keep the current LoomAI endpoint alongside the canary until behavior and
operational evidence are approved.

## Phase 3: Register Specialists And Chain

Existing specialist manifests remain the correct way to define manager and
worker specialists. The manager manifest must use the strict
`SpecialistChainDirective` JSON Schema and enumerate only the canary's exact
worker IDs.

AI Fabric `0.6.x` intentionally has no YAML chain manifest. Register the chain
as an application Java bean referencing registered mapper/projector components.
Do not create a LoomAI-only YAML contract, reflection bridge, or ignored
configuration fields.

LoomAI may generate reviewable Java registration code, but application source
control remains the authority for:

- chain ID and version;
- manager and worker references;
- target descriptions;
- delegation, parallel, and handoff policy;
- component IDs;
- limits; and
- conversation policy.

### Manifest identity migration in 0.6.1

AI Fabric `0.6.1` makes a manifest-defined specialist's effective identity
include the exact resolved prompt profile plus the resolved input and output
schemas. This closes a reproducibility gap: changing a referenced resource now
changes the specialist hash even if the top-level YAML is unchanged.

For a fresh LoomAI chain adoption, no data migration is needed. If LoomAI has
already persisted `0.6.0` executions that reference manifest-defined
specialists, stop new submissions and drain or explicitly cancel those records
before replacing the runtime. Do not relabel old executions with the new hash.
Retain the old runtime and secrets until every retained `0.6.0` execution is
terminal or outside the required audit window.

## Phase 4: Install Durable Storage

1. Add the `ai_specialist_chain_execution` migration from
   `BOUNDED_MULTI_SPECIALIST_CHAINS.md` to LoomAI's Flyway or Liquibase
   ownership.
2. Apply it before enabling the runtime.
3. Generate two different stable secrets with at least 32 characters.
4. Store them in LoomAI's normal deployment secret boundary:

```text
AI_SPECIALIST_CHAIN_ENCRYPTION_SECRET
AI_SPECIALIST_CHAIN_FINGERPRINT_SECRET
```

5. Do not expose either secret in manifests, prompts, logs, health output,
   deployment templates, or browser configuration.
6. Back up and retain the secrets with the database. Losing them makes retained
   protected execution state unreadable.

Recommended production configuration:

```yaml
ai:
  execution:
    output-finalization:
      # Total generation attempts for malformed or validator-rejected output.
      # Use 1 to retain the pre-0.6 single-attempt behavior.
      max-attempts: 2
    specialist-chains:
      enabled: true
      durable-enabled: true
      allow-ephemeral: false
      initialize-schema: false
      max-active: 100
      max-duration: PT90S
      max-manager-decisions: 4
      max-worker-invocations: 2
      max-parallel-workers: 2
      max-invocations-per-target: 1
      max-projected-result-characters: 8000
      lease-duration: PT2M
      recovery-interval: PT30S
      recovery-batch-size: 50
      max-attempts: 3
      cleanup-enabled: true
      retention: P30D
      encryption-secret: ${AI_SPECIALIST_CHAIN_ENCRYPTION_SECRET}
      fingerprint-secret: ${AI_SPECIALIST_CHAIN_FINGERPRINT_SECRET}
```

`output-finalization.max-attempts` and
`specialist-chains.max-attempts` are different controls. The first is a
bounded model-generation budget for correcting malformed or
validator-rejected structured output. The second limits durable chain recovery
attempts. Output correction receives the same approved grounding plus only a
safe failure category and schema location; it never receives the rejected
payload. Provider-call failures are not retried by this mechanism, and exhausted
attempts remain visible failures rather than fallback answers.

## Phase 5: Preserve Trusted Boundaries

The public LoomAI request may supply the user's newest message and an opaque
conversation/request ID. Backend code must derive:

- authenticated principal;
- subject;
- tenant;
- deployment;
- scopes;
- allowed evidence boundaries;
- exact chain ID; and
- stable idempotency key.

Strip or reject caller fields named like `tenantId`, `deploymentId`,
`subjectId`, `scopes`, `targetSpecialist`, `manager`, `vectorSpace`,
`evidenceIds`, or provider configuration. They are data, never authority.

Every worker must independently pass current AI Fabric authorization. Parent
and worker permissions are never unioned.

## Phase 6: Expose Honest Operations

LoomAI should expose:

- submitted execution ID;
- `QUEUED` and `RUNNING` state;
- terminal status and safe failure code;
- cancellation;
- exact replay indicator;
- selected exact workers and grouping;
- approved evidence references;
- deployed AI Fabric version and source commit; and
- durable storage readiness.

Do not show manager chain-of-thought, raw provider responses, protected state,
full trusted context, raw worker output, or unrestricted evidence.

Do not replace a provider, mapper, projector, persistence, grounding, timeout,
or required-branch failure with a canned answer.

## Phase 7: Required Canary Matrix

Run all of these against the packaged LoomAI application:

1. No-worker request completes without a worker call.
2. One-worker request invokes only the relevant specialist.
3. Cross-domain request invokes both independent workers.
4. Adaptive request invokes the second worker only after the first projection.
5. Ambiguous request asks one useful clarification.
6. Invented target is denied without invoking it.
7. Spoofed tenant/deployment/subject/scopes cannot alter retrieval or output.
8. Required branch failure returns no final success answer.
9. Exact replay returns identical lineage without another worker call.
10. Changed payload under the same key conflicts.
11. Restart after a completed worker resumes without repeating that worker.
12. Restart during an in-flight provider operation reports uncertainty and
    does not rerun blindly.
13. Cancellation is visible and late output is discarded.
14. Retention cleanup leaves active work untouched.
15. Malformed or schema-invalid manager/worker output either succeeds within
    the configured output-finalization budget or fails visibly after exhaustion;
    usage evidence records every provider attempt and no rejected payload is
    logged.

Use real provider keys only through LoomAI's private keyed CI/deployment
profile. Provider unavailability must fail visibly.

## Rollback

1. Stop new chain submissions.
2. Allow active executions to reach terminal state or cancel them explicitly.
3. Set `ai.execution.specialist-chains.enabled=false` and redeploy.
4. Restore the prior application endpoint/selection path.
5. Retain the chain table and both secrets until the configured retention
   period has passed and audit requirements permit deletion.

Disabling chains does not alter existing specialist, action, RAG, chat,
receipt, review, delegation, handoff, or fixed-plan state.

## Approval Evidence

LoomAI may enable the canary for production traffic only when it records:

- published `0.6.1` Maven Central resolution;
- immutable framework tag and checksum;
- full platform compile and deterministic tests;
- keyed provider matrix result;
- migration and secret readiness;
- the reviewed `output-finalization.max-attempts` value and its provider-cost
  implication;
- all canary cases above;
- restart and replay evidence;
- no cross-tenant/deployment leakage;
- health output with matching version/commit; and
- an explicit rollback observation.
