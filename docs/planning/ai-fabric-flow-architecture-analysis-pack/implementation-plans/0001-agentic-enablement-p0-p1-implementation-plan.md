# Agentic Enablement P0/P1 Implementation Plan

- **Status:** Implemented and approved for the read-only P0/P1 scope
- **Date:** 2026-07-28
- **Framework baseline:** AI Fabric `0.4.0`
- **Code reviewed at:** `e29961650bb132c89ca33dcb0ee7a3119ae87ad0`
- **Target release:** `0.5.0` if the public contracts and reference proof pass every gate
- **Architecture source:** [Specialist-defined agentic enablement proposal](../Full-Proposal/Product-evolution-proposal.md)
- **Verdict source:** [Agentic enablement portfolio verdict](../AI_FABRIC_AGENTIC_ENABLEMENT_VERDICT.md)
- **Delivery source:** [Agentic product verdict and delivery strategy](../AI_FABRIC_AGENTIC_PRODUCT_VERDICT_AND_DELIVERY_STRATEGY.md)
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## 1. Purpose

Implement the smallest production-grade layer that lets AI Fabric execute the same bounded,
governed intelligence from:

1. an interactive conversation; and
2. a typed Java application call.

The work must preserve the existing orchestration engine, Mode behavior, RAG, providers, action
handlers, confirmation, chat-session support, and live-data synchronization. It must not introduce
a second agent engine or claim general multi-agent support.

The first release proves one specialist through an independently deployable
`agentic-ai-action-resolver` app. The current `ai-fabric-account-resolver` app and deployment remain
unchanged as the behavioral baseline.

## 2. Delivery Verdict

P0/P1 has been implemented as the bounded foundation described here. It remains intentionally
smaller than a complete P0-P5 agent runtime.

The completed gate and decision are recorded in
[the P0/P1 approval scorecard](./0001-agentic-enablement-p0-p1-approval-scorecard.md).

The required order is:

```text
freeze existing behavior
  -> copy the Account Resolver into an independent baseline proof
  -> introduce trusted request/evidence contracts
  -> centralize effective capabilities and action invocation
  -> add the optional execution module
  -> bridge typed requests into the existing pipeline
  -> prove one READ-only specialist from chat and Java
  -> evaluate before adding governed WRITE receipts or composition
```

## 3. Fixed Decisions

These decisions are binding for P0/P1.

1. **Keep the existing engine.** `RAGOrchestrator`, `Pipeline`, Mode policy, RAG, action handlers,
   providers, and chat-session modules remain the execution machinery.
2. **Do not redefine Mode as an agent.** Mode remains a reusable orchestration preset. A specialist
   requests a scope which is intersected with Mode, registered capabilities, and current authority.
3. **Use a new optional module.** Specialist contracts, registry, gateway, and coordination belong
   in `ai-fabric-execution`, not in the default starter or as a large expansion of core.
4. **Keep enforcement primitives in core.** The existing pipeline must own trusted request
   context, effective capability resolution, read-side evidence references, and final governed
   action invocation.
5. **Do not fake a user, session, or conversation.** An application call has a real service/system
   initiator and conversation persistence disabled.
6. **Keep the original demo.** `ai-fabric-account-resolver` remains unchanged and independently
   deployable. The new proof is copied to `agentic-ai-action-resolver`.
7. **Start READ-only.** P1 exposes account profile and policy evidence but no specialist WRITE.
8. **No hidden fallback.** Provider, retrieval, schema, policy, budget, and output-validation
   failures remain visible and typed.
9. **No speculative public APIs.** Every public contract added in a slice must have a production
   implementation, tests, and a real-app caller in that slice.
10. **No P0/P1 durability claims.** `execute` is synchronous. `submit` is explicitly ephemeral,
    bounded, and in-memory until a later durable adapter is justified.
11. **Spring AI is commodity infrastructure.** Reuse stable Spring AI model, structured-output,
    tool-callback, and observability support where helpful. AI Fabric retains policy, evidence,
    action, receipt, and execution semantics.
12. **Other agent frameworks remain research inputs.** LangChain4j, LangGraph, LangGraph4j,
    Embabel, Microsoft Agent Framework, and workflow engines do not become P0/P1 dependencies.
13. **Run tests normally.** No implementation or release command may use `-DskipTests`.

## 4. Code-Backed Baseline

