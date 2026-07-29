# Agentic AI Action Resolver

This independent reference app proves AI Fabric's configurable, bounded
specialist execution model with one governed write:

```text
manifest specialists:
  account-resolver@1
  account-resolver-read@1
  billing-resolution-advisor@1
read actions: get_account_profile, assess_billing_resolution
write proposal: update_address
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

It also proves proactive, read-only intelligence from a raw application event.
A payment-verification failure is mapped to `account-resolver-read@1` and
submitted asynchronously under a backend-owned service principal with
`ExecutionSource.EVENT`. No chat turn or fabricated user message is created.

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
- The next profile read observes the authoritative database update. Account
  PII is intentionally not copied into the policy vector index.

## Manifest-Defined Runtime

The complete deployment bundle is
[`src/main/resources/ai-specialists/account-resolver.yml`](src/main/resources/ai-specialists/account-resolver.yml).
It defines:

- exact-version input and output JSON schemas;
- separate read/write prompt profiles;
- `account-resolver-read@1`, `account-resolver@1`, and
  `billing-resolution-advisor@1`;
- Mode, execution strategy, requested vector/action capabilities, grounding
  requirements, conversation policy, and bounded limits; and
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

Interactive calls additionally use `ai-fabric-chat-session`. The gateway reads
recent turns before execution and records a new turn only after validated
projection. The browser sends only the new question.

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

`Idempotency-Key` is optional for the initial chat and billing-assessment
calls, opaque, and limited to 200 characters. Supply a stable value when
retrying the same initial request. A write proposal persists only an
identity-scoped HMAC of that value; reusing it for different proposal
parameters fails closed.

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
      max-steps: 4
      max-duration: PT1M
      max-active: 500
      result-ttl: PT15M
```

Plan state is separate from chat history, pending specialist input, and durable
action receipts. It checkpoints only the original typed input, validated step
outputs, safe evidence references, status, timing, and access binding needed
for same-process continuation.

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

## Docker

Build from the repository root. Tests run in both framework and app build
stages:

```bash
docker build \
  -f examples/real-apps/agentic-ai-action-resolver/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.4.0 \
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

Regression proof for the unchanged baseline:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl ai-fabric-account-resolver -am test
```

No verification command for this feature uses `-DskipTests`.

## Intentionally Out Of Scope

- automatic LLM confirmation;
- direct model-to-handler execution;
- dynamic/model-authored planning, delegation, or handoff;
- conditional, parallel, WRITE-capable, or durable plans;
- event or scheduled write adapters;
- framework-owned event-broker consumption or scheduler ownership;
- durable WRITE-capable specialist jobs;
- blind retries for unknown write outcomes;
- durable or cross-process specialist input waits;
- durable confirmation, composed-plan continuation, or human review;
- exactly-once provider invocation;
- multi-question input collection;
- a public reconciliation endpoint;
- vectorizing account PII.

These require separate contracts and evidence. They are not silently simulated
by this reference app.
