# LoomAI AI Fabric 0.7 Adoption Prompt

Use this prompt in a coding-assistant session rooted at the private LoomAI
repository. It covers the base `0.7.0` upgrade and the optional declarative
chain canary as separate reviewed changes.

```text
Migrate LoomAI from published AI Fabric 0.6.1 artifacts to published AI Fabric
0.7.0 artifacts, then prepare and prove one optional declarative bounded
specialist-chain canary. Do not introduce a second execution engine, a
LoomAI-private chain DSL, compatibility shims, or fallback intelligence.

Read first:
- docs/release-notes/0.7.0.md in the AI Fabric repository.
- docs/release-notes/LOOMAI_PLATFORM_AI_FABRIC_0_7_0_UPGRADE_NOTES.md.
- LOOMAI_AI_FABRIC_0_7_DECLARATIVE_CHAIN_MIGRATION_RUNBOOK.md.
- SPECIALIST_MANIFEST_AUTHORING_GUIDE.md.
- BOUNDED_MULTI_SPECIALIST_CHAINS.md.
- The LoomAI platform and framework philosophy documents.
- The LoomAI operating context and previous 0.6.1 migration evidence.

Establish the real baseline before editing:
- Record branch, commit, and dirty files. Preserve all existing user work.
- Confirm ai-fabric-product/pom.xml pins 0.6.1.
- Confirm ai-infrastructure-module/pom.xml pins 0.6.1.
- Confirm the Platform backend default and DeploymentVersionEntity use 0.6.1.
- Confirm RailwayProvisioningPlanService emits
  AI_EXECUTION_SPECIALIST_CHAINS_ENABLED=false.
- Inspect all version assertions and generated deployment fixtures.
- Confirm the runtime directly registers deployment-knowledge-specialist@1
  from ai-specialists/deployment-knowledge-specialist.yml.
- Confirm POST /api/specialists/deployment-knowledge/query is the current
  baseline route.
- Confirm the service constructs tenant, deployment, subject, and scopes only
  from RuntimeResolvedIdentity and TrustedExecutionContext.
- Confirm the runtime currently scans only classpath*:ai-specialists/*.
- Confirm no deployment-runtime migration for
  ai_specialist_chain_execution exists.
- Inspect each environment for an existing chain table and non-terminal rows;
  record the result rather than assuming it is empty.

Hard boundaries:
1. Consume only io.github.loom-ai-labs AI Fabric 0.7.0 artifacts from Maven
   Central. Release builds must not clone the framework source or use a local
   Maven install as evidence.
2. Run tests normally. Never use -DskipTests or maven.test.skip.
3. Preserve LoomAI ownership of identity, authorization, tenant, deployment,
   subject, scopes, datasource, vector evidence, chat, writes, confirmations,
   receipts, reviews, reconciliation, deployment, and rollback.
4. The caller must not choose or override chain, manager, worker, topology,
   provider, model, prompt, vector space, evidence, credentials, identity, or
   authority.
5. Every chain worker must be exact-version, read-only, non-interactive, and
   JSON Schema-backed. Workers remain leaves.
6. Chain YAML must not contain Java class names, Spring bean names, adapters,
   mappers, projectors, expressions, scripts, SQL, URLs, providers, models,
   credentials, identity, tenants, deployments, subjects, or scopes.
7. Do not add text-matching routing or deterministic fallback intelligence.
   Provider, mapping, projection, grounding, persistence, and required-branch
   failures must remain visible.
8. Do not remove or silently alter existing LoomAI functionality.
9. Do not pretend LoomAI already has a Java deployment-knowledge chain. Its
   current baseline is a direct specialist endpoint.
10. Do not invent a meaningless second specialist merely to claim parallel
    execution.

Implement in four gates. Keep each gate reviewable and releasable.

GATE A: BASE 0.7.0 DEPENDENCY UPGRADE

A1. Verify release provenance.
- Verify tag ai-fabric-framework-v0.7.0 resolves to commit
  5b075b66384dc5b756b3b3dd12efaf896ce9a50b.
- Resolve io.github.loom-ai-labs:ai-fabric-bom:0.7.0:pom with a new empty
  Maven local repository.

A2. Handle the identity boundary.
- Inspect ai_specialist_chain_execution in every target environment.
- If non-terminal 0.6.1 work exists, stop submissions and drain or cancel it
  under 0.6.1.
- Never rewrite stored hashes or relabel checkpoints.
- LoomAI currently disables chains, so zero/table-not-present is expected but
  must be verified.

A3. Update all current release-version sources together.
- ai-fabric-product/pom.xml
- ai-infrastructure-module/pom.xml
- Platfrom/backend/src/main/resources/application.yml
- DeploymentVersionEntity default for new versions
- DeploymentConfigCompiler default
- every corresponding unit/integration fixture and assertion
- generated deployment version/readback expectations
- operating context only after the migration evidence is true

Preserve immutable historical deployment records and documents that
intentionally describe 0.6.1.

A4. Keep chain execution disabled.
- Keep AI_EXECUTION_SPECIALIST_CHAINS_ENABLED=false in generated environments.
- Keep ai.execution.specialist-chains.enabled=false in runtime templates.
- Package no SpecialistChain resource.
- Add no chain table, secret, route, or product claim in this commit.

A5. Verify all reactors.
- mvn -f ai-fabric-product/pom.xml clean verify
- mvn -f ai-infrastructure-module/pom.xml clean verify
- mvn -f Platfrom/backend/pom.xml clean verify

A6. Prove the packaged release.
- Rebuild with a clean Central-only Maven cache.
- Prove every packaged AI Fabric module is exactly 0.7.0.
- Deploy an immutable LoomAI commit with chains disabled.
- Verify runtime and Platform health/version readback.
- Verify deployment-knowledge-specialist@1 is healthy and registered.
- Run a successful direct query and insufficient-evidence case.
- Run missing-boundary and two-tenant/two-deployment isolation canaries.
- Verify Data Sync, indexing status, chat, RAG, actions, receipts, reviews,
  generated deployment metadata, and existing endpoints.

Stop after Gate A and report evidence. Do not enable the canary until Gate A
is accepted.

GATE B: DURABLE SCHEMA PREPARATION

B1. Add an application-owned migration for ai_specialist_chain_execution to
the deployment-local runtime database, not the central Platform database.
- Use the reviewed AI Fabric 0.7 schema.
- Validate SQL types against the production database.
- Keep initialize-schema=false in production.
- Apply and verify the migration while chain execution remains disabled.

B2. Do not use the protected table as a business/reporting model and do not
log or expose protected request, checkpoint, or result columns.

Stop after Gate B and report migration evidence.

GATE C: DECLARATIVE MECHANICS CANARY

C1. Preserve the baseline.
- Keep deployment-knowledge-specialist@1 and its direct endpoint unchanged.
- Add a separate exact chain ID such as
  deployment-knowledge-declarative@1.
- Never duplicate a Java and manifest exact chain ID.

C2. Use an honest one-worker canary first.
- Reuse deployment-knowledge-specialist@1 as the real read-only grounded
  worker when its current schema contract is sufficient.
- Add exact chain request and manager-directive schemas plus an exact
  read-only manager specialist.
- Do not add a fake second worker.
- If the one-worker shape has no useful user behavior, keep it as an internal
  mechanics canary only and do not market it as a multi-specialist product.

C3. Author immutable resources.
- Use apiVersion ai.fabric/v1 and kind SpecialistChain.
- Use exact name@version references.
- Use JSON_POINTER_MAP only from CHAIN_INPUT or MANAGER_OBJECTIVE into fresh
  top-level fields.
- Validate the complete worker input against its exact schema.
- Use BOUNDED_FACT_PROJECTION for one summary, bounded string facts, and only
  approved evidence IDs.
- Make transition flags and limits explicit and below deployment ceilings.
- Publish a new exact version whenever executable semantics change.

C4. Validate offline.
- Validate every resource with packaged
  META-INF/ai-fabric/specialist-resource-v1.schema.json.
- Compile it with SpecialistChainManifestValidator and the runtime's actual
  manager/worker/schema inventory.
- Persist or compare declarativeSemanticsHash for every published exact chain
  ID and reject exact-version semantic reuse.
- Assert there are no class, bean, mapper, projector, executable, provider,
  credential, or trusted-context references.

C5. Configure discovery.
- Keep existing ai-specialists locations.
- Add classpath*:ai-chains/* only if the chain is packaged separately.
- Keep manifest fail-fast true.
- Do not package a chain in profiles where chain execution remains disabled.

C6. Configure durable runtime boundaries.
- Set durable-enabled=true, allow-ephemeral=false, initialize-schema=false.
- Supply stable and distinct AI_SPECIALIST_CHAIN_ENCRYPTION_SECRET and
  AI_SPECIALIST_CHAIN_FINGERPRINT_SECRET values of at least 32 characters
  through the deployment secret store.
- Never print secret values.
- Configure explicit duration, decision, worker, parallel, invocation,
  projection, JSON Pointer, mapping, copied-node, byte, work, lease, recovery,
  retry, cleanup, and retention ceilings from the migration guide.
- Enable AI_EXECUTION_SPECIALIST_CHAINS_ENABLED=true only for the internal
  canary deployment profile.

C7. Wire the LoomAI route.
- Backend code chooses deployment-knowledge-declarative@1.
- Construct TrustedExecutionContext only from verified server identity.
- Build schema-valid domain input without copying authority into it.
- Use a stable scope-aware idempotency key and bounded deadline.
- Expose submit/status/cancel through public SpecialistChainGateway APIs.
- Preserve newest-message-only browser behavior if conversation policy is
  enabled; backend owns conversation history and binding.

C8. Add honest health.
- Project safe data from SpecialistChainManifestRuntimeStatus.
- Report enabled flags, readiness, counts, aggregate hashes, bounded
  diagnostics, exact ID, source=MANIFEST, manager, and target IDs.
- Do not expose manifest bodies, prompt text, schemas, trusted context,
  protected state, credentials, or raw worker results.

C9. Test and prove.
- Parser/schema/unknown-field/size/duplicate/exact-ID tests.
- Missing/incompatible manager, worker, schema, transition, and limits tests.
- No-worker and one-worker execution.
- Mapping scalar/object/array copy, no coercion/mutation, missing required
  value, invalid destination, pointer/depth/count/byte/work ceilings, and
  final worker-schema mismatch.
- Projection summary/string facts/approved evidence only, raw result
  exclusion, and fail-closed projection error.
- Manager/provider/grounding failure with no fallback answer.
- Exact replay without repeated manager/worker/provider invocation.
- Changed payload under the same key conflict.
- JDBC restart after persisted projection without repeating completed work.
- Cancellation, deadline, definition drift, and descriptive-only metadata
  identity behavior.
- Cross-owner, tenant, deployment, and subject denial.
- Packaged runtime using only published 0.7.0 artifacts.
- Real OpenAI canary with no deterministic fallback and recorded usage and
  lineage.

Stop after Gate C and report evidence before general enablement.

GATE D: PRODUCT-WORTHY MULTI-SPECIALIST CHAIN

D1. Introduce a second worker only for a distinct responsibility. A suitable
future split may be:
- deployment-knowledge-specialist@1 for indexed grounded evidence; and
- deployment-runtime-state-reader@1 for authoritative current runtime,
  provider, deployment, or indexing status.

D2. Keep authoritative reads/application invariants in LoomAI-owned code. Use
a manifest topology only if bounded JSON mapping and projection are enough.
Otherwise retain the relevant chain or adapter in Java.

D3. Prove manager selection of each worker alone and both only when required.
Parallel execution is allowed only for independent work. Required branch
failure must fail the chain rather than create a partial fallback answer.

D4. Compare the direct route and canary for selected workers, facts, approved
evidence IDs, failures, lineage, latency, and provider calls. Promote traffic
gradually and retain rollback.

Before finishing each gate, provide:
- every changed file;
- exact framework and LoomAI commits;
- exact schema, manager, worker, specialist, and chain IDs;
- Java-versus-manifest ownership rationale;
- configuration and secret names without values;
- drain, migration, rollout, and rollback notes;
- deterministic, JDBC, isolation, packaged-runtime, and real-provider test
  results with totals;
- deployed version/commit/source/hash evidence;
- confirmation that no fallback hid a provider or chain failure; and
- every remaining manual or post-deployment gate.
```
