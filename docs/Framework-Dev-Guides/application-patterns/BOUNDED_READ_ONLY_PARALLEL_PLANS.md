# Bounded Read-Only Parallel Plans

AI Fabric supports one opt-in, bounded fan-out/fan-in stage for independent
read-only specialists inside an application-selected fixed plan.

Use it when two or more typed assessments:

- read the same immutable application request or earlier checkpoint;
- do not consume sibling output;
- are already safe and correct as sequential specialist calls; and
- have a measured latency reason to run concurrently.

Do not use it for model-generated workflows, dependent branches, interactive
conversation owners, input collection, WRITE proposals, partial success, or
durable workflow execution.

## Runtime Boundary

```text
trusted typed request
  -> application selects exact registered plan
  -> map every branch before submission
  -> bounded concurrent exact-version READ specialists
  -> independent capability and authority checks
  -> ALL_REQUIRED validation
  -> atomic branch checkpoint
  -> registered deterministic Java aggregator
  -> one typed result
```

Parallelism changes scheduling only. It does not change specialist authority,
provider routing, retrieval policy, grounding, validation, evidence
projection, or aggregation ownership.

## Public Contracts

An execution plan contains `PlanStage` values:

```java
public sealed interface PlanStage
    permits SpecialistPlanStep, ParallelPlanStep {
    String id();
}
```

The first parallel contract is deliberately narrow:

```java
new ParallelPlanStep(
    "independent-readers",
    List.of(accountBranch, billingBranch),
    FanInPolicy.ALL_REQUIRED,
    2
)
```

`ALL_REQUIRED` means:

- every branch must succeed with its declared output type;
- no branch output is checkpointed until all branches pass;
- one failure skips aggregation and fails the plan visibly; and
- outstanding siblings are cancelled.

There is no `BEST_EFFORT`, `QUORUM`, model arbitration, or hidden sequential
fallback.

## Application Definition

Register an equivalent sequential control before adopting parallel execution:

```java
@Bean
ExecutionPlanDefinition<Request, Result> sequentialPlan() {
    return new ExecutionPlanDefinition<>(
        ExecutionPlanId.of("independent-assessment-sequential", "1"),
        Request.class,
        Result.class,
        List.of(accountBranch(), billingBranch()),
        PlanComponentId.of("assessment-result", "1"),
        Duration.ofSeconds(45)
    );
}

@Bean
ExecutionPlanDefinition<Request, Result> parallelPlan() {
    return new ExecutionPlanDefinition<>(
        ExecutionPlanId.of("independent-assessment-parallel", "1"),
        Request.class,
        Result.class,
        List.of(new ParallelPlanStep(
            "independent-readers",
            List.of(accountBranch(), billingBranch()),
            FanInPolicy.ALL_REQUIRED,
            2
        )),
        PlanComponentId.of("assessment-result", "1"),
        Duration.ofSeconds(45)
    );
}
```

Both plans should use the same:

- exact specialist IDs and versions;
- input and output DTOs;
- registered `PlanStepInputMapper` implementations;
- registered `PlanResultAggregator`; and
- trusted application context.

This makes output and latency comparisons meaningful.

## Branch Independence

Each branch mapper declares the earlier step outputs it needs through
`requiredStepOutputs()`.

A parallel branch may depend on a checkpoint completed before its parallel
stage. It may not depend on a sibling in the same stage. AI Fabric rejects
sibling dependencies during plan registration.

Keep identity, tenant, subject, scopes, credentials, provider choice, and
action authority out of mapper output. The execution gateway resolves those
from backend-owned `TrustedExecutionContext` for every branch.

## Configuration

Parallel plans are disabled by default:

```yaml
ai:
  execution:
    plans:
      enabled: true
      parallel-enabled: false
      max-parallel-branches: 4
      max-steps: 8
      max-duration: PT2M
      max-active: 1000
      result-ttl: PT15M
```

Enable the capability only in an application that registers a reviewed
parallel plan:

```yaml
ai:
  execution:
    plans:
      parallel-enabled: true
      max-parallel-branches: 2
```

The group `maximumConcurrency` and branch count must fit the deployment
ceiling. The shared execution executor must also have enough capacity:

```yaml
ai:
  execution:
    async:
      core-pool-size: 2
      max-pool-size: 4
      queue-capacity: 24
```

Parallel plans reuse `aiFabricExecutionTaskExecutor`. Do not create another
unbounded pool for plan branches.

## Startup Validation

Plan registration fails when:

- parallel execution is disabled;
- a group contains fewer than two branches;
- group concurrency cannot cover its declared branches;
- the branch or flattened-step ceiling is exceeded;
- a stage or branch ID is duplicated;
- a specialist or input mapper is unknown;
- exact input or output types do not match;
- a specialist may propose a WRITE;
- a branch depends on a sibling; or
- the plan duration exceeds the deployment maximum.

The registered content hash includes stage topology, fan-in policy,
concurrency, exact specialist content, mapper identity, dependencies, DTO
types, aggregator, and duration.

## Execution And Failure Semantics

Before submission, AI Fabric maps every branch from the same immutable plan
state. A mapping failure invokes no branch.

During execution:

- each specialist runs through its normal typed client and execution gateway;
- one plan deadline bounds the complete stage;
- completion order may differ from declaration order;
- input waits and action confirmations fail as unsupported;
- executor rejection remains visible;
- interruption restores the caller thread's interrupt status; and
- cancellation attempts to interrupt all outstanding siblings.

Successful results are validated and committed atomically in declaration
order. A late sibling may still finish at a remote provider after local
cancellation, so branches must remain read-only. AI Fabric does not claim
exact cancellation of an external model request.

The first store is `EPHEMERAL`. Restart loses active and retained plan state.
It does not inherit durability from JDBC read jobs, action receipts, review
tasks, or chat history.

## Trace And Operations

Each `PlanStepTrace` contains:

- declared branch ID;
- parallel group ID;
- common source revision;
- exact specialist ID and version;
- independent invocation ID;
- safe evidence;
- terminal status; and
- start and completion timestamps.

Traces are returned in declaration order. Compare timestamps to prove actual
overlap.

Monitor:

- plan success, failure, cancellation, and deadline rates;
- branch latency and provider throttling;
- executor saturation and rejection;
- total model and retrieval work per request;
- sequential versus parallel output equivalence; and
- end-to-end latency percentiles.

Parallel calls can reduce latency while increasing simultaneous provider load.
Keep the sequential route available for rollback until production evidence is
stable.

## Verification Checklist

Before deployment, prove:

1. the packaged app starts with the intended topology and content hash;
2. the feature remains disabled in applications that do not opt in;
3. equivalent sequential and parallel plans return the same policy-relevant
   typed output;
4. parallel branch timestamps overlap;
5. traces retain declaration order and one common source revision;
6. one failed or timed-out branch produces no committed partial output;
7. aggregation runs exactly once only after complete success;
8. WRITE-capable specialists and sibling dependencies fail at startup;
9. input waits, confirmations, executor rejection, and provider failures stay
   visible; and
10. at least three warm real-provider runs show enough latency value to
    justify concurrent provider work.

The executable reference is
[`agentic-ai-action-resolver`](../../../examples/real-apps/agentic-ai-action-resolver).
Its independent account and billing plans provide equivalent sequential and
parallel routes over the same two specialists and deterministic aggregator.
