# LoomAI AI Fabric 0.7 Migration Runbook

## Purpose

This is the operator checklist for moving LoomAI from AI Fabric `0.6.1` to
`0.7.0`, then optionally adopting one declarative bounded specialist chain.

Read the complete
[LoomAI platform upgrade notes](../../release-notes/LOOMAI_PLATFORM_AI_FABRIC_0_7_0_UPGRADE_NOTES.md)
before executing this runbook.

## Correct Starting Point

LoomAI does not currently have a Java deployment-knowledge chain to convert.
It has:

- a direct manifest-defined `deployment-knowledge-specialist@1`;
- `POST /api/specialists/deployment-knowledge/query`;
- backend-owned trusted tenant, deployment, subject, and scopes;
- grounded retrieval from the `document` vector space; and
- `AI_EXECUTION_SPECIALIST_CHAINS_ENABLED=false`.

The existing direct endpoint is the functional baseline. The first chain is a
new optional route, not a replacement hidden inside the dependency upgrade.

## Release Gates

Treat these as separate releasable gates:

| Gate | Change | Chain flag |
| --- | --- | --- |
| A | Upgrade all LoomAI framework consumers to `0.7.0` | `false` |
| B | Add deployment-runtime chain table migration | `false` |
| C | Package one reviewed canary and stable secrets | Internal profile only |
| D | Promote a useful product chain | Approved traffic only |

Do not combine Gate A with chain enablement.

## Gate A: Base `0.7.0` Upgrade

### A1. Capture baseline

```bash
git status --short --branch
git rev-parse HEAD
```

Record and preserve existing dirty files. Do not reset user work.

### A2. Verify the release

- Tag: `ai-fabric-framework-v0.7.0`
- Commit: `5b075b66384dc5b756b3b3dd12efaf896ce9a50b`
- BOM: `io.github.loom-ai-labs:ai-fabric-bom:0.7.0`

Resolve the BOM from an empty Maven cache. Do not use a local framework
install as release evidence.

### A3. Audit legacy chain state

For each environment, determine whether
`ai_specialist_chain_execution` exists and whether it contains non-terminal
`0.6.1` work.

Expected LoomAI result: the table is absent or contains no work because chains
are disabled. Record the actual result.

If any non-terminal work exists:

1. stop new chain submissions;
2. let it finish or cancel it under `0.6.1`;
3. retain the database and existing secrets;
4. do not rewrite stored hashes; and
5. continue only when retained work is terminal.

This drain is required because `0.7.0` preserves target declaration order in
chain identity while `0.6.1` sorted targets during hashing.

### A4. Update LoomAI version sources

Update current release defaults in:

```text
ai-fabric-product/pom.xml
ai-infrastructure-module/pom.xml
Platfrom/backend/src/main/resources/application.yml
Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/entity/DeploymentVersionEntity.java
Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/DeploymentConfigCompiler.java
```

Update associated tests and generated deployment expectations. Preserve
historical records and evidence that intentionally identify `0.6.1`.

### A5. Preserve disabled behavior

Keep both of these effective:

```text
AI_EXECUTION_SPECIALIST_CHAINS_ENABLED=false
```

```yaml
ai:
  execution:
    specialist-chains:
      enabled: false
```

Package no `SpecialistChain` resource yet. In fail-fast mode, discovering one
while chain execution is disabled rejects startup by design.

### A6. Run regression suites

```bash
mvn -f ai-fabric-product/pom.xml clean verify
mvn -f ai-infrastructure-module/pom.xml clean verify
mvn -f Platfrom/backend/pom.xml clean verify
```

Run tests normally. Never use `-DskipTests` or `maven.test.skip`.

### A7. Build and deploy from Central

Build the release image with an empty Central-only cache. Prove all AI Fabric
dependencies resolve to `0.7.0`. Deploy the exact immutable LoomAI commit.

### A8. Base canary

Verify:

- runtime and Platform health;
- framework version and deployed commit readback;
- `deployment-knowledge-specialist@1` registration and health;
- valid direct specialist query;
- insufficient-evidence behavior;
- missing tenant/deployment denial;
- two-tenant and two-deployment retrieval isolation;
- Data Sync and indexing status;
- existing chat, RAG, actions, receipts, and reviews; and
- generated deployments still receive the chain flag as `false`.

Gate A is complete only after this evidence is attached to the LoomAI release
record.

## Gate B: Durable Schema Preparation

Add an application-owned migration to each deployment-local runtime database
for `ai_specialist_chain_execution`. Do not add it to the central Platform
backend database.

