# Explicit Read-Only Specialist Handoff Implementation Plan

- **Status:** Implemented and verified; not released
- **Date:** 2026-07-29
- **Framework baseline:** AI Fabric `0.4.0`
- **Code reviewed at:** `8690964`
- **Prerequisite:** Plans `0001` through `0009`
- **Target:** Post-P3 coordination slice; version not assigned
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## 1. Purpose

Add a second, explicitly different specialist relationship:

- delegation borrows one child's typed result and leaves responsibility with
  the parent; and
- handoff completes the predecessor's routing responsibility and creates one
  independently authorized successor that owns the resulting specialist
  outcome.

The first product proof is a non-interactive Account Resolution Intake
specialist. It may transfer a service request to one exact-version, read-only
successor:

- `account-resolver-read@1`; or
- `billing-resolution-advisor@1`.

The source produces a validated structured `COMPLETE` or `HANDOFF` decision.
The host application maps the approved request into the successor's typed
input. AI Fabric validates the source, declared handoff target, source content
hash, target registration, authority, deadline, relationship depth, typed
binding, and replay identity before invoking the successor through the
existing specialist execution gateway.

## 2. Why This Is Not Delegation

```text
delegation
  parent invocation
    -> child invocation
    -> typed child result returns to parent
    -> parent remains responsible

handoff
  predecessor invocation completes routing responsibility
    -> successor invocation
    -> successor result is the relationship outcome
    -> no result is routed back for predecessor continuation
```

The runtime must expose `parentInvocationId` only for delegation and
`predecessorInvocationId` only for handoff. It must not relabel a fixed plan
step, mutate an invocation's specialist in place, or represent handoff as a
delegation with different UI wording.

## 3. Deliberate First Boundary

This plan implements a complete synchronous, non-interactive handoff. It does
not claim the full interactive handoff described by Flow 10.

Included:

- one exact-version successor;
- one-level relationship depth;
- read-only successors;
- application-owned typed input projection;
- backend-owned trusted context;
- independent target authorization;
- no conversation transfer;
- predecessor/successor lineage;
- process-local exact replay and conflict detection;
- inherited deadline;
- visible target/provider failures; and
- startup validation of every handoff declaration.

Deferred to a later product-backed plan:

- dialogue-owner transfer;
- an interactive successor conversation;
- persistent handoff state across restart;
- successor input wait/resume;
- successor WRITE proposals or review tasks;
- pending-action migration or cancellation;
- handoff chains, recursive transitions, or cycles beyond depth one; and
- plan-authored or manager-authored handoff.

This boundary is intentional. Dialogue ownership needs an atomic persistent
execution-owner record. WRITE handoff needs exact pending receipt ownership
and migration rules. Neither should be simulated by copying a conversation
binding or action proposal.

## 4. Code-Backed Starting Point

| Current code | Consequence |
| --- | --- |
| `SpecialistDefinition` owns an immutable exact-version delegation policy | Add a distinct handoff policy rather than overloading delegation targets. |
| `SpecialistManifestSpec` supports strict optional delegation targets | Add strict optional `handoff.targets` with the same exact-reference discipline. |
| `DefaultSpecialistRegistry` validates the complete assembled registry | Validate handoff targets, self-targets, and read-only eligibility after all Java and manifest definitions load. |
| `DefaultSpecialistDelegationGateway` now proves source pinning, independent typed child execution, deadlines, replay, and safe lineage | Extract only the common one-level transition mechanics so handoff does not duplicate the gateway or weaken delegation behavior. |
| `AIExecutionGateway` remains the only specialist execution path | Successors must use the existing typed client and gateway; handoff gets no direct model, RAG, or action path. |
| `ConversationBinding` is explicit and adapters declare whether it is allowed | The first handoff always supplies no conversation; a conversation-required successor fails visibly. |
| Governed receipts are pinned to invocation, specialist, profile, action schema, and trusted access | The first handoff rejects WRITE-capable successors and never migrates a pending proposal. |
| Plan `0009` derives `GENERATION_ONLY` for a closed routing specialist | Reuse that policy for the handoff intake specialist without text-match routing or hidden fallback. |

