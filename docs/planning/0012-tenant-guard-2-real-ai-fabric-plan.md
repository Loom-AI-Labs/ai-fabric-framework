# ADR 0012 - Tenant Guard 2.0 real AI Fabric plan

- **Status:** Implemented P0-P3 Tenant Guard 2.0 scope
- **Date:** 2026-07-07
- **Decision owner:** AI Fabric framework and public demo UI
- **Applies to backend:** `examples/real-apps/tenant-knowledge-portal`
- **Applies to frontend:** `/Users/mahmoudashraf/Downloads/Projects/aifabric`
- **Depends on:** ADR 0004 vector provider hardening, ADR 0005 real app coverage, ADR 0006 capability priority map

## Context

## Implementation Status

Implemented on 2026-07-08:

- `tenant-knowledge-portal` now indexes session-scoped tenant documents into AI Fabric vector storage.
- Lucene is the default vector provider for the demo, including the app-specific smoke profile.
- `/api/tenant-guard-demo/query` runs AI Fabric retrieval with trusted `sessionId`, `tenantId`, and
  `visibleToUser` metadata filters.
- Returned evidence is re-checked by the app before being exposed to the UI.
- `/api/tenant-guard-demo/query` now generates the final answer through `AICoreService.generateContent`
  with `LlmPurpose.GENERATION`, using only the verified retrieved evidence and returning citations.
- `/api/tenant-guard-demo/index/seed` and `/api/tenant-guard-demo/index/proof` expose vector provider,
  metadata-filtering, count, and per-tenant proof.
- `/api/tenant-guard-demo/actions/nl` resolves natural-language action requests through
  `AICoreService.generateContent` with `LlmPurpose.ORCHESTRATION`, parses JSON-only action drafts, and
  then passes the draft through the deterministic tenant/role/confirmation policy engine.
- Tenant deletion removes both source documents and indexed vector entities.
- The public UI has an AI Fabric indexed retrieval panel, LLM answer metadata/citations, NL action controls,
  vector proof display, and updated About page architecture notes.

Remaining optional extensions outside this implemented scope:

- Add chat-session memory if Tenant Guard becomes a multi-turn tenant-support assistant.
- Persist tenant documents in a real app table instead of the current session-scoped demo map.
- Consider richer portable metadata filter operators (`notEquals`, `in`, `exists`) after the current
  positive-equality metadata model proves insufficient.

Before Tenant Guard 2.0, the **AI Fabric Tenant Guard** demo was valuable but intentionally
deterministic. It proved tenant-boundary behavior with in-memory documents, app-side keyword search,
app-side role checks, and app-side deletion evidence.

That made the boundary proof easy to inspect, but it did not fully expose the AI Fabric surfaces this
demo now represents:

- tenant-aware indexing;
- metadata-filtered vector/RAG retrieval;
- natural-language tenant knowledge chat;
- governed action resolution and confirmation;
- vector lifecycle/deletion proof;
- provider capability diagnostics around metadata filtering.

Tenant Guard 2.0 keeps the deterministic boundary proof and adds real AI Fabric retrieval, generation,
natural-language action resolution, and vector cleanup proof beside it.

## Current Code Evidence

### Current backend posture

The original `tenant-knowledge-portal` depended on AI Fabric, but used only a small framework slice:

- `ai-fabric-starter`
- `ai-fabric-governance`
- `ActionAccessMode`

Evidence:

- `examples/real-apps/tenant-knowledge-portal/pom.xml` declares `ai-fabric-starter` and
  `ai-fabric-governance`.
- `TenantKnowledgeService` imports `ai.fabric.intent.action.ActionAccessMode`.
- `TenantKnowledgeService.search(...)` performs keyword search over an in-memory map and filters
  through `canRead(...)`.
- `TenantKnowledgeService.executeAction(...)` performs deterministic action target, role, access
  mode, and confirmation checks.
- `TenantKnowledgeService.boundaryProof(...)` creates backend proof checks for the UI.

The current README also says the demo does not use `@AICapable` annotations and primarily proves
scoped metadata filtering, role checks, and deletion evidence rather than live LLM generation.

### Current tenant filtering

Current filtering is app code:

```java
documentsForSession(sessionId).values().stream()
    .filter(document -> canRead(user, document))
```

`canRead(...)` allows platform admin to see all documents, but regular users see only their tenant
and only non-restricted documents:

```java
if (isPlatformAdmin(user)) {
    return true;
}
return user.tenantId().equals(document.tenantId()) && !"restricted".equals(document.visibility());
```

