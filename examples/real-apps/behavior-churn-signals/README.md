# Behavior Churn Signals

## Scenario

This app demonstrates behavior analytics, churn/sentiment insight generation, and governed operator
actions using AI Fabric's behavior module. The public-demo scenario is a SaaS behavior command center:
operators review real account behavior signals, analyze churn/upside/product-risk signals, inject a
new behavior event, and confirm a retention offer only when the evidence supports it.

The app is fully offline by default. It uses H2, deterministic sample behavior data, and an in-app
deterministic LLM provider so the scenario is repeatable without external API keys.

## AI Fabric Capabilities Proved

- `ai.behavior.enabled=true` activates the behavior module.
- Application events can be exposed through the `ExternalEventProvider` SPI.
- `BehaviorAnalysisService` produces persisted `BehaviorInsights` per user.
- Built-in behavior analytics endpoints can query trend and rapid-decline signals.
- Retention workflow can combine behavior evidence, plan evidence, policy explanations, and confirmation-gated actions.
- Product-shaped `/api/behavior-demo` endpoints expose repeatable real-world scenarios for UI demos.
- Customer-safe recommendation output can cite stable evidence ids rather than raw internal state.
- Public demo sessions can isolate each visitor's seeded users, injected signals, and action previews.
- `/api/behavior-demo/health` exposes build metadata, provider posture, behavior mode, and demo readiness counts.

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

The app can also run with a live AI Fabric LLM provider:

```bash
AI_LLM_PROVIDER=openai
OPENAI_ENABLED=true
OPENAI_API_KEY=...
OPENAI_MODEL=gpt-4o-mini
```

The demo health endpoint reports the active provider as either `deterministic-local` or `live-external`,
so the public UI can be honest about whether insight generation is no-key deterministic or live LLM-backed.

## Public Demo App

This app backs the public AI Fabric demo page:

- Demo UI: `https://ai-fabric.dev/demos/ai-fabric-behavior-signals`
- Expected backend runtime: `https://behavior-churn-signals.46.224.145.148.sslip.io`
- Demo API base path: `/api/behavior-demo`

The demo shows an operator workflow for a SaaS behavior team:

1. Create an isolated browser demo session.
2. Analyze five seeded behavior scenarios.
3. Review churn, sentiment, trend, recommendation, and action-family evidence.
4. Inject a new behavior signal such as cancellation intent, feature error, or no-login risk.
5. Preview governed action output with backend policy explanations.
6. Confirm a retention offer when the selected scenario actually calls for one.

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
   curl -fsS http://localhost:8097/api/behavior-demo/health | jq
   ```

4. Create an isolated demo session and analyze all scenarios:

   ```bash
   curl -fsS -X POST http://localhost:8097/api/behavior-demo/sessions \
     -H 'Content-Type: application/json' \
     -d '{"sessionId":"local-browser-1","analyze":true}' | jq
   ```

5. Inject a cancellation signal for the session's high-risk account:

   ```bash
   curl -fsS -X POST http://localhost:8097/api/behavior-demo/scenarios/behavior-demo-user-local-browser-1-user-1001/signals \
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
   curl -fsS -X POST http://localhost:8097/api/behavior-demo/scenarios/behavior-demo-user-local-browser-1-user-1001/retention-offer \
     -H 'Content-Type: application/json' \
     -d '{"discountPercent":25,"confirmed":false}' | jq
   ```

7. Confirm the retention action:

   ```bash
   curl -fsS -X POST http://localhost:8097/api/behavior-demo/scenarios/behavior-demo-user-local-browser-1-user-1001/retention-offer \
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

1. Create or restore a browser session.
2. Seed session-scoped users and behavior events.
3. Analyze all seeded accounts or a selected account through AI Fabric `BehaviorAnalysisService`.
4. Review persisted behavior insight summaries, trend distribution, and immediate-action signals.
5. Inject a new account behavior event and re-run analysis.
6. Review behavior evidence, policy explanation, and recommended action family.
7. Preview and optionally confirm a confirmation-gated retention offer.

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
- `GET /api/behavior-demo/health`
- `POST /api/behavior-demo/sessions`
- `POST /api/behavior-demo/seed`
- `POST /api/behavior-demo/seed-and-analyze`
- `POST /api/behavior-demo/reset`
- `POST /api/behavior-demo/scenarios/{userId}/analyze`
- `POST /api/behavior-demo/scenarios/{userId}/signals`
- `POST /api/behavior-demo/scenarios/{userId}/retention-offer`

Add `?sessionId=<id>` to `dashboard`, `scenarios`, `seed`, and `seed-and-analyze` to work with an isolated public demo session.

Seeded scenarios:

- `user-1001` / Acme Finance: billing failures, support complaints, and cancellation intent (`RETENTION_OFFER`).
- `user-1002` / Northstar Analytics: frequent usage and expansion/upgrades (`EXPANSION_FOLLOW_UP`).
- `user-1003` / Harbor Clinics: onboarding friction and support confusion (`ADOPTION_HELP`).
- `user-1004` / BrightMarket: dashboard errors after a release with usage drop (`ENGINEERING_ESCALATION`).
- `user-1005` / QuietRiver Legal: no complaints, but usage and logins fade (`PROACTIVE_CHECK_IN`).

Session users are cloned as `behavior-demo-user-<sessionId>-user-100X`. A scheduled cleanup job removes
old session users after `app.behavior-demo.cleanup.ttl` (default `PT6H`).

## Docker

Build from the repo root:

```bash
docker build -f examples/real-apps/behavior-churn-signals/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.3.2 \
  --build-arg SOURCE_COMMIT="$(git rev-parse --short HEAD)" \
  --build-arg SOURCE_BRANCH="$(git branch --show-current)" \
  -t ai-fabric-behavior-churn-signals:0.3.2 \
  examples/real-apps
```

Run the image:

```bash
docker run --rm -p 8097:8097 \
  -e PORT=8097 \
  -e CORS_ALLOWED_ORIGINS=https://ai-fabric.dev \
  ai-fabric-behavior-churn-signals:0.3.2
```

Then verify the running container:

```bash
curl -fsS http://localhost:8097/actuator/health
curl -fsS http://localhost:8097/api/behavior-demo/health | jq
curl -fsS -X POST http://localhost:8097/api/behavior-demo/sessions \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"local-docker-1","analyze":true}' | jq
```

For a local frontend smoke, set `CORS_ALLOWED_ORIGINS` to the local UI origin instead, for example
`http://127.0.0.1:4173`.

Suggested deployment values:

- `PORT=8097`
- `CORS_ALLOWED_ORIGINS=https://ai-fabric.dev`
- `AI_LLM_PROVIDER=behavior-local` for deterministic no-key mode, or `openai` for live LLM mode.
- `OPENAI_ENABLED=true`, `OPENAI_API_KEY=<secret>`, `OPENAI_MODEL=gpt-4o-mini` when using OpenAI.
- `APP_BEHAVIOR_DEMO_CLEANUP_TTL=PT6H`
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
