# ADR 0005 - Real app use case expansion plan for AI Fabric

- **Status:** Implemented; third-pass MCP and multi-store retrieval review added
- **Date:** 2026-06-20
- **Decision owner:** AI Fabric framework
- **Context version:** AI Fabric `0.3.2`, Java `21`, Spring Boot `4.1.0`, Spring AI `2.0.0`
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
| `ai-fabric-account-resolver` | Account resolver with governed actions and readiness blockers | Annotation plus config setup, behavior and relationship-query dependencies, Lucene | `examples/real-apps/ai-fabric-account-resolver/README.md` |
| `cloud-qdrant-openai-vector-search` | Production-like semantic search with Postgres, Qdrant, and OpenAI | Cloud embeddings, external vector DB, annotation-driven indexing/search, provider configuration | `examples/real-apps/cloud-qdrant-openai-vector-search/README.md` |
| `smoke-support` | Shared smoke profile for no-key/no-service boot | Deterministic local AI provider, deterministic embeddings, memory vector store, CI smoke support | `examples/real-apps/smoke-support/README.md` |
| `customer-runtime-demo` | Customer-owned domain fixture plus runtime-style sync/search/actions | Data-sync DTO payloads, tenant-scoped retrieval, governed action confirmation, structured connector outage | `examples/real-apps/customer-runtime-demo/README.md`, `CustomerRuntimeServiceTest` |
| `db-action-registry-lab` | DB-backed connector action registration, approval, discovery, execution, and deregistration | `ai-fabric-actions-registry`, DB action catalog, runtime `AIActionRegistry` refresh, connector action handler execution, API-key protected registry API | `examples/real-apps/db-action-registry-lab/README.md`, `DbActionRegistryLabServiceTest`, `DbActionRegistryControllerTest` |
| `mcp-operations-assistant` | Deterministic governed MCP-style operations tool execution | `McpActionExecutor`, action access modes, confirmation policy, sanitized tool output | `examples/real-apps/mcp-operations-assistant/README.md`, `McpOperationsServiceTest` |
| `tenant-knowledge-portal` | Tenant-aware knowledge search, catalog, actions, and deletion | Tenant metadata, role checks, cross-tenant action rejection, tenant deletion evidence | `examples/real-apps/tenant-knowledge-portal/README.md`, `TenantKnowledgeServiceTest` |
| `document-ingestion-workbench` | Trusted document upload, preview, index, reindex, delete | Spring AI document readers, AI Fabric indexing requests, chunk manifest lifecycle, metadata sanitization | `examples/real-apps/document-ingestion-workbench/README.md`, `DocumentIngestionServiceTest` |
| `provider-failover-lab` | Provider routing/fallback diagnostics and transient input evidence | `AIProvider`, provider fallback attempts, safe diagnostics, transient URL non-persistence evidence | `examples/real-apps/provider-failover-lab/README.md`, `ProviderFailoverServiceTest` |
| `vector-readiness-playground` | Vector lifecycle/admin readiness evidence | `VectorDatabaseService.adminDiagnostics()`, lifecycle status, store/existence/delete evidence | `examples/real-apps/vector-readiness-playground/README.md`, `VectorReadinessServiceTest` |

Previously deployed apps are not expansion targets in this plan. Treat the existing commerce store
fixture as prior deployed reference material, not as a target for new scenario work.

## Coverage Targets And Closure

These were the framework surfaces that needed clearer real-world product scenarios. Each target is
now either covered by the implemented app suite or intentionally left as non-expansion material.

1. **Customer connector end-to-end.**
   Covered by `customer-runtime-demo`, which runs a customer-domain fixture shape with data-sync
   payloads, tenant-scoped retrieval, governed action confirmation, and structured connector outage
   behavior.

2. **DB-backed action registry and connector action lifecycle.**
   Covered by `db-action-registry-lab`, which proposes actions, requires operator approval before DB
   publication, discovers the refreshed runtime registry, executes through the connector handler path,
   and deregisters actions from DB/runtime availability.

3. **MCP action bridge governance contract.**
   Covered deterministically by `mcp-operations-assistant`, which exposes operations tools through AI
   Fabric action access modes, confirmation policy, failure handling, and hidden-context sanitization.
   Live Spring AI-managed MCP client discovery and transport execution remain a separate proof target.

