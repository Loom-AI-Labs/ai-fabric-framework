# One-Level Declared Specialist Delegation Implementation Plan

- **Status:** Implemented and verified; not released
- **Date:** 2026-07-29
- **Framework baseline:** AI Fabric `0.4.0`
- **Code reviewed at:** `21d0ca5`
- **Prerequisite:** Plans `0001` through `0008`
- **Target:** Post-P3 coordination slice; version not assigned
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## 1. Purpose

Allow one validated specialist result to request one application-approved
child specialist without turning AI Fabric into a recursive agent graph or
giving a model access to the complete specialist registry.

The first product proof is an Account Resolution Coordinator that may select
one exact-version, read-only specialist from a closed manifest allowlist:

- `account-resolver-read@1` for current-account readiness; or
- `billing-resolution-advisor@1` for a supplied refund or credit assessment.

The coordinator returns a typed structured decision. The host application
passes that validated result to the delegation gateway. AI Fabric checks the
source declaration, target registration, current specialist versions,
trusted authority, deadline, depth, and target contract before invoking the
child through the existing `AIExecutionGateway`.

The coordinator manifest is a closed structured-generation contract with no
retrieval or actions. AI Fabric derives the server-owned
`GENERATION_ONLY` orchestration intent policy for that shape. The model still
performs semantic intent extraction, but cannot accidentally request retrieval
that the manifest forbids. Normal grounded specialists retain
`MODEL_DIRECTED` behavior.

## 2. Product Boundary

This slice adds one-level declared delegation, not a conversation manager,
supervisor, workflow graph, or open-ended tool router.

```text
application-selected root specialist
             |
     validated structured output
             |
 exact target from source allowlist
             |
 SpecialistDelegationGateway
             |
 source version/depth/deadline checks
             |
 independent target binding + authority resolution
             |
 existing AIExecutionGateway / RAG / READ actions / provider path
             |
 typed child result with immutable lineage
```

The model may choose among names already present in the root output schema and
specialist delegation policy. It cannot invent a target, enumerate the
registry, widen scopes, supply identity, transfer a conversation, or execute a
WRITE.

## 3. Code-Backed Starting Point

| Current code | Consequence |
| --- | --- |
| `SpecialistDefinition` is the canonical immutable aggregate for Java and manifest specialists | Add one immutable delegation policy here so every authoring path reaches the same runtime contract. |
| Plan `0003` deliberately excluded delegation fields until execution and tests existed | Add the manifest field only together with the enforced gateway in this plan. |
| `DefaultSpecialistRegistry` validates all compiled Java and manifest definitions after startup loading | Perform exact target, self-target, and read-only target validation after the complete registry is assembled. |
| `AIExecutionResult` contains validated typed output, specialist identity, evidence, diagnostics, and invocation ID | Use the successful source result as the delegation parent; do not parse raw model prose in the gateway. |
| Successful execution diagnostics contain the exact current specialist content hash | Require that hash to match the current registered source before delegation. |
| `SpecialistClientFactory` validates typed Java bindings against native or JSON Schema-backed specialists | Reuse it for target input/output compatibility rather than adding reflective conversion. |
| `AIExecutionGateway` already performs target input validation, Mode/policy resolution, effective-capability intersection, grounding, output validation, deadline handling, and provider invocation | Delegation must call this boundary; it must not invoke the pipeline, model, RAG, or action handlers directly. |
| Fixed plans already prove deterministic multi-specialist composition | Delegation adds a dynamic target chosen from a closed source allowlist; it does not replace fixed plans. |
| `TrustedExecutionContext` is backend-owned | The child receives the current backend context and is independently authorized. Parent or model output supplies no authority. |

## 4. Included Scope

### Definition and manifest contracts

- `SpecialistDelegationPolicy` with an immutable set of exact
  `SpecialistId` targets;
- disabled-by-default behavior for every existing Java definition;
- optional manifest shape:

```yaml
spec:
  delegation:
    targets:
      - account-resolver-read@1
      - billing-resolution-advisor@1
```

- a maximum of eight declared targets;
- canonical hashing of the sorted target set;
- strict parsing and duplicate rejection;
- registry validation that every target exists;
- registry rejection of self-delegation; and
- registry rejection of WRITE-capable targets in this first version.

### Runtime contracts

- typed `SpecialistDelegationRequest<P,I>`;
- typed `SpecialistDelegationResult<P,O>`;
- safe `SpecialistDelegationFailure`;
- `SpecialistDelegationGateway`;
- `DefaultSpecialistDelegationGateway`;
- required source success and output;
- exact source content-hash verification;
- fixed maximum depth of one;
- exact declared-target enforcement;
- required idempotency key;
- same-process scoped replay and conflict detection;
- parent deadline inheritance;
- no conversation binding on the child;
- target binding through `SpecialistClientFactory`;
- child execution through `AIExecutionGateway`;
- immutable parent/child invocation lineage; and
- safe diagnostics without input, output, identity, tenant, or evidence
  payload logging.

### Reference application

Add a configuration-defined `account-resolution-coordinator@1` that:

- receives a natural-language account-support request plus optional typed
  billing fields;
- returns either `COMPLETE` or `DELEGATE`;
- selects only an exact target from the output-schema enum;
- lets the host map the validated application request to typed child input;
- does not receive actions, a conversation, or application authority;
- delegates current-account questions to `account-resolver-read@1`;
- delegates complete billing-policy questions to
  `billing-resolution-advisor@1`;
- returns the validated coordinator decision and typed child result; and
- exposes denial and provider failures instead of deterministic fallback.

## 5. Explicitly Excluded