| Current code | Evidence | Implementation consequence |
| --- | --- | --- |
| `Pipeline.execute(String, OrchestrationContext)` is the only public pipeline entry | `ai-fabric-core/.../pipeline/Pipeline.java:54` | Add an additive structured request overload; retain the current method as a compatibility adapter. |
| `DefaultOrchestrationPipeline` validates a user/session context and builds `PipelineContext` from a string | `DefaultOrchestrationPipeline.java:106-116` | Application calls need source-aware validation and must not manufacture a user/session. |
| `PipelineContext` is query-centric | `PipelineContext.java:73-101` | Carry a typed request envelope while preserving `originalQuery` and `processedQuery` for existing steps. |
| `OrchestrationContext.validate()` requires `userId` or `sessionId` | `OrchestrationContext.java:157-165` | Keep this rule for legacy interactive calls; use trusted initiator validation for application calls. |
| Auth context is currently reconstructed from context metadata | `OrchestrationAuthContextResolver.java:15-43` | Add a first-class trusted context and never expose a public `trust(Map)` shortcut. |
| Mode already resolves action, retrieval, vector-space, planner, and budget policy | `OrchestrationPolicy.java:17-25`, `80-126`, `199-247` | Specialist scope must intersect with this policy instead of duplicating it. |
| `AIActionRegistry` is the discovery source | `AIActionRegistry.java:179-199` | Keep it as discovery, but stop treating direct handler lookup as authorization or execution. |
| Main-pipeline actions call handlers directly | `IntentHandlingStep.java:591-648` | Route final invocation through one governed service. |
| Read-action planning calls handlers directly | `ReadActionResolutionService.java:414-436` | Route planner READ calls through the same governed service. |
| Action context resolution calls handlers directly | `ActionContextParamResolutionSupport.java:224` | Route prerequisite READ calls through the same governed service. |
| Spring AI tool callbacks call handlers directly | `AIActionToolCallbackFactory.java:200-234` | Tool callbacks must consume the effective catalog and governed invoker. |
| Pending actions pin parameters and trusted evidence, but not the effective execution profile | `PendingAction.java:14-35` | Do not add specialist WRITEs until a later receipt plan also pins specialist/profile versions. |
| `RAGResponse.RAGDocument` is a broad read DTO | `RAGResponse.java:180-298` | Adapt retrieved results into one small safe read-side evidence reference. |
| `AIIndexDocument` is a versioned indexing queue payload | `AIIndexDocument.java:14-31` | Keep it write-side only; never reuse it as execution evidence. |
| The reactor has no execution module | `ai-infrastructure-module/pom.xml:42-75` | Add `ai-fabric-execution` explicitly to the reactor and BOM. |
| Core currently uses Spring AI `2.0.0` model contracts | `ai-infrastructure-module/pom.xml:92`, `ai-fabric-core/pom.xml:135` | Reuse Spring AI selectively without making it the execution authority. |
| Account Resolver already proves RAG, Mode, actions, chat memory, and policies | `examples/real-apps/ai-fabric-account-resolver` | Copy it as the source for the first independent proof and compare the same scenarios. |

## 5. Module Boundary

```text
ai-fabric-core
  trusted request/context
  effective capability profile
  capability-aware action catalogue
  governed action invocation
  canonical read-side evidence reference
  additive structured pipeline entry
          ^
          |
ai-fabric-execution
  specialist definitions and registry
  typed input/output adapters
  execute/submit gateway
  single-invocation coordinator
  ephemeral handles
          ^
          |
agentic-ai-action-resolver
  account-resolver@1 definition
  typed domain request/result
  interactive endpoint
  application-call endpoint
  domain policies and data
```

### 5.1 `ai-fabric-core`

Core owns contracts required to enforce policy in the existing pipeline:

- trusted execution principal, subject, source, and context;
- structured orchestration request envelope;
- conversation persistence policy;
- requested and effective capability profiles;
- capability-aware action catalogue;
- governed action invocation request/outcome/service;
- canonical read-side evidence reference;
- compatibility adapters for the existing string and Mode-only paths.

Core must not know about `SpecialistDefinition`, specialist registries, execution handles, plans,
delegation, or durable execution.

### 5.2 `ai-fabric-execution`

The new optional module owns:

- specialist identity, definition, and immutable registry;
- typed input renderer/validator and output projector/validator;
- execution request, result, failure, and handle contracts;
- synchronous execution gateway;
- bounded ephemeral submission;
- one single-invocation coordinator;
- Spring Boot auto-configuration for these components.

Dependency direction is `ai-fabric-execution -> ai-fabric-core`. Core must never depend on the
execution module.

### 5.3 Existing Optional Modules

- `ai-fabric-rag` remains responsible for retrieval implementation.
- `ai-fabric-chat-session` remains responsible for real conversational continuity.
- `ai-fabric-provider-spring-ai` remains an LLM/embedding provider adapter.
- vector modules remain native AI Fabric vector providers.
- data sync and indexing remain responsible for application-data projection and revision updates.

