# AI Fabric Agentic Product Verdict And Delivery Strategy

- **Status:** Architecture and product verdict
- **Date:** 2026-07-28
- **Reviewed framework baseline:** AI Fabric `0.4.0`, repository head `e299616`
- **Reviewed proposal:** [Specialist-Defined Agentic Enablement Proposal](./Full-Proposal/Product-evolution-proposal.md)
- **Reviewed cases:** [Flow analysis documents](./ai-fabric-flow-analysis-documents/README.md)
- **Implementation plan:** [P0/P1 Agentic Enablement](./implementation-plans/0001-agentic-enablement-p0-p1-implementation-plan.md)
- **Scope:** Product value, architecture fit, implementation order, release gates, and corrections
- **Out of scope:** Implementing the proposed contracts in this document

## 1. Executive Verdict

The direction is valid and strategically valuable.

The proposal can move AI Fabric from a framework entered mainly through conversational
orchestration into an application-owned AI enablement runtime that can support:

- interactive specialists;
- typed intelligence called from Java services;
- evidence-linked decision workflows;
- proactive intelligence triggered by application events;
- durable human-reviewed work;
- bounded multi-specialist coordination where decomposition proves useful.

The strongest part of the proposal is not "multi-agent." It is the combination of:

```text
live application data
+ specialist-scoped evidence and actions
+ current application authority
+ deterministic coordination
+ governed business operations
+ typed outcomes and provenance
```

That combination extends AI Fabric's current differentiators instead of competing with generic
agent libraries on agent graphs, group chat, or autonomous tool use.

### Primary Decision

Do not choose either extreme:

1. Do not implement each product case independently with unrelated application code.
2. Do not build the complete P0-P5 supporting layer before proving one product.

Use this strategy instead:

> Design the complete architecture, implement a thin common execution spine, and expand it through
> production-quality vertical product proofs.

The implementation order should be:

```text
P0 governance and capability enforcement
  -> P1 one specialist plus application-called intelligence
  -> P2 typed wait/resume and one fixed sequential plan
  -> P3 proactive execution plus durable review
  -> evidence-gated delegation or conversation management
  -> parallel execution last
```

The proposal's broad phase order is therefore substantially correct. The first implementation
boundary needs to be smaller, and several public contract shapes need correction before coding.

## 2. What Is A Product And What Is A Supporting Capability

The pack contains a mixture of product opportunities, runtime capabilities, hardening work, and
architecture patterns. They should not all be presented or funded as separate products.

### Product Opportunities

| Product family | User-visible value | Recommended status |
| --- | --- | --- |
| Interactive specialist applications | A user can ask, understand, and safely act over live application state | Continue and formalize |
| Smart Java application capabilities | A service calls typed AI intelligence without inventing chat artifacts | Build first |
| Governed decision workflows | Focused analysis steps produce a typed, evidence-linked decision package | Pilot after P1 |
| Proactive intelligence | Events, schedules, files, or batches produce signals, recommendations, or review work | Build after durable execution exists |
| Human-reviewed automation | Sensitive proposals can wait for an authorized decision and resume safely | Build with the first proactive product |
| Managed specialist operations in LoomAI | Register, version, evaluate, observe, and govern framework definitions | Platform opportunity after runtime contracts stabilize |

### Supporting Capabilities

| Capability | Role |
| --- | --- |
| `SpecialistDefinition` | Declares one bounded AI capability |
| Effective capability resolution | Prevents a specialist declaration from granting authority |
| `AIExecutionGateway` | Supplies one application-owned submission and continuation boundary |
| Typed result and evidence contracts | Make output usable by Java workflows without trusting prose |
| Governed action lifecycle | Separates model proposal, framework governance, and application truth |
| `NeedsUserInput` | Pauses safely for a missing fact |
| Fixed execution plan | Composes focused specialists in known order |
| Durable review | Pauses across actor, time, request, or process boundaries |
| Delegation, handoff, manager, parallelism | Later coordination patterns, not initial products |

### Current Differentiators To Preserve

The live-data intelligence loop and current governed actions are not speculative agent features.
They are existing AI Fabric foundations that the new execution layer must consume:

- annotation-driven approved projections;
- transaction-aware synchronization;
- class-free `AIIndexDocument` indexing work;
- bounded retrieval and vector-space policy;
- registered actions and application-owned handlers;
- immediate confirmation;
- backend-owned conversation continuity;
- current Mode behavior and bounded read-action planning.

