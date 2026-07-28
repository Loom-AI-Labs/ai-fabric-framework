# AI Fabric Specialist-Defined Agentic Enablement Proposal

**Status:** Revised standalone architecture proposal for review
**Baseline:** AI Fabric Framework `0.4.0`
**Target horizon:** Incremental `0.5+` evolution
**Scope:** Framework architecture, contracts, execution coordination, and reference proof
**Excluded scope:** Product UI, screen design, Experience Kit, Control Center, landing pages, and
commercial packaging

**Executable delivery plan:** [P0/P1 Agentic Enablement](../implementation-plans/0001-agentic-enablement-p0-p1-implementation-plan.md)

## Decision Summary

AI Fabric does not need a second agent engine, and it does not need to turn `Mode` into an agent
definition.

The current framework already contains most of a bounded single-agent execution flow:

- `Mode` is an existing reusable orchestration preset. It currently controls capabilities such as
  action enablement, retrieval behavior, vector-space restrictions, and bounded read-action
  planning;
- `RAGOrchestrator` and the orchestration pipeline execute the governed request flow;
- `ReadActionResolutionService` performs a bounded plan → action → observation loop;
- retrieval and vector policies constrain evidence;
- `AIActionRegistry`, action handlers, confirmation, and application services constrain operations;
- `OrchestrationContext` carries identity, tenant, and request authority;
- chat-session, action-draft, and pending-action stores support conversational continuity.

The recommended evolution is therefore smaller and more precise than introducing a parallel agent
implementation:

1. preserve existing Mode behavior and Mode-only integrations;
2. make the versioned `SpecialistDefinition` the canonical, single declarative view of an agent;
3. let the specialist declare its objective, instructions, typed input/output, evidence scope,
   visible and requestable READ actions, proposable WRITE actions, execution behavior, human-control
   profile, and specialist limits;
4. intersect those declarations with existing Mode restrictions, registered capabilities, and
   current application authority—never treat the declaration as authorization;
5. add to Mode only when a concern is genuinely shared by several specialists, fits the existing
   Mode concept, and cannot live more naturally in application or framework policy;
6. identify each running specialist through a `SpecialistInvocation`;
7. select specialists only from an application-approved candidate set;
8. execute every specialist through the existing orchestration flow;
9. use `AIExecutionGateway` as the canonical framework submission/resume boundary for user
   interactions, application calls, events, schedules, files, batches, and missing input, while
   retaining the dedicated `ReviewDecisionGateway` for governed reviewer decisions;
10. treat a conversation as an optional external interaction record—not as a specialist-to-
    specialist message bus;
11. assign exactly one dialogue-owning invocation to each interactive execution turn, while worker
    specialists run as separate invocations over typed inputs and individually projected context;
12. let any specialist return a typed `NeedsUserInput` outcome, then route that request through the
    dialogue owner and resume only the waiting branch;
13. add a small, versioned `ExecutionPlanDefinition` for fixed multi-specialist coordination;
14. keep the coordinator deterministic and use an optional governed supervisor specialist only at
    ambiguous decision points;
15. make governed action invocation and outcome finalization explicit without moving business
    operations out of application-owned handlers;
16. deliver durable human-review work through registered SPI adapters and accept decisions through
    a framework-owned gateway;
17. add durable state only when work crosses a request, process, actor, or time boundary;
18. preserve the existing Account Resolver as the behavioral baseline and prove the design in a
    separately deployable copy named `agentic-ai-action-resolver`.

The key correction is:

> `SpecialistDefinition` is the complete definition of an agent. It tells us why the specialist
> exists, what data and evidence it may use, which READ actions it may request, which WRITE actions
> it may propose, how it should reason, when a person must review its work, and what typed result it
> must return. Mode continues to work as it does today and contributes shared orchestration behavior
> or existing restrictions. Application authority and registered framework policy can always narrow
> the specialist; the specialist can never grant itself authority.

The interaction correction is equally important:

> One external conversation has one dialogue owner for a given execution turn. Other specialists
> do not join that conversation as peers. They receive separate, typed, policy-filtered
> invocations, return typed results, and never read or write the complete conversation. A
> non-interactive trigger has no dialogue owner and no conversation unless real human dialogue is
> later opened deliberately.

This preserves AI Fabric's central principle:

> The model may interpret, retrieve, plan, and propose. AI Fabric owns the governed proposal,
> confirmation/review, registered-handler invocation, receipt validation/recording, outcome
> finalization, and coordination lifecycle. The application owns trusted identity, domain
> authorization, business validation, transactions, side effects, idempotency, the authoritative
> receipt, and final business truth.

### Status Vocabulary

| Label | Meaning |
| --- | --- |
| `CURRENT` | Present in the reviewed `0.4.0` code or packaged modules |
| `CURRENT GAP` | Existing behavior that needs stronger or more consistent enforcement |
| `PROPOSED` | New contract or component requiring implementation evidence |
| `P0` | Mode compatibility, specialist ownership, effective-capability resolution, and enforcement |
| `P1` | First releasable single-specialist extension and canonical execution ingress |
| `P2` | Fixed execution plans, dialogue ownership, projected context, and deterministic coordination |
| `P3` | Channel-neutral durable execution and human review |
| `P4` | Optional supervisor-directed conversational coordination |
| `P5` | Bounded parallel expansion after isolation and cancellation are proven |
| `LATER` | Valid expansion deferred until adopter evidence justifies it |

## 1. Purpose And Product Direction

AI Fabric should evolve from a framework that is commonly entered through a conversational request
into an application-owned AI enablement layer that can run bounded intelligence from:

- a chat turn;
- an application service or API;
- a domain or application event;
- a scheduled condition;
- a file or batch;
- a resumed human-review task.

Chat remains a useful interaction channel. It is not the architectural boundary of the framework,
and a specialist invocation must not require a fake conversation or an invented human user.

The product direction is:

> Live application context → specialist-defined execution under application governance →
> typed, evidence-linked outcome
> → human review or governed application action → authoritative receipt and synchronized result.

This direction supports answers, recommendations, classifications, risk signals, governed action
proposals, and background application workflows without turning the model into the system of
record.

## 2. Existing Foundation And Code Evidence

### 2.1 Capabilities Already Present

AI Fabric `0.4.0` already provides a credible foundation beyond a model wrapper:

- annotation-driven application-data projection;
- transaction-aware live synchronization;
- synchronous, asynchronous, and batch indexing strategies;
- evidence-grounded retrieval and RAG;
- vector-space and retrieval policy;
- governed read and write actions;
- explicit confirmation before sensitive application operations;
- application-supplied tenant, authorization, and access-policy context;
- privacy and PII controls;
- conversation and memory support;
- LLM-provider integration through Spring AI where its contract fits, while AI Fabric retains its
  richer RAG and vector-provider contracts where migration would lose required behavior;
- behavior signals, relationship queries, document ingestion, migration, and backfill.

The proposal must reuse those contracts. It must not create replacement registries, retrieval
implementations, model clients, or action lifecycles.

### 2.2 The Existing Bounded Agentic Loop

The strongest evidence is the current read-action resolution flow:

```text
Understand the request
        ↓
Plan from the current Mode-approved READ actions
        ↓
Validate the requested action and arguments
        ↓
Execute through application-owned handlers
        ↓
Observe the returned evidence
        ↓
Continue, retrieve, clarify, or answer
        ↓
Stop at configured iteration and action limits
```

This is bounded agentic behavior in the `CURRENT` implementation. It lets the model choose the next
approved information-gathering step while Java code enforces the available actions, arguments,
iteration limits, evidence handling, and completion path. In the proposed specialist path, the
specialist's planner-READ scope becomes an additional, agent-specific narrowing layer; it does not
replace or weaken the current Mode restriction.

Relevant implementation evidence:

- [`OrchestrationProperties`](https://github.com/Loom-AI-Labs/ai-fabric-framework/blob/main/ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/config/OrchestrationProperties.java)
  defines mode-level orchestration controls.
- [`OrchestrationPolicyResolutionStep`](https://github.com/Loom-AI-Labs/ai-fabric-framework/blob/main/ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/steps/OrchestrationPolicyResolutionStep.java)
  resolves request policy before later stages execute.
- [`ReadActionResolutionService`](https://github.com/Loom-AI-Labs/ai-fabric-framework/blob/main/ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/information/ReadActionResolutionService.java)
  implements the bounded read plan/action/observation cycle.
- [`RAGOrchestrator`](https://github.com/Loom-AI-Labs/ai-fabric-framework/blob/main/ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/RAGOrchestrator.java)
  exposes the current governed orchestration entry point.
- [`DefaultOrchestrationPipeline`](https://github.com/Loom-AI-Labs/ai-fabric-framework/blob/main/ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/DefaultOrchestrationPipeline.java)
  provides the common pipeline.
- [`ChatSessionService`](https://github.com/Loom-AI-Labs/ai-fabric-framework/blob/main/ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/service/ChatSessionService.java)
  provides conversational continuity.
- [`PendingActionStore`](https://github.com/Loom-AI-Labs/ai-fabric-framework/blob/main/ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/PendingActionStore.java)
  supports the immediate confirmation lifecycle.

### 2.3 Current Agentic Mapping

| Agentic concern | Current foundation | Proposed owner |
| --- | --- | --- |
| Reusable orchestration behavior | Mode configuration | Existing Mode, unchanged unless a proven shared concern requires an addition |
| Complete agent definition | Spread across Mode, prompts, context, and application configuration | `SpecialistDefinition` |
| Entry-point mapping | Position, Mode routing, application configuration, and request context | `AIExecutionGateway` plus trusted explicit or registered deterministic specialist/plan resolution; specialist then references a Mode |
| Goal and instructions | Intent extraction and prompt instructions | `SpecialistDefinition` |
| Evidence requested by one agent | Retrieval policy and Mode vector-space allowlists | `SpecialistDefinition.evidenceScope`, narrowed by existing Mode and application policy |
| Direct READ actions visible to one agent | General registered-action discovery when actions are enabled | `SpecialistDefinition.actionScope.directReadActions` |
| Planner READ actions | Mode read-action-resolution allowlist | Intersection of specialist planner scope and the existing Mode allowlist |
| WRITE actions one agent may propose | General registered-action discovery when actions are enabled | `SpecialistDefinition.actionScope.proposableWriteActions` |
| Iterative information gathering | `ReadActionResolutionService` | Reused by specialist execution |
| Governed operations | `AIActionRegistry`, action handlers, and confirmation | Existing registry plus effective specialist capability filtering |
| Identity and tenant scope | `OrchestrationContext` and host-application integration | Remains trusted application/framework authority |
| Conversation continuity | `ChatSessionService` | Conversation binds to `AIExecution`; one invocation owns dialogue and workers receive projected views |
| Missing action parameters | Action-draft lifecycle | Reused and bound to the specialist/policy snapshot |
| Immediate human confirmation | `PendingActionStore` | Reused; durable review is added only where required |
| Final side effect | Host-application service | Remains host-application owned |

### 2.4 Honest Current Gaps

The existing foundation should not be overstated. The reviewed design still lacks:

- a stable, selectable specialist definition with versioned instructions and a complete declared
  capability view;
- an explicit execution-strategy contract distinguishing direct, single-pass, and bounded-agentic
  behavior;
- consistent per-specialist scopes for evidence, direct READ actions, planner READ actions, and
  proposable WRITE actions;
- a typed specialist input/output contract beyond the generic orchestration result;
- specialist and execution-plan time, token, cost, and cancellation budgets;
- a resolved effective-capability snapshot attached to an invocation;
- a specialist identity distinct from a chat turn;
- one canonical execution ingress across chat, application, trigger, and resume sources;
- explicit conversation-to-execution binding, one dialogue owner, projected worker context, and
  controlled owner handoff;
- typed missing-input routing that resumes only the requesting invocation;
- parent/child specialist invocation state;
- a versioned execution-plan contract for fixed specialist order and typed step boundaries;
- a typed, governed supervisor directive for genuinely dynamic coordination;
- cross-specialist context isolation and aggregation;
- a generalized durable human-review task for work that leaves the active request;
- a pluggable review-delivery SPI and trusted decision-return contract;
- an explicit boundary between AI Fabric's governed action invocation lifecycle and the
  application's business-operation implementation;
- a typed application-issued action receipt and outcome-finalization lifecycle;
- defined handling for uncertain write outcomes, post-outcome review, revision visibility, and
  separately governed compensation;
- proven parallel execution where configuration currently exposes parallel limits.

The existing multi-agent documentation should also be corrected where it calls ordinary pipeline
stages or modules “agents.” Retrieval, security, privacy, policy, and action stages are framework
services unless they independently own a goal, bounded context, typed result, and invocation
lifecycle.

## 3. Correct Conceptual Model

### 3.1 Terms

| Concept | Meaning |
| --- | --- |
| `Action` | One application-owned operation the model may request or propose through a governed contract |
| `Position` | An application-facing entry or selection hint that should normally resolve a specialist or plan; legacy Mode routing remains supported |
| `Mode` | The existing reusable orchestration preset and shared restriction source; it is not an agent definition |
| `ExecutionStrategy` | A specialist behavior choice: direct, one planning pass, or the existing bounded iterative loop |
| `SpecialistDefinition` | The canonical, versioned definition of one agent: goal, instructions, typed contract, evidence, actions, behavior, human control, limits, and delegation |
| `ResolvedSpecialistProfile` | The immutable effective view produced by intersecting one SpecialistDefinition with existing Mode restrictions, registries, and current application authority, then applying invocation limits |
| `SpecialistInvocation` | One active execution of one resolved specialist for one goal |
| `Conversation` | Optional external user-facing interaction history; neither execution state nor an inter-specialist message bus |
| `DialogueOwner` | The one invocation permitted to conduct the external dialogue for an interactive execution turn |
| `ApprovedConversationView` | Immutable, specialist-specific projection of one frozen conversation snapshot; never the unrestricted transcript |
| `ConversationContextProjector` | Deterministic service that applies specialist, Mode, privacy, tenant, authority, and plan-input restrictions to produce an approved view |
| `NeedsUserInput` | Typed specialist outcome requesting additional external input without speaking to the user directly |
| `ExecutionPlanDefinition` | A versioned blueprint describing permitted specialist steps, dependencies, mappings, and decision points |
| `ResolvedExecutionPlan` | The authorization-filtered, version-pinned plan for one execution |
| `ExecutionRequest` | Trusted, channel-neutral submission containing the source, input, target hint, optional conversation reference, and trusted context reference |
| `AIExecutionGateway` | Narrow application-facing facade for submit, resume, cancel, status, and result operations |
| `AIExecution` | One running instance of a single specialist or resolved execution plan; it may remain ephemeral |
| `AIExecutionCoordinator` | Deterministically validates and advances execution state |
| `ConversationManagerSpecialist` | Optional governed specialist that proposes the next conversational move when it is genuinely ambiguous |
| `CoordinationDirective` | Typed manager proposal that the coordinator may accept or reject |
| `SpecialistResult` | Validated output with evidence, proposals, usage, and finish information |
| `RegisteredActionHandler` | Application-owned action implementation discovered through the existing action registry |
| `GovernedActionExecutionService` | AI Fabric service that validates, confirms/reviews, and invokes a registered action handler |
| `ActionReceipt` | Application-issued, typed statement of the authoritative action outcome |
| `ActionFinalizationRecord` | AI Fabric lifecycle record for receipt validation, missing-receipt uncertainty, revision visibility, recovery, and final transition |
| `ActionOutcomeFinalization` | AI Fabric processing that validates the receipt, records provenance, coordinates visibility, and advances the declared transition |
| `ReviewTask` | Version-bound human decision request attached to a proposal or action invocation/finalization state and its approved evidence |
| `ReviewTaskStore` | Persistence SPI for durable review-task state and optimistic transitions |
| `ReviewTaskDispatcher` | Outbound SPI that delivers a safe review-task reference through an application-selected channel |
| `ReviewerAuthorizer` | Application SPI that validates reviewer eligibility and authority for the specific decision |
| `ReviewDecisionGateway` | Framework-owned inbound contract for submitting a trusted reviewer decision |

### 3.2 Central Formula

```text
Specialist definition
  = stable specialist identity
  + reference to one Mode
  + objective, instructions, and versioned prompt profile
  + typed input/output contract
  + evidence and context scope
  + direct and planner READ-action scope
  + proposable WRITE-action scope
  + execution and conversation behavior
  + human-control policy references
  + specialist limits
  + selection and delegation metadata

Running specialist
  = SpecialistDefinition
  + existing Mode configuration and restrictions
  + registered action/evidence metadata
  + current application and tenant authority
  + execution-plan input mapping and budget allocation when plan-bound
  + current goal and trusted context
  + invocation state and remaining limits
  + existing AI Fabric orchestration

Interactive execution turn
  = one AIExecution
  + one optional external Conversation
  + exactly one dialogue-owning SpecialistInvocation
  + zero or more isolated worker SpecialistInvocations
  + one frozen conversation snapshot revision
  + one ApprovedConversationView per invocation
  + one validated external response

Non-interactive execution
  = one AIExecution
  + trusted machine or application initiator
  + no Conversation and no DialogueOwner by default

Multi-specialist execution
  = ExecutionPlanDefinition
  + authorized, version-pinned specialist steps
  + AIExecution state
  + deterministic coordinator

Governed write execution
  = specialist-declared and effectively authorized ActionProposal
  + confirmation or review policy
  + AI Fabric handler invocation
  + application-owned business operation
  + authoritative ActionReceipt
  + ActionOutcomeFinalization
```

The distinction matters:

- `SpecialistDefinition` is the single declarative point of view for understanding one agent;
- that definition requests capabilities but does not grant authorization;
- Mode remains the source of its current orchestration settings and shared restrictions;
- several specialists may reuse one Mode while declaring different evidence, action, behavior, and
  result contracts;
- existing Mode restrictions, application policy, and action metadata may narrow the specialist's
  effective capabilities but never enlarge them;
- an execution plan may map inputs and allocate a smaller budget, but it does not redefine the
  specialist's evidence or action scopes;
- execution plans describe composition and order; they do not redefine specialists or grant
  authority;
- assigning dialogue ownership is a coordination decision, not a capability grant;
- a conversation reference provides correlation only; visibility comes from the resolved
  specialist profile and a policy-filtered projection;
- a configuration object is not the same as a running invocation.

### 3.3 Mapping The Earlier Vision

The earlier ideas fit this model without becoming separate engines:

| Vision term | Technical meaning |
| --- | --- |
| **Smart Brain** | The bounded reasoning behavior inside a specialist invocation, supported by the existing planner, retrieval, action, and policy flow |
| **Router Agent** | Usually a deterministic `SpecialistSelectionPolicy`; an LLM-assisted initial selector or later supervisor is optional only for genuine ambiguity |
| **Multi-agent** | Multiple isolated specialist invocations with explicit delegation or handoff and typed aggregation |
| **Execution plan** | A small, versioned specialist-coordination blueprint interpreted deterministically |
| **Supervisor** | An optional specialist that proposes typed coordination directives; it never replaces coordinator enforcement |
| **Proactive AI** | A trusted event, schedule, file, or application condition that starts the same specialist flow without waiting for a chat message |
| **Human in the loop** | A governed decision boundary before a sensitive effect, or after an uncertain/high-impact outcome requires evaluation or recovery |

If one conversation merely changes instructions or available context inside one specialist
invocation, it remains one agent. A Mode is an orchestration preset, not an agent persona. The flow
becomes multi-agent only when separate specialist invocations have explicit ownership, isolated
context, typed results, and coordination. Those specialists may support one user-facing execution,
but they do not become participants in an unrestricted shared chat.

## 4. Mode Compatibility And Minimal Evolution

### 4.1 Keep Mode Working As It Does Today

Mode remains a reusable orchestration preset. The first specialist release should require no Mode
schema redesign.

Today, Mode already provides shared behavior such as:

- `actionsEnabled`, which controls the general action catalogue and normal action handling while
  preserving the current policy-controlled READ exceptions;
- retrieval and deep-retrieval behavior;
- current vector-space allowlists and RAG limits;
- read-action-resolution enablement, planning mode, RAG cooperation, planner limits, and the
  planner READ-action allowlist;
- prompt and orchestration hints already implemented in `ModeOverrides`.

Those semantics remain authoritative wherever they already apply. Existing applications that invoke
AI Fabric with only a Mode continue to receive exactly the current behavior.

Mode is not the complete definition of an agent. In particular, the current implementation does
not provide one Mode-level list that scopes every direct READ and WRITE action. When actions are
enabled, ordinary action discovery can still expose the registered action catalogue; only the
planner-driven READ path has the specific `allowedReadActions` filtering described above.

The specialist extension closes that agent-scoping gap without pretending the gap is a missing Mode
feature.

Current implementation evidence:

- [`OrchestrationProperties.ModeOverrides`](https://github.com/Loom-AI-Labs/ai-fabric-framework/blob/main/ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/config/OrchestrationProperties.java)
  contains `actionsEnabled` and the planner-specific `allowedReadActions`, but no general direct or
  WRITE action allowlist;
- [`EnrichedPromptBuilder`](https://github.com/Loom-AI-Labs/ai-fabric-framework/blob/main/ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/EnrichedPromptBuilder.java)
  treats `actionsEnabled` as an all-or-nothing switch for the general available-action section;
- [`ReadActionResolutionService`](https://github.com/Loom-AI-Labs/ai-fabric-framework/blob/main/ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/information/ReadActionResolutionService.java)
  applies the Mode-derived allowlist specifically to planner-eligible READ actions;
- [`IntentHandlingStep`](https://github.com/Loom-AI-Labs/ai-fabric-framework/blob/main/ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/steps/IntentHandlingStep.java)
  preserves the current policy-controlled READ exceptions when general actions are disabled.

### 4.2 Mode Admission Rule

Do not add a field to Mode merely because a specialist needs it. A proposed Mode addition is
accepted only when all of these are true:

1. the concern is genuinely shared by multiple specialists using that Mode;
2. it is not part of one specialist's identity, objective, evidence, actions, instructions, output,
   delegation, conversation behavior, or specialist budget;
3. it does not belong more naturally in application policy, framework-wide policy, an action
   definition, or a reusable named policy profile;
4. it preserves existing Mode-only behavior and has clear fallback semantics;
5. a concrete adopter or reference use case proves the need.

The default decision is therefore: keep agent-specific configuration in
`SpecialistDefinition`. Mode does not need a bigger wardrobe for every new specialist.

### 4.3 What Belongs Where

| Concern | Correct owner |
| --- | --- |
| Existing action/retrieval/RAG/planner settings shared by a Mode | Mode, unchanged |
| Agent identity, purpose, and instructions | `SpecialistDefinition` |
| Exact evidence and context one agent requests | `SpecialistDefinition.evidenceScope` |
| Exact direct READ actions visible to one agent | `SpecialistDefinition.actionScope.directReadActions` |
| Exact planner READ actions available to one agent | `SpecialistDefinition.actionScope.plannerReadActions` |
| Exact WRITE actions one agent may propose | `SpecialistDefinition.actionScope.proposableWriteActions` |
| Typed input and output | `SpecialistDefinition` |
| Reasoning, clarification, and response behavior | `SpecialistDefinition.behavior` |
| Eligibility to own external dialogue | `SpecialistDefinition.behavior.userInteractionCapability` |
| Actual dialogue owner for one execution turn | `ExecutionPlanDefinition.interactionPolicy`, resolved and enforced in `AIExecution` |
| Per-specialist conversation projection | `ConversationContextProjector` under effective profile and application policy |
| Specialist limits | `SpecialistDefinition.limits` |
| Aggregate multi-step limit | `ExecutionPlanDefinition.maximumBudget` |
| Cross-application security ceiling | Application or framework policy |
| Action access mode, parameters, confirmation, and implementation | Existing action definition and registry |
| Current caller, tenant, and subject authority | Trusted application context and application policy |
| Coordination order | `ExecutionPlanDefinition` |
| User-facing channel and rendering | Host application |

Several specialists can reference the same Mode and still expose different data, actions, outputs,
and behavior. That is reuse—not permission duplication.

### 4.4 Execution Strategy Belongs To The Specialist

Agentic behavior describes how one specialist reasons. It therefore belongs to
`SpecialistDefinition`, not to a new broad Mode named `agentic`.

```java
public enum ExecutionStrategy {
    DIRECT,
    SINGLE_PASS,
    BOUNDED_ITERATIVE
}
```

| Strategy | Meaning |
| --- | --- |
| `DIRECT` | Run the existing governed orchestration without model-directed read planning |
| `SINGLE_PASS` | Permit one approved plan/action/observation pass before producing a result |
| `BOUNDED_ITERATIVE` | Reuse the current iterative read-action loop within specialist and policy limits |

The current Mode read-action-resolution configuration still constrains what is possible:

- when Mode disables read-action resolution, the specialist cannot enable it;
- when Mode allows only single-pass planning, a specialist cannot request iterative planning;
- when Mode allows iterative planning, a specialist may choose a simpler strategy;
- `actionsEnabled=false` hides the general action catalogue and blocks WRITE proposals; the
  existing policy-controlled planner/grounding READ exceptions remain available exactly as they do
  today.

Invalid combinations should fail when the specialist is registered. This adapts the current loop;
it does not create a second planner or silently rewrite Mode behavior.

### 4.5 Effective Capability Resolution

`SpecialistDefinition` is the single definition of the agent, but not the sole source of
authorization. AI Fabric creates an internal `ResolvedSpecialistProfile` for each invocation:

```text
specialist-declared evidence and action scope
∩ existing Mode restrictions where they apply
∩ registered action and evidence capabilities
∩ tenant policy
∩ caller or service authority
∩ subject and data-access policy
∩ trigger and position restrictions
∩ source delegation boundary for dynamic handoff
= effective specialist capabilities
```

Dimension-specific rules are clearer than one vague precedence rule:

| Dimension | Resolution rule |
| --- | --- |
| Allowed evidence and actions | Intersection of every applicable restriction |
| `actionsEnabled` | `false` disables the general action catalogue and WRITE proposals; current policy-controlled READ exceptions remain |
| Limits and budgets | Lowest applicable limit |
| Execution strategy | Least powerful permitted strategy |
| Confirmation and human review | Strictest requirements accumulate |
| Prompt | Framework safety overlay + existing Mode context + specialist instructions |
| Conversation context | Frozen turn snapshot projected through the effective profile, privacy, tenant, subject, and plan-input policy |
| Output | Specialist type/schema; a plan mapping may narrow or transform it |
| Application authorization | Re-evaluated from trusted current context |
| Registration conflict | Reject the specialist definition |
| Conflict after wait/resume | Deny, expire, or require a fresh review; never widen silently |

The concrete action sets are:

```text
effective direct READ actions
  = registered READ actions
  ∩ Specialist.directReadActions
  ∩ current application authority

effective planner READ actions
  = registered planner-eligible READ actions
  ∩ Specialist.plannerReadActions
  ∩ current Mode allowedReadActions when configured
  ∩ current application authority

effective WRITE proposals
  = registered WRITE actions
  ∩ Specialist.proposableWriteActions
  ∩ current application authority

approved conversation view
  = frozen conversation snapshot
  ∩ Specialist.evidenceScope.contextSources
  ∩ current Mode and application context restrictions
  ∩ tenant, privacy, subject, and authority policy
  ∩ plan input mapping
```

When Mode sets `actionsEnabled=false`, the ordinary direct-action catalogue and all WRITE proposals
are disabled. Planner READ actions—and narrowly allowed grounding READ handling—continue only when
the current Mode/read-action policy explicitly permits the existing exception. The specialist
scope can narrow those READ actions but cannot create a new exception.

These effective sets must be used in prompt construction, intent extraction, planner selection,
direct-action resolution, proposal validation, and final pre-execution validation. Filtering only
the prompt would be security by polite suggestion.

### 4.6 Compatibility And Safety Rules

1. A legacy request without a specialist follows the current Mode-only path unchanged.
2. A new specialist must declare explicit evidence and action scopes. Explicit empty sets mean
   “none”; an absent scope fails registration rather than inheriting the full registry.
3. A specialist references one existing Mode for orchestration compatibility and shared behavior.
4. A handoff creates a new successor specialist invocation; it does not mutate an agent in place.
5. A target specialist is independently authorized and never inherits a union of parent or sibling
   capabilities.
6. Capability filtering occurs before exposure to the model and again before action invocation.
7. Durable state pins the specialist version and a hash or snapshot of the resolved effective
   profile. Mode versioning is not required merely to introduce specialists.
8. Specialist prompts, examples, and preferred models cannot bypass effective capabilities,
   mandatory review, or application limits.
9. A fixed-plan transition is coordinator-owned; delegation metadata applies only to dynamic
   specialist-requested handoff.
10. Review policy may select only registered application-approved policies and dispatchers. The
    model and untrusted request cannot choose a reviewer, recipient, or channel.
11. An action receipt never enlarges authority or creates permission for a follow-up action.
12. A conversation reference never enlarges context visibility; every invocation receives only its
    independently projected view.
13. Exactly one invocation owns external dialogue in an interactive turn; worker invocations
    cannot append conversation messages.

## 5. Proposed Specialist And Execution Contracts

### 5.1 Versioned `SpecialistDefinition`

A conceptual definition is:

```java
public record SpecialistDefinition<I, O>(
    String id,
    String version,
    String description,
    String modeRef,
    String objective,
    SpecialistInstructions instructions,
    TypeContract<I> inputContract,
    TypeContract<O> outputContract,
    SpecialistEvidenceScope evidenceScope,
    SpecialistActionScope actionScope,
    SpecialistBehavior behavior,
    HumanControlProfile humanControl,
    SpecialistLimits limits,
    DelegationPolicy delegation,
    Set<TriggerType> supportedTriggers,
    Map<String, String> selectionTags
) {}

public record SpecialistInstructions(
    String promptProfileRef,
    String examplesRef,
    Set<String> frameworkOverlayRefs
) {}

public record SpecialistEvidenceScope(
    Set<String> entityTypes,
    Set<String> documentTypes,
    Set<String> vectorSpaces,
    Set<ContextSource> contextSources,
    Optional<String> metadataPolicyRef
) {}

public record SpecialistActionScope(
    Set<String> directReadActions,
    Set<String> plannerReadActions,
    Set<String> proposableWriteActions
) {}

public record SpecialistBehavior(
    ExecutionStrategy executionStrategy,
    ResponseStyle responseStyle,
    ClarificationPolicy clarificationPolicy,
    UserInteractionCapability userInteractionCapability,
    Optional<String> preferredModelProfileRef
) {}

public enum UserInteractionCapability {
    INTERNAL_ONLY,
    DIALOGUE_CAPABLE
}

public record HumanControlProfile(
    Optional<String> resultReviewPolicyRef,
    Map<String, String> actionReviewPolicyRefs,
    Optional<String> uncertainOutcomeReviewPolicyRef
) {}

public record DelegationPolicy(
    Set<String> allowedSpecialistTargets,
    int maximumDepth,
    int maximumChildren
) {}

public record SpecialistLimits(
    int maximumIterations,
    int maximumActionCalls,
    int maximumModelCalls,
    Duration timeout,
    OptionalLong maximumInputTokens,
    Optional<BigDecimal> maximumCost
) {}

public enum ContextSource {
    REQUEST,
    CONVERSATION,
    PINNED_ATTACHMENTS,
    APPLICATION_CONTEXT,
    RETRIEVAL,
    ACTION_OBSERVATION
}
```

`ContextSource.CONVERSATION` means that the specialist may receive a policy-projected
`ApprovedConversationView`; it never means direct access to the conversation store or complete
transcript.

`SpecialistDefinition` is the server-owned, versioned agent definition and the single point of view
for understanding that agent. Its responsibilities are:

- stable discovery identity;
- user/developer-readable purpose and objective;
- one existing Mode reference for orchestration compatibility and shared behavior;
- versioned prompt, examples, and approved framework overlays;
- concrete Java or schema input/output contract;
- declared entity, document, vector-space, and context-source scope;
- direct READ actions visible to the specialist;
- planner READ actions requestable inside the bounded action-observation loop;
- WRITE actions the specialist may propose, but never execute directly;
- bounded reasoning, clarification, response-style, and model-preference behavior;
- whether the specialist is eligible to own external dialogue or must remain an internal worker;
- specialist limits;
- review-policy references for results, proposed actions, and uncertain outcomes;
- bounded delegation and handoff targets;
- supported trigger and deterministic-selection metadata.

The specialist declares capability names and policy references; it does not duplicate their
implementations. Action schemas and handlers remain in `AIActionRegistry`; retrieval adapters and
indexed entities remain in existing AI Fabric modules; reviewer authorization and domain policy
remain application-owned. One readable definition, many authoritative services.

Registration must validate:

- every named action exists and matches the declared READ or WRITE access mode;
- direct READ and planner READ sets are validated independently; overlap is allowed but not
  required;
- every planner READ action is marked planner-eligible by action metadata;
- the specialist's planner scope is compatible with the referenced Mode's current read-action
  resolution configuration;
- every evidence space/type and policy reference exists;
- the strategy is compatible with the current Mode planner behavior;
- a specialist assigned as dialogue owner declares `DIALOGUE_CAPABLE`;
- review references resolve only to registered application-approved policies;
- delegation targets are registered and cannot exceed configured depth/child limits;
- missing evidence or action scopes fail closed for new specialist definitions.

The effective limits are the minimum of specialist limits, execution-plan allocation,
framework/application ceilings, and relevant limits already present in the referenced Mode.

Specialist definitions must be registered and versioned by the application. An untrusted request or model may
select only from an already-authorized specialist set; it may not supply an arbitrary system prompt,
profile, evidence/action scope, examples set, review destination, or model configuration.

#### Example: Account Resolver

The exact configuration binding can evolve, but the ownership should be obvious:

```yaml
ai:
  orchestration:
    default-mode: resolver
    modes:
      resolver:                         # existing Mode stays intact
        actions-enabled: true
        read-action-resolution:
          enabled: true
          planning-mode: ITERATIVE
          require-allowlist: true
          allowed-read-actions:
            - get_account_profile

  specialists:
    account-resolver:
      version: "1"
      mode-ref: resolver
      objective: >
        Identify the relevant account and explain a safe resolution
        from current approved application evidence.
      prompt-profile-ref: account-resolver-v1
      input-contract: AccountResolutionRequest
      output-contract: AccountResolutionResult

      evidence:
        entity-types: [account, payment]
        vector-spaces: [account-support]
        context-sources: [request, conversation, retrieval, action-observation]

      actions:
        direct-read: [get_account_profile]
        planner-read: [get_account_profile]
        proposable-write: []

      behavior:
        execution-strategy: BOUNDED_ITERATIVE
        clarification-policy: WHEN_REQUIRED
        user-interaction-capability: DIALOGUE_CAPABLE
        response-style: CONCISE

      limits:
        maximum-iterations: 4
        maximum-action-calls: 6
        timeout: 20s

      human-control:
        result-review-policy-ref: account-resolution-review

      delegation:
        allowed-specialist-targets: [payment-investigator]
        maximum-depth: 1
        maximum-children: 1
```

The duplicate-looking planner list is deliberate during compatibility: the existing Mode list is a
shared restriction; the specialist list states what this specific agent requests. Effective access
is their intersection.

### 5.2 `SpecialistInvocation`

```text
SpecialistInvocation
  invocationId
  executionId                  logical parent; may remain in memory
  stepId                       explicit plan step or implicit root step
  relation                     ROOT | PLAN_STEP | DELEGATED_CHILD |
                               HANDOFF_SUCCESSOR | CONVERSATION_MANAGER
  executionRole                DIALOGUE_OWNER | WORKER
  parentInvocationId          optional; true delegated child only
  predecessorInvocationId     optional; fixed sequence or handoff
  specialistId + version
  promptProfileRef + version
  modeId
  resolvedSpecialistProfileHash
  conversationRef             optional; correlation only
  conversationAccess          NONE | PROJECTED_READ | DIALOGUE_OWNER
  conversationSnapshotRevision optional
  approvedConversationViewRef optional
  triggerRef
  initiatorRef
  subjectRef                  optional
  tenantRef
  authorityContextRef
  inputRef
  resultRef
  status
  remainingBudgets
  createdAt / deadline
```

A direct synchronous request has one logical execution and one invocation, both of which may remain
in memory. It is treated as an implicit one-step plan. A coordinated request has one execution and
several step, delegated-child, handoff-successor, or conversation-manager invocations.

A fixed plan transition from step A to step B is coordinator-owned and uses predecessor/step
relationships. It is not represented as specialist A dynamically delegating to B.

`conversationRef` is an audit and correlation reference; it does not authorize transcript access.
Only the invocation assigned `DIALOGUE_OWNER` may conduct external dialogue. A worker may receive
an immutable `ApprovedConversationView` if its resolved specialist profile permits conversation
context. `NONE` remains the default for non-interactive work.

### 5.3 `AIExecutionGateway` And `AIExecution`

`AIExecutionGateway` is the narrow Java/application facade through which trusted adapters submit
or continue work:

```java
public interface AIExecutionGateway {
    <I> ExecutionHandle submit(ExecutionRequest<I> request);
    ExecutionHandle resume(String executionId, ResumeInput input);
    void cancel(String executionId, CancellationReason reason);
    ExecutionStatus status(String executionId);
    Optional<AIExecutionResult<?>> result(String executionId);
}

public record ExecutionRequest<I>(
    ExecutionSource source,
    Optional<String> specialistRef,
    Optional<String> planRef,
    Optional<String> selectionKey,
    Optional<String> conversationRef,
    I input,
    TrustedExecutionContextRef trustedContext
) {}

public enum ExecutionSource {
    USER_INTERACTION,
    APPLICATION_CALL,
    DOMAIN_EVENT,
    SCHEDULE,
    FILE,
    BATCH
}
```

The gateway:

- accepts already-authenticated application context;
- requires at most one of `specialistRef`, `planRef`, or `selectionKey`; when none is present, only a
  registered entry-point mapping may resolve the target;
- validates the requested specialist, plan, or registered selection key;
- resolves the target in the order: explicit target, registered endpoint/position/trigger mapping,
  deterministic application policy, then optional LLM-assisted selection over an already-
  authorized candidate set;
- creates an implicit one-step plan when a specialist is submitted directly;
- assigns an interaction policy only when the source represents actual dialogue;
- delegates lifecycle control to the deterministic coordinator;
- exposes Java/service contracts without requiring a generic public network API.

A new `USER_INTERACTION` request may carry a real conversation reference. Other sources reject an
untrusted conversation reference by default. `resume(...)` accepts only a typed pending input,
confirmation, or application continuation that matches the pinned execution; reviewer decisions
continue to use `ReviewDecisionGateway`.

It does not invoke a model, resolve tools, execute actions, or own execution state itself.

`AIExecution` is the small, channel-neutral running coordination envelope for an explicit plan or
implicit one-step plan. It may remain ephemeral and does not imply mandatory persistence. It is not
a second orchestration engine and it must not become a universal payload containing prompts,
documents, domain entities, vector data, and provider details.

```text
AIExecution
  executionId
  execution source and entry-point mapping
  trigger type and reference      optional
  initiator / subject / authority references
  tenant and correlation references
  conversationRef                 optional
  conversationSnapshotRevision    optional; frozen for one interactive turn
  dialogueOwnerInvocationId       exactly one for an interactive turn; otherwise absent
  requested specialist, execution plan, or goal
  planId + version            explicit or generated implicit one-step plan
  resolvedPlanHash
  active / ready / waiting / completed step IDs
  execution status
  aggregate execution-plan budget
  current review, confirmation, or typed-input wait reason
  result reference
```

Conversation identity belongs primarily to `AIExecution`, because it describes the external
interaction that the coordinated work supports. Child invocations retain only correlation and the
specific projected view required for their work. The execution store—not the conversation
transcript—holds plan progress, branch waits, aggregation, and resume state.

### 5.4 `ExecutionPlanDefinition`

`ExecutionPlanDefinition` is an immutable, versioned blueprint for composition. It defines order
and typed boundaries; it grants no data, action, provider, or identity authority.

```java
public record ExecutionPlanDefinition<I, O>(
    String id,
    String version,
    PlanStrategy strategy,
    TypeContract<I> inputContract,
    TypeContract<O> outputContract,
    String entryStepId,
    Map<String, PlanStep> steps,
    Set<PlanTransition> allowedTransitions,
    InteractionPolicy interactionPolicy,
    Optional<String> aggregatorRef,
    ExecutionBudget maximumBudget,
    FailurePolicy failurePolicy
) {}

public record InteractionPolicy(
    DialogueOwnerStrategy dialogueOwnerStrategy,
    Optional<String> dialogueOwnerSpecialistRef
) {}

public enum DialogueOwnerStrategy {
    NONE,
    ROOT_SPECIALIST,
    CONVERSATION_MANAGER
}

public enum PlanStrategy {
    FIXED,
    SUPERVISED_BOUNDED
}

public sealed interface PlanStep
    permits SpecialistStep,
            DeterministicDecisionStep,
            HumanReviewStep,
            AggregateStep,
            ParallelStep {}
```

The first plan release should support only acyclic fixed sequential plans, deterministic decisions,
and deterministic aggregation. `HumanReviewStep` may be reserved in the contract but its durable
wait/resume behavior belongs to P3. `ParallelStep` and `SUPERVISED_BOUNDED` are contract
placeholders until isolation, cancellation, budgets, and typed directives are proven.

An implicit direct interactive plan defaults to `ROOT_SPECIALIST`; an implicit non-interactive
request resolves to `NONE`. An explicit multi-specialist plan may declare which owner strategy to
use when it is invoked interactively. The same plan invoked from a machine source has no actual
dialogue owner. `NONE` means that the plan does not support interactive dialogue. Assigning the
role does not expand the selected specialist's evidence, action, or application authority.

At registration, AI Fabric must validate:

- unique step IDs and valid references;
- entry and terminal steps;
- input/output type compatibility across transitions;
- known, versioned specialist and aggregator references;
- every non-`NONE` interaction policy resolves one registered, `DIALOGUE_CAPABLE` owner;
- plan-level limits within application policy;
- absence of cycles unless a later bounded-loop contract explicitly permits them.

At execution resolution, a new `USER_INTERACTION` execution requires exactly one owner. A resume
retains and revalidates the pinned execution's interaction role. A newly created non-interactive
execution has no owner, even when the reusable plan contains an interaction policy for possible
interactive use.

A `ResolvedExecutionPlan` pins the plan, specialist, prompt-profile, and schema versions plus the
resolved specialist-profile hash authorized for one run. It records the referenced Mode ID and the
effective settings used without requiring a new Mode-version contract. Every step is independently
authorized. A plan may order a specialist but cannot grant permission to use it.

### 5.5 Typed Results

An expected-output example is prompt guidance, not a contract. The contract must be a Java type or
validated JSON schema.

```java
public record SpecialistResult<O>(
    O output,
    List<EvidenceReference> evidence,
    List<ActionProposal> proposedActions,
    FinishReason finishReason,
    UsageSummary usage,
    List<String> warnings
) {}
```

`SpecialistResult` records what one specialist concluded or proposed. It must not pretend that a
proposed WRITE action has executed.

A specialist that cannot complete without external input returns a typed wait outcome instead of
writing directly to the conversation:

```java
public record NeedsUserInput(
    String requestId,
    String requestingInvocationId,
    String purposeCode,
    String safeQuestion,
    TypeContract<?> responseContract,
    Optional<Instant> expiresAt
) {}
```

The coordinator validates that the request is allowed, records which invocation is waiting, and
routes it through the one dialogue owner or a host-application input channel. Input returns through
`AIExecutionGateway.resume(...)`; only the requesting branch resumes. The request does not grant
the worker transcript access, and it is distinct from a `ReviewTask`: input supplies missing facts,
while review supplies a governed human decision or authority.

The application-owned registered handler returns an authoritative typed receipt when AI Fabric
invokes an approved WRITE action:

```java
public enum ActionExecutionOutcome {
    COMMITTED,
    REJECTED,
    FAILED_BEFORE_COMMIT,
    OUTCOME_UNKNOWN
}

public record ActionReceipt<T>(
    String actionInvocationId,
    String actionId,
    String actionVersion,
    String idempotencyKey,
    ActionExecutionOutcome outcome,
    Optional<DomainReference> affectedResource,
    Optional<String> domainRevision,
    Optional<T> safeResult,
    Optional<String> safeFailureCode,
    Instant issuedAt
) {}
```

Only the application handler may issue a receipt that asserts `COMMITTED`, `REJECTED`, or
`FAILED_BEFORE_COMMIT`; it may also explicitly report `OUTCOME_UNKNOWN`. The outcomes mean:

| Outcome | Meaning and continuation rule |
| --- | --- |
| `COMMITTED` | The handler asserts that the business effect committed; never retry this action invocation |
| `REJECTED` | The handler conclusively declined the business operation with no commit; this is distinct from a user/reviewer rejecting the earlier proposal |
| `FAILED_BEFORE_COMMIT` | The handler proves that no business effect committed; retry only when declared policy permits it and the same idempotency contract is preserved |
| `OUTCOME_UNKNOWN` | Commitment cannot currently be established; reconcile or enter outcome review and never retry blindly |

If a timeout, connection loss, or missing response means no receipt arrives, AI Fabric must not
fabricate an application receipt. It creates an `ActionFinalizationRecord` with an effective
`OUTCOME_UNKNOWN` state and an empty receipt reference:

```java
public record ActionFinalizationRecord(
    String actionInvocationId,
    ActionExecutionOutcome effectiveOutcome,
    Optional<ActionReceiptReference> receipt,
    Optional<String> domainRevision,
    RevisionVisibilityStatus revisionVisibility,
    ActionFinalizationStatus status,
    List<String> warnings
) {}
```

The final execution result can therefore distinguish proposals from completed application
operations and preserve unresolved finalization explicitly:

```java
public record AIExecutionResult<O>(
    O output,
    List<EvidenceReference> evidence,
    List<ActionProposal> proposedActions,
    List<ActionReceiptReference> actionReceipts,
    List<ActionFinalizationReference> actionFinalizations,
    FinishReason finishReason,
    UsageSummary usage,
    List<String> warnings
) {}
```

Invalid structured output should produce a visible typed failure, retry within policy, request
clarification, or enter review. It must not be silently treated as a valid business result.

## 6. Secure Specialist And Profile Selection

### 6.1 Selection Order

Every source enters through `AIExecutionGateway`; channel adapters do not bypass selection or call a
specialist directly. Use the least dynamic target-resolution method that solves the request:

1. the trusted application explicitly selects a specialist or registered execution plan;
2. a position, endpoint, domain event, or application mapping selects it deterministically;
3. application predicates choose among approved candidates;
4. an optional LLM-assisted selector handles genuine ambiguity;
5. a parent specialist delegates or hands off only to its configured
   `delegation.allowedSpecialistTargets` subset.

The model is never given the full registry before security filtering.

Mode does not choose the specialist. Selection resolves a specialist or plan first; the selected
definition then supplies its Mode reference for the existing orchestration flow. Legacy
position-to-Mode routing remains available only for requests that use the legacy path.

The first point of contact is therefore explicit:

```text
User → chat/UI/API adapter → AIExecutionGateway → entry-point resolution → specialist or plan

Event/schedule/file/batch
     → trusted trigger adapter → AIExecutionGateway
     → deterministic trigger mapping → specialist or plan
```

A user-facing adapter may supply a real `conversationRef`. A trigger adapter normally does not.
The trigger carries a service/system initiator and trusted execution context; it must not create a
fake user or conversation to reuse the chat path.

Selecting a plan does not authorize every specialist it names. Plan resolution must independently
validate every referenced specialist, then load its Mode reference for current orchestration
compatibility and resolve its effective capabilities before creating the per-execution
`ResolvedExecutionPlan`.

### 6.2 Candidate Resolution

```text
registered specialist definitions
∩ tenant-permitted specialists
∩ caller/service-authorized specialists
∩ trigger/position-compatible specialists
∩ application policy
∩ resolved-plan target set when plan-bound
∩ source delegation.allowedSpecialistTargets when specialist-requested delegation/handoff
= candidates available to the selector
```

An LLM-assisted selector may recommend only from this final set. The server validates the returned
ID, loads the registered definition, loads its referenced Mode, and resolves the effective
specialist profile; the model cannot invent a specialist, prompt profile, evidence/action scope, or
Mode.

`delegation.allowedSpecialistTargets` restricts dynamic target specialist IDs; it does not define
the mandatory order of a fixed plan, copy the parent's permissions into the child, or require
unrelated specialist modes to have identical capabilities. Every target is independently
reauthorized against caller/service
authority, tenant policy, subject access, application policy, the resolved plan, its own declared
scope, and the current restrictions of its referenced Mode. Parent, child, and sibling permissions
are never unioned.

### 6.3 Selection Evidence

A safe `ExecutionSelectionDecision` should record:

- execution source and registered entry-point/trigger mapping;
- selected plan and version, or implicit one-step-plan reason;
- selected entry specialist and version;
- selected prompt profile and specialist versions, referenced Mode ID, and effective-profile hash;
- selection source: explicit, position, rule, model-assisted, or parent delegation;
- safe reason code;
- candidate-set hash or version;
- rejection or clarification reason when no candidate is valid.

Do not store sensitive prompts or unrestricted candidate metadata merely to explain routing.

## 7. Reusing The Existing Orchestration

The proposed execution path is:

```text
Chat/UI adapter | API adapter | application/trigger adapter | input-resume adapter
                              ↓
                    trusted context creation
                              ↓
                    AIExecutionGateway
                              ↓
       explicit or registered entry-point target resolution
                              ↓
       resolve effective specialist capabilities
      and authorize/pin ResolvedExecutionPlan
       (implicit one-step plan when appropriate)
                              ↓
            deterministic AIExecutionCoordinator
                              ↓
      each SpecialistStep invokes existing RAGOrchestrator
 context → policy → intent → read loop/RAG → result or action proposal
                              ↓
      confirmation/review when a WRITE proposal requires it
                              ↓
 AI Fabric invokes registered handler through GovernedActionExecutionService
                              ↓
 application business service → ActionReceipt or explicit unknown finalization
                              ↓
 ActionOutcomeFinalization → typed result and validated transition
                              ↓
       return | aggregate | continue | wait | escalate
```

Governed reviewer decisions continue to enter through `ReviewDecisionGateway`; after reviewer and
task validation, that gateway asks the same coordinator to resume the pinned execution.

The coordinator invokes the current `RAGOrchestrator`. It does not reproduce the current pipeline
or call model providers through a parallel path. Even the optional conversation manager is invoked
as an ordinary bounded specialist through this same path.

### 7.1 Single-Specialist Interactive Flow

```text
Open or resume conversation
        ↓
submit through AIExecutionGateway
        ↓
resolve and bind specialist + Mode
        ↓
Resolve effective evidence/actions/behavior
        ↓
Create an implicit one-step AIExecution
        ↓
assign the root invocation as the one dialogue owner
and freeze the conversation snapshot for this turn
        ↓
Call the existing pipeline for each turn
        ↓
append one validated response and persist conversation continuity
        ↓
Return the typed result or pending confirmation
```

This is the first implementation target because it proves the specialist abstraction with minimal
change.

### 7.2 Enforcement Work Required

Before calling the flow specialist-safe:

- introduce one `EffectiveCapabilitiesResolver` used by prompt construction, intent extraction,
  planner READ actions, direct READ actions, WRITE proposals, and pre-execution validation;
- apply the specialist's explicit evidence/action scopes and preserve the current Mode planner
  allowlist and `actionsEnabled` behavior as additional restrictions;
- repeat validation immediately before application execution;
- invoke approved WRITE actions only through the existing registry and one governed AI Fabric
  execution service;
- bind the specialist version, referenced Mode ID, effective-profile hash, prompt-profile version,
  plan, and step to action drafts and pending actions;
- ensure a later confirmation cannot resume under a wider effective capability set;
- validate plan references, transition types, specialist schemas, and version pins;
- assign at most one dialogue owner, project conversation context independently for every worker,
  and prevent workers from reading or appending the unrestricted transcript;
- route typed `NeedsUserInput` outcomes through the dialogue owner or application input adapter and
  resume only the requesting invocation;
- enforce the specialist execution strategy against current Mode planner behavior, plan allocation,
  and application/framework limits;
- expose explicit finish reasons such as completed, clarified, budget-exhausted, denied, review
  required, or failed;
- enforce time and cost limits in addition to iteration and action-count limits;
- either implement configured parallelism safely or document it as non-operative.

### 7.3 Governed Action Execution And Outcome Finalization

AI Fabric should own the action-execution lifecycle without taking ownership of the application's
business operation.

Application developers define and register the action implementation and metadata through the
existing `@AIAction`/`@ActionExecute` model or equivalent registry adapter. A model, specialist,
execution plan, review decision, or untrusted request may select only from authorized registered
actions; none of them can create a new action definition.

```text
specialist-declared and effectively authorized ActionProposal
          ↓
schema, policy, identity, authority, and source-version validation
          ↓
confirmation or durable review when required
          ↓
revalidate immediately before execution
          ↓
GovernedActionExecutionService invokes registered @AIAction handler
          ↓
application performs domain authorization, validation, transaction, and side effect
          ↓
ActionReceipt received? ── yes → validate, deduplicate, and record immutable receipt
          │
          └────────────── no  → record OUTCOME_UNKNOWN ActionFinalizationRecord
          ↓
coordinate existing live-sync visibility when domainRevision and adapter support exist
          ↓
complete | continue declared transition | wait for outcome review | escalate
```

`GovernedActionExecutionService` is an AI Fabric service over the existing `AIActionRegistry`; it
is not a second action registry. It owns:

- final framework-policy and schema validation;
- confirmation/review-state verification;
- creation of action invocation and idempotency references;
- invocation of the registered handler;
- receipt correlation, validation, deduplication, and safe provenance;
- deterministic continuation based on the declared plan and receipt outcome.

The registered application handler owns:

- current domain authorization;
- business validation and invariants;
- transaction and side effect;
- application idempotency enforcement;
- the authoritative outcome, affected resource, and resulting domain revision;
- any domain event or transactional-outbox publication.

Action outcome finalization must:

1. treat `COMMITTED`, `REJECTED`, `FAILED_BEFORE_COMMIT`, and `OUTCOME_UNKNOWN` distinctly;
2. never infer commit from model text, an HTTP status alone, or the absence of an exception;
3. avoid blindly retrying `OUTCOME_UNKNOWN`; reconcile through the idempotency key or an
   application-supplied status check;
4. reuse the existing transaction-aware live-sync/indexing path rather than introduce a competing
   post-action indexer;
5. optionally enforce a revision-visibility barrier, only when the receipt supplies a domain
   revision and the selected sync adapter supports visibility checks, before a later retrieval step
   that requires that revision;
6. preserve a committed business outcome even when indexing, notification, or explanation later
   fails, using a warning or recovery-required state rather than falsely reporting the action as
   failed;
7. expose only the safe receipt result as a later model observation;
8. advance only a registered plan transition or accept a new governed proposal within the same
   resolved specialist profile and remaining limits.

There must be no unrestricted post-action callback capable of silently mutating domain state. A
correction or compensation is a new registered application action with fresh authorization,
validation, idempotency, and confirmation/review policy. Cancellation cannot undo a committed
business operation.

## 8. Conversation, Continuation, And Context Isolation

Conversation and execution state solve different problems:

| State | Responsibility |
| --- | --- |
| Conversation | External user-facing turns, attachments, and conversational memory |
| Specialist definition | Goal, instructions, declared evidence/actions, behavior, human-control references, and configured limits |
| Specialist invocation | One specialist's resolved profile, projected input/context, remaining limits, observations, proposals, waits, and typed result |
| AIExecution | Entry source, optional conversation reference, dialogue owner, resolved plan, current/ready/waiting steps, delegation/handoff state, aggregate budget, waits, aggregation, and final result |
| Application domain state | Authoritative accounts, orders, products, policies, and transactions |

The governing rule is:

> One external conversation, one dialogue owner for each active interactive execution turn, and
> separate specialist invocations. The conversation is not an inter-agent bus.

### 8.1 External Conversation And Turn Boundary

An interactive request binds the conversation to the `AIExecution` and its pinned plan,
specialist, prompt, schema, Mode, and resolved-profile versions. The coordinator freezes one
conversation snapshot revision for the turn before starting specialists. Authorization is still
resolved on every turn, and the client cannot use conversation continuation to select a wider
specialist or capability set.

The initial implementation should allow one active user turn per conversation. A new user message
is queued behind the active turn or handled by an explicit cancellation/replacement policy. Two
executions must not append competing answers or mutate the same conversation state concurrently.

### 8.2 Dialogue Ownership

Dialogue ownership is an invocation role, not a second kind of agent:

- a direct interactive specialist is the `ROOT_SPECIALIST` dialogue owner;
- an interactive multi-specialist plan may keep a dialogue-capable root specialist as owner;
- when conversational routing or synthesis is genuinely complex, the plan may assign a restricted
  `ConversationManagerSpecialist`;
- a non-interactive execution has no dialogue owner;
- the coordinator validates and advances state but never generates dialogue.

Only the dialogue owner may receive the approved external turn, ask the external user a question,
and append the one final response. Assignment requires `DIALOGUE_CAPABLE` specialist behavior and
does not enlarge evidence, action, or application authority. A plan that assigns a conversation
manager is still using an ordinary, independently scoped specialist.

### 8.3 Projected Worker Context

A worker specialist never joins the external conversation directly and never receives unrestricted
access to `ChatSessionService` or the conversation store. Possessing the same `conversationRef`
provides correlation, not read permission.

Before invocation, a `ConversationContextProjector` creates an immutable
`ApprovedConversationView` from:

```text
frozen conversation snapshot
∩ specialist-declared context/evidence scope
∩ referenced Mode restrictions
∩ privacy and tenant policy
∩ current identity, subject, and authority
∩ plan input mapping
```

The resulting invocation input contains only:

- the child goal;
- the mapped typed input;
- an approved conversation summary or selected facts when allowed;
- evidence references the child is allowed to see;
- necessary user facts after privacy filtering;
- the typed contract it must return;
- remaining budgets and deadline.

Each invocation has isolated working state. It returns a typed result to the coordinator,
downstream step, or true delegating parent. Consumers see only mapped typed results and approved
evidence references—not hidden reasoning, private scratch state, or unrestricted child context.

For parallel work, every sibling starts from the same frozen conversation revision but receives a
different approved projection. One specialist's broader view must never leak into another through
aggregation.

### 8.4 Missing Input And Resume

Any specialist may return a typed `NeedsUserInput`, but an internal worker does not ask the user
directly:

1. the coordinator validates the request and the response contract;
2. the requesting invocation enters `WAITING_FOR_INPUT`;
3. the dialogue owner turns the approved request into one user-facing question, or the host
   application exposes it through another trusted input channel;
4. input returns through `AIExecutionGateway.resume(...)` with trusted context;
5. current authority, versions, expiry, and input schema are revalidated;
6. only the requesting invocation or its declared successor resumes.

If several parallel branches request input, the coordinator combines compatible requests
deterministically. A conversation manager may phrase or prioritize an ambiguous set, but it cannot
see fields outside its own approved view or invent new required data.

Missing factual input and human review are different boundaries. `NeedsUserInput` supplies a typed
fact needed to continue; `ReviewTask` records a governed approval, correction, rejection, or
escalation. A review may itself request information, but the contracts remain distinct.

### 8.5 Handoff

A handoff:

1. completes or pauses the current invocation;
2. selects a permitted target specialist;
3. creates a new successor invocation under the same execution, linked through
   `predecessorInvocationId`, with its own resolved specialist profile and referenced Mode;
4. passes a filtered handoff package;
5. transfers dialogue ownership atomically only when the plan explicitly declares an interactive
   owner handoff; ordinary worker handoff does not change the dialogue owner.

Changing the active specialist or its effective profile in place would make authorization, evidence
lineage, pending actions, and audits ambiguous and is therefore rejected.

`parentInvocationId` is reserved for delegated child work that returns a result to its parent.
Handoff transfers responsibility and therefore uses a successor link rather than pretending the
target is subordinate work.

### 8.6 Non-Chat Work

An event, batch, schedule, file, or service call normally creates an execution without a
conversation or dialogue owner. It still requires a trusted initiator, tenant, authority source,
and subject when relevant. Machine work must not invent an authenticated human principal.

If non-chat work later needs a human:

- create a `ReviewTask` when a governed decision or authority is required;
- expose a typed pending input request through the host application when a missing fact is
  required;
- open or attach a real conversation only when continuing dialogue is genuinely part of the
  product experience.

## 9. Coordination, Delegation, And Aggregation

The common interactive shape is:

```text
External user
     ↕
one DialogueOwner invocation
     ↕ typed turn / final result
AIExecutionCoordinator ← ExecutionPlanDefinition
     ├── Worker specialist A ← approved view A
     ├── Worker specialist B ← approved view B
     └── Worker specialist C ← approved view C
                     ↓
          typed fan-in / aggregation
                     ↓
          one result to DialogueOwner
```

For a machine trigger, remove the external user and dialogue-owner lane. The trusted trigger
adapter submits the typed input, and the final result returns to the application, review boundary,
or governed action lifecycle.

### 9.1 Deterministic `AIExecutionCoordinator`

The coordinator is the main new framework component, but it is a deterministic state machine—not
an LLM agent. Its responsibilities are:

1. resolve an approved plan or create an implicit one-step plan;
2. authorize and pin plan, specialist, prompt-profile, and schema versions plus the resolved
   specialist-profile hash and referenced Mode ID;
3. create execution and invocation state, assign the one dialogue owner when interactive, and
   freeze the external conversation snapshot revision;
4. validate ready steps, typed transitions, input mappings, and interaction roles;
5. project an independent approved conversation view for each invocation;
6. allocate step and child budgets below the parent ceiling;
7. invoke the existing `RAGOrchestrator` for each business or conversation-manager specialist;
8. validate typed specialist results, `NeedsUserInput`, and coordination directives;
9. follow fixed transitions or validate a supervisor proposal;
10. create true delegated children or handoff successors when permitted;
11. pause and resume the correct branch for typed input or human review;
12. delegate approved action invocation to `GovernedActionExecutionService`;
13. advance deterministically from validated action receipts and outcome-review decisions;
14. run deterministic aggregators by default and route one interactive result through the dialogue
    owner;
15. enforce deadlines, retries, cancellation, depth, child count, and terminal status.

It must not:

- interpret free-form prompts as executable control instructions;
- choose specialists through an internal ungoverned model call;
- generate user dialogue itself;
- expose the full conversation to worker specialists or allow multiple invocations to append to it;
- own another action registry, retrieval pipeline, or model-provider path;
- implement application business operations, infer that a transaction committed, or perform a
  generic rollback;
- become a generic low-code graph language;
- recreate a distributed workflow system;
- become an autonomous application owner.

Start the coordinator inside the existing framework boundary. Extract a separate module only when a
real dependency or release boundary appears.

### 9.2 Execution-Plan Semantics

An execution plan may contain:

- typed `SpecialistStep` nodes;
- deterministic decision conditions;
- explicit human-review gates;
- registered input mappings;
- registered deterministic aggregators;
- failure, timeout, and partial-result policy;
- an interaction policy that assigns an eligible dialogue owner for interactive use;
- later, feature-gated parallel groups or supervised choice points.

The plan owns coordination only. It does not redefine specialist evidence/action scopes, carry raw
prompts or credentials, or grant application authority. A plan step may map a narrower typed input,
allocate a smaller budget, or add a review gate. If a step requires a different action or evidence
scope, it must reference a different registered specialist definition or rely on application policy;
the plan itself does not become a second agent definition.

For a fixed transition `A → B`:

- the application-authored plan defines the order;
- A does not need to dynamically delegate to B;
- B is independently authorized and receives a filtered typed input;
- the coordinator validates the transition and starts B.

Interaction policy is orthogonal to step order. When a plan is invoked from a non-interactive
source, the execution has no dialogue owner even if the plan declares which specialist would own
dialogue during interactive use. When invoked interactively, the declared root specialist or
conversation manager is assigned once; worker steps remain isolated.

For a specialist-requested delegation or handoff:

- the target must appear in the source specialist's
  `delegation.allowedSpecialistTargets`;
- the target must also be permitted by the resolved plan and current application policy;
- the target receives its own independently resolved specialist profile and referenced Mode;
- parent, child, and sibling privileges are never combined.

A transition that depends on a WRITE action outcome cannot become ready until
`ActionOutcomeFinalization` has produced a validated finalization state. `OUTCOME_UNKNOWN` may
route only to reconciliation, `OUTCOME_REVIEW`, escalation, or a terminal unknown result; it may
not take a normal success transition.

### 9.3 Fixed Versus Supervised Plans

| Plan strategy | Behavior |
| --- | --- |
| `FIXED` | The coordinator follows versioned application-authored transitions and deterministic conditions |
| `SUPERVISED_BOUNDED` | A registered supervisor may propose only among plan-approved targets and transitions |

Start with `FIXED`. A supervised plan must still declare:

- approved specialist IDs and versions;
- allowed transition matrix;
- maximum turns, steps, depth, model calls, and cost;
- typed directives;
- stall and repeated-question detection;
- deterministic terminal and escalation conditions.

An LLM must never generate an unrestricted executable graph. Accepted and rejected supervisor
directives become safe execution evidence under the original bounded plan.

### 9.4 Optional `ConversationManagerSpecialist`

The conversational manager is an ordinary bounded specialist, not logic embedded inside the
coordinator. It may be assigned as the dialogue owner for a genuinely conversational plan, or it
may be invoked internally over an approved summary to propose a bounded routing decision:

```java
public sealed interface CoordinationDirective
    permits AskUser,
            ContinueCurrent,
            InvokeSpecialist,
            HandoffTo,
            CompleteExecution,
            RequestHumanReview,
            Escalate {}

public record ConversationManagerInput(
    Optional<ApprovedUserTurn> latestApprovedUserTurn,
    ApprovedConversationSummary summary,
    List<VisibleStepResult> visibleResults,
    Set<String> approvedNextTargets,
    RemainingBudget remainingBudget
) {}
```

Its input contains only:

- the latest user turn after the manager's own projection policy, when permitted;
- an approved conversation summary;
- visible typed step results;
- the already-filtered next-target set;
- remaining budget and deadline.

Its output may propose:

- ask a clarification;
- continue the active specialist;
- invoke or hand off to an approved specialist;
- complete the execution;
- request human review.
- escalate through an application-approved route.

The directive cannot contain a new Mode, specialist definition, prompt profile, permission, action
scope, executable plan fragment, or enlarged budget. The coordinator validates target, transition,
authority, schema, budget, and state before applying it.

Use the manager only when ambiguity, clarification, or conversational synthesis justifies another
model call. A direct interactive specialist already owns its dialogue, and a fixed plan requires no
manager. If the manager is not the dialogue owner, an `AskUser` directive becomes a typed input
request routed through the actual owner; it does not let the manager append to the conversation.

> The conversation manager proposes the next intelligent move. The deterministic coordinator
> validates and applies it.

### 9.5 Supported Coordination Patterns

Implement in this order:

1. **Single specialist** — one resolved specialist invocation through existing orchestration.
2. **Fixed sequential plan** — validated typed output becomes input to the next declared step.
3. **Typed input wait and resume** — a worker requests input through the dialogue owner or host
   input channel, and only that branch resumes.
4. **Human review wait and resume** — an authorized reviewer supplies a governed decision.
5. **Controlled delegation or handoff** — a permitted specialist requests a bounded target.
6. **Supervisor-directed choice** — an optional manager proposes among declared transitions.
7. **Bounded parallel fan-out/fan-in** — independent workers run within shared limits and
   conversation isolation.

Do not begin with an open-ended group chat among agents.

### 9.6 Aggregation Rules

- A single specialist result returns directly.
- Sequential composition validates every boundary before invoking the next step.
- Parallel results return to an explicit aggregator.
- Deterministic Java aggregation is the default for business decisions.
- An optional read-only synthesis specialist may explain already-aggregated results.
- In an interactive execution, the validated aggregate or synthesis result returns to the one
  dialogue owner for one user-facing response.
- Synthesis cannot alter evidence references, policy decisions, review decisions, or action
  receipts, and it cannot remove or reinterpret an unresolved action outcome.
- Conflicts produce a typed unresolved outcome, clarification, or human-review task.
- Every final result retains step provenance and failure evidence.

### 9.7 Parallel Specialist Semantics

Parallelism is fan-out/fan-in over isolated invocations—not several agents talking in one
conversation:

1. freeze one external conversation snapshot revision for the turn;
2. construct a separate typed input and `ApprovedConversationView` for each ready specialist;
3. run only independent work under aggregate and per-branch budgets;
4. prevent all worker branches from writing to the conversation;
5. allow safe READ operations in parallel when their action metadata and application policy permit;
6. keep WRITE operations as proposals, then conflict-check and govern them after fan-in rather than
   execute competing mutations concurrently;
7. collect typed results under an explicit policy such as `ALL_REQUIRED`, `QUORUM`, or
   `BEST_EFFORT`; start with `ALL_REQUIRED`;
8. aggregate deterministically by default;
9. route one final result or one consolidated input request through the dialogue owner.

Each branch has its own timeout, cancellation state, remaining limits, observations, and failure
evidence. Cancellation of one branch does not erase another branch's committed application action,
and a new user turn cannot start a competing write path against the still-active turn.

### 9.8 Budgets And Failure

The execution owns an aggregate budget. Each step or delegated child receives a smaller allocation.
At minimum track:

- maximum step and child count;
- maximum delegation depth;
- maximum manager turns;
- maximum model calls and action calls;
- token or usage budget when the provider exposes it;
- deadline and per-step timeout;
- retry budget;
- cancellation state;
- partial-failure policy.

A plan, manager, or child cannot create unbounded work. Cancellation stops future work where
possible but cannot undo an application action that has already committed.

WRITE retry behavior follows the finalized outcome: `COMMITTED` is never retried; `REJECTED` is
terminal for that proposal; `FAILED_BEFORE_COMMIT` may be retried only under declared policy with
the same application idempotency contract; and `OUTCOME_UNKNOWN` is reconciled or reviewed rather
than blindly replayed.

## 10. Human Control As A First-Class Boundary

Human control supports both pre-effect governance and post-outcome evaluation or recovery. It
applies to single-specialist, coordinated, proactive, asynchronous, and batch work.

A review may be required by the specialist's registered human-control profile, action metadata,
application/framework policy, an explicit `HumanReviewStep`, an approved `RequestHumanReview`
directive, or action-outcome finalization when execution is uncertain or requires governed
follow-up. Existing Mode behavior may contribute a shared restriction if such a rule already exists
or later satisfies the strict Mode admission rule. The LLM never approves its own work, selects its
reviewer, or grants reviewer authority.

### 10.1 Intervention Types

| Type | Typical use |
| --- | --- |
| `CONFIRMATION` | The active user confirms an immediate action proposal |
| `OPERATIONAL_REVIEW` | An authorized reviewer assesses a background recommendation or proposed action |
| `CORRECTION` | A reviewer corrects typed extraction, classification, or proposed parameters |
| `ESCALATION` | A sensitive or uncertain case moves to a qualified person |
| `OUTCOME_REVIEW` | A reviewer evaluates an unknown, mismatched, or high-impact action outcome |
| `QUALITY_SAMPLE` | A completed result is selected for evaluation without changing it |

### 10.2 Deliberately Different Paths

| Flow | Contract |
| --- | --- |
| Missing fact during an active interactive execution | Return `NeedsUserInput`, route it through the one dialogue owner, and resume the requesting invocation |
| Missing fact during non-chat work | Expose a typed pending input request through the host application; do not invent a conversation |
| Immediate confirmation inside the active request or conversation | Preserve the existing `PendingAction` lifecycle |
| Review crossing a request, process, actor, or time boundary before an effect | Create a durable `ReviewTask` attached to the proposal and execution/invocation |
| Review after an uncertain or high-impact outcome | Create a durable `ReviewTask` attached to the action invocation/finalization state, optional receipt, and exact source revisions |

Do not turn every clarification into human review, and do not require durable execution tables for
every immediate “yes” or “no.” A review carries decision authority; a typed input request carries
only the missing information allowed by its response contract.

### 10.3 Review Contracts And SPI Boundary

AI Fabric owns the review lifecycle and trusted decision gateway. Delivery, persistence
infrastructure, reviewer eligibility/authorization, and external review systems remain pluggable
application integrations.

```java
public interface ReviewTaskStore {
    ReviewTask save(ReviewTask task, TrustedReviewScope scope);
    Optional<ReviewTask> find(String reviewTaskId, TrustedReviewScope scope);
    ReviewTask transition(ReviewTransition transition, TrustedReviewScope scope);
}

public interface ReviewTaskDispatcher {
    ReviewDispatchReceipt dispatch(
        ReviewRequest request,
        ReviewDispatchContext context
    );
}

public interface ReviewerAuthorizer {
    ReviewerAuthorization authorize(
        ReviewTask task,
        ReviewDecision decision,
        TrustedReviewerContext reviewer
    );
}

public interface ReviewDecisionGateway {
    ReviewDecisionResult submit(
        String reviewTaskId,
        ReviewDecision decision,
        TrustedReviewerContext reviewer
    );
}
```

`ReviewTaskStore`, `ReviewTaskDispatcher`, and `ReviewerAuthorizer` are SPIs with registered
application-selected implementations. `ReviewDecisionGateway` is an AI Fabric-owned inbound
contract that an application endpoint, existing workflow system, or messaging adapter calls after
it has established trusted reviewer context.

`TrustedReviewScope` and `TrustedReviewerContext` must be created by authenticated application
integration, not copied from the decision payload. Every store lookup and transition remains
tenant-scoped.

The server-owned review policy selects only from registered dispatcher IDs. Neither the model nor
untrusted request data may provide a dispatcher, recipient, reviewer identity, or return endpoint.
The dispatcher receives a sanitized review request, normally containing a secure task reference
rather than sensitive evidence. A dispatch receipt proves delivery acceptance only; it is never a
review decision.

A delivery failure leaves the same task in `WAITING_FOR_REVIEW`. AI Fabric retries dispatch under
policy using the same review-task ID; it never resumes, rejects, cancels, or replaces the task
merely because a notification channel failed.

A `ReviewTask` must bind at least:

- execution, invocation, plan step, proposal, optional action-receipt, and action-finalization
  identities;
- specialist, prompt-profile, schema, and plan versions plus referenced Mode ID and
  resolved-specialist-profile hash;
- tenant, subject, initiator, and required reviewer-policy references;
- safe evidence references, source revisions, and proposal hash;
- review type, allowed decisions, expiry, and escalation policy;
- optimistic state version, idempotency key, and safe correlation references.

The task must be persisted before dispatch. Durable delivery should use an after-commit event or
outbox-compatible adapter so retries cannot create different review tasks or lose the wait state.
Review inboxes, notification screens, and third-party workflow interfaces are host-product concerns
and remain outside this framework architecture proposal.

### 10.4 Durable Review Lifecycle

```text
Specialist proposes a typed outcome/action
              ↓
specialist, action, plan, application, or outcome policy requires human intervention
              ↓
Persist version-bound ReviewTask and enter WAITING_FOR_REVIEW
              ↓
resolve registered ReviewTaskDispatcher
              ↓
dispatch safe task reference and record ReviewDispatchReceipt
              ↓
authorized reviewer approves, corrects, rejects, requests information, or escalates
              ↓
submit through ReviewDecisionGateway
              ↓
validate reviewer, task state, decision schema, versions, evidence, and expiry
              ↓
resume the pinned execution or deliberately create a successor
              ↓
when approved, GovernedActionExecutionService invokes the registered action handler
              ↓
application returns ActionReceipt, or no receipt produces unknown finalization
              ↓
ActionOutcomeFinalization records and advances the governed result
```

The model may propose and explain. The dispatcher may deliver. The reviewer may decide within
current authority. AI Fabric validates and coordinates. The registered application handler remains
the business executor.

### 10.5 Review Decisions, Freshness, And Authority

Before accepting a decision, AI Fabric must verify:

- the review task is still waiting, unexpired, and in the expected optimistic state version;
- the trusted reviewer satisfies application-owned role, tenant, subject, separation-of-duty, and
  escalation policy;
- the proposal, evidence, source revisions, action definition, specialist/schema/plan versions,
  referenced Mode ID, and effective-profile hash still match;
- the proposed action is still permitted for the original initiator/subject and current
  application policy;
- the submitted decision is allowed for this review type and satisfies its typed schema.

Decision semantics are:

- **approve** — authorize continuation, then revalidate again immediately before handler
  invocation;
- **reject** — close the proposal or follow an explicitly declared rejection transition;
- **correct** — validate typed corrections and create a revised proposal or successor invocation;
  do not silently rewrite historical evidence;
- **request information** — enter `WAITING_FOR_INPUT` with an explicit target and resume contract;
- **escalate** — create or route to a new task under a registered higher-authority policy.

Duplicate decisions must be idempotent. A stale or conflicting decision is rejected visibly rather
than applied to newer domain state.

### 10.6 Post-Outcome Human Review

Post-outcome review is appropriate when:

- an action receipt or missing-receipt finalization state is `OUTCOME_UNKNOWN`;
- an application status check conflicts with the original receipt or expected postcondition;
- policy requires inspection of a high-impact committed operation;
- live-sync revision visibility or another finalization step remains unresolved;
- a completed result is selected for quality or compliance sampling.

A committed operation cannot be retroactively “rejected.” The reviewer may accept the recorded
outcome, flag it, escalate it, request reconciliation, or authorize a separately governed
corrective/compensating action. Any correction or compensation receives a new action invocation,
idempotency key, authorization check, and confirmation/review lifecycle.

## 11. Proactive And Non-Chat Invocation

Proactive AI is not a separate intelligence implementation. It is a different trusted trigger for
the same specialist-defined or execution-plan flow through the existing orchestration.

```text
Domain event | scheduled condition | file | batch | application call
                              ↓
                  trusted trigger adapter
                              ↓
                    AIExecutionGateway
                              ↓
     explicit target or deterministic registered trigger mapping
                              ↓
               create and advance one AIExecution
                              ↓
        each specialist runs through existing orchestration
                              ↓
 signal | recommendation | persisted review task | governed action proposal
```

Start with:

1. programmatic submission;
2. a Spring application/domain-event adapter;
3. explicit specialist/plan ID or deterministic mapping;
4. no conversation or dialogue owner by default;
5. one typed outcome;
6. human review or a typed external-input wait when policy requires it.

An annotation can be a later convenience:

```java
@AITrigger(
    event = PaymentFailed.class,
    plan = "account-resolution"
)
public ResolutionRequest onPaymentFailure(PaymentFailed event) {
    return resolutionRequestFactory.from(event);
}
```

The annotation must not hide transaction phase, idempotency, duplicate delivery, tenant context,
authority construction, retry ownership, ordering, or failure behavior. Programmatic submission
must remain canonical until those semantics are proven.

The annotation should accept exactly one of `specialist` or `plan`. Selecting either still requires
current authority and independent authorization of every resolved specialist step.

Proactive behavior should produce a signal, recommendation, review task, or governed action
proposal by default—not an unsolicited model-controlled mutation.

If a machine-triggered specialist needs a missing fact, the execution enters `WAITING_FOR_INPUT`
and exposes the typed request through the host application. If it needs judgment or authority, it
creates a `ReviewTask`. Neither condition silently converts the machine flow into a chat; a real
conversation is attached only when the product deliberately opens one.

When policy requires review, AI Fabric persists the task before invoking a registered
`ReviewTaskDispatcher`. When an action is later approved, the coordinator uses
`GovernedActionExecutionService`; the trigger or review adapter never calls the domain service
directly.

## 12. State, Durability, Storage, And Recovery

### 12.1 When Persistence Is Required

No execution database is required for an ordinary synchronous, single-specialist conversation.

Durable state is required when work:

- survives the submitting call or application process;
- waits for a person or external input;
- is triggered asynchronously and may be retried;
- coordinates plan steps, parent/child work, or handoffs across restart;
- needs cancellation, replay-safe diagnostics, or policy-required evidence;
- runs as a long batch or scheduled activity.

### 12.2 Storage Boundaries

| Store | Responsibility | Authority |
| --- | --- | --- |
| Application domain store | Accounts, orders, products, policies, permissions, and transactions | System of record |
| Conversation store | External user-facing interaction and memory; never inter-specialist execution state | Interaction continuity |
| AI Fabric execution-state store | Entry source, optional conversation/snapshot reference, dialogue owner, resolved-plan progress, isolated invocation state, delegation/handoff coordination, input waits, review tasks/dispatch, retries, budgets, aggregation, typed result references, and receipts | Operational lifecycle record |
| Vector store | Searchable projection of approved application/document evidence | Derived index |
| Event broker | Optional delivery of external triggers and lifecycle events | Transport only |
| Workflow adapter | Optional support for advanced long-running flows | Infrastructure behind AI Fabric contracts |

Recommended initial adapters:

1. in-memory state for local development, tests, and bounded same-process work;
2. JDBC/JPA for durable execution, review, and recovery;
3. a pluggable state-store contract for established workflow systems when adopters need them.

Suggested logical tables:

```text
ai_execution
ai_specialist_invocation
ai_execution_event
ai_specialist_result
ai_execution_input_request
ai_review_task
ai_review_dispatch
ai_action_proposal
ai_action_receipt
ai_action_finalization
```

These tables coordinate intelligence work. They do not duplicate the application domain model.
Versioned specialist and plan definitions may remain in application configuration or a registry;
their existence does not require a database. Durable executions pin the exact registered versions
they resolved.

### 12.3 Required Durable Semantics

Before the JDBC/JPA adapter is production-ready, define:

- versioned schemas for executions, plans, invocations, directives, reviews, and results;
- exactly-one-dialogue-owner enforcement for active interactive turns and no owner for machine-only
  executions;
- immutable turn-snapshot references and independently projected specialist context;
- one-active-turn or explicit queue/cancellation semantics per conversation;
- tenant-scoped idempotency keys with uniqueness enforcement;
- optimistic state versions;
- legal state transitions and stable rejection reasons;
- worker lease, heartbeat, expiry, and abandoned-work recovery when background workers exist;
- retry classification, budgets, backoff, and terminal failure evidence;
- persist-before-dispatch review semantics, safe dispatch envelopes, delivery receipts, and
  outbox-compatible retries;
- duplicate-safe callback, review decision, resume, approval, and action-receipt handling;
- a typed action-receipt schema with correlation, idempotency, outcome, affected resource, domain
  revision, safe result/failure code, and issue time;
- `OUTCOME_UNKNOWN` reconciliation that never retries a write blindly;
- optional domain-revision visibility checks that reuse the existing live-sync/indexing path;
- separate status for committed operations whose indexing, notification, or explanation
  finalization needs recovery;
- cancellation boundaries;
- deadlines, expiry, and deterministic clocks in tests;
- bounded artifact sizes and event histories;
- redaction, encryption, retention, deletion, and subject-erasure policy;
- queries that cannot omit tenant scope;
- safe diagnostics without credentials, raw unrestricted evidence, or model secrets.

Delivery is at-least-once wherever retries or external delivery exist. AI Fabric provides
duplicate-safe coordination, stable idempotency references, current-state checks, and
duplicate-safe review/receipt processing. Effectively-once business effects depend on
application-handler idempotency and domain-state checks. An unknown outcome remains unresolved and
is never replayed blindly. AI Fabric must not claim exactly-once distributed execution.

Review dispatch is also at-least-once: dispatcher implementations must deduplicate by review-task
ID, and AI Fabric must treat a dispatch receipt as delivery evidence rather than approval. For a
write whose response is uncertain, effectively-once behavior comes from the application's
idempotency/status contract—not from replaying the handler and hoping for the best.

### 12.4 Minimal Durable State

Persist only what is required to resume, inspect, or prove the governed lifecycle:

- execution and invocation identity;
- plan ID, version, resolved hash, strategy, and current/ready/waiting/completed steps;
- specialist, prompt-profile, and schema IDs and versions, referenced Mode ID, and
  resolved-specialist-profile hash;
- trigger, initiator, subject, tenant, authority, and correlation references;
- optional conversation reference, frozen snapshot revision, dialogue-owner invocation ID, and
  per-invocation approved-view reference;
- parent/child delegation and handoff-successor relationships;
- status, `WAITING_FOR_INPUT`, `WAITING_FOR_CONFIRMATION`, `WAITING_FOR_REVIEW`,
  `WAITING_FOR_OUTCOME_REVIEW`, or reconciliation reason, attempts, budgets, deadlines, and
  cancellation;
- accepted and rejected typed coordination directives with safe reason codes;
- review-task policy/version binding, dispatch attempts/receipts, and reviewer decisions;
- action proposals, action invocation/idempotency references, and application-issued receipts;
- immutable receipt references separated from mutable action-finalization state;
- action effective outcome, affected domain revision, revision-visibility state, reconciliation
  attempts, recovery warnings, and any successor corrective/compensating action link;
- safe evidence references, source versions, hashes, and lineage;
- typed pending-input request, response contract, requesting invocation, delivery state when
  durable, and resume correlation;
- typed result references and aggregation state;
- sanitized selection, policy, and failure events.

Raw prompts, complete documents, complete conversation copies, credentials, unrestricted tool
output, and sensitive model content must not be stored by default. Prefer snapshot and projected-
view references over duplicating transcripts inside every invocation.

## 13. Agentic AI Action Resolver Reference Proof

The current Account Resolver is the best source and comparison baseline because its `resolver` Mode
already exercises bounded iterative information gathering. Its configuration demonstrates an
approved read action, RAG cooperation, vector restrictions, and strict iteration/action limits:

- [`ai-fabric-account-resolver/application.yml`](https://github.com/Loom-AI-Labs/ai-fabric-framework/blob/main/examples/real-apps/ai-fabric-account-resolver/src/main/resources/application.yml)

Do not modify or redeploy that application as the agentic proof. Copy it to:

```text
examples/real-apps/agentic-ai-action-resolver
```

The copied app becomes an independent real app and deployment with its own artifact identity,
configuration, Dockerfile, health/build metadata, tests, seed data, sessions, backend URL, and AI
Fabric website route. The original `ai-fabric-account-resolver` remains available and unchanged for
behavioral and compatibility comparison.

The specialist in the new app may retain the domain identity `account-resolver@1`. The app name
identifies the separately deployable product proof; the specialist ID identifies the reusable
domain capability.

### Stage A — Formalize The Copied Single Specialist

```text
User or application request
        ↓
chat/API adapter → AIExecutionGateway
        ↓
trusted selection of account-resolver
        ↓
SpecialistDefinition → resolver Mode
        ↓
resolve specialist scope ∩ current Mode restrictions ∩ application authority
        ↓
existing RAGOrchestrator
        ↓
specialist-scoped get_account_profile read loop + approved evidence
        ↓
typed AccountResolutionResult
        ↓
one response through the root dialogue owner when interactive
```

Implement:

- place the new integration only in `agentic-ai-action-resolver`;
- explicit `account-resolver` specialist identity and version;
- reference to the current `resolver` Mode;
- objective and prompt-profile ownership in `SpecialistDefinition`;
- typed `AccountResolutionRequest` and `AccountResolutionResult` in
  `SpecialistDefinition`;
- evidence scope, direct/planner READ scopes, proposable WRITE scope, `BOUNDED_ITERATIVE`
  behavior, human-control references, and specialist limits in `SpecialistDefinition`;
- the current `resolver` Mode left unchanged, including its existing planner READ allowlist and
  limits;
- one effective-profile resolver proving the specialist scope is intersected with the existing Mode
  and application authority;
- finish reason and enforced specialist/execution limits;
- conversation binding and root-specialist dialogue ownership when invoked interactively;
- no dialogue owner when invoked as an application call;
- no mandatory new persistence.

### Stage B — Fixed `AccountResolutionPlan`

First represent Stage A as an explicit one-step fixed plan to prove registration, version pinning,
typed mapping, and deterministic coordinator behavior without changing the existing intelligence.
This is not yet a multi-agent claim.

Only if evaluation shows that separate contexts improve quality or governance, expand the fixed plan:

```text
AccountResolutionPlan — FIXED
  1. Identity Matcher
  2. Payment Evidence Checker
  3. Policy Checker
  4. Deterministic AccountResolutionAggregator
          ↓
typed result → return or application-owned governed action
```

Each step owns an independent goal and typed result. Ordinary security, retrieval, privacy, or
pipeline stages must not be renamed as specialists merely to make the diagram busier. Durable
review is added in Stage C. If this plan is invoked interactively, one dialogue-capable root
specialist owns the external conversation. Identity Matcher, Payment Evidence Checker, and Policy
Checker are worker invocations over separate approved views; they never append to the conversation.

### Stage C — Proactive Account Resolution Queue

```text
Account or payment domain event
        ↓
trusted trigger adapter → AIExecutionGateway
        ↓
deterministic account-resolution plan selection
        ↓
durable AIExecution with no conversation or dialogue owner
        ↓
deterministic coordinator + existing specialist orchestration
        ↓
structured recommendation
        ↓
persist ReviewTask when uncertain or sensitive
        ↓
registered ReviewTaskDispatcher delivers safe task reference
        ↓
authorized decision returns through ReviewDecisionGateway
        ↓
GovernedActionExecutionService invokes registered resolution handler
        ↓
application-owned transaction returns ActionReceipt, or finalization records unknown
        ↓
ActionOutcomeFinalization + existing live-sync visibility
```

Durability is introduced here because the event, retry, or review may cross request and process
boundaries—not because every specialist requires storage.

### Stage D — Optional Conversation Manager

Only if real conversations require dynamic clarification or a bounded choice between approved
specialists:

```text
ConversationManagerSpecialist as the one dialogue owner
  → AskUser
  → ContinueCurrent
  → InvokeSpecialist
  → CompleteExecution
  → RequestHumanReview
             ↓
deterministic validation by AIExecutionCoordinator
```

The manager sees only its approved conversation view, approved targets, and visible typed results.
It cannot alter the plan, invent or modify a specialist/Mode, widen authority, or continue beyond
plan/turn/budget limits. Worker specialists still return `NeedsUserInput` to the coordinator; the
manager asks the approved question and only the requesting branch resumes.

### Proof Acceptance Criteria

The reference proof is complete only when it demonstrates:

1. unauthorized specialist or Mode selection is rejected;
2. each invocation has one versioned specialist definition, one referenced Mode, and one resolved
   effective-profile hash;
3. no child gains a union of parent or sibling permissions;
4. evidence, READ actions, and WRITE proposals remain specialist-scoped and are narrowed by current
   Mode restrictions and application policy;
5. action filtering occurs at discovery and execution;
6. `SpecialistDefinition` is the complete readable declaration of the account-resolver agent in
   `agentic-ai-action-resolver` and owns the typed `AccountResolutionResult`;
7. the resolved plan pins its definition, specialist, prompt-profile, and schema versions plus the
   Mode ID and effective-profile hash;
8. the deterministic coordinator rejects an invalid step, transition, or type mapping;
9. a non-chat event can invoke a specialist/plan without a conversation;
10. tenant, initiator, subject, and authority are represented correctly;
11. child context and evidence remain isolated when the optional fixed multi-step plan is enabled;
12. aggregation preserves evidence and disagreement;
13. when the optional Stage D supervisor is enabled, every directive is typed, plan-bounded,
    independently validated, and safely recorded;
14. a durable review task is version-bound and persisted before dispatch;
15. a registered dispatcher receives only a safe task envelope and cannot grant reviewer authority;
16. decisions return through `ReviewDecisionGateway`, are authorized against trusted reviewer
    context, and are idempotent;
17. stale evidence, expired review, changed domain revision, or changed policy blocks continuation;
18. AI Fabric invokes an approved registered handler only through
    `GovernedActionExecutionService`;
19. the application handler reauthorizes, validates, performs the transaction, enforces
    idempotency, and issues the authoritative receipt;
20. `COMMITTED`, `REJECTED`, and `FAILED_BEFORE_COMMIT` receipts follow their distinct terminal or
    declared-retry semantics;
21. a missing receipt creates an explicit `OUTCOME_UNKNOWN` finalization record and never causes a
    blind write retry;
22. a committed domain revision becomes visible through the existing live-sync path when a
    supported visibility barrier is required; no second index path is introduced;
23. a committed action remains committed when live-sync or later finalization needs recovery;
24. an application-issued receipt remains immutable during reconciliation and post-outcome review;
25. correction or compensation is a new governed action rather than a hidden post-action mutation;
26. duplicate events, dispatches, decisions, resumes, and receipts do not duplicate side effects;
27. provider, retrieval, selection, policy, plan, review, dispatch, and action failures remain
    visible;
28. logs and lifecycle events remain sanitized;
29. every request source enters through `AIExecutionGateway` and follows explicit or registered
    deterministic target resolution before any optional model-assisted selection;
30. an interactive Stage A turn has exactly one dialogue owner, while an application or event
    execution has none;
31. worker specialists cannot read or write the complete external conversation;
32. each sibling receives a distinct `ApprovedConversationView` derived from the same frozen turn
    snapshot and its own effective profile;
33. a worker's `NeedsUserInput` request is delivered through the dialogue owner or host input
    channel, and only the waiting branch resumes;
34. parallel results fan in through an explicit aggregator and create at most one external
    response;
35. a machine trigger does not invent a user, conversation, or dialogue owner.

## 14. Prioritized Delivery Roadmap And Release Gates

### P0 — Preserve Mode And Establish Specialist Ownership

- map Mode, position, orchestration, action, session, draft, confirmation, durable review, and
  action-outcome contracts;
- document the current bounded read-action loop as the single-agent foundation;
- correct guidance that calls ordinary pipeline modules “agents”;
- freeze the compatibility rule: Mode-only requests retain their current behavior;
- define `SpecialistDefinition` as the complete agent view, including objective, instructions,
  typed I/O, evidence scope, direct/planner READ actions, proposable WRITE actions, behavior,
  human-control references, limits, and delegation;
- add no Mode field unless it independently passes the shared-concern admission rule;
- map current planning behavior to `DIRECT`, `SINGLE_PASS`, and `BOUNDED_ITERATIVE` at the specialist
  layer without creating another loop;
- define `EffectiveCapabilitiesResolver` with separate legacy and specialist paths;
- verify specialist filtering in prompt construction, intent extraction, planner READ, direct READ,
  WRITE proposal, and final pre-execution paths;
- define the boundary in which AI Fabric owns governed handler invocation/finalization while the
  registered application handler owns business authorization, validation, transaction, side
  effect, idempotency, and authoritative outcome;
- define `ActionExecutionOutcome`, typed `ActionReceipt`, and the rule that uncertain writes are
  never retried blindly;
- define the interaction vocabulary: external `Conversation`, invocation-level `DialogueOwner`,
  immutable `ApprovedConversationView`, and typed `NeedsUserInput`;
- freeze the rule that a conversation is not an inter-specialist bus and its reference is not
  permission to read the transcript;
- define review SPI ownership: `ReviewTaskStore`, `ReviewTaskDispatcher`,
  `ReviewerAuthorizer`, and the framework-owned `ReviewDecisionGateway`;
- define resolved-specialist-profile hash/snapshot semantics without requiring Mode versioning;
- define typed finish and failure reasons;
- add regression tests proving the original Mode-only Account Resolver and other current flows are
  unchanged, plus a paired scenario suite comparing it with `agentic-ai-action-resolver`.

**Gate:** the current flow and ownership boundaries are accurately documented, no allowed action
path bypasses the resolved specialist scope or application policy, and no review adapter or action
receipt grants authority. Legacy Mode behavior is unchanged, and `BOUNDED_ITERATIVE` reuses the
existing iterative loop rather than creating a second one.

### P1 — One Specialist-Defined Agent

- add versioned `SpecialistDefinition<I,O>` and registry;
- add its evidence, action, behavior, human-control, limit, and delegation contracts;
- add registered prompt-profile references;
- add a lightweight logical `AIExecution` envelope that may remain in memory;
- add `SpecialistInvocation` identity;
- add typed input and result contracts to `SpecialistDefinition`;
- add trusted explicit and deterministic selection;
- add the small `AIExecutionGateway`, typed `ExecutionRequest`, and source-specific entry-point
  resolution;
- register delegation and handoff-target metadata without yet enabling dynamic transition
  execution;
- bind an interactive conversation and pending action to specialist/prompt/schema versions,
  referenced Mode ID, and resolved-profile hash;
- make every model-facing action catalogue and every action invocation use the same resolved
  specialist action sets;
- adapt the existing action registry behind `GovernedActionExecutionService`;
- add synchronous action-receipt validation and `AIExecutionResult` receipt references;
- reuse the existing live-sync path after committed application changes;
- run Agentic AI Action Resolver entirely through the existing orchestration engine;
- assign the root Agentic AI Action Resolver invocation as the sole dialogue owner for interactive
  use and assign no owner for application calls;
- add step, time, action, token/usage, and cost limits where measurable;
- keep the synchronous path storage-optional.

**Gate:** the separate `agentic-ai-action-resolver` application can invoke a bounded Account
Resolver specialist with no second orchestration path or privilege widening, and an approved
synchronous action produces one validated application-issued receipt without a competing indexing
path. Interactive use produces one response from one owner; programmatic use does not create a
fake conversation. The original Account Resolver still passes its unchanged baseline scenarios.

### P2 — Fixed Execution Plans And Deterministic Coordination

- add immutable `ExecutionPlanDefinition` with an implicit one-step compatibility path;
- add a deterministic `AIExecutionCoordinator`;
- add `InteractionPolicy` and enforce exactly one eligible dialogue owner for an interactive turn;
- freeze one conversation snapshot per turn and project a separate `ApprovedConversationView` for
  every worker invocation;
- add fixed sequential specialist steps, deterministic decision steps, and registered typed input
  mappings;
- add typed `NeedsUserInput`, branch-specific `WAITING_FOR_INPUT`, and resume through
  `AIExecutionGateway`;
- add deterministic transitions for committed, rejected, failed-before-commit, and unknown action
  outcomes;
- add deterministic aggregation and explicit plan/specialist/schema version plus
  effective-profile-hash pinning;
- prove the one-step Account Resolver plan first inside `agentic-ai-action-resolver`;
- expand to a fixed multi-specialist Account Resolution plan only if evaluation justifies the
  decomposition;
- keep the same existing `RAGOrchestrator` for every specialist step.

**Gate:** a fixed plan advances only through registered, type-compatible, independently authorized
steps, and produces the same or better measured outcome than the single-specialist baseline.
Worker specialists cannot access or append the full conversation, and one validated response is
returned through the dialogue owner.

### P3 — Channel-Neutral Durability And Human Review

- support application-service and Spring-event invocation;
- require trigger adapters to submit through `AIExecutionGateway` with a service/system initiator,
  deterministic target mapping, and no conversation by default;
- add in-memory and JDBC/JPA execution-state adapters;
- add step status, cancellation, result, retry, and safe lifecycle events;
- add `HumanReviewStep` and durable `ReviewTask` for approve, reject, correct, escalate, expire,
  resume, and reauthorization;
- add registered `ReviewTaskStore`, `ReviewTaskDispatcher`, and `ReviewerAuthorizer` SPIs plus the
  AI Fabric-owned `ReviewDecisionGateway`;
- persist review tasks before safe dispatch, record delivery receipts separately from decisions,
  and make dispatch/decision handling idempotent;
- add `OUTCOME_REVIEW`, unknown-outcome reconciliation, and optional domain-revision visibility
  barriers through the existing live-sync path;
- require correction and compensation to create new governed action proposals;
- support `WAITING_FOR_INPUT` and resume through the same pinned execution;
- expose durable typed input requests through the host application without treating missing facts
  as reviewer authority or inventing a chat;
- implement direct specialist-requested delegation and controlled handoff with independent target
  authorization, typed inputs, `parentInvocationId`/predecessor links, and duplicate-safe resume;
- test that delegation targets, resolved-plan targets, specialist scope, current Mode restrictions,
  and current application authority are all enforced;
- build the proactive Account Resolution Queue;
- prove idempotency, retry, restart, review resume, and conversation-free invocation.

**Gate:** an event-triggered execution survives restart and produces an application-issued receipt,
an explicit unknown/reconciliation state, an authorized review task persisted before notification,
or stable failure evidence. Review decisions remain version-safe, an uncertain write is reconciled
without blind replay, and dynamic delegation/handoff cannot bypass the resolved plan or widen
authority.

### P4 — Optional Conversation Manager And Supervised Plans

- add a narrowly scoped `ConversationManagerSpecialist` with its own complete definition and a
  suitable existing Mode;
- allow it to become the sole dialogue owner only for plans that genuinely need conversational
  routing or synthesis;
- add typed `CoordinationDirective`;
- add `SUPERVISED_BOUNDED` plan strategy and an approved transition matrix;
- validate every proposed target, transition, schema, authority, and budget;
- add maximum manager turns, repeated-question/stall detection, and deterministic termination;
- record accepted and rejected directives safely;
- use the manager only for genuine ambiguity, clarification, handoff, or narrative synthesis.

**Gate:** the manager cannot escape the resolved plan, invent or widen a specialist definition or
effective profile, or continue beyond its limits. It cannot turn workers into shared-chat
participants, and fixed plans remain fully functional without it.

### P5 — Bounded Parallel Expansion

- add parallel step groups only for independent typed work;
- freeze one turn snapshot, project distinct per-specialist views, and prohibit every worker from
  appending to the conversation;
- enforce aggregate child, token, cost, timeout, and cancellation budgets;
- define partial-failure, `ALL_REQUIRED` first-release fan-in, consolidated input-request, and
  single-response policy;
- allow parallel safe reads but collect, conflict-check, and govern WRITE proposals after fan-in;
- prove context isolation and restart behavior;
- compare latency, cost, and quality against sequential execution.

**Gate:** parallel execution provides measured value without weakening isolation, cancellation,
provenance, single-dialogue ownership, duplicate-safe coordination, or host-enforced action
idempotency.

### Later

- optional LLM-assisted selection for ambiguous requests;
- broader event, schedule, file, batch, and external delivery adapters;
- established workflow-system adapters for complex long-running coordination;
- additional specialist blueprints only when they represent independent goals and contexts;
- broader evaluation, replay, and observability integration.

Do not generalize the typed plan contract into a graph language, hosted service, or open-ended agent
network before independent adopter requirements prove the need.

## 15. Security, Ownership, And Non-Goals

### 15.1 Non-Negotiable Invariants

- The application creates trusted identity, tenant, subject, and authority context.
- Specialist, execution-plan, prompt-profile, or Mode selection never grants application
  authority.
- `SpecialistDefinition` is the canonical declaration of one agent's requested capabilities.
- Each invocation has one specialist definition, one referenced Mode, and one resolved effective
  profile.
- Every source enters through `AIExecutionGateway`; channel and trigger adapters cannot bypass
  target resolution, context construction, or coordination.
- An interactive execution turn has exactly one dialogue-owning invocation; a non-interactive
  execution has none.
- Dialogue ownership is an execution role and never enlarges specialist evidence, action, or
  application authority.
- A conversation reference is correlation, not authorization to read the transcript.
- Worker specialists receive only immutable, independently projected conversation views and cannot
  append to the external conversation.
- Parallel siblings use the same frozen turn revision but retain separate typed inputs, views,
  working state, and budgets.
- Only the dialogue owner or host interaction adapter emits the one validated external response or
  question.
- Typed missing input resumes only the waiting invocation; it does not automatically become a
  human-review decision.
- Specialist declarations never bypass existing Mode restrictions, registered metadata, or current
  application authority.
- An execution plan orders work but grants no authority; every step is independently authorized.
- During dynamic delegation or handoff, a source specialist cannot target another specialist
  outside its declared delegation policy; the target is independently reauthorized and does not
  inherit or union parent/sibling capabilities.
- No privilege union occurs across specialists or modes.
- Evidence visibility is enforced before retrieval and at response boundaries.
- Specialist-scoped READ and WRITE actions are filtered before model exposure and before
  execution.
- Application developers define and register action implementations and metadata; models,
  specialists, plans, and review adapters cannot create actions.
- WRITE actions remain proposals until confirmation/review and application validation succeed.
- AI Fabric invokes approved WRITE actions only through `GovernedActionExecutionService` and the
  existing registered action handler.
- Specialists never mutate domain state outside application-owned services.
- Only the registered application handler may assert that a business transaction committed.
- Application-issued receipts are immutable; reconciliation and visibility progress belongs to the
  separate action-finalization record.
- `OUTCOME_UNKNOWN` never authorizes blind replay; reconciliation uses application idempotency or
  status contracts.
- A committed action is not reclassified as failed because live-sync, notification, or explanation
  finalization later fails.
- Outcome visibility reuses AI Fabric's existing live-sync/indexing contracts and never introduces
  a direct post-action index path.
- Compensation or correction is a new governed action, never a generic framework rollback.
- Current authority and source versions are checked again after a wait.
- A durable review task is persisted and version-bound before dispatch.
- A review dispatcher transports only a safe application-approved task envelope; delivery grants
  no reviewer authority and is not a decision.
- Review decisions enter through `ReviewDecisionGateway` with trusted reviewer context and are
  reauthorized before continuation.
- Confidence may influence routing or review; it never grants mutation authority.
- Machine-triggered work does not invent a human identity.
- Machine-triggered work does not invent a conversation or dialogue owner.
- The conversation manager may propose only typed plan-permitted directives; the coordinator
  remains the final state-transition authority.
- Final results preserve evidence and action provenance.
- Sensitive content is not persisted or logged by default.

### 15.2 Explicitly Rejected Designs

Do not:

- create a second agent engine beside `RAGOrchestrator`;
- treat `Mode` and `SpecialistDefinition` as identical concepts;
- split one agent's new evidence/action/behavior definition across Mode and
  `SpecialistDefinition`;
- expand Mode with agent-specific action lists, evidence, prompts, outputs, delegation, or budgets;
- remove or reinterpret existing Mode settings merely to introduce specialists;
- create a broad Mode named `agentic` instead of putting bounded execution behavior in the
  specialist;
- let an untrusted request provide an arbitrary specialist prompt/profile;
- let a client or model choose any configured Mode;
- merge multiple modes by unioning their privileges;
- change a running specialist or resolved effective profile in place;
- embed an untyped specialist chain inside `SpecialistDefinition`;
- treat a fixed plan transition as if one specialist dynamically delegated to the next;
- let an LLM generate an unrestricted executable plan;
- embed LLM reasoning directly inside the deterministic coordinator;
- share complete transcripts or unrestricted evidence among specialists;
- use a conversation as an agent-to-agent message bus or permit several specialists to append
  competing responses;
- treat a shared conversation ID as permission to read user history;
- give parallel specialists mutable shared conversation state;
- create a fake conversation or human user for an event, schedule, file, batch, or service trigger;
- let a worker specialist ask the external user directly instead of returning a typed input
  request;
- require every specialist invocation to own a chat;
- treat an output example as a validated contract;
- use an LLM aggregator as the authoritative business decision maker;
- call every retrieval, privacy, security, or policy stage an agent;
- build a generic low-code agent builder;
- describe Smart Brain as autonomous control of the application;
- create a new identity provider, event broker, vector database, or general workflow system;
- hard-code email, Teams, Slack, or another review channel into the core;
- let an LLM or untrusted request select a review dispatcher, recipient, reviewer, or callback;
- dispatch a durable review request before its version-bound task is persisted;
- treat successful review dispatch as human approval;
- let a dispatcher authorize a reviewer, decide a task, or resume execution directly;
- let a review adapter call a domain action directly and bypass execution coordination;
- add an unrestricted post-action hook that can mutate application state;
- let model text or transport status assert that a business transaction committed;
- blindly retry an uncertain WRITE action or perform a generic automatic rollback;
- mutate an application-issued receipt during reconciliation or post-outcome review;
- create a direct post-action indexing path beside the existing live-sync contracts;
- persist every synchronous interaction;
- replay write side effects by default;
- claim general multi-agent support before secure selection, isolation, budgets, aggregation,
  pause/resume, and reference proof exist.

### 15.3 Framework And Host-Application Ownership

| Concern | AI Fabric | Host application |
| --- | --- | --- |
| Existing Mode behavior | Preserves and applies current orchestration settings and shared restrictions | Configures permitted Modes and existing application behavior |
| Specialist definition | Registers the versioned complete agent view: objective, instructions, typed contract, evidence/action scopes, behavior, human control, limits, and delegation | Chooses domain purpose and approves definitions/policy references |
| Effective capability resolution | Intersects specialist declarations, current Mode restrictions, registries, and trusted application policy; applies plan budget/input constraints separately | Supplies current identity, tenant, subject, and authorization decisions |
| Execution-plan definition | Validates typed steps, transitions, versions, and budgets | Authors/approves the permitted business coordination |
| Execution submission | Exposes the narrow `AIExecutionGateway`, typed source request, resume, cancellation, status, and result facade | Supplies trusted input, target/mapping hint, trigger, identity, tenant, and authority context |
| External conversation | Binds an optional conversation to execution, freezes a turn revision, and enforces one dialogue owner | Owns the product channel, authenticated user interaction, and retention policy |
| Conversation projection | Applies specialist, Mode, privacy, tenant, authority, and plan-input restrictions to create immutable per-invocation views | Supplies source conversation data and application-specific privacy/subject policy |
| Dialogue ownership | Assigns and enforces one eligible invocation and routes typed input/results | Chooses whether the entry point is interactive and renders/delivers the approved response |
| Deterministic coordination | Advances execution state, isolates invocations, validates directives, routes input waits, aggregates results, and enforces limits | Supplies authority, infrastructure, and domain mappings |
| Optional supervisor | Invokes it as a restricted specialist and validates its typed proposal | Decides whether supervised coordination is permitted |
| Selection | Filters and validates candidates | Supplies trusted mapping, identity, tenant, and authority |
| Retrieval and reasoning | Coordinates approved evidence and model calls | Owns source data and access decisions |
| Action definition | Provides annotation/registry and typed invocation contracts | Defines metadata and registers the application handler |
| Action lifecycle | Discovers, filters, validates, confirms/reviews, invokes the registered handler, validates receipts, and finalizes the governed outcome | Registers the handler and configures action/review policy |
| Business action | Never implements domain behavior or asserts commit independently | Owns domain authorization, validation, transaction, side effect, idempotency, and authoritative receipt |
| Review lifecycle | Creates/version-binds tasks, persists before dispatch, validates decisions, and coordinates wait/resume | Configures review policy, reviewer eligibility, delivery adapters, and escalation |
| Review delivery | Defines `ReviewTaskDispatcher` SPI and sends a safe task envelope through the selected registered adapter | Implements email, Teams, Slack, portal, or workflow-system delivery and owns credentials |
| Reviewer decision | Exposes `ReviewDecisionGateway`, checks task state, and reauthorizes continuation | Establishes trusted reviewer identity and business authority |
| Outcome finalization | Validates/deduplicates receipts, records provenance, coordinates declared transitions, and optionally checks revision visibility | Publishes authoritative domain revision/events and provides reconciliation/compensation operations |
| Correction/compensation | Treats it as a fresh governed action with a new invocation and policy checks | Defines and performs the corrective business operation |
| Execution state | Stores optional coordination state | Chooses infrastructure and retention policy |
| Final business truth | Never owns it | Always owns it |

## 16. Alignment With Other Agentic Frameworks And Public Positioning

### 16.1 Binding Ecosystem Decision

Spring AI is the approved commodity AI infrastructure provider for this architecture wherever its
stable APIs are relevant and useful. Reuse it incrementally for model and embedding access,
structured output, tool-call transport, MCP, advisors, observability, document ETL, and evaluation.
Do not rebuild that plumbing inside the agentic layer.

AI Fabric remains responsible for the contracts that define its product:

- specialist definitions and effective capabilities;
- trusted initiator, subject, tenant, and authority;
- evidence projection and provenance;
- confirmation and durable review;
- governed WRITE execution and application-issued receipts;
- execution coordination, continuation, and visible failure;
- application-owned business truth.

Spring AI must remain infrastructure below these contracts. Its availability is helpful, not a
release dependency beyond the capabilities already selected for a phase. P0/P1 must proceed
without waiting for a future Spring AI agent runtime or redesigning AI Fabric around one.

LangChain4j, LangGraph, LangGraph4j, Embabel, Microsoft Agent Framework, and general durable
workflow engines form a **future technology watch list** only. They must not disturb the current
implementation:

- no new core dependency;
- no speculative public abstraction;
- no second provider/orchestration path;
- no delay to the independent `agentic-ai-action-resolver` proof;
- no adapter until a stable AI Fabric SPI and a measured product need exist.

After the native specialist and execution contracts are proven, an isolated evaluation may compare
a candidate against the same scenario corpus. Adoption is justified only if it measurably reduces
complexity or operational risk while preserving every AI Fabric authority, context-isolation,
action, receipt, and failure rule.

This architecture follows a sound industry distinction:

- Spring AI separates predefined workflows from agents that dynamically direct tool use, and
  recommends starting with the simplest pattern that meets the requirement.
- Spring AI tool calling also keeps actual tool execution in the application; the model requests a
  tool and arguments, while application code executes it.
- LangChain4j's currently experimental agentic module models agents as focused AI services and
  provides sequential, parallel, loop, and supervisor coordination patterns.
- LangChain recommends a single agent with dynamic context or tools when that is sufficient, adding
  multiple agents mainly for context management, parallel work, or distinct ownership.
- Microsoft separates an individual agent's model/tool choices from workflows that coordinate
  agents, functions, humans, checkpoints, and recovery.

AI Fabric should follow the same boundary deliberately: dynamic conversational judgment belongs in
a bounded specialist; plan enforcement, state transitions, authorization, budgets, and recovery
belong in deterministic Java coordination.

Primary references:

- [Spring AI — Building Effective Agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)
- [Spring AI — Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [LangChain4j — Agents and Agentic AI](https://docs.langchain4j.dev/tutorials/agents/)
- [LangChain — Multi-agent](https://docs.langchain.com/oss/python/langchain/multi-agent/index)
- [LangGraph — Overview](https://docs.langchain.com/oss/python/langgraph/overview)
- [LangGraph4j — Overview](https://langgraph4j.github.io/langgraph4j/)
- [Embabel Agent Framework](https://github.com/embabel/embabel-agent)
- [Microsoft Agent Framework — Workflows](https://learn.microsoft.com/en-us/agent-framework/workflows/)

AI Fabric's differentiating position is not “we also have agents.” It is the application-owned
boundary around live Java domain data, specialist-scoped evidence and actions, current application
authority, governed action execution, pluggable human control, typed application-issued action
receipts, and business-service truth.

Recommended architecture statement:

> AI Fabric is an application-owned AI enablement layer for Java and Spring Boot. It runs versioned
> specialist definitions through its existing orchestration and can compose them through typed,
> governed execution plans. Each specialist explicitly declares what it sees, what it may request
> or propose, how it behaves, and what it must return. AI Fabric coordinates review and
> registered-handler invocation while application services retain business authority and issue the
> final action outcome. Interactive plans expose one application-facing dialogue while isolated
> specialists work through typed, policy-projected context; non-interactive plans run without
> pretending to be chats.

Recommended agentic-extension statement:

> AI Fabric formalizes the bounded agent already present in its orchestration, introduces
> `SpecialistDefinition` as the complete agent view, preserves Mode as a reusable existing
> orchestration preset, and adds typed execution plans, deterministic coordination, human control
> through registered SPIs, governed action-outcome finalization, and an optional supervisor for
> ambiguous conversations.

Claim boundary:

> Until versioned plans, step authorization, typed transitions, isolation, aggregation, budgets,
> pause/resume, and reference proof ship, describe AI Fabric as specialist-ready with bounded
> orchestration—not as a general multi-agent framework.

## 17. What I Would Build Next

The highest-value next implementation is deliberately small:

1. **Preserve Mode behavior.** Freeze regression tests for Mode-only requests and avoid Mode schema
   changes in the first specialist release.
2. **Create the complete specialist contract.** Add identity, Mode reference, instructions, typed
   I/O, evidence scope, direct/planner READ actions, proposable WRITE actions, behavior,
   human-control references, limits, and delegation to `SpecialistDefinition`.
3. **Resolve effective capabilities once.** Build one resolver with a legacy path that reproduces
   current behavior and a specialist path used consistently by prompts, extraction, planning,
   direct actions, proposals, and final invocation checks.
4. **Formalize action ownership and outcomes.** Keep AI Fabric responsible for governed
   registered-handler invocation and finalization; keep domain authorization, transaction,
   idempotency, side effect, and the authoritative `ActionReceipt` inside the application.
5. **Normalize agentic behavior.** Map current read planning to `DIRECT`, `SINGLE_PASS`, and
   `BOUNDED_ITERATIVE` in the specialist; do not create another loop.
6. **Define the first real specialist.** In the new `agentic-ai-action-resolver` app, create
   `SpecialistDefinition<AccountResolutionRequest, AccountResolutionResult>` for
   `account-resolver`, including `get_account_profile`, its evidence, behavior, review, limits, and
   typed result, while referencing the unchanged `resolver` Mode.
7. **Reuse the existing flow.** Invoke it through `RAGOrchestrator` and
   `ReadActionResolutionService`; do not introduce another provider or action path.
8. **Create one canonical ingress.** Add `AIExecutionGateway` and a typed `ExecutionRequest` so
   chat, application calls, triggers, and resumes use the same governed boundary without pretending
   every source is a conversation.
9. **Make dialogue ownership explicit.** For an interactive Account Resolver turn, assign the root
   invocation as the sole owner; freeze one conversation snapshot and treat its reference as
   correlation rather than transcript permission.
10. **Complete one governed action lifecycle.** Adapt the current action registry behind
   `GovernedActionExecutionService`, distinguish committed/rejected/failed/unknown outcomes, reuse
   live sync, and prohibit hidden post-action mutation or blind write retry.
11. **Add secure selection and continuity.** Bind specialist/prompt/schema versions, referenced
   Mode ID, resolved-profile hash, optional conversation snapshot, and dialogue owner to the
   execution, draft, pending action, and result.
12. **Introduce a fixed plan.** Represent the single specialist as an implicit/explicit one-step
   plan, then prove one typed sequential plan through the deterministic coordinator.
13. **Project worker context.** Give each worker its own typed input and
   `ApprovedConversationView`; never expose or mutate a shared transcript.
14. **Add typed input continuation.** Let a worker return `NeedsUserInput`, route one approved
   question through the dialogue owner or host input channel, and resume only the waiting branch.
15. **Prove non-chat and human continuation.** Let one account/payment event run the plan with no
   conversation or dialogue owner, persist
   only required event/retry/review state, dispatch its safe task reference through a registered
   SPI, accept a trusted decision through `ReviewDecisionGateway`, and reauthorize on resume.
16. **Prove post-outcome recovery.** Exercise unknown action outcome, duplicate dispatch/decision,
    revision-visibility delay, and a separately governed corrective action.
17. **Measure before decomposing.** Add Identity Matcher, Payment Evidence Checker, and Policy
    Checker only if separate contexts measurably improve quality, latency, auditability, or team
    ownership.
18. **Add the conversation manager last.** Make it the one dialogue owner only if fixed plans and a
    root specialist cannot handle real conversational ambiguity; it still sees only approved
    summaries, targets, and typed results.
19. **Parallelize only after proof.** Add bounded fan-out/fan-in after sequential isolation,
    cancellation, budgets, and aggregation are reliable. Use one frozen snapshot, distinct
    projections, proposal-only writes, explicit fan-in, and one final response.

Final recommendation:

> Do not build a second agent engine or put an LLM inside the coordinator. Formalize the existing
> bounded loop, keep Mode backward-compatible, and make `SpecialistDefinition` the complete
> definition of an agent. Resolve that definition against current Mode restrictions and application
> authority, make action invocation, application-issued receipts, outcome finalization, and
> pluggable human review explicit, then prove one canonical gateway, one interactive dialogue
> owner, and a fixed typed execution plan through the separately deployable Agentic AI Action
> Resolver. Keep the current Account Resolver unchanged as the comparison baseline, keep worker
> specialists out of the shared conversation, allow non-chat work to remain conversation-free, and
> add an optional governed conversation manager only where dynamic specialist choice creates
> measurable value.