### 5.4 Starter Decision

Do not add `ai-fabric-execution` to `ai-fabric-starter` in the first release. Users opt in:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-execution</artifactId>
  <version>${ai-fabric.version}</version>
</dependency>
```

The normal starter may include it only after compatibility, package-size, startup, and adoption
evidence justify making it a default.

## 6. P0 Core Contracts

Names in this section are the intended implementation names. Rename only when a code-level
conflict is discovered and document the replacement before merging.

### 6.1 Trusted Execution Context

Add under `ai.fabric.execution.context` in `ai-fabric-core`:

```text
ExecutionSource
  INTERACTIVE
  APPLICATION
  EVENT
  SCHEDULED

ExecutionPrincipal
  principalId
  principalType: END_USER | SERVICE | SYSTEM

ExecutionSubjectRef
  subjectType
  subjectId

TrustedExecutionContext
  initiator                 required
  subject                   optional typed binding
  source                    required
  tenantId                  optional only when policy permits
  deploymentId              optional
  grantedScopes             immutable
  correlationId             required/generated by server
  authenticatedAt           optional
```

Rules:

- `APPLICATION`, `EVENT`, and `SCHEDULED` require a `SERVICE` or `SYSTEM` initiator.
- An interactive request requires an `END_USER` or an explicitly allowed anonymous session path.
- Request bodies may not set initiator, tenant, deployment, or granted scopes.
- Applications build trusted context from authenticated server state through a source-specific
  adapter.
- Do not add `TrustedExecutionContext.fromMetadata(Map<String,Object>)`.
- The existing `AIAccessSubjectContext` remains available. Add a compatibility adapter from trusted
  context and migrate access-control hooks incrementally.

### 6.2 Structured Orchestration Request

Add under `ai.fabric.intent.orchestration.request`:

```text
ConversationPersistencePolicy
  CONVERSATION
  READ_ONLY
  NEVER

OrchestrationRequest
  modelInput
  orchestrationContext
  trustedExecutionContext
  conversationPersistencePolicy
```

Behavior:

- `Pipeline.execute(String, OrchestrationContext)` remains source-compatible.
- The old method creates an interactive `OrchestrationRequest`.
- Add `Pipeline.execute(OrchestrationRequest)`.
- Legacy requests continue to call `OrchestrationContext.validate()`.
- Non-interactive requests validate `TrustedExecutionContext` and may have no `userId`,
  `sessionId`, or `conversationId`.
- `PipelineContext` carries the request envelope while retaining existing query fields.
- `modelInput` is the bounded, deterministic representation consumed by existing text-based
  pipeline steps. It is not labeled or persisted as a user message.
- `ConversationPersistencePolicy.READ_ONLY` permits server-authorized conversation enrichment but
  prevents the pipeline from recording an unvalidated specialist result.
- After schema, grounding, evidence, and domain validation succeeds, the execution gateway commits
  the completed turn through the chat-session service.
- `ConversationPersistencePolicy.NEVER` prevents both enrichment and recording.
- The current `QUERY_PERSISTENCE_MODE` metadata key remains a compatibility projection during
  migration, not the long-term source of truth.

### 6.3 Requested And Effective Capabilities

Add under `ai.fabric.intent.orchestration.capability`:

```text
RequestedCapabilityProfile
  retrievalEnabled
  requestedVectorSpaces
  visibleActions
  requestableReadActions
  proposableWriteActions

EffectiveCapabilityProfile
  resolved Mode/profile
  effectiveVectorSpaces
  visibleActions
  executableReadActions
  proposableWriteActions
  effective budgets
  deterministic profileHash

EffectiveCapabilitiesResolver
  resolve(CapabilityResolutionRequest)
```

The resolver computes an intersection:

```text
specialist request
  INTERSECT Mode/position policy
  INTERSECT registered actions/vector spaces
  INTERSECT trusted caller and subject authority
  INTERSECT deployment allowlists and budgets
  = immutable effective profile
```

Rules:

- A declaration never grants authority.
- An unknown requested action or vector space fails startup validation for static definitions when
  it can be known, otherwise fails visibly at invocation.
- Empty required intersections fail closed.
- The effective profile hash is deterministic and safe to log.
- Legacy Mode-only requests resolve an equivalent profile that preserves current behavior.

### 6.4 Capability-Aware Action Catalogue

Add:

```text
CapabilityAwareActionCatalog
  listVisibleActions(effectiveProfile)
  findVisibleAction(actionName, effectiveProfile)
  requireExecutableAction(actionName, effectiveProfile)