## 5. Definition And Manifest Contracts

Add:

- `SpecialistHandoffPolicy`;
- disabled-by-default behavior for existing definitions;
- exact target references with a maximum of eight;
- immutable canonical target ordering in the specialist fingerprint; and
- manifest shape:

```yaml
spec:
  handoff:
    targets:
      - account-resolver-read@1
      - billing-resolution-advisor@1
```

Startup rejects:

- blank or malformed references;
- duplicates;
- more than eight targets;
- an unregistered target;
- self-handoff; and
- a successor with proposable WRITE actions.

Delegation and handoff declarations remain independent. A source may allow one
relationship, both, or neither for a given target.

## 6. Runtime Contracts

Add:

- `SpecialistHandoffRequest<P,I>`;
- `SpecialistHandoffResult<P,O>`;
- `SpecialistHandoffFailure`;
- `SpecialistHandoffGateway`; and
- `DefaultSpecialistHandoffGateway`.

The result includes:

```text
handoff ID
predecessor invocation ID
predecessor specialist ID
successor specialist ID
depth = 1
handoff status
validated predecessor output
typed successor execution result
safe failure when no successor result is available
replay flag
started/completed timestamps
```

Successor diagnostics include only safe lineage:

```text
handoff=true
handoffId
handoffDepth=1
predecessorInvocationId
predecessorSpecialist
```

They do not include `parentInvocationId`, raw prompts, trusted identity,
credentials, unrestricted evidence, or model-native output.

## 7. Shared One-Level Transition Mechanics

Refactor only mechanics that are truly common to delegation and handoff:

- canonical request fingerprinting;
- access-bound idempotency replay;
- successful and content-hash-pinned source validation;
- exact target resolution;
- read-only target enforcement;
- deadline inheritance;
- typed target binding;
- target execution with no conversation;
- unsupported wait/confirmation handling;
- safe target failure projection; and
- bounded replay cleanup.

Keep relationship-specific behavior outside the shared engine:

- source policy admission;
- stable reason-code prefix;
- child idempotency namespace;
- lineage field names;
- relationship result type; and
- log vocabulary.

All Plan `0009` tests must continue passing unchanged after the refactor.

## 8. Reference Application

Add `account-resolution-intake@1` as a manifest-backed specialist.

Its structured output is:

```text
decision: COMPLETE | HANDOFF
targetSpecialist:
  account-resolver-read@1 |
  billing-resolution-advisor@1 |
  null
reason: safe concise routing explanation
```

Rules:

1. current-account readiness and blocker questions may hand off to
   `account-resolver-read@1`;
2. a complete informational refund or account-credit assessment may hand off
   to `billing-resolution-advisor@1`;
3. incomplete or unsupported requests return `COMPLETE`;
4. the model cannot supply identity, account, authority, target input, action,
   provider, Mode, prompt, or deadline;
5. the host maps only validated application fields to the typed successor
   input; and
6. failures remain visible without deterministic routing fallback.

Expose:

```text
POST /api/agentic-resolver/handoff
X-AI-Fabric-Demo-Session: <server-created session>
Idempotency-Key: <stable key>
```

The response contains the predecessor execution and optional handoff result.

## 9. Security Rules

1. Only a successful validated predecessor output may request handoff.
2. The predecessor content hash must still match the registered definition.
3. The successor must be exact-versioned and declared in the predecessor's
   handoff policy.
4. The predecessor cannot hand off to itself.
5. A delegated child or handoff successor cannot initiate another transition.
6. The successor receives the backend-created trusted context, not authority
   from model output.
7. The successor is independently authorized through normal profile
   resolution.
8. No conversation binding is transferred.
9. No pending action, receipt, review, evidence body, or hidden working state
   is transferred.
