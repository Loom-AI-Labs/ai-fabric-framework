# ADR 0005 - Real app use case expansion plan for AI Fabric

- **Status:** Proposed
- **Date:** 2026-06-20
- **Decision owner:** AI Fabric framework
- **Context version:** AI Fabric `0.2.1`, Java `21`, Spring Boot `4.1.0`, Spring AI `2.0.0`
- **Depends on:** ADR 0002 Spring AI LLM and embedding execution, ADR 0003 Spring AI capability adoption plan, ADR 0004 vector provider hardening plan

## Context

The `examples/real-apps` suite is now strong enough to act as release evidence, not just sample code.
It already validates AI Fabric in realistic Spring Boot application shapes:

- local-first semantic search and indexing;
- migration/backfill indexing;
- PII redaction;
- natural language relationship query;
- behavior analysis;
- chat sessions, RAG, and actions;
- provider-only action bots;
- subscription plan recommendation;
- external domain API and data-sync shape;
- Qdrant plus OpenAI cloud vector search;
- smoke-profile boot without keys or external services.

The next step should be to turn these examples into a smaller set of high-value, real-world scenarios
that prove why AI Fabric exists as a Java AI enablement framework:

```text
Spring AI and provider SDKs
  -> model calls, embeddings, tool protocol plumbing, model client integrations

AI Fabric
  -> product workflows, entity indexing lifecycle, governed action execution,
     retrieval policy, connector/runtime boundaries, PII safety, vector lifecycle/admin,
     tenant/context metadata, migration/backfill, chat sessions, and release gates
```

This plan focuses on two tracks:

1. Deepen the current real apps so they tell complete product stories.
2. Add only the missing apps needed to cover AI Fabric surfaces that are not yet visible in
   `examples/real-apps`.

## Current real app coverage

| App | Current scenario | AI Fabric surfaces proved | Evidence |
| --- | --- | --- | --- |
| `smart-faq-assistant` | Offline FAQ semantic search and optional answer generation | Config-driven indexing, local deterministic embeddings, Lucene vector search, optional RAG | `examples/real-apps/smart-faq-assistant/README.md`, `requests/demo.http` |
| `migration-enabled-product-catalog` | Bulk product backfill and resumable indexing migration | `ai-fabric-migration-core`, indexing queue, `AICoreService.performSearch`, local embeddings, Lucene | `examples/real-apps/migration-enabled-product-catalog/README.md` |
| `privacy-first-customer-facing-support` | Customer support message PII redaction and encrypted/hash original storage | `ai-fabric-pii`, fail-closed privacy behavior, no vector/RAG dependency | `examples/real-apps/privacy-first-customer-facing-support/README.md` |
| `relationship-query-crm-insights` | Natural language to JPQL over CRM accounts, deals, contacts, tickets | Relationship query planner, JPA metamodel discovery, deterministic LLM provider | `examples/real-apps/relationship-query-crm-insights/README.md` |
| `behavior-churn-signals` | Behavior event analysis and churn/sentiment insights | `ai-fabric-behavior`, `ExternalEventProvider`, stored behavior insights, analytics endpoints | `examples/real-apps/behavior-churn-signals/README.md` |
| `chat-capabilities-demo` | Commerce chat with catalog RAG, chat sessions, and cart/order actions | `ai-fabric-starter`, curated commerce, chat-session, governance, indexing, RAG, Lucene, Spring AI provider | `examples/real-apps/chat-capabilities-demo/README.md` |
| `it-support-action-bot` | Provider-only IT ticket action bot | LLM-only action orchestration, Spring AI provider, no vector/indexing/RAG requirement | `examples/real-apps/it-support-action-bot/README.md` |
| `sub-management-hub-simple` | Config-first subscription plan search and natural language recommendation | Config-driven AI setup, Lucene, local embeddings, explicit reindex, product-level fallback | `examples/real-apps/sub-management-hub-simple/README.md` |
| `sub-management-hub` | Annotation-assisted subscription plan search | Annotation plus config setup, behavior and relationship-query dependencies, Lucene | `examples/real-apps/sub-management-hub/README.md` |
| `ecommerce-store` | Domain API fixture for products, carts, orders, coupons, policies, reviews | Connector/runtime boundary, event-based indexing producer shape, demo reset/clear APIs | `examples/real-apps/ecommerce-store/README.md`, `requests/demo.runtime.http` |
| `cloud-qdrant-openai-vector-search` | Production-like semantic search with Postgres, Qdrant, and OpenAI | Cloud embeddings, external vector DB, annotation-driven indexing/search, provider configuration | `examples/real-apps/cloud-qdrant-openai-vector-search/README.md` |
| `smoke-support` | Shared smoke profile for no-key/no-service boot | Deterministic local AI provider, deterministic embeddings, memory vector store, CI smoke support | `examples/real-apps/smoke-support/README.md` |