```

`AIActionRegistry` remains the source of registered handlers and metadata. Every action exposure
path consumes the effective catalogue:

- intent prompt construction;
- intent post-processing;
- direct action resolution;
- planner read-action resolution;
- Spring AI tool callbacks;
- specialist output validation;
- final invocation.

An action absent from the effective profile is not shown to the model and cannot execute if a model
or caller nevertheless names it.

### 6.5 Governed Action Invocation

Add under `ai.fabric.intent.action.invocation`:

```text
GovernedActionInvocationService
  invoke(GovernedActionInvocation)

GovernedActionInvocation
  actionName
  parameters
  actionContext
  trustedExecutionContext
  effectiveCapabilityProfile
  confirmationState
  trustedEvidence

GovernedActionInvocationOutcome
  status: EXECUTED | CONFIRMATION_REQUIRED | DENIED | INVALID | FAILED
  actionResult
  publicFailure
```

The default service performs the final non-bypassable checks:

1. action exists;
2. action is in the effective profile;
3. access mode is allowed for the requested invocation;
4. authentication/anonymous contract is satisfied;
5. handler `validateActionAllowed` succeeds;
6. required confirmation is present and valid;
7. parameters passed to the handler are the validated effective parameters;
8. errors and null results are normalized without hiding failure;
9. only then call `AIActionHandler.executeAction`.

Migrate all current direct call sites:

- `IntentHandlingStep`;
- `ReadActionResolutionService`;
- `ActionContextParamResolutionSupport`;
- `AIActionToolCallbackFactory`.

Add an architecture test which fails if production code outside the governed invoker or concrete
handler implementation invokes `executeAction` directly. A test-scoped architecture dependency is
acceptable; do not add it to published runtime dependencies.

### 6.6 Canonical Read-Side Evidence

Add `ai.fabric.evidence.AIEvidenceReference`:

```text
evidenceId
content
relevanceScore
source
sourceUrl
vectorSpace
safeMetadata
```

Rules:

- Adapt from filtered `RAGResponse.RAGDocument` results.
- Exclude embeddings, internal provider payloads, hidden metadata, and unsafe source values.
- Preserve entity/revision/provenance identifiers only when policy marks them safe.
- Apply tenant and evidence policy before creating the reference.
- Keep `AIIndexDocument` separate. It remains the versioned write-side queue payload introduced in
  `0.4.0`, not an execution or retrieval DTO.
- Typed execution results return references, not raw vector-provider records.

## 7. P1 Optional Execution Module

### 7.1 Maven And Auto-Configuration

Create:

```text
ai-infrastructure-module/ai-fabric-execution/
  pom.xml
  src/main/java/ai/fabric/execution/...
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports
  src/test/java/ai/fabric/execution/...
```

Add the module to:

- `ai-infrastructure-module/pom.xml` reactor modules;
- the same POM's dependency management/BOM section;
- release source/Javadoc/signing verification.

The module depends on core and Spring Boot only as required. Do not add a second orchestration,
agent, graph, persistence, vector, or model SDK.

### 7.2 Specialist Aggregate

Add:

```text
SpecialistId(name, version)
SpecialistDefinition<I,O>
SpecialistIdentity
SpecialistInstructions
SpecialistExecutionProfile
SpecialistLimits
SpecialistInputAdapter<I>
SpecialistOutputAdapter<O>
SpecialistRegistry
```

`SpecialistDefinition` is the canonical aggregate but delegates concerns to the smaller typed
values above. It declares:

- stable name and version;
- objective and bounded instructions/prompt overlay;
- Java input and output contracts;
- input validation and deterministic model rendering;
- output projection and validation;
- existing Mode name;
- requested capability profile;
- execution strategy;
- limits.

It does not contain:

- authorization decisions;
- provider clients;
- mutable conversation state;
- persistence implementations;
- arbitrary callbacks;
- graph edges;
- UI components.

Registry startup validation must reject:

- duplicate IDs;
- blank/unversioned identities;
- absent input/output adapters;
- unrecognized Mode;
- unsupported strategy;
- invalid limits;
- statically known missing actions;
- contradictory READ/WRITE declarations;
- a WRITE declaration in the P1 READ-only reference specialist.

### 7.3 Execution Strategy

P1 supports only:

```text
DIRECT
SINGLE_PASS
BOUNDED_ITERATIVE
```

These map to existing pipeline and Mode behavior. They are not new reasoning engines.

- `DIRECT` is for a deterministic pipeline invocation without iterative read planning.
- `SINGLE_PASS` permits one model-driven orchestration pass.
- `BOUNDED_ITERATIVE` uses the existing bounded read-action resolution policy and its configured
  maximums.

The Agentic AI Action Resolver uses `BOUNDED_ITERATIVE`.

### 7.4 Gateway Contracts

Add:

```text
AIExecutionRequest<I>
  specialistId
  input
  trustedExecutionContext
  optional ConversationBinding
  deadline
  idempotencyKey when supplied by an application