## 3. Code-Backed Baseline

The proposal is right that AI Fabric already has bounded agentic behavior:

- [`RAGOrchestrator`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/RAGOrchestrator.java)
  delegates to one existing pipeline.
- [`DefaultOrchestrationPipeline`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/DefaultOrchestrationPipeline.java)
  executes ordered policy, intent, retrieval, action, sanitization, and recording steps.
- [`ReadActionResolutionService`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/information/ReadActionResolutionService.java)
  already implements a bounded plan, approved READ action, observation, and continuation loop.
- [`OrchestrationProperties.ModeOverrides`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/config/OrchestrationProperties.java)
  supplies existing reusable Mode controls.
- [`AIActionRegistry`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/AIActionRegistry.java)
  discovers annotation and contributor-backed action handlers.
- [`PendingAction`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/PendingAction.java)
  and [`PendingActionStore`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/PendingActionStore.java)
  support immediate confirmation.
- [`AIEntityIndexingGateway`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/indexing/api/AIEntityIndexingGateway.java)
  and [`AIIndexDocument`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/indexing/model/AIIndexDocument.java)
  provide the current write-side evidence lifecycle.

The proposed `SpecialistDefinition`, `AIExecutionGateway`, `AIExecutionCoordinator`,
`ExecutionPlanDefinition`, durable review contracts, and typed action receipts do not currently
exist in Java code. The pack labels them as proposed correctly.

## 4. Verdict Method

Each new case should pass seven gates before it becomes a framework backlog item.

### Gate 1 - Real Product Outcome

The idea must solve a named application problem and produce an observable outcome. "Support
multi-agent" is not enough. "Reduce account-resolution time while preserving evidence and review"
is a product outcome.

### Gate 2 - Intelligence Is Actually Needed

Identify which decision benefits from model interpretation and which parts should remain
deterministic Java. Reject an agent where rules, SQL, or a normal state machine solve the problem
more reliably.

### Gate 3 - AI Fabric Fit

The case should reuse at least two meaningful AI Fabric differentiators such as live data, RAG,
governed actions, privacy, tenant scope, conversation memory, provider orchestration, or behavior
signals.

### Gate 4 - Reusable Foundation

The required primitive should benefit more than one approved product case without becoming a
generic workflow platform.

### Gate 5 - Authority And Failure Model

The proposal must identify:

- trusted initiator and optional subject;
- tenant and resource authority;
- evidence boundary;
- visible actions and pre-execution validation;
- wait, retry, cancellation, and unknown-outcome behavior;
- source of final business truth.

### Gate 6 - Proofability

The capability must be provable in one real app with:

- a deterministic baseline;
- focused unit and integration tests;
- packaged-runtime verification;
- a real-provider smoke where model behavior matters;
- explicit failure scenarios;
- measurable quality, latency, and cost observations.

### Gate 7 - Complexity Budget

Estimate new contracts, stores, adapters, public API surface, migration impact, and long-term
ownership. Prefer the smallest mechanism that proves the product.

### Decision Labels

| Verdict | Meaning |
| --- | --- |
| `BUILD NOW` | Required foundation or high-value vertical proof |
| `BUILD AFTER FOUNDATION` | Valid product, but depends on earlier verified contracts |
| `EXPERIMENT` | Implement only in a reference app until measurable value is proven |
| `DEFER` | Architecturally valid but premature |
| `REJECT` | Duplicates existing behavior, weakens governance, or lacks a real product outcome |

## 5. Case-By-Case Verdict

