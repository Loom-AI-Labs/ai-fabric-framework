# Plan: Read-Only Action Resolver and Thinker Modes

Status: proposed framework implementation plan (2026-04-22)

Update (2026-04-25): the core read-action resolution capability now exists in code. The current platform supports bounded LLM planning over eligible read-only actions, optional iterative planning for `thinker`, action evidence collection, RAG cooperation, and final answer generation from gathered evidence. Treat older "missing" notes below as historical implementation-plan context; verify current behavior in:

- `ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/information/ReadActionResolutionService.java`
- `ai-infrastructure-module/ai-infrastructure-core/src/test/java/com/ai/infrastructure/intent/orchestration/information/ReadActionResolutionServiceTest.java`
- `ai-infrastructure-module/ai-infrastructure-core/src/test/java/com/ai/infrastructure/intent/orchestration/pipeline/steps/IntentHandlingStepReadActionResolutionTest.java`
- `ai-infrastructure-module/curated/ai-curated-commerce/src/main/resources/ai-curated/packs/commerce.yml`

This document defines how the framework should support a new capability where the LLM can plan and use **read-only actions** as live information tools, then answer the user using the returned evidence, optionally cooperating with the existing RAG flow.

This is a **framework capability plan**, not a product-specific note.

---

## 1) Executive Decision

We should add a new **read-action-assisted information resolution** capability to the framework.

This capability should support app/product-facing modes such as:

- `resolver_assistant`
- `thinker`

But the framework feature itself should stay generic and mode-agnostic.

### Critical correction

The LLM should **not** be treated as the source of truth for:

- which actions exist
- whether an action is safe
- whether an action is eligible for autonomous planning
- whether a result is authoritative enough to ground an answer

The correct split is:

- **LLM** = planner for which safe read-only actions may help answer the request
- **Framework** = authority for policy, validation, execution, budgets, fallback, and final safety

This keeps the capability aligned with the framework philosophy:

- intelligence is respected
- server policy remains authoritative
- security stays fail-closed

---

## 2) Code-Validated Current State

The framework already has most of the primitives needed for this feature.

### 2.1 What is already real

#### Server-owned modes and capabilities

The framework already resolves server-authoritative orchestration modes through:

- `OrchestrationPolicyResolutionStep`
- `OrchestrationProperties`
- `OrchestrationPolicy`

Current policy already supports:

- `actionsEnabled`
- `retrievalEnabled`
- `deepRetrievalEnabled`
- `actionsPreferred`
- retrieval allowlists and vector-space requirements
- mode-specific RAG budgets

That is the right extension point for resolver/thinker modes.

#### Read/write action semantics already exist

The action contract already exposes:

- `accessMode` (`READ`, `READ_WRITE`, `WRITE_ONLY`)
- `groundingEligible`
- `sideEffectLevel`
- `resultPresentationHint`
- parameter schemas and required parameters

This exists for:

- annotation-based handlers via `AIActionRegistry`
- connector actions via `ConnectorActionMetadataMapper`

#### The extractor is already told to prefer read-only actions

The current managed extraction prompts already instruct the model to prefer read-only actions when they can answer factual questions from live systems.

Deployment prompt defaults already reinforce this in:

- `default-prompt-config.json`
- `commerce-prompt-config.json`
- `support-prompt-config.json`

So the system already has the beginnings of “use live read tools when appropriate”.

#### Post-action generation already exists

The framework already supports:

- action execution
- optional LLM generation over handler-provided facts

This is implemented through:

- `IntentHandlingStep`
- `AIActionHandler.buildPostActionLlmFacts(...)`
- post-action generation config and prompts

This is a useful precedent for answer generation grounded in action results.

#### READ → RAG fallback already exists

There is already a bounded helper-tool pattern:

- if a READ action returns an empty successful payload
- the orchestrator can fall back to RAG

This proves the framework already conceptually supports:

- READ actions as information tools
- cooperation between actions and retrieval

#### Deep retrieval modes already exist

The framework already ships examples like:

- `navigator`
- `navigator_deep`
- `executor`
- `cart_assistant`

So “mode as a policy bundle” is already established.

### 2.2 Historical Gap Before Implementation

The items in this section describe the pre-implementation gap that existed before the current
`ReadActionResolutionService` and curated `thinker` mode were added. They are retained as
historical design context, not as current code status.