## Coverage gaps

The current apps cover the core well, but these framework surfaces are not yet represented as clear
real-world product scenarios:

1. **Customer connector and relay end-to-end.**
   `ecommerce-store` has the domain fixture and request shape, but the suite does not yet have a
   first-class scenario that runs domain app + runtime + relay/actions/retrieval connector together.

2. **DB-backed action registry and connector action lifecycle.**
   The action registry modules exist, but no real app shows action registration, approval, discovery,
   deregistration, and runtime execution against a customer-owned system.

3. **MCP action bridge.**
   ADR 0003 and the actions connector include MCP capability, but there is no real app showing MCP
   tools behind AI Fabric governance and confirmation policy.

4. **Governance, catalog, retention, and deletion as a product story.**
   Governance is present in framework modules and integration tests, but real apps do not yet show
   compliance operators inspecting catalog state, deleting indexed customer data, and consuming
   vector readiness evidence.

5. **RAG quality and evaluation gates.**
   `smart-faq-assistant` and `chat-capabilities-demo` exercise retrieval, but neither acts as a
   release-quality RAG evaluation workbench with golden questions, relevancy/fact checks, and
   regression thresholds.

6. **Observability and provider diagnostics.**
   Provider metrics, Spring AI observation diagnostics, vector readiness, and fallback evidence are
   not yet visible in a real app workflow.

7. **Tenant/role-aware AI workflows.**
   Existing apps use user/session context, but there is no app whose core scenario proves tenant
   isolation, role-limited actions, and retrieval allowlists.

8. **Multi-step agentic workflows.**
   The docs describe agentic apps, and existing action bots cover single-turn execution, but there is
   no polished real app where a user goal moves through search, planning, confirmation, action,
   post-action generation, and audit.

9. **Document ingestion beyond simple DB text.**
   The current suite mostly indexes entities stored in app tables. There is no app for PDF/Markdown/
   knowledge-base ingestion, chunking, metadata normalization, and reindex/update/delete.

10. **Business-domain breadth.**
    Current domains are commerce, subscriptions, CRM, support, FAQ, behavior, and product catalog.
    Add finance/claims, healthcare operations, HR policy, or security operations only when they prove
    missing framework behavior, not just a different story around the same APIs.

## Decision

Prefer enhancing existing apps before creating new apps.

Create new apps only when an AI Fabric capability cannot be shown cleanly by extending an existing
app without confusing the story.

Every new or enhanced app must have:

- a named business scenario;
- seed data;
- a request file that demonstrates the happy path and at least one safety/failure path;
- unit/controller tests for app logic;
- smoke-profile boot without external services;
- optional real-provider profile when cloud services are needed;
- README sections for "what this proves", "run", "validate", and "configuration";
- no unlabeled dummy, empty, or stub production behavior.

## Priority roadmap

### P0 - Deepen current apps for release storytelling

#### 1. Commerce runtime and customer connector scenario

**Start from:** `ecommerce-store` plus `chat-capabilities-demo`.

