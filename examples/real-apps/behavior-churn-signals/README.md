# Behavior Churn Signals

## Scenario

This app demonstrates behavior analytics and churn/sentiment insight generation through a
manifest-defined, durable, read-only AI Fabric specialist. The public-demo scenario is a SaaS
behavior command center:
operators review real account behavior signals, analyze churn/upside/product-risk signals, record a
raw application event, and inspect AI-selected operator recommendations without showing customer-facing
offer execution controls.

The app is fully offline by default. It uses H2, deterministic sample behavior data, and an in-app
deterministic LLM provider so the scenario is repeatable without external API keys.

For the public demo, the same workflow runs with the AI Fabric Spring AI provider and OpenAI. That
path proves the behavior module with live LLM-generated sentiment, churn, trend, recommendation, and
action-family analysis.

## AI Fabric Capabilities Proved

- `ai.behavior.enabled=true` activates the behavior module.
- Application events can be exposed through the `ExternalEventProvider` SPI.
- `behavior-risk-analyst@1` receives the previous approved insight plus only the new raw events.
- `DurableAIExecutionGateway` provides queued execution, leases, retry/recovery, cancellation,
  expiration, and idempotent replay.
- Successful typed specialist output is projected once into persisted `BehaviorInsights` per user.
- Built-in behavior analytics endpoints can query trend and rapid-decline signals.
- Retention workflow can combine behavior evidence, plan evidence, policy explanations, and separate policy-gated actions.
- Product-shaped `/api/behavior-demo` endpoints expose repeatable real-world scenarios for UI demos.
- Agentic UI planning can ask the configured AI Fabric LLM provider for an allowlisted structured component plan.
- Customer-safe recommendation output can cite stable evidence ids rather than raw internal state.
- Public demo sessions can isolate each visitor's seeded users, injected signals, and generated insights.
- `/api/demo/health` exposes build metadata, specialist hash, provider posture, JDBC readiness,
  durable execution, and the explicit no-fallback/no-automatic-write policy.

## Framework Surfaces

- `ai-fabric-behavior`
- `ai-fabric-execution`
- `ai-fabric-provider-starter`
- `ai-fabric-provider-spring-ai` for live external LLM runs
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

When `AI_LLM_PROVIDER=openai`, the demo uses the AI Fabric Spring AI provider. The deterministic
`behavior-local` provider is only registered for `AI_LLM_PROVIDER=behavior-local` or missing provider
configuration, so live deployments cannot accidentally generate insights through the offline fallback.

The demo health endpoint reports the active provider as either `deterministic-local` or `live-external`,
so the public UI can be honest about whether insight generation is no-key deterministic or live LLM-backed.

## Public Demo App

This app backs the public AI Fabric demo page:

- Demo UI: `https://ai-fabric.dev/demos/ai-fabric-behavior-signals`
- Behavior-driven home preview: `https://ai-fabric.dev/demos/ai-fabric-behavior-signals/agentic-ui`
- Expected backend runtime: `https://behavior-churn-signals.46.224.145.148.sslip.io`
- Demo API base path: `/api/behavior-demo`
- Live deployment posture: `AI_LLM_PROVIDER=openai`, `OPENAI_ENABLED=true`, `OPENAI_MODEL=gpt-4o-mini`.

The demo shows an operator workflow for a SaaS behavior team:

1. Create an isolated browser demo session.
2. Analyze five seeded behavior scenarios.
3. Review churn, sentiment, trend, recommendation, and action-family evidence.
4. Record a typed raw app event such as `PAYMENT_FAILED`, `FEATURE_ERROR`, `HELP_CENTER_SEARCH`, or `NO_LOGIN_14D`.
5. Review the AI-selected action family with backend policy validation.
6. Compose a behavior-driven home preview where the LLM selects safe user-facing home modules and the backend fills trusted module props.
7. Add positive recovery events to a churning user and observe how churn, sentiment, trend, and component selection react.

Agentic UI planning deliberately keeps the LLM contract small. The backend sends a component catalog
with each component name, description, and recommended use case. The LLM returns a short ordered list
of component `name` plus `reason`; it does not return props, CSS, React configuration, or arbitrary UI
data. The backend validates names against the allowlist and fills all component props from trusted
behavior insight, events, and retention review data.

The public UI is intentionally evidence-first. It displays the active provider posture, deployed
commit/build metadata, behavior pipeline steps, scenario queue, model used for each insight, and
governed action state. It should never present deterministic output as live AI; check
`GET /api/demo/health` before claiming live LLM behavior.

## Demo Backend App Architecture

This is the backend for the `aifabric` Behavior Signals UI and its behavior-driven home-preview
subpage. It is intentionally not a RAG/vector demo; it proves AI Fabric behavior analysis,
structured LLM output, governed analytics recommendations, and agentic UI planning.