Previously missing:

- no dedicated mode/capability for “plan safe read actions to answer this question”
- no LLM planner contract that chooses a bounded set of read-only actions for an informational request
- no iterative plan/observe/replan loop
- no action-result evidence bundle that works like RAG context
- no first-class hybrid strategy for:
  - read actions only
  - read actions then RAG
  - read actions and RAG in parallel
- no explicit server-side allowlist for “planner-eligible” read actions
- no dedicated observability for read-action-assisted answering

### 2.3 Important limitations in the current design

#### `orchestrationStrategy` is not enough

Current extractor output uses:

- `DIRECT_ACTION`
- `RETRIEVE_AND_GENERATE`
- `ADMIT_UNKNOWN`

That field is currently diagnostic and derived from intents.

It is **not** a sufficient contract for a planner loop.

We should **not** overload it to mean:

- action planning
- tool execution graph
- hybrid action + RAG strategy

#### `IntentHandlingStep` already does too much

`IntentHandlingStep` already owns:

- action execution
- information handling
- RAG handling
- confirmation behavior
- post-action generation
- READ → RAG fallback

We should not make it the only place that knows the new planner behavior.

The new capability should be factored into dedicated services/strategies.

---

## 3) Capability Definition

### 3.1 Core capability name

Use a generic internal capability name such as:

- `read_action_resolution`

This capability means:

- the request is informational
- the framework may let the LLM choose one or more **allowlisted read-only actions**
- the framework executes them under server policy
- the framework may combine their evidence with RAG
- the framework then generates the user-facing answer from bounded evidence

### 3.2 Recommended public mode labels

Recommended mode labels:

- `resolver_assistant`
  - shallow or single-pass live read resolution
  - best for production “assistant with live system reads”
- `thinker`
  - deeper bounded reasoning posture
  - may allow iterative read action planning and/or parallel RAG

### 3.3 Meaning of the modes

#### `resolver_assistant`

Default behavior:

- informational request
- planner may choose a small set of read-only actions
- framework executes them
- framework may run RAG if action evidence is insufficient
- final answer is generated from action evidence plus optional RAG evidence

#### `thinker`

Default behavior:

- same baseline as `resolver_assistant`
- larger budgets
- allows multiple bounded planner iterations
- may allow parallel action + RAG execution
- may use deeper response generation budgets

Important:

- `thinker` is **not** “unsafe autonomous agent mode”
- it remains read-only and bounded

---

## 4) Design Principles

### 4.1 Read-only means truly read-only

Planner-driven execution must only allow actions with:

- `accessMode = READ`

Do **not** include:

- `READ_WRITE`
- `WRITE_ONLY`

even if the action looks harmless.

### 4.2 Planner eligibility must be explicit

Not every READ action should be planner-callable.

Examples of READ actions that may still be poor candidates:

- expensive exploratory queries
- actions with broad data leakage risk
- actions that are technically read-only but operationally confusing

So we need a new explicit action metadata field such as:

- `plannerEligible` or `readResolutionEligible`

Default:

- `false`

That rule is important. It keeps the feature fail-closed.

### 4.3 Framework owns budgets and stop conditions

The LLM must not be able to run unbounded tool loops.

The server must own:

- max planner iterations
- max actions per iteration
- max total actions
- max parallel actions
- max evidence chars returned to planner/generator
- total time budget

### 4.4 No chain-of-thought persistence

Store:

- planner decisions
- action names
- params
- result summaries
- fallback reasons

Do **not** store:

- raw hidden chain-of-thought
- free-form internal reasoning dumps

### 4.5 RAG remains first-class

This feature should cooperate with RAG, not replace it.

Some questions are best answered by:

- indexed knowledge only
- live read actions only
- both together

The framework should support all three paths under policy.

---

## 5) Proposed Architecture

### 5.1 New policy surface

Extend the server-authoritative policy model with a dedicated read-action resolution section.

Recommended addition:

- `OrchestrationPolicy.ReadActionResolutionPolicy`

Suggested fields:

- `enabled`
- `planningMode`
  - `OFF`
  - `SINGLE_PASS`
  - `ITERATIVE`