- recursive delegation or depth greater than one;
- a public list/search endpoint for all specialists;
- unrestricted model-selected specialist IDs;
- target patterns, aliases, labels, or `latest` versions;
- delegation to WRITE-capable specialists;
- action proposals or review tasks created by a delegated child;
- shared or transferred conversation history;
- delegated input wait/resume in this first slice;
- handoff of dialogue ownership;
- parallel fan-out/fan-in;
- a manager/supervisor model;
- model-generated execution plans;
- dynamic specialist registration;
- general graph nodes, transitions, cycles, or loops;
- cross-process durable delegation state; and
- hidden fallback when source or target intelligence fails.

## 6. Security And Authority Rules

1. The host application selects the root specialist.
2. The root sees only its own declared capabilities.
3. A source result must be `SUCCEEDED` and contain a validated typed output.
4. The source's pinned content hash must equal the current registered hash.
5. The target must be exact-versioned and declared by the source.
6. The source cannot target itself.
7. Delegation depth must be zero before invocation and becomes exactly one.
8. The child receives no conversation binding.
9. The child receives the current backend-created `TrustedExecutionContext`.
10. The child independently passes input, Mode, deployment, registry,
    authority, evidence, grounding, and output validation.
11. Parent and child capabilities are never unioned.
12. The first runtime rejects a target that may propose a WRITE.
13. The earliest parent/request deadline wins.
14. The same scoped idempotency key may replay only the same parent, target,
    input, and authority binding.
15. Any mismatch is an explicit denial or conflict, never a fallback.

## 7. Runtime Result Shape

The safe result records:

```text
delegation ID
parent invocation ID
source specialist ID
target specialist ID
depth = 1
delegation status
validated source output
typed target AIExecutionResult
safe delegation failure when no target invocation occurred
started/completed timestamps
```

The child execution result remains the existing AI Fabric contract. Its
diagnostics add only safe lineage:

```text
delegation=true
delegationId
delegationDepth=1
parentInvocationId
sourceSpecialist
```

No raw prompt, credentials, trusted context, action parameters, or unprojected
provider response is added.

## 8. Test Matrix

### Contract and registry tests

- existing definitions remain delegation-disabled;
- exact targets normalize immutably;
- blank, duplicate, excessive, and self targets fail;
- unknown target fails final registry construction;
- WRITE-capable target fails final registry construction;
- delegation changes the specialist content hash;
- manifest target list compiles to the canonical policy; and
- unknown manifest fields remain rejected.

### Gateway tests

- declared typed read-only child succeeds;
- undeclared target is denied before target binding;
- stale source content hash is denied;
- failed or waiting source cannot delegate;
- delegated child receives no conversation;
- trusted context reaches the child unchanged and is reauthorized normally;
- deadline is inherited and expired work is rejected;
- child type mismatch is explicit;
- child input wait is explicitly unsupported;
- child confirmation is explicitly unsupported;
- depth-one child cannot delegate again;
- same idempotency request replays;
- changed target/input under the same scoped key conflicts; and
- child provider/framework failure remains visible.

### Reference-app tests

- coordinator manifest and schemas load from a clean packaged runtime;
- current-account routing invokes only `account-resolver-read@1`;
- billing routing invokes only `billing-resolution-advisor@1`;
- an invented target is rejected;
- incomplete billing input remains a coordinator result and does not start an
  unsupported child wait;
- application authority still denies a missing target specialist scope;
- no text-match routing exists in application code; and
- mock and real OpenAI paths return the same contract family.

## 9. Verification Gate

The slice is complete only when:

1. all existing core/execution tests pass normally;
2. all Agentic Resolver tests pass normally;
3. the packaged application starts with strict manifest loading;
4. a real OpenAI request selects and executes both approved target families;
5. an undeclared target is denied before any child provider call;
6. missing target authority is denied by the existing capability resolver;
7. replay and conflict behavior are proved;
8. no WRITE-capable child can be registered as a delegation target;
9. no placeholder, stub, disabled test, or hidden fallback is present;
10. `git diff --check` passes; and
11. documentation states that delegation is process-local and one-level.

## 10. Completed Verification

Verification completed on 2026-07-29:

- the focused core and execution delegation suites passed 13 and 38 tests;
- the final execution reactor passed 970 tests with no failures or skips: 5
  curated-default, 673 core, 56 chat-session, and 236 execution tests;
- the real-app reactor passed 12 shared smoke-support and 117 Agentic Resolver
  tests;
- a clean packaged app build passed all 117 application tests;
- the packaged `ai-fabric-core-0.4.0.jar` and
  `ai-fabric-execution-0.4.0.jar` hashes matched the verified local Maven
  artifacts, preventing stale nested dependencies from invalidating the live
  proof;
- packaged real OpenAI execution routed a current-account request to
  `account-resolver-read@1`, returned one payment blocker, and exposed four
  safe policy evidence records;
- packaged real OpenAI execution routed a typed account-credit request to
  `billing-resolution-advisor@1` and returned the expected policy assessment;
- the coordinator exposed `GENERATION_ONLY` and the visible policy-adjustment
  diagnostic while preserving the model's semantic routing decision;
- exact replay returned the original coordinator, delegation, and child
  invocation IDs without a second execution;
- changed input under the same key returned visible
  `IDEMPOTENCY_CONFLICT`;
- an unsupported marketing request returned `COMPLETE` without creating a
  child execution; and
- registry, gateway, and application tests prove undeclared targets, missing
  target authority, WRITE targets, recursion, waits, confirmations, stale
  source definitions, and provider failures remain explicit.

## 11. Later Options

Only product evidence may promote a later plan for:

- durable delegation across restart;
- typed delegated input wait/resume;
- controlled dialogue handoff;
- one registered result aggregator;
- selected WRITE proposal delegation with full receipt/review ownership; or
- bounded parallel read-only fan-out/fan-in.

Those capabilities must not be implied by this version.
