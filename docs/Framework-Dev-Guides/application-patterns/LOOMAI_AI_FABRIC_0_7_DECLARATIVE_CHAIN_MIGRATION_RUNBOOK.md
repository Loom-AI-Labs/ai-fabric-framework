# LoomAI AI Fabric 0.7 Declarative Chain Migration Runbook

## Purpose

This runbook moves LoomAI from AI Fabric `0.6.1` Java-defined bounded chains
to the optional `0.7.0` `SpecialistChain` resource without changing LoomAI's
identity, authorization, data, deployment, receipt, review, or reconciliation
ownership.

The declarative resource is a startup-loaded configuration form of the
existing bounded chain runtime. It does not add another engine, database, graph
language, or model-controlled authority boundary.

## Release Decision

Adopt a declarative chain only when:

- the manager and workers already exist as exact registered specialists;
- all workers are read-only, non-interactive, and JSON Schema-backed;
- inputs can be selected with bounded JSON Pointers into top-level fields;
- outputs can be reduced to a summary, bounded string facts, and approved
  evidence IDs; and
- the topology is one closed bounded chain.

Keep LoomAI's chain Java-defined when an input mapper or result projector must
query authoritative state, compute a domain invariant, reconcile a system of
record, or return a rich application representation.

Do not add Java class names, bean names, adapters, mappers, projectors, SpEL,
scripts, SQL, providers, credentials, identity, tenant, deployment, subject,
or scopes to chain YAML.

## Compatibility And Drain Boundary

`0.7.0` intentionally corrects Java-chain execution identity so target
declaration order is preserved rather than sorted during hashing. Before
replacing a `0.6.1` runtime:

1. Stop new chain submissions.
2. Inventory all non-terminal `ai_specialist_chain_execution` rows.
3. Let them finish or cancel them under `0.6.1`.
4. Retain the existing chain secrets and database.
5. Do not rewrite stored chain hashes or relabel old work.
6. Deploy `0.7.0` only after the retained work is terminal.

Other execution state remains separate. Do not migrate chat turns, action
receipts, reviews, durable direct jobs, indexing status, or business records
into the chain table.

## Phase 1: Upgrade With Declarative Chains Absent

1. Resolve the published `0.7.0` BOM from Maven Central using an empty Maven
   local repository.
2. Keep current Java chain beans and endpoints in place.
3. Do not package any `SpecialistChain` resource yet.
4. Run the complete LoomAI compile and deterministic regression suite.
5. Verify indexing, Data Sync, RAG, chat, actions, receipts, reviews, direct
   specialists, plans, delegation, handoff, and Java chains.
6. Verify health reports AI Fabric `0.7.0` and the deployed immutable commit.

No new table is required. Declarative chains reuse the existing
`ai_specialist_chain_execution` table.

## Phase 2: Select One Canary

Use a deployment-knowledge or deployment-diagnostics read path whose useful
evidence may require one or two existing read-only specialists. Keep its
current Java endpoint and add a separately named exact declarative chain, for
example:

```text
deployment-knowledge@1              Java baseline
deployment-knowledge-declarative@1  Manifest canary
```

Do not register both sources under the same exact chain ID. Duplicate Java and
manifest IDs fail startup by design.

The canary must cover:

- completion without a worker;
- one worker;
- independent parallel workers;
- adaptive second-worker selection;
- one clarification;
- one terminal read-only handoff if the domain needs it;
- exact replay;
- cancellation;
- restart recovery; and
- cross-principal, tenant, deployment, and subject denial.

## Phase 3: Package Immutable Resources

Keep the exact input schema, manager, workers, prompts, and chain in reviewed
classpath or mounted deployment resources. Example locations:

```yaml
ai:
  execution:
    manifests:
      enabled: true
      fail-fast: true
      locations:
        - classpath*:ai-specialists/*.yml
        - classpath*:ai-chains/*.yml
        # - file:/etc/loomai/ai-fabric/*.yml
```

Use `apiVersion: ai.fabric/v1` and `kind: SpecialistChain`. Every reference is
an exact `name@version` identity. The manifest may declare only:

- one chain input schema reference;
- one manager message pointer and bounded context allowlist;
- one exact manager;
- one to eight exact workers within deployment ceilings;
- `JSON_POINTER_MAP` input fields from `CHAIN_INPUT` or
  `MANAGER_OBJECTIVE`;
- `BOUNDED_FACT_PROJECTION` summary/string facts/evidence IDs;
- explicit delegation, parallel, and handoff flags;
- bounded limits; and
- conversation policy.

Validate the resource with the packaged schema:

