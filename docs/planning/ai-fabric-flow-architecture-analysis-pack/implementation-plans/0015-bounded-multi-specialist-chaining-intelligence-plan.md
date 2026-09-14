# Bounded Multi-Specialist Chaining Intelligence Plan

- **Status:** Implementation complete; verified `0.6.0` source release
  candidate; immutable publication and deployed-canary gates remain
- **Date:** 2026-09-13
- **Framework baseline:** AI Fabric `0.5.3`
- **Code reviewed at:** `291e01640d4e`
- **Prerequisite:** Plans `0009` through `0014`
- **Recommended release target:** `0.6.0`, only after every release gate passes
- **Primary reference proof:** `examples/real-apps/incident-investigation-room`

Implementation began after approval. Sections 1 through 16 retain the design
rationale and accepted boundary; sections 17 through 19 record implementation
and release-gate status. `0.6.0` must remain an unreleased candidate until Gate
E is independently satisfied.

## 1. Executive Decision

Build the next chaining capability as a **centralized, bounded manager loop**.
Do not implement recursive specialist-to-specialist chaining or a general graph
runtime.

The manager may:

- decide that no specialist is required;
- ask the user one bounded clarification question;
- invoke one approved specialist;
- invoke a small approved set of independent specialists in parallel;
- inspect only application-projected results from completed specialists;
- select another approved specialist when a previous result makes it relevant;
- produce one grounded final response; or
- request one terminal, explicitly permitted read-only handoff.

Every worker remains a leaf. A delegated worker or handoff successor still
cannot start another transition. The current one-level delegation and handoff
gateways therefore keep their existing security meaning.

This design closes the most useful competitive gap without turning AI Fabric
into an unrestricted agent network:

```text
authenticated application request
  -> one exact-version manager
  -> typed proposed directive
  -> deterministic Java validation
  -> zero, one, or bounded approved READ workers
  -> projected worker-result views
  -> bounded manager re-evaluation
  -> one grounded answer or explicit terminal handoff
```

The model proposes the next move. AI Fabric remains the transition authority.
The application remains the authority for identity, tenant, data, permissions,
business effects, and final system-of-record truth.

## 2. Why This Capability Is Worth Building

### 2.1 User problem

A normal user should not need to understand delegation, handoff, specialist
names, execution plans, or provider topology before describing a problem.

The current Incident Investigation developer controls make each primitive
observable, but they are not the intended product experience. A production UI
should accept a request such as:

> Checkout latency increased after this morning's deployment. Investigate the
> likely cause and tell me what evidence supports it.

The manager should decide whether it needs service health, change risk, both,
or neither. The UI should explain the validated decision after it happens. It
should not ask the user to choose an internal transition type.

### 2.2 Business problem

Real business cases frequently cross more than one expertise boundary, but not
every case requires every expert:

- an incident may require service-health and deployment-risk evidence;
- a claim may require policy and fraud analysis, but medical analysis only for
  some cases;
- a support request may require account, billing, or product expertise;
- a security case may require identity, access, and threat evidence;
- a deployment diagnosis may require configuration, release, health, and
  policy knowledge; and
- an enterprise assistant may span separately owned HR, IT, finance, or
  facilities capabilities.

A single oversized specialist weakens capability isolation and increases
prompt size. A fixed plan invokes unnecessary specialists when relevance is
request-dependent. Asking the user to select the specialist creates product
friction and exposes implementation detail.

Bounded manager-controlled chaining offers a fourth option:

- use model judgment only to select the next approved expertise;
- invoke each specialist with its own exact profile and authority;
- expose only projected results to the manager;
- stop under deterministic limits; and
- return an attributable answer with visible execution lineage.

### 2.3 Expected business value

The capability should be built only if the reference proof demonstrates:

- lower user routing friction than manual capability selection;
- better answer completeness than a single-specialist baseline on genuinely
  cross-domain cases;
- fewer unnecessary model calls than an always-run-all fixed plan;
- lower latency than sequential execution when independent specialists are
  selected for parallel execution;
- preserved tenant, identity, evidence, and action boundaries;
- an auditable explanation of which specialists were selected and why; and
- no hidden fallback when routing, a worker, aggregation, or persistence fails.

If measured product quality does not improve, keep the current direct and
fixed-plan paths and do not release the extra abstraction.

## 3. Where AI Fabric Stands Today

### 3.1 Capabilities already implemented

AI Fabric `0.5.3` already has the required lower-level pieces:

| Current contract | What it proves | Boundary retained by this plan |
| --- | --- | --- |
| `SpecialistDefinition` | Exact-version identity, instructions, execution profile, limits, delegation/handoff policy, and typed adapters | Every manager and worker remains independently registered and hashed |
| `SpecialistDelegationGateway` | One validated root result can invoke one declared read-only child | Remains singular and depth-one; it is not changed into recursive fan-out |
| `SpecialistHandoffGateway` | One validated predecessor can transfer to one declared read-only successor | Used only for an explicitly permitted terminal transition |
| `ConversationManagerGateway` | One frozen backend-owned conversation can produce `ASK_USER`, `INVOKE_SPECIALIST`, or `COMPLETE` | Reused as the conceptual and internal foundation |
| `ExecutionPlanDefinition` | Known specialist order is application-declared and typed | Remains the preferred path for known workflows |
| `ParallelPlanStep` | Known independent READ branches can run concurrently | Its pre-map, bounded-executor, deadline, and `ALL_REQUIRED` lessons are reused |
| `PlanResultAggregator` | Application code can deterministically aggregate approved typed outputs | Remains authoritative for fixed business workflows |
| `AIExecutionGateway` | Every specialist independently resolves policy, RAG, validation, provider, and authority | Remains the only worker invocation path |
| `ChatSessionService` integration | History is backend-owned and the UI sends only the latest message | No caller-provided history is introduced |
| Durable job, review, and receipt repositories | Protected payloads, optimistic state transitions, replay, recovery, and cleanup | Persistence patterns are reused rather than bypassed |

