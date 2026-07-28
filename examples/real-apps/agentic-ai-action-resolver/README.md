# Agentic AI Action Resolver

This independent reference app proves AI Fabric's first bounded specialist:
`account-resolver@1`.

The same specialist can be called as:

- a typed Java application operation with no fabricated user, session, or
  conversation; and
- an interactive operation with backend-owned conversation continuity.

Both paths use the existing AI Fabric orchestration pipeline. The specialist is
strictly read-only: it can read the trusted current account profile and retrieve
account-resolution policies, but it cannot expose or invoke a write action.

The original `ai-fabric-account-resolver` app remains unchanged and deployable
as the governed-action baseline.

## What This Proves

- A typed `AccountResolutionRequest` enters through `AIExecutionGateway`.
- Server-created session state binds the trusted subject account.
- Request bodies contain a question only; identity, tenant, scopes, and target
  identifiers cannot be supplied by the caller.
- `account-resolver@1` requests one Mode, one READ action, and one vector space.
- Effective capabilities intersect specialist requests, Mode policy, registered
  capabilities, and trusted authority.
- `get_account_profile` reads current application state.
- RAG retrieves only `account-resolution-policy` evidence.
- The LLM returns a typed `AccountResolutionResult`.
- Invalid or incomplete model output fails visibly instead of being replaced by
  a deterministic answer.
- Interactive turns are persisted only after output projection and validation.
- Application calls do not read or write conversation history.
- Evidence returned to callers uses safe `AIEvidenceReference` projections.

## Specialist Contract

Input:

```json
{
  "question": "Review my current account against the policies. Can I place an order?"
}
```

Output:

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

## Published Scenarios

Each public demo session receives isolated seeded data for three scenarios:

| Scenario | Trusted current state | Expected assessment |
| --- | --- | --- |
| `ready-account` | Active subscription, verified payment, validated address | `READY` |
| `missing-payment` | Active subscription and address, no verified payment | `BLOCKED` |
| `missing-address` | Active subscription and payment, no validated address | `BLOCKED` |

The scenario descriptions are navigation aids, not model answers. The specialist
must read current profile facts and policy evidence for every assessment.

## Architecture

```text
HTTP controller
  -> server-owned demo session and selected subject
  -> TrustedExecutionContext
  -> AIExecutionGateway
  -> account-resolver@1 definition
  -> effective capability intersection
  -> existing AI Fabric pipeline
       -> security and access control
       -> get_account_profile READ action
       -> account-resolution-policy RAG
       -> OpenAI generation
  -> structured JSON projection and validation
  -> safe evidence projection
  -> typed AIExecutionResult<AccountResolutionResult>
```

Interactive calls additionally use `ai-fabric-chat-session`. The gateway reads
recent turns before execution and records the new turn only after the typed
result has passed validation. The browser sends only the new question.

## Important Security Boundary

The public request has no `userId`, `subscriptionId`, `tenantId`, action scope,
or vector-space field. Those values are resolved from server-owned state.

Text such as "inspect account 94 instead" is untrusted model input and cannot
change the bound subject. The specialist has no WRITE capability and its
effective action catalogue contains only `get_account_profile`.

## API

Create an isolated session:

```http
POST /api/agentic-resolver/sessions
```

Read or delete a session:

```http
GET /api/agentic-resolver/sessions/{sessionId}
DELETE /api/agentic-resolver/sessions/{sessionId}
```

Select a scenario:

```http
PUT /api/agentic-resolver/sessions/{sessionId}/scenarios/{scenarioId}
```

Run a stateless application call:

```http
POST /api/agentic-resolver/evaluate
X-AI-Fabric-Demo-Session: {sessionId}
Content-Type: application/json
```

Run an interactive call with backend memory:

```http
POST /api/agentic-resolver/chat
X-AI-Fabric-Demo-Session: {sessionId}
Content-Type: application/json
```

Deployment health and specialist registration:

```http
GET /api/demo/health
```

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
contract. The specialist call must therefore return a visible
`GROUNDING_VALIDATION_FAILED`. This is a packaged-runtime failure-visibility
proof, not a replacement for the real-provider assessment matrix.

