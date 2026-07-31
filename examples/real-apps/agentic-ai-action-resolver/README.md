# Agentic AI Action Resolver

This independent reference app proves AI Fabric's configurable, bounded
specialist execution model with one governed write:

```text
manifest specialists:
  account-resolver@1
  account-resolver-read@1
  account-resolution-coordinator@1
  account-resolution-intake@1
  billing-resolution-advisor@1
  account-conversation-manager@1
  account-resolver-manager-read@1
  billing-resolution-manager-advisor@1
  support-credit-proposer@1
read actions: get_account_profile, assess_billing_resolution
write proposals: update_address, request_refund
evidence: account-resolution-policy
```

The model may diagnose the current account and propose an address update. It
cannot authorize, confirm, or directly execute that update. AI Fabric and the
application create an identity-bound durable receipt, require an explicit user
decision, revalidate authority, and execute the registered application action
at most once.

The billing specialist proves a different boundary: required factual input can
pause an invocation before provider orchestration, return a typed JSON response
contract, and safely resume the same invocation after the host supplies the
missing value. This is not action confirmation and does not create a refund or
account credit.

The app also proves fixed sequential composition without changing the original
Account Resolver demo. `account-readiness@1` is a one-step parity plan.
`account-billing-resolution@1` runs the read-only account specialist and then
the billing advisor, passing only the validated typed account result through a
registered mapper. Missing billing amount pauses step two; resume does not
rerun step one.

The app also compares two equivalent independent-read plans.
`account-billing-independent-sequential@1` invokes the account and billing
specialists in order. `account-billing-independent-parallel@1` invokes the
same exact-version, read-only specialists concurrently and atomically joins
them with `ALL_REQUIRED`. Both use the same typed request, branch mappers, and
deterministic Java aggregator.

It also proves one-level model-selected delegation without exposing an open
specialist catalogue. `account-resolution-coordinator@1` may select only
`account-resolver-read@1` or `billing-resolution-advisor@1` from a closed
manifest enum and delegation allowlist. The application maps the validated
request to typed child input; AI Fabric rechecks source version, depth,
deadline, target declaration, target type, and backend authority before the
child runs through the normal execution gateway.

The app separately proves explicit responsibility handoff.
`account-resolution-intake@1` may select the same two exact-version read-only
specialists through an independent `handoff` policy. AI Fabric records
predecessor/successor lineage and treats the successor as the relationship
outcome; it does not return the result for intake continuation, transfer a
conversation, or relabel the transition as delegation.

It also proves proactive, read-only intelligence from a raw application event.
A payment-verification failure is mapped to `account-resolver-read@1` and
submitted asynchronously under a backend-owned service principal with
`ExecutionSource.EVENT`. No chat turn or fabricated user message is created.

The support-credit specialist adds a cross-request human-review boundary. A
real provider proposes one governed billing action, the application places its
receipt into a durable review task, and a separately authenticated reviewer
may approve, reject, correct, request information, or escalate it. The model
cannot choose or impersonate the reviewer.

The original `ai-fabric-account-resolver` remains unchanged and deployable as
the governed-action baseline.

## What This Proves

- A typed `AccountResolutionRequest` enters through a schema-bound
  `SpecialistClient` over `AIExecutionGateway`.
- `billing-resolution-advisor@1` binds a registered input continuation and
  returns `WAITING_FOR_INPUT` when its billing amount is absent.
- Its host-facing wait result contains a safe question, exact response schema,
  expiry, attempt limit, delivery target, and explicit `EPHEMERAL` durability.
- Resume is bound to the original invocation, principal, subject, tenant,
  deployment, execution source, specialist content hash, capability profile,
  response schema, and idempotency key.
- A malformed response remains visibly rejected without invoking the provider;
  an identical successful resume is replayed without repeating execution.
- Exact-version application plans, mappers, and aggregators are validated at
  startup against the pinned manifest schemas.
- One validated coordinator result may select one exact-version, read-only
  target from its closed manifest allowlist.
- Delegation reuses backend-owned authority, transfers no conversation, and
  independently authorizes the typed child through `AIExecutionGateway`.
- Identical scoped delegation replays in process; changed work under the same
  key conflicts. Delegation state is not durable across restart.
- One validated intake result may hand responsibility to one exact-version,
  read-only successor from an independent closed handoff allowlist.
- Handoff rechecks the predecessor content hash, target declaration, typed
  binding, deadline, depth, and target authority through the normal gateway.
- Handoff transfers no conversation, pending action, review state, or hidden
  model context; successor diagnostics use predecessor/successor lineage.
- Identical scoped handoff replays in process; changed work conflicts.
  Handoff state is not durable across restart.
- The bounded conversation-manager route gives one exact-version manager one
  frozen, backend-approved turn and only `ASK_USER`, `INVOKE_SPECIALIST`, or
  `COMPLETE`.
- It may select at most one of `account-resolver-manager-read@1` or
  `billing-resolution-manager-advisor@1`. Both workers are exact-version,
  read-only, conversation-isolated, and unable to wait for more input.
- The manager directive remains internal. The application records exactly one
  validated external question, completion, or projected worker result.
- Each plan step receives an independent effective-capability evaluation and
  no shared worker conversation.
- Completed plan steps are retained in a bounded `EPHEMERAL` checkpoint store;
  restart requires a new plan execution.
- A raw payment-verification event contains facts only; identity, subject,
  tenant, specialist, and authority are resolved by the backend.
- Typed asynchronous submit/status/cancel calls preserve the manifest input
  and output contract.
- Identical retained event delivery returns the original invocation without a
  second specialist execution; changed facts under the same event ID fail as
  `IDEMPOTENCY_CONFLICT`.
- Event execution is service-owned, `EVENT` sourced, read-only, and explicitly
  `DURABLE`.
- The validated request is encrypted and committed to JDBC before dispatch;
  the typed result, access binding, and replay decision survive restart.
- Expired worker leases can recover abandoned read-only work. This provides
  one durable terminal record, not exactly-once provider invocation.
- Server-created, database-backed session state binds the opaque browser
  session to the trusted principal, tenant, and current account subject.
- `account-resolver@1` is explicitly `DIALOGUE_CAPABLE`; ordinary workers and
  read-only successors remain non-interactive.
