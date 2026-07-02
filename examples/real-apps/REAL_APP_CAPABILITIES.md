# AI Fabric Real App Capability Guide

This file explains what each real app proves about AI Fabric. Use it when deciding which app to run
for a release check, demo, or framework capability review.

The apps are intentionally small and scenario-focused. They are not meant to be production products;
they are executable evidence that a framework capability works in a realistic Spring Boot app shape.

## Quick Coverage Map

| Capability | Primary real apps |
| --- | --- |
| No-key smoke boot and deterministic local providers | `smoke-support`, most apps under `--spring.profiles.active=smoke` |
| Config-driven entity indexing and local semantic search | `smart-faq-assistant`, `sub-management-hub-simple`, `migration-enabled-product-catalog` |
| Annotation-assisted indexing | `ai-fabric-account-resolver`, `cloud-qdrant-openai-vector-search` |
| RAG and retrieval evidence | `smart-faq-assistant`, `chat-capabilities-demo` |
| RAG quality gates and golden questions | `smart-faq-assistant` |
| Migration/backfill indexing | `migration-enabled-product-catalog` |
| PII detection/redaction | `privacy-first-customer-facing-support`, `chat-capabilities-demo` |
| Governance deletion/catalog evidence | `privacy-first-customer-facing-support`, `tenant-knowledge-portal`, `vector-readiness-playground` |
| Chat sessions and conversation-aware orchestration | `chat-capabilities-demo` |
| Local `@AIAction` discovery/execution | `chat-capabilities-demo`, `it-support-action-bot`, `ai-fabric-account-resolver`, `sub-management-hub-simple` |
| Action confirmation and pending-action flows | `chat-capabilities-demo`, `it-support-action-bot`, `customer-runtime-demo`, `db-action-registry-lab` |
| Connector action execution | `customer-runtime-demo`, `db-action-registry-lab`, `mcp-operations-assistant` |
| DB-backed action registry | `db-action-registry-lab` |
| MCP-style tool execution through AI Fabric action policy | `mcp-operations-assistant` |
| Data-sync/customer-runtime shape | `customer-runtime-demo`, `ecommerce-store` with `chat-capabilities-demo` |
| Tenant-scoped search and role-limited actions | `tenant-knowledge-portal`, `customer-runtime-demo` |
| Relationship query / natural language to JPQL | `relationship-query-crm-insights` |
| Behavior analytics and retention action story | `behavior-churn-signals` |
| Document ingestion and chunk lifecycle | `document-ingestion-workbench` |
| Provider fallback and safe diagnostics | `provider-failover-lab` |
| Vector lifecycle/admin readiness | `vector-readiness-playground` |
| Real external vector/provider stack | `cloud-qdrant-openai-vector-search` |

## App Details

### `smoke-support`

**Role:** shared support module for the real-app suite, not a product demo.

**Scenario:** Provide deterministic local AI and vector dependencies so apps can boot and test without
external API keys.

**Capabilities proved:**

- Local `AIProvider` named `smoke`.
- Local deterministic `EmbeddingProvider`.
- In-memory vector provider and H2-friendly smoke defaults.
- Shared `application-smoke.yml` profile used by real-app boot checks.

**Runtime posture:** no external services, no model keys.

**Best used for:** CI smoke support and local release checks.

### `smart-faq-assistant`

**Scenario:** Offline semantic search over a curated FAQ knowledge base, with optional RAG answer
generation and a local RAG quality workbench.

**Capabilities proved:**

- Config-driven AI entity indexing.
- H2 plus deterministic local embeddings plus Lucene vector search.
- FAQ seed, reindex, semantic search, and optional answer generation.
- Golden-question RAG quality gate with expected article evidence.
- Optional Spring AI-backed RAG evaluation when a real `ChatClient.Builder` is configured.
- Fail-closed quality behavior when expected retrieval evidence is missing.

**AI Fabric surfaces:** `ai-fabric-starter`, `ai-fabric-rag`, `ai-fabric-vector-lucene`, indexing,
retrieval evidence, optional Spring AI evaluation.

**Runtime posture:** local by default; optional LLM/Spring AI evaluation profile.

**Best used for:** RAG quality demos, local semantic search checks, and release evidence for
retrieval relevance.

### `migration-enabled-product-catalog`

**Scenario:** Bulk product backfill into AI Fabric indexing with resumable migration jobs.

**Capabilities proved:**

- Migration/backfill job creation and progress tracking.
- Minimal `@AICapable` entity discovery for migration.
- Async indexing queue integration.
- Product search after backfill.
- Pause, resume, cancel, and retry-oriented migration flow.