AIExecutionResult<O>
  invocationId
  specialistId
  status
  typed output
  evidence references
  safe diagnostics
  failure

AIExecutionFailure
  reason
  publicMessage
  retryable

AIExecutionGateway
  execute(AIExecutionRequest<I>)
  submit(AIExecutionRequest<I>)

ExecutionHandle
  invocationId
  durability: EPHEMERAL
  status
  deadline
```

Use a closed specialist ID in the request. Do not add separate optional target strings for
specialist, plan, or coordinator.

### 7.5 Single-Invocation Coordinator

`DefaultAIExecutionGateway` performs:

1. registry lookup;
2. trusted context validation;
3. input type and domain validation;
4. effective profile resolution;
5. deterministic input rendering;
6. structured `OrchestrationRequest` creation;
7. existing pipeline execution;
8. output projection and schema validation;
9. safe evidence projection;
10. typed result creation and observability.

It must not:

- select an unapproved specialist;
- mutate application state outside governed actions;
- invent a conversation;
- retry unknown action outcomes;
- silently substitute deterministic text for an LLM/provider failure.

### 7.6 Ephemeral Submission

`submit` is a real but deliberately limited implementation:

- execute on a configured bounded Spring `TaskExecutor`;
- retain status/result in an in-memory store with configurable TTL;
- expose `EPHEMERAL` durability in every handle;
- reject duplicate live idempotency keys deterministically;
- enforce deadlines and bounded queue capacity;
- allow cancellation before terminal completion;
- return `NOT_FOUND_OR_EXPIRED` after TTL;
- lose non-terminal work on restart by documented design.

Do not name this durable, resumable, or exactly-once. JDBC and workflow adapters require a later
implementation plan tied to a real P3 product.

## 8. Compatibility Design

### 8.1 Existing Interactive Calls

The following remains valid and behaviorally unchanged:

```java
ragOrchestrator.orchestrate(message, orchestrationContext);
```

The legacy call:

- is adapted to `ExecutionSource.INTERACTIVE`;
- retains Mode and position resolution;
- retains current chat-session behavior;
- retains current response types;
- retains action confirmation behavior;
- does not require `ai-fabric-execution`.

### 8.2 New Application Calls

Applications opt into:

```java
AIExecutionResult<AccountResolutionResult> result =
    executionGateway.execute(request);
