# ADR 0003 - Spring AI capability adoption plan for AI Fabric

- **Status:** Partially implemented
- **Date:** 2026-06-18
- **Decision owner:** AI Fabric framework
- **Context version:** AI Fabric `0.2.1`, Java `21`, Spring Boot `4.1.0`, Spring AI `2.0.0`
- **Depends on:** ADR 0002 Spring AI LLM and embedding execution
- **Indexing note:** Lifecycle details were superseded by ADR 0016 and the 0.4 typed
  `AIEntityIndexingGateway` contract.

## Context

ADR 0002 moved AI Fabric cloud LLM and embedding execution behind Spring AI while preserving
AI Fabric's own contracts for routing, fallback, governance, transient file policy, RAG, actions,
indexing, chat session, and vector providers.

The next adoption question is not "how much Spring AI can we use?" but "which Spring AI pieces remove
commodity code while strengthening AI Fabric's differentiated layer?"

AI Fabric should continue to be positioned as a governed AI enablement framework for Java/Spring
applications:

```text
Spring AI
  -> model execution, tool-call protocol plumbing, structured-output helpers, ETL primitives,
     model observability, MCP transports, optional multimodal model APIs

AI Fabric
  -> provider policy, action governance, confirmation and permissions, RAG policy, indexing lifecycle,
     vector lifecycle/admin, transient file safety, tenant/context metadata, entity annotations,
     chat/session product flows, compliance and governance
```

## Decision

Adopt Spring AI selectively in the following order:

1. Structured output support.
2. ChatClient/advisors/observability bridge.
3. Tool-calling bridge for AI Fabric actions.
4. ETL/document reader helpers for indexing ingestion.
5. Evaluation helpers for RAG and generated answers.
6. Chat memory only as an LLM context-window helper, not as AI Fabric chat history storage.
7. MCP client/server plumbing where it reduces transport boilerplate.
8. Multimodal APIs only when AI Fabric adds explicit image/audio/OCR/moderation modules.

Do not use Spring AI to replace AI Fabric's vector providers in this plan. Vector providers remain
native AI Fabric providers unless a separate ADR changes that decision.

## Priority matrix

| Priority | Spring AI piece | AI Fabric target | Adopt as | Why |
| --- | --- | --- | --- | --- |
| P0 | Structured output converters | `ai.fabric.llm.structured`, relationship-query planning, intent JSON parsing | Helper behind AI Fabric retry/repair | Helper implemented and relationship-query planner migrated; remaining intent JSON callers can migrate incrementally. |
| P0 | ChatClient/advisors | `ai-fabric-provider-spring-ai` | Internal execution facade | Implemented for ChatClient execution, builder customizers, trusted request-scoped advisor attachment, app `ObservationRegistry` propagation to dynamic model instances, and redacted observation diagnostics. |
| P1 | ToolCallback and tool attachment | actions and connector actions | Bridge from AI Fabric action registry to Spring AI tools | Guarded `ToolCallback` bridge, opt-in Spring AI provider attachment, connector failure coverage, and read-only commerce example implemented. |
| P1 | Observability metadata | provider, embedding, action, RAG diagnostics | Mapped telemetry layer | Redacted Spring AI observation diagnostics implemented for ChatClient, chat model, embedding model, advisor, and tool-call observations; deeper request correlation/export remains future work. |
| P2 | ETL DocumentReader/DocumentTransformer | indexing and data-sync ingestion | Optional ingestion helpers | Useful for PDF, Markdown, JSON, Tika, token splitting, and document normalization. |
| P2 | Evaluation testing | RAG and post-action generation tests | Test/quality harness | Adds relevancy and fact-check quality gates without rewriting RAG orchestration. |
| P3 | ChatMemory | chat-session prompt context only | Adapter/optional strategy | Useful for LLM memory windows; not a replacement for durable chat session history. |
| P3 | MCP starters/annotations | MCP action gateway and connector tooling | Transport/annotation helper | Reduces MCP plumbing while AI Fabric keeps action catalog/security model. |
| Future | Image/audio/moderation/OCR APIs | new multimodal AI Fabric modules | New AI Fabric SPIs backed by Spring AI | Avoids writing new provider clients if the product expands beyond chat/embedding. |

