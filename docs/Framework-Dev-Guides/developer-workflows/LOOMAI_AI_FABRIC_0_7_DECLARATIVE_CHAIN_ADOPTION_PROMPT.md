# LoomAI AI Fabric 0.7 Declarative Chain Adoption Prompt

Use this prompt in a coding-assistant session rooted at the LoomAI repository.
The assistant must inspect the actual platform code before changing it.

```text
Adopt AI Fabric 0.7.0 declarative bounded specialist chains in LoomAI as one
reviewed, read-only canary. Do not invent a second orchestration engine or a
LoomAI-private chain DSL.

Read first:
- AI Fabric 0.7.0 release notes.
- LOOMAI_AI_FABRIC_0_7_DECLARATIVE_CHAIN_MIGRATION_RUNBOOK.md.
- SPECIALIST_MANIFEST_AUTHORING_GUIDE.md.
- BOUNDED_MULTI_SPECIALIST_CHAINS.md.
- The current LoomAI AI Fabric BOM/version configuration.
- Current specialist manifests, Java chain definitions, trusted-context
  construction, chain endpoints, Flyway/Liquibase migrations, health output,
  and tests.

Hard boundaries:
1. Use only published AI Fabric 0.7.0 Maven Central artifacts. Do not depend on
   a framework source checkout or locally installed release artifact.
2. Preserve LoomAI's current identity, tenant, deployment, subject, scopes,
   datasource, chat, receipt, review, write, and reconciliation ownership.
3. The browser/API request must not choose chain, manager, worker, topology,
   provider, prompt, vector space, action scope, evidence, identity, or
   authority.
4. Keep every chain worker exact-version, read-only, non-interactive, and
   schema-backed. Workers remain leaves.
5. Chain YAML must not contain Java class names, Spring bean names, adapter,
   mapper, projector, expression, script, SQL, URL, provider, model,
   credential, identity, tenant, deployment, subject, or scope fields.
6. Do not add text-matching routing or deterministic fallback intelligence.
   Provider, mapping, projection, grounding, persistence, and required-branch
   failures must stay visible.
7. Run tests normally. Never use -DskipTests or maven.test.skip.
8. Do not remove or silently alter existing LoomAI functionality.

Execution sequence:

A. Baseline and drain
- Record git branch, commit, dirty files, current AI Fabric version, and Java
  chain IDs.
- Inspect ai_specialist_chain_execution for non-terminal 0.6.1 work.
- Document the drain/cancel requirement caused by 0.7.0 ordered-target hash
  identity. Do not rewrite stored hashes.
- Upgrade the BOM to 0.7.0 only after Maven Central and tag verification.
- Compile and run the full existing LoomAI regression suite with declarative
  resources absent.

B. Choose a canary
- Select one deployment-knowledge or diagnostics chain with one manager and no
  more than two existing read-only workers.
- Keep the current Java chain and route.
- Add a separate exact manifest ID such as
  deployment-knowledge-declarative@1.
- Do not duplicate an exact Java chain ID.

C. Author immutable resources
- Define or reuse one exact chain input SpecialistSchema.
- Reuse exact manager and worker Specialist manifests only when their
  contracts satisfy the chain rules.
- Add one apiVersion ai.fabric/v1, kind SpecialistChain resource.
- Use JSON_POINTER_MAP with CHAIN_INPUT and MANAGER_OBJECTIVE only.
- Map into fresh top-level worker fields and validate against the exact worker
  input schemas.
- Use BOUNDED_FACT_PROJECTION with one summary, bounded string facts, and NONE
  or ALL_APPROVED evidence references.
- Make all transition flags explicit and keep limits below deployment
  ceilings.
- Use a new exact version whenever execution semantics change.

D. Validate before runtime
- Validate every YAML/JSON document with the packaged
  META-INF/ai-fabric/specialist-resource-v1.schema.json.
- Compile with SpecialistChainManifestValidator using the runtime compilation
  inventory.
- Persist or compare the declarativeSemanticsHash for every published exact
  chain ID and reject exact-version semantic reuse.
- Assert there are no class/bean/mapper/projector references.

E. Configure production boundaries
- Configure ai.execution.manifests with fail-fast=true and immutable classpath
  or reviewed mounted locations.
- Enable ai.execution.specialist-chains with durable-enabled=true,
  allow-ephemeral=false, and initialize-schema=false.
- Reuse the reviewed ai_specialist_chain_execution migration.
- Supply stable, distinct private encryption and fingerprint secrets of at
  least 32 characters.
- Set explicit chain, mapping, pointer, copied-node, byte, and total-work
  ceilings from the migration runbook.

F. Wire the backend
- The backend chooses the exact declarative chain ID.
- Build TrustedExecutionContext only from authenticated server state.
- Build the schema-valid chain input from user/domain data without copying
  authority into it.
- Preserve backend-owned ConversationBinding and newest-message-only UI
  behavior where conversation policy is REQUIRED or OPTIONAL.
- Expose sync or async submit/status/cancel through the existing
  SpecialistChainGateway.
- Preserve stable, scoped idempotency keys and exact replay.

G. Expose honest operations
- Add safe chain source and hash information to health from
  SpecialistChainManifestRuntimeStatus and SpecialistChainRegistry.
- Prove source=MANIFEST, exact manager/targets, discovered=registered counts,
  readiness, audit hash, semantics hash, and effective registry hash.
- Do not expose manifests, prompts, schemas, protected payloads, raw worker
  output, credentials, or trusted context.

H. Test
- Add parser/schema/unknown-field/size/duplicate/exact-ID tests.
- Add mapping tests for scalar/object/array copy, no coercion, no mutation,
  missing required data, bad destination, pointer/depth/count/byte/work limits,
  and final worker schema mismatch.
- Add projection tests for summary, string facts, approved evidence only, raw
  result exclusion, and fail-closed errors.
- Add startup tests for missing/incompatible manager/worker/schema, write or
  interactive workers, invalid transitions/limits, disabled feature behavior,
  and duplicate Java/manifest IDs.
- Add chain behavior tests for no worker, one worker, adaptive sequential,
  parallel ALL_REQUIRED, clarification, terminal handoff if enabled, invented
  target denial, grounding attribution, deadline, and cancellation.
- Add JDBC restart, exact replay, changed payload, definition drift, and
  cross-owner/tenant/deployment/subject tests.
- Run a real OpenAI canary with no fallback and verify usage/lineage.

I. Compare and promote
- Keep Java and declarative routes side by side.
- Compare exact worker selection, bounded facts, evidence IDs, failures,
  lineage, latency, and provider calls.
- Switch only canary traffic after all gates pass.
- Keep a documented drain and rollback procedure.

Before finishing, provide:
- every changed file;
- exact chain, manager, worker, and schema IDs;
- Java-versus-manifest boundary rationale;
- configuration and secret names without values;
- migration/drain/rollback notes;
- deterministic, JDBC, isolation, packaged-runtime, and keyed-provider test
  results;
- deployed version/commit/hash evidence; and
- any remaining manual or post-publication gate.
```
