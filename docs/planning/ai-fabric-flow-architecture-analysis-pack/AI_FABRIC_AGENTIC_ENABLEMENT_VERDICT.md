# AI Fabric Agentic Enablement Portfolio Verdict

- **Status:** Architecture and product verdict
- **Date:** 2026-07-28
- **Framework baseline:** AI Fabric `0.4.0`
- **Code reviewed at:** `e29961650bb1`
- **Proposal reviewed:** [Specialist-Defined Agentic Enablement Proposal](./Full-Proposal/Product-evolution-proposal.md)
- **Case pack reviewed:** [Flow Analysis Documents](./ai-fabric-flow-analysis-documents/README.md)
- **Implementation plan:** [P0/P1 Agentic Enablement](./implementation-plans/0001-agentic-enablement-p0-p1-implementation-plan.md)
- **Scope:** Product value, framework fit, contract correctness, implementation order, and proof strategy

## 1. Executive Verdict

The proposal is strategically valid and worth pursuing.

It can move AI Fabric from a framework commonly entered through a chat request into an
application-owned AI enablement runtime that supports:

- interactive assistants;
- typed intelligence called from Java services;
- event-triggered and scheduled analysis;
- evidence-linked decision workflows;
- governed application actions;
- durable human review;
- bounded specialist composition.

The strongest part of the proposal is not "multi-agent." It is the combination of:

```text
live application data
+ explicit specialist scope
+ current application authority
+ governed evidence and actions
+ typed results
+ deterministic continuation
```

That is a credible differentiation for Java and Spring Boot applications. Generic agent libraries
already offer routing, sequential, parallel, supervisor, and handoff patterns. AI Fabric should not
compete by merely recreating those patterns. It should make them safe and useful over live Java
application state.

### Primary decision

Do not build the complete supporting runtime before proving a product, and do not implement each
product independently.

Use this strategy:

> Design the complete architectural boundaries now, implement a thin shared execution spine, and
> expand it through one production-quality vertical proof at a time.

This is an "architecture-wide, implementation-narrow" approach:

1. preserve the complete conceptual model so early contracts do not block later flows;
2. implement only the common contracts required by the next real product;
3. prove each addition in an existing real application;
4. measure whether the next layer improves quality, governance, reuse, latency, or developer
   experience;
5. stop when a simpler single-specialist design is sufficient.

The pack's P0 through P5 ordering is broadly correct. The implementation scope inside P1 should,
however, be narrowed before coding begins.

## 2. Why The Direction Fits AI Fabric

AI Fabric `0.4.0` already has the important inner runtime pieces:

- [`RAGOrchestrator`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/RAGOrchestrator.java)
  is the current public orchestration facade.
- [`DefaultOrchestrationPipeline`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/DefaultOrchestrationPipeline.java)
  provides ordered security, policy, intent, retrieval, action, and response steps.
- [`ReadActionResolutionService`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/information/ReadActionResolutionService.java)
  already implements a bounded plan -> READ action -> observation loop.
- [`OrchestrationProperties.ModeOverrides`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/config/OrchestrationProperties.java)
  already controls reusable orchestration behavior and restrictions.
- [`AIActionRegistry`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/AIActionRegistry.java)
  discovers application-owned action contracts and handlers.
- [`PendingAction`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/PendingAction.java)
  and [`PendingActionStore`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/PendingActionStore.java)
  support active-conversation confirmation.
- [`OrchestrationContext`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/OrchestrationContext.java)
  carries the current interaction context, position, Mode hint, attachments, and policy.
- The `0.4.0` indexing lifecycle already has a class-free
  [`AIIndexDocument`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/indexing/model/AIIndexDocument.java),
  transaction-aware handoff, retries, dead-letter state, source versions, and stale-work
  protection.

The proposal therefore extends a real bounded foundation. It does not need a second provider path,
another action registry, another vector abstraction, or a replacement RAG engine.

### Current gaps that justify a shared layer

The code also confirms several real gaps:

1. The public pipeline still accepts only `String query` plus `OrchestrationContext`.
2. `OrchestrationContext` requires a user or session even for work that should have a real service
   principal and no conversation.