| Case | Verdict | Reason |
| --- | --- | --- |
| 01 Enablement Landscape | `ACCEPT AS NORTH STAR` | Strong portfolio map, but it must not become one epic or universal payload. |
| 02 Interactive Intelligent Application | `BUILD NOW` | Formalizes the strongest current path with specialist identity, typed output, and scoped capabilities. |
| 03 Application-Called Intelligence | `BUILD NOW` | Highest-value new entry point. It converts AI Fabric from chat-shaped integration into a reusable Java capability. |
| 04 Fixed Multi-Specialist Plan | `EXPERIMENT AFTER P1` | Valuable only when a measured single-specialist baseline shows quality, isolation, audit, or ownership benefits. |
| 05 Parallel Specialist Analysis | `DEFER TO P5` | Latency benefit is plausible, but cancellation, budget multiplication, branch isolation, and write conflicts make it a poor first target. |
| 06 Proactive Intelligence | `BUILD AFTER FOUNDATION` | Strategically important for true application enablement. It requires idempotent durable execution and trusted machine identity first. |
| 07 Missing Input And Safe Resume | `BUILD IN P2` | A necessary continuation primitive for interactive and non-chat work. Keep it distinct from confirmation and review. |
| 08 Durable Human Review | `BUILD WITH FIRST P3 PRODUCT` | High enterprise value, but should be driven by one real proactive or sensitive workflow rather than a generic inbox product. |
| 09 Conversation Manager | `DEFER TO P4` | Most routes should be explicit, deterministic, or handled by one specialist. Add another model call only when real conversations prove ambiguity. |
| 10 Delegation And Handoff | `EXPERIMENT AFTER FIXED PLANS` | One-level typed delegation may be useful. General recursive delegation and dynamic handoff are premature. |
| 11 Governed Action Lifecycle | `BUILD/HARDEN FIRST` | This is a security and correctness prerequisite. Current action execution paths must share one enforced capability and invocation boundary. |
| 12 Live Data Intelligence Loop | `PRESERVE AND INTEGRATE` | Already a framework differentiator. Add provenance and revision linkage; do not rebuild synchronization. |

### Strongest Immediate Product Pair

The first release should prove two uses over one common spine:

1. **Interactive Agentic AI Action Resolver specialist**
   - real conversation;
   - current `resolver` Mode;
   - current bounded iterative READ action;
   - scoped evidence;
   - typed `AccountResolutionResult`;
   - no mandatory new persistence.

2. **Application-called Account Resolution**
   - typed Java request;
   - real service/system initiator;
   - optional real subject;
   - no conversation or fake session;
   - same specialist and inner orchestration;
   - typed result.

This pair proves that the abstraction is channel-neutral without requiring multi-specialist,
durability, review, or parallelism.

### Reference-App Isolation Decision

Keep the current `ai-fabric-account-resolver` real app and deployed demo unchanged as the AI Fabric
`0.4.0` behavioral baseline. Copy it into a separately owned real app:

```text
examples/real-apps/agentic-ai-action-resolver
```

All proposed specialist, gateway, typed-result, receipt, plan, and durable-review integration belongs
in the new app. It must receive its own artifact name, configuration, Dockerfile, health/build
metadata, tests, seed/session state, backend deployment, and website demo route.

The specialist inside the new app may remain `account-resolver@1`: the specialist ID represents the
domain capability, whereas `agentic-ai-action-resolver` is the reference-app and deployment name.
Evaluate both applications with the same scenario corpus. The current app proves compatibility;
the new app proves the new architecture.

## 6. Required Corrections Before Implementation

### 6.1 Reuse The Engine, Not Only The `RAGOrchestrator` Facade

The proposal says typed application calls must not create synthetic user messages, but the current
public engine boundary is:

```java
OrchestrationResult orchestrate(String query, OrchestrationContext context)
```

The underlying [`Pipeline`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/Pipeline.java)
also accepts only a query string and `OrchestrationContext`.

Do not create a second engine. Introduce a structured internal orchestration request that the
existing pipeline can consume:

```text
Legacy text request -> RAGOrchestrator compatibility facade --+
                                                             |
Typed specialist request -> AIExecutionGateway --------------+
                                                             v
                                           one orchestration engine
```

The structured request may contain a typed input reference plus a registered, deterministic prompt
renderer. Rendering typed input for an LLM is valid. Pretending that the input came from a human
conversation is not.

Keep `RAGOrchestrator.orchestrate(String, OrchestrationContext)` unchanged for legacy callers, but
do not require all future sources to enter through that text-shaped public method.

### 6.2 Make Continuation Operations Authority-Scoped

The proposed gateway shape uses only `executionId` for `status`, `result`, and `cancel`. That is not
a sufficient authorization boundary.

Every continuation operation must receive trusted current context:

```java
ExecutionHandle<O> resume(
    ExecutionHandle<O> handle,
    ResumeInput input,
    TrustedExecutionContext context
);

ExecutionStatus status(
    ExecutionHandle<?> handle,
    TrustedExecutionContext context
);

void cancel(
    ExecutionHandle<?> handle,
    CancellationReason reason,
    TrustedExecutionContext context
);
```

