# Deployment Chat Latency Optimization Plan

## Scope

This guide explains the current slowness profile for platform-managed deployments and defines the execution plan to reduce end-to-end POC and runtime chat latency without changing core behavior blindly.

For the concrete benchmark runbook, commands, metric definitions, and investigation map, use:

- [DEPLOYMENT_CHAT_BENCHMARKING_GUIDE.md](./DEPLOYMENT_CHAT_BENCHMARKING_GUIDE.md)

It is based on live checks from April 12, 2026 and the current request path in:

- [DeploymentPocChatService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/DeploymentPocChatService.java)
- [ChatRuntimeController.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/web/ChatRuntimeController.java)
- [RAGOrchestrator.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/RAGOrchestrator.java)
- [IntentQueryExtractor.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/IntentQueryExtractor.java)
- [IntentHandlingStep.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/pipeline/steps/IntentHandlingStep.java)
- [RAGService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-rag/src/main/java/com/ai/infrastructure/rag/service/RAGService.java)

## Status Board

### DONE

- Platform POC runtime timeout increased to `60s`.
- Weaviate search adapter overhead reduced.
- Routing hot-path knowledge-base overview optimized and cached.
- Runtime and POC trace timing now expose extraction, embedding, retrieval, search, and response-generation timing.
- Canonical rollouts and ecommerce bootstrap now seed explicit orchestration and generation model defaults.
- Deployment-level Prompts UI now exposes:
  - `ragSimilarityThreshold`
  - `smartSuggestionsEnabled`
  - `ragMaxDocumentsUsedForContext`
  - `ragMaxContextChars`
  - response-generation token budgets by profile
- Canonical rollouts and ecommerce bootstrap now default `smartSuggestionsEnabled=false`.
- OpenAI GPT-5 generation token field handling was fixed.
- Target-resolution prompt contract now keeps explicit current-turn identifiers out of forced clarification.
- Successful read actions can now persist explicit `pinnedTargets` for follow-up turns.
- Canonical rollout recreate/apply now runs in parallel with bounded backend concurrency instead of sequentially.

### PENDING

- **PENDING:** read actions that expect pronoun follow-ups still need to emit explicit `pinnedTargets`. Persistence is fixed, but target production is still missing for flows like `search_products -> add it to cart`.
- **PENDING:** broad informational RAG turns still leave multi-item working sets, so follow-ups like `tell me more about it` remain ambiguous by design unless a single target is pinned.
- **PENDING:** rerun the pronoun validation matrix after action-output contracts are updated so target carry-forward can be verified end to end.

## Current Findings

### Live Benchmark Baseline

The latest live benchmark was run on April 12, 2026 against:

- Pinecone deployment `dep-a85f815f`
- Weaviate deployment `dep-713bb33e`

Auth path used:

- `PLATFORM_PRIVATE`

Queries:

- `hello`
- `tell me about Alienware m18 R2`
- `analyze and summarize high performance laptops for gaming`
- `summarize return policy`

Measured averages:

- Pinecone `dep-a85f815f`
  - average wall clock about `13.7s`
  - average runtime request about `13.1s`
  - average intent extraction about `7.6s`
  - average retrieval about `0.64s`
  - average vector search about `0.09s`
- Weaviate `dep-713bb33e`
  - average wall clock about `20.1s`
  - average runtime request about `19.4s`
  - average intent extraction about `10.6s`
  - average retrieval about `0.45s`
  - average vector search about `0.20s`

Most important result:

- raw retrieval/search is not the main latency driver
- the dominant shared cost is `IntentExtraction`
- the largest Weaviate-specific tax is in `VectorSpaceResolution`, not in the final vector search call itself

### Post-Deploy Benchmark After Hot-Path Optimization

After deploying the routing hot-path fix, the same benchmark was rerun on April 12, 2026.

Measured averages:

- Pinecone `dep-a85f815f`
  - average wall clock about `8.9s`
  - average runtime request about `8.4s`
  - average intent extraction about `3.9s`
  - average vector-space resolution about `0.28s`
- Weaviate `dep-713bb33e`
  - average wall clock about `12.7s`
  - average runtime request about `12.1s`
  - average intent extraction about `8.0s`
  - average vector-space resolution about `0.61s`

What changed:

- vector-space resolution improved sharply on both backends
- the previous Weaviate-specific overview tax is no longer the dominant differentiator
- the remaining dominant latency source is still `IntentExtraction`

Operational observation:

- both live deployments were still using the shared default `gpt-4o-mini`
- neither deployment had `AI_PROVIDERS_ORCHESTRATION_LLM_PROVIDER` or `AI_PROVIDERS_ORCHESTRATION_MODEL` configured

That means intent extraction is still paying the general-purpose default model path instead of a purpose-specific orchestration model.