**AI Fabric surfaces:** `ai-fabric-migration-core`, `ai-fabric-starter`, indexing queue, Lucene vector
provider, local embeddings.

**Runtime posture:** local H2, deterministic embeddings, Lucene; no external model keys.

**Best used for:** proving a brownfield app can backfill existing records into AI Fabric search.

### `privacy-first-customer-facing-support`

**Scenario:** Customer support messages containing PII are accepted, redacted, and stored with safe
evidence.

**Capabilities proved:**

- PII detection and redaction before storage.
- Encrypted-original or hash-original behavior depending on configured secret.
- Privacy-safe support records.
- Governance/deletion evidence around customer support data.
- PII workflow without requiring vector search or RAG.

**AI Fabric surfaces:** PII detection/redaction, governance, optional catalog/deletion evidence,
starter auto-configuration.

**Runtime posture:** local H2 and smoke support; no external model keys required.

**Best used for:** privacy-first release checks and showing fail-closed sensitive-data handling.

### `relationship-query-crm-insights`

**Scenario:** Natural language CRM questions are translated into structured relationship queries over
accounts, deals, contacts, and support tickets.

**Capabilities proved:**

- Natural language to JPQL/traversal using the relationship-query module.
- JPA metamodel discovery over a realistic CRM schema.
- Deterministic offline LLM provider for repeatable tests.
- Structured planner output parsing and bounded repair behavior.
- Revenue-copilot workflow with allowlisted entity access, target validation, and evidence summaries.

**AI Fabric surfaces:** `ai-fabric-relationship-query`, provider starter, structured output parsing,
relationship query planning.

**Runtime posture:** offline deterministic LLM; no vector DB or embeddings required.

**Best used for:** proving AI Fabric can reason over relational business data without a vector store.

### `behavior-churn-signals`

**Scenario:** Behavior events produce churn and sentiment insights that can drive retention actions.

**Capabilities proved:**

- Behavior event ingestion and analysis.
- `ExternalEventProvider` style integration.
- Stored behavior insights and analytics endpoints.
- Deterministic sentiment/churn behavior in local tests.
- Retention-studio flow with behavior/plan evidence and confirmation-gated offers.

**AI Fabric surfaces:** `ai-fabric-behavior`, provider starter, relationship/business context, governed
retention action pattern.

**Runtime posture:** offline deterministic LLM and H2.

**Best used for:** behavior intelligence demos and multi-step recommendations based on event history.

### `chat-capabilities-demo`

**Scenario:** Commerce chat runtime with product catalog RAG, chat sessions, and governed actions.

**Capabilities proved:**

- Chat-session storage and conversation-aware orchestration.
- Product catalog CRUD plus indexing into Lucene.
- RAG over products/catalog material.
- Local actions for cart, orders, reviews, support, addresses, shipments, and account reads.
- Confirmation-required write actions and pending-action handling.
- Confirmation interceptor behavior for cancellation/retention paths.
- Runtime vector probe endpoint for raw retrieval evidence.
- CORS and UI request contract support for a browser client.

**AI Fabric surfaces:** `ai-fabric-starter`, chat-session module, RAG, indexing, Lucene, action
annotations, confirmation policy, curated commerce behavior, data-sync runtime endpoints.

**Runtime posture:** smoke profile can boot locally; full RAG/action extraction can use OpenAI
configuration.

**Best used for:** broad product demo of AI Fabric as a chat/runtime layer.

### `customer-runtime-demo`

**Scenario:** A customer-owned domain fixture emits records and actions through AI Fabric
runtime-style contracts.

**Capabilities proved:**

- Data-sync upsert/delete DTO payloads.
- Tenant-scoped retrieval and search behavior.
- Governed write actions that require confirmation.
- Structured connector outage response instead of raw client exceptions.
- Customer-owned system boundary without needing the relay module.

**AI Fabric surfaces:** `ai-fabric-data-sync`, action connector patterns, tenant metadata, action
confirmation, retrieval/customer runtime boundary.

**Runtime posture:** local test fixture; no external services required.

**Best used for:** explaining how AI Fabric fits beside a customer-owned Java app.

### `db-action-registry-lab`

**Scenario:** DB-backed connector actions are proposed, approved, published, discovered, executed,
and deregistered.

**Capabilities proved:**

- Controlled action proposals before runtime publication.
- `ConnectorActionRegistryService.register` and DB persistence.
- Runtime `AIActionRegistry` refresh after DB publication.
- Connector-handler execution against a customer ticket fixture.
- Confirmation-required write-like action before mutation.
- API-key protection on raw action registry endpoints.
- Deregistration from DB and runtime availability.