Network adapters must derive context from authenticated server state. A client must not be able to
claim a trusted-context reference or obtain another tenant's result by knowing an execution ID.

### 6.3 Separate Synchronous And Asynchronous Ergonomics

An application-called synchronous capability should not require `submit`, immediate `result`
polling, and an unchecked cast.

Provide both:

```java
<I, O> AIExecutionResult<O> execute(
    TypedSpecialistRef<I, O> specialist,
    I input,
    TrustedExecutionContext context
);

<I, O> ExecutionHandle<O> submit(
    TypedSpecialistRef<I, O> specialist,
    I input,
    TrustedExecutionContext context
);
```

Both operations must delegate to the same gateway/coordinator path.

### 6.4 Replace Conflicting Optional String Targets

Three optional strings for specialist, plan, and selection key permit invalid combinations and
move validation too late. Prefer a sealed target:

```java
sealed interface ExecutionTarget
    permits SpecialistTarget, PlanTarget, SelectionKeyTarget, RegisteredEntryPointTarget {}
```

Use typed/versioned references rather than parsing `"account-resolver@1"` throughout the code.

### 6.5 Treat `SpecialistDefinition` As An Aggregate, Not A God Object

The conceptual ownership is right: one specialist definition should be the complete readable view
of one specialist.

The implementation should remain a composed aggregate:

- identity and version;
- typed contract;
- instructions;
- requested evidence and actions;
- execution behavior;
- enforced limits;
- optional policy subcontracts that are actually supported.

Do not publish ignored configuration fields. In particular, do not ship placeholder
`HumanReviewStep`, `ParallelStep`, delegation, or supervisor types before their execution,
validation, and tests exist. Add versioned subcontracts when the matching behavior is implemented.

### 6.6 Centralize Action Availability And Invocation

Current code reaches `AIActionRegistry` and handlers from several paths, including:

- prompt/action catalogue construction;
- intent extraction validation;
- direct action handling;
- context READ actions;
- planner READ actions;
- Spring AI tool callbacks.

The proposed `EffectiveCapabilitiesResolver` is therefore necessary, not decorative.

The same immutable effective action view must be used:

1. before model exposure;
2. during intent and planner validation;
3. during parameter/context resolution;
4. immediately before handler invocation.

`GovernedActionExecutionService` should wrap the existing registry and become the one invocation
boundary. It must not become a second registry or business service.

### 6.7 Introduce Action Receipts Without Faking Legacy Certainty

Current `@ActionExecute` handlers return `ActionResult` or an action payload. A new
`ActionReceipt` is stronger because it explicitly states committed, rejected,
failed-before-commit, or unknown.

Do not automatically translate every successful legacy `ActionResult` into `COMMITTED`. That would
claim stronger business truth than the handler supplied.

Recommended migration:

1. preserve legacy action behavior on the legacy Mode-only path;
2. allow new handlers to return a typed receipt;
3. require receipts for specialist-coordinated WRITE actions that influence later plan
   transitions;
4. provide explicit migration guidance and tests;
5. keep READ action observations on the existing safe result contract.

### 6.8 Define One Canonical Read-Side Evidence Reference

`EvidenceReference` is conceptual; it is not a current Java contract.

Define it from sanitized read-side evidence, aligned with
[ADR 0017](../0017-0.4-post-release-retrieval-connector-hardening-plan.md):

- document/evidence ID;
- vector space;
- bounded relevance score;
- safe source and validated URL;
- safe metadata;
- optional source revision and projection version;
- provenance and correlation references.

Do not use `AIIndexDocument` as a specialist result or retrieval response. `AIIndexDocument` remains
the durable write-side indexing payload. Full document content should remain request-scoped unless
an explicit retention policy permits otherwise.

### 6.9 Make Trusted Execution Context First-Class

Current [`OrchestrationContext`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/OrchestrationContext.java)
requires a user or session and carries tenant/authority values through metadata conventions.

That is usable for current interactive requests when the host builds it correctly, but it is not a
sufficient typed boundary for application calls, events, schedules, files, or batches.

Add a typed trusted context with:

- initiator type and ID;
- optional real subject;
- tenant and customer/deployment scope;
- granted authority reference;
- correlation and deadline;
- source type;
- optional real conversation reference only for interactive work.

