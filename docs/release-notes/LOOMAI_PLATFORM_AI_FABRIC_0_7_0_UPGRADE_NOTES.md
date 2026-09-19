# LoomAI Platform Upgrade Notes: AI Fabric `0.7.0`

## Decision

LoomAI can upgrade its framework dependency from AI Fabric `0.6.1` to
`0.7.0` without enabling specialist chains. That dependency-only upgrade is
the first release step and should preserve the current runtime behavior.

Declarative bounded specialist chains are a separate, opt-in product change.
They should be enabled only after LoomAI has durable chain storage, stable
private secrets, a useful read-only chain design, and the security and restart
evidence described below.

This split is important:

1. **Base adoption:** consume the released `0.7.0` artifacts while keeping
   `AI_EXECUTION_SPECIALIST_CHAINS_ENABLED=false`.
2. **Schema preparation:** add the deployment-local chain-state migration
   while chains remain disabled.
3. **Canary adoption:** enable one exact declarative chain for one internal
   deployment profile.
4. **Product adoption:** add a genuinely useful multi-specialist topology only
   when LoomAI has distinct worker responsibilities.

No compatibility shim is required. LoomAI has no external consumers that need
an old framework behavior preserved.

## Released Artifact Evidence

- Maven group: `io.github.loom-ai-labs`
- BOM: `ai-fabric-bom:0.7.0`
- Git tag: `ai-fabric-framework-v0.7.0`
- Release commit: `5b075b66384dc5b756b3b3dd12efaf896ce9a50b`
- GitHub release:
  `https://github.com/Loom-AI-Labs/ai-fabric-framework/releases/tag/ai-fabric-framework-v0.7.0`
- Java: 21
- Spring Boot: 4.1.x

The release was resolved from Maven Central with an empty local cache and was
verified through standalone consumers, framework tests, real-app tests,
PostgreSQL restart proof, and live OpenAI-backed declarative-chain demos.

Release builds in LoomAI must consume Maven Central. They must not clone the
framework source repository or rely on a locally installed `0.7.0` artifact.

## Audited LoomAI Baseline

The following state was verified in the LoomAI `Platform-V11` checkout before
writing this guide:

| Area | Current LoomAI state | Migration implication |
| --- | --- | --- |
| Product modules | `ai-fabric-product/pom.xml` pins `0.6.1` | Change the BOM property to `0.7.0` |
| Product services | `ai-infrastructure-module/pom.xml` pins `0.6.1` | Change the BOM property to `0.7.0` |
| Platform default | `Platfrom/backend/src/main/resources/application.yml` defaults to `0.6.1` | Change the generated deployment version to `0.7.0` |
| Deployment records | `DeploymentVersionEntity` defaults to `0.6.1` | Update only the default for new versions; keep historical records immutable |
| Provisioning | `RailwayProvisioningPlanService` emits the framework version and disables chains | Emit `0.7.0`; keep chain enablement `false` for the base release |
| Runtime | `ai-fabric-execution` is already present | No new execution module is required |
| Manifests | Only `classpath*:ai-specialists/*` is scanned | Add an `ai-chains` location only when a chain resource is packaged there |
| Existing specialist | `deployment-knowledge-specialist@1` is a direct read-only, grounded specialist | Preserve its endpoint as the functional baseline |
| Chain runtime | `AI_EXECUTION_SPECIALIST_CHAINS_ENABLED=false` | Keep disabled during the base upgrade |
| Chain schema | No LoomAI runtime migration for `ai_specialist_chain_execution` was found | Add an application-owned deployment-runtime migration before canary enablement |
| Chain secrets | No chain protection secrets are configured | Add stable secrets only in the canary deployment secret store |

The existing direct endpoint is:

```text
POST /api/specialists/deployment-knowledge/query
```

It binds `deployment-knowledge-specialist@1`, builds trusted tenant,
deployment, subject, and scope context on the server, retrieves only from the
`document` vector space, requires grounding and citations, and disables
writes. Preserve that behavior through the base upgrade.

## What `0.7.0` Adds For LoomAI

LoomAI gains:

- immutable `SpecialistChain` YAML/JSON resources;
- exact manager and worker version references;
- bounded input mapping from chain input or manager objective;
- bounded result projection to summaries, string facts, and approved evidence
  IDs;