- `account-conversation-manager@1` is a dialogue-capable coordinator that
  defers recording; the manager gateway owns its one final conversation
  append.
- Manager input contains only the current typed question, bounded
  application-approved scalar context, and the closed target descriptions.
  The browser cannot supply history, target identity, authority, prompt,
  provider, Mode, or snapshot.
- The manager has no retrieval or action capabilities. A selected worker is
  independently authorized through the normal one-level delegation boundary.
- Dedicated manager workers are read-only, non-interactive,
  conversation-disabled, and unable to request input continuation.
- Exact manager replay returns the original external result without another
  model call, worker call, or chat append; changed payload under the same key
  fails with `MANAGER_IDEMPOTENCY_CONFLICT`.
- Direct interactive dialogue and manager dialogue share one active-turn
  lease, so they cannot append competing responses to one conversation.
- The browser sends only the latest typed question. The interactive gateway
  freezes one authorized, bounded backend-history snapshot for the turn.
- A stable chat idempotency key replays the original invocation without
  appending a duplicate turn; changed input under that key conflicts.
- Only one process-local turn may own a conversation at a time, while
  different conversations can proceed independently.
- `account-resolver@1` requests one Mode, one READ action, one WRITE proposal,
  and one vector space.
- Effective capabilities intersect specialist requests, Mode policy,
  deployment inventory, registered capabilities, and trusted authority.
- `get_account_profile` reads current application state.
- RAG retrieves only `account-resolution-policy` evidence.
- Read-only assessments return a validated `AccountResolutionResult`.
- An explicit, complete address request may return
  `CONFIRMATION_REQUIRED` with a safe `ActionProposalView`.
- Executable parameters are encrypted in JDBC and are never returned in the
  public receipt.
- Confirmation is bound to the original principal, subject, tenant,
  deployment, specialist version, profile hash, action schema, and
  idempotency fingerprint.
- `update_address` executes only through
  `GovernedActionInvocationService`.
- The application projects the authoritative handler result into a safe
  response without account IDs or raw address fields.
- Replays cannot execute a terminal receipt twice.
- Stale `EXECUTING` receipts become `OUTCOME_UNKNOWN`; they are never retried
  blindly.
- `support-credit-proposer@1` can create a governed `request_refund` proposal
  for later operational review.
- Review tasks and delivery receipts are committed separately in JDBC.
- Reviewer identity, tenant, and scopes are mapped by the backend rather than
  accepted in public JSON.
- Approval and rejection advance only the linked governed action receipt.
- Correction creates a successor proposal and task without rewriting history.
- Typed information exchange and senior escalation survive request boundaries.
- The next profile read observes the authoritative database update. Account
  PII is intentionally not copied into the policy vector index.

## Manifest-Defined Runtime

The complete deployment bundle is
[`src/main/resources/ai-specialists/account-resolver.yml`](src/main/resources/ai-specialists/account-resolver.yml).
It defines:

- exact-version input and output JSON schemas;
- separate read/write/coordinator prompt profiles;
- `account-resolver-read@1`, `account-resolver@1`,
  `account-resolution-coordinator@1`, and `billing-resolution-advisor@1`;
- Mode, execution strategy, requested vector/action capabilities, grounding
  requirements, explicit dialogue capability, conversation policy, and
  bounded limits; and
- stable references to approved application extensions.

No Java specialist declaration remains in the app. AI Fabric loads and
strictly validates the bundle at startup, calculates each definition's
canonical SHA-256 hash, compiles it to the normal execution path, and merges it
into the same immutable registry used by Java-defined specialists.

Java remains only where the application owns real domain behavior:

- `AccountReadinessProjection` compares authoritative account facts with
  policy requirements;
- `AccountResolverSpecialistExtensions` registers that projection plus the
  grounding and consistency validators by exact ID;
- existing action handlers own `get_account_profile` and `update_address`; and
- the action-level outcome projector owns the safe write result.

The manifest cannot choose a user, account, tenant, scope, credential,
provider endpoint, action implementation, or confirmation result.

The manager bundle is
[`src/main/resources/ai-specialists/account-conversation-manager.yml`](src/main/resources/ai-specialists/account-conversation-manager.yml).
It pins the manager input/directive schemas, manager prompt profile, closed
delegation targets, and two isolated worker variants. Application Java owns
the typed manager definition, safe context adapter, worker input mappers, and
external result projectors.

## Bounded Conversation Manager

Create a demo session, then send only the newest typed message:

```http
POST /api/agentic-resolver/manager/chat
X-AI-Fabric-Demo-Session: <server-created-session>
Idempotency-Key: manager-turn-1
Content-Type: application/json

{
  "question": "Why can I not place an order?"
}
```

For a complete informational billing assessment, the application may also
accept its existing typed fields:

```json
{
  "question": "What path would a 25 dollar account credit take?",
  "resolutionType": "ACCOUNT_CREDIT",
  "amount": 25
}
```

The public response exposes a safe status, message, selected exact target when
one ran, invocation lineage, snapshot revision, replay marker, and a safe
failure when applicable. It never exposes the model's internal directive,
prompt, raw provider response, trusted context, or transcript.

The manager manifest uses prompt profile version 4 and a closed semantic
category set: account state, billing assessment, supported follow-up, or
outside scope. Its `STRUCTURED_OUTPUT_ONLY` policy runs exactly one manager
structured-output model stage while retaining access, capability, validation,
and sanitization controls. It does not run a second ordinary intent model
call. If the manager selects the account reader, application code maps the
request to the worker's narrow current-account readiness task rather than
forwarding ambiguous conversation prose.

## Specialist Contract

Read input:

```json
{
  "question": "Review my current account against the policies. Can I place an order?"
}
```

Successful read output:

```json
{
  "assessment": "READY | BLOCKED | INSUFFICIENT_EVIDENCE",
  "summary": "Evidence-grounded explanation",
  "blockers": [
    {
      "requirement": "ACTIVE_SUBSCRIPTION | VERIFIED_PAYMENT_METHOD | VALIDATED_BILLING_ADDRESS | OTHER",
      "explanation": "Why the requirement is not met",
      "recommendedNextStep": "A user-facing next step"
    }
  ]
}
```

`READY` and `INSUFFICIENT_EVIDENCE` require an empty blocker list. `BLOCKED`
requires at least one complete blocker.