- `allowedReadActions`
- `requireAllowlist`
- `maxIterations`
- `maxActionsPerIteration`
- `maxTotalActions`
- `maxParallelActions`
- `maxPlannerContextChars`
- `maxActionEvidenceCharsPerAction`
- `ragCooperationMode`
  - `NONE`
  - `RAG_IF_ACTIONS_INSUFFICIENT`
  - `PARALLEL_ACTIONS_AND_RAG`
- `requireGroundingEligible`

This should be added under:

- `OrchestrationProperties.ModeOverrides`
- `OrchestrationPolicy`

Do **not** overload `RagBudgets` for this.

### 5.2 New action metadata flag

Extend action metadata with a new planner eligibility field for:

- `@AIAction`
- connector action catalogs
- DB-backed registered connector actions

Recommended field:

- `readResolutionEligible`

Optional follow-up fields:

- `readResolutionDescription`
- `costClass`

But only `readResolutionEligible` is required for Phase 1.

### 5.3 New planner service

Introduce a dedicated service such as:

- `ReadActionResolutionService`

Responsibilities:

1. decide whether the request qualifies for read-action planning
2. build the planner prompt/context
3. ask the LLM for a bounded read-action plan
4. validate the proposed actions against:
   - access mode
   - planner eligibility
   - allowlist
   - auth context
   - parameter contract
5. execute the approved read actions
6. optionally replan or run RAG
7. hand a normalized evidence bundle to answer generation

### 5.4 New planner output contract

Do not ask the existing intent extractor to emit a multi-step tool plan.

Instead use a dedicated planner prompt/output schema.

Recommended planner output shape:

```json
{
  "decision": "ANSWER_FROM_CONTEXT | EXECUTE_READ_ACTIONS | EXECUTE_READ_ACTIONS_AND_RAG | USE_RAG_ONLY",
  "actions": [
    {
      "name": "action_name",
      "params": {},
      "priority": 1
    }
  ],
  "needsMoreSteps": false,
  "generationInstructions": "optional bounded instruction"
}
```

For iterative planning, a later pass can also emit:

- `continuePlanning`
- `missingEvidenceReason`
- `suggestedRagVectorSpaces`

### 5.5 New evidence bundle

Introduce a normalized evidence bundle for generation:

- `ReadActionEvidenceBundle`

Contents:

- executed action summaries
- sanitized action payload excerpts
- action metadata
- execution timing and status
- optional merged RAG context summary

This bundle becomes the authoritative input to final answer generation.

### 5.6 RAG cooperation

Support these cooperation paths:

#### Path A: actions only

Use when live read actions fully answer the question.

#### Path B: actions then RAG if needed

Use when live action results are partial and indexed context can fill the gap.

This should be the default for `resolver_assistant`.

#### Path C: actions and RAG in parallel

Use when latency and coverage justify it, or when `thinker` mode is explicitly selected.

This should be bounded and opt-in.

### 5.7 Pipeline placement

Recommended implementation approach:

- keep intent extraction as classification/contract extraction
- add a dedicated read-action resolution strategy invoked from the INFORMATION path

Implementation advice:

- do **not** keep adding large special-case blocks to `IntentHandlingStep`
- extract a dedicated information-resolution strategy layer, for example:
  - `InformationResolutionService`
  - `ReadActionResolutionService`
  - `RagInformationResolutionService`

Then `IntentHandlingStep` chooses the strategy, instead of owning all logic directly.

---

## 6) Request Qualification Rules

A request should qualify for read-action resolution only when:

- intent type is `INFORMATION`
- actions are enabled by policy
- read-action resolution is enabled by policy
- at least one eligible read-only action is available
- the request is not already fully answerable from authoritative pinned context

Optional heuristics:

- explicit mode is `resolver_assistant` or `thinker`
- action selection prompt or deployment prompt strongly prefers live read actions
- query category is known to benefit from live reads:
  - orders
  - account status
  - policies from live systems
  - relationship query style lookups

Current implementation note:

- The LLM planner chooses which eligible read actions to execute.
- Runtime policy constrains eligibility, access mode, grounding eligibility, budgets, and allowlists.
- Application code must not replace a planner-selected read action with a different action based on text matching.
- Action handlers may still perform deterministic lookup normalization inside the selected action, such as mapping "return policy" to the Shopify refund policy record.

Requests that should **not** use this path:

- explicit mutating requests
- clarification-only turns
- confirmation turns
- trivial no-data conversational turns

---

## 7) Security Model

### 7.1 Hard rules

