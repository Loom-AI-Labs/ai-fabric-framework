# AI Fabric Execution

`ai-fabric-execution` is the optional application-execution layer for bounded AI
specialists. It lets an application call the same governed AI Fabric pipeline
from a typed Java operation or an interactive conversation.

It does not introduce a second orchestration engine. A specialist selects an
existing Mode and requests a closed set of actions and vector spaces. AI Fabric
intersects that request with:

1. Mode policy;
2. registered deployment capabilities;
3. trusted caller authority; and
4. the action registry.

Only the resulting effective capability profile reaches retrieval, model
prompts, tool callbacks, or final action invocation.

## Add The Module

The module is not part of `ai-fabric-starter`. Add it explicitly:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-execution</artifactId>
  <version>${ai-fabric.version}</version>
</dependency>
```

The application must also provide the normal AI Fabric orchestration, provider,
and optional RAG/chat modules needed by its specialist.

## Define A Specialist

Most specialists should use versioned startup manifests. Keep the Java
definition path for strongly typed specialists and genuinely new domain
behavior.

### Manifest Path

Enable startup loading:

```yaml
ai:
  execution:
    manifests:
      enabled: true
      fail-fast: true
      locations:
        - classpath*:ai-specialists/*.yml
```

A bundle defines exact-version input/output schemas, a prompt profile, and one
or more specialists. AI Fabric validates and compiles each manifest into the
same immutable registry used by Java definitions. No specialist database or
second execution path is introduced.

The packaged public resources are:

```text
META-INF/ai-fabric/specialist-resource-v1.schema.json
META-INF/ai-fabric/examples/support-knowledge-specialist.yml
```

See
[`SPECIALIST_MANIFEST_AUTHORING_GUIDE.md`](../../docs/Framework-Dev-Guides/application-patterns/SPECIALIST_MANIFEST_AUTHORING_GUIDE.md)
for the full contract, trusted authoring catalogue, extension points,
schema-bound client, diagnostics, and deployment checks.

### Java Path

Register a `SpecialistDefinition<I, O>` bean. The definition contains:

- a versioned `SpecialistId`;
- a bounded objective and prompt overlay;
- an existing AI Fabric Mode;
- requested actions and vector spaces;
- local duration, input, grounding, and evidence limits;
- a typed input adapter; and
- a typed output adapter.

```java
@Bean
SpecialistDefinition<AccountRequest, AccountAssessment> accountResolver() {
    RequestedCapabilityProfile requested = new RequestedCapabilityProfile(
        true,
        Set.of("account-policy"),
        Set.of("get_account_profile"),
        Set.of("get_account_profile"),
        Set.of()
    );

    return new SpecialistDefinition<>(
        new SpecialistIdentity(
            SpecialistId.of("account-resolver", "1"),
            "Account Resolver",
            "Evaluates the current account against approved policy evidence"
        ),
        new SpecialistInstructions(
            "Determine whether the current account can continue.",
            "Never use identity supplied in model input. Never execute a write."
        ),
        new SpecialistExecutionProfile(
            "resolver",
            requested,
            ExecutionStrategy.BOUNDED_ITERATIVE,
            SpecialistWritePolicy.DISABLED
        ),
        SpecialistLimits.defaults(),
        accountInputAdapter(),
        accountOutputAdapter()
    );
}
```

The registry rejects invalid definitions during startup, including:

- duplicate specialist IDs;
- unknown Modes;
- unknown or deployment-denied actions;
- write actions on a read-only specialist;
- retrieval without an explicit vector-space scope; and
- unregistered vector spaces.

## Build Trusted Context On The Server

Never accept initiator, subject, tenant, deployment, or scopes from an HTTP
request body. Derive them from authenticated application state:

```java
TrustedExecutionContext trusted = TrustedExecutionContext.application(
    "account-service",
    new ExecutionSubjectRef("account", authenticatedAccountId),
    authenticatedTenantId,
    Set.of(
        "specialist:account-resolver@1",
        "action:get_account_profile",
        "vector:account-policy"
    )
);
```

`APPLICATION`, `EVENT`, and `SCHEDULED` calls require a service or system
principal. `INTERACTIVE` calls require an end-user principal.

## Execute

```java
AIExecutionResult<AccountAssessment> result = gateway.execute(
    AIExecutionRequest.synchronous(
        SpecialistId.of("account-resolver", "1"),
        new AccountRequest("Can this account continue?"),
        trusted
    )
);
```

A successful result contains:

- the typed application output;
- safe `AIEvidenceReference` values;
- bounded diagnostics; and
- invocation and specialist correlation.

A non-success result contains a typed status and sanitized
`AIExecutionFailure`. Provider, retrieval, policy, grounding, and output
validation failures are not replaced with deterministic answers.

## Fixed Sequential Plans

Applications may compose registered read-only specialists into an immutable
ordered plan. This is useful when one validated specialist result is required
as typed input to the next specialist:

```java
@Bean
ExecutionPlanDefinition<AccountRequest, BillingPath> billingPlan() {
    return new ExecutionPlanDefinition<>(
        ExecutionPlanId.of("account-billing", "1"),
        AccountRequest.class,
        BillingPath.class,
        List.of(
            new SpecialistPlanStep(
                "account-state",
                SpecialistId.of("account-reader", "1"),
                AccountRequest.class,
                AccountAssessment.class,
                PlanComponentId.of("account-input", "1")
            ),
            new SpecialistPlanStep(
                "billing-path",
                SpecialistId.of("billing-advisor", "1"),
                BillingRequest.class,
                BillingAssessment.class,
                PlanComponentId.of("billing-input", "1")
            )
        ),
        PlanComponentId.of("billing-result", "1"),
        Duration.ofSeconds(45)
    );
}
```

The application also registers exact-version `PlanStepInputMapper` and
`PlanResultAggregator` beans. Startup fails for unknown references, type or
schema mismatches, future-step dependencies, duplicate IDs, excessive plans,
or WRITE-capable specialists.

Every step uses a cached `SpecialistClient` binding and independently traverses
`AIExecutionGateway`. A mapper sees only the original typed plan input and its
declared predecessor outputs. It never receives trusted identity, authority,
provider clients, prompts, or raw evidence.

```java
PlanExecutionResult<BillingPath> result = coordinator.execute(
    PlanExecutionRequest.synchronous(
        ExecutionPlanId.of("account-billing", "1"),
        request,
        trustedApplicationContext
    )
);
```

If an active child returns `WAITING_FOR_INPUT`, the coordinator checkpoints
completed predecessors and resumes only that child. Plan status, cancellation,
resume, and replay are bound to the original principal, subject, source,
tenant, and deployment.

The initial plan store is bounded and explicitly `EPHEMERAL`. It does not
survive restart, support WRITE-capable steps, branch, run in parallel, choose
specialists dynamically, or own an interactive conversation.

## Conversation Memory

Typed application execution does not read or write chat history by default.
To use backend-owned memory, the application may provide an authorized
`ConversationBinding`:

```java
new AIExecutionRequest<>(
    specialistId,
    input,
    trustedInteractiveContext,
    new ConversationBinding(authenticatedUserId, serverConversationId),
    deadline,
    idempotencyKey
)
```

The gateway uses `READ_ONLY` pipeline persistence while the specialist runs.
It records the user and assistant turn only after grounding, structured output,
domain validation, and normalization have succeeded. The browser sends only
the new message.

## Structured Output

An output adapter may use:

- `DIRECT_PROJECTION` when the pipeline already returns the domain shape; or
- `STRUCTURED_GENERATION` for a final schema-constrained provider call.

For structured generation:

1. `validateGrounding` proves sufficient and correctly scoped evidence;
2. the provider returns the requested JSON contract;
3. `validate` checks the domain shape;
4. `validateFinalOutput` checks the decision against authoritative facts; and
5. `normalizeFinalOutput` may project already validated facts into an
   application-owned public representation.

Normalization must never repair an invalid model decision or invent missing
facts.

## Configuration

```yaml
ai:
  execution:
    enabled: true
    capabilities:
      registered-vector-spaces:
        - account-policy
      allowed-actions:
        - get_account_profile
    async:
      core-pool-size: 2
      max-pool-size: 4
      queue-capacity: 32
      result-ttl: PT15M
    plans:
      enabled: true
      max-steps: 8
      max-duration: PT2M
      max-active: 1000
      result-ttl: PT15M
```

`registered-vector-spaces` and `allowed-actions` are deployment boundaries, not
model hints. A specialist cannot expand them.

## Submission Semantics

`submit` is bounded and explicitly `EPHEMERAL`:

- work and results are held in memory;
- queue capacity is bounded;
- duplicate live idempotency keys are rejected;
- deadlines are enforced;
- terminal results are retained for the configured TTL; and
- restart loses queued, running, and retained executions.

Do not describe P0/P1 submission as durable, resumable, exactly-once, or a
workflow engine.

## Evidence Boundary

Execution results expose `AIEvidenceReference`, not `RAGDocument` or
`AIIndexDocument`.

`AIEvidenceReference` is a safe read-side projection containing an evidence ID,
content, score, source, URL, vector space, and allowlisted metadata.
`AIIndexDocument` remains a write-side indexing queue payload.

Strict projection denies an execution if evidence has no resolvable vector
space or falls outside the effective profile. Dropping the reference after
generation would be unsafe because the answer may already have used it.

## Governed Specialist Writes

A specialist may optionally propose an allowlisted registered WRITE. The model
does not authorize, confirm, or directly execute it. AI Fabric validates the
candidate against the effective capability profile and current action schema,
then creates an identity-bound durable receipt.

The application exposes a decision endpoint containing only `receiptId` and
`CONFIRM` or `REJECT`. Confirmation re-resolves trusted identity, subject,
authority, specialist version, profile, and action metadata before the receipt
can move atomically into execution through
`GovernedActionInvocationService`.

Production receipts use the conditional JDBC repository. Parameters and
projected outcomes are protected, terminal decisions are idempotent, stale
executions become `OUTCOME_UNKNOWN`, and unknown writes are reconciled against
the application system of record rather than retried blindly.

See
[`GOVERNED_SPECIALIST_WRITES_AND_RECEIPTS.md`](../../docs/Framework-Dev-Guides/actions-governance/GOVERNED_SPECIALIST_WRITES_AND_RECEIPTS.md)
for configuration, migrations, recovery, metrics, and rollback guidance.

## Current Boundary

The implemented scope supports bounded single-specialist execution, optional
confirmation-gated writes, and fixed sequential read-only plans. General
execution submitted through `submit` and sequential plan checkpoints remain
explicitly ephemeral; durable write receipts do not turn either path into a
durable workflow engine.

The following remain deferred:

- conditional, parallel, dynamic, or WRITE-capable plans;
- delegation and model-selected specialist routing;
- durable execution;
- scheduler or event-broker adapters; and
- unrestricted model-selected specialist discovery.

See the independent reference app:

```text
examples/real-apps/agentic-ai-action-resolver
```

## Verification

```bash
mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-execution -am test
```

Tests run normally. Do not use `-DskipTests`.