## Typed Input Continuation

The billing assessment accepts a resolution type and an optional amount:

```json
{
  "question": "What path would this refund take?",
  "resolutionType": "REFUND"
}
```

Because the amount is missing, AI Fabric stops before provider orchestration:

```json
{
  "status": "WAITING_FOR_INPUT",
  "needsUserInput": {
    "purposeCode": "MISSING_BILLING_AMOUNT",
    "safeQuestion": "What billing amount should be assessed?",
    "responseContract": {
      "schemaId": {
        "name": "billing-amount-response",
        "version": "1"
      },
      "schema": {
        "type": "object",
        "additionalProperties": false,
        "required": ["amount"],
        "properties": {
          "amount": {
            "type": "number",
            "exclusiveMinimum": 0,
            "maximum": 1000000
          }
        }
      }
    },
    "deliveryTarget": "HOST_APPLICATION",
    "durability": "EPHEMERAL",
    "maxAttempts": 3
  }
}
```

The host submits only the schema-valid response plus the returned request and
invocation IDs. AI Fabric reconstructs trusted context server-side, revalidates
the pinned specialist and authority, and resumes the same invocation.

The final assessment is informational. For example, the current demo policy
automatically approves a small account credit, while a refund above its
automatic threshold is routed to review. The registered read action supplies
those application-owned policy facts; RAG supplies policy evidence; neither
path executes a financial write.

Pending input waits are bounded process-local state. They do not survive an
application restart and must not be presented as durable workflow tasks.
Action receipts remain a separate JDBC-backed mechanism with different
semantics and guarantees.

## Proactive Event Execution

The app accepts this raw event:

```json
{
  "eventId": "payment-attempt-42",
  "failureCode": "DECLINED",
  "attemptNumber": 2,
  "occurredAt": "2026-07-29T10:00:00Z"
}
```

Supported failure codes are `DECLINED`, `EXPIRED`, `VERIFICATION_FAILED`, and
`PROVIDER_UNAVAILABLE`. The body deliberately has no user, account,
subscription, tenant, principal, scope, specialist, provider, or action
fields.

The application performs the trusted mapping:

```text
opaque demo session
  -> current account subject
  -> raw event validation
  -> deterministic AccountResolutionRequest
  -> SERVICE principal + ExecutionSource.EVENT + READ scopes
  -> account-resolver-read@1
  -> current profile READ + policy RAG + provider reasoning
  -> typed AccountResolutionResult
```

This path evaluates the event against current application state and policy
evidence. It cannot propose or execute `update_address`, mutate payment
details, create a conversation, or choose a different specialist.

The stable idempotency key is derived from the event contract version and
event ID. The durable repository preserves these behaviors across restart:

- an exact redelivery returns the same invocation ID;
- changed facts under that event ID return a rejected handle with
  `IDEMPOTENCY_CONFLICT`; and
- another session cannot inspect or cancel the invocation.

The validated request and typed result are encrypted at rest. JDBC query
columns contain keyed access and idempotency fingerprints rather than raw
principal, subject, tenant, or idempotency values. A worker owns a bounded
lease; startup and scheduled recovery reclaim queued work and read-only work
whose lease expired before a terminal result was committed.

This remains an at-least-once read execution model. A crash during a provider
call can cause the read-only analysis to run again after lease expiry, but the
repository retains one invocation and one terminal result. AI Fabric does not
own the event broker and does not claim exactly-once model invocation.

## Fixed Sequential Plans

The application registers two fixed plans in
`AccountResolverPlanConfiguration`:

```text
account-readiness@1
  account-state -> account-resolver-read@1

account-billing-resolution@1
  account-state -> account-resolver-read@1
  billing-path  -> billing-resolution-advisor@1
```

Plan definitions contain only exact IDs, application DTO classes, ordered
steps, registered mapper/aggregator references, and a maximum duration.
They do not carry identity, scopes, prompts, provider settings, model names,
actions, or vector-space grants.

The two-step mapper receives `AccountBillingResolutionPlanRequest` and the
declared `AccountResolutionResult` checkpoint. It creates a bounded
`BillingResolutionAssessmentRequest`; no transcript, raw evidence, account ID,
or trusted context is copied into model input. The deterministic aggregator
returns `AccountBillingResolutionPlanResult`.

The coordinator currently accepts `APPLICATION`, `EVENT`, and `SCHEDULED`
sources. Interactive plan execution and WRITE-capable plan steps fail closed
until dialogue ownership and durable composed-action continuation have
separate contracts.

## Bounded Read-Only Parallel Plan

The application explicitly opts into one narrow parallel shape:

```text
account-billing-independent-sequential@1
  account-state -> account-resolver-read@1
  billing-path  -> billing-resolution-advisor@1

account-billing-independent-parallel@1
  independent-readers [ALL_REQUIRED, maximumConcurrency=2]
    account-state -> account-resolver-read@1
    billing-path  -> billing-resolution-advisor@1
```

The two branch mappers depend only on the immutable plan request, not sibling
output. Startup validation rejects unknown or WRITE-capable specialists,
incompatible mapper types, sibling dependencies, duplicate IDs, disabled
parallel plans, and branch counts above the deployment ceiling.

AI Fabric pre-maps every branch before submission, uses its existing bounded
execution executor, applies the plan deadline, cancels outstanding siblings
after any failure, and commits branch checkpoints only after every branch
succeeds. There is no partial fan-in, retry under a different strategy, or
hidden sequential fallback.

Successful traces remain in declaration order even when completion order
differs. Each trace identifies its exact specialist and invocation, the
parallel group, and the shared source revision. The plan remains synchronous
and process-local; it is not a durable graph runtime.

The full framework adoption guide is
[`BOUNDED_READ_ONLY_PARALLEL_PLANS.md`](../../../docs/Framework-Dev-Guides/application-patterns/BOUNDED_READ_ONLY_PARALLEL_PLANS.md).

## One-Level Declared Delegation

The configuration-defined `account-resolution-coordinator@1` returns a
validated `AccountDelegationDecision`:

```json
{
  "decision": "DELEGATE",
  "targetSpecialist": "account-resolver-read@1",
  "reason": "The request asks about current account readiness."
}
```

The output schema and manifest allowlist contain the same two exact targets.
The model cannot enumerate the registry or invent a specialist. It also does
not provide account identity, authority, or arbitrary child input.