### Latest Benchmark After Orchestration, Generation, and Trace Tuning

After deploying the later runtime/provider tuning through April 13, 2026, the same benchmark was rerun on the live rollout deployments with authenticated `PLATFORM_PRIVATE` traffic.

Current live observations:

- Both deployments are now using:
  - orchestration model `gpt-5.4-nano-2026-03-17`
  - generation model `gpt-5.4-mini-2026-03-17`
- Pinecone deployment `dep-a85f815f`
  - `hello` about `1.9s` to `3.0s`
  - gaming-laptop summary about `6.5s` to `7.3s`
  - return-policy summary about `5.7s` to `6.6s`
- Weaviate deployment `dep-713bb33e`
  - `hello` about `1.6s` to `2.9s`
  - gaming-laptop summary about `6.3s` to `6.9s`
  - return-policy summary about `5.6s` to `7.4s`

Most important result:

- `VectorSpaceResolution` is no longer the dominant issue
- answered turns are now dominated by:
  - `IntentExtraction` about `1.3s` to `3.1s`
  - `IntentHandling` / response generation about `1.8s` to `3.3s`
- `SmartSuggestions` is now a measurable but secondary tax:
  - usually `0.2s` to `0.65s` when it fires
  - `0ms` when skipped

Operational conclusion:

- the next low-risk rollout-level optimization is to default `smartSuggestionsEnabled=false` for canonical commerce rollouts and the ecommerce bootstrap
- the feature remains deployment-configurable from the Prompts UI and can still be enabled explicitly when the UX value outweighs the latency cost
- the next low-risk generation optimization after that is to seed `generationMaxTokens=800` for the OpenAI rollout path, so the main RAG answer step stops relying on an open-ended provider default
- that budget matches the existing post-action generation default and narrows the answer-generation token ceiling without changing retrieval or routing behavior

### Latest April 13 Benchmark and Follow-Up Validation

Latest clean benchmark after the philosophy-safe extraction changes:

- Pinecone `dep-a85f815f`
  - `hello`: wall about `2.9s`, extraction about `2.1s`
  - `tell me about Alienware m18 R2`: wall about `8.0s`, extraction about `4.1s`, response generation about `1.8s`
  - `analyze and summarize high performance laptops for gaming`: wall about `8.1s`, extraction about `3.0s`, response generation about `3.7s`
  - `summarize return policy`: wall about `7.5s`, extraction about `2.6s`, response generation about `2.9s`
- Weaviate `dep-713bb33e`
  - `hello`: wall about `2.2s`, extraction about `1.6s`
  - `tell me about Alienware m18 R2`: wall about `7.6s`, extraction about `2.8s`, response generation about `3.4s`
  - `analyze and summarize high performance laptops for gaming`: wall about `9.4s`, extraction about `3.2s`, response generation about `4.8s`
  - `summarize return policy`: wall about `7.8s`, extraction about `3.5s`, response generation about `2.6s`

Latest pronoun-follow-up validation:

- explicit identifier in the current turn is now correct on both Pinecone and Weaviate
  - `tell me about Alienware m18 R2` returns grounded information with `requiresTargetResolution=false`
- pronoun follow-ups are still incomplete
  - `tell me more about it` after a broad informational answer remains ambiguous
  - `search products for Alienware m18 R2` followed by `add it to cart` still fails with `TARGET_REQUIRED`

Operational conclusion:

- the remaining pronoun-follow-up issue is now in target production and carry-forward semantics, not in extraction heuristics
- the next functional fix is to make the relevant read/search actions emit explicit `pinnedTargets`
- canonical rollout recreate/apply throughput is now fixed at the platform orchestration layer

### 1. Slowness is not only the vector database

Even simple queries like `hello` are slow enough to show that the baseline pipeline cost is already high before retrieval-heavy behavior starts.

Observed live behavior:

- Weaviate deployment `dep-713bb33e`
  - `hello` about `12s`
  - product/policy information queries about `22s` to `28s`
- Pinecone deployment `dep-a85f815f`
  - `hello` about `8s`
  - similar information queries about `12s` to `16s`

Conclusion:

- vector database choice matters
- but the floor latency is already high due to orchestration and LLM work

### 2. The request path is multi-stage by design

For a normal `/api/chat/me/query` request:

1. Platform POC proxy calls runtime in [DeploymentPocChatService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/DeploymentPocChatService.java).
2. Runtime resolves identity and builds orchestration context in [ChatRuntimeController.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/web/ChatRuntimeController.java).
3. Root access control runs in [AccessControlStep.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/pipeline/steps/AccessControlStep.java).
4. Intent extraction makes an orchestration-model call in [IntentQueryExtractor.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/IntentQueryExtractor.java).
5. Retrieval builds an embedding and runs vector search in [RAGService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-rag/src/main/java/com/ai/infrastructure/rag/service/RAGService.java).
6. Answer generation or post-action generation may make another LLM call in [IntentHandlingStep.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/pipeline/steps/IntentHandlingStep.java).

