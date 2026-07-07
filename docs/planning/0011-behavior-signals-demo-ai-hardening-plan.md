# ADR 0011 - Behavior Signals AI demo hardening plan

- **Status:** Implemented
- **Date:** 2026-07-06
- **Decision owner:** AI Fabric framework and public demo UI
- **Applies to backend:** `examples/real-apps/behavior-churn-signals`
- **Applies to frontend:** `/Users/mahmoudashraf/Downloads/Projects/aifabric/src/pages/demos/AIFabricBehaviorSignals.tsx`
- **Depends on:** `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md`

## Context

The public Behavior Signals demo is useful, but it is not yet hardened to the same level as the
Shopping Experience and Account Resolver demos.

The current app proves the AI Fabric behavior module path:

```text
App behavior events
  -> ExternalEventProvider
  -> BehaviorAnalysisService
  -> AICoreService.generateContent(...)
  -> structured JSON parsing/repair
  -> persisted BehaviorInsights
  -> analytics endpoints and retention workflow
```

However, the public demo currently uses an offline deterministic provider:

```text
ai.providers.llm-provider=behavior-local
```

That is valuable for repeatable tests and no-key local runs, but it is weaker as a public "AI
intelligence" demo. The README is correct to say it does not need API keys by default. The public
demo should make that posture visible and should support an optional real-provider mode so external
users can see behavior intelligence backed by a live LLM.

## Current Code Evidence

| Area | Evidence |
| --- | --- |
| Behavior module enabled | `examples/real-apps/behavior-churn-signals/src/main/resources/application.yml` has `ai.behavior.enabled=true`. |
| Offline provider | `application.yml` has `ai.providers.llm-provider=behavior-local`. |
| Provider implementation | `BehaviorLocalLlmProvider` implements `AIProvider` and returns deterministic JSON for behavior prompts. |
| Framework behavior path | `BehaviorDemoScenarioService` calls `BehaviorAnalysisService.analyzeUser(...)`. |
| AI Fabric core call | `BehaviorAnalysisService` builds an `AIGenerationRequest` and calls `AICoreService.generateContent(...)`. |
| Structured output | `BehaviorAnalysisService` uses `StructuredJsonCallExecutor` with repair. |
| Public UI | `AIFabricBehaviorSignals.tsx` calls `/api/behavior-demo/dashboard`, `/seed-and-analyze`, `/analyze`, `/signals`, and `/retention-offer`. |
| Shared demo state | Public endpoints use canonical users such as `user-1001`, `user-1002`, and `user-1003`. |
| Build metadata | Docker writes `/app/build-info.properties`, but the app does not yet expose a rich `/api/behavior-demo/health`. |

## Lessons-Learned Alignment

This plan applies these lessons directly:

- **Do not fake AI reasoning with frontend shortcuts.** The UI may offer workflow buttons, but it
  must not claim that frontend branching is model reasoning.
- **Policy decisions belong in the action result, not the UI.** Retention outcomes must explain
  their policy decision in backend data and messages.
- **Public demo sessions need isolation and cleanup.** Signal injection and retention actions write
  state, so shared canonical users should not be the only public path.
- **Validate the deployed bundle/backend, not only a commit stamp.** Behavior backend needs a health
  endpoint that exposes build metadata and runtime posture.
- **Backend action results stay structured; UI projects the useful fields.** The UI should show
  compact behavior insight and action summaries while keeping raw payloads out of the primary path.

These lessons are mostly not applicable unless the demo later grows a chat/RAG surface:

- RAG evidence/indexing lessons.
- `position` versus orchestration `mode`.
- app prompt overlays and `ai-fabric-chat-session`.
- payment/card handling.

## Decision

Evolve Behavior Signals into a truthful behavior-intelligence demo:

```text
Raw account behavior events
  -> AI Fabric behavior analysis
  -> AI-generated sentiment/churn/trend insight
  -> evidence-backed recommendation
  -> governed retention or operator decision
```

Keep deterministic local mode for CI and local development, but add a live-provider posture for the
public demo:

```text
local/dev/CI: behavior-local
public demo: OpenAI or another configured AI Fabric LLM provider
fallback: behavior-local only when explicitly selected
```

The UI and health endpoint must show which provider posture is active. If the demo is using
`behavior-local`, the page should call it an offline deterministic provider. If it is using OpenAI,
the page can truthfully say the behavior insight is live LLM-backed.

## Target AI Scenarios

### 1. Cancellation Risk

```text
Billing failures + login drop + cancellation message
  -> AI detects RAPIDLY_DECLINING trend
  -> AI explains churn reason: payment friction plus cancellation intent
  -> AI emits insights.action_family=RETENTION_OFFER
  -> Operator reviews the recommendation without customer-facing offer execution
```

Purpose:

- Proves churn detection from mixed event evidence.
- Proves behavior insight persistence.
- Proves AI-selected recommendation family plus backend validation.

### 2. Expansion Ready

```text
More seats + frequent usage + positive feedback
  -> AI detects healthy/improving trend
  -> AI recommends expansion/sales follow-up
  -> AI avoids unnecessary retention discount
```

Purpose:

- Proves the model does not always choose rescue/discount actions.
- Shows behavior intelligence can identify upside, not only risk.

### 3. Onboarding Friction

```text
Repeated help searches + setup complaints + low feature adoption
  -> AI detects confused sentiment and medium churn risk
  -> AI recommends onboarding help or support handoff
  -> Operator records an adoption task or guided outreach
```

Purpose:

- Proves nuanced classification between churn and adoption friction.
- Shows intervention can be education/support, not always discounting.

### 4. Release Regression Signal

```text
Feature errors + negative support notes + sudden usage drop after a release
  -> AI detects sudden sentiment/trend drop
  -> AI identifies likely product regression
  -> AI recommends engineering escalation instead of retention offer
```

Purpose:

- Shows behavior signals can expose product-health issues.
- Prevents the demo from being only a customer-success discount story.

### 5. Silent Churn

```text
No complaints + no tickets + steady usage decline
  -> AI detects quiet disengagement
  -> AI explains risk from absence/decline of activity
  -> AI recommends proactive check-in
```

Purpose:

- Proves behavior analysis can reason from missing/declining engagement, not only explicit negative text.
- Shows why behavior analytics matters before a user says "cancel".

## Product Goals

1. Make the behavior module's AI path visible and truthful.
2. Support both deterministic no-key local mode and live-provider public mode.
3. Add richer scenarios that map to real SaaS/account operations.
4. Keep operator workflow controls honest and separate from AI-generated insight.
5. Isolate public demo writes per visitor/session.
6. Make retention and escalation policy decisions self-explaining in backend results.
7. Expose backend build/runtime/provider metadata for deployment verification.

## Non-Goals

- Do not add RAG/vector search to this demo just to look like Shopping Experience.
- Do not add a chat surface unless a separate plan defines conversation behavior.
- Do not remove deterministic provider mode; it is still needed for CI and no-key local runs.
- Do not encode behavior policy explanations only in the frontend.
- Do not pretend `behavior-local` is a live LLM provider.
- Do not make frontend buttons secretly choose an "AI" answer.

## Backend Plan

### P0. Add Behavior Demo Health

Add:

```text
GET /api/behavior-demo/health
```

Response should include:

```json
{
  "app": "behavior-churn-signals",
  "version": "1.0.0-SNAPSHOT",
  "aiFabricVersion": "0.3.2",
  "commit": "...",
  "buildBranch": "...",
  "buildTime": "...",
  "buildMetadataSource": "file:/app/build-info.properties",
  "provider": "behavior-local|openai|...",
  "providerMode": "deterministic-local|live-external",
  "behaviorEnabled": true,
  "totalEvents": 17,
  "insights": 3,
  "checkedAt": "..."
}
```

Implementation notes:

- Reuse the build-info file pattern from Shopping Experience.
- Prefer `/app/build-info.properties` over runtime environment variables.
- Report the configured provider name from environment/config and, where possible, provider status.
- Add focused tests for file-backed metadata and provider mode mapping.

### P0. Make Provider Mode Explicit

Keep the default local profile:

```yaml
ai:
  providers:
    llm-provider: behavior-local
```

Add a documented live profile or deployment config:

```yaml
ai:
  providers:
    llm-provider: openai
    openai:
      enabled: true
      api-key: ${OPENAI_API_KEY:}
      model: ${OPENAI_MODEL:gpt-4o-mini}
```

Rules:

- Local tests keep `behavior-local`.
- Public deployment can use OpenAI keys when configured.
- Health must say which provider is active.
- UI must display "Offline deterministic provider" or "Live LLM provider".

### P0. Expand Scenario Catalog

Extend the scenario definitions from three to five:

```text
user-1001 cancellation-risk
user-1002 expansion-ready
user-1003 onboarding-friction
user-1004 release-regression
user-1005 silent-churn
```

Each scenario should define:

- account/customer name;
- plan;
- operator goal;
- default event timeline;
- default signal injection;
- expected intervention class;
- recommended action family;
- evidence labels that explain why.

Do not hardcode final insight results in the UI. The backend should seed events, run
`BehaviorAnalysisService`, and return the persisted `BehaviorInsights`.

### P0. Backend-Owned Policy Explanation

`RetentionStudioService` should return self-explaining analytics recommendation data:

```json
{
  "riskCategory": "HIGH",
  "actionFamily": "RETENTION_OFFER",
  "recommendation": "Offer retention credit; Assign CSM outreach",
  "policyExplanation": "AI Fabric accepted the LLM-selected action family RETENTION_OFFER for analytics review...",
  "evidenceIds": ["insight-acct-1001-user-1001", "plan-pro", "ai-action-retention_offer"]
}
```

