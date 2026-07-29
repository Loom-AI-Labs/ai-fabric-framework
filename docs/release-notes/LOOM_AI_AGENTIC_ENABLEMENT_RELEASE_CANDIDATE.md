# Loom AI Adoption Release Notes: AI Fabric Agentic Enablement

- **Status:** Framework implementation complete and pushed; publication pending
- **Audience:** Loom AI platform, application, security, and operations teams
- **Recommended release:** AI Fabric `0.5.0`
- **Compatibility baseline:** AI Fabric `0.4.0`
- **Baseline tag:** `ai-fabric-framework-v0.4.0`
- **Baseline commit:** `857619f`
- **Candidate commit:** pending the fixed-plan release-candidate commit
- **Prepared:** 2026-07-29
- **Reference application:**
  [`agentic-ai-action-resolver`](../../examples/real-apps/agentic-ai-action-resolver)

## 1. Executive Summary

AI Fabric now provides an optional agentic-enablement layer for bounded,
application-selected AI specialists.

Loom AI can use this layer to define a specialist from versioned configuration,
invoke it through a typed execution API, ground it with approved retrieval and
READ actions, and allow it to propose confirmation-gated application writes.
Applications can also compose exact-version, read-only specialists into
bounded fixed sequential plans with typed step mappings, deterministic
aggregation, checkpointed input waits, and safe resume. The model never
receives authority to approve or directly execute a write.

This work does not add a second orchestration engine. It composes the AI Fabric
capabilities that already exist:

- Modes and position-aware orchestration;
- provider-backed intent and generation;
- scoped vector retrieval;
- registered READ and WRITE actions;
- backend-owned conversation memory;
- trusted application context;
- structured output;
- confirmation;
- safe action-result projection; and
- visible provider, validation, policy, and persistence failures.

The recommended Loom AI direction is now **manifest-first specialist
authoring**. Loom AI should generate and deploy validated specialist
configuration over capabilities registered by an application. Java remains
necessary only for genuinely new domain behavior such as an action, connector,
authoritative reconciliation rule, or safe outcome projector.

## 2. Release Verdict For Loom AI

The framework side is ready for a release candidate.

Loom AI should adopt it as an additive capability after AI Fabric publishes the
new version. Loom AI should not build a separate agent runtime, action engine,
receipt store, or manifest interpreter.

The adoption boundary is:

| AI Fabric owns | Loom AI and the host application own |
| --- | --- |
| Specialist manifest contract and compiler | Specialist authoring experience |
| Strict schema and prompt-resource validation | Selection from the trusted authoring catalogue |
| Execution gateway and effective-capability intersection | Authentication and trusted context construction |
| Governed action invocation | Domain action handlers and authorization |
| Durable confirmation receipts | Production datasource and migrations |
| Receipt state transitions and replay protection | Confirmation and operational user experience |
| Safe evidence contract | Tenant and subject policy |
| Structured provider output validation | Domain consistency validators |
| Backend conversation binding | Conversation ownership supplied by the backend |
| Exact-version fixed-plan registry and coordinator | Application-owned plan selection, typed mappers, and deterministic aggregators |
| Manifest and execution diagnostics | Deployment, monitoring, support, and rollback |

## 3. Included Change Set

| Commit | Capability |
| --- | --- |
| `4c3209c` | Bounded specialist execution module and independent Agentic AI Action Resolver |
| `34d47dd` | Governed specialist writes, durable receipts, restart safety, and reconciliation |
| `0b73eec` | Published governed-write ownership and operations guidance |
| `9722834` | Semantic multi-turn completion of missing action parameters |
| `aec429f` | Regression proof that an unrelated turn does not hijack an action draft |
| `480aa4c` | Configurable specialist manifests, schema-backed execution, authoring catalogue, and manifest-based reference app |
| `958e80a` | Typed specialist input waits, bounded continuation state, and authority-scoped safe resume |
| Pending | Exact-version fixed sequential plans, process-local checkpoints, typed resume, and one-step/two-step Account Resolver proofs |

These commits build on the released `0.4.0` lifecycle, indexing, RAG, action,
provider, and chat-session contracts.

## 4. New Optional Execution Module

The new opt-in artifact is:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-execution</artifactId>
  <version>${ai-fabric.version}</version>