3. direct action discovery can see the broad registered catalogue when actions are enabled;
4. planner READ filtering is Mode-specific, but there is no one per-specialist capability snapshot;
5. action lookup and invocation occur in several paths, so one effective capability policy is not
   yet applied everywhere;
6. pending actions are not bound to specialist, prompt, schema, plan, Mode, or capability-profile
   versions;
7. the generic `OrchestrationResult` is not the typed result contract required by Java service
   callers;
8. there is no execution identity, fixed plan state, branch-specific wait, or durable review task.

These are good reasons to build a small common execution layer.

## 3. Evaluation Method

Every proposed flow should be evaluated as a product capability, not only as an interesting
architecture pattern.

### 3.1 Scorecard

Score each candidate from 0 to 5 on the following dimensions:

| Dimension | Question | Weight |
| --- | --- | ---: |
| User value | Does it solve a clear application or operator problem? | 20% |
| AI necessity | Does model reasoning materially help compared with deterministic Java? | 15% |
| AI Fabric fit | Does it build on live data, RAG, actions, policy, memory, or privacy? | 15% |
| Cross-product reuse | Will at least two credible products use the primitive? | 15% |
| Governance value | Does AI Fabric provide meaningful control beyond a model call? | 15% |
| Proofability | Can it be demonstrated and measured in a current real app? | 10% |
| Delivery risk | Is the complexity proportionate to the expected value? | 10% |

Interpret the weighted score as:

| Score | Verdict |
| --- | --- |
| 80-100 | Build in the next release sequence |
| 65-79 | Pilot after its prerequisites |
| 50-64 | Keep designed, but defer implementation |
| Below 50 | Reject or return for material redesign |

The number is a decision aid, not false precision. A candidate also has to pass all mandatory
gates.

### 3.2 Mandatory gates

A flow is not ready to implement unless it has:

1. one named product and one concrete user or application outcome;
2. a deterministic non-AI baseline for comparison;
3. explicit identity, tenant, subject, and authority ownership;
4. explicit evidence and action boundaries;
5. a typed successful result and typed visible failures;
6. defined behavior for provider failure, missing evidence, malformed output, timeout, and
   cancellation;
7. a testable reason why one specialist is insufficient before adding more specialists;
8. a real-app proof with measurable acceptance criteria;
9. no hidden fallback that fabricates intelligence or hides model/provider failure;
10. no new framework-owned copy of application business truth.

## 4. Case-By-Case Verdict

| Case | Verdict | Priority | Reason |
| --- | --- | --- | --- |
| 01 Enablement landscape | **Accept as north-star architecture** | Cross-cutting | It is a portfolio map, not one implementation unit. Keep it as the boundary model and never deliver it as one epic. |
| 02 Interactive intelligent application | **Build as the first compatibility proof** | P1 | This formalizes what AI Fabric already does well and proves specialist scope without requiring durability or multiple specialists. |
| 03 Application-called intelligence | **Build first** | P1 | This is the most important new product surface. It removes the artificial chat boundary and makes AI Fabric a reusable Java capability. |
| 04 Fixed multi-specialist plan | **Pilot after the single-specialist baseline** | P2 | Valuable for typed decomposition and auditability, but only where evaluation proves that separate contexts improve results. |
| 05 Parallel specialist analysis | **Defer** | P5 | Valid for independent read-only checks, but it multiplies cost, cancellation, isolation, and fan-in complexity. Sequential proof must come first. |
| 06 Proactive intelligence | **Build after the gateway and durable state boundary** | P3 | Strategically important because it enables intelligence before a user opens chat. Start with application events, not every trigger type. |
| 07 Missing input and safe resume | **Build with fixed plans** | P2, then P3 | A typed wait is a foundational continuation primitive. Implement in-memory first, then durable resume only when a boundary is crossed. |
| 08 Durable human review | **Build for enterprise workflows** | P3 | High value and aligned with governed AI, but it needs durable execution identity and application-owned reviewer authorization first. |
| 09 Conversation manager | **Defer and evidence-gate** | P4 | Useful only when direct routing or a root specialist cannot handle genuine ambiguity. It adds another model call and must not become a control plane. |
| 10 Delegation and handoff | **Pilot one-level delegation later** | P3+ | The distinction is sound, but fixed plans cover most known flows more predictably. Implement delegation before handoff, with depth one. |
| 11 Governed action lifecycle | **Mandatory hardening** | P0/P1 | This is central to AI Fabric's differentiation. One capability snapshot and one governed invocation boundary must cover every action path. |
| 12 Live data intelligence loop | **Preserve and integrate** | P0/P1 | This is already a strong differentiator. Add provenance and revision linkage; do not rebuild synchronization or introduce a post-action indexer. |

