# Typed Specialist Input Wait And Safe Resume Implementation Plan

- **Status:** Complete
- **Date:** 2026-07-29
- **Framework baseline:** AI Fabric `0.4.0`
- **Code reviewed at:** `0aeb2e9c`
- **Prerequisite:** Plans `0001` through `0003`
- **Target:** P2.1 of the agentic-enablement roadmap; version not assigned
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## 1. Purpose

Add a first-class, typed pause/resume boundary for a specialist that lacks one
required factual input.

The specialist must stop before provider orchestration, return a bounded
`NeedsUserInput` result, and resume the same invocation only after the host
application supplies a schema-valid response with trusted current context.

This is not:

- action confirmation;
- action-parameter drafting;
- human review or approval;
- free-form chat clarification;
- a general workflow engine; or
- durable cross-process execution state.

The P2.1 proof is deliberately same-process and in memory. Durable waits,
restart recovery, fixed sequential plans, parallel branches, review tasks, and
input-request consolidation remain later plans.

## 2. Code-Backed Starting Point

| Current code | Consequence for this plan |
| --- | --- |
| `AIExecutionGateway` exposes execute, submit, find, and cancel but no resume operation | Add one canonical typed resume operation. |
| `AIExecutionResult` distinguishes success, confirmation, denial, and failure | Add `WAITING_FOR_INPUT` and a mutually exclusive `NeedsUserInput` payload. |
| `SpecialistInputAdapter` validates and renders application input | Add an optional continuation extension at this boundary so incomplete input never reaches the provider pipeline. |
| `SpecialistInputSpec` and `JsonSchemaSpecialistInputAdapter` compile manifest input contracts | Allow a manifest to reference one exact-version input-continuation extension. |
| `SpecialistJsonSchemaRegistry` already validates exact-version Draft 2020-12 input schemas with external references disabled | Reuse registered input schema resources as typed resume-response contracts. Do not invent a second schema engine. |
| `RegisteredSpecialist.contentHash` and `EffectiveCapabilityProfile.profileHash` already pin specialist semantics and effective capability scope | Store and revalidate both values before resume. |
| `DefaultAIExecutionGateway` performs policy and capability resolution before provider orchestration | Resolve and pin authority before entering the wait, then resolve again and require the same effective profile on resume. |
| `EphemeralExecutionStore` explicitly owns process-local async state | Add a separate bounded pending-input store; do not overload action receipts, pending actions, or chat history. |
| `ActionProposalCoordinator` revalidates trusted identity, specialist content, current authority, and profile hashes after confirmation | Apply the same fail-closed pattern to input resume without reusing action-confirmation records. |
| `agentic-ai-action-resolver` already proves manifest specialists, current-account facts, policy RAG, governed writes, and typed clients | Add a separate read-only billing assessment that waits for an amount and leaves the existing account/read/write demonstrations unchanged. |

## 3. Product Boundary

### P2.1 in this plan

- one specialist invocation may wait for one typed factual response;
- the host receives a safe question plus exact JSON response schema;
- the wait is bound to invocation, specialist, trusted principal, subject,
  tenant, deployment, execution source, specialist content, and effective
  capability profile;
- resume validates current identity, current authority, current specialist
  content, schema, expiry, attempt limit, and idempotency;
- only the waiting invocation resumes;
- the original provider pipeline has not run before the wait;
- repeated identical resume requests return the previously produced result;
- conflicting, malformed, expired, or unauthorized resumes fail visibly; and
- storage is bounded and explicitly `EPHEMERAL`.

### P2.2 and P3 excluded

- fixed sequential or branching plans;
- completed-step checkpointing;
- multiple waiting branches;
- compatible-request consolidation;
- JDBC/JPA pending-input persistence;
- process-restart resume;
- durable human review;
- event-triggered continuation; and
- UI ownership or delivery adapters.

## 4. Canonical Public Contracts

Add these contracts under `ai.fabric.execution.input` and
`ai.fabric.execution.gateway`.

### `SpecialistInputContinuation<I>`

An application-registered, exact-version extension:

```java
public interface SpecialistInputContinuation<I> {
    String id();
    Class<I> inputType();
    Set<SpecialistSchemaId> responseSchemas();
    Optional<SpecialistInputRequirement> requiredInput(I input);
    I resume(
        I originalInput,
        SpecialistInputRequirement requirement,
        JsonNode response
    );
    default I snapshot(I input) { return input; }
}
```