Use the reviewed schema in the
[`BOUNDED_MULTI_SPECIALIST_CHAINS.md` configuration section](BOUNDED_MULTI_SPECIALIST_CHAINS.md#configuration).
Validate SQL types against LoomAI's production database.

Apply the migration while chains remain disabled. Production must use:

```yaml
ai:
  execution:
    specialist-chains:
      initialize-schema: false
```

Verify migration apply, restart, and rollback policy without enabling a chain.

## Gate C: Declarative Mechanics Canary

### C1. Keep the baseline

Do not remove or rename:

```text
deployment-knowledge-specialist@1
POST /api/specialists/deployment-knowledge/query
```

### C2. Select exact canary IDs

Use separately versioned resources, for example:

```text
deployment-knowledge-chain-request@1
deployment-knowledge-chain-directive@1
deployment-knowledge-chain-manager@1
deployment-knowledge-declarative@1
```

Do not register the same exact chain ID from Java and a manifest. Duplicate
sources fail startup.

### C3. Keep the topology honest

The current LoomAI baseline provides one real read-only worker. A one-worker
chain may be used to prove manager routing, packaging, trusted context,
persistence, replay, restart, and health.

Do not add a fake second worker solely to claim parallel orchestration. Add a
second worker only when it owns distinct data or reasoning, such as an
authoritative runtime-state reader separate from indexed deployment evidence.

### C4. Author immutable resources

Use `apiVersion: ai.fabric/v1` and `kind: SpecialistChain`. The resource may
declare only:

- one exact chain input schema;
- one exact manager;
- one to eight exact read-only, non-interactive workers;
- `JSON_POINTER_MAP` fields from `CHAIN_INPUT` or `MANAGER_OBJECTIVE`;
- `BOUNDED_FACT_PROJECTION` output;
- explicit transition flags and limits; and
- conversation policy.

Do not place Java classes, Spring beans, mappers, projectors, expressions,
scripts, SQL, URLs, providers, models, credentials, identity, tenants,
deployments, subjects, or scopes in YAML.

Validate resources with:

```text
META-INF/ai-fabric/specialist-resource-v1.schema.json
SpecialistChainManifestValidator
```

Persist or compare published exact-ID semantics so the same exact version
cannot silently change execution meaning.

### C5. Add resource locations deliberately

LoomAI currently scans `classpath*:ai-specialists/*`. Either keep the chain in
that immutable bundle or add:

```yaml
ai:
  execution:
    manifests:
      locations:
        - classpath*:ai-specialists/*.yml
        - classpath*:ai-specialists/*.yaml
        - classpath*:ai-specialists/*.json
        - classpath*:ai-chains/*.yml
        - classpath*:ai-chains/*.yaml
        - classpath*:ai-chains/*.json
```

### C6. Configure secrets

Create distinct, stable secret-store values of at least 32 characters:

```text
AI_SPECIALIST_CHAIN_ENCRYPTION_SECRET
AI_SPECIALIST_CHAIN_FINGERPRINT_SECRET
```

Never print their values. Keep them stable across restarts and replacements.

### C7. Enable only the canary profile

Use durable storage, no ephemeral fallback, no runtime schema creation, and
explicit bounded limits from the upgrade notes.

Enable:

```text
AI_EXECUTION_SPECIALIST_CHAINS_ENABLED=true
```

only for the reviewed internal canary deployment. Leave general provisioning
defaults at `false` until promotion is approved.

### C8. Preserve backend authority

LoomAI backend code owns:

- authenticated principal;
- tenant, deployment, subject, and scopes;
- exact chain selection;
- schema-valid chain input;
- idempotency key and deadline;
- conversation binding when configured;
- source-of-truth reads and all writes; and
- receipts, reviews, confirmations, and reconciliation.

Strip or reject caller-provided identity, authority, topology, target,
evidence, vector-space, provider, model, prompt, or credential fields.

### C9. Expose safe health

Project safe fields from `SpecialistChainManifestRuntimeStatus`:

- enabled flags and readiness;
- Java, discovered, inactive, manifest, and total counts;
- aggregate audit, semantics, and execution hashes;
- bounded diagnostics; and
- exact chain source and safe identity.

Never expose manifest bodies, prompts, schemas, trusted context, protected
state, secrets, or raw worker results.

### C10. Verify the canary

Required evidence:

1. schema and semantic validation;
2. duplicate and unknown reference rejection;
3. no-worker completion;
4. one-worker grounded answer;
5. insufficient evidence;
6. malformed manager, mapping, projection, grounding, and provider failures
   with no fallback answer;
7. exact replay with no repeated provider or worker call;
8. changed request under the same key conflict;
9. restart after a persisted worker projection without repeating that worker;
10. cancellation and deadline;
11. definition-drift rejection;
12. cross-owner, tenant, deployment, and subject denial;
13. packaged-runtime startup with immutable resources;
14. real OpenAI execution with usage and lineage; and
15. no raw worker output or protected state in the public response.

## Gate D: Product Chain

Promote beyond the mechanics canary only when a second specialist has a real,
separate responsibility. Prove manager selection of each worker alone and both
when required. Parallel work must be independent and bounded.

Keep a direct route during the observation window. Compare selected workers,
facts, approved evidence IDs, failures, lineage, latency, and provider calls.

## Rollback

1. Stop new declarative submissions.
2. Drain or cancel active declarative work.
3. Route traffic to the direct deployment-knowledge endpoint.
4. Set the chain flag to `false` in the next immutable deployment.
5. Remove the chain resource from that package.
6. Retain chain storage and both secrets through the audit/replay retention
   period.

Do not hot-edit an exact resource, rewrite stored hashes, or delete durable
state merely to make a rollback appear clean.

## Final Sign-Off

Record:

- changed files and LoomAI commit;
- exact framework version and Central-only resolution proof;
- all test totals;
- packaged dependency evidence;
- deployed commit/version evidence;
- direct-specialist regression evidence;
- two-boundary security evidence;
- chain table and secret names, without values;
- exact chain/manager/worker/schema IDs;
- manifest source and hashes;
- replay/restart/cancellation/drift evidence;
- real-provider evidence; and
- rollback readiness.