</dependency>
```

It is dependency-managed by the AI Fabric BOM but is not included in the
default starter.

Applications that only need the existing interactive orchestration endpoint do
not need this module.

Use it when an application needs:

- a stable, versioned specialist identity;
- typed or schema-bound input and output;
- an explicit capability boundary;
- application or interactive invocation;
- optional backend conversation memory;
- safe evidence references;
- confirmation-gated write proposals; or
- durable proposal and decision semantics;
- typed input waits that resume the same specialist invocation; or
- fixed application-selected read-only specialist plans.

## 5. Specialist Execution Contract

### 5.1 Application-selected specialists

An application selects a specific specialist:

```text
specialist-name@version
```

The model cannot discover or invoke an unrestricted specialist catalogue. A
specialist selects an existing AI Fabric Mode and requests a bounded set of
actions and vector spaces.

At runtime, AI Fabric computes:

```text
specialist requested capabilities
  intersect Mode policy
  intersect deployment inventory
  intersect registered actions and vector spaces
  intersect trusted caller authority
  -> effective execution capabilities
```

A specialist can narrow authority. It cannot grant itself authority.

### 5.2 Trusted execution context

The host backend must construct `TrustedExecutionContext` after
authentication. Public request JSON and model output must never supply:

- principal identity;
- subject or account identity;
- tenant;
- deployment;
- authority scopes;
- provider credentials; or
- confirmation state.

`APPLICATION`, `EVENT`, and `SCHEDULED` calls require a service or system
principal. `INTERACTIVE` calls require an end-user principal.

### 5.3 Typed execution result

`AIExecutionGateway` returns `AIExecutionResult<O>` with:

- invocation and specialist identity;
- `AIExecutionStatus`;
- typed or schema-backed output;
- safe `AIEvidenceReference` values;
- bounded diagnostics;
- a sanitized failure; or
- a public `ActionProposalView` when confirmation is required.

Supported execution statuses are:

```text
SUCCEEDED
CONFIRMATION_REQUIRED
WAITING_FOR_INPUT
FAILED
DENIED
INVALID
DEADLINE_EXCEEDED
CANCELLED
```

Provider, retrieval, policy, grounding, schema, persistence, and domain
validation failures remain visible. AI Fabric does not replace them with a
deterministic success response.

## 6. Manifest-First Specialist Authoring

### 6.1 What a manifest may define

A V1 manifest may compose:

- specialist name and immutable version;
- description and objective;
- one existing AI Fabric Mode;
- execution strategy;
- versioned prompt profile;
- versioned JSON input and output schemas;
- approved vector spaces;
- visible and requestable READ actions;
- confirmation-gated proposable WRITE actions;
- grounding requirements;
- conversation eligibility;
- execution limits; and
- references to application-registered extensions.

### 6.2 What a manifest may not define

A manifest cannot contain:

- identity, tenant, subject, scopes, or credentials;
- Java class names;
- scripts or expressions;
- SQL;
- arbitrary HTTP endpoints;
- provider API keys or per-specialist secrets;
- unrestricted tools;
- executable business rules; or
- authority grants.

### 6.3 Startup loading

Enable manifest loading:

```yaml
ai:
  execution:
    enabled: true
    manifests:
      enabled: true
      fail-fast: true
      max-manifest-bytes: 65536
      max-resource-bytes: 65536
      locations:
        - classpath*:ai-specialists/*.yml
```

The default locations scan `.yml`, `.yaml`, and `.json` files under
`classpath*:ai-specialists/`.

Loading is one-shot during application startup. Activation follows the normal
application build, deployment, and restart lifecycle. V1 does not provide a
specialist database, hot reload, active-version alias, or framework-managed
draft/activate/retire lifecycle.

Production should use `fail-fast: true`. Invalid resources then fail startup
instead of creating a partially available specialist catalogue.

### 6.4 Strict validation

The runtime rejects before registry publication:

- unknown fields;
- unsupported API versions;
- malformed or duplicate exact IDs;
- oversized resources;
- missing schemas or prompt profiles;
- unknown Modes, actions, vector spaces, or extension references;
- READ/WRITE metadata mismatches;
- retrieval without an explicit scope;
- writes that do not require confirmation;
- unsupported execution strategies; and
- duplicate specialist IDs across Java and manifest sources.

Every compiled manifest receives a canonical SHA-256 content hash.

### 6.5 Version semantics

References use exact immutable identifiers such as:

```text
account-resolver@1
account-resolution-request@1
account-resolution-result@1
account-resolver-prompt@1
```

A semantic change requires a new specialist version. Changing manifest content
under the same specialist version causes pending confirmation to fail closed
because durable receipts pin both specialist identity and content hash.

## 7. Loom AI Authoring Flow

AI Fabric provides `SpecialistAuthoringCatalogProvider` for trusted platform or
application code.

Loom AI should use its bounded catalogue to present:

- available Modes;
- registered vector spaces;
- registered READ and confirmation-gated WRITE actions;
- exact schema versions;
- exact prompt-profile versions;
- approved extension IDs; and
- framework limits.

Recommended Loom AI flow:

```text
developer selects application/deployment
  -> Loom AI reads the trusted authoring catalogue
  -> developer selects approved capabilities
  -> Loom AI produces a V1 manifest bundle
  -> validate against the published JSON Schema
  -> package or mount the immutable bundle
  -> deploy/restart the application
  -> AI Fabric parses and compiles at startup
  -> health confirms ID, version, source, hash, and readiness
  -> Loom AI invokes only that exact specialist ID
```

The catalogue is an authoring aid, not an authorization decision. Do not send
the complete catalogue to a model for unrestricted specialist selection.

The public resource contracts are packaged at:

```text
META-INF/ai-fabric/specialist-resource-v1.schema.json
META-INF/ai-fabric/examples/support-knowledge-specialist.yml
```

## 8. Java Extension Boundary

Manifest authoring removes repeated specialist declaration classes. It does
not remove application code that owns business truth.

Keep Java for:

- new domain actions;
- new connectors;
- application authorization;
- safe action-result projection;
- authoritative system-of-record reconciliation;
- complex domain consistency validation;
- custom deterministic direct projection; and
- representations that cannot be expressed safely through JSON Schema.

Reusable manifest extension points are:

- `SpecialistGroundingValidator`;
- `SpecialistFinalOutputValidator`;
- `SpecialistDirectOutputProjector`; and
- `SpecialistOutputNormalizer`.

Manifests reference stable registered extension IDs, never implementation class
names.

Existing `SpecialistDefinition<I, O>` beans remain supported. Java and manifest
definitions may coexist, but duplicate exact specialist IDs fail startup.

For Loom AI greenfield adoption, prefer manifests for specialist composition
and use small reusable Java extensions only where application truth requires
them.

## 9. Governed Specialist Writes

### 9.1 Model responsibility

The model may interpret a request and propose an allowlisted registered WRITE
with typed parameters.

The model cannot:

- grant authority;
- choose the trusted target;
- confirm the proposal;
- bypass application confirmation;
- call the handler directly; or
- report an application write as successful before authoritative execution.

### 9.2 Required write intersection

A write is proposable only when it survives:

```text
registered WRITE action metadata
  intersect specialist proposable writes
  intersect Mode policy
  intersect deployment allowed actions
  intersect trusted caller authority
  -> effective proposable writes
```

The action must require confirmation and have:

- typed parameter metadata;
- application authorization;
- a registered handler;
- an application-owned safe outcome projector;
- an idempotent domain boundary where possible; and
- an authoritative reconciliation path.

### 9.3 Durable receipt flow

```text
specialist analysis
  -> validated write proposal
  -> encrypted, identity-bound JDBC receipt
  -> CONFIRMATION_REQUIRED
  -> application displays confirmation
  -> user sends receiptId + CONFIRM or REJECT
  -> backend rebuilds trusted context
  -> identity, authority, specialist hash, profile, and action schema rechecked
  -> atomic PROPOSED -> CONFIRMED -> EXECUTING transition
  -> GovernedActionInvocationService
  -> application handler and system of record
  -> safe outcome projection
  -> SUCCEEDED | FAILED | OUTCOME_UNKNOWN
```

The public decision request contains only:

```json
{
  "receiptId": "action-receipt-...",
  "decision": "CONFIRM"
}
```

Do not accept action name, parameters, identity, subject, tenant, target, or
authority in this request.

### 9.4 Receipt states

```text
PROPOSED
CONFIRMED
EXECUTING
SUCCEEDED
FAILED
OUTCOME_UNKNOWN
REJECTED
EXPIRED
```

Transitions use optimistic compare-and-set. A terminal receipt cannot return
to an executable state. Concurrent confirmation and terminal replay do not
execute the action again.

`OUTCOME_UNKNOWN` means the framework cannot prove whether the application
side effect happened. It is never retried blindly. Loom AI or the host
application must query the system of record and invoke authoritative
reconciliation.

## 10. Receipt Storage And Operations

Production governed writes use the JDBC receipt repository and table:

```text
ai_action_proposal_receipt
```

The repository protects:

- action parameters;
- projected outcomes;
- identity and subject fingerprints;
- specialist and profile binding;
- idempotency;
- optimistic version;
- transition timestamps; and
- terminal replay response.

Example configuration:

```yaml
ai:
  execution:
    receipts:
      enabled: true
      repository: JDBC
      initialize-schema: false
      ttl: PT10M
      stale-executing-after: PT2M
      recovery-batch-size: 100
      cleanup-enabled: false
      retention: P90D
      encryption-secret: ${AI_EXECUTION_RECEIPT_ENCRYPTION_SECRET}
      fingerprint-secret: ${AI_EXECUTION_RECEIPT_FINGERPRINT_SECRET}
```

Production requirements:

- configure a `DataSource`;
- create the table through a Loom AI-owned Flyway or Liquibase migration;
- set `initialize-schema: false` after applying that migration;
- use different random secrets of at least 32 characters;
- keep both secrets stable across replicas and restarts;
- enable Spring scheduling for periodic recovery;
- retain `OUTCOME_UNKNOWN` receipts until reconciliation; and
- choose retention according to audit and privacy requirements.

`IN_MEMORY` is for deterministic tests only. Production startup rejects it
unless the application explicitly acknowledges the non-durable risk.

Receipt storage is not chat history and is not specialist configuration. It
exists only to preserve a proposed write, confirmation decision, execution
state, and authoritative outcome safely across requests and restarts.

## 11. Conversation Memory And Action Drafts

### 11.1 Backend-owned conversation

Specialist execution does not read or write chat history unless the backend
provides an authorized `ConversationBinding`.

With a binding, the gateway:

1. reads bounded recent backend turns;
2. invokes the pipeline with internal recording disabled;
3. validates grounding, structured output, and domain rules; and
4. records the new turn only after successful validation.

The browser sends only the new user message. Loom AI must not trust browser
supplied history as authoritative conversation state.

### 11.2 Multi-turn missing parameters

Incomplete action parameters now survive across later user turns.

Before normal intent extraction, a bounded structured LLM decision determines
whether the current message:

- continues the existing action draft;
- supplies or corrects one or more public action fields; or
- is an independent question, different action, cancellation, or topic change.

If it continues, AI Fabric merges only allowlisted public parameters. Current
values override older values. Hidden and application-owned values are never
taken from the draft or model; the backend resolves them again at execution
time.

This is semantic LLM interpretation, not phrase or keyword matching.
Deterministic code owns allowlisting, merge behavior, schema validation,
authorization, and execution safety.

An unrelated question does not accidentally execute or replace the draft.
Ambiguous values remain a clarification rather than being guessed.

### 11.3 Typed specialist input waits

A specialist may return a typed `NeedsUserInput` outcome when required public
input is missing. AI Fabric stores a bounded, process-local continuation bound
to the original invocation, exact specialist/profile identity, trusted access
context, deadline, and response schema.

Resume accepts the registered typed host response and delegates conversion and
schema validation through the same specialist client. The caller cannot select
specialist identity, tenant, subject, authority, or profile during resume.
Malformed responses remain visible and retryable while the wait is valid;
cross-context, expired, conflicting, or cancelled resumes fail closed.

Input-wait state is not chat history and is not durable workflow state. It is
`EPHEMERAL` and does not survive process restart.

### 11.4 Fixed sequential specialist plans

An application may register an immutable exact-version plan containing one or
more exact-version read-only specialist steps. Java-registered typed mappers
construct each step input from the original plan input and explicitly declared
predecessor outputs. A deterministic registered aggregator constructs the
final typed result.

Every step independently invokes `AIExecutionGateway`, so capability,
authority, grounding, schema, deadline, provider, and result validation remain
in force. Completed steps are checkpointed in a bounded process-local plan
store. If a later step needs user input, resume continues that step without
rerunning successful predecessors.

This is an application-selected composition contract, not a model-generated
plan, graph engine, supervisor, or unrestricted multi-agent runtime. The first
version rejects interactive invocation and WRITE-capable specialists.

## 12. Evidence And Indexing Boundary

Specialist results return `AIEvidenceReference`.

Do not expose `AIIndexDocument`, raw `RAGDocument`, embeddings, unrestricted
metadata, or queue payloads to Loom AI callers.

| Contract | Direction | Purpose |
| --- | --- | --- |
| `AIIndexDocument` | Application or indexing worker to vector provider | Canonical write-side indexing payload |
| `AIEvidenceReference` | Governed retrieval to application caller | Safe read-side evidence projection |

An evidence reference contains bounded content and safe metadata such as:

- evidence ID;
- content;
- relevance score;
- source and URL;
- vector space; and
- allowlisted metadata.

Evidence must remain within the effective vector profile. Missing or
out-of-scope evidence fails the specialist rather than being silently dropped
after generation.

## 13. Configuration Baseline

A Loom AI-enabled application will normally configure:

```yaml
ai:
  execution:
    enabled: true
    manifests:
      enabled: true
      fail-fast: true
    capabilities:
      registered-vector-spaces:
        - account-policy
      allowed-actions:
        - get_account_profile
        - update_address
    async:
      core-pool-size: 2
      max-pool-size: 4
      queue-capacity: 32
      result-ttl: PT15M
    receipts:
      enabled: true
      repository: JDBC
      initialize-schema: false
      ttl: PT10M
      stale-executing-after: PT2M
      recovery-batch-size: 100
      cleanup-enabled: false
      retention: P90D
      encryption-secret: ${AI_EXECUTION_RECEIPT_ENCRYPTION_SECRET}
      fingerprint-secret: ${AI_EXECUTION_RECEIPT_FINGERPRINT_SECRET}
    input-waits:
      enabled: true
      max-active: 1000
      ttl: PT10M
    plans:
      enabled: true
      max-steps: 8
      max-duration: PT2M
      max-active: 1000
      result-ttl: PT15M
```

The application must separately configure:

- its AI Fabric Mode;
- provider and model routing;
- vector provider and registered spaces;
- action registry and handlers;
- optional chat-session storage;
- datasource and schema migrations;
- authentication and authorization; and
- any application-specific extension beans.

## 14. Storage Map For Loom AI

| State | Storage owner | Durability |
| --- | --- | --- |
| Specialist manifest | Application artifact or immutable mounted configuration | Deployment lifecycle |
| Specialist registry | AI Fabric process memory, rebuilt at startup | Reproducible from manifest/Java definitions |
| Chat turns | Configured `ai-fabric-chat-session` provider | Backend conversation lifecycle |
| Pending ordinary chat action/draft | Chat-session action state | Conversation lifecycle |
| Specialist write receipt | JDBC `ai_action_proposal_receipt` | Durable across restart |
| Specialist input wait | Bounded execution-module process memory | `EPHEMERAL`; lost on restart |
| Fixed-plan checkpoints and terminal result | Bounded execution-module process memory | `EPHEMERAL`; lost on restart |
| Vector evidence | Existing AI Fabric vector provider | Provider lifecycle |
| Domain entity and authoritative action result | Host application system of record | Application lifecycle |
| General `submit` execution and result | Bounded process memory | `EPHEMERAL`; lost on restart |

Durable write receipts do not make the general execution engine a durable
workflow system.

## 15. Compatibility With AI Fabric 0.4

### Additive public adoption

Existing `0.4.0` applications do not need to:

- replace their existing orchestration endpoint;
- rewrite Modes;
- replace RAG or vector providers;
- replace action annotations or handlers;
- replace chat-session storage;
- replace live data sync; or
- adopt specialist manifests or fixed specialist plans.

The execution artifact remains opt-in.

### Shared behavioral hardening

Action execution paths now converge on `GovernedActionInvocationService`.
Applications that previously depended on an unsafe bypass may now receive a
visible denial or confirmation requirement. This is intentional security
hardening, not a compatibility switch.

Multi-turn action continuation is a core orchestration improvement and may
cause a clear field-only response to continue an existing draft. Independent
questions and different actions remain independently classified.

### Java specialist compatibility

Existing Java `SpecialistDefinition<I, O>` beans remain valid. Loom AI can
migrate them incrementally:

1. preserve existing tests;
2. move identity, objective, schemas, prompt profile, capabilities, and limits
   into a manifest;
3. retain real domain validators, projectors, actions, and reconciliation in
   Java;
4. verify Java and manifest parity; and
5. remove only redundant declaration code.

Because Loom AI currently has no external specialist users, use the clean
manifest-first model for new specialists instead of adding compatibility
aliases for an unpublished configuration contract.

Fixed plans are also additive. Existing direct specialist callers keep their
current behavior. A plan must be selected explicitly by trusted application
code and cannot be inferred or activated by a model response.

## 16. Earlier Documentation Superseded

The following earlier P0/P1 guidance remains useful for read-only architecture
but is no longer the complete release boundary:

- `0001-agentic-enablement-release-impact.md`;
- `0001-agentic-enablement-module-and-migration-guide.md`; and
- `0001-agentic-enablement-p0-p1-approval-scorecard.md`.

Those documents state that specialist writes are not approved. The governed
write and manifest implementations completed that missing boundary.

For current adoption use, in order:

1. this Loom AI release note;
2. the
   [Specialist Manifest Authoring Guide](../Framework-Dev-Guides/application-patterns/SPECIALIST_MANIFEST_AUTHORING_GUIDE.md);
3. the
   [Governed Specialist Writes And Durable Receipts Guide](../Framework-Dev-Guides/actions-governance/GOVERNED_SPECIALIST_WRITES_AND_RECEIPTS.md);
4. the
   [governed-write implementation plan](../planning/ai-fabric-flow-architecture-analysis-pack/implementation-plans/0002-governed-specialist-write-and-receipt-implementation-plan.md);
5. the
   [manifest-runtime implementation plan](../planning/ai-fabric-flow-architecture-analysis-pack/implementation-plans/0003-configurable-specialist-manifest-runtime-implementation-plan.md);
   and
6. the
   [`agentic-ai-action-resolver`](../../examples/real-apps/agentic-ai-action-resolver)
   reference application.

## 17. Loom AI Adoption Plan

### Phase 0: Release consumption

- [ ] Assign and publish the new AI Fabric version.
- [ ] Import one version through the AI Fabric BOM.
- [ ] Add `ai-fabric-execution` only to applications using specialists.
- [ ] Run the Loom AI platform suite against the published Maven artifacts.
- [ ] Do not consume mutable framework source from a production deployment.

### Phase 1: Read-only specialist proof

- [ ] Select one bounded, low-risk application use case.
- [ ] Expose only approved READ actions and vector spaces.
- [ ] Build trusted context from authenticated backend state.
- [ ] Author exact-version input, output, and prompt resources.
- [ ] Enable manifest fail-fast startup.
- [ ] Verify safe evidence, grounding, provider failure, and tenant denial.
- [ ] Deploy and record the specialist ID and content hash.

### Phase 1.5: Fixed read-only composition proof

- [ ] Choose a deterministic use case that genuinely benefits from two bounded
  specialist assessments.
- [ ] Register exact-version plan, mapper, and aggregator components in
  application code.
- [ ] Keep every step read-only and reject interactive plan invocation.
- [ ] Verify each child receives only its own effective capability boundary.
- [ ] Verify a second-step input wait resumes without rerunning step one.
- [ ] Treat plan checkpoints as process-local and restart the request from the
  beginning after process loss.

### Phase 2: Governed write proof

- [ ] Choose one low-risk, confirmation-required registered WRITE.
- [ ] Remove model-controlled target and identity parameters.
- [ ] Register a safe application outcome projector.
- [ ] Configure JDBC receipt storage and production migrations.
- [ ] Configure stable receipt secrets.
- [ ] Expose a decision endpoint with only receipt ID and decision.
- [ ] Verify proposal does not mutate domain state.
- [ ] Verify confirm, reject, expiry, concurrency, terminal replay, and restart.
- [ ] Prove `OUTCOME_UNKNOWN` reconciliation against the system of record.

### Phase 3: Loom AI authoring support

- [ ] Read `SpecialistAuthoringCatalogProvider` from trusted platform code.
- [ ] Build an authoring form from the bounded catalogue.
- [ ] Generate only V1 schema-compliant immutable bundles.
- [ ] Keep secrets, identity, authority, and executable code outside manifests.
- [ ] Validate before commit/deployment.
- [ ] Package or mount the bundle through the application release process.
- [ ] Surface startup compilation diagnostics and readiness.
- [ ] Track deployed specialist ID, version, hash, application, and environment.

### Phase 4: Controlled rollout

- [ ] Start with internal users and a single tenant/application boundary.
- [ ] Observe provider, grounding, denial, receipt, expiry, and unknown metrics.
- [ ] Exercise rollback before expanding capability.
- [ ] Add specialists only from registered capabilities.
- [ ] Capture real authoring friction before proposing dynamic manifest storage.

## 18. Security Review Checklist

- [ ] Public payload cannot provide trusted identity or authority.
- [ ] Manifest cannot contain credentials, identity, tenant, or endpoints.
- [ ] Specialist capabilities are a request, never a grant.
- [ ] Retrieval spaces are explicitly allowlisted.
- [ ] READ and WRITE metadata match the registered action.
- [ ] Every WRITE requires confirmation.
- [ ] Every WRITE has a safe outcome projector.
- [ ] Public confirmation accepts only receipt ID and decision.
- [ ] Receipt parameters and outcomes are encrypted.
- [ ] Identity and subject are fingerprinted.
- [ ] Secrets are stable across all replicas.
- [ ] Cross-principal, subject, tenant, deployment, and session access is denied.
- [ ] Provider and validation failures remain visible.
- [ ] Logs and diagnostics exclude prompts, raw receipt payloads, keys, and PII.
- [ ] Unknown action outcomes are reconciled and never blindly retried.
- [ ] Manifest content changes require a new version.

## 19. Operations And Observability

Monitor:

```text
ai.fabric.specialist.manifest.load
ai.fabric.specialist.manifest.validation
ai.fabric.specialist.registry.definition.count
ai.fabric.specialist.execution.by.source
ai.fabric.execution.action.receipts
ai.fabric.execution.input.waits
ai.fabric.execution.plans
```

Health may expose:

- manifest runtime enabled;
- loaded, manifest, and Java definition counts;
- specialist source, ID, and version;
- specialist content hash;
- registry content hash; and
- safe readiness/diagnostic reason codes.

Do not expose:

- manifest or prompt content;
- JSON schemas;
- raw input or provider output;
- authority scopes;
- principal, subject, or tenant;
- receipt payloads; or
- provider secrets.

Alert on:

- manifest load or validation failure;
- specialist execution denial spikes;
- provider or grounding failure;
- receipt-store unavailability;
- outcome-persistence failure;
- `OUTCOME_UNKNOWN`;
- recovery-marked unknown receipts;
- abnormal expiry growth;
- repeated cross-context confirmation denial; and
- plan registration failure, wait expiry, resume denial, or deadline growth.

## 20. Rollback

### Stop specialist invocation

Remove or disable the Loom AI route that invokes the specialist. Existing
interactive AI Fabric orchestration remains available.

### Stop new specialist writes

1. remove the WRITE from the specialist manifest;
2. remove it from deployment `allowed-actions`;
3. remove it from trusted authority scopes;
4. deploy a new specialist version; and
5. verify the effective profile exposes no proposable write.

Keep receipt support running until all existing receipts are terminal or
reconciled. Do not drop the receipt table during rollback.

### Remove manifest adoption

Disable manifest loading and restore an already tested Java specialist
definition if required. Do not create duplicate exact IDs while both sources
are active.

### Remove the optional module

After all specialist routes and receipt obligations are removed, the
application may remove `ai-fabric-execution` and continue using existing AI
Fabric orchestration.

Do not add a runtime switch that bypasses `GovernedActionInvocationService`.

## 21. Verification Evidence

The implementation has the following recorded evidence.

### Specialist execution and governed writes

- Source Docker build executed 1,175 framework tests across 15 selected
  modules with zero failures or errors.
- The packaged Agentic AI Action Resolver executed 12 shared smoke tests and
  79 application tests.
- The original Account Resolver remained unchanged and passed its 49 tests
  plus 12 shared smoke tests.
- JDBC proposal, restart, confirmation, second-restart replay, rejection,
  concurrency, expiry, unknown outcome, cleanup, and reconciliation were
  exercised.
- Real OpenAI testing covered read assessment, write proposal, confirmation,
  post-write assessment, rejection, hostile instructions, malformed and extra
  parameters, replay, and cross-session isolation.
- Invalid-provider execution returned a visible provider failure, created no
  receipt, and exposed no provider key or native error.

### Manifest runtime

- The full 36-module infrastructure reactor passed, including all 130
  `ai-fabric-execution` tests.
- The full 22-module real-app reactor passed, including 80 Agentic AI Action
  Resolver tests after manifest migration.
- Strict loader, manifest compiler, resource registries, JSON Schema adapters,
  typed client, authoring catalogue, metrics, public example, and registry
  bootstrap tests passed.
- Packaged resources were verified in the execution JAR and application boot
  JAR.
- Real OpenAI and packaged restart flows passed using manifest-backed
  specialists.

### Multi-turn action continuation

- `ai-fabric-core`: 670 tests passed.
- `ai-fabric-chat-session`: 56 tests passed.
- Agentic resolver: 79 application tests plus 12 shared smoke tests passed.
- The final selected chat integration reactor passed two end-to-end
  missing-parameter tests across 16 reactor modules.
- Real OpenAI scenarios covered fragmented fields, correction of an earlier
  value, an unrelated question between field replies, a different requested
  action, rejection, and conservative clarification for ambiguous input.

These suites overlap and must not be summed into a single test-count claim.
The release CI must rerun the authoritative matrix from the final release
commit.

### Typed input waits and fixed sequential plans

- The final 36-module infrastructure reactor passed with tests enabled.
- Focused plan coordinator and contract installation passed 11 tests.
- The packaged Agentic AI Action Resolver passed 12 shared smoke tests and 96
  application tests.
- Packaged OpenAI verification proved one-step success, two-step success,
  second-step `WAITING_FOR_INPUT`, typed resume without rerunning step one, and
  idempotent replay.
- Packaged verification exposed and fixed a Spring Boot 4/Jackson web-boundary
  incompatibility. The public resume contract now accepts a typed host response
  and a Spring MVC regression test protects deserialization.

## 22. Release Gate Still Required

Before Loom AI adopts a published artifact:

- [ ] Assign the release version.
- [ ] Update framework, BOM, examples, and documentation consistently.
- [ ] Run the complete clean release gate with tests enabled.
- [ ] Run keyed OpenAI execution and action-continuation scenarios.
- [ ] Run packaged Docker and JDBC restart/replay proof.
- [ ] Verify Maven Central consumer resolution.
- [ ] Publish release notes and migration guidance.
- [ ] Deploy the independent Agentic AI Action Resolver.
- [ ] Verify its health reports the released version and candidate commit.
- [ ] Run Loom AI consumer compilation and runtime smoke tests.
- [ ] Obtain explicit release approval.

Until those items pass, Loom AI should treat the final fixed-plan commit named
at the top of this document as a verified framework candidate, not a published
dependency.

## 23. Explicitly Deferred

This release does not provide:

- model-generated or dynamic multi-specialist planning;
- conditional or parallel specialist branches;
- specialist-authored delegation or handoff;
- interactive plan dialogue ownership;
- WRITE-capable composed plans;
- model-selected unrestricted specialist discovery;
- durable general or plan execution;
- a workflow graph or workflow engine;
- event-broker or scheduler adapters;
- a specialist-definition database;
- runtime manifest hot reload;
- draft/validate/activate/retire framework persistence;
- tenant-authored executable manifests;
- arbitrary provider endpoints or credentials in manifests;
- scripts, SQL, expressions, or arbitrary HTTP tools;
- generic business-rule authoring; or
- exactly-once guarantees for arbitrary external side effects.

These should remain separate roadmap items. Spring AI may be used underneath
future provider or tool infrastructure where it fits, but it must not replace
AI Fabric's application ownership, governance, storage, or execution
contracts.

## 24. Final Recommendation

Approve this capability set as the proposed AI Fabric `0.5.0` release scope.

For Loom AI:

1. adopt the manifest-first path for new specialists;
2. keep trusted identity, authority, deployment, and secrets in platform and
   application code;
3. use Java only for real domain behavior;
4. start with a read-only internal specialist;
5. add one low-risk governed write only after JDBC receipt operations are
   ready;
6. use fixed read-only plans only where application-owned decomposition is
   deterministic and measurably better than one specialist; and
7. defer dynamic planning, delegation, WRITE composition, and durable plan
   execution until the fixed-plan contract has production usage evidence.

This moves Loom AI from generating ad hoc AI integrations toward composing
versioned, governed AI-enabled application capabilities without turning model
output into application authority.