4. **Governance, catalog, retention, and deletion as a product story.**
   Covered by `privacy-first-customer-facing-support`, `tenant-knowledge-portal`, and
   `vector-readiness-playground` through privacy inventory, deletion provider behavior, tenant
   deletion, and vector readiness diagnostics.

5. **RAG quality and evaluation gates.**
   Covered by `smart-faq-assistant` golden questions, quality gate, retrieved evidence, and optional
   Spring AI evaluation path.

6. **Observability and provider diagnostics.**
   Covered by `provider-failover-lab` and `vector-readiness-playground`, with safe fallback
   diagnostics, transient URL non-persistence evidence, and readiness status.

7. **Tenant/role-aware AI workflows.**
   Covered by `tenant-knowledge-portal`, which proves tenant-scoped search, admin/user catalog
   visibility, cross-tenant action rejection, and tenant-specific deletion.

8. **Multi-step agentic workflows.**
   Covered across `it-support-action-bot`, `relationship-query-crm-insights`, `behavior-churn-signals`,
   and `db-action-registry-lab` with retrieval/evidence, planning, confirmation, action execution,
   post-action/customer-safe summaries, and audit-style action evidence.

9. **Document ingestion beyond simple DB text.**
   Covered by `document-ingestion-workbench`, which handles trusted upload, Spring AI reader preview,
   chunk manifest lifecycle, reindex deletes, source deletion, and unsupported-file fail-closed behavior.

10. **Business-domain breadth.**
    Intentionally not expanded just for variety. New domains should be added only when they prove a
    missing framework behavior, not merely a different story around the same APIs.

## Capability Coverage Re-Review - 2026-07-01

This second pass compared the real-app suite against
`docs/planning/0006-framework-capability-priority-map.md` and the framework modules under
`ai-infrastructure-module`.

Conclusion: the suite does not need another broad domain app for release evidence. It already covers
the core AI Fabric story well. The remaining worthwhile additions are narrow boundary labs for
capabilities that are framework-owned, externally useful, and still mostly visible only in module
tests or guides.

### Coverage Decision Matrix

