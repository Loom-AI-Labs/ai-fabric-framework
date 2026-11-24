# Goal Execution Contract (010.76)

Implementation contract for Runtime integration. Unreleased local development
version 0.8.70; no tag, publication, deployment or infrastructure change.

## Core

Package `ai.fabric.intent.orchestration.workflow`:

```java
record WorkflowGoalPolicy(boolean enabled, Duration maxElapsed,
    Duration continuationDelay, int maxConsecutiveNoProgress,
    int maxConsecutivePlannerFailures, List<String> delegatedProfiles) {}
// WorkflowPolicy gains final WorkflowGoalPolicy goal; six-argument constructor retained.
// WorkflowPolicy.goalMode() tests the opt-in.
// WorkflowCheckpoint gains WorkflowGoalProgress goal; usage() stays cumulative.
// State adds YIELDED and PAUSED.
// WorkflowAllowance.segmentUsage() subtracts persisted segmentStartUsage.
WorkflowCheckpoint BoundedWorkflowRunner.resumeGoal(Request request,
    WorkflowPolicy policy, Planner planner, Actions actions);
```

`WorkflowGoalProgress` persists segment number/start/deadline/start usage,
next eligible time, consecutive structural non-progress/planner failures and
active delegation. Request deadline is the root lifetime. Segment deadlines
and counters refresh only on a new segment, never cumulative counters or IDs.
Eligible YIELDED work continues through `run`; PAUSED needs explicit resume.
Authenticated `resumeGoal` also reopens NEEDS_INPUT with a fresh segment, using
the host's verified directive revision without replacing the immutable root input.

```yaml
execution:
  strategy: BOUNDED_ITERATIVE
  workflow:
    enabled: true
    maxActions: 8
    maxWrites: 3
    maxModelCalls: 16
    maxCorrections: 4
    timeout: PT120S
    goal:
      enabled: true
      maxElapsed: PT24H
      continuationDelay: PT1S
      maxConsecutiveNoProgress: 8
      maxConsecutivePlannerFailures: 4
      delegatedProfiles: []
```

No small root-total call/write/repair limit. Finite lifetime, existing funding,
provider admission, cancellation and authority remain mandatory per operation.

## Durable Gateway

Package `ai.fabric.execution.gateway`:

```java
record GoalExecutionRequest(AIExecutionRequest<?> request, String authorityReference) {}
enum GoalExecutionControl { PAUSE, RESUME, CANCEL }
record GoalExecutionClaim(String invocationId, String authorityReference,
    String leaseOwner, Instant deadline, int attemptCount, boolean resumeRequested) {}
interface DurableGoalExecutionHost {
    AIExecutionRequest<?> prepareSubmission(GoalExecutionRequest request);
    AIExecutionResult<?> executeSegment(GoalExecutionClaim claim,
        AIExecutionRequest<?> persistedRequest,
        Function<AIExecutionRequest<?>, AIExecutionResult<?>> execute);
    default void executionUpdated(ExecutionSnapshot snapshot, String authorityReference) {}
}
// Added to AIExecutionGateway; unsupported defaults fail closed.
ExecutionHandle submitGoal(GoalExecutionRequest request);
boolean controlGoal(String invocationId, GoalExecutionControl command,
    TrustedExecutionContext context);
Optional<WorkflowCheckpoint> findWorkflowCheckpoint(String invocationId,
    TrustedExecutionContext context);
```

`AIExecutionStatus` and `ExecutionHandleStatus` add YIELDED and PAUSED.
Existing `find` exposes handle and protected segment result. Ordinary APIs
retain bounded behavior. The optional host SPI bean is required for goal admission.
YIELDED and PAUSED results carry no final output and need not contain a failure.
Goal NEEDS_INPUT is a PAUSED handle with a NEEDS_INPUT checkpoint and actual
questions in workflow diagnostics; it is not a failed strict-schema output.

The host checks its persisted server-owned authority reference, canonical
conversation/control epoch, release and sources on every claim. It reconstructs
the short-lived lease/snapshot/ThreadLocal binding around `execute.apply` and
cleans it afterward. Preparation strips transient assertions, browser secrets
and process-local conversation snapshots: persisted request conversation binding
must be null, subject must be present. Input contains trusted work/source refs.
Restoration may not change specialist, principal, tenant/deployment or scopes.
The host owns canonical Pause/Resume/Cancel, history erasure and final-message
projection. SDK checks pinned definition and durable lease/CAS on every segment.

Additive existing-table fields: `goal_authority_reference VARCHAR(512) NULL`,
`next_eligible_at TIMESTAMP NULL`, and
`goal_notification_pending BOOLEAN NOT NULL DEFAULT FALSE`.
Existing encrypted `protected_workflow_checkpoint` holds progress and receipts.
The existing durable scheduler claims eligible YIELDED work and releases paused
leases. A successful yield does not consume the crash-recovery attempt budget.
No new queue/table, Platform outer retry, or serialized browser credentials.
The authority reference is also bound inside the encrypted request. Native input
remains exactly schema-valid and immutable; host-only work records are looked up
by authority/subject/conversation, not smuggled through additional input fields.