The application maps the original validated request to the selected child's
DTO and calls `SpecialistDelegationGateway`. The gateway requires a successful
current source result, depth zero, a declared registered read-only target, an
unexpired inherited deadline, and a valid typed child binding. It invokes the
child through `AIExecutionGateway` with the current backend-created trusted
context and no conversation.

Child waits and confirmations are unsupported and explicitly rejected. Replay
is scoped and payload-checked but process-local. See
[`ONE_LEVEL_SPECIALIST_DELEGATION.md`](../../../docs/Framework-Dev-Guides/application-patterns/ONE_LEVEL_SPECIALIST_DELEGATION.md)
for the complete adoption contract.

Write request example:

```json
{
  "question": "Update my billing address to 10 Downing Street, London, London, SW1A 2AA, GB."
}
```

The chat call does not execute the write. A valid proposal returns:

```json
{
  "status": "CONFIRMATION_REQUIRED",
  "actionProposal": {
    "receiptId": "action-receipt-...",
    "actionName": "update_address",
    "confirmationMessage": "Are you sure you want to update your billing address?",
    "status": "PROPOSED",
    "createdAt": "...",
    "expiresAt": "..."
  }
}
```

The public view deliberately contains no executable parameters or identity.

## Durable Human Review

`support-credit-proposer@1` is defined in
[`src/main/resources/ai-specialists/support-credit-review.yml`](src/main/resources/ai-specialists/support-credit-review.yml).
It retrieves the approved refund/credit policy and asks AI Fabric to create one
`request_refund` proposal. The application then chooses
`support-credit-review@1`; neither user text nor model output can choose that
policy.

```text
real OpenAI proposal
  -> governed action receipt
  -> application-selected review policy
  -> encrypted JDBC task before dispatch
  -> local inbox dispatch receipt
  -> backend-authenticated regular or senior reviewer
  -> approve | reject | correct | request information | escalate
  -> current policy, authority, source, and action revalidation
  -> governed action path or non-mutating terminal result
```

The standard policy supports all five decisions. The senior policy supports
approval or rejection and requires an additional senior-review scope.
Separation of duty prevents the original initiator from deciding the task.

Correction rejects the original action receipt and creates a new receipt plus
one successor review. Information responses are accepted only from the
original server-owned demo session. Escalation leaves the action untouched and
creates one task visible only to the senior reviewer.

The full framework adoption and migration guide is
[`DURABLE_HUMAN_REVIEW.md`](../../../docs/Framework-Dev-Guides/application-patterns/DURABLE_HUMAN_REVIEW.md).

## Published Scenarios

Each public demo session receives isolated seeded data for three scenarios:

| Scenario | Trusted current state | Expected assessment |
| --- | --- | --- |
| `ready-account` | Active subscription, verified payment, validated address | `READY` |
| `missing-payment` | Active subscription and address, no verified payment | `BLOCKED` |
| `missing-address` | Active subscription and payment, no validated address | `BLOCKED`; eligible for an explicit `update_address` proposal |

Scenario descriptions are navigation aids, not model answers. The specialist
must read current profile facts and policy evidence for every assessment.
The opaque session-to-subject bindings are stored server-side alongside the
demo data, so a pending receipt can still be confirmed after an application
restart. A production application should resolve the same trusted context from
its authenticated identity and tenant boundary instead of copying this public
demo session mechanism.

## Request And Write Flow

```text
HTTP request with question only
  -> server-owned session resolves principal + account + tenant + scopes
  -> TrustedExecutionContext
  -> AIExecutionGateway
  -> account-resolver@1
  -> effective capability intersection
  -> AI Fabric orchestration
       -> access policy
       -> get_account_profile
       -> account-resolution-policy RAG
       -> OpenAI intent/generation
  -> read result
       -> structured output + grounding validation
       -> AccountResolutionResult
  -> write proposal
       -> typed parameter and action-schema validation
       -> application-owned confirmation preflight
       -> encrypted JDBC receipt
       -> CONFIRMATION_REQUIRED
  -> decision request with receiptId + decision only
       -> identity, authority, profile, schema, and expiry revalidation
       -> atomic PROPOSED -> CONFIRMED -> EXECUTING transition
       -> GovernedActionInvocationService
       -> application-owned update_address handler
       -> safe outcome projection
       -> SUCCEEDED | FAILED | OUTCOME_UNKNOWN
```

Interactive calls additionally use `ai-fabric-chat-session`. The
`AIInteractiveExecutionGateway` claims one turn, freezes recent authorized
messages, and passes an opaque one-use approval to the normal execution
gateway. The orchestration pipeline uses that frozen snapshot and records a
new turn only after validated projection. The browser sends only the new
question and cannot provide history or a snapshot. The application binds the
manifest-backed DTO types through `SpecialistClientFactory` and calls
`SpecialistClient.executeInteractive`, preserving typed input/output while the
manifest runtime validates its JSON schemas.

When the JDBC async repository is enabled, interactive submissions still use
the bounded process-local gateway. JDBC execution durability applies to
eligible machine-owned read jobs; conversation durability remains owned by
`ai-fabric-chat-session`.

## Security Boundary

Public requests have no `userId`, `subscriptionId`, `tenantId`, scopes, Mode,
specialist ID, action catalog, vector space, or write parameters in the
decision call. These values come from server-owned state and the encrypted
receipt.

Text such as "update account 94 instead" is untrusted model input and cannot
rebind the subject. A receipt from another session returns the same
not-available response as a missing receipt. A hostile prompt cannot bypass
the registered action contract or explicit confirmation.

Receipt persistence stores:

- HMAC fingerprints instead of raw principal, subject, tenant, and deployment
  IDs;
- AES-GCM protected parameters and projected outcomes;
- hashes of the action schema, parameters, effective profile, and evidence;
- state-transition timestamps and an optimistic version.

Do not log receipt payloads or expose the repository through a public API.

## API

Create an isolated session:

```http
POST /api/agentic-resolver/sessions
```

Read, select a scenario, or delete the session:

```http
GET /api/agentic-resolver/sessions/{sessionId}
PUT /api/agentic-resolver/sessions/{sessionId}/scenarios/{scenarioId}
DELETE /api/agentic-resolver/sessions/{sessionId}
```