| Capability area | Current real-app coverage | Code/guide evidence | Decision |
| --- | --- | --- | --- |
| Local actions, confirmation, confirmation interceptors, post-action summaries | Covered by `chat-capabilities-demo`, `it-support-action-bot`, subscription apps, and support/CRM/retention services. | `ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`, action handlers under `examples/real-apps/**/action`, core action tests. | No new app. Continue hardening packaged smoke scripts. |
| DB-backed action registry | Covered by `db-action-registry-lab`. | `ai-fabric-actions-registry`, `DbActionRegistryLabServiceTest`, `DbActionRegistryControllerTest`. | No new app. This gap is now closed. |
| File-based connector action catalog | Partially covered by connector module tests; not visible in a real app. `db-action-registry-lab` covers DB registry, not YAML catalog loading. | `ai-fabric-actions-connector/src/main/java/.../ConnectorActionCatalogLoader.java`, `ConnectorActionCatalogLoaderTest`, `ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md`. | Add a focused candidate app only if file catalogs remain a public onboarding path. Best shape: `connector-catalog-lab`. |
| Customer connector action execution | Covered by `customer-runtime-demo`, `db-action-registry-lab`, and `mcp-operations-assistant` through connector executor paths. | `ActionConnectorExecutor`, `ConnectorAIActionHandler`, app tests. | No broad new app. Extend `connector-catalog-lab` if the file-catalog path needs product evidence. |
| Retrieval connector `/retrieval/search` documents-only boundary | Not covered by a real app. Current proof is module-level. | `ai-fabric-retrieval-connector`, `RetrievalConnectorRAGProviderTest`, `RETRIEVAL_CONNECTOR_GUIDE.md`. | Add `retrieval-connector-boundary-lab`. This is the strongest next app candidate. |
| Data-sync push API and customer runtime | Covered by `customer-runtime-demo` and the ecommerce-to-chat smoke. | `ai-fabric-data-sync`, `DATA_SYNC_PUSH_API_GUIDE.md`, `smoke-ecommerce-chat-datasync.sh`. | No new app. |
| Indexing queue retry/dead-letter/worker operator behavior | Ingestion and migration apps enqueue/update/delete work, but queue retry/dead-letter is only module-test visible. | `ai-fabric-indexing` queue/worker classes and tests, `document-ingestion-workbench`. | Optional `indexing-ops-lab` if operator evidence becomes release-facing. Not urgent for app suite. |
| Spring AI document ingestion bridge | Covered by `document-ingestion-workbench`. | `SpringAiDocumentIndexingAdapter`, `SpringAiDocumentReaderFactory`, app tests. | No new app. |
| RAG quality, evidence, and fail-closed retrieval | Covered by `smart-faq-assistant`; retrieval connector fail-closed boundary remains separate. | `FaqQualityServiceTest`, `SpringAiRagEvaluationServiceTest`. | No new app beyond the retrieval connector boundary lab. |
| Vector lifecycle/admin readiness | Covered by `vector-readiness-playground` plus provider contract CI. | `VectorDatabaseService`, vector provider tests, Docker-backed vector provider CI. | No new app. |
| Tenant/role-aware search and deletion | Covered by `tenant-knowledge-portal`. | `TenantKnowledgeServiceTest`, runtime auth/access guides. | No new app. |
| Public runtime browser-token integration | Partially covered at framework metadata propagation and ecommerce authz reference level; token bootstrap/provisioning is platform-owned. | `PUBLIC_RUNTIME_BROWSER_TOKEN_INTEGRATION_GUIDE.md`, `PUBLIC_ANONYMOUS_ACTION_POLICY_GUIDE.md`, core auth-context tests, ecommerce `AuthzController`. | Add `public-runtime-policy-lab` only if AI Fabric framework owns the public token validator/runtime entrypoint. Otherwise keep in platform verification. |
| Public anonymous action policy gates | Partially covered by action metadata and core tests; not product-shaped in a framework real app. | `anonymousAllowed` metadata, `IntentHandlingStep*` tests, public anonymous policy guide. | Same as browser-token integration: candidate only if this remains framework-owned. |
| AI Web admin/governance controllers | Covered by `ai-fabric-web` module tests, not a real app. | `AIComplianceControllerTest`, `AISecurityControllerTest`, `AdvancedRAGControllerTest`, `MigrationControllerTest`. | Optional `admin-governance-console-lab` if `ai-fabric-web` is marketed as a user-facing starter surface. |
| Generic REST connector pattern | Documented blueprint only; no runnable generic REST connector module in this reactor. | `GENERIC_REST_API_CONNECTOR_GUIDE.md` explicitly says the runnable connector module is not present. | Do not add a real app yet. Add `generic-rest-connector-lab` only after a framework module exists. |
| Action registry Liquibase helper | Module-level helper only. | `ai-fabric-actions-registry-liquibase`, environment post-processor test. | No standalone app. Mention in `db-action-registry-lab` README if users need migration wiring. |
| Marketplace plugins, public provisioning, sealed backup/restore, hosted deployment verification | Platform/control-plane capabilities, not framework runtime apps. | Marketplace, public API client, deployment export/import guides. | Keep out of `examples/real-apps`; verify in platform repository/process. |

### Recommended Next App Additions

Only two additions are clearly worth planning from the framework side right now:

1. **`retrieval-connector-boundary-lab` - P1**

   Proves a customer-owned retrieval service implementing `POST /retrieval/search` can be used as the
   RAG provider while AI Fabric keeps generation, citations, policy, and fail-closed response
   validation. This should include one good documents-only response, one forbidden/policy response,
   and one invalid response containing generated-answer or prompt-like fields that AI Fabric rejects.

2. **`connector-catalog-lab` - P1 conditional**

   Proves file-based connector action catalogs remain viable for users who do not need the DB action
   registry. It should load a YAML action catalog, validate access modes/confirmation metadata, execute
   through `ConnectorAIActionHandler`, prove retry/error normalization, and show how catalog-defined
   confirmation interceptors behave. This may also be implemented as an extension to
   `db-action-registry-lab` if keeping app count lower is preferable.

Three candidates should stay deferred until ownership is clearer:

- **`public-runtime-policy-lab`**: useful if framework owns public token validation and browser-direct
  runtime admission; otherwise platform-owned.
- **`indexing-ops-lab`**: useful if queue retry/dead-letter/admin evidence becomes user-facing; module
  tests currently cover the important mechanics.
- **`admin-governance-console-lab`**: useful if `ai-fabric-web` is promoted as a user-facing starter
  experience; otherwise controller tests are enough.

## MCP And Multi-Store Retrieval Review - 2026-07-26

This review evaluates two proposed public demos against the current AI Fabric `0.4.0` code:

1. live MCP support;
2. multiple vector stores contributing evidence to one generated answer.