```

The application call:

- uses a verified service/system initiator;
- binds the current domain subject from server-owned context;
- has no user/session/conversation unless the application explicitly provides an authorized
  conversation binding;
- defaults to `ConversationPersistencePolicy.NEVER` when there is no authorized conversation;
- uses `ConversationPersistencePolicy.READ_ONLY` for an authorized conversation, then records the
  completed turn only after specialist output and evidence validation succeeds;
- receives a typed result or typed visible failure.

### 8.3 No Dual Enforcement Runtime

During development, tests compare old and new paths. The released runtime must not ship a feature
flag which restores direct handler execution and bypasses the governed invoker. Rollback is by
reverting the release, not by exposing an unsafe bypass switch.

## 9. Incremental Change Sets

Each change set must be independently reviewable and green before the next begins.

### Change Set 0 - Freeze Existing Behavior

**Production code:** none.

Add characterization tests for:

- legacy string pipeline validation;
- anonymous and authenticated contexts;
- default, commerce, resolver, and tenant-scoped Mode behavior already represented in tests/apps;
- READ action planning limits;
- confirmation-required WRITE behavior;
- Spring AI tool callback behavior;
- action failure visibility;
- chat persistence and `NEVER_PERSIST`;
- RAG evidence projection currently returned to users.

Do not snapshot timestamps, generated IDs, or entire unstable LLM messages. Assert stable result
types, action names, capability sets, confirmation state, safe metadata, and failure codes.

**Gate:** current core, chat-session, RAG, provider, and Account Resolver tests pass without
production changes.

### Change Set 1 - Create The Independent Reference App

Copy source behavior from:

```text
examples/real-apps/ai-fabric-account-resolver
```

to:

```text
examples/real-apps/agentic-ai-action-resolver
```

Required isolation:

- artifact `agentic-ai-action-resolver`;
- base package `com.ai.fabric.realapps.agenticresolver`;
- independent application name;
- independent H2/Postgres schema names;
- independent Lucene path;
- independent seed/session identifiers;
- independent tests;
- independent Dockerfile and health/build metadata;
- local default port `8105`;
- module entry in `examples/real-apps/pom.xml`;
- no copied `target/`, database files, Lucene segments, logs, or secrets.

At this point it still uses the legacy orchestration path and must pass the same behavioral scenario
corpus as the current app.

**Gate:** both apps compile and test in one clean real-app reactor run; the original app's tracked
files are unchanged.

### Change Set 2 - Trusted Request And Evidence Foundation

Implement:

- `ExecutionSource`;
- trusted principal/subject/context contracts;
- source-aware validation;
- `ConversationPersistencePolicy`;
- `OrchestrationRequest`;
- additive pipeline overload;
- `PipelineContext` request support;
- trusted auth-context adapter;
- `AIEvidenceReference` and safe mapper.

Update chat-session enrichment/recording to consume typed persistence policy while preserving the
legacy metadata projection.

Tests:

- legacy request produces equivalent pipeline context;
- application request succeeds with service principal and no user/session;
- machine request without trusted principal fails;
- request-body metadata cannot elevate scopes;
- application request is not enriched or persisted as chat;
- evidence mapping drops embeddings and unsafe metadata;
- tenant/evidence denial happens before reference creation;
- `AIIndexDocument` remains unaffected.

**Gate:** no action behavior changes and all Change Set 0 tests remain green.

### Change Set 3 - Effective Capabilities And Governed Invocation

Implement:

- requested/effective capability profiles;
- effective resolver and deterministic profile hash;
- legacy Mode adapter;
- capability-aware action catalogue;
- governed invocation request/outcome/service.

Migrate all direct execution paths and prompt/tool exposure.

Tests:

- Mode, specialist request, registry, and authority intersection;
- missing/unknown capability failure;
- denied action absent from prompts and callbacks;
- forged action name denied at final invocation;
- READ/WRITE access-mode enforcement;
- anonymous action contract;
- confirmation cannot be bypassed;
- handler authorization rechecked immediately before execution;
- handler exception and null result remain visible;
- all four existing direct-call paths use the invoker;
- architecture rule rejects new direct handler execution.

**Gate:** behavior-compatible legacy tests pass and direct production invocation exists only in the
governed service.

### Change Set 4 - Add `ai-fabric-execution`

Implement the module, specialist aggregate, registry, adapters, strategy, gateway contracts,
auto-configuration, and startup validation.

Do not merge a module containing only interfaces. The same change set must include:

- immutable in-memory registry;
- real startup validator;
- default synchronous gateway/coordinator;
- a test specialist that invokes the real pipeline with deterministic providers;
- typed input and output validation;
- complete auto-configuration tests.

**Gate:** the module passes unit and Spring context tests, publishes source/Javadoc artifacts
locally, and is absent when users do not add the dependency.

### Change Set 5 - Implement Ephemeral `submit`

Implement the bounded executor, TTL store, handle lookup, duplicate-key handling, deadline, queue
rejection, cancellation, and expiry semantics.

Tests use a controllable clock and executor. They must cover restart-loss semantics by constructing
a new store and proving old handles are unavailable.

**Gate:** no API or documentation implies durability; saturated and expired states are explicit.

### Change Set 6 - Wire The Agentic AI Action Resolver

Add:

- specialist ID `account-resolver@1`;
- typed `AccountResolutionRequest`;
- typed `AccountResolutionResult`;
- deterministic input renderer;
- output validator/projector;
- resolver Mode binding;
- account-policy vector-space scope;
- `get_account_profile` as the only specialist READ action in the first gate;
- interactive endpoint;
- typed application endpoint;
- no specialist WRITE exposure;
- app README, architecture flow, Dockerfile, health/build metadata, and smoke requests.

Suggested endpoints:

```text
POST /api/agentic-resolver/chat
POST /api/agentic-resolver/evaluate
GET  /api/demo/health
```

The backend derives account/user/tenant context. Requests never accept trusted identity,
subscription ID, account ID, tenant ID, or scopes from the caller body when those values are
available from authenticated application state.

**Gate:** one specialist, one Mode, one effective capability profile, and the same providers serve
both endpoints. The application endpoint creates no chat turn.

### Change Set 7 - Release Proof And Documentation

Complete:

- compatibility/migration guide;
- new module reference;
- typed Java quickstart;
- trusted-context security guidance;
- action invocation architecture;
- read-side evidence versus `AIIndexDocument` explanation;
- reference-app deployment guide;
- release notes;
- provider-keyed smoke instructions;
- public API/Javadoc review.

Do not start P1.1 WRITE work until the P1 scorecard is reviewed.

## 10. Reference Proof Scenarios

Run every deterministic scenario with mock providers first, then the marked scenarios with a real
OpenAI key.

| Scenario | Entry | Expected proof |
| --- | --- | --- |
| Explain missing payment blocker | Interactive | Uses current account profile READ plus policy evidence; no WRITE action exposed. |
| Evaluate order readiness | Typed Java/API | Returns typed blockers, recommendation, and evidence with no conversation. |
| Follow-up in chat | Interactive | Uses real backend-owned chat session; UI sends only the new message. |
| Cross-account request | Both | Fails closed because subject/account binding comes from trusted server context. |
| Unauthorized vector space | Both | Evidence is denied before model context and result projection. |
| Forged WRITE action | Both | Action is absent from exposure and rejected at final invocation. |
| Provider unavailable | Both | Typed provider failure; no deterministic answer masks the error. |
| Retrieval unavailable | Both | Typed evidence/retrieval failure; no unsupported account conclusion. |
| Invalid typed output | Application | Output validation failure; raw model payload is not returned as domain truth. |
| Budget exhausted | Both | Explicit bounded-execution failure with safe diagnostics. |
| Current Account Resolver corpus | Interactive comparison | Existing app remains unchanged; overlapping READ outcomes do not regress. |
| Real OpenAI blocker analysis | Both | Real model follows the scoped READ/evidence contract and returns schema-valid output. |

The application-call result must make these distinctions explicit:

- no blocker found from sufficient evidence;
- blocker found;
- insufficient evidence;
- policy denied;
- provider/retrieval failure;
- output-schema failure.

## 11. Test Matrix

### 11.1 Unit

- trusted context invariants;
- request-source validation;
- effective capability intersections;
- deterministic profile hash;
- action catalogue filtering;
- governed invocation;
- evidence mapping;
- specialist registry validation;
- input rendering and output projection;
- execution failure mapping;
- ephemeral handle lifecycle.

### 11.2 Module Integration

- core pipeline legacy and structured entries;
- chat-session persistence policy;
- RAG evidence filtering;
- Spring AI callback through governed invocation;
- execution auto-configuration;
- execution module absent/present context behavior;
- bounded iterative read action through the gateway.

### 11.3 Real App

- both Account Resolver apps in the same reactor;
- independent seed and storage state;
- interactive chat;
- typed application call;
- no conversation persisted for application calls;
- account/tenant denial;
- Docker health/build metadata;
- mock profile startup.

### 11.4 Real Provider

Real API tests are required only where model behavior matters:

- scoped intent/action selection;
- bounded iterative account analysis;
- evidence-grounded typed output;
- invalid/insufficient evidence behavior;
- provider failure visibility.

Use environment variables, never committed keys:

```text
OPENAI_ENABLED=true
OPENAI_API_KEY=<private runtime secret>
OPENAI_MODEL=<approved model>
OPENAI_EMBEDDING_MODEL=<approved embedding model>
```

Do not print secrets, request authorization headers, or raw sensitive account data.

### 11.5 Required Commands

During focused development:

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-core -am test
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-chat-session,ai-fabric-rag -am test
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-execution -am test
```