Run a stateless read-only application call:

```http
POST /api/agentic-resolver/evaluate
X-AI-Fabric-Demo-Session: {sessionId}
Content-Type: application/json

{"question":"Can I place an order?"}
```

Let the coordinator select one approved read-only specialist:

```http
POST /api/agentic-resolver/delegate
X-AI-Fabric-Demo-Session: {sessionId}
Idempotency-Key: {stable-delegation-key}
Content-Type: application/json

{
  "question": "Review my current account and explain any order blockers."
}
```

For a billing-policy assessment, include the typed fields owned by the
application request:

```json
{
  "question": "What policy path applies to this account credit?",
  "resolutionType": "ACCOUNT_CREDIT",
  "amount": 25
}
```

The response contains the validated coordinator result and, when delegation
succeeds, the typed child execution with parent/child lineage.

Transfer responsibility from a validated intake to one approved read-only
successor:

```http
POST /api/agentic-resolver/handoff
X-AI-Fabric-Demo-Session: {sessionId}
Idempotency-Key: {stable-handoff-key}
Content-Type: application/json

{
  "question": "Review my current account and explain any order blockers."
}
```

For a billing-policy handoff, provide the same application-owned typed fields:

```json
{
  "question": "What policy path applies to this account credit?",
  "resolutionType": "ACCOUNT_CREDIT",
  "amount": 25
}
```

The response contains the validated predecessor and, when handoff succeeds,
the typed successor execution with predecessor/successor lineage. Handoff is
not a fixed plan step, delegation, or conversation transfer.

Submit a raw payment-verification failure:

```http
POST /api/agentic-resolver/events/payment-verification-failed
X-AI-Fabric-Demo-Session: {sessionId}
Content-Type: application/json

{
  "eventId":"payment-attempt-42",
  "failureCode":"DECLINED",
  "attemptNumber":2,
  "occurredAt":"2026-07-29T10:00:00Z"
}
```

Read or cancel its process-local execution:

```http
GET /api/agentic-resolver/events/executions/{invocationId}
DELETE /api/agentic-resolver/events/executions/{invocationId}
X-AI-Fabric-Demo-Session: {sessionId}
```

Use the same event ID and identical facts when redelivering an event. Reusing
the ID with changed facts returns a rejected execution handle rather than
silently reusing or replacing the first result.

Run an interactive call. This is the only path granted the write-proposal
scope:

```http
POST /api/agentic-resolver/chat
X-AI-Fabric-Demo-Session: {sessionId}
Idempotency-Key: {stable-client-request-id}
Content-Type: application/json

{"question":"Update my billing address to 10 Downing Street, London, London, SW1A 2AA, GB."}
```

`Idempotency-Key` is required for `/chat`, opaque, and limited to 200
characters. Retrying the same question with the same key returns the original
invocation and snapshot revision. Reusing that key with changed input returns
`IDEMPOTENCY_CONFLICT`.

Start a typed billing assessment:

```http
POST /api/agentic-resolver/billing-assessment
X-AI-Fabric-Demo-Session: {sessionId}
Idempotency-Key: {stable-client-request-id}
Content-Type: application/json

{
  "question": "What path would this refund take?",
  "resolutionType": "REFUND"
}
```

Resume the returned input request:

```http
POST /api/agentic-resolver/input/resume
X-AI-Fabric-Demo-Session: {sessionId}
Idempotency-Key: {stable-resume-idempotency-key}
Content-Type: application/json

{
  "invocationId": "exec-...",
  "requestId": "input-request-...",
  "response": {
    "amount": 75
  }
}
```

The public resume body cannot select a principal, subject, tenant, deployment,
specialist, capability, or action. A request from a different session receives
the same unavailable response as an unknown request.

`Idempotency-Key` is optional for the initial billing-assessment call, opaque,
and limited to 200 characters. Supply a stable value when retrying the same
initial request. A write proposal persists only an identity-scoped HMAC of
that value; reusing it for different proposal parameters fails closed.

The resume endpoint requires its own stable `Idempotency-Key`. The bounded
process-local wait state compares that key together with the canonical response
hash. An identical completed resume returns `REPLAYED`; different data for the
claimed request returns `INPUT_RESUME_CONFLICT`.

Run the one-step parity plan:

```http
POST /api/agentic-resolver/plans/account-readiness
X-AI-Fabric-Demo-Session: {sessionId}
Idempotency-Key: {stable-plan-start-key}
Content-Type: application/json

{"question":"Can the current account place an order?"}
```

Run the two-step plan:

```http
POST /api/agentic-resolver/plans/account-billing-resolution
X-AI-Fabric-Demo-Session: {sessionId}
Idempotency-Key: {stable-plan-start-key}
Content-Type: application/json

{
  "question":"Can this account receive a refund?",
  "resolutionType":"REFUND",
  "amount":75
}
```

Compare equivalent independent sequential and parallel plans:

```http
POST /api/agentic-resolver/plans/account-billing-independent-sequential
X-AI-Fabric-Demo-Session: {sessionId}
Idempotency-Key: {unique-sequential-key}
Content-Type: application/json

{
  "question":"Assess whether a 25 dollar account credit is appropriate.",
  "resolutionType":"ACCOUNT_CREDIT",
  "amount":25
}
```

```http
POST /api/agentic-resolver/plans/account-billing-independent-parallel
X-AI-Fabric-Demo-Session: {sessionId}
Idempotency-Key: {unique-parallel-key}
Content-Type: application/json

{
  "question":"Assess whether a 25 dollar account credit is appropriate.",
  "resolutionType":"ACCOUNT_CREDIT",
  "amount":25
}
```

Use the same session and complete input when comparing them. The output policy
fields should be equivalent. Parallel traces should share
`parallelGroupId=independent-readers` and one source revision, while their
time intervals overlap.

Omit `amount` to receive a plan-level `WAITING_FOR_INPUT`. Resume it without
selecting a specialist:

```http
POST /api/agentic-resolver/plans/input/resume
X-AI-Fabric-Demo-Session: {sessionId}
Idempotency-Key: {stable-plan-resume-key}
Content-Type: application/json

{
  "executionId":"plan-execution-...",
  "requestId":"input-request-...",
  "response":{"amount":75}
}
```

Inspect or cancel process-local plan state:

```http
GET /api/agentic-resolver/plans/executions/{executionId}
DELETE /api/agentic-resolver/plans/executions/{executionId}
X-AI-Fabric-Demo-Session: {sessionId}
```

Confirm or reject the durable receipt:

```http
POST /api/agentic-resolver/actions/decide
X-AI-Fabric-Demo-Session: {sessionId}
Content-Type: application/json

{"receiptId":"action-receipt-...","decision":"CONFIRM"}
```

The only accepted decisions are `CONFIRM` and `REJECT`.

Create a real provider-backed support-credit proposal and durable review:

```http
POST /api/agentic-resolver/reviews/support-credit
X-AI-Fabric-Demo-Session: {sessionId}
Idempotency-Key: {stable-review-proposal-key}
Content-Type: application/json

{
  "question":"Propose a support credit for the verified incident.",
  "resolutionType":"ACCOUNT_CREDIT",
  "amount":25,
  "reason":"Verified support incident"
}
```

List or inspect tasks with a backend-configured reviewer key:

```http
GET /api/agentic-resolver/reviews
X-AI-Fabric-Review-Key: {reviewerKey}

GET /api/agentic-resolver/reviews/{taskId}
X-AI-Fabric-Review-Key: {reviewerKey}
```

Submit a version-bound review decision:

```http
POST /api/agentic-resolver/reviews/{taskId}/decision
X-AI-Fabric-Review-Key: {reviewerKey}
Content-Type: application/json

{
  "decisionId":"review-decision-1",
  "decision":"APPROVE",
  "expectedVersion":0
}
```

`CORRECT` and `REQUEST_INFORMATION` also require a `response` object matching
their exact manifest schema. The original source session supplies requested
information through:

```http
POST /api/agentic-resolver/reviews/{taskId}/information
X-AI-Fabric-Demo-Session: {sessionId}
Content-Type: application/json

{
  "submissionId":"information-1",
  "expectedVersion":2,
  "response":{"incidentReference":"INC-2026-42"}
}
```

The public body never supplies reviewer identity, tenant, scopes, role,
dispatcher, recipient, action receipt, or confirmation authority.

Deployment and receipt readiness:

```http
GET /api/demo/health
```

The `execution` block reports the repository type, receipt TTL, stale
execution threshold, cleanup policy, retention, specialist source/ID/version/
hash, manifest runtime readiness and counts, registry content hash, and the
proactive event source, service-principal type, no-automatic-mutation policy,
and `DURABLE` durability. It also reports whether the JDBC async repository is
ready. It never exposes prompts, schemas, encryption/fingerprint secrets, or
user data.

## Run Locally

Build the framework modules and app with tests:

```bash
mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-execution -am install

mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl agentic-ai-action-resolver -am package
```

Offline smoke profile:

```bash
java -jar \
  examples/real-apps/agentic-ai-action-resolver/target/agentic-ai-action-resolver-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke
```

The offline provider cannot satisfy the full profile-plus-policy grounding
contract. A specialist read call must fail visibly rather than receiving a
fabricated answer. This is a packaged-runtime failure proof, not a replacement
for real-provider verification.

Real OpenAI:

```bash
export AI_EXECUTION_RECEIPT_ENCRYPTION_SECRET="<stable-secret-of-at-least-32-characters>"
export AI_EXECUTION_RECEIPT_FINGERPRINT_SECRET="<different-stable-secret-of-at-least-32-characters>"
export AI_EXECUTION_ASYNC_ENCRYPTION_SECRET="<third-stable-secret-of-at-least-32-characters>"
export AI_EXECUTION_ASYNC_FINGERPRINT_SECRET="<fourth-stable-secret-of-at-least-32-characters>"

OPENAI_ENABLED=true \
OPENAI_API_KEY="$OPENAI_API_KEY" \
OPENAI_MODEL=gpt-4o-mini \
OPENAI_TIMEOUT=60 \
PORT=8105 \
java -jar \
  examples/real-apps/agentic-ai-action-resolver/target/agentic-ai-action-resolver-1.0.0-SNAPSHOT.jar
```

Never commit provider, receipt, or durable-execution secrets.

The default profile uses file-backed H2 for a self-contained local proof. The
`prod` profile uses PostgreSQL. When selecting it, also provide:

- `DATABASE_URL=jdbc:postgresql://<host>:<port>/<database>`
- `DB_USERNAME=<database user>`
- `DB_PASSWORD=<database secret>`

## Input Wait Configuration

This app enables the framework's bounded process-local input-wait store:

```yaml
ai:
  execution:
    input-waits:
      enabled: true
      default-ttl: PT10M
      max-ttl: PT30M
      max-pending: 500
      max-attempts: 3
      max-requests-per-invocation: 3
      result-ttl: PT15M
```

`AI_EXECUTION_INPUT_WAIT_MAX_PENDING` may override the demo capacity. A shared
deployment must size the bound deliberately and expose expiry to its client.
The cap covers active waits and completed entries retained for idempotent
replay; expired replay entries are removed on the next store operation.
Restarting the process invalidates pending input request IDs; clients must
start a new specialist invocation.

The app also configures bounded process-local plan state:

```yaml
ai:
  execution:
    plans:
      enabled: true
      parallel-enabled: true
      max-parallel-branches: 2
      max-steps: 4
      max-duration: PT1M
      max-active: 500
      result-ttl: PT15M
```

Plan state is separate from chat history, pending specialist input, and durable
action receipts. It checkpoints only the original typed input, validated step
outputs, safe evidence references, status, timing, and access binding needed
for same-process continuation.

The bounded manager uses its own process-local replay retention:

```yaml
ai:
  execution:
    conversation-managers:
      enabled: true
      max-duration: PT1M
      max-active: 500
      result-ttl: PT15M
```

Its active-turn lease and approved history snapshot are shared with direct
interactive dialogue. Its retained replay result is separate from chat turns
and is lost after its TTL or process restart.

## Durable Async Configuration

The proactive event path uses the framework's separate durable read-job
repository:

```yaml
ai:
  execution:
    async:
      repository: JDBC
      initialize-schema: true
      lease-duration: PT2M
      recovery-interval: PT30S
      recovery-batch-size: 50
      max-attempts: 3
      cleanup-enabled: true
      retention: P30D
      encryption-secret: ${AI_EXECUTION_ASYNC_ENCRYPTION_SECRET}
      fingerprint-secret: ${AI_EXECUTION_ASYNC_FINGERPRINT_SECRET}
```