**AI Fabric surfaces:** `ai-fabric-actions-registry`, connector action handler path,
`AIActionRegistry`, registry API-key filter, H2/JPA persistence.

**Runtime posture:** local H2; no model keys, vector DB, or external connector process.

**Best used for:** proving the DB action registry lifecycle before release.

### `document-ingestion-workbench`

**Scenario:** Trusted knowledge-base documents are uploaded, previewed, chunked, indexed, reindexed,
and deleted.

**Capabilities proved:**

- Spring AI document readers feeding AI Fabric indexing requests.
- Trusted-resource policy around document input.
- Chunk manifest lifecycle.
- Re-upload/reindex queues stale chunk deletes and new updates.
- Source deletion queues deletes for every indexed chunk.
- Metadata normalization and sanitization before indexing payload evidence.
- Unsupported file handling fails closed.

**AI Fabric surfaces:** `ai-fabric-indexing`, Spring AI document reader bridge,
`IndexingQueueService`, Lucene/memory vector provider depending on profile.

**Runtime posture:** local fixtures; smoke profile uses deterministic providers.

**Best used for:** document ingestion, reindex, and delete lifecycle demos.

### `it-support-action-bot`

**Scenario:** Provider-only IT support action bot with no vector DB, indexing, or RAG dependency.

**Capabilities proved:**

- LLM-only action orchestration path.
- Local `@AIAction` discovery and execution.
- `@ActionAllowed` authorization hooks.
- `@ActionConfirmation` confirmation for ticket mutations.
- Support operations workflow with runbook evidence, severity classification, escalation, and
  customer-safe summaries.
- RAG-disabled fallback remains usable.

**AI Fabric surfaces:** provider starter, Spring AI provider integration, local action annotations,
confirmation policy, post-action summary pattern.

**Runtime posture:** can run with smoke support for deterministic checks; real LLM provider is
optional for provider-only behavior.

**Best used for:** showing AI Fabric can build action bots without retrieval/vector infrastructure.

### `mcp-operations-assistant`

**Scenario:** MCP-style operations tools execute behind AI Fabric action governance.

**Capabilities proved:**

- `McpActionExecutor` bridge contract.
- Read/write action access modes.
- Confirmation policy for write/destructive operations.
- Unknown tool and connector failure handling.
- Hidden connector context stripped from user-facing tool output.

**AI Fabric surfaces:** `ai-fabric-actions-connector`, MCP executor abstraction, action access modes,
confirmation policy, sanitized action results.

**Runtime posture:** local deterministic executor; no external MCP server required.

**Best used for:** explaining how Spring AI/MCP tool capability can be governed by AI Fabric action
policy.

### `provider-failover-lab`

**Scenario:** Provider routing, fallback, safe diagnostics, and transient input evidence.

**Capabilities proved:**

- Primary provider failure falls back to a configured secondary provider.
- Provider attempts are exposed as safe diagnostics.
- Errors include safe provider/error categories without leaking prompts or secrets.
- Transient file URL policy evidence: seen for the call, not persisted/logged/indexed.
- Token/model evidence when a provider returns it.

**AI Fabric surfaces:** `AIProvider`, provider starter, Spring AI provider integration, fallback
diagnostics, transient input policy.

**Runtime posture:** smoke support by default; real providers can be used when configured.

**Best used for:** release checks around provider resilience and safe observability.

### `sub-management-hub-simple`

**Scenario:** Minimal subscription management app using configuration-driven indexing without AI
annotations in app code.

**Capabilities proved:**

- Config-first entity indexing through `ai-entity-config.yml`.
- Deterministic local embeddings plus Lucene vector search.
- Explicit reindex endpoint for seeded subscription plans.
- App-level plan search/recommendation without exposing framework internals.
- Async queue validation endpoints for enqueue, run-once, and queue inspection.

**AI Fabric surfaces:** `ai-fabric-starter`, Spring AI provider dependency, Lucene vector provider,
indexing queue, configuration-driven entity model.

**Runtime posture:** local H2 and deterministic providers.

**Best used for:** onboarding users who want the smallest config-first integration example.

### `ai-fabric-account-resolver`

**Scenario:** Account resolver app showing how AI Fabric can diagnose account blockers and execute
governed subscription, payment, address, and refund/account-credit actions.

**Capabilities proved:**

- Annotation-assisted indexing.
- Read-action grounding through account readiness inspection.
- Subscription actions such as subscribe, upgrade, downgrade, cancel, and address update.
- Account resolver actions such as payment-method update and refund/account-credit request.
- `@ActionAllowed` authorization hooks.
- `@ActionConfirmation` on write actions.
- Sync and async indexing validation endpoints.

**AI Fabric surfaces:** `ai-fabric-starter`, action annotations, indexing queue, Lucene vector
provider, deterministic local embeddings.