`executionUpdated` runs after the durable segment/control transition commits.
Delivery is at least once; the existing recovery scheduler retries the persisted
pending notification, including terminal results. Runtime must finalize the
canonical outcome idempotently by invocation ID. Notification/status reads make
no inference calls. `findWorkflowCheckpoint` is protected host-state access,
including after pause/completion; never forward its private parameters or raw
observations to a public endpoint. Normal goal results include a bounded recent
step window with `stepOffset`, `totalStepCount`, and `hasMore`.

`Authority.resumeGoal()` defaults false. Return true only for an authenticated
Resume (`claim.resumeRequested()`), never because a model asks for more budget.
`Authority.directiveContext()` returns `Map<String, Object>` (empty by default).
Runtime supplies its verified current request revision/query and selected work/
source references here. Goal planning includes `directiveContext`; the original
input hash, root authority and operation namespace do not change. The host must
fence stale revisions and recheck current authorization; model text is not a grant.
The durable root deadline uses published `goal.maxElapsed`, not specialist
`limits.maxDuration`; `workflow.timeout` still bounds every segment.

## One-Level Delegation

```java
record WorkflowDelegation(String profileId, String task,
    List<String> workReferences, List<String> sourceReferences) {}
record WorkflowDelegationProfile(String profileId, String definitionFingerprint,
    String instructions, List<String> allowedActions) {}
// DecisionType adds DELEGATE; Decision gains optional WorkflowDelegation delegation.
// Existing Decision constructor retained.
// WorkflowExecution.Authority defaults:
List<WorkflowDelegationProfile> delegatedProfiles(); // empty
void verifyDelegation(WorkflowDelegation delegation); // denies by default
void verifyDelegatedAction(WorkflowDelegation delegation, String action,
    Map<String, Object> parameters); // denies by default
CompletionFinding validateDelegatedCompletion(WorkflowCheckpoint checkpoint,
    WorkflowDelegation delegation); // null by default
```

Runtime resolves descriptors from pinned published profiles and verifies typed
source/work references. The model adopts that profile's instructions in the same
runner, narrowed to profile actions AND root effective capabilities. No nested
delegation. Root funding/cancellation/counters/step IDs stay shared. A child's
COMPLETE/NEEDS_INPUT/UNSUPPORTED becomes a receipt for the parent planner, not
automatic parent termination. Ordinary read-only SpecialistChain is untouched.
Root completion retains `Authority.validateCompletion`, including valid no-ops.

## Safe Diagnostics

`AIGenerationResponse.providerOutcome` and `StructuredJsonFailure.providerOutcome`
carry optional `ProviderOutcome`: call purpose, model, effective output cap,
provider request ID, finish reason, input/output/reasoning token counts,
returned-content size and transport classification. Unknown values remain null.
An optional `retryAfterMillis` carries explicit provider backoff. The original
eleven-argument constructor remains available. `ProviderOutcomeException` in
`ai.fabric.llm.structured` carries this safe observation across wrapped transport
exceptions, without provider bodies. `ADMISSION_DENIED` pauses immediately;
`CALL_ERROR` observations yield with durable backoff and eventually pause.
No raw exception bodies, credentials, or private reasoning. Empty, truncation,
schema/validation and transport failures stay distinct. Compact recovery context
goes through the same configured model loop. Segment corrections yield;
repeated structural non-progress/unavailable planning pauses operationally.

## Local Qualification

24 November 2026, source candidate 0.8.70 (not published):

- Focused workflow/gateway/checkpoint/structured-call install: 106 tests,
  zero failures or errors. Both compiled-manifest direct and structured output
  paths cross segments and resume verified directives with unchanged native input.
- Final ordinary framework reactor: all 32 projects succeeded; 2759 tests,
  zero failures, zero errors, four explicit live-provider opt-in skips.
  This includes the added missing-definition retirement regression, real
  PostgreSQL lease-transition contracts and H2 durable goal/checkpoint tests.
- Release guards and whitespace checks passed. The 0.8.70 artifacts are installed
  locally for explicit unreleased-framework integration; 0.8.69 artifacts were
  not replaced.
- Root usage beyond two corrections/three writes/sixteen model calls, stable
  operation IDs, receipt reconciliation, control fencing/late settlement,
  retry-after backoff, structural no-progress, profile narrowing/recursion denial,
  no-op completion, bounded observation windows and finalization-only continuation
  have focused coverage. Ordinary SpecialistChain restrictions are unchanged.

Final ordinary-reactor command (all ordinary modules; separate integration-test
reactors and paid/live provider qualification excluded):

```sh
env -u LOOMAI_LEXICAL_LIVE_CONFIG AI_FABRIC_REAL_API_RECOVERY=false \
  mvn -B --no-transfer-progress -Dai.vector-db.lucene.cleanup-on-close=true \
  -pl '!integration-Testing/vector-contract-tests,!integration-Testing/testcontainers-support,!integration-Testing/integration-tests,!integration-Testing/relationship-query-integration-tests,!integration-Testing/chat-session-integration-tests,!integration-Testing/behavior-integration-tests' \
  install
```

Model transports in the new goal tests are scripted, not live-provider evidence.
Public CI, immutable publication, Central-only consumer verification, Runtime/
Platform adoption and live qualification remain separate release gates.