The extension decides only whether its typed application input is incomplete
and how a validated response is merged into a new input value. It does not
authorize capabilities, invoke providers, append chat turns, execute actions,
or choose a network destination.

Manifest-backed JSON continuations must deep-copy input snapshots and return a
new JSON value on resume.

### `SpecialistInputRequirement`

Contains:

- stable bounded purpose code;
- safe host/user-facing question;
- exact registered input schema ID;
- positive bounded wait TTL; and
- positive bounded response-attempt limit.

The requirement carries no trusted identity, action authority, endpoint, raw
evidence, prompt, or credential.

### `NeedsUserInput`

Contains:

- request ID and invocation ID;
- specialist ID;
- purpose code and safe question;
- exact response schema ID and schema document;
- delivery target derived by the framework (`DIALOGUE_OWNER` or
  `HOST_APPLICATION`);
- `EPHEMERAL` durability;
- creation/expiry times; and
- maximum response attempts.

It is a validated execution outcome, not an assistant message.

### `AIExecutionResumeRequest`

Contains:

- expected specialist ID;
- invocation ID;
- request ID;
- untrusted JSON response;
- trusted current execution context; and
- required resume idempotency key.

The request body must never be allowed to construct the trusted context.

### `AIExecutionResumeResult<O>`

Separates resume-operation status from specialist execution status:

- `RESUMED`;
- `REPLAYED`;
- `REJECTED`;
- `DENIED`;
- `EXPIRED`; or
- `IN_PROGRESS`.

A successful/replayed operation carries the resulting
`AIExecutionResult<O>`. A rejected operation carries only a safe
`AIExecutionFailure`. This avoids making a malformed answer look like a
terminal failure of an invocation that is still waiting.

## 5. Manifest And Extension Model

Extend `SpecialistInputSpec` with:

```yaml
input:
  schemaRef: billing-resolution-request@1
  continuationRef: billing-resolution-input@1
```

Rules:

1. the reference is optional;
2. it must use exact `name@version` syntax;
3. startup fails when the extension is absent;
4. the extension input type must match the compiled adapter input type;
5. every response schema advertised by the extension must exist as an INPUT
   `SpecialistSchema` resource;
6. a runtime requirement may reference only an advertised schema;
7. the extension ID participates in Java-specialist fingerprints; and
8. the authoring catalog lists registered input continuations.

No class name, script, expression, SQL, HTTP endpoint, or arbitrary bean lookup
is accepted from the manifest.

## 6. Runtime Flow

### Initial invocation

1. Resolve exact registered specialist and validate conversation policy.
2. Verify the adapter input type.
3. Ask the optional continuation whether factual input is missing.
4. If complete, follow the existing execution path unchanged.
5. If incomplete, validate the requirement and registered response schema.
6. Run policy resolution and current-authority capability resolution without
   invoking the provider pipeline.
7. Snapshot the input through the registered continuation.
8. Store bounded pending state with:
   - invocation and request IDs;
   - original request and explicit deadline;
   - trusted access binding;
   - specialist content hash;
   - effective profile hash;
   - continuation ID;
   - response schema and version;
   - purpose, expiry, attempts, and idempotency state;
   - original start time; and
   - latest safe result.
9. Return `WAITING_FOR_INPUT` with `NeedsUserInput`.

### Resume

1. Look up by request and invocation without revealing existence to an
   unauthorized context.
2. Match principal, subject, tenant, deployment, and execution source.
3. Validate expected specialist, expiry, optimistic state, and idempotency.
4. Verify the specialist remains registered with the same content hash.
5. Verify the same continuation and response schema are registered.
6. Validate the untrusted response against the pinned Draft 2020-12 schema.
7. Merge it through the continuation into a new typed input snapshot.
8. Re-enter the same gateway invocation ID.
9. Re-run input validation, policy resolution, and current authority.
10. Require the same effective-profile hash. Changed or narrowed authority
    fails closed; newly granted authority cannot widen the waiting invocation.
11. Execute the existing provider/RAG/action pipeline once.
12. Record terminal result or another permitted bounded input wait.
13. Return the same result for an identical idempotent retry.

## 7. State And Security Rules

### Dedicated state