### Portfolio grouping

The twelve documents contain three different kinds of material:

#### Product surfaces

- interactive AI-enabled applications;
- application-called typed intelligence;
- proactive operations;
- human-reviewed decision workflows;
- batch/file intelligence.

#### Reusable enabling primitives

- specialist definitions;
- effective capabilities;
- canonical execution ingress;
- typed results;
- fixed plans;
- input wait/resume;
- action finalization;
- durable review.

#### Advanced coordination options

- conversation manager;
- delegation and handoff;
- parallel fan-out/fan-in.

The advanced coordination options should not determine the first architecture. They should consume
the proven primitives later.

## 5. Required Corrections Before Implementation

The proposal is strong, but the following issues should be corrected in the design before code is
written.

### 5.1 Reuse the engine, not the current text-only facade

The proposal says application-called intelligence must not turn typed input into a synthetic user
message. That is correct.

It also says every specialist step should invoke `RAGOrchestrator`. The current method is:

```java
OrchestrationResult orchestrate(String query, OrchestrationContext context)
```

The underlying [`Pipeline`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/Pipeline.java)
is also text-shaped:

```java
OrchestrationResult execute(String query, OrchestrationContext context)
```

Calling that facade from a typed application request would either lose the typed input or serialize
it into the fake text path that the proposal rejects.

Recommended correction:

```text
legacy chat request
  -> RAGOrchestrator compatibility facade
  -> structured internal orchestration request
  -> existing pipeline steps

specialist/application/event request
  -> AIExecutionGateway
  -> structured internal orchestration request
  -> the same pipeline steps
```

Introduce one structured internal request/envelope and evolve the existing pipeline to consume it.
Keep `RAGOrchestrator.orchestrate(String, OrchestrationContext)` as a compatibility adapter. This is
one engine with two safe adapters, not a second engine.

### 5.2 All continuation operations need trusted scope

The conceptual gateway currently shows:

```java
resume(String executionId, ResumeInput input)
cancel(String executionId, CancellationReason reason)
status(String executionId)
result(String executionId)
```

An execution ID is correlation, not authorization. Every resume, cancel, status, and result
operation must receive or resolve trusted current tenant/caller scope and reauthorize access.

Conceptually:

```java
resume(ExecutionHandle handle, ResumeInput input, TrustedExecutionContext context)
cancel(ExecutionHandle handle, CancellationReason reason, TrustedExecutionContext context)
status(ExecutionHandle handle, TrustedExecutionContext context)
result(ExecutionHandle handle, TrustedExecutionContext context)
```

Network adapters must derive the trusted context from authenticated server state. They must never
accept it from an untrusted payload.

### 5.3 Use a closed target type instead of three optional strings

`specialistRef`, `planRef`, and `selectionKey` as independent optionals permit conflicting or empty
states and remain stringly typed.

Prefer a sealed target:

```java
sealed interface ExecutionTarget
    permits SpecialistTarget, PlanTarget, RegisteredSelectionTarget {}
```

The request can then carry one optional target. An absent target is allowed only when the trusted
entry point has a registered deterministic mapping.

### 5.4 Keep `SpecialistDefinition` complete as an aggregate, but release it incrementally

The proposed aggregate is conceptually correct. Implementing all trigger, delegation, review,
conversation, model, limit, and selection fields in the first release would freeze a large public
API before there is runtime evidence.

P1 should require:

- identity and version;
- Mode reference;
- objective and registered instruction profile;
- typed input/output contracts;
- evidence scope;
- direct READ, planner READ, and proposable WRITE scopes;
- execution strategy;
- core limits;
- dialogue eligibility.

Add durable review policy, delegation, trigger declarations, and advanced coordination fields in
the phase that implements them. The top-level definition remains the complete aggregate; its
versioned nested contracts grow deliberately.