`initialize-schema=true` is convenient for this self-contained demo.
Production applications should set it to `false` and own the table through
Flyway or Liquibase. Both async secrets must contain at least 32 characters,
must differ, and must remain stable across restarts and replicas.

The lease should cover a normal bounded specialist call. If a process crashes
during a provider request, recovery may repeat that read-only request after
lease expiry. Terminal provider, grounding, and validation failures are stored
and are not automatically retried.

This repository is separate from pending input waits, fixed-plan checkpoints,
chat history, and governed action receipts. It stores only eligible
service/system-owned, read-only terminal specialist jobs.

## Receipt Configuration

Required for every shared or production deployment:

- `AI_EXECUTION_RECEIPT_ENCRYPTION_SECRET`: stable, at least 32 characters.
- `AI_EXECUTION_RECEIPT_FINGERPRINT_SECRET`: different stable secret, at least
  32 characters.

Operational controls:

- `AI_EXECUTION_RECEIPT_TTL=PT10M`
- `AI_EXECUTION_STALE_EXECUTION=PT2M`
- `AI_EXECUTION_RECOVERY_INTERVAL=PT1M`
- `AI_EXECUTION_RECOVERY_BATCH_SIZE=100`
- `AI_EXECUTION_RECEIPT_CLEANUP_ENABLED=true`
- `AI_EXECUTION_RECEIPT_RETENTION=P30D`
- `AI_EXECUTION_RECEIPT_INITIALIZE_SCHEMA=true` for app-managed schema creation

The framework default leaves cleanup disabled. This demo opts into 30-day
terminal receipt retention. `OUTCOME_UNKNOWN` is never deleted by automatic
cleanup because it requires reconciliation.

The app declares `@EnableScheduling`. Startup recovery always runs when the
receipt service is created; scheduling is also required for periodic recovery,
demo-session cleanup, and optional terminal receipt cleanup.

The current protected-payload format supports one active key pair. Keep both
receipt secrets stable across restarts and replicas. Before rotating either
secret, drain or expire confirmable receipts and reconcile unknown outcomes.
Changing a key while active receipts remain makes their identity or encrypted
payload unverifiable.

## Durable Review Configuration

Required when `ai.execution.reviews.enabled=true`:

- `AI_EXECUTION_REVIEW_ENCRYPTION_SECRET`: stable and at least 32 characters.
- `AI_EXECUTION_REVIEW_FINGERPRINT_SECRET`: a different stable secret.
- `APP_REVIEWER_API_KEY`: demo mapping for a regular reviewer.
- `APP_SENIOR_REVIEWER_API_KEY`: demo mapping for a senior reviewer.

The two review secrets must also differ from every action-receipt and durable
job secret. This self-contained app enables JDBC schema initialization in its
smoke and production-demo profiles. A production adoption should set
`AI_EXECUTION_REVIEW_INITIALIZE_SCHEMA=false` after installing the migration
from the durable human-review guide.

Review task retention defaults to 90 days in this app. Cleanup removes
dispatch history before its retained terminal task. Waiting and deciding tasks
are never removed by retention cleanup.

## Docker

Build from the repository root. Tests run in both framework and app build
stages:

```bash
docker build \
  -f examples/real-apps/agentic-ai-action-resolver/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.5.2 \
  --build-arg BUILD_COMMIT="$(git rev-parse HEAD)" \
  --build-arg BUILD_BRANCH="$(git branch --show-current)" \
  --build-arg BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  -t agentic-ai-action-resolver:source \
  .
```

Run with durable local data. The volume holds application state, opaque demo
session bindings, action receipts, durable specialist jobs, chat sessions, and
the local vector index. It deliberately does not make pending input waits or
fixed-plan checkpoints durable:

```bash
docker volume create agentic-resolver-data

docker run --rm -p 8105:8105 \
  -v agentic-resolver-data:/app/data \
  -e PORT=8105 \
  -e OPENAI_ENABLED=true \
  -e OPENAI_API_KEY="$OPENAI_API_KEY" \
  -e OPENAI_MODEL=gpt-4o-mini \
  -e AI_EXECUTION_RECEIPT_ENCRYPTION_SECRET="$AI_EXECUTION_RECEIPT_ENCRYPTION_SECRET" \
  -e AI_EXECUTION_RECEIPT_FINGERPRINT_SECRET="$AI_EXECUTION_RECEIPT_FINGERPRINT_SECRET" \
  -e AI_EXECUTION_ASYNC_ENCRYPTION_SECRET="$AI_EXECUTION_ASYNC_ENCRYPTION_SECRET" \
  -e AI_EXECUTION_ASYNC_FINGERPRINT_SECRET="$AI_EXECUTION_ASYNC_FINGERPRINT_SECRET" \
  -e AI_EXECUTION_REVIEW_ENCRYPTION_SECRET="$AI_EXECUTION_REVIEW_ENCRYPTION_SECRET" \
  -e AI_EXECUTION_REVIEW_FINGERPRINT_SECRET="$AI_EXECUTION_REVIEW_FINGERPRINT_SECRET" \
  -e APP_REVIEWER_API_KEY="$APP_REVIEWER_API_KEY" \
  -e APP_SENIOR_REVIEWER_API_KEY="$APP_SENIOR_REVIEWER_API_KEY" \
  -e CORS_ALLOWED_ORIGINS=https://ai-fabric.dev \
  agentic-ai-action-resolver:source
```

For Coolify source deployment:

- repository: `Loom-AI-Labs/ai-fabric-framework.git`
- branch: `main`
- base directory: `/`
- Dockerfile: `/examples/real-apps/agentic-ai-action-resolver/Dockerfile`
- exposed port: `8105`
- persistent storage: mount at `/app/data`, or use the `prod` PostgreSQL profile
- include source commit in build: enabled

## Failure And Recovery Semantics

- Provider, retrieval, grounding, schema, policy, persistence, and domain
  failures remain visible as typed non-success results.
- A proactive event never falls back to a fabricated assessment. Provider or
  grounding failure remains visible on its typed execution snapshot.