Create `EphemeralInputWaitStore`. It must not reuse:

- `PendingActionStore`;
- chat turns or `ChatSessionStorageProvider`;
- `ActionProposalReceiptRepository`; or
- behavior/event storage.

Those stores have different meanings and lifecycle guarantees.

### Bounded memory

Add `ai.execution.input-waits` configuration:

```yaml
ai:
  execution:
    input-waits:
      enabled: false
      default-ttl: PT15M
      max-ttl: PT1H
      max-pending: 1000
      max-attempts: 3
      max-requests-per-invocation: 3
      result-ttl: PT15M
```

The store cleans expired/retained entries on access and creation, counts both
active waits and replay-retained terminal entries against its capacity,
rejects new waits at capacity, and never logs response values.

### Trusted access binding

Status, cancellation, and resume require current trusted context and compare:

- principal ID and type;
- subject type and ID;
- tenant;
- deployment; and
- execution source.

Correlation ID, authentication time, and raw scope set are not identity. Scope
is re-resolved through the current authority path and compared through the
pinned effective-profile hash.

### Failure privacy

Wrong principal, tenant, subject, deployment, specialist, invocation, or
unknown request returns a generic unavailable/denied response. It must not
reveal which field matched or whether a request exists.

## 8. Async Compatibility

`submit(...)` may also reach `WAITING_FOR_INPUT`.

Update process-local async state so:

- `ExecutionHandleStatus.WAITING_FOR_INPUT` is non-terminal;
- status snapshots contain the same validated wait result;
- context-scoped `find` and `cancel` protect async execution state;
- resume transitions a waiting async handle back to `RUNNING`; and
- final completion updates the original handle.

The P2.1 reference app uses synchronous execute/resume, but the gateway must
not produce an incoherent async state.

## 9. Reference App Proof

Add a separate manifest-backed read-only specialist:

```text
billing-resolution-advisor@1
```

Input:

```json
{
  "question": "What path would this refund take?",
  "resolutionType": "REFUND",
  "amount": null
}
```

Behavior:

1. `amount` missing returns `NeedsUserInput` with purpose
   `MISSING_BILLING_AMOUNT`.
2. The response contract accepts only a positive bounded decimal amount.
3. Resume merges the amount into a new immutable input.
4. The provider pipeline invokes a registered READ action that assesses the
   billing-resolution threshold from application policy.
5. Policy RAG provides explanatory evidence.
6. A deterministic projector returns a typed assessment such as
   `AUTOMATIC` or `REVIEW_REQUIRED`.
7. No refund is created, no WRITE is proposed, and no confirmation is implied.

Expose:

```text
POST /api/agentic-resolver/billing-assessment
POST /api/agentic-resolver/input/resume
```

Both derive trusted current account/session context in the backend. Neither
accepts identity, tenant, scopes, or subject from request JSON.

## 10. Tests

### Framework contracts

- `AIExecutionResult` enforces mutually exclusive success, confirmation,
  wait, and failure payloads.
- requirement/question/schema/TTL/attempt bounds reject invalid values.
- resume-operation result invariants reject ambiguous states.
- response schemas remain defensive copies.

### Manifest runtime

- valid continuation reference compiles;
- unknown or malformed reference fails startup;
- duplicate extension ID fails startup;
- input-type mismatch fails startup;
- undeclared/missing/non-input response schema fails;
- schema and authoring catalog expose the new extension safely; and
- existing manifests without continuations remain unchanged.

### Gateway and state

- provider pipeline is not called before missing input is supplied;
- valid response resumes the same invocation exactly once;
- output uses the resumed value;
- malformed response leaves the request waiting within the attempt limit;
- attempt exhaustion closes the wait;
- expired request cannot resume;
- wrong principal, subject, tenant, deployment, source, invocation, or
  specialist fails closed;
- changed specialist content or effective profile cannot resume;
- identical duplicate resume returns the existing result;
- conflicting duplicate is rejected;
- cancellation closes only the authorized waiting invocation;
- status lookup is context scoped;
- max-pending capacity is enforced;
- process restart has no wait state; and
- async handles transition through `WAITING_FOR_INPUT`.

### Reference app

