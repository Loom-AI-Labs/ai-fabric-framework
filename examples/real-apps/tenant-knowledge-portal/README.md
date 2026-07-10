# AI Fabric Tenant Guard

## Scenario

This app powers the **AI Fabric Tenant Guard** public demo. It demonstrates tenant-aware knowledge
search, catalog inspection, role-limited actions, and tenant deletion.

It is a focused proof that AI workflows can respect tenant and role boundaries even when documents
have overlapping titles or similar content.

## AI Fabric Capabilities Proved

- Same document title in two tenants returns only the caller tenant's result.
- Tenant metadata is used in retrieval and catalog evidence.
- Tenant documents are indexed into AI Fabric vector storage with `sessionId`, `tenantId`, and
  `visibleToUser` metadata.
- RAG answers are generated through AI Fabric LLM generation after backend evidence verification.
- Generated answers return citations that match the backend evidence list.
- Natural-language actions are resolved through AI Fabric LLM orchestration, then enforced by the app
  policy engine.
- Admin catalog visibility differs from regular user visibility.
- Cross-tenant action targets are rejected.
- Tenant deletion removes only the selected tenant's documents/catalog entries.
- Role-limited actions can be modeled as product workflows.
- Public demo endpoints expose a repeatable browser-friendly Tenant Guard workflow.
- Browser-session scoped demo data keeps one visitor's delete/write experiments from affecting
  another visitor's proof state.
- Backend action and deletion responses include policy decisions and explanations so the frontend
  does not infer governance rules.

## Framework Surfaces

- tenant/context metadata
- metadata filters
- AI Fabric vector search through `AICoreService.performSearch`
- LLM answer generation through `AICoreService.generateContent(..., LlmPurpose.GENERATION)`
- structured natural-language action resolution through `AICoreService.generateContent(..., LlmPurpose.ORCHESTRATION)`
- governance catalog pattern
- role-limited actions
- deletion lifecycle
- deterministic local smoke profile

## Runtime Posture

Local Maven runtime is deterministic unless you provide live provider settings. Docker/public-demo
runtime defaults to real OpenAI-backed AI and fails closed if the smoke provider is selected.

- H2/in-memory fixtures
- Lucene vector index by default
- smoke profile supported for no-key startup and local wiring checks
- OpenAI/Spring AI provider settings can be supplied for live LLM/embedding generation
- `APP_DEMO_REQUIRE_REAL_AI=true` prevents smoke responses from appearing as live AI in the public demo.

## Public Demo App

This app backs the public demo page:

- Demo UI: `https://ai-fabric.dev/demos/ai-fabric-tenant-guard`
- Expected backend runtime: `https://ai-fabric-tenant-guard.46.224.145.148.sslip.io`
- Demo API base path: `/api/tenant-guard-demo`

The demo shows:

1. The same search query returning tenant-specific evidence for tenant A and tenant B.
2. Platform admin catalog visibility compared with tenant user visibility.
3. A cross-tenant write action rejected by policy.
4. A same-tenant write action that requires confirmation.
5. A natural-language action that is first resolved by the LLM and then enforced by backend policy.
6. Tenant deletion evidence that removes only the selected tenant's documents and vectors.

## Demo Backend App Architecture

This is the backend for the `aifabric` Tenant Guard UI. It is a small, deterministic app that shows
the tenant and role boundaries AI Fabric applications must preserve around retrieval and actions.

Backend dependencies:

- Spring Boot Web, Actuator, and Lombok.
- AI Fabric modules: `ai-fabric-starter`, `ai-fabric-governance`, `ai-fabric-vector-lucene`, and
  `ai-fabric-provider-spring-ai`.
- `smoke-support` for shared build metadata.

AI-enabled domain model:

- This demo does not use `@AICapable` annotations. It uses explicit in-memory `KnowledgeDocument`
  records so the tenant-boundary behavior is easy to inspect.
- Each document carries tenant metadata and visibility; the service exposes search hits, catalog
  entries, action decisions, and tenant-deletion evidence with that metadata intact.
- `ActionAccessMode` models read/write action access so the UI can show why write attempts require
  role checks and confirmation.
- RAG answer generation is LLM-backed, but only after the app verifies returned vector evidence is
  inside the trusted tenant/session/visibility boundary.
- Natural-language action resolution returns a JSON action draft. The draft cannot execute directly;
  `TenantKnowledgeService` still performs target, tenant, role, registered-action, and confirmation checks.

Providers and storage:

- Default provider ids are smoke/local through configuration.
- The default vector DB is Lucene, with metadata-filtered search and scan proof exposed through
  `/api/tenant-guard-demo/index/proof`.
- Demo data lives in the in-memory `TenantKnowledgeService` and can be reset through
  `/api/tenant-guard-demo/reset`.

Request and data flow:

1. The UI calls `/api/tenant-guard-demo/dashboard` to load seeded tenant scenarios and current proof
   state. Public UI calls include a `sessionId` query parameter so mutations are isolated per browser.
2. `/compare?q=...` runs the same query as tenant A, tenant B, and platform admin so the UI can show
   scoped retrieval side by side.
3. `/query` indexes and searches tenant documents through AI Fabric, verifies evidence, then calls LLM
   generation with only the allowed evidence.
4. `/actions/nl` calls LLM orchestration for a structured action draft, then validates the draft
   through the same backend policy path as manual actions.
5. `/actions/execute` validates target tenant, role, access mode, and confirmation before returning an
   action decision with `policyDecision` and `policyExplanation` evidence.
6. `/tenants/delete` removes only the requested tenant's documents and returns the deleted ids as
   evidence plus remaining tenant ids.
7. `/api/demo/health` exposes deployment commit/build metadata for live verification.
8. The frontend renders policy outcomes from backend decisions; it does not infer tenant access
   locally.

## Run Locally

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl tenant-knowledge-portal -am package
java -jar examples/real-apps/tenant-knowledge-portal/target/tenant-knowledge-portal-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke
```

Default port: `8101`.

Verify the local demo API:

```bash
curl -fsS http://localhost:8101/actuator/health
curl -fsS http://localhost:8101/api/demo/health | jq
curl -fsS http://localhost:8101/api/tenant-guard-demo/dashboard | jq
curl -fsS -X POST http://localhost:8101/api/tenant-guard-demo/reset | jq
```

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl tenant-knowledge-portal -am test
```

Use `requests/demo.http` to run the tenant boundary scenario.

## Public Demo Endpoints

- `GET /api/tenant-guard-demo/dashboard`
- `GET /api/tenant-guard-demo/dashboard?sessionId=browser-123`
- `POST /api/tenant-guard-demo/reset`
- `GET /api/tenant-guard-demo/compare?q=VPN`
- `POST /api/tenant-guard-demo/actions/execute`
- `POST /api/tenant-guard-demo/actions/nl`
- `POST /api/tenant-guard-demo/query`
- `POST /api/tenant-guard-demo/index/seed`
- `GET /api/tenant-guard-demo/index/proof`
- `POST /api/tenant-guard-demo/tenants/delete`
- `GET /api/demo/health`

## Demo Flow

1. Seed documents for two tenants with overlapping titles.
2. Search as a tenant user and verify only that tenant's documents are returned.
3. Inspect catalog as a regular user.
4. Inspect catalog as an admin.
5. Attempt a cross-tenant action target and verify rejection.
6. Delete one tenant and verify the other tenant's data remains.
7. Run a natural-language archive action and verify the LLM-selected draft still requires backend
   policy approval.
8. Open a second session id and verify the deletion did not leak across browser sessions.

## Docker

Build from the repo root:

```bash
docker build -f examples/real-apps/tenant-knowledge-portal/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.3.3 \
  --build-arg BUILD_COMMIT="$(git rev-parse --short HEAD)" \
  --build-arg BUILD_BRANCH="$(git rev-parse --abbrev-ref HEAD)" \
  --build-arg BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  -t ai-fabric-tenant-guard:0.3.3 \
  examples/real-apps
```

Run the image:

```bash
docker run --rm -p 8101:8101 \
  -e PORT=8101 \
  -e CORS_ALLOWED_ORIGINS=https://ai-fabric.dev \
  ai-fabric-tenant-guard:0.3.3
```

Suggested deployment values:

- `PORT=8101`
- `CORS_ALLOWED_ORIGINS=https://ai-fabric.dev`
- `JAVA_OPTS=-Xms256m -Xmx768m`
- `SPRING_PROFILES_ACTIVE=production` to avoid the local `smoke` profile. The Docker image sets this
  by default.
- `AI_LLM_PROVIDER=openai`, `AI_EMBEDDING_PROVIDER=openai`, `OPENAI_ENABLED=true`, and
  `APP_DEMO_REQUIRE_REAL_AI=true` are Docker defaults for the public demo.
- `OPENAI_API_KEY`, `OPENAI_MODEL`, and `OPENAI_EMBEDDING_MODEL` must be supplied for live
  LLM/embedding behavior.
- Optional deployment metadata: `APP_VERSION`, `AI_FABRIC_VERSION`, `APP_BUILD_COMMIT`, `APP_BUILD_BRANCH`, `APP_BUILD_TIME`.
- `git_repository=Loom-AI-Labs/ai-fabric-framework.git`
- `git_branch=main`
- `base_directory=/examples/real-apps`
- `dockerfile_location=/tenant-knowledge-portal/Dockerfile`
- `ports_exposes=8101`

## What This App Does Not Cover

- Public browser token issuance. That is platform/runtime-auth territory.
- Live shared vector storage providers. Use provider/vector integration tests.
- Marketplace/shared-index data plugins. Those belong to platform verification.