**Goal:** Show AI Fabric as the runtime layer for a customer-owned commerce domain app.

**User story:**

An ecommerce site owns product/order/cart data. AI Fabric runtime receives data-sync events, indexes
products/policies/reviews, answers catalog and policy questions, and executes governed cart/order
actions back against the domain app.

**AI Fabric surfaces:**

- `ai-fabric-data-sync`;
- `ai-fabric-actions-connector`;
- `ai-fabric-actions-registry`;
- `ai-fabric-retrieval-connector`;
- `ai-fabric-relay`;
- chat sessions;
- action confirmation;
- indexing update/delete lifecycle;
- governance-safe vector deletion and readiness diagnostics.

**Implementation shape:**

```text
ecommerce-store
  -> emits domain events and exposes customer-owned domain APIs
  -> optional relay endpoint for actions/retrieval

commerce-runtime-demo
  -> uses AI Fabric data-sync to ingest product/policy/review records
  -> uses retrieval connector or local vector provider for RAG
  -> uses action connector/registry for cart/order actions
  -> exposes chat/query and admin readiness endpoints
```

**Use current apps where possible:**

- Keep `ecommerce-store` as the customer-owned app.
- Either add a new `commerce-runtime-demo` app or split the runtime parts out of
  `chat-capabilities-demo` if that app is currently carrying too many concerns.

**Acceptance tests:**

- Domain product create/update/delete produces matching upsert/delete request payloads.
- Runtime search returns updated products and does not return deleted products.
- Action execution requires confirmation for write actions.
- Connector outage returns a structured AI Fabric failure instead of a raw HTTP/client exception.
- Smoke profile starts both apps without external services.

#### 2. Smart FAQ RAG quality workbench

**Start from:** `smart-faq-assistant`.

**Goal:** Turn the FAQ app into the release evidence for RAG quality and regression gates.

**User story:**

A support team maintains FAQs and wants to know whether AI answers remain grounded after content
changes. The app runs golden questions, shows retrieved evidence, and fails release gates when
answers are not relevant or not supported by retrieved documents.

**AI Fabric surfaces:**

- `ai-fabric-rag`;
- Spring AI-backed RAG evaluation helpers from ADR 0003;
- retrieval evidence;
- answer generation policy;
- sanitized evaluation metadata;
- vector lifecycle after reindex/update/delete.

**Implementation shape:**

```text
FAQ article seed data
  -> reindex
  -> golden questions
  -> retrieve documents
  -> optional generated answer
  -> evaluation result with relevance/factuality score and evidence ids
```

**Acceptance tests:**

- Golden search-only questions return expected article ids.
- Generated answers must cite retrieved document ids.
- Evaluation metadata excludes URLs, secrets, raw paths, and PII-like values.
- CI can run a deterministic local smoke gate; real LLM evaluation runs under an opt-in profile.

#### 3. Privacy and governance support desk

**Start from:** `privacy-first-customer-facing-support`.

**Goal:** Show privacy, catalog, retention, deletion, and audit as one operator-facing workflow.

**User story:**

A customer asks support to delete personal data. The app redacts incoming messages, stores only safe
content, indexes searchable safe summaries, and lets an operator execute retention/deletion workflows
with evidence that vectors and catalog records are removed.

**AI Fabric surfaces:**

- `ai-fabric-pii`;
- `ai-fabric-governance`;
- vector catalog;
- deletion discovery;
- retention policy;
- audit-safe logs;
- vector provider readiness diagnostics.

**Implementation shape:**

```text
incoming support message with PII
  -> PII detection/redaction
  -> store redacted content and encrypted/hash original policy result
  -> index safe summary
  -> operator lists catalog entries for customer
  -> operator executes deletion
  -> vector/catalog absence is verified
```

**Acceptance tests:**