Backend dependencies:

- Spring Boot Web, Data JPA, Validation, Actuator, H2, and Lombok.
- AI Fabric modules: `ai-fabric-provider-starter`, `ai-fabric-provider-spring-ai`,
  `ai-fabric-behavior`, and `ai-fabric-execution`.
- `smoke-support` for shared health/build metadata.

AI-enabled domain model:

- This demo does not use `@AICapable` entities. That is intentional: behavior analytics is fed through
  AI Fabric's behavior SPI rather than entity annotations.
- `AppBehaviorEvent` stores raw app events, and `DbExternalEventProvider` exposes them through
  `ExternalEventProvider`.
- `DurableBehaviorAnalysisService` binds the manifest specialist, submits durable execution, and
  applies a successful typed result once through the behavior persistence service.
- `AgenticUiComposerService` uses the shared structured JSON path to ask the configured LLM for an
  allowlisted component-name plan; backend code fills the trusted props.

Providers and storage:

- Local runs use the deterministic `behavior-local` LLM provider by default.
- Live public deployments use OpenAI via `ai-fabric-provider-spring-ai`.
- No vector database is configured; `ai.vector-db.type=false` and search/embedding features are off.
- H2 stores behavior events, persisted insights, application job bindings, and encrypted durable
  specialist execution state for each session-scoped demo user.
- `BehaviorInsights.aiModelUsed` records the immutable specialist contract id
  (`behavior-risk-analyst@1`). Provider selection is proved independently by `/api/demo/health`;
  agentic UI plans also report the concrete provider model used for that structured call.

Request and data flow:

1. The UI creates or restores a browser session through `/api/behavior-demo/sessions`.
2. The backend clones seeded demo users and raw behavior events into that session namespace.
3. The UI records event facts through `/events`, then submits the exact
   `behavior-risk-analyst@1` specialist through `/analyses` with a stable idempotency key.
4. The UI polls the opaque invocation ID while AI Fabric leases and executes the durable job.
5. AI Fabric validates structured sentiment, churn risk, trend, recommendation, action family, and
   evidence, then the application projects the result once into the current approved insight.
6. The analytics page shows AI-selected recommendation families, not frontend heuristics or offer execution controls.
7. The agentic UI route calls `/agentic-ui`; the LLM receives a small component catalog and returns
   component names plus reasons. The backend validates names and renders trusted, domain-specific
   module props for the UI.
8. Session cleanup removes old public-demo users, events, insights, and app-owned job bindings after the
   configured TTL.

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
   curl -fsS http://localhost:8097/api/demo/health | jq
   ```

4. Create an isolated demo session without running analysis implicitly:

   ```bash
   created=$(curl -fsS -X POST http://localhost:8097/api/behavior-demo/sessions \
     -H 'Content-Type: application/json' \
     -d '{"sessionId":"local-browser-1","analyze":false}')
   session_id=$(printf '%s' "$created" | jq -r '.sessionId')
   user_id=$(printf '%s' "$created" | jq -r '.scenarios[0].userId')
   printf '%s' "$created" | jq
   ```

5. Submit one durable analysis and retain the opaque invocation ID:

   ```bash
   submitted=$(curl -fsS -X POST \
     "http://localhost:8097/api/behavior-demo/scenarios/${user_id}/analyses" \
     -H "X-Demo-Session-Id: ${session_id}" \
     -H 'Idempotency-Key: local-analysis-1')
   invocation_id=$(printf '%s' "$submitted" | jq -r '.invocationId')
   printf '%s' "$submitted" | jq

   curl -fsS \
     "http://localhost:8097/api/behavior-demo/analyses/${invocation_id}" \
     -H "X-Demo-Session-Id: ${session_id}" | jq
   ```

6. Record a raw app event for the session user without triggering analysis implicitly:

   ```bash
   curl -fsS -X POST \
     "http://localhost:8097/api/behavior-demo/scenarios/${user_id}/events" \
     -H 'Content-Type: application/json' \
     -d '{
       "eventType": "PAYMENT_FAILED",
       "eventData": {
         "reason": "card_declined",
         "invoiceStatus": "past_due",
         "renewalAttempt": "2",
         "gateway": "stripe"
       },
       "source": "billing-service"
     }' | jq
   ```

7. Optional API-only check: preview the confirmation-gated retention offer endpoint:

   ```bash
   curl -fsS -X POST "http://localhost:8097/api/behavior-demo/scenarios/${user_id}/retention-offer" \
     -H 'Content-Type: application/json' \
     -d '{"discountPercent":25,"confirmed":false}' | jq
   ```

8. Optional API-only check: confirm the retention action endpoint:

   ```bash
   curl -fsS -X POST "http://localhost:8097/api/behavior-demo/scenarios/${user_id}/retention-offer" \
     -H 'Content-Type: application/json' \
     -d '{"discountPercent":25,"confirmed":true}' | jq
   ```

9. Compose an agentic UI component plan for the same analyzed user:

   ```bash
   curl -fsS -X POST "http://localhost:8097/api/behavior-demo/scenarios/${user_id}/agentic-ui" \
     -H 'Content-Type: application/json' | jq
   ```

10. Add positive recovery events to the churning account and rerun analysis:

   ```bash
   curl -fsS -X POST "http://localhost:8097/api/behavior-demo/scenarios/${user_id}/positive-recovery-events" \
     -H 'Content-Type: application/json' | jq
   ```

Default port: `8097`.

## Validate

Run the focused test suite:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl behavior-churn-signals -am test
```