The existing multi-vector-space capability is not a new demo target. `chat-capabilities-demo` already
indexes and retrieves distinct `product`, `review`, and `policy` entity/vector spaces, and AI Fabric
already supports bounded fan-out across vector spaces before merging evidence for generation.

### Decision Summary

| Proposal | Current support | Decision |
| --- | --- | --- |
| Live governed MCP tools | Framework bridge exists; current real app uses only a deterministic local executor | Upgrade the existing MCP app into a live deployable demo. |
| Multiple vector spaces in one configured vector provider | Supported and already demonstrated by the shopping experience | Do not add another demo. Make the existing RAG journey and evidence routing explicit where useful. |
| Multiple physical vector databases/providers in one RAG request | Extension contracts and result merging exist, but provider wiring and federation semantics are incomplete | Harden this as a framework capability first, then add a federated knowledge demo. |

### Terminology

The LLM does not connect directly to vector databases. AI Fabric must:

1. resolve the eligible knowledge sources;
2. retrieve evidence from one or more stores;
3. enforce tenant and metadata policies at each source;
4. rank, deduplicate, and attribute the evidence;
5. send the resulting grounded context to one LLM.

The accurate capability name is **federated multi-source RAG**, not "multiple vector stores in the
same LLM."

### Existing Multi-Vector-Space Coverage - Closed

No new app is required for multiple vector spaces in one provider.

Current code evidence:

- `VectorSpaceRoutingProperties` defaults to bounded fan-out and controls maximum spaces, top-K per
  space, retrieval threshold, and clarification threshold.
- `BoundedFanOutRouter` selects eligible entity/vector spaces when intent extraction does not provide
  one definitive space.
- `InformationRagExecutionSupport.fanOut(...)` executes retrieval per selected space, tags each
  document with its vector-space provenance, merges by rank, deduplicates, and builds one generation
  context.
- `chat-capabilities-demo` defines separate `product`, `review`, and `policy` entities with
  `@AICapable(entityType = ...)` while using one configured Lucene provider.

Future Shopping UI work may show the selected spaces and merged evidence more clearly, but it must
not be presented as a new framework capability or a separate app.

### Live MCP Demo Plan

#### Current Support

AI Fabric already has the runtime foundation:

- `McpActionExecutor` is the governed action execution contract.
- `SpringAiMcpActionExecutor` uses Spring AI-managed `McpSyncClient` instances, checks the declared
  server and tool catalog, renders parameter and trusted-context templates, invokes the MCP tool, and
  maps structured content into an `ActionResult`.
- `AIActionConnectorAutoConfiguration` installs the Spring AI bridge when MCP client classes are
  present and an application has not supplied a custom executor.
- connector action catalogs support `adapterType: mcp-tool`, `serverRef`, `toolName`,
  `argumentTemplate`, access mode, internal runtime parameters, and result-path projection.
- normal AI Fabric authorization, confirmation, execution, and result-projection rules continue to
  govern the MCP-backed action.

The existing `mcp-operations-assistant` proves governance with a deterministic local
`McpActionExecutor`. Its README correctly states that it does not prove live MCP server discovery or
live Spring AI model/tool execution.

#### Implementation Decision

Upgrade `mcp-operations-assistant` rather than create a duplicate app. Its public demo title should be
**AI Fabric MCP Operations Console**.

Use a small, deployable companion MCP server so the scenario is reproducible and does not depend on
an unstable public MCP endpoint. The companion server should expose:

- `service.health` - read-only;
- `runbook.search` - read-only;
- `incident.create` - governed write requiring confirmation;
- `deployment.rollback` - destructive write requiring confirmation.

The application flow should be:

```text
natural-language request
  -> AI Fabric intent and action selection
  -> action authorization and access-mode policy
  -> confirmation when required
  -> Spring AI MCP client selects the declared server/tool
  -> MCP transport call
  -> structured result projection
  -> safe post-action response and visible diagnostics
```

#### Demo UI

The UI should make three boundaries visible without exposing secrets:

1. the user's request and AI response;
2. the AI Fabric governance decision, including action, access mode, and confirmation state;
3. the MCP execution evidence, including server reference, tool name, correlation id, status, and
   sanitized projected result.

The UI must not expose hidden action context, credentials, raw prompts, or unprojected MCP payloads.
It must show MCP and LLM failures explicitly; the live profile must not fall back to deterministic
behavior.

