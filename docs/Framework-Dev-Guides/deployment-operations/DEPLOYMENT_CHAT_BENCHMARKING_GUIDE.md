# Deployment Chat Benchmarking Guide

This guide is the operational runbook for benchmarking platform-managed chat deployments through the same authenticated POC path the Platform UI uses.

Use it when you need to:

- compare rollout deployments such as Pinecone, Weaviate, Qdrant, and Milvus
- identify whether latency comes from extraction, retrieval, embeddings, search, or final answer generation
- decide what to optimize next without changing behavior blindly
- debug regressions after rollout, provider, or prompt changes

This guide complements:

- [DEPLOYMENT_CHAT_LATENCY_OPTIMIZATION_PLAN.md](./DEPLOYMENT_CHAT_LATENCY_OPTIMIZATION_PLAN.md)
- [ORCHESTRATION_OPTIMIZATION_GUIDE.md](../architecture/orchestration/ORCHESTRATION_OPTIMIZATION_GUIDE.md)

## Scope

This runbook benchmarks the live authenticated POC route:

- platform entry: [DeploymentController.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/web/DeploymentController.java)
- platform proxy/service: [DeploymentPocChatService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/DeploymentPocChatService.java)
- runtime entry: [ChatRuntimeController.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/web/ChatRuntimeController.java)

That makes the benchmark useful for real deployment comparisons because it includes:

- platform session auth
- platform proxy overhead
- runtime orchestration
- provider latency
- vector database latency

It is not a synthetic micro-benchmark.

## What To Run

### 1. Resolve rollout deployment ids

If you are benchmarking canonical verification rollouts, resolve the current deployment ids first:

```bash
PLATFORM_BASE_URL="https://ai-fabric-framework-production-324f.up.railway.app" \
PLATFORM_LOGIN_EMAIL="admin@gmail.com" \
PLATFORM_LOGIN_PASSWORD="admin" \
./scripts/resolve-verification-rollouts.sh
```

Use the returned deployment ids for the benchmark.

### 2. Run the benchmark script

The benchmark script is:

- [benchmark-platform-poc-chat.py](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/scripts/benchmark-platform-poc-chat.py)

Quick comparison run:

```bash
PLATFORM_BASE_URL="https://ai-fabric-framework-production-324f.up.railway.app" \
PLATFORM_LOGIN_EMAIL="admin@gmail.com" \
PLATFORM_LOGIN_PASSWORD="admin" \
python3 scripts/benchmark-platform-poc-chat.py \
  --deployment dep-a85f815f:Pinecone \
  --deployment dep-713bb33e:Weaviate \
  --deployment dep-7786c409:Qdrant \
  --deployment dep-11c2fdce:Milvus \
  --runs 2
```

Stronger confidence run:

```bash
PLATFORM_BASE_URL="https://ai-fabric-framework-production-324f.up.railway.app" \
PLATFORM_LOGIN_EMAIL="admin@gmail.com" \
PLATFORM_LOGIN_PASSWORD="admin" \
python3 scripts/benchmark-platform-poc-chat.py \
  --deployment dep-a85f815f:Pinecone \
  --deployment dep-713bb33e:Weaviate \
  --deployment dep-7786c409:Qdrant \
  --deployment dep-11c2fdce:Milvus \
  --runs 5 \
  --pause-ms 250
```

JSON output for deeper analysis:

```bash
PLATFORM_BASE_URL="https://ai-fabric-framework-production-324f.up.railway.app" \
PLATFORM_LOGIN_EMAIL="admin@gmail.com" \
PLATFORM_LOGIN_PASSWORD="admin" \
python3 scripts/benchmark-platform-poc-chat.py \
  --deployment dep-a85f815f:Pinecone \
  --deployment dep-713bb33e:Weaviate \
  --runs 5 \
  --format json > /tmp/platform-chat-benchmark.json
```

The JSON output includes per-query:

- averages
- medians
- nearest-rank p95
- min/max
- raw run details

### 3. Override the query set when needed

The default query set is:

- `hello`
- `tell me about Alienware m18 R2`
- `analyze and summarize high performance laptops for gaming`
- `summarize return policy`

You can replace it:

```bash
python3 scripts/benchmark-platform-poc-chat.py \
  --deployment dep-a85f815f:Pinecone \
  --runs 3 \
  --query hello:::hello \
  --query catalog:::tell me about Razer Blade 16 2024 \
  --query compare:::compare Razer Blade 16 2024 and Alienware m18 R2
```

## How To Run It Correctly

### Use the authenticated POC path

Default auth path should be:

- `PLATFORM_PRIVATE`

That matches the normal platform admin/operator testing flow and keeps the benchmark consistent with the POC UI.

### Benchmark comparable deployments

Do not compare:

