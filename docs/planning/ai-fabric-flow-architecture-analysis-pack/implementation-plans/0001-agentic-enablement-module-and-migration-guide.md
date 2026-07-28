# Agentic Enablement Module And Migration Guide

- **Status:** Implemented P0/P1 guidance
- **Date:** 2026-07-28
- **Baseline:** AI Fabric `0.4.0`
- **Target:** Proposed `0.5.0`
- **Module:** `ai-fabric-execution`
- **Reference app:** `examples/real-apps/agentic-ai-action-resolver`

## 1. Migration Verdict

Agentic P0/P1 is additive. Existing AI Fabric applications do not need to
replace their orchestration endpoints, Modes, action annotations, providers,
RAG configuration, vector providers, chat-session storage, or live data sync.

Existing calls remain valid:

```java
ragOrchestrator.orchestrate(message, orchestrationContext);
```

Applications adopt the new layer only when they need a versioned, typed,
application-selected AI operation with an explicit capability boundary:

```java
AIExecutionResult<AccountAssessment> result =
    executionGateway.execute(executionRequest);
```

The original `ai-fabric-account-resolver` remains unchanged. The new
`agentic-ai-action-resolver` is a separate app, artifact, storage boundary, and
deployment.

## 2. What Changed In Shared Runtime

The shared pipeline gained additive enforcement primitives:

- trusted execution initiator and subject context;
- a structured `OrchestrationRequest`;
- explicit conversation persistence policy;
- requested and effective capability profiles;
- a capability-aware action catalog;
- one governed action invocation service;
- safe read-side evidence references; and
- an optional specialist execution gateway.

Existing action execution paths now converge on
`GovernedActionInvocationService`. This is an internal safety improvement:
main-pipeline actions, iterative read actions, action-context reads, and Spring
AI tool callbacks all recheck exposure, access mode, authority, confirmation,
and handler authorization at the final boundary.

There is no compatibility flag that restores direct handler invocation.

## 3. Opt In

Add the optional module after its target release is published:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-execution</artifactId>
  <version>${ai-fabric.version}</version>
</dependency>
```

Do not add it when the existing interactive orchestration API already satisfies
the application.

## 4. Adoption Steps

### Step 1: Keep Or Define A Mode

Use an existing Mode as the orchestration ceiling. Configure only the
capabilities that the application is prepared to expose:

```yaml
ai:
  orchestration:
    modes:
      resolver:
        actions-enabled: true
        retrieval-enabled: true
        retrieval-allowlist-required: true
        rag:
          retrieval-vector-spaces-allowlist:
            - account-policy
        read-action-resolution:
          enabled: true
          planning-mode: ITERATIVE
          require-allowlist: true
          allowed-read-actions:
            - get_account_profile
```

### Step 2: Declare Deployment Inventory

```yaml
ai:
  execution:
    capabilities:
      registered-vector-spaces:
        - account-policy
      allowed-actions:
        - get_account_profile
```

This inventory is independent from the specialist request. A requested
capability must exist in both.

### Step 3: Register A Closed Specialist

Provide a `SpecialistDefinition<I, O>` bean with:

- a stable name and explicit version;
- one existing Mode;
- explicit vector spaces;
- visible and executable read actions;
- no write actions for the P0/P1 profile;
- deterministic input validation/rendering;
- output grounding and domain validation; and
- local execution limits.

Startup fails when a definition references an unknown or undeclared
capability.

### Step 4: Build Authority From Authenticated State

Construct `TrustedExecutionContext` after authentication. Never deserialize it
from the public payload.

The application owns:

- principal identity and type;
- current domain subject;
- tenant;
- deployment;
- granted scopes;
- correlation ID; and
- optional authorized conversation binding.

### Step 5: Call The Gateway

Use `execute` for synchronous typed work. Treat non-success status and failure
reason as application outcomes; do not assume an exception is the only failure
surface.

Use `submit` only when in-memory, restart-losing semantics are acceptable.

### Step 6: Add Conversation Only When Needed

No binding means no history read or write. An explicit server-authorized
`ConversationBinding` lets the gateway:

1. read recent backend turns;
2. execute with pipeline persistence disabled;
3. validate the final typed result; and
4. commit the new turn.

Do not send prior messages from the browser.

### Step 7: Return Safe Evidence

Return `AIEvidenceReference` to callers. Do not expose:

- embeddings;
- unrestricted vector metadata;
- indexing queue payloads;
- internal tenant or subject identifiers; or
- raw provider output.

## 5. Conversation Persistence Migration

The structured request contract supports:

- `CONVERSATION`: read history and use normal pipeline recording;
- `READ_ONLY`: read history without recording inside the pipeline; and
- `NEVER`: neither read nor record.

The execution gateway uses `NEVER` without a conversation binding and
`READ_ONLY` with one. It then records only a validated typed result.

Legacy chat metadata remains a compatibility projection. New execution code
should use the typed policy.

## 6. `AIEvidenceReference` Versus `AIIndexDocument`

These contracts serve opposite directions:

| Contract | Direction | Purpose |
| --- | --- | --- |
| `AIIndexDocument` | Application/indexing worker to vector provider | Versioned write-side indexing payload |
| `AIEvidenceReference` | Governed retrieval to application caller | Safe read-side evidence projection |

Do not reuse `AIIndexDocument` as RAG or specialist evidence. It contains
write-lifecycle concerns that do not establish read authorization.

## 7. Release Adoption Checklist

- [ ] Upgrade all AI Fabric artifacts through the BOM to the same released version.
- [ ] Add `ai-fabric-execution` only to applications that define specialists.
- [ ] Keep current Mode and action behavior under regression tests.
- [ ] Declare deployment action and vector inventory.
- [ ] Register specialists as immutable Spring beans.
- [ ] Derive trusted context from authentication, never request JSON.
- [ ] Keep P0/P1 specialists read-only.
- [ ] Validate grounding before typed output projection.
- [ ] Validate final model decisions against authoritative application facts.
- [ ] Return safe evidence and sanitized failures.
- [ ] Decide explicitly whether conversation memory is needed.
- [ ] Treat `submit` as ephemeral.
- [ ] Run P0/P1 and consumer tests with zero skips; report pre-existing
      key-gated provider suites and performance benchmarks separately.
- [ ] Run a packaged Docker smoke and a keyed provider smoke.

## 8. Rollback

An adopting application can remove its execution endpoints and the optional
module, then continue using legacy orchestration.

Do not add a runtime switch that bypasses the governed action invoker. If a
shared invocation regression is found, correct it in a framework patch release.

## 9. Not Yet Approved

Do not migrate write workflows into specialists yet. P0/P1 does not provide a
durable, profile-pinned specialist write receipt. Continue using existing
governed action and confirmation flows until the separate P1.1 design is
implemented and approved.