#### MCP Acceptance Criteria

- A real Spring AI-managed MCP client discovers and calls the companion server.
- A read-only tool executes without confirmation.
- A write/destructive tool cannot execute before confirmation.
- Rejection proves the MCP tool was not invoked.
- Unknown tool, unavailable server, timeout, and MCP error produce structured visible failures.
- Trusted user/session/tenant context is supplied by the backend and is not requested from the user.
- Result-path projection returns only the intended domain fields.
- Unit tests cover catalog mapping, policy, confirmation, rejection, result projection, and failure
  normalization.
- A packaged smoke profile remains deterministic and clearly labeled.
- An opt-in real profile proves live LLM selection plus live MCP transport.
- The deployed demo health endpoint reports application version, source commit, MCP readiness, and
  model-provider readiness.

#### MCP Capability Boundary

This demo proves AI Fabric acting as a governed **MCP client for tools**. It must not claim that AI
Fabric currently:

- exposes AI Fabric actions as an MCP server;
- implements MCP resources, prompts, sampling, or every MCP protocol feature;
- allows the model to bypass AI Fabric action authorization or confirmation.

### Federated Multi-Source RAG Plan

#### Current Foundation

AI Fabric already includes useful extension-level support:

- `SearchSource` represents an eligible retrieval source.
- `SearchSourceRegistry` resolves multiple sources for a `RAGRequest`.
- `RAGSearchExecutor` queries resolved sources, merges and deduplicates their results, records source
  diagnostics, and marks partial failures as degraded retrieval.
- `RAGServiceSearchSourceRegistryTest` proves results from private and shared sources can contribute to
  one RAG response with attribution and degraded-source evidence.

This is not yet turnkey multi-vector-provider support:

- vector provider auto-configuration selects one global `ai.vector-db.type` and one primary
  `VectorDatabaseService`;
- the framework does not supply named Lucene/Qdrant/Weaviate/Pinecone source construction and routing;
- one query embedding is currently generated and passed to every resolved source;
- source calls are sequential;
- raw provider scores are sorted together even though score scales may differ;
- partial failure currently degrades and continues without a configurable required-source policy;
- lifecycle writes, ownership, and deletes are not routed across named stores.

An application can manually implement `SearchSourceRegistry` and wrap several stores today, but that
would demonstrate application-specific wiring rather than a complete AI Fabric feature.

#### Framework Work Required Before The Demo

1. **Named source configuration**
   - Add named vector source definitions with provider type, connection reference, entity types,
     attribution label, required/optional status, and embedding profile.
   - Keep the existing single-provider configuration backward compatible.

2. **Default registry and adapters**
   - Supply a default `SearchSourceRegistry`.
   - Supply a `VectorDatabaseSearchSource` adapter that delegates to a named
     `VectorDatabaseService`.
   - Allow applications and platform runtimes to contribute custom sources without replacing the
     entire registry.

3. **Explicit routing**
   - Map entity/vector spaces to allowlisted source ids on the server.
   - Never let free-form model output select an arbitrary connection or store.
   - Apply tenant, visibility, and metadata filters before each source returns candidates.

4. **Embedding compatibility**
   - For the first release, require every source in one federated request to use the same embedding
     provider, model, dimensions, and normalization profile.
   - Fail startup/readiness when configured sources are incompatible.
   - Treat per-source embedding generation as a later, separately designed capability.

5. **Provider-neutral ranking**
   - Replace direct raw-score comparison with reciprocal-rank fusion or another provider-neutral
     rank strategy.
   - Preserve original provider score as diagnostics, not as the sole cross-provider ordering signal.
   - Deduplicate using source id, entity type, entity id, and canonical content identity.

6. **Bounded execution**
   - Query sources with bounded parallelism.
   - Configure per-source timeout and maximum result count.
   - Cancel or contain slow sources without leaking executor work.

7. **Failure policy**
   - Support `REQUIRE_ALL` and `ALLOW_PARTIAL`.
   - Allow security-critical sources to be marked required.
   - Return visible degraded-source metadata; never silently hide a failed required source.

8. **Provenance and diagnostics**
   - Every document must carry source id, provider, vector space, entity id, attribution label, rank,
     and original score.
   - Add aggregate and per-source health/readiness diagnostics.
   - Keep credentials, endpoints, raw filters, and internal tenant handles out of user-facing output.