**Runtime posture:** local H2 and deterministic providers.

**Best used for:** demonstrating account-resolution workflows where AI proposes a governed action,
asks for confirmation, and returns structured post-action readiness evidence.

### `tenant-knowledge-portal`

**Scenario:** Tenant-aware knowledge search, catalog inspection, role-limited actions, and tenant
deletion.

**Capabilities proved:**

- Same-title documents in multiple tenants return only the caller tenant's result.
- Admin catalog visibility differs from regular user visibility.
- Cross-tenant action targets are rejected.
- Tenant deletion removes only selected tenant documents/catalog entries.
- Tenant metadata is part of search, action, and deletion evidence.

**AI Fabric surfaces:** tenant metadata, metadata filters, governance catalog, role-limited actions,
deletion lifecycle.

**Runtime posture:** local deterministic smoke path.

**Best used for:** proving tenant and role boundaries in AI workflows.

### `vector-readiness-playground`

**Scenario:** Inspect vector provider lifecycle/admin readiness and run a small store/delete check.

**Capabilities proved:**

- Vector readiness report with `READY`, `WARN`, or `NOT_READY`.
- `VectorDatabaseService.adminDiagnostics()` evidence.
- Store, existence check, and delete lifecycle.
- Metadata evidence included in lifecycle run.
- Default smoke path uses in-memory provider.

**AI Fabric surfaces:** `VectorDatabaseService`, vector provider diagnostics, lifecycle/admin API.

**Runtime posture:** local memory provider by default; provider-specific profiles can be layered in.

**Best used for:** vector provider readiness checks and explaining AI Fabric's provider lifecycle API.

### `ecommerce-store`

**Role:** prior deployed domain API fixture retained as reference material.

**Scenario:** Customer-owned ecommerce domain API for products, carts, orders, coupons, policies,
reviews, and reset endpoints.

**Capabilities proved:**

- Domain app can remain independent from AI Fabric runtime.
- Product/policy/review changes can emit event-based indexing requests.
- Domain API can push data-sync events into `chat-capabilities-demo` runtime.
- Runtime vector search can see created products and stop seeing deleted products.
- Demo reset/clear APIs support repeatable migration/runtime proof.
- Public anonymous/authz reference logic exists for ecommerce runtime policy experiments.

**AI Fabric surfaces:** data-sync client shape, customer-domain boundary, external runtime proof with
`chat-capabilities-demo`.

**Runtime posture:** local H2; optional runtime integration points to another app/process.

**Best used for:** external runtime proof and customer-owned domain fixture demos. It is not a new
expansion target in ADR 0005.

### `cloud-qdrant-openai-vector-search`

**Scenario:** Production-like semantic search with Postgres, Qdrant, and OpenAI embeddings.

**Capabilities proved:**

- External vector DB configuration using Qdrant.
- OpenAI embeddings through the provider stack.
- Postgres as domain storage.
- Config-driven indexing/search via `ai-entity-config.yml`.
- App writes can call AI Fabric indexing APIs and then search through `AICoreService.performSearch`.

**AI Fabric surfaces:** `ai-fabric-starter`, Qdrant vector provider, OpenAI embeddings/provider
configuration, annotation/config indexing.

**Runtime posture:** requires Docker services and OpenAI credentials for full runtime validation.

**Best used for:** cloud/provider demo and real external vector DB shape, not default no-key CI.

## Recommended App Selection

| Need | Start with |
| --- | --- |
| Explain AI Fabric quickly to a Java/Spring user | `smart-faq-assistant`, then `chat-capabilities-demo` |
| Prove no-key local release boot | `smoke-support` plus any app under `--spring.profiles.active=smoke` |
| Prove RAG quality | `smart-faq-assistant` |
| Prove action governance | `chat-capabilities-demo`, `it-support-action-bot`, `db-action-registry-lab` |
| Prove connector/runtime boundary | `customer-runtime-demo`, `ecommerce-store` with `chat-capabilities-demo` |
| Prove tenant and governance boundaries | `tenant-knowledge-portal`, `privacy-first-customer-facing-support` |
| Prove document ingestion | `document-ingestion-workbench` |
| Prove provider resilience | `provider-failover-lab` |
| Prove vector provider lifecycle/admin API | `vector-readiness-playground` |
| Prove real cloud vector/provider wiring | `cloud-qdrant-openai-vector-search` |

## Suite-Level Verification

Use this command for the standard local suite:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml test
```

Use this command to package all real apps after framework artifacts are installed:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml package
```

Cloud/provider-specific scenarios should stay explicit and opt-in because they require external
services or credentials.