This is safe and inspectable, but it does not prove vector-provider metadata filtering.

### Lucene metadata filtering support

The Lucene vector provider supports metadata-filtered search and scan:

- `supportsSearchMetadataFiltering()` returns `true`.
- `supportsScanMetadataFiltering()` returns `true`.
- diagnostics advertise `lucene-indexed-metadata-query`.
- metadata filter subset is `scalar-string-boolean-integer-long-decimal`.
- search builds a Boolean query with the vector query plus filter query.
- scan builds the same indexed metadata filter query.

The current portable request shape is equality-based:

```java
AISearchRequest.builder()
    .entityType("tenant-document")
    .metadata(Map.of("tenantId", currentTenant, "visibleToUser", true))
    .build();
```

Lucene can safely support:

```text
tenantId = currentTenant
visibleToUser = true
```

Lucene should not be asked, through today’s portable AI Fabric API, to express:

```text
visibility != restricted
```

For Tenant Guard 2.0, model access with positive equality metadata such as `visibleToUser=true`,
`classification=public`, or `audience=user`.

## Decision

Upgrade Tenant Guard into a two-layer demo:

1. **Boundary Proof Layer**
   Keep the current deterministic dashboard proof so the app always has clear, testable guardrails.

2. **Real AI Fabric Layer**
   Add tenant-aware AI Fabric indexing, Lucene metadata-filtered retrieval, RAG answer generation,
   governed action orchestration, and vector lifecycle/deletion proof.

The LLM must never decide the tenant filter. The application resolves tenant context from trusted
request/session data, builds the metadata filter, and verifies retrieved evidence before answer
generation.

## Product Story

Tenant Guard 2.0 should let a user test:

```text
Tenant A asks: "How do I set up VPN?"
  -> AI retrieves only Tenant A VPN evidence.
  -> Answer cites Tenant A Okta/device-compliance guidance.
  -> UI shows the metadata filter and excluded tenant proof.

Tenant B asks the same question.
  -> AI retrieves only Tenant B VPN evidence.
  -> Answer cites hardware-key guidance.

Tenant A user asks: "Archive the Tenant B VPN document."
  -> Action is denied before execution with CROSS_TENANT_DENIED.

Tenant A admin asks: "Archive our VPN setup document."
  -> Action requires confirmation.
  -> Confirmed action executes only for Tenant A.

Platform admin deletes Tenant B evidence.
  -> Tenant B vectors/catalog entries are removed.
  -> Tenant A evidence remains searchable.
  -> Session isolation prevents one public visitor from mutating another visitor's demo.
```

## Architecture

### Storage

Use two stores:

| Store | Role | Demo choice | Production guidance |
| --- | --- | --- | --- |
| App database | Source of truth for tenant documents, roles, sessions, audit/actions | H2 first, Postgres optional | Postgres with `tenant_id`, indexes, and optionally RLS |
| Vector/search index | Retrieval evidence and AI search | Lucene | Postgres/pgvector, Qdrant, OpenSearch, Pinecone, or Lucene for embedded/local |

Lucene is not the multi-tenant database. It is the retrieval index. Tenant isolation comes from
trusted app context plus metadata filters plus post-retrieval verification.

### Proposed backend modules

Keep `tenant-knowledge-portal`, but add:

```text
domain/
  TenantDocument
  TenantDocumentRepository
  TenantPrincipal
  TenantDocumentVisibility

service/
  TenantContextResolver
  TenantDocumentIndexingService
  TenantAwareRetrievalService
  TenantRagAnswerService
  TenantActionPolicyService
  TenantVectorDeletionService
  TenantBoundaryProofService

web/
  TenantAiChatController
  TenantIndexingController
  TenantGuardDemoController
```

### AI Fabric dependencies

Add or enable, as needed:

- `ai-fabric-rag`
- `ai-fabric-indexing`
- `ai-fabric-vector-lucene`
- `ai-fabric-chat-session` if the NL chat should preserve follow-up context
- `ai-fabric-actions-connector` only if the action registry/connector path is part of the demo

Keep:

- `ai-fabric-starter`
- `ai-fabric-governance`

### Metadata model

Index each tenant document with metadata:

```java
Map.of(
    "tenantId", tenantId,
    "sessionId", sessionId,
    "entityType", "tenant-document",
    "audience", "tenant-user",
    "visibleToUser", true,
    "classification", "internal",
    "documentScope", "tenant",
    "sourceApp", "tenant-knowledge-portal"
)
```