9. **Lifecycle ownership**
   - Route create/update/delete to one authoritative store per entity type.
   - Do not introduce implicit dual writes.
   - Treat replication or migration between stores as a separate explicit workflow with verification.

10. **Verification**
    - Contract tests for routing, filtering, fusion, provenance, timeout, required-source failure, and
      lifecycle ownership.
    - Docker-backed integration coverage for at least Lucene plus Qdrant.
    - Real embedding and generation smoke proving one grounded answer from both stores.

#### Future Demo

After the framework work is complete, add **AI Fabric Federated Knowledge**:

```text
private policies in Lucene
  + operational or product knowledge in Qdrant
  -> allowlisted source routing
  -> parallel retrieval
  -> provider-neutral rank fusion
  -> attributed merged evidence
  -> one grounded LLM answer
```

Use two physical stores initially. More providers would increase deployment cost without proving a
different capability.

The UI should show:

- the resolved retrieval plan;
- one lane per physical source;
- tenant/policy eligibility without exposing private filter values;
- source timing and success/failure;
- attributed evidence selected after rank fusion;
- the final grounded answer;
- an explicit degraded or failed state when a source is unavailable.

#### Federated Retrieval Acceptance Criteria

- One request retrieves relevant evidence from both Lucene and Qdrant.
- One generated answer cites evidence from both sources.
- An ineligible tenant source is never queried.
- A required-source failure fails visibly.
- An optional-source failure returns a visibly degraded response.
- Raw similarity scores from different providers are not directly compared as equivalent values.
- Duplicate documents do not appear twice after fusion.
- Update/delete operations affect only the authoritative store.
- No deterministic answer fallback hides embedding, retrieval, or generation failure.

### Priority

1. **P1 - Live MCP demo:** the bridge already exists; the missing value is real protocol, model, and
   deployment proof.
2. **No new priority - Multi-vector-space demo:** already covered by the Shopping experience.
3. **P2 - Federated multi-source RAG:** implement and verify the framework capability before building
   the public demo.

## Decision

Prefer enhancing existing apps before creating new apps.

Create new apps only when an AI Fabric capability cannot be shown cleanly by extending an existing
app without confusing the story.

The deployable Relay service is now treated as a platform-owned runtime component, not a framework
real-app coverage target. This plan ignores the relay module and focuses framework examples on the
portable Customer Connector API contracts, connector client libraries, action registry behavior, and
retrieval/data-sync boundaries. Platform verification should cover relay packaging, deployment,
inbound auth, replay protection, rate limiting, idempotency persistence, and operational controls.

Every new or enhanced app must have:

- a named business scenario;
- seed data;
- a request file that demonstrates the happy path and at least one safety/failure path;
- unit/controller tests for app logic;
- smoke-profile boot without external services;
- optional real-provider profile when cloud services are needed;
- README sections for "what this proves", "run", "validate", and "configuration";
- no unlabeled dummy, empty, or stub production behavior.

## Implementation Evidence

Implemented on 2026-07-01 as a complete real-app expansion pass. The relay remains intentionally
excluded from framework examples because it is platform-owned runtime infrastructure.

