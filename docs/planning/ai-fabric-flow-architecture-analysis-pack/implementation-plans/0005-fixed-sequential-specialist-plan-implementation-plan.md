# Fixed Sequential Specialist Plan Implementation Plan

- **Status:** Implemented and verified; not released
- **Date:** 2026-07-29
- **Framework baseline:** AI Fabric `0.4.0`
- **Code reviewed at:** `958e80a9a3a3`
- **Implemented at:** `ce03c22`
- **Prerequisite:** Plans `0001` through `0004`
- **Target:** P2.2 of the agentic-enablement roadmap; version not assigned
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## 1. Purpose

Add the first deterministic composition boundary above
`AIExecutionGateway`: an immutable, exact-version, fixed sequential plan.

One application-approved plan may invoke one or more independently registered
specialists in a predefined order. Registered Java mappers create each typed
step input, and a registered Java aggregator creates the final typed output.
Each step binds its application DTOs through `SpecialistClientFactory` and
then passes through the existing execution gateway. Manifest specialists keep
their JSON Schema boundary without leaking `JsonNode` into plan mappers, while
native Java specialists retain their exact registered types. Every invocation
therefore receives its own authority, effective-capability, grounding, schema,
limit, and provider checks.

The reference proof adds:

1. one explicit one-step plan equivalent to the current read-only Account
   Resolver specialist;
2. one acyclic two-step plan that first assesses account state and then assesses
   a billing-resolution path; and
3. a typed input wait in the second step that resumes without rerunning the
   completed first step.

This plan is composition, not open-ended agent autonomy.

## 2. Code-Backed Starting Point

| Current code | Consequence for this plan |
| --- | --- |
| `AIExecutionGateway` is the canonical entry for one exact-version specialist | The plan coordinator must call this gateway once per specialist step; it must not call the orchestration pipeline or providers directly. |
| `SpecialistClientFactory` validates application DTOs against manifest schemas and delegates to `AIExecutionGateway` | A plan step can expose application records while preserving the manifest's native `JsonNode` execution boundary. |
| `SpecialistRegistry` exposes immutable registered definitions and fingerprints | Plan registration can validate exact specialist references and adjacent input/output types at startup. |
| `AIExecutionResult` already has typed success, confirmation, input-wait, denial, and failure outcomes | The coordinator can translate child outcomes without inventing a second provider or orchestration result contract. |
| Plan `0004` added authority-scoped, profile-pinned, schema-valid `AIExecutionGateway.resume(...)` | A waiting plan step must retain the child invocation and delegate the response to this existing resume path. |
| `ExecutionAccessBinding` protects process-local status and continuation operations | Plan execution state must use the same principal, subject, tenant, deployment, and source binding. |
| `EphemeralExecutionStore` and `EphemeralInputWaitStore` explicitly do not survive restart | The first plan checkpoint store must also be bounded and explicitly `EPHEMERAL`; durable cross-process plans remain P3. |
| `SpecialistExecutionProfile.writeEnabled()` identifies specialists that may produce WRITE proposals | P2.2 plan registration rejects WRITE-capable specialists until durable confirmation/action continuation is designed for composed plans. |
| The Agentic Resolver already has `account-resolver-read@1` and `billing-resolution-advisor@1` | The reference plan can prove real independent specialist calls without changing the existing Account Resolver demo or duplicating business operations. |

## 3. Product Boundary

### Included in P2.2

- exact-version Java-registered fixed plans;
- an ordered specialist-step list that is acyclic by construction;
- exact-version registered Java input mappers and aggregators;
- startup validation of plan, specialist, mapper, aggregator, and Java types;
- independent `AIExecutionGateway` invocation for every step;
- one plan deadline propagated to every child request;
- bounded, context-protected, process-local plan state;
- checkpointed completed steps;
- typed input wait and resume for the active step;
- idempotent resume replay and conflicting-resume rejection;
- status lookup and cancellation;
- application, event, and scheduled execution sources only;
- one-step and two-step reference plans;
- deterministic final aggregation; and
- per-step evidence and timing traces.

### Excluded

- model-generated plans or routing;
- a graph DSL, cycles, loops, dynamic step insertion, or parallel branches;
- supervisor models, handoffs, or specialist-authored delegation;
- manifest/YAML plan definitions;
- conditional branches or human review;
- interactive plan invocation or dialogue-owner selection;
- WRITE-capable specialist steps and composed action confirmation;
- durable JDBC/JPA plan state or restart resume;
- distributed workers, callbacks, queues, or schedulers;
- generic workflow-engine semantics; and
- claims of general multi-agent support.

## 4. Canonical Public Contracts

Add the contracts under `ai.fabric.execution.plan`.

