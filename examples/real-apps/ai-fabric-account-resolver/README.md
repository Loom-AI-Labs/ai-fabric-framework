# AI Fabric Account Resolver

## Scenario

This app demonstrates an AI-enabled account resolver for subscription and checkout-style blockers. It is built from the original subscription management hub, but the demo focus is now account diagnosis and governed resolution:

- Inspect whether an account can continue using the app or place an order.
- Explain blockers such as missing payment method, missing billing address, or inactive subscription.
- Let AI Fabric propose safe read actions and confirmable write actions.
- Resolve payment, address, cancellation, plan change, and refund/account-credit issues through local `@AIAction` handlers.
- Keep the readiness endpoint available for dashboard state and regression checks while the LLM path reasons from factual profile data plus policies.

## Demo Personas

Seed deterministic resolver personas with:

```bash
curl -X POST http://localhost:8081/api/account-resolver/demo/seed
```

The seeded demo users are:

- `91` ready account: active subscription, validated address, verified payment method.
- `92` missing payment: active subscription and address, but ordering is blocked by missing payment.
- `93` missing address: active subscription and payment method, but ordering is blocked by missing billing address.
- `94` refund request: usable account with a billing issue that can become an account credit or refund.

## AI Fabric Capabilities Proved

- Read-action grounding through `get_account_profile`, which returns factual account state without precomputed blockers or recommendations.
- Dedicated AI Fabric `resolver` orchestration mode.
- Policy-grounded RAG through the `account-resolution-policy` vector space.
- Confirmable write actions through `update_payment_method`, `update_address`, `request_refund`, `cancel_subscription`, `upgrade_subscription`, and `downgrade_subscription`.
- Action authorization through `@ActionAllowed`.
- Action confirmation through `@ActionConfirmation`.
- Post-action readiness evidence returned as structured `ActionResult` payloads after write actions.
- Policy-backed resolver behavior without hardcoded frontend business logic.
- Annotation-assisted indexing over subscription plans.
- Deterministic local embeddings plus Lucene vector search.
- Explicit reindex and search endpoints.
- Behavior event capture for subscription and resolution actions.

## Framework Surfaces

- `ai-fabric-starter`
- `ai-fabric-provider-spring-ai`
- `ai-fabric-vector-lucene`
- `ai-fabric-behavior`
- indexing queue
- local `@AIAction` handlers
- action authorization and confirmation annotations
- planner-eligible read actions
- config-driven entity metadata

## Runtime Posture

Default runtime:

- Java 21
- Spring Boot 4.1.0
- H2 file database
- deterministic local embeddings
- Lucene vector DB
- no external model key required unless `OPENAI_ENABLED=true`

Default port: `8081`.

## Run Locally

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl ai-fabric-account-resolver -am package
java -jar examples/real-apps/ai-fabric-account-resolver/target/ai-fabric-account-resolver-1.0.0-SNAPSHOT.jar
```

With OpenAI for real LLM orchestration:

```bash
OPENAI_ENABLED=true \
OPENAI_API_KEY="$OPENAI_API_KEY" \
OPENAI_MODEL=gpt-4o-mini \
PORT=8081 \
java -jar examples/real-apps/ai-fabric-account-resolver/target/ai-fabric-account-resolver-1.0.0-SNAPSHOT.jar
```

## Docker

Build from the repo root:

```bash
docker build -f examples/real-apps/ai-fabric-account-resolver/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.3.2 \
  --build-arg BUILD_COMMIT="$(git rev-parse --short HEAD)" \
  --build-arg BUILD_BRANCH="$(git rev-parse --abbrev-ref HEAD)" \
  --build-arg BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  -t ai-fabric-account-resolver:0.3.2 \
  examples/real-apps
```

Run:

```bash
docker run --rm -p 8081:8081 \
  -e PORT=8081 \
  -e OPENAI_ENABLED=true \
  -e OPENAI_API_KEY="$OPENAI_API_KEY" \
  -e OPENAI_MODEL=gpt-4o-mini \
  -e CORS_ALLOWED_ORIGINS=https://ai-fabric.dev \
  ai-fabric-account-resolver:0.3.2
```

## Validate

Focused tests/package:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl ai-fabric-account-resolver -am test
```

Use `requests/demo.http` for ready-to-run calls.

## Resolver Endpoints

- `GET /api/account-resolver/health` - public deployment health with app version, AI Fabric version, commit, branch, build time, start time, and check time.
- `GET /api/account-resolver/policies`
- `GET /api/account-resolver/scenarios`
- `POST /api/account-resolver/demo/seed`
- `GET /api/account-resolver/users/{userId}/readiness`
- `GET /api/account-resolver/subscriptions/{subscriptionId}/readiness`
- `POST /api/account-resolver/subscriptions/{subscriptionId}/payment-method`
- `PUT /api/account-resolver/subscriptions/{subscriptionId}/billing-address`
- `POST /api/account-resolver/subscriptions/{subscriptionId}/refund`

Natural language orchestration stays at:

- `POST /api/subscriptions/query`

When no `position` is provided, this app defaults to `resolver`. Action proposal, confirmation, and rejection should all be sent as normal turns to this query endpoint with the same `conversationId`.

## Deployment Env Vars

- `PORT=8081`
- `OPENAI_ENABLED=true`
- `OPENAI_API_KEY=<set in deployment secret store>`
- `OPENAI_MODEL=gpt-4o-mini`
- `CORS_ALLOWED_ORIGINS=https://ai-fabric.dev`
- `JAVA_OPTS=-Xms256m -Xmx768m`
- Optional deployment metadata: `APP_VERSION`, `AI_FABRIC_VERSION`, `APP_BUILD_COMMIT`, `APP_BUILD_BRANCH`, `APP_BUILD_TIME`.

## What This App Does Not Cover

- Minimal no-annotation onboarding. Use `sub-management-hub-simple`.
- DB-backed action registry. Use `db-action-registry-lab`.
- Tenant-scoped retrieval/deletion. Use `tenant-knowledge-portal`.