| Priority item | Implementation evidence | Test evidence |
| --- | --- | --- |
| P0.1 Customer connector runtime scenario | Added `customer-runtime-demo` with customer-domain fixture behavior, data-sync upsert/delete payloads, tenant-scoped search, action confirmation, and structured connector outage handling. | `CustomerRuntimeServiceTest`; focused run: `mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl customer-runtime-demo -am test` |
| P0.1b DB-backed action registry lifecycle | Added `db-action-registry-lab` with controlled action proposals, approval into the DB registry, runtime discovery through `AIActionRegistry`, connector-handler execution against a customer ticket fixture, API-key protected raw registry endpoints, and deregistration. | `DbActionRegistryLabServiceTest`, `DbActionRegistryControllerTest`; focused run: `mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl db-action-registry-lab -am test` |
| P0.2 Smart FAQ RAG quality workbench | Existing `smart-faq-assistant` includes golden questions, quality gate, retrieved evidence, and optional Spring AI evaluation. | `FaqQualityServiceTest`, `FaqQualityControllerTest` |
| P0.3 Privacy/governance support desk | Existing `privacy-first-customer-facing-support` includes governance inventory, deletion provider, deletion service delegation, and catalog evidence. | `PrivacyGovernanceServiceTest`, `SupportMessageDeletionProviderTest` |
| P1.4 MCP operations assistant | Added `mcp-operations-assistant` with local MCP executor implementing the same `McpActionExecutor` bridge contract, read/write action policy, confirmation, and hidden-context sanitization. | `McpOperationsServiceTest` |
| P1.5 Support operations center | Enhanced `it-support-action-bot` with `SupportOperationsService` for runbook evidence, severity classification, governed support actions, RAG-disabled fallback, and customer-safe summaries. | `SupportOperationsServiceTest` plus existing smoke action tests |
| P1.6 SaaS retention studio | Enhanced `behavior-churn-signals` with `RetentionStudioService` for churn-risk review, behavior/plan evidence ids, and confirmation-gated retention offers. | `RetentionStudioServiceTest` |
| P1.7 CRM revenue copilot | Enhanced `relationship-query-crm-insights` with `RevenueCopilotService` for structured planner output parsing, allowlisted entity access, follow-up task target validation, and evidence summary. | `RevenueCopilotServiceTest` |
| P1.8 Tenant-aware knowledge portal | Added `tenant-knowledge-portal` with tenant-scoped search, admin/user catalog views, role-limited actions, cross-tenant rejection, and tenant deletion. | `TenantKnowledgeServiceTest` |
| P2.9 Document ingestion workbench | Added `document-ingestion-workbench` with trusted uploads, Spring AI reader preview, AI Fabric indexing request planning, reindex deletes, delete lifecycle, and unsupported-file fail-closed behavior. | `DocumentIngestionServiceTest`, `DocumentIngestionControllerTest` |
| P2.10 Provider failover diagnostics lab | Added `provider-failover-lab` with `AIProvider` probe execution, primary/fallback attempts, safe error summaries, and transient file URL non-persistence diagnostics. | `ProviderFailoverServiceTest` |
| P2.11 Vector readiness playground | Added `vector-readiness-playground` with `READY`/`WARN`/`NOT_READY` reports from `VectorDatabaseService` diagnostics and lifecycle store/existence/delete evidence. | `VectorReadinessServiceTest` |

Release verification completed for this implementation pass:

- `mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml test`
- `mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl customer-runtime-demo,mcp-operations-assistant,tenant-knowledge-portal,document-ingestion-workbench,provider-failover-lab,vector-readiness-playground -am package`
- Smoke-started the six new app jars with `--spring.profiles.active=smoke --server.port=0 --management.server.port=0`.

## Priority roadmap

### P0 - Deepen current apps for release storytelling

#### 1. Customer connector runtime scenario

**Start from:** `chat-capabilities-demo` plus a new lightweight customer-domain fixture.

**Goal:** Show AI Fabric as the runtime layer for a customer-owned domain app.

**User story:**

A customer system owns domain records and APIs. AI Fabric runtime receives data-sync events, indexes
records and policy-like documents, answers grounded questions, and executes governed connector
actions back against the domain app.

**AI Fabric surfaces:**

- `ai-fabric-data-sync`;
- `ai-fabric-actions-connector`;
- `ai-fabric-actions-registry`;
- `ai-fabric-retrieval-connector`;
- chat sessions;
- action confirmation;
- indexing update/delete lifecycle;
- governance-safe vector deletion and readiness diagnostics.

**Implementation shape:**

```text
customer-domain-fixture
  -> emits domain events and exposes customer-owned domain APIs
  -> optional Customer Connector API endpoints or local test harness for actions/retrieval

customer-runtime-demo
  -> uses AI Fabric data-sync to ingest domain records and policy-like documents
  -> uses retrieval connector or local vector provider for RAG
  -> uses action connector/registry for governed domain actions
  -> exposes chat/query and admin readiness endpoints
```

**Use current apps where possible:**

- Add a new lightweight customer-domain fixture only if the scenario cannot be expressed cleanly
  inside an existing app.
- Either add a new `customer-runtime-demo` app or split the runtime parts out of
  `chat-capabilities-demo` if that app is currently carrying too many concerns.

**Acceptance tests:**

- Domain record create/update/delete produces matching upsert/delete request payloads.
- Runtime search returns updated records and does not return deleted records.
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

**Start from:** `behavior-churn-signals` and `ai-fabric-account-resolver`.

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
| `ai-fabric-account-resolver` | Account resolver and annotation-assisted subscription search | Merge behavior/retention storyline or keep as advanced indexing reference |
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
3. Build the customer connector runtime scenario around a lightweight fixture or existing chat demo harness.
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