### 5.5 Separate synchronous execution from asynchronous submission

The first application-called example submits a handle and immediately looks up a result. That is
awkward for normal in-process Java use.

Provide both:

```java
<I, O> AIExecutionResult<O> execute(SpecialistCall<I, O> call);
<I, O> ExecutionHandle<O> submit(SpecialistCall<I, O> call);
```

Both methods must delegate to the same gateway/coordinator. `execute` is the normal bounded
synchronous API. `submit` exists for asynchronous, waiting, or durable work.

### 5.6 Define a canonical read-side evidence reference

The proposed `EvidenceReference` does not currently exist in framework code. It must be defined
against the `0.4` read-side RAG contract, not against `AIIndexDocument`.

The boundary from [ADR 0017](../0017-0.4-post-release-retrieval-connector-hardening-plan.md) must
remain:

| Contract | Purpose |
| --- | --- |
| `AIIndexDocument` | Durable write/indexing work payload |
| `RAGResponse.RAGDocument` | Request-scoped retrieved evidence |
| Proposed `AIEvidenceReference` | Safe, stable provenance retained in specialist/execution results |

The reference should contain only bounded safe provenance such as:

- evidence/document ID;
- authorized vector space;
- source reference or safe URL;
- relevance/similarity score;
- source revision and projection version when known;
- provider/connector provenance;
- allowlisted safe metadata;
- optional content hash or request-scoped document reference.

It must not persist the complete document, embeddings, arbitrary connector metadata, or indexing
queue fields by default.

### 5.7 Introduce action receipts without misrepresenting legacy results

Current `@ActionExecute` handlers return `ActionResult` or an action payload. A new
`ActionReceipt` is stronger because it explicitly states whether a write committed, was rejected,
failed before commit, or has an unknown outcome.

Do not silently convert every successful legacy `ActionResult` into an authoritative committed
receipt.

Recommended sequence:

1. centralize capability filtering and action invocation;
2. keep legacy Mode-only behavior compatible;
3. add an explicit receipt-capable handler contract for new specialist-coordinated WRITE actions;
4. require receipts for durable or multi-step WRITE continuation;
5. provide migration diagnostics for legacy WRITE handlers;
6. never infer commit from model text, HTTP status, or lack of an exception.

The first specialist in `agentic-ai-action-resolver` can remain READ-only while this contract is
proven separately.

### 5.8 Treat current context metadata as input to policy, not proof by itself

`OrchestrationContext` has useful subject, tenant, caller, and scope metadata keys, but its core
object remains a mutable application-built context with a generic metadata map.

The specialist gateway needs a typed, server-created `TrustedExecutionContext` or resolvable
reference that distinguishes:

- initiator principal;
- optional affected subject;
- tenant/customer/deployment;
- granted and requested scopes;
- source/entry point;
- authority policy reference;
- correlation and deadline.

An adapter may project that trusted object into the legacy `OrchestrationContext`. The generic map
must not become the durable authority record.

### 5.9 Internal proposal links

The case documents now link to the checked-in proposal at
`../Full-Proposal/Product-evolution-proposal.md` relative to the case directory. Keep that path
valid when moving or publishing this pack.

## 6. The Thin Shared Spine

The first shared layer should contain only what every valid product needs.

### 6.1 P0: authority and compatibility foundation

Implement or finalize:

1. `TrustedExecutionContext` and source-specific trusted context factories;
2. `EffectiveCapabilitiesResolver`;
3. immutable `ResolvedSpecialistProfile`;
4. one capability-aware action catalogue view;
5. one governed action invocation boundary over `AIActionRegistry`;
6. canonical read-side `AIEvidenceReference`;
7. legacy Mode-only regression tests;
8. capability enforcement at:
   - prompt/action exposure;
   - intent post-processing;
   - direct READ resolution;
   - planner READ resolution;
   - WRITE proposal validation;
   - final handler invocation.

This work has value even before multi-specialist execution because current action access is resolved
across several code paths.

### 6.2 P1: minimal specialist execution

Add:

1. minimal versioned `SpecialistDefinition<I,O>`;
2. immutable registry and startup validation;
3. typed `SpecialistInvocation`;
4. structured internal orchestration request;
5. `AIExecutionGateway.execute(...)` and `submit(...)`;
6. typed `AIExecutionResult<O>`;
7. explicit `ExecutionSource`;
8. deterministic target mapping;
9. one optional conversation binding and one dialogue owner;
10. no mandatory execution database.

P1 does not need:

- execution graphs;
- durable review;
- conversation manager;
- delegation;
- handoff;
- parallel fan-out;
- a generic workflow engine.

### 6.3 P2: deterministic composition only

After the P1 product proof:

1. introduce fixed acyclic sequential plans;
2. use registered typed input mappers;
3. use deterministic Java decisions and aggregation;
4. add typed `NeedsUserInput`;
5. resume only the waiting invocation;
6. keep synchronous fixed plans in memory;
7. compare quality and cost against the one-specialist baseline.

Do not split the Agentic AI Action Resolver into several specialists unless the evaluation proves
a material benefit over both its one-specialist implementation and the current Account Resolver
baseline.

### 6.4 P3: durability at real boundaries

Add persistence only for:

- event-triggered or retried work;
- waits that cross a request/process boundary;
- durable human review;
- action-outcome reconciliation;
- long-running batch work;
- recovery after restart.

Provide:

- in-memory implementation for tests and bounded local work;
- JDBC implementation for the reference runtime;
- an SPI for an adopter-owned workflow system later.

AI Fabric should define execution semantics and governance. It should not become a general event
broker, scheduler, or distributed workflow product.

### 6.6 Keep The Existing Demo As An Independent Baseline

Do not retrofit the current `ai-fabric-account-resolver` real app or its live deployment.

Create a separate real app by copying it to:

```text
examples/real-apps/agentic-ai-action-resolver
```

The new app should have its own artifact identity, configuration, Dockerfile, health/build
metadata, seed state, sessions, tests, backend deployment, and AI Fabric website demo route. It may
reuse the domain specialist identity `account-resolver@1`; that identifier describes the
specialist contract, while `agentic-ai-action-resolver` identifies the independently deployable
reference product.

The original app remains the frozen behavioral baseline. Run the same scenario corpus against both
apps so the new execution layer must prove its value without hiding regressions inside an upgraded
demo. Do not introduce a shared demo-business-code abstraction merely to avoid the initial copy;
both apps should share AI Fabric framework contracts, not mutable runtime or test state.

## 7. Recommended Vertical Delivery

### Release slice 1: Agentic AI Action Resolver

Use the new `agentic-ai-action-resolver` app as the first proof, seeded from the existing Account
Resolver while leaving the original app and deployment unchanged.

Deliver:

- one `account-resolver@1` definition;
- current `resolver` Mode unchanged;
- `get_account_profile` in the effective planner READ set;
- explicit account-policy evidence scope;
- typed `AccountResolutionRequest`;
- typed `AccountResolutionResult`;
- bounded iterative execution using the existing read-action loop;
- interactive invocation with one dialogue owner;
- Java application invocation with a service principal and no conversation;
- visible provider, evidence, schema, policy, and budget failures;
- no mandatory new storage;
- no WRITE action in the first acceptance gate.

This proves the new abstraction while preserving the current intelligence.

### Release slice 2: Governed receipt-capable action

Add one application-owned WRITE action with:

- specialist proposal scope;
- current application authorization;
- explicit confirmation;
- idempotency key;
- authoritative `ActionReceipt`;
- unknown-outcome test;
- existing live-sync update path;
- no hidden post-action mutation;
- no blind retry.

### Release slice 3: Fixed plan and missing input

Start with an explicit one-step plan for compatibility. Add a second specialist only when baseline
evaluation justifies it.

Prove:

- typed step mapping;
- independent capability profiles;
- no privilege union;
- deterministic aggregation;
- one `NeedsUserInput` wait and branch-specific resume;
- cancellation and deadline behavior.

### Release slice 4: Proactive Behavior Signals or Account Resolution

Use an existing application event to submit through the same gateway:

- real service/system initiator;
- no fake user;
- no fake conversation;
- deterministic trigger mapping;
- stable idempotency;
- typed recommendation or review task;
- visible failures;
- no automatic mutation by default.

Behavior Signals is a good reference for event-driven analysis. Agentic AI Action Resolver is the
reference for a governed case/review queue; the current Account Resolver remains the comparison
baseline.