```text
META-INF/ai-fabric/specialist-resource-v1.schema.json
```

Then compile it with `SpecialistChainManifestValidator`. Supply LoomAI's
previously published exact-ID semantics catalogue so an exact version cannot
silently change meaning.

## Phase 4: Configure The Runtime

Keep LoomAI's existing datasource and stable chain secrets:

```yaml
ai:
  execution:
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
      max-json-pointer-characters: 500
      max-json-pointer-depth: 16
      max-mappings-per-target: 32
      max-mappings-per-chain: 128
      max-copied-node-depth: 16
      max-copied-node-count: 1000
      max-copied-value-bytes: 32768
      max-mapped-input-bytes: 65536
      max-mapping-work-units: 4096
      lease-duration: PT2M
      recovery-interval: PT30S
      recovery-batch-size: 50
      max-attempts: 3
      cleanup-enabled: true
      retention: P30D
      encryption-secret: ${AI_SPECIALIST_CHAIN_ENCRYPTION_SECRET}
      fingerprint-secret: ${AI_SPECIALIST_CHAIN_FINGERPRINT_SECRET}
```

The two secrets must be distinct, stable, private, and at least 32 characters.
Do not put them in Git, manifests, health output, prompts, or browser config.

If a `SpecialistChain` resource is discovered while chain execution is
disabled, fail-fast mode rejects startup. Diagnostics mode keeps it inactive
and readiness false; it never silently ignores the chain or requires JDBC
while inactive.

## Phase 5: Preserve LoomAI Ownership

The browser or API caller may provide only the newest user message and normal
domain input. LoomAI backend code still owns:

- authenticated principal;
- subject;
- tenant and deployment;
- authority scopes;
- conversation ownership;
- exact chain selection;
- idempotency key derivation;
- deadlines;
- source-of-truth reads;
- write proposals, confirmations, receipts, and reviews; and
- reconciliation.

Strip or reject caller-provided identity, scope, topology, target, evidence,
vector-space, provider, model, prompt, or credential fields. A manager
objective is untrusted routing text; it never widens worker authority.

## Phase 6: Prove Runtime Identity

Expose safe health fields from `SpecialistChainManifestRuntimeStatus`:

- manifest loading and chain execution enabled;
- readiness;
- discovered, inactive, manifest-defined, Java-defined, and total counts;
- audit resource aggregate hash;
- declarative semantics aggregate hash;
- effective execution registry hash; and
- bounded reason/source diagnostics.

For each registered chain, expose only exact ID, source (`JAVA` or `MANIFEST`),
effective content hash, optional resource/semantics hashes, exact manager, and
exact target IDs. Do not expose prompt text, full schemas, manifests, trusted
context, protected state, or raw worker results.

## Phase 7: Required Verification

Run, without test-skipping flags:

1. resource JSON Schema validation;
2. offline semantic compilation;
3. duplicate Java/manifest ID rejection;
4. malformed mapping/projection and deployment-ceiling rejection before a
   provider call;
5. no-worker, single, adaptive, parallel, clarification, and optional handoff;
6. required-branch/provider/projection failures with no fallback answer;
7. exact replay with no repeated worker call;
8. changed payload under the same key conflict;
9. restart after a persisted worker projection without repeating that worker;
10. cancellation and deadline;
11. changed schema, prompt, target order, mapping, projection, limit, and
    policy rejection;
12. descriptive metadata changing only the audit hash;
13. cross-owner, cross-tenant, cross-deployment, and cross-subject denial;
14. a packaged application with mounted immutable resources;
15. a real OpenAI route with no deterministic fallback; and
16. an empty-cache standalone consumer resolving only Maven Central `0.7.0`
    artifacts.

Use the framework references:

```text
examples/real-apps/incident-investigation-room
examples/real-apps/agentic-ai-action-resolver
examples/agentic-execution-consumer
```

## Traffic Migration

1. Deploy Java and declarative chain IDs side by side.
2. Compare approved scenarios, projected facts, evidence IDs, lineage,
   latency, model calls, and failures.
3. Route internal canary traffic to the declarative exact ID.
4. Promote only after deterministic, restart, isolation, and keyed-provider
   evidence is recorded.
5. Keep the Java route during the observation window.
6. Remove it only through a separate reviewed application change.

## Rollback

1. Stop new declarative submissions.
2. Drain or cancel active declarative executions.
3. Route traffic back to the Java exact ID.
4. Remove the chain resource from the next immutable deployment.
5. Keep chain storage and secrets until retention and audit requirements allow
   cleanup.

Do not hot-edit an active exact resource or overwrite its stored hashes.