### Versioned identity

`ExecutionPlanId` and `PlanComponentId` use normalized exact
`name@version` identity. A plan, mapper, or aggregator reference without a
version is invalid.

### `ExecutionPlanDefinition<I, O>`

An immutable application-owned blueprint:

```java
public record ExecutionPlanDefinition<I, O>(
    ExecutionPlanId id,
    Class<I> inputType,
    Class<O> outputType,
    List<SpecialistPlanStep> steps,
    PlanComponentId aggregatorId,
    Duration maximumDuration
) {}
```

The step list is ordered, non-empty, bounded, and immutable. This deliberately
avoids publishing a graph language before branching and durable continuation
are justified.

### `SpecialistPlanStep`

```java
public record SpecialistPlanStep(
    String id,
    SpecialistId specialistId,
    Class<?> inputType,
    Class<?> outputType,
    PlanComponentId inputMapperId
) {}
```

A step names only composition references plus its application-facing typed
binding. It does not carry a prompt, Mode, vector-space grant, action grant,
identity, endpoint, model name, or application service. Registration binds
these Java classes through `SpecialistClientFactory`: manifest-backed
specialists validate them against pinned input/output schemas, while native
Java specialists require exact adapter types.

### `PlanStepInputMapper<P, I>`

```java
public interface PlanStepInputMapper<P, I> {
    PlanComponentId id();
    Class<P> planInputType();
    Class<I> stepInputType();
    Map<String, Class<?>> requiredStepOutputs();
    I map(P planInput, PlanStepOutputs approvedOutputs);
}
```

Rules:

- the mapper is a registered application bean;
- it receives the typed original plan input;
- it receives only predecessor outputs declared by step ID and Java type;
- undeclared or future-step output access is impossible;
- it cannot read trusted execution context, capability profiles, evidence
  stores, chat history, or provider clients from the coordinator contract; and
- its result must match the step's application-facing specialist input type.

The application remains responsible for keeping identity, tenant, and
authority out of public plan input. Those values come from
`TrustedExecutionContext`.

### `PlanResultAggregator<P, O>`

```java
public interface PlanResultAggregator<P, O> {
    PlanComponentId id();
    Class<P> planInputType();
    Class<O> outputType();
    Map<String, Class<?>> requiredStepOutputs();
    O aggregate(P planInput, PlanStepOutputs approvedOutputs);
}
```

The aggregator receives only declared, validated outputs and returns one typed
result. It cannot authorize or execute a business action. Null or wrong-type
results fail visibly.

### Gateway contracts

`AIExecutionCoordinator` exposes:

```java
<I, O> PlanExecutionResult<O> execute(PlanExecutionRequest<I> request);
<O> PlanExecutionResumeResult<O> resume(PlanExecutionResumeRequest request);
Optional<PlanExecutionSnapshot> find(
    String executionId,
    TrustedExecutionContext context
);
boolean cancel(String executionId, TrustedExecutionContext context);
```

`PlanExecutionRequest` contains exact plan ID, typed input, trusted execution
context, optional deadline, and optional idempotency key. The first release
rejects `ExecutionSource.INTERACTIVE`; it will not share one conversation
binding across worker specialists before dialogue ownership is implemented.

`PlanExecutionResumeRequest` contains plan execution ID, child input request
ID, a typed host response, current trusted context, and a required idempotency
key. It does not accept specialist identity or trusted scope from the caller.
The specialist client converts that typed response to the specialist's
internal schema-bound representation. This keeps Jackson-specific tree types
out of the public web boundary and preserves the exact response type registered
by a native Java specialist.

## 5. Registration And Startup Validation

Create independent registries for:

- plan definitions;
- input mappers; and
- result aggregators.

Startup fails when:

- a plan, mapper, or aggregator exact-version ID is duplicated;
- a plan has no steps or exceeds `max-steps`;
- a step ID is blank or duplicated;
- a referenced specialist, mapper, or aggregator is absent;
- a plan references a WRITE-capable specialist;
- mapper plan-input type differs from the plan input type;
- mapper output type differs from the step's application input type;
- the step's application input/output types cannot bind to the pinned
  specialist schemas or native adapter types;
- a mapper declares an unknown, current, or future step output;
- a declared predecessor output type differs from the registered specialist
  output type;
- the aggregator input/output types differ from the plan contract;
- the aggregator declares an unknown step or wrong output type; or
- maximum duration is absent, non-positive, or exceeds the application
  ceiling.

Registration computes a deterministic content hash from plan identity, Java
types, ordered steps, exact specialist/mapper/aggregator references, and
maximum duration. A waiting execution pins this hash.

## 6. Runtime Flow

### Start