- one shared Java/manifest chain registry and gateway;
- durable replay, restart recovery, cancellation, and definition-drift
  protection;
- source-aware runtime status and aggregate hashes;
- offline schema and semantic validation; and
- fail-closed behavior when manifests are invalid, incompatible, or disabled.

LoomAI still owns:

- authentication and authorization;
- tenant, deployment, subject, and scope construction;
- chain selection and idempotency keys;
- source-of-truth reads and business writes;
- confirmations, receipts, reviews, and reconciliation;
- deployment packaging, migrations, secrets, health projection, and rollout.

`0.7.0` does not add arbitrary graphs, recursive chains, nested chains,
write-capable workers, dynamic target discovery, executable YAML, provider or
model selection in manifests, or exactly-once provider invocation.

## Base Upgrade Procedure

### 1. Preserve the working tree and baseline

Before changing LoomAI:

```bash
git status --short --branch
git rev-parse HEAD
```

Record existing user changes and do not reset or overwrite them. Record the
current framework version and current deployment-knowledge tests.

### 2. Verify the immutable release

Verify that the tag resolves to the release commit and that Central resolves
the BOM from an empty cache. A suitable isolated command is:

```bash
EMPTY_REPO="$(mktemp -d)"
mvn -Dmaven.repo.local="$EMPTY_REPO" \
  dependency:get \
  -Dartifact=io.github.loom-ai-labs:ai-fabric-bom:0.7.0:pom
```

Do not use `-DskipTests` or `maven.test.skip` in any migration verification.

### 3. Update every framework-version source together

At minimum, inspect and update:

```text
ai-fabric-product/pom.xml
ai-infrastructure-module/pom.xml
Platfrom/backend/src/main/resources/application.yml
Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/entity/DeploymentVersionEntity.java
Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/DeploymentConfigCompiler.java
```

Also update version assertions and fixtures in:

```text
Platfrom/backend/src/test/java/com/ai/fabric/platform/backend/deployment/entity/DeploymentVersionEntityTest.java
Platfrom/backend/src/test/java/com/ai/fabric/platform/backend/deployment/service/DeploymentConfigCompilerTest.java
Platfrom/backend/src/test/java/com/ai/fabric/platform/backend/deployment/service/DeploymentReleaseVerificationServiceTest.java
Platfrom/backend/src/test/java/com/ai/fabric/platform/backend/deployment/service/RailwayProvisioningPlanServiceTest.java
ai-infrastructure-module/ai-fabric-runtime/src/test/java/com/ai/fabric/runtime/RuntimeAdminOverviewControllerTest.java
```

Search again after editing:

```bash
rg -n '0\.6\.1|AI_FABRIC_FRAMEWORK_VERSION' \
  ai-fabric-product \
  ai-infrastructure-module \
  Platfrom/backend
```

Not every historical `0.6.1` string should change. Preserve immutable old
deployment records, migration evidence, and compatibility-test inputs when
they intentionally describe the old release.

### 4. Keep chain behavior off

The base upgrade must retain:

```yaml
ai:
  execution:
    specialist-chains:
      enabled: false
```

And the generated deployment environment must retain:

```text
AI_EXECUTION_SPECIALIST_CHAINS_ENABLED=false
```

Do not add chain resources, tables, secrets, routes, or product claims to the
base dependency-upgrade commit.

### 5. Run the LoomAI release suites

Run all three independent Maven reactors normally:

```bash
mvn -f ai-fabric-product/pom.xml clean verify
mvn -f ai-infrastructure-module/pom.xml clean verify
mvn -f Platfrom/backend/pom.xml clean verify
```

The base gate must preserve:

- runtime startup with chains disabled;
- deployment-knowledge specialist manifest loading;
- trusted tenant and deployment propagation;
- retrieval filtering and fail-closed post-filtering;
- Data Sync and indexing status;
- chat, RAG, actions, receipts, and reviews;
- generated deployment metadata and version readback; and
- all existing runtime and platform endpoints.

### 6. Prove the packaged artifact

Build LoomAI using a clean Central-only Maven cache. Inspect the packaged
runtime dependency tree or JAR contents and prove that `ai-fabric-execution`
and every other AI Fabric module resolve to exactly `0.7.0`.

Deploy with chains disabled. Verify:

- runtime health is up;
- admin/version output reports `0.7.0` and the immutable LoomAI commit;
- the deployment-knowledge health indicator is up;
- its exact specialist remains registered;
- the direct query endpoint succeeds with valid trusted context;
- missing tenant/deployment context fails;
- a second tenant/deployment cannot retrieve the first boundary's evidence;
  and
- no chain table or chain secret is needed while chain execution is disabled.

## Declarative Chain Adoption

### Product decision before implementation

Do not invent a second specialist merely to produce a parallel screenshot.
The current `deployment-knowledge-specialist@1` is one useful grounded worker,
not an existing chain.

The first declarative canary may be a one-worker mechanics canary when the goal
is only to prove packaging, trusted context, durable state, and replay. Its
baseline is the existing direct specialist endpoint, not a Java chain.

A product-worthy multi-specialist chain should wait until LoomAI has a second
distinct read-only responsibility, for example:

- `deployment-knowledge-specialist@1`: indexed deployment evidence; and
- `deployment-runtime-state-reader@1`: authoritative current deployment,
  provider, index, or runtime status.

The manager may choose one or both. Parallel execution is valid only when the
questions are independent. If authoritative state requires application code,
keep that read in an application-owned specialist or action. The manifest may
define the topology only when its JSON mapping and bounded projection are
sufficient.

### Exact-ID strategy

Preserve the direct specialist:

```text
deployment-knowledge-specialist@1
```

Use new exact IDs for chain components, for example:

```text
deployment-knowledge-chain-request@1
deployment-knowledge-chain-directive@1
deployment-knowledge-chain-manager@1
deployment-knowledge-declarative@1
```

Never reuse an exact ID after executable semantics change. Publish a new
version instead.

### Resource discovery

LoomAI currently scans only `ai-specialists`. Either package the chain in that
reviewed bundle or add an explicit immutable location:

```yaml
ai:
  execution:
    manifests:
      enabled: true
      fail-fast: true
      locations:
        - classpath*:ai-specialists/*.yml
        - classpath*:ai-specialists/*.yaml
        - classpath*:ai-specialists/*.json
        - classpath*:ai-chains/*.yml
        - classpath*:ai-chains/*.yaml
        - classpath*:ai-chains/*.json
```

If a chain resource is discovered while chain execution is disabled,
fail-fast mode rejects startup. Do not package the canary resource in the base
upgrade image before the canary profile is ready.

### Deployment-local database migration

The chain table belongs to each deployed LoomAI runtime because it stores that
runtime's protected execution checkpoints. Do not place it in the central
Platform backend database.

Create a reviewed runtime Flyway/Liquibase migration for
`ai_specialist_chain_execution` using the schema in
`BOUNDED_MULTI_SPECIALIST_CHAINS.md`. Apply the migration while chains remain
disabled, then configure production with:

```yaml
ai:
  execution:
    specialist-chains:
      initialize-schema: false
```

The table is execution state, not a business reporting model. Do not query its
protected columns from product analytics.

### Secrets and runtime limits

Configure two different, stable, private values of at least 32 characters:

```text
AI_SPECIALIST_CHAIN_ENCRYPTION_SECRET
AI_SPECIALIST_CHAIN_FINGERPRINT_SECRET
```

They must survive restarts and replacement deployments. Do not expose them in
Git, generated manifests, browser configuration, health, logs, or prompts.

Start the canary with conservative limits and durable storage:

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

### Backend ownership

LoomAI backend code chooses the exact chain. It must construct
`TrustedExecutionContext` from the verified runtime identity exactly as the
existing deployment-knowledge service does. Requests must not choose or
override identity, tenant, deployment, subject, scopes, manager, workers,
topology, provider, model, vector space, evidence, prompt, or secrets.

Use a stable, scoped idempotency key. The same key and same protected request
must replay the same execution. The same key with a changed request must
conflict.

### Health and operations

Add a LoomAI-owned health projection backed by
`SpecialistChainManifestRuntimeStatus`. It may safely expose:

- whether manifests and chain execution are enabled;
- readiness;
- Java, discovered, inactive, manifest-defined, and total counts;
- aggregate audit, declarative-semantics, and effective-execution hashes;
- bounded diagnostics; and
- exact registered chain IDs with source and safe hashes.

