# Agentic AI Action Resolver

This independent reference app proves AI Fabric's bounded specialist execution
model with one governed write:

```text
specialist: account-resolver@1
read action: get_account_profile
write proposal: update_address
evidence: account-resolution-policy
```

The model may diagnose the current account and propose an address update. It
cannot authorize, confirm, or directly execute that update. AI Fabric and the
application create an identity-bound durable receipt, require an explicit user
decision, revalidate authority, and execute the registered application action
at most once.

The original `ai-fabric-account-resolver` remains unchanged and deployable as
the governed-action baseline.

## What This Proves

- A typed `AccountResolutionRequest` enters through `AIExecutionGateway`.
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

Run an interactive call. This is the only path granted the write-proposal
scope:

```http
POST /api/agentic-resolver/chat
X-AI-Fabric-Demo-Session: {sessionId}
Idempotency-Key: {stable-client-request-id}
Content-Type: application/json

{"question":"Update my billing address to 10 Downing Street, London, London, SW1A 2AA, GB."}
```

`Idempotency-Key` is optional, opaque, and limited to 200 characters. Supply a
stable value when retrying the same client request. AI Fabric stores only an
identity-scoped HMAC of the value; reusing it for different parameters fails
closed instead of creating another executable proposal.

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
execution threshold, cleanup policy, retention, and specialist registration.
It never exposes receipt secrets.

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

OPENAI_ENABLED=true \
OPENAI_API_KEY="$OPENAI_API_KEY" \
OPENAI_MODEL=gpt-4o-mini \
PORT=8105 \
java -jar \
  examples/real-apps/agentic-ai-action-resolver/target/agentic-ai-action-resolver-1.0.0-SNAPSHOT.jar
```

Never commit provider or receipt secrets.

The default profile uses file-backed H2 for a self-contained local proof. The
`prod` profile uses PostgreSQL. When selecting it, also provide:

- `DATABASE_URL=jdbc:postgresql://<host>:<port>/<database>`
- `DB_USERNAME=<database user>`
- `DB_PASSWORD=<database secret>`

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
session bindings, action receipts, chat sessions, and the local vector index:

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
```

Tags are bounded to `event`, registered `action`, and receipt `status`. Events
include proposal, confirmation, execution, completion, denial, replay-related
terminal reads, expiry, unknown recovery, store unavailability, reconciliation,
and retention deletion.

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
- multi-specialist planning, delegation, or handoff;
- event or scheduled write adapters;
- blind retries for unknown write outcomes;
- durable async job execution;
- a public reconciliation endpoint;
- vectorizing account PII.

These require separate contracts and evidence. They are not silently simulated
by this reference app.
