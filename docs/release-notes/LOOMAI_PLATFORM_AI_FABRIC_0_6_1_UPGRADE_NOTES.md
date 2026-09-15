# LoomAI Platform Upgrade Notes: AI Fabric `0.6.1`

- **Audience:** LoomAI platform maintainers and deployment owners
- **Observed platform baseline:** AI Fabric `0.5.2`
- **Target release:** AI Fabric `0.6.1`
- **Upgrade path:** direct `0.5.2 -> 0.6.1`
- **Release date:** 2026-09-14
- **Java:** 21
- **Spring Boot:** 4.1.x
- **Maven group:** `io.github.loom-ai-labs`
- **Release tag:** `ai-fabric-framework-v0.6.1`
- **Release commit:** `bf6d19eed5ed0a8d8085db7cc02e0505e9973e65`
- **GitHub release:**
  <https://github.com/Loom-AI-Labs/ai-fabric-framework/releases/tag/ai-fabric-framework-v0.6.1>

## Upgrade Verdict

LoomAI should upgrade directly from AI Fabric `0.5.2` to `0.6.1`. Do not
deploy `0.6.0` as an intermediate step. `0.6.1` contains the complete bounded
multi-specialist chain capability plus the manifest-identity and structured
manager hardening required for its production use.

The rollout has two deliberately separate decisions:

1. **Required platform dependency upgrade:** move all AI Fabric artifacts to
   `0.6.1`, keep specialist chains disabled, and regress every existing LoomAI
   capability.
2. **Optional capability adoption:** provision durable chain state and enable
   one read-only LoomAI canary chain after the base upgrade is accepted.

Existing chat, RAG, indexing, Data Sync, actions, receipts, reviews, direct
specialists, fixed plans, delegation, handoff, and conversation-manager flows
do not need to be rebuilt as chains. Chains are disabled by default and are
appropriate only when the useful specialists depend on request meaning or an
earlier specialist result.

## Current LoomAI Evidence

At preparation time, the LoomAI platform source declares:

- `ai-fabric.framework.version=0.5.2`;
- Java 21 and Spring Boot `4.1.0`, which already match the `0.6.1` baseline;
- an explicit `ai-fabric-execution` dependency;
- one manifest-defined deployment-knowledge specialist;
- process-local async execution in the default profile; and
- receipts, reviews, input waits, plans, and conversation managers disabled in
  the default profile.

These observations reduce migration complexity, but deployed environment
overrides and persisted production state remain authoritative. Inventory those
before deployment.

## Platform Changes Since `0.5.2`

### Inherited `0.5.3` hardening

The direct upgrade also includes all `0.5.3` corrections:

- trusted tenant and deployment identity now remain canonical RAG filters,
  even when response context or metadata is not requested;
- caller-supplied identity filters cannot weaken authenticated retrieval
  boundaries;
- Lucene searchers are leased during reads, preventing refresh or shutdown
  from closing an active reader;
- MCP actions bind `serverRef` to the exact remote server name or title and
  reject oversized results before projection or model context;
- backend-owned nested read-action parameters are resolved before required
  parameter validation; and
- specialists may ground against any approved requestable read action.

Most of these are transparent corrections. They can expose code that depended
on spoofable retrieval metadata or ambiguous MCP server selection, which is
intended fail-closed behavior.

### Bounded multi-specialist chains

AI Fabric `0.6.x` adds an opt-in execution model in `ai-fabric-execution` where
one application-selected manager can:

- complete without a worker;
- ask one bounded clarification question;
- invoke one exact-version specialist;
- invoke an explicitly independent bounded worker group in parallel;
- inspect application-approved projections and choose a second worker;
- synthesize one structurally attributed answer; or
- finish through one approved terminal read-only handoff.

Java remains the transition authority. The manager receives a closed target
catalog and returns one strict directive. Every worker is independently
authorized and remains a read-only, non-interactive leaf.

This release also provides:

- typed chain inputs, worker input mappers, and bounded result projectors;
- synchronous execute plus asynchronous submit, status, result, and cancel;
- deterministic decision, worker, parallelism, deadline, and output limits;
- safe execution lineage and Micrometer metrics;
- JDBC checkpoints with encrypted payloads, keyed fingerprints, leases,
  recovery, retention, and exact replay; and
- backend-owned conversation coordination where the browser sends only the
  newest message.