Also add non-discount action recommendations:

```text
CSM_OUTREACH
ADOPTION_HELP
ENGINEERING_ESCALATION
EXPANSION_FOLLOW_UP
MONITOR_ONLY
```

This prevents the demo from implying every behavior insight should produce a retention discount.

### P1. Public Demo Session Isolation

Add session creation:

```text
POST /api/behavior-demo/sessions
```

Response:

```json
{
  "sessionId": "behavior-demo-session-...",
  "scenarios": [
    {
      "baseUserId": "user-1001",
      "userId": "behavior-demo-user-...-1001",
      "accountId": "behavior-demo-account-...-1001",
      "title": "Cancellation Risk"
    }
  ]
}
```

Rules:

- Canonical users remain templates.
- Public UI uses cloned session users.
- Signal injection and retention actions affect only cloned users.
- Dashboard can support either canonical or session-scoped view.

Cleanup:

```text
APP_BEHAVIOR_DEMO_CLEANUP_ENABLED=true
APP_BEHAVIOR_DEMO_CLEANUP_TTL=PT6H
APP_BEHAVIOR_DEMO_CLEANUP_CRON=0 */30 * * * *
```

Test:

- two sessions get different users;
- injecting a signal in one session does not change the other;
- cleanup deletes only expired demo-session users/events/insights;
- canonical users are never deleted.

### P1. Safer Seed/Reset Contract

Current UI may call global `/seed-and-analyze` when the backend has no data. That is acceptable for
development, but public reset behavior should be explicit.

Recommended endpoints:

```text
GET  /api/behavior-demo/dashboard
POST /api/behavior-demo/seed
POST /api/behavior-demo/seed-and-analyze
POST /api/behavior-demo/reset
```

Controls:

```text
APP_BEHAVIOR_DEMO_CONTROLS_ENABLED=true
APP_BEHAVIOR_DEMO_CONTROLS_API_KEY=<optional>
APP_BEHAVIOR_DEMO_AUTO_BOOTSTRAP=true|false
```

Rules:

- Seed should be idempotent.
- Reset should require `confirm=true`.
- Public UI should not reset shared global state on every page load.
- If session isolation exists, "Reset my session" should reset only the current browser session.

### P2. Optional Natural-Language Behavior Copilot

Only after the workflow demo is strong, consider adding:

```text
POST /api/behavior-demo/query
```

Possible questions:

```text
Why is Acme Finance at risk?
Which account needs immediate action?
Should we offer a discount or escalate to support?
What changed after I added this cancellation signal?
```

If added, follow the lessons:

- use AI Fabric orchestration, not frontend text matching;
- define a real behavior mode if orchestration modes are needed;
- use app prompt overlays for behavior-specific follow-ups;
- wire `ai-fabric-chat-session` if multi-turn memory is part of the product story;
- keep read/action allowlists bounded.

This is intentionally P2 because the behavior module can prove meaningful AI without chat.

## Frontend Plan

### P0. Make Provider Posture Visible

Add a small runtime card:

```text
Provider: behavior-local
Mode: offline deterministic
AI Fabric: behavior module
Build: <commit/time>
```

For live provider mode:

```text
Provider: openai
Mode: live LLM
Model: gpt-4o-mini
```

Do not label deterministic mode as "live AI".

### P0. Add Five Scenario Queue

Update the page to show:

1. Cancellation Risk
2. Expansion Ready
3. Onboarding Friction
4. Release Regression
5. Silent Churn

Each scenario card should show:

- customer/account;
- event count;
- observed signals;
- current AI Fabric insight status;
- recommended action family.

### P0. Show The Behavior Pipeline

Add an evidence panel that makes the module purpose obvious:

```text
Events ingested -> BehaviorAnalysisService -> AI insight -> recommendation -> governed action
```

For each scenario, show:

- event timeline;
- AI insight;
- patterns;
- churn risk;
- sentiment;
- trend;
- recommendations;
- evidence IDs.

### P0. Action Result Projection

Render retention/action outcomes from backend fields:

- `message`;
- `policyDecision`;
- `policyExplanation`;
- `evidenceIds`;
- `requestedDiscountPercent`;
- `discountPercent`;
- `actionFamily`.

Do not infer policy explanations in the UI.

### P1. Session-Isolated UI State

When backend session API exists:

- create or load a session id from local storage;
- call `/api/behavior-demo/sessions`;
- use returned cloned users for all scenario operations;
- provide "Reset my demo session";
- do not reset global seed data on refresh.

### P1. Served Bundle Verification Marker

Add a harmless marker string to the page:

```text
behavior-signals-ai-hardening-2026-07-06
```

After deployment, verify the served JS bundle contains it before testing live behavior.