## Detailed plan

### 1. Structured output

**Use Spring AI for:**

- `BeanOutputConverter`, `MapOutputConverter`, and `ListOutputConverter`.
- Format instruction generation.
- JSON-schema-based structured output where the provider supports it.
- Native structured output switches where Spring AI exposes them cleanly.

**Keep in AI Fabric:**

- Multi-attempt retry and repair policy.
- Validation of parsed values.
- Failure diagnostics.
- Purpose-specific prompts and bounded facts.
- Redaction and transient input policy.

**Code evidence:**

- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/llm/structured/StructuredJsonExtractor.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/llm/structured/DefaultStructuredJsonCallExecutor.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/llm/structured/springai/SpringAiStructuredOutputSupport.java`
- `ai-infrastructure-module/ai-fabric-relationship-query/src/main/java/ai/fabric/relationship/service/RelationshipQueryPlanner.java`

**Design sketch:**

```text
StructuredJsonCallSpec<T>
  -> SpringAiStructuredOutputSupport builds converter/format/schema support
  -> AICoreService / Spring AI provider call
  -> StructuredOutputConverter converts raw output
  -> AI Fabric validator, retry, diagnostics, fallback
```

**Acceptance tests:**

- Existing malformed/fenced/truncated JSON tests remain green.
- Add converter-backed tests for object, map, list, and validation failure.
- Add provider-neutral test that failed structured output returns AI Fabric diagnostics, not a raw Spring AI exception.

### 2. ChatClient, advisors, and observability bridge

The Spring AI provider now calls Spring AI through `ChatClient` via `SpringAiChatClientFactory`, while
keeping `SpringAiModelResolver` as the source of model identity, endpoint profiles, and cache keys.

**Use Spring AI for:**

- `ChatClient` fluent prompt execution.
- Advisor chain integration.
- Builder customizers and observation wiring.
- Streaming-ready execution shape.

**Keep in AI Fabric:**

- `AIProvider` and `EmbeddingProvider` SPIs.
- Provider selection, fallback, and fail-closed behavior.
- `TransientInputSupport`.
- Provider status and AI Fabric response metadata.

**Code evidence:**

- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiChatProvider.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiChatClientFactory.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiRequestAdvisorSupport.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiModelResolver.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiPromptMapper.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/ProviderMetrics.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiObservationDiagnostics.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiObservationHandler.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/test/java/ai/fabric/provider/springai/SpringAiObservationDiagnosticsTest.java`

**Design sketch:**

```text
AIGenerationRequest
  -> SpringAiModelResolver resolves ChatModel and request options
  -> SpringAiChatClientFactory builds configured ChatClient
  -> trusted request advisors and optional action tool bridge attach
  -> Spring AI ChatClient call
  -> AI Fabric AIGenerationResponse
```

**Implementation note:**

Spring AI documentation recommends using the configured `ChatClient.Builder` path to preserve
observability and customizers. AI Fabric now constructs dynamic clients through
`SpringAiChatClientFactory`, applies `ChatClientBuilderCustomizer` beans, and avoids naked
`ChatClient.create(chatModel)` in the provider call path.

Request-scoped Spring AI advisors are attached through `SpringAiRequestAdvisorSupport`. This is a
trusted server-side Java helper: callers pass actual `Advisor` instances, not user-supplied advisor
names. The provider ignores invalid advisor parameter values and records only safe response metadata
with advisor count and advisor names.

`SpringAiModelResolver` now accepts the application `ObservationRegistry` and passes it into Spring
AI chat/embedding model instances created for default and request-overridden connection profiles.
This preserves model-level Spring AI observations even when AI Fabric builds dynamic cached clients
instead of relying on static starter-created model beans.

`SpringAiObservationHandler` maps Spring AI ChatClient, chat model, embedding model, advisor, and
tool-call observations into `SpringAiObservationDiagnostics`. The snapshot contains counters,
duration totals, provider/operation/component dimensions, token usage totals where available, and
error type. It deliberately excludes prompt text, completion text, tool arguments, tool results, tool
call ids, hidden action context, and transient file URLs.

### 3. Tool calling bridge for AI Fabric actions