### `0.6.1` production hardening

`0.6.1` changes a manifest-defined specialist's effective identity to include
the resolved prompt profile, input schema, and output schema as well as the
manifest itself. A prompt-only or schema-only deployment change now changes
the specialist hash and prevents old protected work from resuming under new
behavior.

The shared structured-output finalizer also makes the manager boundary more
explicit: application input can describe work but cannot grant identity,
authority, tools, targets, evidence, or capabilities. Invalid manager shape
may receive one bounded correction under the default attempt budget; policy,
catalog, schema, authorization, and grounding checks remain authoritative.

There is no keyword router or canned-answer fallback. Exhausted provider or
validation failures remain visible.

## Required Base Upgrade

### 1. Update the BOM

Change the LoomAI parent property from `0.5.2` to `0.6.1`:

```xml
<ai-fabric.framework.version>0.6.1</ai-fabric.framework.version>
```

Continue importing one BOM and omit versions from individual AI Fabric
dependencies:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.loom-ai-labs</groupId>
      <artifactId>ai-fabric-bom</artifactId>
      <version>${ai-fabric.framework.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

LoomAI already declares `ai-fabric-execution`; no new framework module is
required for the base upgrade.

### 2. Keep chains disabled for the first deployment

Make the initial rollout state explicit:

```yaml
ai:
  execution:
    output-finalization:
      max-attempts: 1
    specialist-chains:
      enabled: false
```

AI Fabric `0.6.1` defaults structured-output finalization to two total
generation attempts. Starting the platform upgrade at `1` preserves the prior
single-generation cost and failure profile. LoomAI may deliberately adopt `2`
after testing malformed and validator-rejected output; this is a product and
provider-cost decision, not a hidden retry.

`output-finalization.max-attempts` is unrelated to durable chain recovery
attempts.

### 3. Inventory manifest-backed work

Every manifest-defined specialist receives a new effective hash under
`0.6.1`. Before replacing a deployed runtime, inventory non-terminal or
retained work linked to manifests:

- async specialist jobs;
- confirmable action receipts;
- human-review tasks; and
- any experimental `0.6.0` chain executions.

Drain or explicitly cancel active work where possible. Do not rewrite stored
hashes or relabel old state. If definition drift fails closed after deployment,
reauthorize and create a new operation under the `0.6.1` identity.

The checked default LoomAI profile uses in-memory async execution and disables
receipts and reviews, but production overrides must be checked independently.

### 4. Review corrected boundaries

- Ensure authenticated tenant and deployment values, rather than request
  metadata, are the intended RAG authority.
- If LoomAI introduces MCP actions, ensure every `serverRef` equals the remote
  server's reported name or title and review `responseMapping.maxCharacters`.
- Do not compensate for the stricter behavior by copying browser identity into
  trusted metadata.

### 5. Build and deploy without test skipping

At minimum, run the platform reactor from a clean checkout:

```bash
mvn -f ai-infrastructure-module/pom.xml clean verify
```

Then deploy with chains disabled and verify existing indexing, Data Sync,
retrieval, chat memory, provider routing, action, confirmation, specialist, and
deployment-knowledge canaries. Record the resolved AI Fabric version and the
immutable LoomAI source commit in health output.

No chain table or chain secret is required while chains are disabled.

## Optional Chain Adoption

### Recommended first LoomAI proof

Use a read-only deployment investigation as the first canary. A possible
closed topology is:

```text
deployment-investigation-manager@1
  -> deployment-health-reader@1
  -> configuration-evidence-reader@1
```

The manager may choose neither, either, sequential use, or an explicitly
independent parallel call. Existing direct deployment-knowledge execution must
remain available until the canary is approved.

Do not put deployment mutations, configuration writes, secret changes,
rollback execution, action confirmation, or review approval inside a chain
worker. Continue to use the existing governed state machines for those tasks.

### Application contracts

Register exact-version manager and worker specialists, then one Java
`SpecialistChainDefinition`. The application must own:

- the chain input adapter;
- one typed input mapper per target;
- one bounded safe result projector per target;
- exact component IDs and versions;
- target eligibility for delegation, parallel execution, and terminal handoff;
- conversation policy; and
- limits narrower than or equal to deployment ceilings.

The manager manifest must return the exact five-field
`SpecialistChainDirective` JSON shape and enumerate only registered targets.
LoomAI must not create a separate YAML chain format for `0.6.x`.

### Trusted context

Backend code selects the chain and derives:

- authenticated principal and subject;
- tenant and deployment;
- scopes and evidence boundaries;
- conversation binding; and
- a stable, scoped idempotency key.

Strip or reject authority-like request fields such as `tenantId`,
`deploymentId`, `subjectId`, `scopes`, `targetSpecialist`, `manager`,
`vectorSpace`, `evidenceIds`, and provider configuration. Browser input is
business data, never authority.

### Durable database state

Production chain execution requires a `DataSource`, Spring JDBC, and the
`ai_specialist_chain_execution` table. LoomAI already carries a JDBC-capable
Spring data stack, but must still own and test the production migration.

Use the PostgreSQL-compatible schema in
[`BOUNDED_MULTI_SPECIALIST_CHAINS.md`](../Framework-Dev-Guides/application-patterns/BOUNDED_MULTI_SPECIALIST_CHAINS.md#configuration)
as the reviewed migration source. Do not use framework schema initialization
in production and do not query protected payload columns for business
reporting.

Provision two different stable secrets of at least 32 characters:

```text
AI_SPECIALIST_CHAIN_ENCRYPTION_SECRET
AI_SPECIALIST_CHAIN_FINGERPRINT_SECRET
```

Keep them stable across replicas, restarts, and rollback windows. Store them
with normal LoomAI secret controls and never expose them through health,
manifests, prompts, logs, deployment templates, or browser configuration.

### Production-oriented configuration

```yaml
ai:
  execution:
    output-finalization:
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

Use lower per-chain limits where possible. Ephemeral mode is only for explicit
local development and requires both `durable-enabled=false` and
`allow-ephemeral=true`.

### Platform API and operations

LoomAI may expose asynchronous submission, access-scoped status/result lookup,
and cancellation through `SpecialistChainGateway`. Public responses may show:

- execution and chain IDs;
- queued, running, and terminal status;
- exact selected specialists and sequential/parallel grouping;
- approved evidence-reference IDs;
- safe failure codes;
- durable and replay indicators; and
- AI Fabric and application release identity.

Do not expose manager chain-of-thought, raw provider responses, encrypted
state, trusted context, unrestricted evidence, or unprojected worker output.

## Required Platform Canary Matrix

Run these cases before enabling a LoomAI chain for user traffic:

| Case | Required proof |
| --- | --- |
| No worker | Manager completes without invoking an unavailable or irrelevant worker. |
| One worker | Only the relevant exact-version specialist runs. |
| Sequential | The second worker is selected only after an approved first projection. |
| Parallel | Only explicitly independent workers run together; all required branches must pass. |
| Clarification | Missing typed business input produces one useful question and no worker call. |
| Invented target | Unregistered or unapproved target is denied without fallback. |
| Tenant spoofing | Request fields cannot alter tenant, deployment, subject, scope, or evidence boundaries. |
| Required branch failure | No final success answer is synthesized. |
| Exact replay | Same payload and scoped key return identical result and lineage without another worker call. |
| Changed payload | Same key with different work returns an idempotency conflict. |
| Restart | Completed boundaries are not repeated after process/database restart. |
| In-flight uncertainty | An interrupted provider operation is reported, not rerun blindly. |
| Current authorization | Worker authority is re-evaluated during recovery. |
| Cancellation/deadline | Late output is discarded and terminal state is visible. |
| Retention | Cleanup removes only eligible terminal rows and preserves active work. |
| Structured correction | Every model attempt is visible in usage evidence; exhaustion fails without fallback. |
| Logs/API | No secret, raw identity, request, provider payload, or protected evidence leaks. |

Use a real provider key only through LoomAI's private keyed CI or deployment
profile. A missing or failing provider must remain visible.

## Rollout Sequence

1. Record the current LoomAI commit, `0.5.2` test state, deployed configuration,
   and durable specialist work.
2. Resolve `0.6.1` from Maven Central in a clean dependency environment.
3. Upgrade the BOM, set output finalization deliberately, and keep chains off.
4. Run the complete deterministic platform suite and package the real runtime.
5. Deploy staging and execute all existing platform canaries.
6. Deploy the base upgrade to production with chains still off.
7. Add the reviewed chain migration and stable secrets.
8. Register one read-only canary chain behind a LoomAI feature flag.
9. Run deterministic, restart, security, and real-provider chain canaries.
10. Enable only the canary endpoint or tenant cohort, observe it, then expand
    deliberately.

## Rollback

For a chain-specific incident:

1. Stop new chain submissions.
2. Drain or explicitly cancel active executions.
3. Set `ai.execution.specialist-chains.enabled=false` and redeploy.
4. Restore the existing direct specialist or fixed-plan path.
5. Retain the table and both secrets through the configured retention and audit
   window.

The base dependency rollback should be a separate decision. Do not downgrade
to `0.6.0`; it lacks the `0.6.1` identity hardening. If a full rollback to
`0.5.2` is necessary, first ensure no `0.6.1` protected work is expected to
resume under the old runtime and retain its state and secrets for audit.

## Deliberate Boundaries

AI Fabric `0.6.1` does not provide:

- recursive worker transitions;
- open-ended graphs or cycles;
- dynamic specialist discovery;
- write-capable chain workers;
- partial-success parallel fan-in;
- model-selected identity, tenant, provider, action, vector space, or
  authority;
- exactly-once provider invocation; or
- semantic proof of every generated sentence beyond exact result attribution.

Use direct specialists, fixed plans, governed actions, receipts, reviews, and
reconciliation for work outside the bounded-chain contract. A dedicated graph
runtime should be evaluated separately for genuinely open-ended orchestration.

## Release Evidence

The immutable `0.6.1` release recorded:

- 2,049 framework tests with zero failures or errors and nine explicit gated
  skips;
- all 36 modules passing the Maven release profile;
- 524 real-app tests with zero failures or errors and 21 keyed skips;
- 7/7 Account Resolver OpenAI chain cases;
- a Docker/PostgreSQL restart and exact-replay proof;
- a clean Maven Central consumer resolving `0.6.1` and passing 3/3 tests;
- 86/86 website component tests and 26/26 Playwright cases; and
- eleven healthy hosted services, including ten consumers reporting AI Fabric
  `0.6.1` and the immutable release commit.

Maven test-skipping flags were not used. Keyed and external-service cases were
kept visible as separate release evidence.

## Platform Acceptance Checklist

- [ ] LoomAI BOM resolves only AI Fabric `0.6.1` artifacts.
- [ ] Java 21 and Spring Boot 4.1.x remain aligned.
- [ ] Existing manifest-backed work has been inventoried and handled.
- [ ] `output-finalization.max-attempts` is an explicit reviewed decision.
- [ ] Chains are disabled during the base upgrade.
- [ ] Existing platform deterministic and keyed canaries pass.
- [ ] Health reports the expected AI Fabric version and LoomAI commit.
- [ ] Trusted RAG tenant/deployment boundaries pass spoofing tests.
- [ ] Any MCP configuration passes exact server binding and result-size tests.
- [ ] The chain table is owned by a reviewed production migration.
- [ ] Chain secrets are stable, distinct, private, and recoverable.
- [ ] The first chain is exact-version, read-only, bounded, and feature-gated.
- [ ] All chain matrix cases pass, including restart and cross-tenant denial.
- [ ] Rollback with chains disabled has been observed.
- [ ] Production enablement has explicit owner approval.

## Authoritative References

- [`0.6.1` release notes](0.6.1.md)
- [`0.6.0` capability release notes](0.6.0.md)
- [`0.5.3` inherited hardening notes](0.5.3.md)
- [Bounded multi-specialist chain guide](../Framework-Dev-Guides/application-patterns/BOUNDED_MULTI_SPECIALIST_CHAINS.md)
- [LoomAI chain migration runbook](../Framework-Dev-Guides/application-patterns/LOOMAI_AI_FABRIC_0_6_CHAIN_MIGRATION_RUNBOOK.md)
- [LoomAI coding-assistant adoption prompt](../Framework-Dev-Guides/developer-workflows/LOOMAI_AI_FABRIC_0_6_CHAIN_ADOPTION_PROMPT.md)
- [Hosted deployment verification guide](../Framework-Dev-Guides/deployment-operations/PLATFORM_HOSTED_DEPLOYMENT_VERIFICATION_GUIDE.md)
- [Platform regression guide](../Framework-Dev-Guides/testing-verification/PLATFORM_REGRESSION_AND_LIVE_ADMIN_VERIFICATION_GUIDE.md)