1. Resolve the exact registered plan.
2. Validate the plan input type and effective deadline.
3. Create an `EPHEMERAL` execution bound to current trusted context.
4. For the first ready step:
   - resolve the exact registered mapper;
   - expose only its declared completed outputs;
   - create and validate the typed specialist input;
   - resolve the cached schema/native `SpecialistClient` binding;
   - invoke that client, which delegates to `AIExecutionGateway`, with current
     trusted context, no conversation binding, the plan deadline, and a child
     idempotency key derived from the plan execution and step.
5. On child success, checkpoint its validated output, evidence, diagnostics,
   invocation ID, and timing.
6. Repeat for the next ordered step.
7. After the final step, expose only aggregator-declared outputs, run the
   deterministic aggregator, validate the final output type, and complete.

### Child input wait

1. When a child returns `WAITING_FOR_INPUT`, retain:
   - plan and content hash;
   - typed original plan input;
   - completed step checkpoints;
   - active step and child invocation;
   - child request ID and specialist ID;
   - deadline and trusted access binding.
2. Return one plan-level wait view that identifies the plan execution and
   active step while preserving the safe child response contract.
3. On resume, claim the plan wait by context and idempotency.
4. Re-resolve the same plan content hash.
5. Delegate the typed host response through the same specialist client to
   `AIExecutionGateway.resume(...)`, where schema validation and the original
   specialist wait contract remain authoritative.
6. If the child waits again, update only the active step wait.
7. If it succeeds, checkpoint it and continue at the next step.
8. Never rerun an already completed predecessor.

### Failure and cancellation

- A child denial, invalid result, deadline, cancellation, unexpected action
  proposal, mapper error, or aggregator error terminates the plan visibly.
- Cancellation marks the plan terminal and delegates cancellation to the
  active child invocation when present.
- A result arriving after cancellation or terminal completion cannot advance
  the plan.
- The first release does not compensate or roll back completed read-only
  steps.

## 7. State And Idempotency

Create `EphemeralPlanExecutionStore`. It is separate from:

- conversation history;
- pending actions;
- action proposal receipts;
- specialist input-wait state; and
- behavior/event storage.

Configuration:

```yaml
ai:
  execution:
    plans:
      enabled: true
      max-steps: 8
      max-duration: PT2M
      max-active: 1000
      result-ttl: PT15M
```

The store:

- is bounded by `max-active`;
- evicts terminal entries after `result-ttl`;
- retains active input waits until their child expiry/deadline;
- stores no credentials or raw provider payloads;
- protects lookup, resume, and cancellation with `ExecutionAccessBinding`;
- indexes active start idempotency keys;
- retains completed-step checkpoints while waiting;
- claims one resume operation at a time;
- replays the same terminal result for an identical resume;
- rejects conflicting resume data/idempotency; and
- explicitly does not survive process restart.

## 8. Reference App Proof

### One-step plan

Register:

```text
account-readiness@1
```

It maps `AccountResolutionRequest` to
`account-resolver-read@1` and deterministically returns the same typed
`AccountResolutionResult`. This proves that using the plan abstraction does not
change the existing specialist result.

### Two-step plan

Register:

```text
account-billing-resolution@1
```

Input:

```json
{
  "question": "Can this account receive a $75 refund?",
  "resolutionType": "REFUND",
  "amount": 75
}
```

Steps:

1. `account-state` invokes `account-resolver-read@1`;
2. `billing-path` invokes `billing-resolution-advisor@1`.

The second mapper receives the original typed request plus only the typed
`AccountResolutionResult` checkpoint. It creates the billing specialist input
and includes the account assessment as bounded context in the question; it
does not forward a prompt, transcript, trusted identity, or raw evidence.

The aggregator returns:

```text
AccountBillingResolutionPlanResult
  accountAssessment
  accountSummary
  billingDecision
  expectedBillingStatus
  automaticLimit
  explanation
```

If amount is absent, step one succeeds and step two waits. Resume with a valid
amount must complete the plan while invocation metrics prove step one ran
exactly once.

Expose:

```text
POST /api/agentic-resolver/plans/account-readiness
POST /api/agentic-resolver/plans/account-billing-resolution
POST /api/agentic-resolver/plans/input/resume
GET  /api/agentic-resolver/plans/executions/{executionId}
DELETE /api/agentic-resolver/plans/executions/{executionId}
```

Every endpoint derives current subject, tenant, principal, source, and scopes
from the demo session. Request JSON cannot select them.

## 9. Tests

### Framework contracts and registries

- exact ID normalization and validation;
- immutable definitions and output maps;
- duplicate component and plan rejection;
- unknown specialist/component rejection;
- empty/oversized plan rejection;
- duplicate step rejection;
- mapper input/output mismatch rejection;
- undeclared/future output dependency rejection;
- aggregator contract mismatch rejection;
- WRITE-capable specialist rejection;
- maximum-duration validation; and
- deterministic plan content hash.