- Raw PII is not stored in redacted fields, logs, or vector content.
- Deletion by customer id removes indexed records.
- Catalog drift/failure is surfaced as a failure, not silently ignored.
- Smoke profile uses deterministic embeddings and memory/Lucene only.

### P1 - Add missing product scenarios

#### 4. MCP operations assistant

**Add app:** `mcp-operations-assistant`.

**Goal:** Prove AI Fabric can safely expose MCP tools through governed Java action workflows.

**User story:**

An internal operations assistant can inspect service health, search runbooks, summarize incidents,
and request a deployment rollback. Read-only tools can run directly; write/destructive tools require
confirmation and policy checks.

**AI Fabric surfaces:**

- `ai-fabric-actions-connector`;
- Spring AI MCP bridge;
- `AIActionToolCallbackFactory`;
- action access modes;
- confirmation policy;
- action audit;
- chat session;
- retrieval over runbooks.

**Implementation shape:**

```text
MCP tool catalog
  -> AI Fabric connector action definitions
  -> action registry
  -> chat request opts into Spring AI tool bridge
  -> read-only tool executes
  -> rollback tool returns confirmation-required result
  -> confirmation executes through AI Fabric policy
```

**Acceptance tests:**

- MCP read-only action result is returned as sanitized tool output.
- Write action requires confirmation.
- Unknown tool and connector failure return structured failure results.
- Tool arguments/results do not leak hidden action context.

#### 5. Support operations center

**Start from:** `it-support-action-bot` and optionally `smart-faq-assistant`.

**Goal:** Upgrade provider-only action orchestration into a complete helpdesk copilot.

**User story:**

An agent asks for help with a ticket. AI Fabric retrieves relevant runbooks, classifies severity,
suggests next actions, assigns/escalates tickets, and writes a customer-safe resolution summary.

**AI Fabric surfaces:**

- provider-only mode;
- RAG over runbooks;
- governed ticket actions;
- post-action generation;
- chat-session context;
- PII redaction on customer notes;
- action failure recovery.

**Acceptance tests:**

- Ticket assignment and escalation action permissions are enforced.
- Runbook retrieval evidence is present in generated suggestions.
- Customer-facing summary does not include internal-only fields.
- Existing provider-only path remains usable when RAG is disabled.

#### 6. SaaS retention studio

**Start from:** `behavior-churn-signals` and `sub-management-hub`.

**Goal:** Show behavior analysis feeding governed retention actions.

**User story:**

A SaaS operator reviews churn-risk users, sees behavior insights, asks why a customer is at risk,
retrieves plan and usage context, and approves a retention offer action.

**AI Fabric surfaces:**

- behavior analysis;
- relationship query over subscription/customer/account data;
- RAG over plan docs;
- governed action confirmation;
- chat session;
- audit-safe reasoning evidence.

**Acceptance tests:**

- Behavior event seed produces deterministic risk categories in smoke mode.
- Offer creation requires confirmation.
- Relationship-query filters scope results to the requested account/user.
- Generated recommendation cites behavior insight id and plan evidence id.

#### 7. CRM revenue copilot

**Start from:** `relationship-query-crm-insights`.

**Goal:** Move relationship query from "query only" to a real revenue workflow.

**User story:**

A sales manager asks for open enterprise deals with support risk, retrieves account context, creates
follow-up tasks, and exports a summary for the account team.

**AI Fabric surfaces:**

- relationship query;
- structured output repair;
- RAG over account notes;
- governed CRM actions;
- row/tenant scoped context;
- post-action summary.

**Acceptance tests:**

- Query planner cannot access entity types outside the request allowlist.
- Follow-up task action requires valid account/deal ids.
- Bad planner JSON is repaired through the shared structured-output path.
- Generated summary includes ids of the deals and tickets used.

#### 8. Tenant-aware knowledge portal

**Add app:** `tenant-knowledge-portal`.

**Goal:** Make tenant and permission boundaries visible in a real app.

**User story:**

