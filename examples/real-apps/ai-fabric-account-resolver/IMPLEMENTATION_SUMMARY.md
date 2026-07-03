# AI Fabric Account Resolver - Implementation Summary

## Status

The `ai-fabric-account-resolver` real app has been upgraded from the subscription management hub into the AI Fabric Account Resolver demo. The module still keeps subscription-management capabilities, but the user-facing scenario is now account diagnosis and governed resolution for blockers that prevent a user from continuing app usage or placing an order.

## Implemented Capabilities

- Deterministic resolver personas:
  - user `91`: ready account
  - user `92`: missing payment method
  - user `93`: missing billing address
  - user `94`: refund/account-credit issue
- Resolver policy API for active subscription, payment method, billing address, and refund/account-credit rules.
- Account readiness API that returns blockers, recommended actions, and confirmation requirements.
- New payment-method domain model attached to subscriptions.
- New refund-request domain model and repository.
- New resolver service for readiness inspection, demo seeding, payment resolution, and refund/account-credit policy execution.
- New resolver REST API under `/api/account-resolver`.
- New AI Fabric read action:
  - `inspect_account_readiness`
- New AI Fabric confirmable write actions:
  - `update_payment_method`
  - `request_refund`
- Existing `update_address` action improved so it can resolve the active subscription from `userId` when `subscriptionId` is not supplied.
- Natural language endpoint defaults to `position=resolver` when the caller does not provide a position.
- Dockerfile for release-style deployment from the released AI Fabric Maven version.
- CORS configuration for `ai-fabric.dev` style deployment.

## Main Endpoints

- `GET /api/account-resolver/policies`
- `GET /api/account-resolver/scenarios`
- `POST /api/account-resolver/demo/seed`
- `GET /api/account-resolver/users/{userId}/readiness`
- `GET /api/account-resolver/subscriptions/{subscriptionId}/readiness`
- `POST /api/account-resolver/subscriptions/{subscriptionId}/payment-method`
- `PUT /api/account-resolver/subscriptions/{subscriptionId}/billing-address`
- `POST /api/account-resolver/subscriptions/{subscriptionId}/refund`
- `POST /api/subscriptions/query`

## Verification

Focused package build with tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl ai-fabric-account-resolver -am package
```

Local smoke path:

```bash
PORT=18081 OPENAI_ENABLED=false \
java -jar examples/real-apps/ai-fabric-account-resolver/target/ai-fabric-account-resolver-1.0.0-SNAPSHOT.jar
```

Smoke calls:

```bash
curl -fsS -X POST http://localhost:18081/api/account-resolver/demo/seed
curl -fsS http://localhost:18081/api/account-resolver/users/92/readiness
```

## Deployment Env Vars

- `PORT=8081`
- `OPENAI_ENABLED=true`
- `OPENAI_API_KEY=<secret>`
- `OPENAI_MODEL=gpt-4o-mini`
- `CORS_ALLOWED_ORIGINS=https://ai-fabric.dev`
- `JAVA_OPTS=-Xms256m -Xmx768m`

## Notes

- The app can boot without an LLM key for API and readiness smoke tests.
- Real natural-language orchestration requires `OPENAI_ENABLED=true` and a valid OpenAI key.
- Dockerfile build was added, but local Docker daemon must be running to validate the image build.