Adapt that context into legacy orchestration internals. Do not manufacture a human user or
anonymous session for machine work.

### 6.10 Keep Durable Execution Outside The First Slice

No execution database is needed for a synchronous single specialist.

When P3 needs durability, provide a small execution-state SPI and:

- an in-memory adapter for tests and bounded local work;
- a JDBC adapter for the first durable reference product;
- an adapter boundary for an existing workflow engine when adopters already operate one.

Do not turn AI Fabric into a general scheduler, event broker, or distributed workflow engine.

### 6.11 Enforce Measurable Limits Honestly

Time, step, action, and model-call limits can be enforced directly. Token and cost limits depend on
provider usage support.

Registration/runtime behavior must state whether a limit is:

- hard-enforced;
- estimated;
- observed after the call;
- unsupported by the selected provider.

Do not present an after-the-fact usage observation as a guaranteed pre-call cost ceiling.

## 7. Recommended Runtime Boundary

The smallest useful common spine is:

```text
Trusted source adapter
        |
        v
AIExecutionGateway
        |
        +-- trusted source and target validation
        +-- SpecialistRegistry
        +-- EffectiveCapabilitiesResolver
        +-- typed input validation
        v
Single-invocation coordinator
        |
        v
Existing orchestration engine and providers
        |
        +-- scoped retrieval/evidence
        +-- bounded READ planning
        +-- governed action proposal/invocation
        v
Typed AIExecutionResult
```

This is enough to support interactive and application-called intelligence. It does not require:

- execution graphs;
- durable tables;
- a conversation manager;
- delegation;
- parallelism;
- a review inbox;
- a generic agent builder.

## 8. Recommended Module Boundary

Avoid placing the whole proposal into `ai-fabric-core`.

### Core Changes

Keep only contracts required by existing pipeline enforcement in core:

- effective capability/action/evidence view;
- invocation policy context;
- canonical read-side evidence reference;
- trusted execution principal/context primitives required at policy boundaries;
- compatibility adapters for legacy Mode-only requests.

### New Optional Execution Module

An `ai-fabric-execution` module can own:

- specialist definition and registry;
- typed specialist references and type contracts;
- execution gateway;
- single-invocation coordinator;
- execution handles and typed results;
- registered input/output renderers and validators;
- specialist selection mappings;
- later fixed-plan contracts.

### Later Optional Adapters

Add only when the corresponding P3 product is implemented:

- `ai-fabric-execution-jdbc`;
- durable review store/dispatcher adapters;
- trigger adapters;
- workflow-system adapters.

The normal starter may include the module only after its compatibility and packaging gates pass.

## 9. Vertical Delivery Plan

### Phase 0 - Governance And Compatibility

Goal: make the current runtime safe to host specialist scoping without changing legacy behavior.

Implement:

- golden regression tests for current Mode-only real apps;
- one immutable action/evidence capability view;
- enforcement across prompt, extraction, planner, direct action, tool callback, and handler paths;
- one governed action invocation boundary over the existing registry;
- typed trusted initiator/subject vocabulary;
- canonical read-side evidence reference;
- explicit finish/failure reason taxonomy;
- package-level architecture tests preventing bypasses.

Do not add multi-specialist execution in this phase.

### Phase 1 - One Specialist, Two Entry Points

Goal: prove one definition can support a real conversation and a typed Java call.

Implement:

- minimal enforced `SpecialistDefinition<I,O>`;
- specialist registry and startup validation;
- `DIRECT`, `SINGLE_PASS`, and `BOUNDED_ITERATIVE` mapping to existing behavior;
- structured internal execution request;
- `execute` and `submit` gateway operations;
- one ephemeral invocation;
- trusted deterministic target selection;
- typed result validation;
- interactive root dialogue ownership;
- no conversation for application calls.

Reference proof:

- Agentic AI Action Resolver chat;
- Agentic AI Action Resolver Java/service call;
- same specialist, Mode, read loop, evidence policy, and providers.

Release gate:

- no legacy regression;
- no fake user/session for the application call;
- typed output is validated;
- unauthorized evidence/action is rejected at exposure and invocation;
- failures remain visible;
- packaged real app and OpenAI smoke pass.

### Phase 1.1 - One Governed Specialist WRITE