Before testing the independent real app against the unreleased local module:

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-execution -am install
mvn -f examples/real-apps/pom.xml \
  -pl ai-fabric-account-resolver,agentic-ai-action-resolver \
  -am test
```

Release gate:

```bash
mvn -f ai-infrastructure-module/pom.xml clean verify
mvn -f examples/real-apps/pom.xml clean verify
```

No command may add `-DskipTests`.

## 12. Security And Governance Gates

P0/P1 is not complete unless all are proven:

1. trusted identity is derived by backend adapters;
2. service calls do not masquerade as end users;
3. subject and tenant bindings cannot come from untrusted request payloads;
4. specialist capability declarations cannot expand Mode or application authority;
5. denied actions are absent from model/tool exposure;
6. final invocation repeats capability and handler authorization;
7. evidence is tenant/policy filtered before model context;
8. only safe evidence metadata is returned;
9. application calls do not enter chat history by default;
10. raw provider output cannot become typed domain truth without validation;
11. failures are not hidden by fallback answers;
12. logs contain IDs/hashes and safe diagnostics, not secrets or raw sensitive content.

## 13. Observability

Emit safe observations for:

- invocation ID;
- specialist name/version;
- execution source;
- Mode/profile;
- effective profile hash;
- input/output schema IDs;
- provider purpose/model identifier;
- retrieval spaces and safe evidence IDs;
- model/action call counts;
- latency and bounded-iteration count;
- result/failure reason;
- ephemeral submission status.

Do not log:

- raw trusted context;
- access tokens or API keys;
- hidden action parameters;
- unsafe evidence metadata;
- raw PII;
- full model prompts by default.

Spring AI observations may supply provider/model telemetry. AI Fabric must add the invocation,
specialist, capability, evidence, and action correlation.

## 14. Release And Migration

### 14.1 Versioning

A new public module and typed execution API justify a minor release: `0.5.0`, subject to API review.

### 14.2 Existing Users

Existing `0.4.x` users should need no code or configuration change:

- legacy string orchestration remains;
- Mode YAML remains;
- current providers remain;
- current action annotations remain;
- current RAG/vector APIs remain;
- current chat-session behavior remains;
- the execution module is opt-in.

Document behavior-neutral internal action routing through the governed invoker.

### 14.3 Packaging

Verify:

- module present in BOM dependency management;
- source and Javadoc JARs;
- Spring Boot auto-configuration metadata;
- no optional execution classes leak into core startup;
- no new runtime test dependency;
- Maven Central staging validation;
- clean consumer app using only published artifacts.

### 14.4 Reference Deployment

Deploy `agentic-ai-action-resolver` separately after the framework release. Do not replace or
redirect the existing Account Resolver backend or UI.

Health must expose:

- application version;
- AI Fabric version;
- deployed source commit;
- provider readiness without secret values;
- execution module readiness;
- specialist registry readiness.

## 15. Rollback Strategy

- Before release, revert the current change set while retaining characterization tests.
- After release, consumers can remove the optional `ai-fabric-execution` dependency and continue
  using legacy orchestration.
- If governed invocation introduces a regression, release a corrective framework version. Do not
  enable direct handler bypass.
- Keep the original Account Resolver deployment as the stable comparison and user-facing fallback.
- The new app has independent storage and can be stopped without affecting the original app.

## 16. Explicitly Deferred

The following require later numbered plans and must not enter P0/P1:

- specialist WRITE receipts and profile-pinned pending confirmation;
- fixed multi-specialist plans;
- `NeedsUserInput` continuation;
- delegation or handoff;
- conversation-manager specialist;
- parallel fan-out/fan-in;
- durable execution or review tables;
- JDBC execution adapter;
- scheduler, event-broker, or workflow-engine implementation;
- dynamic model-selected specialist discovery across an unrestricted registry;
- generic agent builder UI;
- LangChain4j, LangGraph, LangGraph4j, Embabel, Microsoft Agent Framework, or workflow-engine
  dependencies.

The next plan should be `0002-governed-specialist-write-and-receipt-implementation-plan.md` only
after the READ-only P1 proof passes its scorecard.

## 17. Definition Of Done

P0/P1 is complete only when:

- [x] Existing Mode-only behavior is characterized before production changes.
- [x] Current core, RAG, chat-session, action, provider, and real-app tests remain green.
- [x] Structured application requests run without fake user/session/conversation identifiers.
- [x] Trusted initiator and subject context is first-class and cannot be populated from request
      payloads.
- [x] One immutable effective capability profile controls exposure and invocation.
- [x] All `ai-fabric-core` orchestration action-handler calls and all new Agentic Resolver
      paths use the governed invocation service. Legacy real-app migration is outside this scope.
- [x] Read-side evidence uses `AIEvidenceReference`; `AIIndexDocument` remains write-side.
- [x] `ai-fabric-execution` is optional, auto-configured, documented, and fully tested.
- [x] Specialist registry rejects invalid definitions at startup.
- [x] Synchronous and ephemeral submission semantics are honest and tested.
- [x] `account-resolver@1` serves chat and typed application calls through the same definition.
- [x] The new app is independent from the existing Account Resolver.
- [x] The first specialist is READ-only and cannot expose or invoke a WRITE.
- [x] Typed output validation and all failure classes are visible.
- [x] Mock-provider, packaged-runtime, Docker, and real OpenAI proofs pass.
- [x] No P0/P1 test is skipped or disabled. Pre-existing key-gated provider suites and explicitly
      disabled performance benchmarks are reported separately and are not counted as P0/P1 proof.
- [x] No empty, dummy, fallback, or stub implementation ships.
- [x] Migration, security, troubleshooting, and release documentation is complete.
- [x] The evaluation scorecard gives an explicit P0/P1 decision and keeps specialist WRITE work
      unapproved until its separate receipt and durability plan passes review.
