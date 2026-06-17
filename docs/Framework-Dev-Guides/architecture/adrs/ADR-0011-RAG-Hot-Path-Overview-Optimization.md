# ADR-0011 — Optimize RAG Routing Hot Path Without Changing Retrieval Behavior

## Status
Accepted

## Context
Live benchmarking on April 12, 2026 showed that deployment chat latency was not dominated by the final vector search call.

Observed baseline:
- Pinecone deployment `dep-a85f815f`
  - average wall clock about `13.7s`
  - average runtime request about `13.1s`
- Weaviate deployment `dep-713bb33e`
  - average wall clock about `20.1s`
  - average runtime request about `19.4s`

The benchmark isolated two important facts:
- the largest shared cost was `IntentExtraction`
- the largest backend-specific gap was in `VectorSpaceResolution`

Tracing that path showed the main issue was [KnowledgeBaseOverviewService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/KnowledgeBaseOverviewService.java), which was doing expensive fallback work on the request path:
- computing per-entity counts via `getVectorCountByEntityType(...)` even when a provider could only answer that by enumerating the full store
- deriving `lastIndexUpdateTime` by walking all vectors for all entity types
- recomputing the same overview multiple times inside the same request window

This was especially expensive for:
- Weaviate
- Milvus

Those providers did not expose cheap per-type count statistics in the same way Pinecone, Lucene, or Qdrant effectively do.

We explicitly did **not** want to:
- hardcode product- or provider-specific routing behavior into core orchestration
- change retrieval thresholds, retrieval routing, or RAG recall behavior as part of a latency-only fix
- disable vector-space routing or remove prompt-level knowledge-base context entirely

## Decision
Optimize the routing/overview hot path with a **generic provider capability contract**, not provider-name checks in orchestration logic.

Specifically:

1. Add a provider capability flag:
   - `supportsEfficientEntityTypeCount()`

2. In the overview service:
   - use exact per-type counts only when the provider declares they are cheap
   - otherwise use lightweight presence checks via `scan(limit=1)` for entity-type availability

3. Stop deriving `lastIndexUpdateTime` by scanning live vectors on the request path.
   - only use direct statistics values when a provider exposes them
   - otherwise leave `lastIndexUpdateTime` unset on the hot path

4. Add a short-lived overview cache so repeated prompt/routing lookups do not recompute the same overview multiple times in the same request window.

5. Keep retrieval and routing behavior unchanged:
   - no change to RAG thresholds
   - no change to vector-space selection rules
   - no change to the actual search execution path

## Consequences
Positive:
- reduces request-path latency without changing search semantics
- keeps orchestration generic
- avoids hardcoding provider names in the core overview logic
- preserves exact counts for providers that can answer them cheaply
- reduces repeated request-local recomputation

Negative / tradeoffs:
- for expensive-count providers, request-path overview may no longer include exact `documentsByType`
- for providers that do not expose direct timestamp stats, `lastIndexUpdateTime` may be `null` on the orchestration hot path
- this is a latency-first compromise, not a full observability solution

Important clarification:
- this decision does **not** stop RAG
- this decision does **not** change the vector DB used for retrieval
- this decision does **not** reduce admin/indexing visibility requirements

It only trims expensive overview derivation from the chat request path.

## Follow-up
If exact counts and last-updated timestamps are still needed for prompt/debug/admin surfaces without the latency penalty, the next step is:
- make providers expose cheap `entityTypeCounts` and `lastUpdated` values directly in `getStatistics()`

That is the correct place to restore fidelity, rather than reintroducing full-store scans into the request path.

## Implementation
- [KnowledgeBaseOverviewService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/KnowledgeBaseOverviewService.java)
- [VectorDatabaseService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/rag/VectorDatabaseService.java)
- [GovernanceVectorDatabaseServiceDecorator.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-governance/src/main/java/com/ai/infrastructure/governance/vector/GovernanceVectorDatabaseServiceDecorator.java)
- [WeaviateVectorDatabaseService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/victor-databases/ai-infrastructure-vector-weaviate/src/main/java/com/ai/infrastructure/vector/weaviate/WeaviateVectorDatabaseService.java)
- [MilvusVectorDatabaseService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/victor-databases/ai-infrastructure-vector-milvus/src/main/java/com/ai/infrastructure/vector/milvus/MilvusVectorDatabaseService.java)