A B2B SaaS product has multiple tenants and role-limited knowledge spaces. Users can search only
their tenant's documents and can execute only the actions allowed by role.

**AI Fabric surfaces:**

- tenant/context metadata;
- vector-space allowlists;
- metadata filters;
- action access modes;
- governance catalog;
- deletion by tenant/customer.

**Acceptance tests:**

- Same document title in two tenants returns only the caller's tenant result.
- Admin can see all tenant catalog summaries; regular user cannot.
- Cross-tenant action target is rejected.
- Tenant deletion removes vectors and catalog entries for that tenant only.

### P2 - Add operational and cloud proof apps

#### 9. Document ingestion and knowledge base processor

**Add app:** `document-ingestion-workbench`.

**Goal:** Prove document ingestion, chunking, metadata normalization, and update/delete lifecycle.

**User story:**

An enterprise uploads Markdown, JSON, HTML, and PDF-like documents into a knowledge base. AI Fabric
normalizes metadata, chunks content, indexes documents, and supports reindex/delete when the source
changes.

**AI Fabric surfaces:**

- indexing lifecycle;
- Spring AI document readers/transformers where adopted;
- data-sync ingestion;
- vector metadata filters;
- RAG evidence;
- governance deletion.

**Acceptance tests:**

- Re-upload of same source id updates existing chunks without duplicates.
- Delete source id removes all chunks.
- Unsupported metadata shapes fail closed or normalize predictably.
- Smoke profile uses text fixtures; optional profile can test richer parsers.

#### 10. Provider failover and diagnostics lab

**Add app:** `provider-failover-lab`.

**Goal:** Make provider routing, fallback, model cache, endpoint overrides, transient input policy,
and observation diagnostics visible.

**User story:**

An operator configures OpenAI, Anthropic, Gemini, and local smoke providers. The app runs test prompts,
shows selected provider/model, fallback reason, token usage, redacted observations, and fail-closed
behavior for transient files.

**AI Fabric surfaces:**

- Spring AI provider integration;
- model resolver/cache;
- provider fallback;
- redacted observation diagnostics;
- transient input policy;
- structured output failure diagnostics.

**Acceptance tests:**

- Missing primary provider falls back to configured secondary provider.
- Provider errors expose safe error type and provider name, not prompts or secrets.
- Transient file URL is not persisted, logged, or indexed.
- Observation diagnostics include counts/durations without raw content.

#### 11. Vector provider readiness playground

**Add app:** `vector-readiness-playground`.

**Goal:** Give users a small app to compare memory, Lucene, Qdrant, Weaviate, Milvus, and Pinecone
readiness and lifecycle semantics.

**User story:**

An operator changes the vector provider and runs the same store/search/fetch/update/delete/clear
scenario, then inspects readiness diagnostics and fallback evidence before release.

**AI Fabric surfaces:**

- native vector providers;
- `VectorDatabaseService` lifecycle/admin API;
- vector readiness health;
- metadata filter parity;
- provider diagnostics;
- release readiness script compatibility.

**Acceptance tests:**

- Default memory/Lucene smoke tests run locally.
- Docker-backed providers run under an opt-in profile.
- Readiness endpoint includes `READY`, `WARN`, or `NOT_READY`.
- Any metadata/count fallback evidence is visible in diagnostics.

## Reuse plan by existing app

