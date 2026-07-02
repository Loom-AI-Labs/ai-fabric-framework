# Behavior Churn Signals

## Scenario

This app demonstrates behavior analytics, churn/sentiment insight generation, and a retention action
workflow using AI Fabric's behavior module. The public-demo scenario is a SaaS retention studio:
operators review real account behavior signals, analyze churn risk, inject a new behavior signal, and
confirm a retention offer only when the evidence supports it.

The app is fully offline by default. It uses H2, deterministic sample behavior data, and an in-app
deterministic LLM provider so the scenario is repeatable without external API keys.

## AI Fabric Capabilities Proved

- `ai.behavior.enabled=true` activates the behavior module.
- Application events can be exposed through the `ExternalEventProvider` SPI.
- `BehaviorAnalysisService` produces persisted `BehaviorInsights` per user.
- Built-in behavior analytics endpoints can query trend and rapid-decline signals.
- Retention workflow can combine behavior evidence, plan evidence, and confirmation-gated actions.
- Product-shaped `/api/behavior-demo` endpoints expose repeatable real-world scenarios for UI demos.
- Customer-safe recommendation output can cite stable evidence ids rather than raw internal state.

## Framework Surfaces

- `ai-fabric-behavior`
- `ai-fabric-provider-starter`
- deterministic local LLM provider
- H2-backed behavior persistence
- support code for retention studio flows

## Runtime Posture

Default runtime is local-only:

- H2 database
- deterministic local LLM
- no vector DB
- no external provider keys

Use this app to prove behavior analytics independently from RAG/vector infrastructure.

## Public Demo App

This app backs the public AI Fabric demo page:

- Demo UI: `https://ai-fabric.dev/demos/ai-fabric-behavior-signals`
- Expected backend runtime: `https://behavior-churn-signals.46.224.145.148.sslip.io`
- Demo API base path: `/api/behavior-demo`

The demo shows an operator workflow for a SaaS retention team:

1. Reset and analyze seeded account behavior.
2. Review churn, sentiment, trend, and immediate-action evidence.
3. Inject a new behavior signal such as cancellation intent.
4. Preview a retention action that requires confirmation.
5. Confirm the action and inspect the executed result.

## Run Locally

From the repository root:

1. Package the app and its real-app dependencies:

   ```bash
   mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml \
     -pl behavior-churn-signals -am package
   ```

2. Start the app:

   ```bash
   java -jar examples/real-apps/behavior-churn-signals/target/behavior-churn-signals-1.0.0-SNAPSHOT.jar
   ```

3. Open the local health endpoint:

   ```bash
   curl -fsS http://localhost:8097/actuator/health
   ```

4. Seed and analyze the demo scenarios:

   ```bash
   curl -fsS -X POST http://localhost:8097/api/behavior-demo/seed-and-analyze | jq
   ```

5. Inject a cancellation signal for the high-risk account:

   ```bash
   curl -fsS -X POST http://localhost:8097/api/behavior-demo/scenarios/user-1001/signals \
     -H 'Content-Type: application/json' \
     -d '{
       "eventType": "CANCEL_INTENT",
       "eventData": {
         "message": "Customer says renewal failed twice and asks how to cancel before month end."
       },
       "source": "local-demo"
     }' | jq
   ```

6. Preview the confirmation-gated retention offer:

   ```bash
   curl -fsS -X POST http://localhost:8097/api/behavior-demo/scenarios/user-1001/retention-offer \
     -H 'Content-Type: application/json' \
     -d '{"discountPercent":25,"confirmed":false}' | jq
   ```

7. Confirm the retention action:

   ```bash
   curl -fsS -X POST http://localhost:8097/api/behavior-demo/scenarios/user-1001/retention-offer \
     -H 'Content-Type: application/json' \
     -d '{"discountPercent":25,"confirmed":true}' | jq
   ```

Default port: `8097`.

## Validate

Run the focused test suite:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl behavior-churn-signals -am test
```

Use `requests/demo.http` to run the product scenario.

## Demo Flow

1. Seed users and behavior events.
2. Analyze all seeded accounts or a selected account.
3. Review persisted behavior insight summaries, trend distribution, and immediate-action signals.
4. Inject a new account behavior event and re-run analysis.
5. Review behavior evidence plus retention-plan evidence.
6. Preview a confirmation-gated retention offer.
7. Confirm the offer and verify the action result payload.

## Key Endpoints

- `POST /api/demo/seed`
- `POST /api/behavior/analyze/{userId}`
- `POST /api/behavior/process-next`
- `GET /api/behavior/insights`
- `GET /api/behavior/analytics/rapid-decline`
- `GET /api/behavior/analytics/trend-distribution`

## Public Demo Endpoints

- `GET /api/behavior-demo/dashboard`
- `GET /api/behavior-demo/scenarios`
- `POST /api/behavior-demo/seed`
- `POST /api/behavior-demo/seed-and-analyze`
- `POST /api/behavior-demo/scenarios/{userId}/analyze`
- `POST /api/behavior-demo/scenarios/{userId}/signals`
- `POST /api/behavior-demo/scenarios/{userId}/retention-offer`

Seeded scenarios:

- `user-1001` / Acme Finance: billing failures, support complaints, and cancellation intent.
- `user-1002` / Northstar Analytics: frequent usage and expansion/upgrades.
- `user-1003` / Harbor Clinics: onboarding friction and support confusion.

## Docker

Build from the repo root:

```bash
docker build -f examples/real-apps/behavior-churn-signals/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.3.1 \
  -t ai-fabric-behavior-churn-signals:0.3.1 \
  examples/real-apps
```

Run the image:

```bash
docker run --rm -p 8097:8097 \
  -e PORT=8097 \
  -e CORS_ALLOWED_ORIGINS=https://ai-fabric.dev \
  ai-fabric-behavior-churn-signals:0.3.1
```

Then verify the running container:

```bash
curl -fsS http://localhost:8097/actuator/health
curl -fsS -X POST http://localhost:8097/api/behavior-demo/seed-and-analyze | jq
```

For a local frontend smoke, set `CORS_ALLOWED_ORIGINS` to the local UI origin instead, for example
`http://127.0.0.1:4173`.

Suggested deployment values:

- `PORT=8097`
- `CORS_ALLOWED_ORIGINS=https://ai-fabric.dev`
- `JAVA_OPTS=-Xms256m -Xmx768m`
- `git_repository=Loom-AI-Labs/ai-fabric-framework.git`
- `git_branch=main`
- `base_directory=/examples/real-apps`
- `dockerfile_location=/behavior-churn-signals/Dockerfile`
- `ports_exposes=8097`

## What This App Does Not Cover

- Live LLM provider behavior.
- Vector search or RAG.
- Multi-tenant vector storage.

Those are covered by other real apps such as `provider-failover-lab`, `smart-faq-assistant`,
`tenant-knowledge-portal`, and `vector-readiness-playground`.