This means one user-visible request can include:

- 1 authz hop
- 1 orchestration LLM call
- 1 embedding call
- 1 vector search
- 1 generation LLM call

In some flows there are more.

### 3. Remote authz is additive latency

Runtime authz uses remote HTTP by default in:

- [RemoteHttpEntityAccessPolicy.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/authz/RemoteHttpEntityAccessPolicy.java)

It is bounded tightly:

- connect timeout defaults around `500ms`
- request timeout defaults around `1500ms`

This is not the primary cause of 20s to 30s requests, but it is still an extra network hop on the request path.

### 4. Weaviate had avoidable adapter overhead

The Weaviate adapter used to:

- perform repeated class-existence checks
- request full vectors back during search
- do multi-space fanout serially

That overhead has already been reduced in:

- commit `c33b1e48`
- [WeaviateVectorDatabaseService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/victor-databases/ai-infrastructure-vector-weaviate/src/main/java/com/ai/infrastructure/vector/weaviate/WeaviateVectorDatabaseService.java)

### 5. Platform POC timeout was too low

Platform POC proxy had a hardcoded `20s` runtime timeout, which caused false `502` failures on otherwise healthy but slow deployments.

That has already been fixed in:

- commit `b46a0d42`
- [PlatformPocProperties.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/config/PlatformPocProperties.java)
- [application.yml](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/Platfrom/backend/src/main/resources/application.yml)

Default timeout is now `60s`.

### 6. Observability is still incomplete

The stack exposes some retrieval-level timing:

- vector adapters return `processingTimeMs`
- [RAGService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-rag/src/main/java/com/ai/infrastructure/rag/service/RAGService.java) forwards that into `RAGResponse`

But it does not expose a full per-request timing breakdown for:

- authz
- intent extraction
- embedding generation
- vector search per space
- final generation
- total orchestration wall-clock time

Without that, performance work will remain partly guesswork.

### 7. Vector-space overview was doing expensive fallback work

The benchmark showed that the biggest backend-specific delta was in vector-space resolution. The main cause was the knowledge-base overview path:

- [KnowledgeBaseOverviewService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/KnowledgeBaseOverviewService.java)

Before the optimization:

- overview fallback could call `getVectorCountByEntityType(...)` for every supported entity type even on providers where that required enumerating full collections/classes
- overview also walked full vector lists to derive `lastIndexUpdateTime`
- the same overview could be recomputed multiple times in the same request window

Before provider count hardening, that particularly hurt:

- Weaviate
- Milvus

because their count fallback path was materially more expensive than Pinecone/Qdrant/Lucene. Current
provider hardening changed that release posture: Weaviate now uses native aggregate count with a
guarded paged fallback, and Milvus uses native collection statistics with scan fallback only when
Milvus omits `row_count`.

## Already Landed Fixes

These should be deployed before starting a new optimization round:

- `b46a0d42` increase configurable platform POC runtime timeout
- `c33b1e48` optimize Weaviate vector search path
- `67120028` optimize knowledge-base overview for the routing hot path
- `0b0a2460` seed fast OpenAI orchestration defaults for canonical rollouts and ecommerce demo bootstrap
- `5c806f6d` seed explicit generation defaults for canonical rollouts and ecommerce bootstrap
- `f3225c24` default rollout smart suggestions off for latency
- `9bccb75a` seed `generationMaxTokens=800` for rollout-based OpenAI generation
- `5da59e7b` prevent explicit identifiers from triggering target clarification
- `49cdd8f1` persist pinned targets from read actions

That optimization changed:

- introduced a generic `supportsEfficientEntityTypeCount()` capability on [VectorDatabaseService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/rag/VectorDatabaseService.java)
- treated Weaviate and Milvus through lightweight presence checks at the time, before the later native
  Weaviate aggregate-count and Milvus collection-statistics hardening removed the request-path count
  limitation
- made [KnowledgeBaseOverviewService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/KnowledgeBaseOverviewService.java) use lightweight presence checks instead of full count enumeration for those providers
- removed the full-vector `lastIndexUpdateTime` walk from the hot path unless a provider exposes a direct timestamp in stats
- added a short-lived overview cache so repeated prompt/routing lookups in the same request window do not recompute the same overview

## Optimization Plan

### Phase 0: Deploy and Re-Benchmark

Deploy the current branch head first.

Required validation after deployment:

1. Re-run the same query set against at least one Weaviate deployment and one Pinecone deployment.
2. Compare:
   - platform POC wall-clock
   - runtime direct wall-clock if possible
   - vector search `processingTimeMs`