The framework must enforce:

- planner may only propose actions with `accessMode = READ`
- action must also be `readResolutionEligible = true`
- anonymous access still respects existing `anonymousAllowed`
- all action params must pass deterministic validation
- authz checks still run through existing handler validation

### 7.2 No hidden privilege escalation

The planner must never be able to:

- call write actions “just once”
- bypass confirmation
- widen vector-space or entity permissions
- bypass connector-side auth rules

### 7.3 Safe degradation

If planner output is invalid:

- reject the invalid actions
- downgrade to safe alternatives:
  - use RAG only
  - ask for clarification
  - answer from available evidence

### 7.4 PII and evidence controls

Generation should only see:

- sanitized action evidence
- bounded excerpts
- handler-approved facts where available

Do not auto-dump raw action payloads into LLM context.

---

## 8) Implementation Phases

### Phase 1: Framework foundations

Ship:

- policy model for read-action resolution
- planner eligibility flag in action metadata
- dedicated planner prompt family
- single-pass planner service
- actions-then-RAG-if-needed cooperation mode
- normalized read-action evidence bundle
- structured metadata/debug output

Do not ship yet:

- iterative replanning
- parallel action + RAG
- product-specific UI positioning tied to these modes

### Phase 2: Iterative thinker support

Ship:

- iterative planner loop with bounded stop conditions
- parallel action execution for independent read actions
- optional parallel action + RAG mode
- richer observability and debugging

### Phase 3: Curated packs and productization

Ship:

- curated mode examples:
  - `resolver_assistant`
  - `thinker`
- prompt overlays
- benchmark guidance
- release verification coverage

---

## 9) Recommended Defaults

### `resolver_assistant`

Recommended defaults:

- planning mode: `SINGLE_PASS`
- max iterations: `1`
- max actions per iteration: `2`
- max total actions: `2`
- max parallel actions: `1`
- rag cooperation: `RAG_IF_ACTIONS_INSUFFICIENT`
- require planner allowlist: `true`

### `thinker`

Recommended defaults:

- planning mode: `ITERATIVE`
- max iterations: `2`
- max actions per iteration: `3`
- max total actions: `4`
- max parallel actions: `2`
- rag cooperation: `PARALLEL_ACTIONS_AND_RAG`
- deep retrieval enabled: `true`

Important:

- these are capability defaults, not hardcoded core semantics
- apps can override them through normal mode configuration

---

## 10) Verification and Testing Plan

This capability should not ship without dedicated verification.

### Unit tests

Add tests for:

- planner output validation
- READ-only enforcement
- planner-eligibility enforcement
- invalid mode/action downgrade behavior
- evidence truncation and sanitization
- cooperation mode selection

### Integration tests

Add orchestration tests for:

- informational request answered by one read-only action
- informational request answered by multiple read-only actions
- actions then RAG fallback
- parallel actions + RAG
- invalid planner proposal downgraded safely
- anonymous denial remains enforced
- relationship query as planner-eligible read action

### Real API tests

Add realapi coverage for:

- connector-backed read actions
- retry-safe read behavior
- bounded hybrid action + RAG path

### Observability

Expose metadata such as:

- effective mode
- planner enabled/disabled reason
- proposed actions
- executed actions
- skipped/denied actions
- rag cooperation path
- total planner iterations
- total planner/action latency

Do not expose raw chain-of-thought.

---

## 11) Why This Is Better Than Ad-Hoc Tool Calling

This plan is intentionally different from naive agent/tool calling.

It keeps:

- server policy authoritative
- action contracts typed and validated
- write paths out of scope
- read actions bounded and auditable
- RAG as a first-class peer instead of an afterthought

That makes it appropriate for:

- enterprise products
- customer connectors
- hosted platform deployments

---

## 12) Recommendation

Implement this capability as a new **framework-level information resolution strategy**, not as:

- a Shopify-only feature
- a prompt-only hack
- a generic unrestricted tool loop
- an overload of the existing `orchestrationStrategy` string

Recommended first slice:

1. add planner eligibility metadata for read-only actions
2. add policy/model support for read-action resolution modes
3. implement `resolver_assistant` as single-pass `READ actions -> optional RAG -> generate`
4. add observability and tests
5. only then add `thinker` as bounded iterative mode

That is the cleanest production path and aligns with the current framework architecture.