For restricted records:

```java
Map.of(
    "tenantId", tenantId,
    "visibleToUser", false,
    "audience", "admin",
    "classification", "restricted"
)
```

Use positive equality filters:

```java
metadata = Map.of(
    "tenantId", currentTenant,
    "visibleToUser", true
)
```

For browser-isolated public sessions, include:

```java
metadata = Map.of(
    "tenantId", currentTenant,
    "sessionId", browserSessionId,
    "visibleToUser", true
)
```

## Request Flow

### Tenant RAG query

```text
Frontend
  query: "How do I set up VPN?"
  sessionId: browser session
  selectedTenant: tenant-a
  role: USER
        |
        v
TenantAiChatController
        |
        v
TenantContextResolver
  validates tenant and role from trusted demo/session context
        |
        v
TenantAwareRetrievalService
  builds AISearchRequest:
    entityType = tenant-document
    metadata = {
      tenantId = tenant-a,
      sessionId = browser session,
      visibleToUser = true
    }
        |
        v
AI Fabric vector/RAG search
  Lucene applies indexed metadata filter
        |
        v
Post-retrieval boundary verifier
  fail closed if any document has wrong tenant/session/visibility
        |
        v
RAG answer generation
  answer only from allowed evidence
        |
        v
Frontend
  renders answer, evidence, filter proof, excluded tenant proof
```

### Governed action

```text
User: "Archive our VPN setup document"
        |
        v
Intent/action resolution
        |
        v
TenantActionPolicyService
  target exists?
  target tenant equals caller tenant or platform admin?
  write access mode?
  ADMIN role?
  confirmation required?
        |
        v
ActionDecision
  DENIED / CONFIRMATION_REQUIRED / APPROVED
```

### Tenant deletion and vector cleanup

```text
Platform admin deletes tenant-b
        |
        v
TenantVectorDeletionService
  scan vectors where:
    sessionId = current session
    tenantId = tenant-b
        |
        v
remove vectors by id/entity
        |
        v
verify:
  tenant-b search empty in this session
  tenant-a search still returns tenant-a evidence
  another browser session still has tenant-b evidence
```

## API Design

Keep existing demo endpoints:

- `GET /api/tenant-guard-demo/dashboard`
- `POST /api/tenant-guard-demo/reset`
- `GET /api/tenant-guard-demo/compare`
- `POST /api/tenant-guard-demo/actions/execute`
- `POST /api/tenant-guard-demo/tenants/delete`

Add real AI endpoints:

```text
POST /api/tenant-guard-demo/query
POST /api/tenant-guard-demo/index/seed
GET  /api/tenant-guard-demo/index/proof
POST /api/tenant-guard-demo/actions/nl
```

Suggested `query` response:

```json
{
  "answer": "...",
  "tenantContext": {
    "tenantId": "tenant-a",
    "role": "USER",
    "sessionId": "browser-123"
  },
  "metadataFilter": {
    "tenantId": "tenant-a",
    "sessionId": "browser-123",
    "visibleToUser": true
  },
  "documents": [
    {
      "id": "doc-a",
      "tenantId": "tenant-a",
      "title": "VPN setup",
      "score": 0.83
    }
  ],
  "boundaryProof": {
    "passed": true,
    "excludedTenantIds": ["tenant-b"],
    "verification": "All retrieved evidence matched tenant-a and visibleToUser=true."
  }
}
```

## Frontend Plan

Update `https://ai-fabric.dev/demos/ai-fabric-tenant-guard`:

1. Keep current deterministic cards.
2. Add a "Real AI Fabric Query" panel.
3. Show tenant selector: Tenant A, Tenant B, Platform Admin.
4. Show role selector: USER, ADMIN.
5. Show natural-language prompt input.
6. Render:
   - AI answer;
   - retrieved documents;
   - metadata filter applied;
   - provider capability proof;
   - boundary verification result.
7. Add action scenarios:
   - cross-tenant write denied;
   - same-tenant admin write confirmation;
   - confirmed same-tenant write.
8. Add vector cleanup proof after tenant deletion:
   - before delete counts;
   - deleted vector ids;
   - after delete search proof;
   - other-session isolation proof.

The frontend must not infer tenant access. It only renders backend evidence.

## Implementation Phases

### P0 - Real indexed retrieval proof