10. A WRITE-capable successor is rejected at startup and runtime.
11. The earliest source/request deadline wins.
12. Replay is scoped to the trusted access binding and exact typed work.
13. Changed work under the same key returns a visible conflict.

## 10. Test Matrix

### Definition and registry

- existing definitions remain handoff-disabled;
- exact targets normalize immutably;
- blank, duplicate, excessive, unknown, self, and WRITE targets fail;
- delegation-only and handoff-only targets remain distinct;
- handoff policy changes the content hash; and
- manifest handoff targets compile and reject unknown fields.

### Gateway

- declared typed read-only successor succeeds;
- successor lineage uses predecessor fields and no parent field;
- undeclared target is denied;
- failed, waiting, stale, or unregistered predecessor is denied;
- delegated child and handoff successor cannot transition again;
- successor receives no conversation;
- trusted context reaches the successor and is independently authorized;
- deadline inheritance and expiry are enforced;
- type mismatch, provider failure, wait, confirmation, and cancellation
  failure remain visible;
- exact work replays; and
- changed target/input/type/deadline conflicts.

### Reference app

- the intake manifest and schema load from a packaged runtime;
- current-account routing selects only `account-resolver-read@1`;
- complete billing routing selects only `billing-resolution-advisor@1`;
- incomplete billing and unsupported requests do not start a successor;
- missing successor scope is denied by existing authority resolution;
- no text-match routing or hidden fallback exists; and
- mock and real OpenAI paths return the same contract family.

## 11. Verification Gate

The plan is complete only when:

1. existing delegation tests pass unchanged;
2. all core/execution tests pass normally;
3. all Agentic Resolver tests pass normally;
4. a clean packaged app starts with strict manifest loading;
5. packaged framework dependencies match the verified local artifacts;
6. real OpenAI selects and executes both successor families;
7. real OpenAI returns a visible unsupported `COMPLETE`;
8. replay and conflict behavior are proved;
9. source/target authority and WRITE restrictions are proved;
10. no placeholder, disabled test, hidden fallback, or second intelligence path
    exists;
11. `git diff --check` passes; and
12. documentation clearly separates delegation, handoff, fixed plans, and
    conversation ownership.

## 12. Later Options

The next handoff plan may add an explicit persistent execution-owner record,
dialogue-capable successor eligibility, atomic owner transfer, frozen
conversation revision, safe pending-action disposition, and durable recovery.
It must be justified by an interactive product scenario and cannot infer
ownership from chat history or UI state.

## 13. Completed Verification

The implementation gate completed on 2026-07-29:

- the execution reactor passed 985 tests with no failures or skips: 5
  curated-default, 673 core, 56 chat-session, and 251 execution tests;
- the real-app reactor and clean packaged build each passed 12 shared
  smoke-support and 123 Agentic Resolver tests;
- the packaged core and execution JARs were byte-for-byte identical to the
  locally verified Maven artifacts;
- the packaged app started from an isolated data directory with strict
  manifest loading and reported both the coordinator and intake specialists
  ready;
- real OpenAI routed a current-account request to
  `account-resolver-read@1`, which returned the missing-payment blocker with
  four policy evidence records;
- real OpenAI routed a complete account-credit request to
  `billing-resolution-advisor@1`, which returned its typed policy decision;
- incomplete billing and unsupported marketing requests returned `COMPLETE`
  without starting a successor;
- exact replay returned the original predecessor, handoff, and successor
  invocation IDs with `replayed=true`;
- changed work under the same idempotency key returned visible
  `IDEMPOTENCY_CONFLICT`; and
- gateway and registry tests proved independent target authorization,
  undeclared/unknown/self/WRITE target denial, depth and deadline bounds,
  no conversation transfer, no parent-lineage aliasing, and visible target
  failures.

No deterministic text router, hidden provider fallback, disabled test, or
second specialist execution path was added.