- The durable request is committed before dispatch. Identical event
  redelivery replays the original handle across restart; changed facts under
  the same event ID fail with `IDEMPOTENCY_CONFLICT`.
- Event status and cancellation require the same trusted execution binding,
  and a proactive read never mutates the account.
- Queued jobs and lease-expired read jobs are recovered after restart.
  Deadlines become terminal `EXPIRED`; exhausted attempts become terminal
  `FAILED`.
- A terminal provider failure is persisted and never automatically retried.
  Definition-hash drift, protected-payload verification failure, and typed
  payload incompatibility fail closed.
- A crash during a provider call can repeat the read operation after lease
  expiry. The terminal record is singular, but provider invocation is not
  claimed as exactly once.
- `WAITING_FOR_INPUT` and `CONFIRMATION_REQUIRED` are unsupported durable-job
  outcomes in this release and fail visibly rather than creating fake durable
  continuation state.
- Missing specialist input returns `WAITING_FOR_INPUT`, not a generated guess,
  action confirmation, or fabricated fallback.
- Invalid input responses are rejected against the pinned schema and remain
  retryable only within the bounded attempt limit.
- Expired, unauthorized, content-changed, or authority-changed resumes fail
  closed without revealing whether another session owns the request.
- An identical completed resume returns `REPLAYED`; it does not invoke the
  provider pipeline or application read action again.
- Proposal persistence failure returns
  `ACTION_RECEIPT_PERSISTENCE_FAILED`; no receipt is claimed and no action runs.
- Decision-time receipt-store failure returns `RECEIPT_STORE_UNAVAILABLE`; no
  new execution starts.
- An explicit application rejection returned as a failed `ActionResult`
  becomes terminal `FAILED` with an application-safe projected result.
- If action invocation throws after `EXECUTING`, the receipt becomes
  `OUTCOME_UNKNOWN`.
- If authoritative completion cannot be persisted, the response also reports
  `OUTCOME_UNKNOWN`. Recovery never re-executes that action.
- Startup and scheduled recovery expire stale proposals and convert abandoned
  `EXECUTING` receipts into `OUTCOME_UNKNOWN`.
- Unknown outcomes require an application-authoritative reconciliation.
- Replaying a successful, failed, rejected, expired, or unknown receipt returns
  its terminal state without executing it again.
- Review tasks are committed before dispatch and use separate delivery
  receipts. Delivery acceptance is never treated as approval.
- A repeated exact review decision returns the stored result. A different
  reviewer, task version, decision, or response conflicts without executing.
- Expired reviewer leases recover the same protected decision. Exhausted
  attempts become a visible terminal failure.
- Review expiry leaves the linked action proposal untouched. Rejection retires
  it without a domain mutation; correction creates a successor.

Disable specialist writes for rollback by removing `update_address` from the
specialist/deployment/authority scopes or by setting
`ai.execution.receipts.enabled=false` together with disabling the write
capability. Do not drop the receipt table during rollback; retain it for audit
and reconciliation.

## Observability

When Micrometer is available, the module publishes:

```text
ai.fabric.execution.action.receipts
ai.fabric.specialist.manifest.load
ai.fabric.specialist.manifest.validation
ai.fabric.specialist.registry.definition.count
ai.fabric.specialist.execution.by.source
```

Receipt tags are bounded to `event`, registered `action`, and receipt
`status`. Specialist tags contain bounded source/result/reason values.

## Verification

Focused framework:

```bash
mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-execution -am test
```

Focused app:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl agentic-ai-action-resolver -am test
```

Clean packaged app:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl agentic-ai-action-resolver -am clean package
```

For release proof, compare the SHA-256 digest of
`BOOT-INF/lib/ai-fabric-execution-<version>.jar` in the executable app with the
verified artifact in the local Maven repository. A non-clean package is not
sufficient evidence after changing framework dependencies.

Regression proof for the unchanged baseline:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl ai-fabric-account-resolver -am test
```

No verification command for this feature uses `-DskipTests`.

The final manager gate passed:

- 1,044 framework tests: 5 curated-default, 677 core, 59 chat-session, and 303
  execution tests;
- 12 shared smoke tests and 135 app tests in a clean package;
- packaged/local core SHA-256
  `b2543edcc887209513060e4ec0d4246fcfa2ecc524296bc4e79dd319ffd0c9a4`;
- packaged/local execution SHA-256
  `82271608dc71598365a2c8b56d5e8fa3697d58bf965b3d2fc8bf31067d47d6a1`;
- real OpenAI account-read, billing, clarification, unsupported completion,
  backend-history follow-up, exact replay, and changed-payload conflict
  scenarios; and
- visible provider/validation failures with no deterministic success fallback.

The bounded parallel-plan gate then passed:

- 1,052 framework tests: 5 curated-default, 677 core, 59 chat-session, and 311
  execution tests;
- 12 shared smoke-support tests and 138 app tests in a clean package;
- packaged/local core SHA-256
  `b2543edcc887209513060e4ec0d4246fcfa2ecc524296bc4e79dd319ffd0c9a4`;
- packaged/local execution SHA-256
  `feb2c49dad6a404aab57ca92903a6d97090da69012c2e4f99decc48fc68870b0`;
- packaged health proof for the enabled two-branch `ALL_REQUIRED` topology;
- three real OpenAI runs per strategy with identical typed policy output,
  stable declaration-order traces, and a common source revision; and
- measured averages of 15.511 seconds sequentially and 6.699 seconds in
  parallel for the documented complete account-credit request.

## Intentionally Out Of Scope

- automatic LLM confirmation;
- direct model-to-handler execution;
- unrestricted model-authored planning or specialist discovery;
- recursive delegation or handoff;
- dialogue-owner transfer, manager loops, manager-selected writes, a second
  manager synthesis call, or durable manager/delegation/handoff state;
- conditional, nested/dynamic, WRITE-capable, or durable parallel plans;
- event or scheduled write adapters;
- framework-owned event-broker consumption or scheduler ownership;
- durable WRITE-capable specialist jobs;
- blind retries for unknown write outcomes;
- durable or cross-process specialist input waits;
- durable chat confirmation or composed-plan continuation;
- exactly-once provider invocation;
- multi-question input collection;
- a public reconciliation endpoint;
- vectorizing account PII.

These require separate contracts and evidence. They are not silently simulated
by this reference app.