- Add source-of-truth tenant documents in H2/in-memory repository.
- Add indexing service that stores documents through AI Fabric vector APIs with tenant metadata.
- Use Lucene as the demo vector provider.
- Add `/query` endpoint with tenant metadata filter.
- Add post-retrieval verifier that fails closed if returned docs do not match tenant/session/visibility.
- Add tests proving:
  - Tenant A query returns only Tenant A evidence.
  - Tenant B same query returns only Tenant B evidence.
  - `visibleToUser=true` excludes restricted docs.
  - unsupported filter shapes fail closed.

### P1 - RAG answer generation and UI proof

- Add RAG answer generation using retrieved allowed evidence.
- Add UI panel for metadata filter, evidence, and answer.
- Add provider capability panel showing Lucene supports metadata-filtered search and scan.
- Add tests proving answer documents are the same documents shown in evidence.

### P2 - Governed NL actions

- Add natural-language action endpoint or route through existing AI Fabric action/orchestration path.
- Preserve deterministic policy service for target, role, access mode, and confirmation.
- Add tests for:
  - cross-tenant denied;
  - user write denied;
  - admin write confirmation required;
  - confirmed write approved.

### P3 - Vector cleanup lifecycle

- Add tenant/session vector scan and deletion.
- Verify deleted vectors by scan/search.
- Keep browser-session isolation and TTL cleanup.
- Add tests for:
  - deleting tenant B removes only tenant B vectors;
  - tenant A vectors remain;
  - another browser session remains unaffected.

### P4 - Optional framework enhancement

Only after the demo proves the need, consider richer metadata filter operators:

```text
equals
notEquals
in
exists
```

Do not block Tenant Guard 2.0 on this. Model restricted visibility as positive equality today:

```text
visibleToUser = true
```

## Test Plan

### Unit tests

- `TenantContextResolverTest`
- `TenantAwareRetrievalServiceTest`
- `TenantBoundaryVerifierTest`
- `TenantActionPolicyServiceTest`
- `TenantVectorDeletionServiceTest`

### Integration tests

- Lucene metadata-filtered retrieval:
  - tenant A only;
  - tenant B only;
  - visible user docs only;
  - platform admin scoped/broad behavior.
- RAG answer uses only allowed evidence.
- Delete removes only requested tenant/session vectors.

### Live smoke

Use deployed backend:

```text
GET  /api/demo/health
POST /api/tenant-guard-demo/index/seed
POST /api/tenant-guard-demo/query
POST /api/tenant-guard-demo/actions/nl
POST /api/tenant-guard-demo/tenants/delete
GET  /api/tenant-guard-demo/index/proof
```

Expected proof:

- UI bundle is latest.
- Backend commit is latest.
- Lucene diagnostics show metadata-filtered search and scan.
- Tenant A/B same query returns different evidence.
- Restricted docs never enter the RAG answer for tenant users.
- Cross-tenant action is denied before execution.
- Same-tenant write requires confirmation.
- Tenant delete removes only the selected tenant's vectors.

## Risks And Controls

| Risk | Control |
| --- | --- |
| LLM leaks cross-tenant data | LLM never receives cross-tenant evidence; post-retrieval verifier fails closed. |
| Metadata filter bug widens retrieval | Tests assert every returned doc matches tenant/session/visibility; unsupported filters return no docs. |
| `visibility != restricted` is not portable | Use positive equality metadata, e.g. `visibleToUser=true`. |
| Public demo users mutate shared data | Keep session-scoped data and TTL cleanup. |
| UI presents fake intelligence | Natural-language panels call backend AI Fabric endpoints only; no frontend shortcut inference. |
| Lucene local index is mistaken for production multi-tenancy | About page explains Lucene is retrieval index, not tenant source of truth. |

## Open Questions

1. Should Tenant Guard 2.0 use annotation-driven `@AICapable` documents, config-driven `ai-entity-config.yml`, or explicit indexing service APIs?
2. Should platform admin RAG be broad by default, or should admin choose a target tenant before retrieval?
3. Should tenant deletion be exposed as a natural-language action, or remain a protected admin/demo button?
4. Should the demo use real OpenAI for final answer generation, or smoke/local by default with OpenAI-enabled live deployment?

## Recommended Next Step

Implement P0 first:

```text
Tenant documents -> AI Fabric Lucene indexing -> metadata-filtered retrieval -> post-retrieval boundary proof
```

Do not start with NL actions. The most important proof is that AI Fabric retrieval can enforce tenant
boundaries before the LLM sees evidence.