### 3.2 Current limits

The current delegation request and result each contain exactly one target.
Calling the gateway repeatedly from application code is technically possible
with distinct idempotency keys, but it is not one coordinated operation. It
has no shared child budget, result collection, group failure policy, durable
group replay, or manager synthesis.

The current conversation manager permits one manager model call and zero or
one worker invocation. Its approved implementation plan explicitly deferred
manager loops, parallel branches, and a second synthesis call.

The fixed-plan coordinator supports sequential and parallel composition, but
its topology is application-authored. It correctly does not pretend that a
fixed `A -> B` transition is model-directed delegation.

The one-level transition engine rejects any second transition initiated by a
delegated child or handoff successor. This restriction is intentional and must
remain in place during this plan.

## 4. Market Competitiveness

This comparison records the documented market position reviewed on
2026-09-13. It must be refreshed before implementation because external
frameworks change quickly.

| Framework | Documented chaining strength | Relevant gap or distinction for AI Fabric |
| --- | --- | --- |
| Spring AI | Chain, routing, parallelization, orchestrator-workers, and evaluator-optimizer patterns using Java model and structured-output APIs | Strong infrastructure and patterns, but applications still assemble most execution ownership and business governance |
| LangChain4j Agentic | Sequential, parallel, conditional, loop, supervisor, custom planner, shared agent scope, checkpointing, and recovery | Broader Java agentic topology and manager flexibility than AI Fabric today |
| LangGraph | Arbitrary state graphs, cycles, subgraphs, persistence, interrupts, fault recovery, streaming, and time-travel debugging | Considerably broader general orchestration runtime, primarily outside AI Fabric's Java/Spring application niche |
| OpenAI Agents SDK | LLM-driven handoffs, agents as tools, manager ownership, combined patterns, sessions, and tracing | Easier open-ended multi-agent composition, but provider/runtime specific and less opinionated about AI Fabric's application authority model |
| Semantic Kernel | Sequential, concurrent, handoff, group-chat, and Magentic orchestration | Broad pattern catalog, currently documented as experimental and unavailable in its Java SDK |

Primary references:

- [Spring AI: Building Effective Agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)
- [LangChain4j: Agents and Agentic AI](https://docs.langchain4j.dev/tutorials/agents/)
- [LangGraph overview](https://docs.langchain.com/oss/python/langgraph/overview)
- [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
- [OpenAI Agents SDK: Agent orchestration](https://openai.github.io/openai-agents-python/multi_agent/)
- [OpenAI Agents SDK: Handoffs](https://openai.github.io/openai-agents-python/handoffs/)
- [Semantic Kernel: Agent orchestration](https://learn.microsoft.com/en-us/semantic-kernel/frameworks/agent/agent-orchestration/)

### 4.1 Position AI Fabric should own

AI Fabric should not compete on the claim that it can create the largest or
most autonomous graph. Its stronger position is:

> Bounded multi-specialist intelligence for Spring Boot applications, where
> every transition is typed, exact-versioned, tenant-safe, evidence-scoped,
> replayable, and subordinate to application-owned business authority.

That position is valuable because it combines model-assisted routing with:

- backend-owned identity and tenant context;
- independent effective-capability resolution per specialist;
- exact target allowlists and immutable definition hashes;
- application-owned input mapping and safe result projection;
- evidence and conversation minimization;
- confirmation, review, action receipts, and reconciliation for writes;
- visible provider and validation failures; and
- Java/Spring deployment and test conventions.

### 4.2 Competitive gap this plan should close

The material gap is not unlimited recursion. It is the inability of one
approved manager to gather several specialist perspectives, adapt after a
result, and synthesize one answer within a bounded execution.

Closing that gap makes AI Fabric competitive for enterprise concierge,
investigation, triage, and cross-domain support products without duplicating
LangGraph or accepting the risk profile of an unrestricted agent loop.

## 5. Recommended Product Boundary

### 5.1 Name and semantics

The working capability name is **Bounded Specialist Chain**. Public API names
remain subject to an API review before implementation.

A bounded specialist chain has:

- one application-selected, exact-version manager;
- one immutable chain definition;
- one backend-owned request and trusted execution context;
- one frozen conversation revision for interactive work;
- a closed catalog of exact-version read-only targets;
- application-owned target input mappers;
- application-owned safe worker-result projectors;
- a small manager-decision limit;
- a small total-worker and parallel-worker limit;
- one execution deadline and cancellation scope;
- one idempotency and replay boundary;
- one safe decision trace; and
- one final external response or terminal handoff result.

### 5.2 Supported intelligent topologies

#### Adaptive sequential selection

```text
manager -> service health
manager sees approved health projection
manager -> change risk
manager sees approved change projection
manager -> complete
```

The second target is selected because of the first validated result. This is
the main intelligence gain over a fixed sequential plan.

#### Bounded parallel selection

```text
manager -> [service health, change risk]
          branches execute independently
manager sees both approved projections
manager -> complete
```

The manager may select parallel execution only for targets registered as
independent and parallel-eligible.

#### Clarification

```text
manager -> ask user one question
turn completes after persisting the validated question
next user turn starts a new bounded chain against backend history
```

#### Terminal handoff

```text
manager -> handoff to one declared successor
successor produces the terminal read-only relationship result
manager does not resume
```

The first terminal handoff does not transfer a live dialogue, pending action,
or write receipt. Those semantics require a separate durable ownership plan.

### 5.3 Explicit non-goals

- recursive specialist-to-specialist delegation;
- delegated workers selecting or invoking more workers;
- handoff chains such as `A -> B -> C`;
- arbitrary model-generated graph nodes or edges;
- cycles, open-ended reflection, or `while` loops without a static ceiling;
- group chat in which several specialists share one transcript;
- unrestricted shared working memory;
- dynamic specialist discovery from the complete registry;
- model-authored identity, tenant, scopes, actions, provider, Mode, prompts,
  deadlines, or budgets;
- parallel or chained WRITE execution;
- migration of pending actions or receipts between specialists;
- replacing fixed plans for known business processes;
- replacing Spring AI as the model, structured-output, and provider
  infrastructure layer; or
- introducing LangChain4j, LangGraph, or another second execution runtime.

## 6. Decision Rule: Direct, Plan, or Chain

| Situation | Correct AI Fabric path |
| --- | --- |
| One known specialist can answer | Direct `AIExecutionGateway` call |
| Several known steps always run in known order | Fixed sequential plan |
| Several known independent checks always run | Fixed parallel plan |
| One ambiguous conversational route | Current bounded conversation manager |
| Relevant specialists depend on request meaning or prior specialist results | Proposed bounded specialist chain |
| Open-ended research or arbitrary graph traversal | Out of scope; evaluate a dedicated graph runtime instead |

This rule prevents market pressure from turning every application flow into an
agent loop.

## 7. Typed Directive

Do not break the current `ConversationManagerDirective`. Introduce an additive
versioned contract for the new chain runtime.

Working shape:

```java
public record SpecialistChainDirective(
    SpecialistChainDirectiveType type,
    List<SpecialistChainTargetRequest> targets,
    String message,
    String reason,
    List<String> supportingResultIds
) {}

public enum SpecialistChainDirectiveType {
    ASK_USER,
    INVOKE_ONE,
    INVOKE_PARALLEL,
    HANDOFF,
    COMPLETE
}

public record SpecialistChainTargetRequest(
    String targetSpecialist,
    String objective
) {}
```

The JSON schema must encode exact target names as an enum assembled from the
registered chain definition. Runtime validation remains mandatory even when a
provider claims schema enforcement.

Directive invariants:

- `ASK_USER` requires one bounded external message and no target;
- `COMPLETE` requires one bounded external message and no target;
- `INVOKE_ONE` requires exactly one target and no external message;
- `INVOKE_PARALLEL` requires between two and the definition's parallel limit;
- `HANDOFF` requires exactly one target, no external message, and ends the
  manager loop;
- every target reference is exact `name@version`;
- every objective is short, non-authoritative, and safe to expose in a trace;
- `reason` is required, short, and treated as a routing explanation rather
  than hidden reasoning; and
- `COMPLETE` after worker execution must reference every approved projected
  result by exact ID through `supportingResultIds`;
- `ASK_USER`, invocation, and handoff directives cannot supply result IDs; and
- no directive field can carry trusted identity, tenant, permissions,
  credentials, arbitrary evidence, action arguments, or provider settings.

## 8. Approved Manager State

On each decision, the manager receives a new immutable projected view:

```text
current user message
bounded backend-owned conversation snapshot
approved application context values
approved target catalog
approved completed-worker result views
remaining decision, worker, parallel, time, and character budgets
```

The manager does not receive:

- raw worker prompts or provider responses;
- worker hidden reasoning;
- unrestricted worker evidence bodies;
- credentials or security tokens;
- unrestricted chat history;
- application entities outside the input adapter;
- another specialist's tool or action registry; or
- mutable shared memory.

Each `SpecialistChainTargetResultProjector` creates a bounded
`SpecialistChainResultView` containing only fields approved for manager
reasoning, for example:

```text
target specialist ID
worker invocation ID
safe typed summary fields
approved evidence reference IDs
result revision/hash
completion status and timestamp
```

The manager may reason over projected facts. It cannot convert a projected
summary into system-of-record truth or new authority.

## 9. Definition And Registry

Working public contracts:

- `SpecialistChainId`;
- `SpecialistChainDefinition<I>`;
- `SpecialistChainLimits`;
- `SpecialistChainTarget<I, TI, TO>`;
- `SpecialistChainTargetInputMapper<I, TI>`;
- `SpecialistChainTargetResultProjector<I, TO>`;
- `SpecialistChainDirective` and directive type;
- `SpecialistChainExecutionRequest<I>`;
- `SpecialistChainExecutionResult`;
- `SpecialistChainStepTrace`;
- `SpecialistChainFailure`;
- `SpecialistChainRegistry`; and
- `SpecialistChainGateway`.

The definition should contain:

```text
exact chain ID and version
exact manager specialist ID
application input type and input adapter
closed target registrations
separate delegation and terminal-handoff target sets
per-target parallel eligibility
chain limits
optional conversation support
```

Startup validation must prove:

1. the manager and every target are registered exact versions;
2. the manager output adapter matches `SpecialistChainDirective`;
3. the manager has no WRITE actions;
4. worker targets are READ-only, non-interactive, and conversation-isolated;
5. each delegation target is declared by both the manager specialist and the
   chain definition;
6. each handoff target is independently declared in the handoff policy;
7. input mappers and result projectors match target Java types;
8. parallel targets are explicitly marked independent;
9. all definition limits fit deployment ceilings;
10. target descriptions, mapper/projector IDs, specialist content hashes,
    limits, and policies contribute to the chain content hash; and
11. unknown fields, duplicate targets, self-targets, aliases, and unversioned
    references fail application startup.

Do not publish a YAML chain manifest in the first code slice. Prove the Java
contract and runtime first. A later strict manifest may reference registered
mapper/projector component IDs, but must not introduce ignored fields or
string-based reflective invocation.

## 10. Runtime Algorithm

### 10.1 Submission

1. The host authenticates the request and builds `TrustedExecutionContext`.
2. The host selects one registered chain definition. The caller cannot supply
   a manager, target catalog, Mode, prompt, or provider override.
3. The gateway computes request, access, and definition fingerprints.
4. The gateway creates or replays one chain execution under a stable
   idempotency key.
5. Interactive execution claims the conversation through the existing shared
   turn coordinator and freezes an approved history revision.

### 10.2 Manager decision loop

1. Build the approved manager state from application input and projected
   completed results.
2. Invoke the exact manager through the existing `AIExecutionGateway`.
3. Parse and validate the typed directive.
4. Check remaining deadline, decision count, worker count, and character
   budget.
5. Validate every proposed target against the exact chain and specialist
   policy intersection.
6. Apply one valid directive.
7. Checkpoint the manager invocation, directive, and any resulting worker
   outputs.
8. Repeat only after a successful worker directive and only while budget
   remains.

No code path invokes a model, RAG service, tool, or provider directly.

### 10.3 Single worker

`INVOKE_ONE` uses the existing one-level `SpecialistDelegationGateway` with:

- the current successful manager result as source;
- a target input produced by the registered application mapper;
- backend-owned trusted context;
- the chain deadline;
- a child idempotency key derived from chain ID, decision index, and target;
  and
- existing independent target authorization.

The projected worker result is added to the next manager view. The raw target
result is not automatically added to the conversation.

### 10.4 Parallel workers

`INVOKE_PARALLEL`:

- validates and maps every branch before starting any worker;
- reuses the existing bounded AI Fabric execution executor;
- calls the singular delegation gateway once per approved target;
- derives a stable idempotency key per branch;
- executes only targets marked parallel-eligible;
- starts no more than the definition and deployment ceilings;
- initially supports only `ALL_REQUIRED`;
- cancels outstanding work on deadline, interruption, or required-branch
  failure;
- checkpoints successful branch results in declared target order; and
- never silently reruns failed parallel work sequentially.

Because all first-release workers are READ-only, a late provider completion
cannot commit a business effect. Its result is discarded after cancellation
or terminal group failure.

### 10.5 LLM aggregation

After worker results are projected, the manager is invoked again. It may:

- request another relevant worker;
- request another independent parallel set;
- ask the user for missing information; or
- return `COMPLETE` with one grounded response.

This second or later manager call is the supported LLM aggregation path. The
final response validator must ensure that factual references are supported by
the approved application context, conversation projection, or worker result
views. A required worker failure cannot be converted into a successful answer.

For an authoritative typed business aggregate, continue using a fixed plan
and registered Java `PlanResultAggregator`. The first chain release is for
read-only intelligent selection and user-facing synthesis, not final business
truth.

### 10.6 Terminal handoff

`HANDOFF` is permitted only when:

- the target is in the chain's handoff set;
- the manager specialist's handoff policy allows it;
- no worker or previous successor is requesting the handoff;
- the target independently passes current authority; and
- the chain has not already performed a handoff.

The existing `SpecialistHandoffGateway` creates the terminal successor. The
manager does not resume and the successor cannot transition again.

## 11. Limits And Configuration

Add a disabled-by-default deployment section:

```yaml
ai:
  execution:
    specialist-chains:
      enabled: false
      max-active: 250
      max-duration: 2m
      max-manager-decisions: 4
      max-worker-invocations: 4
      max-parallel-workers: 3
      max-invocations-per-target: 1
      max-projected-result-characters: 12000
      durable-enabled: true
      allow-ephemeral: false
      initialize-schema: false
      lease-duration: PT2M
      recovery-interval: PT30S
      recovery-batch-size: 50
      max-attempts: 3
      cleanup-enabled: true
      retention: P30D
      encryption-secret: ${AI_SPECIALIST_CHAIN_ENCRYPTION_SECRET}
      fingerprint-secret: ${AI_SPECIALIST_CHAIN_FINGERPRINT_SECRET}
```

The implemented defaults are conservative and remain subject to load testing.
Chains are disabled by default. Enabling a chain without durable storage fails
closed unless the application explicitly acknowledges development-only state
with `allow-ephemeral=true`. Each chain definition may narrow deployment
ceilings but cannot widen them.

Required runtime rules:

- at least one manager decision remains available for final completion after
  the final worker invocation;
- the same target is invoked at most once unless the definition explicitly
  allows a higher bounded count in a later release;
- a repeated identical directive without new approved state fails with
  `CHAIN_NO_PROGRESS`;
- the earliest request, definition, specialist, and deployment deadline wins;
- cancellation propagates to active workers;
- manager and worker model-call counts are visible in diagnostics; and
- a limit failure is terminal and never produces a fabricated partial answer.

## 12. Persistence And Replay

### 12.1 First proof

The first application proof may use bounded process-local state only while the
feature is marked experimental and read-only. It must still provide exact
payload replay and conflict detection.

### 12.2 Release requirement

Durable checkpointing is required before the capability is described as
production-ready.

Do not repurpose `DurableExecutionRecord`, which represents one asynchronous
specialist job, and do not reuse `EphemeralPlanExecutionStore` as a public
dynamic-chain store.

Add a storage-neutral `SpecialistChainExecutionRepository` with an optional
JDBC implementation. Reuse the established protected-payload codec, access
fingerprints, optimistic compare-and-set, lease, retention, and cleanup
patterns.

The durable record should retain:

```text
chain execution ID
chain definition ID and content hash
manager specialist ID and content hash
access and request fingerprints
protected application input
protected approved chain state
status and next decision index
manager and worker lineage references
deadline, lease, attempt count, timestamps, and version
protected terminal result or safe failure
```

It must not persist raw credentials, unrestricted conversation history,
unprojected evidence, provider payloads, or hidden reasoning.

Recovery rules:

- never rerun a checkpointed successful worker;
- never reuse state if the chain or specialist content hash changed;
- reacquire current authorization before continuing;
- return the original result for an exact replay;
- reject changed work under the same scoped idempotency key;
- expose uncertain provider outcomes rather than assuming success; and
- clean terminal state only after the configured retention period.

No mandatory Spring Data JPA dependency should be added to the framework. The
repository remains an SPI, the JDBC adapter is the default production option,
and applications may provide a JPA-backed adapter if that matches their stack.

## 13. Security And Governance Invariants

1. User text grants no target, identity, tenant, scope, action, or evidence
   authority.
2. The host application selects the chain definition.
3. The manager sees only the chain's approved target catalog.
4. A target must pass manager policy, chain policy, registry, current
   authority, type, state, and remaining-budget checks.
5. Every worker resolves its own effective capabilities independently.
6. Parent and worker permissions are never unioned.
7. Worker conversation access remains disabled.
8. Worker evidence is exposed to the manager only through a registered
   projector.
9. Spoofable identity or tenant fields in model output are rejected or
   ignored, never trusted.
10. Workers are READ-only in the first release.
11. No child worker or successor can initiate another transition.
12. Parallel branches cannot observe sibling output.
13. Required branch failure fails the chain closed.
14. The final manager answer must pass normal output and grounding validation.
15. Logs and diagnostics contain safe lineage, hashes, reason codes, counts,
    and timings, not sensitive payloads.
16. No deterministic fallback hides an LLM, provider, mapper, projector,
    persistence, or validation failure.

WRITE proposals remain governed through the existing specialist action,
confirmation, review, receipt, and reconciliation lifecycle. A future plan
must define exact ownership before a manager chain may include a WRITE-capable
worker.

## 14. Failure Semantics

Required safe failures include:

| Failure | Required behavior |
| --- | --- |
| Invalid structured directive | `INVALID`; no worker starts |
| Unknown or unapproved target | `DENIED`; no target binding or model call |
| Repeated target/no progress | `CHAIN_NO_PROGRESS`; stop visibly |
| Decision or worker budget exceeded | `CHAIN_BUDGET_EXCEEDED`; no partial answer |
| Manager provider failure | `FAILED`; no deterministic route fallback |
| Worker denied or failed | Chain fails under `ALL_REQUIRED` with attributable safe failure |
| Mapper or projector failure | `INVALID` or `FAILED`; no raw worker result exposed |
| Grounding validation failure | `INVALID`; no generic answer substituted |
| Deadline | Cancel active work and return `DEADLINE_EXCEEDED` |
| Interruption/cancellation | Preserve interrupt, cancel children, return `CANCELLED` |
| Persistence uncertainty | Stop and expose retry-safe status; do not rerun blindly |
| Exact replay | Return original lineage and result with `replayed=true` |
| Changed replay payload | `IDEMPOTENCY_CONFLICT` |

Partial-success and best-effort fan-in are deferred. They need explicit rules
for what may be said when some evidence is unavailable.

## 15. Observability And Explainability

Expose a safe chain trace rather than model chain-of-thought:

```text
chain execution ID and definition hash
conversation snapshot revision when interactive
manager invocation ID per decision
directive type and safe reason
selected exact target IDs
sequential or parallel group relationship
worker invocation IDs and statuses
approved evidence reference IDs/counts
remaining budgets after each step
timestamps, duration, replay, and terminal status
safe failure reason
```

Metrics should include:

- active and completed chains;
- manager decisions per chain;
- workers selected per chain;
- parallel group size and duration;
- target-selection distribution;
- clarification, completion, denial, failure, timeout, and replay counts;
- manager and worker model-call counts;
- no-progress and budget-limit terminations; and
- direct, fixed-plan, and bounded-chain comparison measurements.

Trace data must distinguish application-generated test canaries from
LLM-proposed directives.

## 16. Reference Demo Plan

Upgrade the existing Incident Investigation Room. Do not replace or fork it.
Preserve the current deterministic plans and manual primitive proofs as
comparison tools.

### 16.1 Primary user experience

Add one prominent **Smart Investigation** path:

1. The user selects an incident scenario or writes a natural request.
2. The application supplies trusted tenant, incident, service, and candidate
   evidence boundaries.
3. The manager decides whether to complete, clarify, invoke one worker,
   invoke both approved workers, or request terminal handoff.
4. The UI renders the validated decision trace after execution.
5. The final answer identifies the consulted specialists and evidence
   references without displaying hidden reasoning.

Do not expose delegation or handoff as required user choices in the primary
flow.

### 16.2 Developer proof area

Keep the existing direct **Run Delegation** and **Run Handoff** controls under
a clearly named **Transition API Lab** or **Developer Proof** section.

Rename the second-hop result to:

> Application-generated one-level safety canary

This prevents users from believing that the LLM requested the prohibited
second transition.

### 16.3 Required live scenarios

1. **Single specialist:** a pure health request selects only service health.
2. **Adaptive sequence:** health evidence makes a change-risk consultation
   relevant, so the manager invokes it next.
3. **Parallel selection:** checkout degradation after a deployment selects
   service health and change risk together.
4. **No material change:** projected evidence leads to a grounded conclusion
   without inventing a deployment cause.
5. **Clarification:** an underspecified request produces one useful question.
6. **Complete without worker:** an unsupported or already-answerable request
   starts no specialist.
7. **Terminal handoff:** an intake-style request transfers to one declared
   read-only successor and the manager does not resume.
8. **Invented target:** invalid model output is denied with no fallback.
9. **Required branch failure:** one failed parallel worker prevents a final
   success answer.
10. **Replay:** an exact request returns the same manager/worker lineage and
    does not repeat provider calls.
11. **Restart recovery:** completed worker results survive restart and are not
    executed twice once durable mode is enabled.
12. **Trusted boundary attack:** spoofed tenant, incident, target, and evidence
    fields do not affect worker access or final output.

### 16.4 UI requirements

- natural-language primary input;
- no architecture choice required from the user;
- visible current execution status and cancellation;
- compact decision timeline with manager and specialist labels;
- separate sequential and parallel grouping;
- safe target-selection reason;
- evidence IDs and source types used by each worker;
- explicit failure cards with no substitute answer;
- replay and deployed-version indicators;
- responsive desktop/mobile presentation; and
- a developer panel that explains direct, plan, delegation, handoff, and
  bounded-chain differences.

## 17. Implementation Slices

### Slice 0: Demo truth correction, no framework change

**Status:** Complete. Preserved as a comparison path in the Incident demo.

- add a typed app-side manager output that can choose `COMPLETE`, `DELEGATE`,
  or `HANDOFF` from exact enums;
- validate the selected relation and target before calling the existing
  gateway;
- make the natural-language path primary;
- move manual transition controls into the developer proof area;
- label the artificial second hop as application-generated; and
- prove all routes with deterministic and real OpenAI tests.

This slice demonstrates smart relation selection but still invokes at most one
worker.

### Slice 1: Additive contracts and registry

**Status:** Complete with contract, registry, schema, and auto-configuration
tests.

- add the proposed chain IDs, directive, target, limits, definition, registry,
  request, result, trace, and failure contracts;
- add strict directive invariants;
- validate exact targets, policies, types, independence, and limits at startup;
- compute immutable definition content hashes; and
- add disabled-by-default deployment properties.

### Slice 2: Process-local adaptive sequential runtime

**Status:** Complete. Ephemeral mode is now explicit development-only opt-in;
the production proof uses JDBC.

- share manager input construction and interactive-turn coordination with the
  existing manager runtime;
- execute repeated manager decisions under one deadline;
- invoke singular workers through the existing delegation gateway;
- project approved results into the next manager call;
- support `ASK_USER`, `INVOKE_ONE`, and `COMPLETE` first;
- add no-progress and total-budget enforcement; and
- implement exact process-local replay for the experimental proof.

### Slice 3: Bounded parallel selection

**Status:** Complete with `ALL_REQUIRED` fan-in, cancellation, deadline, and
stable-order tests.

- add `INVOKE_PARALLEL`;
- pre-map every branch;
- reuse the bounded execution executor;
- enforce parallel eligibility and `ALL_REQUIRED`;
- collect and checkpoint branch results in stable order;
- propagate failure, cancellation, and deadline correctly; and
- compare with equivalent sequential and always-run-all fixed plans.

### Slice 4: Terminal read-only handoff

**Status:** Complete with terminal-state and no-second-transition tests.

- add `HANDOFF` as a terminal directive;
- validate a separate exact handoff set;
- invoke through the current handoff gateway;
- prevent manager resumption and further transitions; and
- document that dialogue and WRITE ownership do not transfer yet.

### Slice 5: Durable checkpoint and recovery

**Status:** Complete in source with encrypted JDBC state, leases, optimistic
updates, scheduled recovery, retention, and restart coverage.

- add the repository SPI and protected durable record;
- add in-memory and JDBC implementations;
- add lease, optimistic transition, cleanup, and recovery services;
- checkpoint each accepted directive and successful worker/group;
- resume without repeating completed workers; and
- make durable mode a release requirement for production claims.

### Slice 6: Incident Investigation Room proof

**Status:** Source, deterministic tests, keyed OpenAI tests, UI, packaged
candidate Docker/PostgreSQL restart proof, and local packaged-browser evidence
are complete. Repeating the browser canaries against the immutable deployed
`0.6.0` artifact remains a post-publication Gate E step.

- implement the primary smart investigation experience;
- preserve direct, sequential, parallel, delegation, and handoff controls;
- expose safe traces and comparison measurements;
- add deterministic failure injection;
- run packaged local Docker verification with a mock profile;
- run the full real OpenAI scenario matrix; and
- deploy and verify the browser UI at desktop and mobile sizes.

### Slice 7: Documentation and release

**Status:** Architecture, migration, LoomAI adoption, CI, candidate release
notes, and source verification evidence are complete. Publishing, immutable
tagging, deployment, and the final demo video remain release-rollout work, not
source implementation gaps.

- add an architecture guide and decision table;
- add Java registration examples and configuration reference;
- add security, persistence, troubleshooting, and migration guidance;
- update the LoomAI adoption prompt and migration runbook;
- produce a live demo video covering adaptive, parallel, failure, and replay
  behavior; and
- release only after Central artifacts, source tag, packaged consumer, and
  deployed canaries prove the same version.

## 18. Test Matrix

### 18.1 Contract tests

- every valid directive shape;
- missing, conflicting, excessive, duplicate, and malformed targets;
- exact-version target parsing;
- bounded message, reason, objective, and result views;
- immutable defensive copies;
- limit construction and narrowing; and
- safe result/failure invariants.

### 18.2 Registry tests

- feature-disabled behavior;
- unknown manager or target;
- manager/worker type mismatch;
- target absent from delegation or handoff policy;
- WRITE, dialogue-capable, or conversation-reading worker rejection;
- non-independent target rejected from parallel use;
- self, duplicate, alias, and unversioned target rejection;
- deployment ceilings enforced;
- content hash changes for every semantic definition change; and
- all existing manager, plan, delegation, and handoff registrations remain
  unchanged.

### 18.3 Gateway tests

- complete without a worker;
- ask user with exactly one conversation append;
- one successful worker followed by manager completion;
- two adaptive sequential workers;
- two parallel workers that overlap in time;
- final manager synthesis receives only projected views;
- manager cannot select an unapproved target;
- application mapper controls worker input;
- worker is independently authorized;
- one target cannot observe another result;
- repeated-target and no-progress rejection;
- decision, worker, parallel, duration, and projected-character limits;
- manager, worker, mapper, projector, validator, and persistence failures;
- required parallel branch failure and cancellation;
- thread interruption propagation;
- exact replay and changed-payload conflict;
- no user history, trusted context, or raw evidence leakage; and
- one terminal handoff with no manager continuation.

### 18.4 Persistence and restart tests

- durable create and access-scoped lookup;
- optimistic transition conflicts;
- lease expiry and recovery;
- encrypted/protected payload inspection;
- restart after manager decision;
- restart after one sequential worker;
- restart after completed parallel branches;
- completed workers are not invoked twice;
- changed chain/specialist hashes fail resume;
- current authority is reapplied on resume;
- terminal replay after two restarts; and
- retention cleanup cannot delete active work.

### 18.5 Security tests

- spoofed tenant, deployment, subject, scope, target, and identity;
- prompt injection requesting undeclared specialists;
- target objective attempting to inject authority or action parameters;
- cross-tenant worker result projection;
- unrestricted conversation transfer attempt;
- parent/worker capability union attempt;
- worker second-transition attempt;
- parallel sibling data access attempt;
- WRITE-capable target startup and runtime denial;
- raw prompt, payload, evidence, and credential log scan; and
- failure paths produce no fallback answer.

### 18.6 Real-provider evaluations

Use real OpenAI only through the private keyed profile. Never commit keys or
provider payloads.

The evaluation set must include:

- single-target requests for every approved specialist;
- adaptive two-step requests;
- independent two-target parallel requests;
- requests requiring no worker;
- ambiguous requests requiring clarification;
- misleading language attempting to force a target;
- invented target pressure;
- no-material-evidence cases;
- provider refusal, malformed output, timeout, and branch failure; and
- repeat runs measuring routing stability, model calls, latency, and token
  usage.

Compare:

```text
direct single specialist
fixed sequential plan
fixed always-run-all parallel plan
current one-worker conversation manager
proposed bounded specialist chain
```

Do not claim better accuracy, cost, or latency unless the recorded evaluation
shows it.

### 18.7 Commands and CI

Implementation must add exact module and real-app commands after code exists.
Every gate runs tests normally. `-DskipTests` is prohibited.

Required CI layers:

1. core/execution unit and architecture tests;
2. deterministic real-app reactor tests with `-am` dependencies;
3. clean packaged consumer test against installed candidate artifacts;
4. Docker mock-profile startup and HTTP smoke;
5. keyed real OpenAI matrix;
6. JDBC restart and replay matrix;
7. live deployed backend canaries; and
8. Playwright desktop/mobile UI verification with console, network, and layout
   assertions.

### 18.8 Verified source-candidate evidence

The following evidence was recorded on 2026-09-14 against the uncommitted
`0.6.0` source candidate based on `291e01640d4e`:

- the clean 36-module framework reactor completed successfully with 1,913
  discovered tests, zero failures, and zero errors; the only three skips were
  explicit key-gated connectivity checks, while `ai-fabric-execution` ran all
  383 tests with no skips;
- the clean 26-module real-app reactor completed successfully with 503
  discovered tests, zero failures, and zero errors; 14 tests were skipped only
  because they are the separately keyed Incident OpenAI profile;
- the keyed `IncidentInvestigationRealApiIntegrationTest` ran all 14 OpenAI
  scenarios with no failures, errors, or skips, including adaptive,
  parallel, clarification, no-worker, terminal-handoff, denial, replay, RAG,
  and backend-memory cases;
- the standalone minimal consumer ran two tests and the standalone agentic
  consumer ran three tests, including direct compilation and registration of
  the public bounded-chain contract from installed candidate artifacts;
- the candidate Docker image started against PostgreSQL, reported semantic
  readiness, executed a chain, replayed it exactly, restarted against the same
  database, and replayed again without duplicating the worker;
- all framework release guards and the `release,central` Maven profile
  validation passed; the five-module Maven `release` profile also completed
  through `verify` and produced the expected binary, source, and Javadoc
  artifacts wherever applicable;
- the website ran 84 component tests and produced a successful production
  build; and
- Playwright exercised the packaged backend through the real UI at desktop
  and Pixel 7 sizes: adaptive sequencing, parallel `ALL_REQUIRED` fan-out,
  exact replay, explicit required-branch failure with no substitute answer,
  and cancellation all passed with no console error or horizontal overflow.

These results make the source candidate ready to commit and publish. They do
not substitute for resolving `0.6.0` from an empty Maven repository or for
repeating the canaries against the immutable deployed release.

## 19. Acceptance Gates

### Gate A: Product justification

- the Incident Investigation benchmark contains cases where one, two, and no
  workers are objectively appropriate;
- bounded selection improves the cross-domain cases over the single-worker
  baseline;
- it avoids unnecessary workers compared with always-run-all; and
- the UI removes transition selection from the normal user journey.

### Gate B: Architecture

- no second model, RAG, action, conversation, or provider path exists;
- current delegation and handoff depth remains one;
- workers remain leaves;
- fixed plans retain their current semantics;
- manager state contains projected results only; and
- every transition is deterministically validated.

### Gate C: Security

- all identity, tenant, evidence, target, capability, and WRITE attacks fail
  closed;
- required branch failure cannot produce a success answer;
- no sensitive payload appears in logs, traces, or API responses; and
- replay never duplicates completed worker execution.

### Gate D: Durability

- process restart at every checkpoint resumes correctly;
- exact replay survives restart;
- changed requests and changed definitions conflict visibly;
- leases and optimistic updates prevent concurrent continuation; and
- cleanup preserves active executions.

### Gate E: Release

Source-candidate readiness:

- [x] all deterministic tests pass; only explicitly key-gated real-provider
  connectivity tests are skipped by the keyless reactors;
- [x] all 14 keyed Incident OpenAI scenarios pass without a hidden fallback;
- [x] standalone consumers compile and test against installed candidate JARs,
  including the new public chain declaration;
- [x] packaged Docker/PostgreSQL restart and replay proof passes;
- [x] local desktop/mobile browser canaries match the documented semantics;
- [x] release guards, Maven `release,central` validation, and release-profile
  source/Javadoc packaging pass; and
- [x] LoomAI has a documented opt-in adoption and rollback path.

Immutable release proof, performed only after publication:

- [ ] commit and tag the reviewed source as `ai-fabric-framework-v0.6.0`;
- [ ] resolve the BOM and consumer dependencies from an empty Maven repository;
- [ ] deploy the same commit and artifacts, with demo health reporting the
  immutable `0.6.0` version and commit;
- [ ] repeat adaptive, parallel, failure, replay, cancellation, restart, and
  tenant-boundary canaries against the deployed backend and browser UI; and
- [ ] record the LoomAI canary and final demo evidence without weakening any
  fail-closed behavior.

## 20. Compatibility And Release Impact

This should be additive:

- do not change `SpecialistDelegationRequest` or
  `SpecialistDelegationResult` from singular to plural;
- do not change the one-level transition engine depth rule;
- do not mutate the current conversation-manager DTOs;
- do not change fixed sequential or parallel plan behavior;
- do not enable chains for existing applications automatically;
- do not add a required database dependency; and
- do not change Mode, RAG, action, chat-session, or provider contracts.

Because this adds a material public coordination capability, a verified
`0.6.0` minor release is more honest than a patch release. The exact version
must be decided only when implementation scope and compatibility are final.

## 21. Rejected Alternatives

### 21.1 Allow workers to recursively delegate

Rejected for this plan. It creates unclear ownership, cycles, expanding
context, weak cost bounds, and difficult replay. A centralized manager can
achieve the useful adaptive behavior while workers remain isolated leaves.

### 21.2 Change the singular delegation gateway to accept a target list

Rejected. It would blur the meaning of one parent/child relationship and make
existing replay, lineage, and failure semantics ambiguous. The chain
coordinator should call the existing singular gateway under one outer
execution boundary.

### 21.3 Build an arbitrary graph DSL

Rejected. Fixed plans already cover known topology. A graph DSL would duplicate
specialized products and distract from AI Fabric's application-governance
position.

### 21.4 Add LangChain4j or LangGraph as a second runtime

Rejected for the initial implementation. Spring AI remains the infrastructure
provider for models, structured output, and supported integrations. A second
agent runtime would create competing execution, state, tool, and observability
authorities. External runtimes may be evaluated later through narrow adapters
only when a concrete product requirement cannot be met natively.

### 21.5 Let application code call delegation repeatedly without a framework
boundary

Rejected as a supported product pattern. It lacks one budget, one durable
state machine, one group failure policy, one trace, and one replay identity.

### 21.6 Use deterministic keyword routing as fallback

Rejected. Routing is an LLM intelligence responsibility in this capability.
Provider or structured-output failure must remain visible.

## 22. Later Capabilities Requiring Separate Evidence

Do not bundle these into Plan `0015`:

- interactive dialogue-owner handoff with atomic durable ownership transfer;
- chained or parallel WRITE proposals;
- pending-action, review, or receipt migration;
- partial-success or quorum fan-in;
- bounded evaluator-optimizer loops;
- dynamic target discovery through MCP or a platform catalog;
- cross-process distributed chain execution;
- manager-selected provider/model changes;
- arbitrary nested fixed plans inside dynamic chains;
- A2A or remote specialist transport;
- group chat, debate, or consensus agents; and
- unrestricted multi-hop recursion.

Each requires an independent business proof, threat model, persistence model,
and release gate.

## 23. Final Recommendation

Approve the direction, but implement it in the slices above.

The immediate demo correction should happen first because it needs no
framework change and accurately demonstrates the intelligence already
available. The framework investment should then focus on one product-backed
capability: a centralized manager that can select a bounded set of read-only
specialists, adapt after approved results, and synthesize one grounded answer.

This gives AI Fabric meaningful chaining intelligence and better market
coverage while preserving its differentiator: AI may choose among approved
possibilities, but Java application policy controls what can actually happen.