Real OpenAI:

```bash
OPENAI_ENABLED=true \
OPENAI_API_KEY="$OPENAI_API_KEY" \
OPENAI_MODEL=gpt-4o-mini \
PORT=8105 \
java -jar \
  examples/real-apps/agentic-ai-action-resolver/target/agentic-ai-action-resolver-1.0.0-SNAPSHOT.jar
```

Never commit an API key. Use the deployment secret store or a local environment
variable.

The default profile uses a local H2 database and is the self-contained
real-provider demo profile. The `prod` profile uses PostgreSQL; when selecting
it, also provide:

- `DATABASE_URL=jdbc:postgresql://<host>:<port>/<database>`
- `DB_USERNAME=<database user>`
- `DB_PASSWORD=<database secret>`

## Docker

This pre-release Docker build uses the exact framework source revision and runs
tests during both framework and app builds:

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

Run it:

```bash
docker run --rm -p 8105:8105 \
  -e PORT=8105 \
  -e OPENAI_ENABLED=true \
  -e OPENAI_API_KEY="$OPENAI_API_KEY" \
  -e OPENAI_MODEL=gpt-4o-mini \
  -e CORS_ALLOWED_ORIGINS=https://ai-fabric.dev \
  agentic-ai-action-resolver:source
```

For Coolify source deployment, use:

- repository: `Loom-AI-Labs/ai-fabric-framework.git`
- branch: `main`
- base directory: `/`
- Dockerfile: `/examples/real-apps/agentic-ai-action-resolver/Dockerfile`
- exposed port: `8105`
- include source commit in build: enabled

After `ai-fabric-execution` is released to Maven Central, the app may return to
the lighter released-artifact Docker pattern.

## Runtime Configuration

Required for a real-provider deployment:

- `PORT=8105`
- `OPENAI_ENABLED=true`
- `OPENAI_API_KEY=<deployment secret>`
- `OPENAI_MODEL=gpt-4o-mini`
- `CORS_ALLOWED_ORIGINS=https://ai-fabric.dev`
- `JAVA_OPTS=-Xms256m -Xmx768m`

Required in addition when `SPRING_PROFILES_ACTIVE=prod`:

- `DATABASE_URL=jdbc:postgresql://<host>:<port>/<database>`
- `DB_USERNAME=<database user>`
- `DB_PASSWORD=<database secret>`

Useful optional controls:

- `APP_AGENTIC_SESSION_TTL=PT6H`
- `APP_AGENTIC_MAX_SESSIONS=500`
- `APP_AGENTIC_SESSION_CLEANUP_CRON=0 */15 * * * *`
- `JPA_SHOW_SQL=false`
- `JPA_FORMAT_SQL=false`

## Failure Semantics

- Provider, retrieval, grounding, schema, and final domain validation failures
  are returned as typed non-success results.
- Evidence with an unresolved or out-of-profile vector space denies the
  execution.
- The Mode may be broader than this specialist, but effective RAG budgets and
  read-action policy are narrowed to `account-resolution-policy` and
  `get_account_profile`.
- Generic suggestions and knowledge-base overview are disabled for the
  specialist request because they are not declared capabilities.
- A hostile write instruction may still receive a read-only diagnosis; it
  cannot expose or invoke a write and does not alter account state.
- A cross-account target embedded in text cannot rebind the server-owned
  subject.

## Verification

Focused app tests:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl agentic-ai-action-resolver -am test
```

Framework release gate:

```bash
mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml clean verify
```

Real-app release gate:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml clean verify
```

No verification command for this feature uses `-DskipTests`.

## Deliberately Not In P1

- specialist WRITE actions;
- durable execution or restart recovery;
- resumable human review;
- multi-specialist plans, routing, delegation, or handoff;
- event and scheduled execution adapters;
- a claim that ephemeral submission is a durable job system.

Those capabilities require separate evidence and governance work after this
read-only vertical proof is approved.