Goal: prove the complete proposal-to-business-truth boundary.

Implement:

- specialist-scoped WRITE proposal;
- current confirmation;
- version/profile pinning in pending state;
- final reauthorization;
- typed application receipt;
- explicit unknown outcome;
- safe result projection;
- reuse of current live sync after committed domain change.

Do not begin a multi-step plan until this boundary is trustworthy.

### Phase 2 - Typed Continuation And Fixed Plan

Goal: prove composition without claiming general multi-agent support.

Implement in this order:

1. `NeedsUserInput` on one specialist and safe resume;
2. explicit one-step plan equivalent to the Phase 1 specialist;
3. one acyclic two-step sequential plan;
4. registered typed mapper;
5. deterministic aggregation;
6. per-step capability resolution and budget;
7. cancellation and late-result rejection.

Only decompose Agentic AI Action Resolver into Identity, Payment, and Policy specialists if
evaluation shows a measurable improvement over its single-specialist path and the current Account
Resolver baseline.

### Phase 3 - Proactive Product And Durable Review

Goal: prove intelligence that starts and completes outside a chat request.

Recommended proof:

```text
account/payment event
  -> deterministic mapping
  -> durable account-resolution execution
  -> evidence-linked recommendation
  -> review task when policy requires
  -> authorized decision
  -> governed handler
  -> application receipt
```

Implement:

- durable execution SPI and JDBC adapter;
- stable idempotency key;
- worker lease/retry/recovery semantics;
- typed outcome sink;
- durable review task/store/dispatcher/decision gateway;
- current authority and evidence freshness on resume;
- duplicate-safe decisions and receipts;
- unknown-outcome reconciliation.

### Phase 4 - Evidence-Gated Dynamic Coordination

Choose one only when a real product proves it:

- one-level delegation;
- explicit handoff;
- conversation-manager specialist.

Do not build all three merely because the common model can express them.

### Phase 5 - Parallel Fan-Out/Fan-In

Build only when:

- sequential isolation and cancellation are proven;
- branches are genuinely independent;
- latency is a measured product constraint;
- deterministic aggregation is available;
- writes remain proposals until fan-in;
- branch budget multiplication is bounded.

## 10. Reference-App Strategy

Use existing real apps as behavioral baselines and source implementations. Put the new agentic
contracts in the separately deployable `agentic-ai-action-resolver` proof:

| Proof | Best reference app |
| --- | --- |
| Legacy behavioral comparison | Current Account Resolver, unchanged |
| Specialist-defined interactive flow | Agentic AI Action Resolver |
| Typed application call | Agentic AI Action Resolver service/API |
| Effective tenant/evidence scope | Tenant Guard |
| Proactive event execution | Behavior Signals or Agentic AI Action Resolver domain event |
| Action receipt and live revision | Agentic AI Action Resolver plus Live Data Sync |
| Evidence provenance | Live Data Sync |
| Durable review | Agentic AI Action Resolver operations queue |
| Privacy-projected context | Privacy Shield |

Each phase should have one real product story, not only framework unit tests.

## 11. Evaluation Scorecard For Every Vertical Proof

Record the following before and after the change:

### Product

- task completion rate;
- time to useful outcome;
- clarification/review rate;
- downstream acceptance of the typed recommendation;
- user or operator value.

### Intelligence

- output-schema validity;
- evidence sufficiency;
- unsupported-claim rate;
- correct specialist/target selection;
- comparison with deterministic and single-specialist baselines.

### Governance

- unauthorized evidence denied;
- unauthorized action absent from model exposure;
- final action reauthorization;
- stale resume/confirmation denial;
- no privilege union;
- no cross-tenant result/status access.

### Operations

- latency;
- model calls;
- action calls;
- tokens and observable cost;
- retries and duplicate handling;
- cancellation and timeout behavior;
- provider/retrieval/action failures visible.

### Release Proof

- unit tests;
- module integration tests;
- packaged-runtime smoke;
- real-provider smoke where model behavior matters;
- live demo scenario;
- migration and troubleshooting documentation.

## 12. What AI Fabric Should Not Build

Reject or defer:

- a second LLM/provider orchestration engine;
- a generic low-code agent builder;
- an unrestricted graph generated by an LLM;
- shared transcripts or mutable shared scope between specialists;
- a complete workflow engine, scheduler, or event broker;
- a broad supervisor before deterministic selection is exhausted;
- parallel writes by sibling specialists;
- mandatory persistence for synchronous work;
- fake conversations for service/event/file/batch triggers;
- public configuration fields whose behavior is not implemented;
- automatic conversion of successful model/action prose into a committed business receipt.

## 13. LoomAI Platform Opportunity

Once the embedded runtime contracts are stable, LoomAI can add value above them without moving
application authority out of the application.

Potential platform capabilities:

- specialist and prompt-profile catalog;
- version promotion and environment policy;
- plan registry and validation;
- evaluation datasets and regression scorecards;
- safe execution traces and cost observations;
- review operations and adapter configuration;
- deployment compatibility and policy checks;
- fleet-level provider and capability visibility.

The platform should manage definitions, evidence, evaluation, and operations. It should not become
the source of application identity, domain authorization, transactions, or final business truth.

## 14. Documentation Corrections

Before treating the pack as an implementation source:

1. Keep case-document proposal links on the checked-in
   `../Full-Proposal/Product-evolution-proposal.md` path relative to the pack layout.
2. Mark `EvidenceReference` explicitly as proposed until a canonical read-side contract exists.
3. Clarify that current `OrchestrationContext` carries tenant and authority by trusted metadata
   convention, not by a complete typed execution-principal contract.
4. Change wording that says every new specialist step invokes the public `RAGOrchestrator`; say it
   invokes the same underlying orchestration engine.
5. Add trusted current context to every gateway status/result/cancel/resume operation.
6. Remove executable public placeholder types until their behavior is implemented.
7. Document the `ActionResult` to `ActionReceipt` migration instead of implying compatibility is
   automatic.
8. Update the reviewed commit marker when the pack is committed.

## 15. Final Recommendation

Approve the product direction and use the proposal as the architectural north star.

Start implementation only with the common spine required for:

1. specialist-scoped governance;
2. one interactive Agentic AI Action Resolver specialist in a separate real app;
3. the same specialist called as a typed Java capability;
4. one governed action with an authoritative application receipt.

Then let product proof pull the next layer into existence:

- missing input and fixed plans;
- proactive execution and durable review;
- selected delegation or conversation management;
- parallelism last.

This approach makes AI Fabric a stronger AI enablement layer without turning it into an unfinished
general agent platform. It preserves what is already distinctive in `0.4.0`, creates immediate new
application products, and leaves a clean path for LoomAI to become the managed control and
operations layer later.

## 16. Ecosystem Dependency Decision

Use Spring AI as the approved commodity AI infrastructure provider whenever a stable Spring AI
contract usefully removes model, embedding, structured-output, tool-call, MCP, advisor,
observability, document-ETL, or evaluation plumbing. Keep AI Fabric's specialist, evidence,
authority, confirmation, review, receipt, and execution contracts above that infrastructure.

Spring AI reuse is incremental and must not block the delivery plan. The implementation should use
what is relevant now without waiting for Spring AI to become a complete agent runtime.

Treat LangChain4j, LangGraph, LangGraph4j, Embabel, Microsoft Agent Framework, and general durable
workflow engines as a future technology watch list. They must not:

- become core dependencies during P0/P1;
- shape public contracts speculatively;
- introduce a second model/provider stack;
- delay the Agentic AI Action Resolver proof;
- bypass effective-capability, tenant, action, or receipt enforcement.

Evaluate one only after the native contracts and baseline are stable, and only behind a real SPI
where measured evidence shows that an adapter reduces implementation or operational risk. The
current implementation proceeds independently.

## 17. External Architecture References

- [Spring AI - Building Effective Agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)
- [Spring AI - Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [LangChain4j - Agents and Agentic AI](https://docs.langchain4j.dev/tutorials/agents/)
- [LangGraph - Overview](https://docs.langchain.com/oss/python/langgraph/overview)
- [LangGraph4j - Overview](https://langgraph4j.github.io/langgraph4j/)
- [Embabel Agent Framework](https://github.com/embabel/embabel-agent)
- [Microsoft Agent Framework - Workflows](https://learn.microsoft.com/en-us/agent-framework/workflows/)

These references reinforce the same implementation principle: use deterministic workflows for
known processes, put model judgment only where it adds value, keep tools/business operations in
application code, and add dynamic or parallel coordination only when the simpler pattern is
insufficient.