- indexed deployments vs empty deployments
- public anonymous auth paths vs platform private auth paths
- different prompt versions unless that difference is the thing you are testing

Before trusting the results, confirm each deployment’s workspace summary:

- vector database type
- total vectors
- counts by entity type

The benchmark script prints that information first.

### Use enough runs

Recommended:

- quick smoke comparison: `2` runs
- engineering decision: `5` runs
- stronger confidence / provider variance study: `10` runs

For LLM-based systems, single runs are too noisy. Use median and p95, not only averages, when the stakes are high.

## Metrics To Watch

The benchmark reads the live `traceSummary` returned by:

- [DeploymentPocTraceSummary.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/model/DeploymentPocTraceSummary.java)

### Primary metrics

- `wallMs`
  - end-to-end client-observed latency from the benchmark process
- `runtimeRequestDurationMs`
  - total time spent inside the deployed runtime request
- `pipelineDurationMs`
  - orchestration pipeline time inside runtime
- `extractionProcessingTimeMs`
  - total intent extraction wall time
- `extractionProviderProcessingTimeMs`
  - provider-reported extraction model time
- `retrievalProcessingTimeMs`
  - total retrieval stage time
- `embeddingProcessingTimeMs`
  - query embedding wall time
- `embeddingProviderProcessingTimeMs`
  - provider-reported embedding time
- `searchProcessingTimeMs`
  - vector database search time only
- `responseGenerationProcessingTimeMs`
  - final answer generation wall time
- `responseGenerationProviderProcessingTimeMs`
  - provider-reported generation time
- `documentCount`
  - number of grounded docs used in the traced result
- `responseGenerationPath`
  - generation path such as `RAG_ANSWER`, `RAG_ANSWER_CONCISE`, `RAG_ANSWER_DEEP`, `RAG_NO_CONTEXT`
- `resultType`
  - output mode such as `INFORMATION_PROVIDED`, `ACTION_EXECUTED`, `CLARIFICATION_REQUIRED`, `ERROR`

### Derived metrics that matter

- local extraction overhead
  - `extractionProcessingTimeMs - extractionProviderProcessingTimeMs`
- local embedding overhead
  - `embeddingProcessingTimeMs - embeddingProviderProcessingTimeMs`
- local generation overhead
  - `responseGenerationProcessingTimeMs - responseGenerationProviderProcessingTimeMs`
- retrieval orchestration overhead
  - `retrievalProcessingTimeMs - embeddingProcessingTimeMs - searchProcessingTimeMs`
- platform/proxy overhead
  - `wallMs - runtimeRequestDurationMs`
- runtime non-pipeline overhead
  - `runtimeRequestDurationMs - pipelineDurationMs`

Those deltas tell you whether the slowness is mostly provider-bound or mostly framework/runtime-bound.

## How To Interpret The Results

### Case 1: `hello` is already slow

Likely issue:

- extraction baseline
- platform auth/proxy overhead
- runtime request setup

Investigate:

- [DeploymentPocChatService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/DeploymentPocChatService.java)
- [ChatRuntimeController.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/web/ChatRuntimeController.java)
- [IntentQueryExtractor.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/IntentQueryExtractor.java)
- [ProgressiveIntentExtractionEngine.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/extraction/ProgressiveIntentExtractionEngine.java)

### Case 2: extraction dominates all answered turns

Signal:

- `extractionProcessingTimeMs` is the largest stable stage on every query

Investigate:

- orchestration model choice and prompt shape
- extraction path / attempt count / llm call count
- prompt contracts under:
  - [system.md](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/curated/ai-curated-default/src/main/resources/prompts/intent-extraction/compound/v1/system.md)
  - [classify.md](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/curated/ai-curated-default/src/main/resources/prompts/intent-extraction/multi-step/v1/classify.md)
- runtime prompt config loading:
  - [RuntimeDeploymentPromptConfigService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/config/RuntimeDeploymentPromptConfigService.java)

### Case 3: retrieval is high but search is low

Signal:

- `retrievalProcessingTimeMs` is high
- `searchProcessingTimeMs` is low

Likely issue:

- embedding provider latency
- vector-space resolution overhead
- retrieval orchestration overhead before or after the raw search call

Investigate:

- [RAGService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-rag/src/main/java/com/ai/infrastructure/rag/service/RAGService.java)
- [AIEmbeddingService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/core/AIEmbeddingService.java)
- [VectorSpaceResolutionStep.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/pipeline/steps/VectorSpaceResolutionStep.java)
- [KnowledgeBaseOverviewService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/KnowledgeBaseOverviewService.java)

### Case 4: search is high only on one backend

Signal:

- `searchProcessingTimeMs` differs materially across vector backends for the same query

Investigate the backend adapter:

- Pinecone: [PineconeVectorDatabaseService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/victor-databases/ai-infrastructure-vector-pinecone/src/main/java/com/ai/infrastructure/vector/pinecone/PineconeVectorDatabaseService.java)
- Weaviate: [WeaviateVectorDatabaseService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/victor-databases/ai-infrastructure-vector-weaviate/src/main/java/com/ai/infrastructure/vector/weaviate/WeaviateVectorDatabaseService.java)
- Qdrant: [QdrantVectorDatabaseService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/victor-databases/ai-infrastructure-vector-qdrant/src/main/java/com/ai/infrastructure/vector/qdrant/QdrantVectorDatabaseService.java)
- Milvus: [MilvusVectorDatabaseService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/victor-databases/ai-infrastructure-vector-milvus/src/main/java/com/ai/infrastructure/vector/milvus/MilvusVectorDatabaseService.java)
- Lucene: [LuceneVectorDatabaseService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/victor-databases/ai-infrastructure-vector-lucene/src/main/java/com/ai/infrastructure/vector/lucene/LuceneVectorDatabaseService.java)

### Case 5: generation dominates answered turns

Signal:

- `responseGenerationProcessingTimeMs` is the largest stage for answered queries

Investigate:

- [IntentHandlingStep.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/pipeline/steps/IntentHandlingStep.java)
- [SpringAiChatProvider.java](/Users/mahmoudashraf/Downloads/Projects/AI-Fabric-Framework/ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiChatProvider.java)
- [SpringAiModelResolver.java](/Users/mahmoudashraf/Downloads/Projects/AI-Fabric-Framework/ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiModelResolver.java)
- deployment prompt budgets and response profiles:
  - [PromptsPage.tsx](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/Platfrom/ui/src/pages/PromptsPage.tsx)
  - [OrchestrationPolicyResolutionStep.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/pipeline/steps/OrchestrationPolicyResolutionStep.java)

### Case 6: `documentCount` is `0` on an indexed deployment

This is usually not a latency problem first. It is a retrieval-quality or indexing problem.

Check:

- vector db type and vector counts from the POC workspace
- `resultType`
- `responseGenerationPath`
- `vectorSpaces`
- deployment prompt config:
  - `ragSimilarityThreshold`
  - `ragMaxDocumentsUsedForContext`
  - `ragMaxContextChars`

Investigate:

- [IntentHandlingStep.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/pipeline/steps/IntentHandlingStep.java)
- [RAGService.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-rag/src/main/java/com/ai/infrastructure/rag/service/RAGService.java)

### Case 7: follow-ups like `it`, `this`, `buy it` are slow or clarify unexpectedly

That is usually not a raw latency issue. It is target carry-forward or target production.

Investigate:

- [TargetResolutionStep.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/intent/orchestration/pipeline/steps/TargetResolutionStep.java)
- [WorkingSetTargetSeedingStep.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-chat-session/src/main/java/com/ai/infrastructure/chat/pipeline/WorkingSetTargetSeedingStep.java)
- [ConversationRecordingStep.java](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/ai-infrastructure-module/ai-infrastructure-chat-session/src/main/java/com/ai/infrastructure/chat/pipeline/ConversationRecordingStep.java)
- relevant action handlers that should emit `pinnedTargets`

## Recommended Benchmark Sequence

Use this order. It prevents chasing the wrong issue.

1. Confirm deployment health and indexing.
2. Run the default query set with `2` runs.
3. Look at `hello` first.
4. Look at one explicit product query.
5. Look at one broad RAG query.
6. Look at one policy query.
7. Compare stage timings across backends.
8. Only then decide whether to optimize:
   - extraction
   - generation
   - embeddings
   - search adapter
   - target carry-forward

## Decision Rules

- If `hello` is slow, start with extraction and baseline request overhead.
- If answered turns are slow but `hello` is fine, look at generation first.
- If retrieval is slow and search is tiny, investigate embeddings or routing overhead.
- If only one backend is slower and `searchProcessingTimeMs` is high there, optimize that adapter.
- If `documentCount=0` on indexed deployments, stop treating it as a latency problem and debug retrieval quality instead.

## Guardrails

- Do not benchmark empty deployments and compare them to indexed deployments.
- Do not compare different prompt configs unless the prompt change is the thing under test.
- Do not optimize from one run.
- Do not optimize from wall-clock alone when `traceSummary` already tells you the stage breakdown.
- Do not add hardcoded word shortcuts to “improve” extraction latency. Follow the framework rule: LLM decides, configuration constrains.

## Minimal Reporting Template

For each deployment, capture:

- deployment id
- vector db type
- total vectors
- query
- result type
- response generation path
- doc count
- wall average
- extraction average
- retrieval average
- embedding average
- generation average
- search average

That is the minimum needed for an engineering decision.
