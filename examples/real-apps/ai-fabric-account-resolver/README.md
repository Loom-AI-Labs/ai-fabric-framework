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
- AI Fabric chat-session conversation memory for follow-up turns and confirmation context.
- Annotation-assisted indexing over subscription plans.
- Deterministic local embeddings plus Lucene vector search.
- Explicit reindex and search endpoints.
- Behavior event capture for subscription and resolution actions.

## Framework Surfaces

- `ai-fabric-starter`
- `ai-fabric-provider-spring-ai`
- `ai-fabric-chat-session`
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

## Public Demo App

This app backs the public AI Fabric Account Resolver demo:

- Demo UI: `https://ai-fabric.dev/demos/ai-fabric-account-resolver`
- Expected backend runtime: `https://ai-fabric-account-resolver.46.224.145.148.sslip.io`
- Resolver API base path: `/api/account-resolver`
- Natural-language orchestration endpoint: `POST /api/subscriptions/query`

The public demo is designed to prove a current-account resolver, not a generic subscription admin
form:

1. Seed deterministic personas `91` to `94`.
2. Let AI Fabric read factual account profile data through `get_account_profile`.
3. Retrieve account-resolution policies through RAG.
4. Let the LLM reason about blockers from profile facts plus policies.
5. Propose confirmable write actions such as `update_payment_method`, `update_address`, and
   `request_refund`.
6. Persist chat turns through `ai-fabric-chat-session` so follow-up turns such as "ok add it" can use
   prior context.
7. Keep dashboard readiness endpoints available for UI state and regression checks, but do not expose
   precomputed blocker analysis as the primary AI action.

Use `GET /api/account-resolver/health` before demoing to verify deployed build metadata.

## Demo Backend App Architecture

This is the backend for the `aifabric` Account Resolver UI. It models a current user's subscription
account and lets AI Fabric diagnose blockers from factual account data plus policy evidence.

Backend dependencies:

- Spring Boot Web, Data JPA, Validation, Actuator, H2/PostgreSQL drivers, and Lombok.
- AI Fabric modules: `ai-fabric-starter`, `ai-fabric-provider-spring-ai`,
  `ai-fabric-chat-session`, `ai-fabric-rag`, `ai-fabric-vector-lucene`,
  `ai-fabric-behavior`, and `ai-fabric-relationship-query`.
- `smoke-support` for shared build metadata and release smoke support.

AI-enabled domain model:

- `SubscriptionPlan` and `Subscription` are annotated with `@AICapable`; plan text is searchable and
  subscription lifecycle fields are exposed as AI context.
- `PaymentMethod`, `Address`, and `RefundRequest` expose context fields used in action results and
  post-action evidence.
- `SubscriptionService` and `AccountResolutionService` use `@AIProcess` to keep plans,
  subscriptions, payments, addresses, and refund resolutions synchronized with indexing metadata.
- Local `@AIAction` handlers expose `get_account_profile`, `update_payment_method`,
  `update_address`, `request_refund`, `subscribe`, `cancel_subscription`, `upgrade_subscription`, and
  `downgrade_subscription`.
- `get_account_profile` includes `@ActionFacts`, so the LLM can reason from account facts instead of
  relying on frontend shortcuts or precomputed blocker text.

Providers and storage:

- Live natural-language orchestration uses OpenAI through `ai-fabric-provider-spring-ai`.
- Embeddings default to the app's deterministic `simple` provider for account-resolution policies and
  subscription plans.
- RAG uses Lucene vector search over `account-resolution-policy` and `subscription-plan`.
- H2 stores users, subscriptions, payment methods, addresses, refunds, behavior events, and chat
  sessions.

Request and data flow:

1. The UI sends natural language to `POST /api/subscriptions/query` with a stable conversation id.
2. The app defaults AI Fabric to the typed `resolver` mode and persists turns through
   `ai-fabric-chat-session`.
3. AI Fabric can run the planner-eligible `get_account_profile` read action and retrieve policy RAG in
   parallel.
4. The LLM explains blockers from current account facts plus retrieved policies, then proposes a
   confirmable write action when appropriate.
5. Action handlers enforce `@ActionAllowed`, build confirmation text with `@ActionConfirmation`, and
   execute domain writes through `@ActionExecute`.
6. Post-action payloads return concise result facts and readiness evidence for the UI, while dashboard
   readiness endpoints remain separate regression/state APIs.

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
  --build-arg AI_FABRIC_VERSION=0.5.2 \
  --build-arg BUILD_COMMIT="$(git rev-parse --short HEAD)" \
  --build-arg BUILD_BRANCH="$(git rev-parse --abbrev-ref HEAD)" \
  --build-arg BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  -t ai-fabric-account-resolver:0.5.2 \
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
  ai-fabric-account-resolver:0.5.2
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
- `git_repository=Loom-AI-Labs/ai-fabric-framework.git`
- `git_branch=main`
- `base_directory=/examples/real-apps`
- `dockerfile_location=/ai-fabric-account-resolver/Dockerfile`
- `ports_exposes=8081`

## What This App Does Not Cover

- Minimal no-annotation onboarding. Use `sub-management-hub-simple`.
- DB-backed action registry. Use `db-action-registry-lab`.
- Tenant-scoped retrieval/deletion. Use `tenant-knowledge-portal`.