## Tests

### Backend Unit/Integration Tests

Add or update:

- health endpoint reads `/app/build-info.properties` style metadata;
- provider mode maps `behavior-local` to deterministic and `openai` to live external;
- five scenario definitions are present and seed expected event families;
- `seed-and-analyze` analyzes all scenarios;
- retention policy returns `policyDecision` and `policyExplanation`;
- discount cap is explicit in data and message;
- session creation isolates cloned users;
- cleanup deletes only expired cloned sessions.

### Frontend Checks

Add or manually verify:

- provider posture card shows deterministic/live mode correctly;
- scenario queue has five scenarios;
- "Record signal" changes only selected session scenario;
- retention action card shows backend policy explanation;
- served bundle marker exists after deploy.

### Live Smoke

Run after backend deploy:

```bash
curl -fsS https://behavior-churn-signals.46.224.145.148.sslip.io/api/behavior-demo/health | jq
curl -fsS -X POST https://behavior-churn-signals.46.224.145.148.sslip.io/api/behavior-demo/sessions \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"live-smoke-1","analyze":true}' | jq
curl -fsS https://behavior-churn-signals.46.224.145.148.sslip.io/api/behavior-demo/dashboard?sessionId=live-smoke-1 | jq '.scenarios | length'
curl -fsS -X POST https://behavior-churn-signals.46.224.145.148.sslip.io/api/behavior-demo/scenarios/behavior-demo-user-live-smoke-1-user-1004/analyze | jq
curl -fsS -X POST https://behavior-churn-signals.46.224.145.148.sslip.io/api/behavior-demo/scenarios/behavior-demo-user-live-smoke-1-user-1001/agentic-ui | jq
```

Expected:

- health exposes commit/build/provider mode;
- dashboard has five scenarios;
- release regression recommends engineering/product escalation, not discount-first;
- behavior recommendations include a valid LLM-selected `insights.action_family`;
- agentic UI planning returns allowlisted components, or fails visibly if the LLM output is invalid.

## Implementation Evidence

Implemented on 2026-07-06:

- backend health/session/reset APIs in `BehaviorDemoController`;
- five scenario catalog, session-cloned users, and session reset in `BehaviorDemoScenarioService`;
- richer deterministic behavior provider signals for cancellation, expansion, onboarding friction,
  release regression, and silent churn;
- backend-owned retention policy explanations in `RetentionStudioService`;
- scheduled cleanup for old `behavior-demo-user-*` session clones;
- README and HTTP examples updated for deterministic and live-provider modes;
- public UI updated to create a browser session, show provider/build posture, show the behavior
  pipeline, render five scenarios, and project action result fields without raw JSON.
- follow-up hardening requires LLM analysis failures and agentic UI composition failures to surface
  visibly instead of falling back to synthetic recommendations.

Verified locally:

```bash
mvn -q -f examples/real-apps/pom.xml -pl behavior-churn-signals -am test
npm run build
PORT=18097 AI_LLM_PROVIDER=behavior-local \
  mvn -q -f examples/real-apps/pom.xml -pl behavior-churn-signals spring-boot:run
```

Local API smoke proved:

- `/api/behavior-demo/health` reports `behavior-local` / `deterministic-local`;
- `/api/behavior-demo/sessions` creates five cloned scenarios and 33 events;
- release-regression analysis returns `ENGINEERING_ESCALATION`;
- signal injection mutates only the session user;
- action recommendations come from validated `insights.action_family`.

## Rollout Plan

### Phase 1 - Truthful AI Posture

- Add behavior health endpoint.
- Expose provider mode.
- Update README to distinguish deterministic local mode from live provider mode.
- Update UI runtime card.

### Phase 2 - Scenario Expansion

- Add release regression and silent churn.
- Strengthen expansion/onboarding/cancellation seed data.
- Add tests for scenario catalog and analysis.

### Phase 3 - Backend Policy Truth

- Enrich retention/action result with policy fields.
- Add non-discount action families.
- Update UI projection.

### Phase 4 - Public Session Hardening

- Add session clone API.
- Add TTL cleanup.
- Update UI to use session users.

### Phase 5 - Optional Behavior Copilot

- Decide whether natural-language behavior chat is useful.
- If yes, implement using AI Fabric orchestration, prompt overlays, and chat-session.

## Acceptance Criteria

Behavior Signals is considered hardened when:

- the public UI clearly says whether insights are deterministic-local or live-provider-backed;
- backend health exposes commit/build/provider mode;
- the demo has five realistic behavior scenarios;
- the backend, not the UI, explains retention/action policy decisions;
- public users do not mutate each other's scenario state;
- the README documents local no-key mode and live-provider deployment mode;
- focused tests cover scenario catalog, policy explanation, health, and session isolation;
- live smoke proves the deployed backend matches the expected behavior.