| Existing app | Keep as-is | Add next |
| --- | --- | --- |
| `smart-faq-assistant` | Local-first FAQ search | Golden RAG evaluation dataset, answer evidence, release gate profile |
| `migration-enabled-product-catalog` | Backfill indexing | Update/delete lifecycle verification after migration, failed-job recovery scenarios |
| `privacy-first-customer-facing-support` | PII redaction | Governance catalog, customer deletion, audit evidence, optional safe indexing |
| `relationship-query-crm-insights` | NL to JPQL | Governed CRM actions, account notes RAG, tenant/role constraints |
| `behavior-churn-signals` | Behavior insights | Retention offer action flow and subscription context |
| `chat-capabilities-demo` | Broad commerce chat | Narrow into runtime demo or keep as all-in-one showcase with stricter scenario tests |
| `it-support-action-bot` | Provider-only actions | RAG-backed support ops workflow and post-action summaries |
| `sub-management-hub-simple` | Config-first indexing | Keep as minimal "getting started" real app |
| `sub-management-hub` | Annotation-assisted subscription search | Merge behavior/retention storyline or keep as advanced indexing reference |
| `ecommerce-store` | Domain API fixture | Customer connector, relay, action registry, event indexing proof |
| `cloud-qdrant-openai-vector-search` | Cloud vector path | Add vector readiness diagnostics and metadata filter examples |

## App design rules

1. **One app, one primary story.**
   Avoid another broad kitchen-sink demo unless the point is explicitly "runtime platform".

2. **Every app must run in smoke mode.**
   `smoke-support` should remain the default path for CI and local release checks.

3. **Real providers are opt-in.**
   OpenAI/Qdrant/Postgres/etc. scenarios should be available under explicit profiles or Docker
   Compose, but default tests must not require external services.

4. **Production code cannot be fake.**
   Deterministic providers are acceptable only when named as local/smoke/demo providers. Business
   services should be real implementations with tests, not empty placeholders.

5. **Show failure paths.**
   Each request file should include at least one denied action, missing permission, invalid target,
   connector outage, retrieval miss, or privacy failure path.

6. **Prefer framework APIs over direct internals.**
   Real apps should demonstrate public AI Fabric module contracts, not hidden test-only hooks.

7. **Keep debug endpoints separate from product endpoints.**
   Debug endpoints are useful for demos, but the primary flow should look like a normal product API.

8. **Use realistic data, not huge data.**
   Seed enough records to make retrieval and filtering meaningful, but keep local smoke runs fast.

## Testing and CI expectations

For each new or enhanced app:

- Unit tests for domain services and action handlers.
- Controller tests for app-level endpoints.
- Smoke context or jar-start validation under `--spring.profiles.active=smoke`.
- Request file covering the demo path.
- Optional real-provider test profile for cloud/provider functionality.
- No `-DskipTests` in the normal release validation command.

Recommended default verification:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml clean package
```

Recommended smoke-start gate:

```bash
java -jar examples/real-apps/<app>/target/*.jar \
  --spring.profiles.active=smoke \
  --server.port=0 \
  --management.server.port=0
```

Optional provider gates should remain explicit:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml \
  -Preal-providers verify
```

## Suggested implementation order

1. Add RAG evaluation to `smart-faq-assistant`.
2. Extend `privacy-first-customer-facing-support` into privacy/governance deletion evidence.
3. Build the commerce runtime/customer connector scenario around `ecommerce-store`.
4. Add `mcp-operations-assistant`.
5. Upgrade `it-support-action-bot` into support operations center.
6. Extend behavior/subscription apps into SaaS retention studio.
7. Extend CRM relationship query into revenue copilot.
8. Add tenant-aware knowledge portal.
9. Add document ingestion workbench.
10. Add provider failover diagnostics lab.
11. Add vector readiness playground only if docs/scripts are not enough for provider readiness demos.

## Release positioning

The release story should not be "AI Fabric has many sample apps." It should be:

```text
AI Fabric gives Java/Spring teams a governed way to add AI to real applications:

- connect existing domain systems without surrendering ownership;
- index and retrieve business entities safely;
- run actions with permission and confirmation policy;
- protect PII and support deletion/retention workflows;
- migrate/backfill AI indexes safely;
- choose local, cloud, or native vector providers with readiness evidence;
- use Spring AI where it removes commodity model/tool plumbing;
- keep AI Fabric's product workflow and governance layer above provider APIs.
```

The apps should be curated around that story. A smaller set of complete scenarios is more valuable
than many shallow examples.