- typed client binding validates new input/output schemas;
- missing amount returns the expected typed contract;
- valid amount resumes to authoritative assessment;
- the READ action receives the resumed amount;
- no WRITE action or receipt is produced;
- session A cannot resume session B;
- controller never trusts identity fields from JSON;
- packaged JAR starts in smoke profile; and
- a real OpenAI run proves wait, resume, READ facts, policy evidence, and typed
  final output.

## 11. Verification Commands

Run tests normally:

```bash
mvn -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-execution -am install
mvn -f examples/real-apps/pom.xml \
  -pl agentic-ai-action-resolver -am test
mvn -f examples/real-apps/pom.xml \
  -pl agentic-ai-action-resolver -am package
```

Then start the packaged app with the smoke profile and exercise the HTTP
wait/resume flow. Finally run the same flow with the configured real OpenAI
provider without printing or storing credentials.

## 12. Acceptance Gate

P2.1 is complete only when:

1. no provider call occurs before required factual input is supplied;
2. `NeedsUserInput` is typed, bounded, safe, and distinct from confirmation;
3. only the exact authorized invocation resumes;
4. current authority and pinned content/profile/schema are revalidated;
5. duplicate resume cannot duplicate provider or application work;
6. state is bounded and honestly reported as process-local;
7. existing specialists and action-draft behavior remain green;
8. framework and real-app tests pass without skipped tests;
9. packaged-runtime smoke passes; and
10. real OpenAI verification passes with visible failures and no fallback.

## 13. Implementation Outcome

Implemented on 2026-07-29:

- public typed wait and resume contracts under `ai.fabric.execution.input` and
  `ai.fabric.execution.gateway`;
- bounded `EphemeralInputWaitStore` with expiry, capacity, attempt, result
  retention, cancellation, and idempotent replay behavior; active and
  replay-retained entries share the same hard capacity bound;
- trusted access binding across principal, subject, tenant, deployment, and
  execution source;
- specialist content, continuation, schema, and effective-profile pinning;
- synchronous and asynchronous `WAITING_FOR_INPUT` gateway behavior;
- manifest `continuationRef`, strict registry validation, authoring-catalog
  exposure, and definition fingerprinting;
- typed `SpecialistClient.resume(...)`;
- Boot configuration under `ai.execution.input-waits`;
- `billing-resolution-advisor@1`, `billing-resolution-input@1`,
  `billing-amount-response@1`, and the read-only
  `assess_billing_resolution` proof; and
- host-owned HTTP start/resume endpoints in the independent Agentic AI Action
  Resolver app.

The response schema is exposed as an immutable JSON-compatible
`Map<String, Object>`, not a Jackson tree type. This keeps the public contract
portable across the Jackson version used by Spring Boot while schema parsing
and validation remain internal and structured.

Verification completed:

- framework dependency reactor: 885 tests, zero failures, zero errors, zero
  skipped;
- clean real-app reactor package: 102 tests, zero failures, zero errors, zero
  skipped;
- packaged smoke runtime starts and reports healthy;
- real OpenAI missing-amount request returned `WAITING_FOR_INPUT` before
  provider orchestration;
- cross-session resume returned `DENIED/INPUT_REQUEST_UNAVAILABLE`;
- malformed owner response returned retryable
  `REJECTED/INPUT_RESPONSE_INVALID`;
- valid `$75` refund resume returned `RESUMED`, `REVIEW_REQUIRED`, and
  `PENDING_REVIEW` with indexed policy evidence;
- identical resume returned `REPLAYED` with the existing terminal result; and
- a complete `$25` account-credit request skipped the wait and returned
  `AUTO_APPROVED/APPROVED` with indexed policy evidence.

No fallback or text-matching branch supplies these decisions. The provider
performs intent, planning, and generation; registered application code owns
the billing facts and deterministic safe projection.

The accepted limitation remains explicit: pending input waits are process
local. A restart invalidates them. Durable cross-process input and human-review
tasks require the dedicated P3 persistence plan and must not reuse JDBC action
receipts or chat-session storage.

## 14. Explicit Follow-Up

After this plan is approved:

1. plan `0005`: fixed acyclic sequential specialist plans using deterministic
   typed mappers and decision steps;
2. prove that a waiting second step does not rerun a completed first step;
3. plan P3 durable pending-input/review state using a dedicated JPA/JDBC
   repository and optimistic versioning; and
4. only then evaluate parallel branches, consolidated questions, durable
   review, schedules, and proactive triggers.