3. Confirm the old `20s` proxy timeout is no longer truncating slow but valid requests.

Recommended benchmark set:

- `hello`
- `tell me about Alienware m18 R2`
- `analyze and summarize high performance laptops for gaming`
- `summarize return policy`

### Phase 1: Add Stage-Level Timings

This is the highest-priority missing capability.

Add timing capture for:

- root access control
- intent extraction
- embedding generation
- vector search
- answer generation
- post-action generation
- total request wall-clock

Attach the timing breakdown to:

- runtime result metadata
- debug logs keyed by request id
- platform POC trace summary

Target files:

- [ChatRuntimeController.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/web/ChatRuntimeController.java)
- [RAGOrchestrator.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/RAGOrchestrator.java)
- [IntentQueryExtractor.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/IntentQueryExtractor.java)
- [IntentHandlingStep.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/pipeline/steps/IntentHandlingStep.java)
- [RAGService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-rag/src/main/java/com/ai/infrastructure/rag/service/RAGService.java)
- [DeploymentPocChatService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/DeploymentPocChatService.java)

Success criteria:

- every POC query has a stage timing breakdown
- no more reasoning from aggregate latency only

### Phase 2: Reduce LLM Call Cost Before Retrieval

Intent extraction is a structural latency source because it runs before retrieval.

Immediate optimization options:

1. Configure orchestration model separately from generation model.
   - The code already supports this in [AIProviderConfig.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/config/AIProviderConfig.java).
   - Use a smaller/faster model for `ORCHESTRATION`.
2. Avoid repair passes unless parsing actually fails.
3. Add deterministic shortcuts for trivial low-risk requests where possible.
4. Reduce token volume in orchestration prompts and conversation history passed into extraction.

Important rule:

- do not weaken routing quality blindly
- measure extraction latency and error rate first

### Phase 3: Reduce Retrieval Cost

This phase focuses on embedding and vector search.

Candidate work:

1. Add query embedding caching for repeated recent prompts.
2. Cache repeated named-entity lookups when the same request appears in the same conversation.
3. Keep single-space retrieval when routing is confident; avoid unnecessary fanout.
4. Preserve the Weaviate search improvements already landed.
5. Compare Weaviate class and tenant behavior under real deployment load after `c33b1e48`.

Important distinction:

- retrieval correctness work and retrieval latency work should stay separate
- do not change RAG behavior merely to hide latency

### Phase 4: Reduce Authz and Connector Round Trips

Remote authz is not the main bottleneck, but it is still a network dependency.

Follow-up actions:

1. Measure real authz duration in production traces.
2. If authz is consistently expensive, consider short-lived positive-result caching for the root `rag:intent` gate where policy allows.
3. Review whether any action-side authz checks are duplicating root checks unnecessarily.

Target file:

- [RemoteHttpEntityAccessPolicy.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/authz/RemoteHttpEntityAccessPolicy.java)

### Phase 5: Improve User-Perceived Latency

Some latency is real and cannot be eliminated fully.

To improve perceived performance:

1. Add streaming or partial response delivery for long generation paths.
2. Show stage progress in POC when a request is still valid but slow.
3. Surface request ids and timing breakdown in the POC trace UI.

This does not reduce compute time, but it turns opaque slowness into observable progress.

### Phase 6: Set Explicit Performance Targets

Use targets so optimization decisions can be judged objectively.

Recommended initial targets:

- P50 `hello`: under `3s`
- P50 direct named-product information query: under `6s`
- P95 broad RAG summary query: under `12s`
- no false platform timeout before `60s`

If Weaviate remains materially slower than Pinecone after instrumentation and adapter fixes, decide explicitly whether:

- Weaviate remains a supported parity target
- or demos should prefer Pinecone when latency matters more than backend variety

## Recommended Execution Order

1. Deploy `b46a0d42` and `c33b1e48`.
2. Deploy the knowledge-base overview routing optimization.
3. Re-benchmark Weaviate vs Pinecone using the same query suite.
4. Add an orchestration-model override for extraction and compare extraction latency against the current `gpt-4o-mini` baseline.
5. Tune embedding/search path only after stage timings confirm it is still dominant.
6. Add UI/perceived-latency improvements after hard latency work is measurable.

## What Not To Do

- Do not treat vector DB choice as the only problem.
- Do not change RAG behavior just to hide slow queries.
- Do not optimize without stage-level measurements.
- Do not compare deployments using different query mixes and assume the numbers are equivalent.

## Current Recommendation

The next code changes should be:

1. make the relevant read/search action outputs emit explicit `pinnedTargets`
2. keep rollout recreate/apply parallel so dev verification is not blocked on sequential platform orchestration
3. only then run the next latency round, because the remaining hot path is mostly:
   - `IntentExtraction`
   - response generation