### Release slice 5: Durable review

Add JDBC-backed execution and review state only now.

Prove:

- persist-before-dispatch;
- tenant-scoped reviewer authorization;
- duplicate-safe dispatch and decision;
- expiry and stale-evidence denial;
- correction as a revised proposal;
- approval followed by final action revalidation;
- post-outcome review for `OUTCOME_UNKNOWN`.

### Later slices

Implement in this order only when demanded:

1. one-level delegation;
2. explicit handoff;
3. optional conversation manager;
4. read-only parallel fan-out/fan-in.

## 8. Recommended Module Boundary

Avoid spreading the first implementation across many new modules.

Suggested shape:

| Module | Responsibility |
| --- | --- |
| `ai-fabric-core` | Small stable contracts shared with current orchestration: trusted context, evidence reference, capability view, typed finish/failure vocabulary |
| New `ai-fabric-execution` | Specialist registry, effective profile resolution, gateway, invocation envelope, in-memory coordinator, fixed plans when introduced |
| Existing action/RAG/chat/indexing modules | Continue to own their current domain contracts and implementations |
| Later `ai-fabric-execution-jdbc` | Optional durable execution, input wait, review, and recovery adapter |
| `ai-fabric-starter` | Conditional auto-configuration and validation |

Do not create separate modules for specialist, plan, delegation, supervisor, parallel, and review
before their contracts are independently useful.

## 9. Proof And Release Gates

### 9.1 Compatibility gate

- all current Mode-only tests pass unchanged;
- current real apps compile and run;
- no new specialist configuration is required by existing applications;
- current chat and confirmation behavior remains compatible;
- no second provider, action, retrieval, or indexing path exists.

### 9.2 Security gate

- explicit empty scopes mean no capability;
- missing new specialist scopes fail registration;
- unauthorized specialist and target selection fail closed;
- effective capability is enforced before model exposure and before execution;
- every continuation operation is tenant/caller scoped;
- pending confirmation cannot resume under a wider profile;
- conversation reference does not grant transcript access;
- machine sources do not invent human identity or conversation state.

### 9.3 Intelligence gate

- compare the Agentic AI Action Resolver specialist result with the current Account Resolver
  baseline;
- record grounded-answer correctness and unsupported claims;
- verify typed result validity across supported providers;
- make provider and schema failures visible;
- measure model calls, tokens, latency, and cost;
- do not add a second specialist unless it improves a declared metric.

### 9.4 Action gate

- proposals are never rendered as completed operations;
- application authorization is rechecked immediately before execution;
- one handler invocation has one stable idempotency identity;
- `COMMITTED`, `REJECTED`, `FAILED_BEFORE_COMMIT`, and `OUTCOME_UNKNOWN` remain distinct;
- unknown outcomes are not replayed blindly;
- live-sync failure does not rewrite a committed business result as failed.

### 9.5 Durability gate

- duplicate source events resolve to the same logical execution;
- optimistic transitions reject stale callbacks;
- resume, review, and receipt handling are idempotent;
- tenant scope is mandatory in storage queries;
- restart tests recover waits without replaying completed side effects;
- sensitive prompts, documents, and transcripts are not persisted by default.

## 10. Framework Versus LoomAI Platform

Keep the ownership boundary explicit.

### AI Fabric framework

The embedded runtime should own:

- specialist and plan contracts;
- effective capability resolution;
- trusted execution ingress;
- deterministic coordination;
- typed evidence and results;
- governed action invocation;
- optional execution/review storage SPIs;
- provider, RAG, memory, privacy, and live-data integration;
- tests and lifecycle observability.

### LoomAI platform

After the framework contracts are stable, the platform can add:

- managed specialist and prompt-profile catalogues;
- version promotion and rollback;
- policy assignment;
- evaluation datasets and scorecards;
- execution trace exploration;
- review inboxes and operational dashboards;
- usage/cost reporting;
- deployment and environment management;
- tenant administration.

The platform should manage and observe registered framework definitions. It should not bypass the
application-owned authority and handler boundaries.

## 11. Ecosystem Position

### Binding Dependency Decision