### Coordinator

- one-step success and deterministic aggregate;
- two-step order and independent gateway calls;
- trusted context, deadline, absence of worker conversation binding, and child
  idempotency propagation;
- interactive-source rejection until dialogue ownership exists;
- mapper receives only declared outputs;
- child failure/denial/deadline mapping;
- unexpected confirmation fails closed;
- mapper and aggregator exceptions produce safe visible failure;
- final result type validation;
- context-scoped find/cancel;
- duplicate start key rejection or replay according to documented semantics;
- child wait returns plan wait;
- cross-context resume denial;
- malformed child response remains retryable through the child wait;
- valid resume continues from active step;
- completed predecessor is not rerun;
- identical resume replay;
- conflicting resume rejection;
- plan-content change rejection; and
- cancellation/late-result rejection.

### Reference app

- Spring context registers both plans and components;
- endpoint trust context ignores request-controlled identity;
- one-step plan matches the direct read-specialist shape;
- two-step complete-input success;
- two-step missing-input wait and safe resume;
- wrong demo session cannot resume;
- resumed plan records step one once;
- mock-profile package and packaged-JAR health; and
- real OpenAI complete and missing-input scenarios.

### Regression

- all `ai-fabric-execution` tests;
- full `ai-infrastructure-module` reactor tests;
- clean Agentic Resolver package with dependencies; and
- existing specialist, action receipt, manifest, and typed input-wait tests
  unchanged.

## 10. Acceptance Gate

P2.2 is complete only when:

1. all plans and components use immutable exact-version identity;
2. startup rejects every invalid plan/component relationship listed above;
3. each step invokes the existing gateway independently;
4. a plan cannot expand any specialist capability;
5. mappers and aggregators receive only declared predecessor outputs;
6. completed steps are checkpointed outside chat history;
7. a waiting second step resumes without rerunning step one;
8. wrong-context, malformed, expired, conflicting, or cancelled resumes fail
   visibly;
9. WRITE-capable specialists are rejected from this first plan runtime;
10. state is bounded and explicitly process-local;
11. the original Account Resolver remains unchanged;
12. framework and app tests pass normally without skipped tests;
13. the packaged app starts and reports both registered plans; and
14. real OpenAI scenarios prove both complete and wait/resume flows.

All fourteen conditions were verified on 2026-07-29.

## 11. Verification Evidence

### Deterministic gates

- `mvn -f ai-infrastructure-module/pom.xml test`
  passed all 36 infrastructure modules with tests enabled.
- Focused execution installation passed 11 coordinator and contract tests.
- `mvn -f examples/real-apps/pom.xml -pl
  agentic-ai-action-resolver -am package` passed 12 shared smoke tests and 96
  Agentic AI Action Resolver tests and produced the runnable boot JAR.
- The focused plan service and Spring MVC boundary suite passed 10 tests.
- Existing specialist, manifest, governed-write, receipt, input-wait, action,
  RAG, provider, indexing, and integration suites remained green.

### Packaged real-provider proof

The packaged Agentic AI Action Resolver ran with the OpenAI provider and proved:

1. health and readiness expose both registered plans;
2. `account-readiness@1` succeeds through one independent specialist step;
3. `account-billing-resolution@1` succeeds through two ordered specialist
   steps and returns `REVIEW_REQUIRED` for a 75-unit refund against a 50-unit
   automatic limit;
4. an omitted amount returns `WAITING_FOR_INPUT` at the second step after the
   first step has completed;
5. a typed amount response resumes the same execution, completes the second
   step, and does not rerun the first step; and
6. an identical resume returns `REPLAYED` without invoking either specialist
   again.

### Runtime issue found and protected

The first packaged resume exposed an HTTP 500 at the Spring Boot 4 web boundary:
the request DTO used a Jackson 2 `JsonNode`, while Spring Boot 4's HTTP
converter uses Jackson 3. The public plan resume contract now accepts the
registered typed host response, the Account Resolver endpoint accepts
`BillingAmountResponse`, and the specialist client owns conversion to its
internal schema representation. A Spring MVC regression test proves the
packaged request body deserializes successfully.

## 12. Later Plans

- deterministic branch/decision components;
- composed WRITE proposals and durable action continuation;
- durable execution/checkpoint storage;
- human review and machine-trigger continuation;
- parallel groups with sibling isolation and cancellation;
- supervisor-constrained routing;
- plan manifests and Loom AI authoring UI; and
- evaluation gates that decide whether decomposition beats one specialist.