Use `requests/demo.http` to run the product scenario.

For a live browser smoke against the public deployment, verify:

1. `GET /api/demo/health` reports `provider.generation=openai`,
   `provider.realProviderRequired=true`, the exact specialist hash, and durable storage readiness.
2. The UI shows `API connected`, `Live LLM provider`, and the expected commit.
3. Each durable analysis succeeds under the exact `behavior-risk-analyst@1` manifest; provider
   truth comes from `/api/demo/health`, not from the insight's specialist-contract field.
4. Recording a typed app event re-runs behavior analysis and increases the session event count.
5. The recommendation panel shows an AI-selected `action_family`; if LLM analysis fails, the UI shows the failure instead of substituting a fallback recommendation.
6. Agentic UI planning returns only allowlisted component types with backend-populated props; invalid LLM layout output fails visibly.
7. Positive recovery events are visible as raw event evidence and cause a fresh behavior analysis.
8. Reset the session and confirm health returns to zero public-demo events/insights for that session.

## Demo Flow

1. Create or restore a browser session.
2. Seed session-scoped users and behavior events.
3. Submit selected accounts through the exact `behavior-risk-analyst@1` durable specialist.
4. Review persisted behavior insight summaries, trend distribution, and immediate-action signals.
5. Record a new raw account behavior event and re-run analysis.
6. Review behavior evidence, policy explanation, and AI-selected recommended action family.
7. Generate an agentic UI component plan for the selected account and render the returned component list.
8. For the churning account, add positive recovery events and compare before/after insight metrics.

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
- `GET /api/demo/health`
- `POST /api/behavior-demo/sessions`
- `POST /api/behavior-demo/seed`
- `POST /api/behavior-demo/seed-and-analyze`
- `POST /api/behavior-demo/reset`
- `POST /api/behavior-demo/scenarios/{userId}/analyze`
- `POST /api/behavior-demo/scenarios/{userId}/analyses`
- `GET /api/behavior-demo/analyses/{invocationId}`
- `GET /api/behavior-demo/analyses`
- `DELETE /api/behavior-demo/analyses/{invocationId}`
- `POST /api/behavior-demo/scenarios/{userId}/signals`
- `POST /api/behavior-demo/scenarios/{userId}/events`
- `POST /api/behavior-demo/scenarios/{userId}/positive-recovery`
- `POST /api/behavior-demo/scenarios/{userId}/agentic-ui`
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
  --build-arg AI_FABRIC_VERSION=0.5.2 \
  --build-arg SOURCE_COMMIT="$(git rev-parse --short HEAD)" \
  --build-arg SOURCE_BRANCH="$(git branch --show-current)" \
  -t ai-fabric-behavior-churn-signals:0.5.2 \
  examples/real-apps
```

Run the image:

```bash
docker run --rm -p 8097:8097 \
  -e PORT=8097 \
  -e CORS_ALLOWED_ORIGINS=https://ai-fabric.dev \
  ai-fabric-behavior-churn-signals:0.5.2
```

Then verify the running container:

```bash
curl -fsS http://localhost:8097/actuator/health
curl -fsS http://localhost:8097/api/demo/health | jq
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
- `APP_BEHAVIOR_DEMO_REQUIRE_REAL_AI=true` for the public live-provider deployment health gate.
- `APP_BEHAVIOR_DEMO_CLEANUP_TTL=PT6H`
- `JAVA_OPTS=-Xms256m -Xmx768m`
- `git_repository=Loom-AI-Labs/ai-fabric-framework.git`
- `git_branch=main`
- `base_directory=/examples/real-apps`
- `dockerfile_location=/behavior-churn-signals/Dockerfile`
- `ports_exposes=8097`

## What This App Does Not Cover

- Vector search or RAG.
- Multi-tenant vector storage.
- Full conversational chat orchestration. Use `chat-capabilities-demo`.

Those are covered by other real apps such as `provider-failover-lab`, `smart-faq-assistant`,
`tenant-knowledge-portal`, and `vector-readiness-playground`.