Spring AI is AI Fabric's approved commodity AI infrastructure provider where its stable contracts
are relevant and useful. AI Fabric should reuse Spring AI for capabilities such as model and
embedding access, structured output, tool-call transport, MCP integration, advisors, observability,
document ETL, and evaluation instead of rebuilding equivalent provider plumbing.

Spring AI remains below AI Fabric's public application-enablement contracts:

```text
AI Fabric specialist, evidence, authority, action, receipt, and execution contracts
                                  ↓
          Spring AI commodity model/tool/MCP/telemetry infrastructure
                                  ↓
                           model providers
```

Using Spring AI must not transfer ownership of tenant policy, effective capabilities, confirmation,
review, WRITE execution, action receipts, execution state, or business truth. AI Fabric may use
Spring AI automatic tool execution only where the resolved capability and action policy make that
safe; governed writes must continue through the AI Fabric and application-owned execution
boundary.

This is an incremental reuse rule, not a prerequisite project. Implement each AI Fabric phase using
the Spring AI capabilities already available and useful at that time. Do not delay an approved
AI Fabric contract while waiting for Spring AI to expose an unrelated higher-level agent API.

LangChain4j, LangGraph, LangGraph4j, Embabel, Microsoft Agent Framework, and general durable
workflow engines are a **future technology watch list**. During the current delivery phases they:

- are not framework-core dependencies;
- do not define AI Fabric public types, plans, state, or action semantics;
- do not block P0/P1 implementation or release;
- do not require speculative adapters;
- may be evaluated later, behind a real execution or durability SPI, after AI Fabric's contracts
  and the Agentic AI Action Resolver baseline are stable.

Future evaluation must be isolated from the production implementation and must prove a measurable
reduction in complexity or operational risk without bypassing AI Fabric governance. Until then,
the implementation proceeds with AI Fabric's thin deterministic execution spine and Spring AI
underneath where useful.

Current Spring AI guidance distinguishes predefined workflows from agents that dynamically direct
their own work, recommends starting with the simplest adequate pattern, and provides model,
structured-output, and tool-calling primitives. AI Fabric should continue to use those primitives
under its policy layer rather than rebuild model integration:

- [Spring AI - Building Effective Agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)
- [Spring AI - Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)

LangChain4j already exposes sequential, parallel, loop, and supervisor-oriented agentic patterns,
but its agentic module is documented as experimental. AI Fabric can learn from those composition
patterns without adopting a second model/provider/runtime stack:

- [LangChain4j - Agents and Agentic AI](https://docs.langchain4j.dev/tutorials/agents/)

Other future evaluation candidates:

- [LangGraph - Overview](https://docs.langchain.com/oss/python/langgraph/overview)
- [LangGraph4j - Overview](https://langgraph4j.github.io/langgraph4j/)
- [Embabel Agent Framework](https://github.com/embabel/embabel-agent)

Microsoft's workflow guidance demonstrates the value of typed workflows, human input,
checkpointing, and explicit control, but it also illustrates why AI Fabric should not attempt to
build a universal workflow product inside the core:

- [Microsoft Agent Framework - Workflows](https://learn.microsoft.com/en-us/agent-framework/workflows/)

The defensible AI Fabric position is:

> AI Fabric is the governed Java application runtime around specialist intelligence: live
> application evidence, current authority, scoped actions, typed outcomes, and application-owned
> business truth.

## 12. Final Recommendation

Approve the proposal as the north-star architecture, with the corrections in this verdict.

Authorize implementation only through the following initial scope:

1. effective capability resolution;
2. typed trusted execution context;
3. canonical read-side evidence references;
4. minimal specialist definition and registry;
5. structured internal orchestration input;
6. synchronous and asynchronous gateway methods with scoped continuation;
7. typed result and visible failure model;
8. Agentic AI Action Resolver interactive and application-called proof, deployed independently
   from the current Account Resolver baseline;
9. legacy Mode-only compatibility;
10. no durable store, multi-specialist plan, manager, delegation, or parallelism in the first
    implementation slice.

Then use real evidence to unlock each later phase.

The most important product rule is:

> AI Fabric should not become valuable because it can run many agents. It should become valuable
> because a Java application can safely apply bounded AI intelligence to live business context and
> continue from a result it can inspect, govern, and trust.