This is high value because AI Fabric already has a strong action model, but the provider execution
path did not yet take full advantage of provider-native tool calling. The first production slice is
implemented as an opt-in core/provider bridge that turns registry actions into guarded Spring AI
`ToolCallback`s and attaches them to the Spring AI `ChatClient` request only when the AI Fabric
request explicitly opts in.

**Use Spring AI for:**

- `ToolCallback` definitions.
- `ToolCallingAdvisor` tool loop.
- Tool schema generation where compatible.
- Provider-specific native tool call request/response handling.

**Keep in AI Fabric:**

- `AIActionRegistry`.
- Action permissions and access modes.
- Confirmation gates and pending action lifecycle.
- Sensitive parameter handling.
- Connector/MCP gateway policy.
- Action audit, post-action generation facts, and governance.

**Code evidence:**

- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/tool/AIActionToolCallbackFactory.java`
- `ai-infrastructure-module/ai-fabric-core/src/test/java/ai/fabric/intent/action/tool/AIActionToolCallbackFactoryTest.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiChatProvider.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiProviderAutoConfiguration.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/test/java/ai/fabric/provider/springai/SpringAiProviderAdapterTest.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/test/java/ai/fabric/provider/springai/SpringAiReadOnlyActionToolExampleTest.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/AIActionRegistry.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/AIActionHandler.java`
- `ai-infrastructure-module/ai-fabric-actions-connector/src/main/java/ai/fabric/intent/action/connector/ConnectorAIActionHandler.java`
- `ai-infrastructure-module/ai-fabric-actions-connector/src/main/java/ai/fabric/intent/action/connector/ActionConnectorExecutor.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/steps/IntentHandlingStep.java`

**Design sketch:**

```text
AIActionRegistry
  -> AIActionToolCallbackFactory
  -> guarded ToolCallback list per request/context
  -> SpringAiChatProvider opt-in request parameters
  -> Spring AI ChatClient tools(...)
  -> Tool call arrives
  -> AI Fabric validates access/confirmation/params
  -> action executes or returns confirmation-required result
  -> Spring AI receives sanitized tool result
```

**Important boundary:**

The model may ask for a tool, but AI Fabric decides whether the tool can run.

The implemented bridge:

- builds Spring AI `ToolDefinition` JSON schemas from public action parameters;
- hides system, secret, internal, and `askUser=false` parameters from the model-facing schema;
- drops undeclared and hidden model-supplied arguments before execution;
- lets trusted `ActionContext.actionParams()` provide hidden/system values and override model values;
- denies anonymous execution unless the action opted into `anonymousAllowed`;
- applies `AIActionHandler.validateActionAllowed`;
- returns `CONFIRMATION_REQUIRED` instead of executing confirmation-gated actions;
- serializes stable action results with `actionName`, `toolName`, `success`, `message`, `errorCode`,
  `data`, and pinned targets.
- exposes request helpers for opt-in provider execution through
  `AIActionToolCallbackFactory.requestParameters(actionContext, actionNames)`;
- attaches generated callbacks to the Spring AI provider's `ChatClientRequestSpec.tools(...)` path
  only when the request includes the opt-in flag and trusted `ActionContext`.
- records safe response metadata for attached action tools: callback count and public tool names only,
  never tool arguments or hidden context values;
- covers connector-backed action failure through the same guarded tool result contract, including
  failure serialization and hidden parameter filtering before connector execution.
- includes an executable read-only commerce example that exposes `get_order_details` and
  `list_orders` as provider-native tools, proves hidden `shopperSessionId` context comes from trusted
  `ActionContext.actionParams()`, and rejects model-supplied hidden/unknown values.

### 4. Observability and diagnostics

Spring AI has model, ChatClient, advisor, tool, embedding, image, and vector observations. AI Fabric
should consume those signals but keep its own diagnostics model.

**Use Spring AI for:**

- Token usage observations.
- Provider operation timing.
- Advisor and tool call observations.
- Trace propagation to provider SDKs where available.

**Keep in AI Fabric:**

- Public provider status shape.
- Request IDs and correlation IDs.
- Redaction rules.
- No prompt/completion logging by default.
- Product-level metadata such as search source counts, cache hit, fallback path, and transient input evidence.

**Code evidence:**

- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/ProviderMetrics.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiObservationDiagnostics.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiObservationHandler.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiObservationRegistration.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/test/java/ai/fabric/provider/springai/SpringAiObservationDiagnosticsTest.java`
- `ai-infrastructure-module/ai-fabric-rag/src/main/java/ai/fabric/rag/service/RAGService.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/core/AIEmbeddingService.java`