Do not expose prompt text, schemas, full manifests, trusted context, protected
requests/checkpoints/results, credentials, or raw worker output.

## Mandatory Canary Matrix

### Mechanics canary

- manifest discovered and registered as `MANIFEST`;
- no-worker completion when the manager can answer without delegation;
- one-worker invocation of the existing deployment-knowledge specialist;
- insufficient evidence remains visible;
- malformed manager output remains visible;
- exact replay does not call the manager or worker again;
- changed payload with the same idempotency key conflicts;
- cancellation and deadline are enforced;
- restart resumes/replays without repeating a completed worker;
- changed prompt/schema/target/mapping/projection/limit rejects protected
  replay; and
- descriptive metadata changes only the audit identity.

### Security canary

Use at least two tenants and two deployments:

- Tenant A / Deployment A evidence cannot appear in Tenant B / Deployment B;
- missing tenant or deployment is denied;
- caller-supplied identity/scope/topology fields are stripped or rejected;
- manager text cannot widen worker authority;
- invented targets are denied;
- unapproved evidence is not projected; and
- protected database state and secrets never appear in API, logs, or health.

### Product chain gate

Only after adding a genuinely distinct second worker, test:

- manager selects the knowledge worker alone;
- manager selects the live-state worker alone;
- manager selects both only for a question needing both;
- independent workers may run in bounded parallel;
- required worker, mapping, projection, grounding, or provider failure fails
  the chain rather than producing a fallback answer; and
- the final answer cites only approved projected evidence.

## Drain Rule From `0.6.1`

`0.7.0` preserves chain target declaration order in the chain content hash;
`0.6.1` sorted target IDs while hashing. Before replacing any chain-enabled
`0.6.1` runtime, stop submissions and drain or cancel every non-terminal row.
Never rewrite stored hashes.

LoomAI currently disables chains and appears not to own the chain table, so
the expected inventory is zero or table-not-present. Verify that fact in each
environment instead of assuming it.

## Rollout Order

1. Release LoomAI with all framework dependencies at `0.7.0` and chains off.
2. Verify Central-only build, package contents, deployed version, regression
   suite, direct specialist, and two-tenant isolation.
3. Add the runtime-owned chain migration; keep chains off.
4. Add secrets and the immutable canary resource to one internal profile.
5. Enable the exact canary route only for internal traffic.
6. Record deterministic, JDBC restart, isolation, packaged-runtime, and real
   provider evidence.
7. Promote gradually while retaining the direct specialist endpoint.
8. Design a second worker only from a real product responsibility.

## Rollback

### Base upgrade rollback

If `0.7.0` breaks existing LoomAI behavior while chains are disabled, stop
promotion and restore the previous LoomAI artifact/version configuration. Do
not mutate historical deployment records.

### Canary rollback

1. Stop new declarative submissions.
2. Drain or cancel active chain executions.
3. Route traffic to the existing direct deployment-knowledge endpoint.
4. Disable chain execution in the next immutable deployment.
5. Remove the chain resource from that deployment package.
6. Retain chain storage and secrets through the required replay/audit
   retention period.

Do not hot-edit an active exact resource or overwrite stored hashes.

## Completion Evidence

The LoomAI migration is complete only when its release record contains:

- changed files and immutable LoomAI commit;
- Central-only dependency proof for `0.7.0`;
- product, product-services, and Platform test totals;
- packaged dependency/version evidence;
- deployed health and exact commit evidence;
- direct deployment-knowledge success and two-boundary isolation evidence;
- confirmation that base rollout kept chains disabled;
- chain table migration and secret names, without values, if canary is used;
- exact schema, manager, worker, and chain IDs;
- runtime source/hash status;
- replay, conflict, restart, cancellation, drift, and isolation evidence;
- a real-provider canary with no fallback; and
- drain and rollback status.

Use the companion
[migration runbook](../Framework-Dev-Guides/application-patterns/LOOMAI_AI_FABRIC_0_7_DECLARATIVE_CHAIN_MIGRATION_RUNBOOK.md)
for the operator checklist and the
[adoption prompt](../Framework-Dev-Guides/developer-workflows/LOOMAI_AI_FABRIC_0_7_DECLARATIVE_CHAIN_ADOPTION_PROMPT.md)
for a coding-assistant implementation session.