**Acceptance tests:**

- Verify Spring AI usage metadata maps to `AIGenerationResponse.tokensUsed` and `usage`.
- Verify prompt/completion/tool argument/tool result content is not copied into diagnostics.
- Verify transient file descriptors remain redacted in metadata.

### 5. ETL and document ingestion helpers

AI Fabric indexing owns the lifecycle, queueing, entity mapping, ACL metadata, and vector writes.
Spring AI ETL can help with file/document parsing and chunking before the data enters that lifecycle.

**Use Spring AI for:**

- `DocumentReader` implementations for common formats.
- `DocumentTransformer` implementations such as token/text splitters.
- Spring `Resource` based ingestion in trusted contexts.
- `TokenTextSplitter` as the default optional chunking transformer.

**Keep in AI Fabric:**

- `AIEntityIndexingGateway` and the canonical projected queue contract.
- `IndexingWorkProcessor`.
- `DataSyncService`.
- Entity metadata, tenant/user metadata, and access policy metadata.
- Vector update/delete semantics.
- Fail/retry/dead-letter policy.

**Code evidence:**

- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/indexing/api/AIEntityIndexingGateway.java`
- `ai-infrastructure-module/ai-fabric-indexing/src/main/java/ai/fabric/indexing/DefaultAIEntityIndexingGateway.java`
- `ai-infrastructure-module/ai-fabric-indexing/src/main/java/ai/fabric/indexing/worker/IndexingWorkProcessor.java`
- `ai-infrastructure-module/ai-fabric-indexing/src/main/java/ai/fabric/indexing/document/springai/SpringAiDocumentIndexingAdapter.java`
- `ai-infrastructure-module/ai-fabric-indexing/src/main/java/ai/fabric/indexing/document/springai/SpringAiDocumentReaderFactory.java`
- `ai-infrastructure-module/ai-fabric-indexing/src/main/java/ai/fabric/indexing/document/springai/SpringAiTrustedResourcePolicy.java`
- `ai-infrastructure-module/ai-fabric-data-sync/src/main/java/ai/fabric/datasync/service/DataSyncService.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/rag/VectorDatabaseService.java`

**Security rule:**

Do not construct Spring AI readers directly from untrusted user-supplied URLs. Ingest through AI
Fabric's trusted file/object pipeline, then pass vetted resources into readers. The implemented
reader factory rejects remote URL resources and files outside configured trusted roots before Spring
AI reader construction. The indexing adapter bounds chunk count, content length, metadata count, and
metadata value length, and drops URL/path/secret-like metadata keys by default.

### 6. Evaluation testing for RAG and generated answers

Spring AI evaluators are a good fit for release quality gates and integration tests. They are not a
replacement for AI Fabric's production RAG policy.

**Use Spring AI for:**

- `RelevancyEvaluator`.
- Fact-checking evaluator where the model/provider is available.
- Regression suites for RAG and post-action answer quality.

**Keep in AI Fabric:**

- RAG search-source selection.
- Confidence scoring.
- Metadata filtering.
- Deterministic no-context behavior.
- Governance and access-control checks.

**Code evidence:**

- `ai-infrastructure-module/ai-fabric-rag/src/main/java/ai/fabric/rag/service/RAGService.java`
- `ai-infrastructure-module/ai-fabric-rag/src/main/java/ai/fabric/rag/evaluation/springai/SpringAiRagEvaluationService.java`
- `ai-infrastructure-module/ai-fabric-rag/src/main/java/ai/fabric/rag/evaluation/springai/SpringAiRagEvaluationInput.java`
- `ai-infrastructure-module/ai-fabric-rag/src/main/java/ai/fabric/rag/evaluation/springai/SpringAiRagEvaluationResult.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/steps/RagResponseGenerationSupport.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/steps/PostActionGenerationSupport.java`

Implemented behavior:

- `SpringAiRagEvaluationService` converts AI Fabric `RAGResponse` documents into Spring AI
  `EvaluationRequest` supporting documents.
- Relevancy evaluation is available through Spring AI `RelevancyEvaluator`.
- Fact-checking evaluation is available when the Spring AI fact-checking evaluator is configured.
- Evaluator auto-configuration is opt-in through `ai.infrastructure.rag.evaluation.enabled=true`
  and requires a Spring AI `ChatClient.Builder`.
- RAG document and evaluator-response metadata is bounded and sanitized before leaving AI Fabric.

### 7. Chat memory

Spring AI chat memory is useful for prompt context. AI Fabric chat session is broader: durable
history, ownership/access control, action drafts, pending confirmation, and product workflows.

**Use Spring AI for:**

- Optional message-window memory strategy.
- Tool-call turn boundary preservation.
- Provider-neutral prompt memory adapters.

**Keep in AI Fabric:**

- `ChatSessionService`.
- durable session storage.
- access-control policy.
- pending action and action draft stores.
- conversation recording and enrichment.

**Code evidence:**

- `ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/strategy/SlidingWindowMemoryStrategy.java`
- `ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/service/ChatSessionServiceImpl.java`
- `ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/storage/ChatSessionPendingActionStore.java`
- `ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/storage/ChatSessionActionDraftStore.java`

### 8. MCP plumbing

AI Fabric already treats MCP-capable connectors as governed actions. Spring AI MCP support should be
used for transport and annotation plumbing, not as a replacement for the AI Fabric action platform.

**Use Spring AI for:**

- MCP client/server starters.
- WebMVC/WebFlux streamable HTTP transport support.
- MCP annotations for tools/resources/prompts where useful.
- MCP Java SDK compatibility handling.

**Keep in AI Fabric:**

- connector action catalog.
- credential boundaries.
- managed gateway policy.
- action permission and confirmation semantics.
- audit and post-action facts.

**Code evidence:**

- `ai-infrastructure-module/ai-fabric-actions-connector/src/main/java/ai/fabric/intent/action/connector/AIActionConnectorProperties.java`
- `ai-infrastructure-module/ai-fabric-actions-connector/src/main/java/ai/fabric/intent/action/connector/ConnectorActionCatalogLoader.java`
- `ai-infrastructure-module/ai-fabric-actions-connector/src/main/java/ai/fabric/intent/action/connector/ConnectorActionsRegistryContributor.java`

### 9. Multimodal future modules

If AI Fabric expands into multimodal capabilities, use Spring AI APIs first rather than writing new
provider clients.

Candidate AI Fabric modules:

- `ai-fabric-audio` backed by Spring AI transcription and text-to-speech models.
- `ai-fabric-image` backed by Spring AI image model APIs.
- `ai-fabric-moderation` backed by Spring AI moderation APIs, with AI Fabric policy/routing on top.
- `ai-fabric-ocr` backed by Spring AI OCR-capable provider APIs where available.

Do not add these APIs incidentally to the provider module. Each should have an explicit AI Fabric SPI,
policy model, tests, and examples.

## Non-goals

- Do not replace native AI Fabric vector providers in this plan.
- Do not remove AI Fabric action confirmation, authorization, or audit.
- Do not replace durable chat session storage with Spring AI chat memory.
- Do not expose raw Spring AI prompt/completion/tool argument logging by default.
- Do not add every Spring AI starter to the core classpath. Keep optional capabilities modular.
- Do not create provider-specific escape hatches unless Spring AI cannot expose a required capability.

## Proposed modules and packages

| Module/package | Purpose |
| --- | --- |
| `ai-fabric-core/.../llm/structured/springai` | Structured output helper around Spring AI converters. |
| `ai-fabric-provider-spring-ai/.../SpringAiChatClientFactory` | Build configured ChatClient instances from resolver output. |
| `ai-fabric-provider-spring-ai/.../SpringAiRequestAdvisorSupport` | Trusted request-scoped Spring AI advisor attachment helper. |
| `ai-fabric-provider-spring-ai/.../SpringAiObservationDiagnostics` | Redacted Spring AI observation diagnostics for model, advisor, embedding, and tool-call events. |
| `ai-fabric-core/.../intent/action/tool` | Convert AI Fabric actions into guarded Spring AI ToolCallbacks. |
| `ai-fabric-indexing/.../document/springai` | Optional document reader/chunker adapters for indexing ingestion. |
| `ai-fabric-rag/.../evaluation` | Optional Spring AI evaluator wrappers for RAG quality tests. |
| `ai-fabric-actions-connector/.../mcp/springai` | Optional Spring AI MCP transport/annotation bridge. |

## Release sequence

### Phase 1 - Structured output

- Add Spring AI converter helper. **Implemented.**
- Keep `StructuredJsonCallExecutor` contract stable. **Implemented with optional converter parser.**
- Migrate one internal caller, preferably relationship-query planner. **Implemented.**
- Preserve all existing JSON extraction tests. **Implemented; converter-backed tests added.**

### Phase 2 - ChatClient and observability

- Introduce `SpringAiChatClientFactory`. **Implemented.**
- Preserve current `AIProvider` behavior and response metadata. **Implemented through adapter tests.**
- Add tests proving provider metrics, usage metadata, transient file policy, and unsupported media behavior.
- Attach trusted request-scoped Spring AI advisors. **Implemented via `SpringAiRequestAdvisorSupport`
  and provider adapter tests.**
- Propagate the application `ObservationRegistry` into dynamically built Spring AI chat and embedding
  models. **Implemented and covered by provider adapter tests.**
- Map Spring AI ChatClient, chat model, embedding model, advisor, and tool-call observations into
  redacted AI Fabric diagnostics. **Implemented and covered by diagnostics tests.**
- Remaining: expose richer request correlation/export integrations if needed by operators.

### Phase 3 - Tool calling

- Add `AIActionToolCallbackFactory`. **Implemented.**
- Add tests for allowed read action, denied action, confirmation-required action, missing params, and sensitive params. **Implemented for the core bridge.**
- Attach callbacks through the Spring AI provider behind an opt-in request policy. **Implemented via `ChatClientRequestSpec.tools(...)`.**
- Add connector-action failure coverage through the tool bridge. **Implemented.**
- Add safe tool metadata to provider responses without arguments. **Implemented.**
- Add one opt-in read-only action tool-calling example. **Implemented in
  `SpringAiReadOnlyActionToolExampleTest`.**

### Phase 4 - ETL helpers

- Add optional indexing ingestion adapter. **Implemented via
  `SpringAiDocumentIndexingAdapter`.**
- Add trusted-resource tests for supported document formats. **Implemented for Spring AI text and
  JSON readers.**
- Add security tests rejecting untrusted URLs/resources. **Implemented for remote URL resources and
  files outside trusted roots.**

### Phase 5 - Evaluation and MCP

- Add RAG quality test utilities. **Implemented via `SpringAiRagEvaluationService`; auto-config is
  opt-in with `ai.infrastructure.rag.evaluation.enabled=true`.**
- Add MCP bridge only after action-tool bridge is stable.

## Verification gates

For each phase:

```bash
mvn -f ai-infrastructure-module/pom.xml test
mvn -f ai-infrastructure-module/pom.xml test-compile
git diff --check
```

For provider-specific phases, also run:

```bash
mvn -f ai-infrastructure-module/pom.xml -pl providers/ai-fabric-provider-spring-ai -am -Dtest='SpringAi*' -Dsurefire.failIfNoSpecifiedTests=false test
```

Do not use `-DskipTests` for release verification.

## Source references

- Spring AI ChatClient: https://docs.spring.io/spring-ai/reference/api/chatclient.html
- Spring AI structured output: https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html
- Spring AI tool calling: https://docs.spring.io/spring-ai/reference/api/tools.html
- Spring AI ETL pipeline: https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html
- Spring AI chat memory: https://docs.spring.io/spring-ai/reference/api/chat-memory.html
- Spring AI observability: https://docs.spring.io/spring-ai/reference/observability/index.html
- Spring AI evaluation testing: https://docs.spring.io/spring-ai/reference/api/testing.html
- Spring AI MCP: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html
