# 4. Configuration Reference

AI Fabric is configured with standard Spring Boot properties under the `ai.*` namespace. This page
lists the most important keys. Property prefixes are **not** Java packages — they are stable
configuration keys.

> Tip: most beans are `@ConditionalOnMissingBean`, so you can override any of these by supplying your
> own bean. Modules only activate when both present on the classpath and selected here.

## Master switch

| Property | Default | Description |
|----------|---------|-------------|
| `ai.enabled` | `true` | Master toggle for the framework's auto-configuration. |

## Providers (`ai.providers.*`)

### Selection

| Property | Example | Description |
|----------|---------|-------------|
| `ai.providers.llm-provider` | `openai` | Name of the active `AIProvider` used for generation. Matches a provider's `getProviderName()`. |
| `ai.providers.embedding-provider` | `onnx` | Name of the active `EmbeddingProvider`. Defaults to `onnx` when unset. |
| `ai.providers.enable-fallback` | `true` | Allow falling back to another available provider. |

### Per-provider settings

Each active Spring AI LLM provider has its own block (`openai`, `azure`, `anthropic`, `gemini`). A provider
is gated by `enabled` (OpenAI defaults to enabled; the others must be enabled explicitly):

```yaml
ai:
  providers:
    llm-provider: openai
    openai:
      enabled: true
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com/v1
      model: gpt-4o-mini
    anthropic:
      enabled: false
      api-key: ${ANTHROPIC_API_KEY}
      model: claude-sonnet-4-6
```

| Property (per provider `<p>`) | Description |
|-------------------------------|-------------|
| `ai.providers.<p>.enabled` | Register this provider's beans. |
| `ai.providers.<p>.api-key` | Credential (inject via env var). |
| `ai.providers.<p>.base-url` | API base URL (override for proxies/compatible endpoints). |
| `ai.providers.<p>.model` | Default model id. |

Cohere is not active in the Spring AI execution path for this release. Add it back only when AI
Fabric adopts a supported Spring AI Cohere path or an explicit provider-specific exception.

The Spring AI provider also supports runtime-only request helpers for trusted server-side code:
request-scoped Spring AI advisors and guarded AI Fabric action tool callbacks. These are not YAML
settings because callers pass Java objects such as `Advisor` and `ActionContext`. See the
[Spring AI Provider Integration Guide](../Framework-Dev-Guides/runtime-integration/SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md)
and the
[Actions + Confirmation Interceptors Guide](../Framework-Dev-Guides/actions-governance/ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md).

If your application provides a Micrometer `ObservationRegistry`, the Spring AI provider registers a
redacted diagnostics bridge for Spring AI ChatClient, model, advisor, embedding, and tool-call
observations. The snapshot is available from `SpringAiObservationDiagnostics` and excludes prompt
text, completion text, tool arguments, tool results, hidden action context, and transient file URLs.

### Local ONNX embeddings (`ai.providers.onnx.*`)

```yaml
ai:
  providers:
    embedding-provider: onnx
    onnx:
      model-path: ${AI_FABRIC_ONNX_MODEL_PATH:./models/embeddings/all-MiniLM-L6-v2.onnx}
      tokenizer-path: ${AI_FABRIC_ONNX_TOKENIZER_PATH:./models/embeddings/tokenizer.json}
      max-sequence-length: 512
      use-gpu: false
```

Native `onnx` remains the default local embedding path. To use Spring AI's bundled
transformer ONNX path instead, select `spring-ai-onnx`:

Native ONNX and Spring AI ONNX both enforce the `AIEmbeddingRequest` text contract at the provider
boundary: text must be nonblank and at most 8000 characters, including direct provider calls that
bypass web/request validation.

```yaml
ai:
  providers:
    embedding-provider: spring-ai-onnx
    spring-ai-onnx:
      model-uri: ${SPRING_AI_ONNX_MODEL_URI:}
      tokenizer-uri: ${SPRING_AI_ONNX_TOKENIZER_URI:}
      cache-enabled: true
      gpu-device-id: -1
      dimensions: 384
```

## Vector store (`ai.vector-db.*`)

```yaml
ai:
  vector-db:
    type: lucene            # lucene | memory | qdrant | pinecone | weaviate | milvus
    lucene:
      index-path: ./data/lucene-vector-index
      similarity-threshold: 0.7
    operations:
      await-clear-consistency: true
      await-clear-timeout-ms: 30000
      fail-on-missing-payload-index: false
```

| Property | Default | Description |
|----------|---------|-------------|
| `ai.vector-db.type` | `lucene` | Which `VectorDatabaseService` to activate. |
| `ai.vector-db.lucene.index-path` | `./data/lucene-vector-index` | Lucene index directory. |
| `ai.vector-db.lucene.similarity-threshold` | `0.7` | Minimum similarity for matches. |
| `ai.vector-db.operations.await-clear-consistency` | `true` | Wait for remote clear/delete operations to become visible when the provider supports it. |
| `ai.vector-db.operations.await-clear-timeout-ms` | `30000` | Maximum wait for clear consistency polling. |
| `ai.vector-db.operations.fail-on-missing-payload-index` | `false` | Qdrant strict mode. When `true`, metadata-filtered operations fail closed if a required payload index is missing. |
| `ai.vector-db.memory.allow-in-production` | `false` | Explicit acknowledgement required to run the in-memory vector store with a `prod` or `production` Spring profile. |

Vector providers expose exact lifecycle/admin capabilities through `VectorDatabaseService`
diagnostics. Portable metadata filters are exact equality over strings, booleans, and integer/long
numbers; unsupported filter shapes fail closed rather than broadening results. See the detailed
[Vector Database Configuration, Auth, and Deployment Guide](../Framework-Dev-Guides/providers-vector-db/VECTOR_DATABASE_CONFIGURATION_AUTH_AND_DEPLOYMENT_GUIDE.md)
for the provider capability matrix and operational notes.

Cloud stores use their own connection blocks, e.g. Qdrant:

```yaml
ai:
  vector-db:
    type: qdrant
  providers:
    qdrant:
      host: ${AI_PROVIDERS_QDRANT_HOST:localhost}
      grpc-port: ${AI_PROVIDERS_QDRANT_GRPC_PORT:6334}
      api-key: ${AI_PROVIDERS_QDRANT_API_KEY:}
```

## RAG (`ai.infrastructure.rag.*`)

```yaml
ai:
  infrastructure:
    rag:
      enabled: true
      default-limit: 10
      default-threshold: 0.7
      enable-hybrid-search: false
      enable-contextual-search: false
      evaluation:
        enabled: false
```

| Property | Default | Description |
|----------|---------|-------------|
| `ai.infrastructure.rag.enabled` | `true` | Enables the default RAG provider when embedding/search/vector dependencies are present. |
| `ai.infrastructure.rag.default-limit` | `10` | Default result limit when a request does not specify one. |
| `ai.infrastructure.rag.default-threshold` | `0.7` | Default similarity threshold when a request does not specify one. |
| `ai.infrastructure.rag.enable-hybrid-search` | `false` | Requests hybrid retrieval by default when the active search stack can prove support. |
| `ai.infrastructure.rag.enable-contextual-search` | `false` | Requests contextual retrieval by default when supported. |
| `ai.infrastructure.rag.evaluation.enabled` | `false` | Opts into Spring AI-backed RAG evaluation helper beans when `spring-ai-client-chat` and a `ChatClient.Builder` are present. |

The evaluation helpers are for tests and release gates. They wrap Spring AI relevancy and
fact-checking evaluators, map `RAGResponse` documents into Spring AI `EvaluationRequest` supporting
documents, and sanitize URL/path/secret-like metadata before evaluation.

## Relationship Query (`ai.infrastructure.relationship.*`)

```yaml
ai:
  infrastructure:
    relationship:
      enabled: true
      enable-orchestrator-integration: true
      enable-vector-search: true
      default-return-mode: IDS
      max-traversal-depth: 3
      llm:
        model: ""
        temperature: 0.1
        max-tokens: 2000
        max-retries: 3
        timeout-seconds: 30
        min-confidence: 0.6
      planner:
        max-retries: 0
        fail-on-parse-error: false
        min-confidence-to-execute: 0.55
```

| Property | Default | Description |
|----------|---------|-------------|
| `ai.infrastructure.relationship.enabled` | `true` | Enables relationship-query auto-configuration when JPA and core AI classes are present. |
| `ai.infrastructure.relationship.enable-orchestrator-integration` | `true` | Registers the `relationship_query` read action when a `RelationshipQueryAccessControlPolicy` bean is present. |
| `ai.infrastructure.relationship.enable-vector-search` | `true` | Allows vector reranking when the LLM plan says semantic search is useful and vector dependencies exist. |
| `ai.infrastructure.relationship.default-return-mode` | `IDS` | Default response materialization mode: `IDS` or `FULL`. |
| `ai.infrastructure.relationship.max-traversal-depth` | `3` | Maximum relationship traversal depth accepted by planning/validation. |
| `ai.infrastructure.relationship.llm.max-tokens` | `2000` | Output token budget for structured relationship plans. Increase if plans are truncated. |
| `ai.infrastructure.relationship.planner.max-retries` | `0` | Additional bounded repair/retry attempts for invalid plans. |
| `ai.infrastructure.relationship.planner.fail-on-parse-error` | `false` | When true, planner parse failures surface as failures instead of degraded fallback plans. |

The public `ReliableRelationshipQueryService` returns `RAGResponse.success=false` for blank queries
and execution failures, with `metadata.errorCode` and any structured planner context preserved. When
orchestrator integration is enabled, provide a `RelationshipQueryAccessControlPolicy`; the action
handler is not created without it.

## Actions Connector (`ai.actions.connector.*`)

```yaml
ai:
  actions:
    connector:
      base-url: https://connector.example
      execute-path: /actions/execute
      connect-timeout: 3s
      read-timeout: 15s
      max-attempts: 3
      initial-backoff: 1s
      api-key:
        header: X-AIFABRIC-API-KEY
        value: ${AI_ACTIONS_CONNECTOR_API_KEY:}
      hmac:
        secret: ${AI_ACTIONS_CONNECTOR_HMAC_SECRET:}
      mcp-gateway:
        base-url: https://mcp-gateway.internal
        execute-path: /api/internal/mcp/actions/execute
        api-key-header: X-MCP-GATEWAY-API-KEY
        api-key: ${AI_MCP_GATEWAY_API_KEY:}
```

| Property | Default | Description |
|----------|---------|-------------|
| `ai.actions.connector.base-url` | unset | Customer Connector API base URL. Required when file/DB connector actions are registered. |
| `ai.actions.connector.execute-path` | `/actions/execute` | Relative path for connector action execution. |
| `ai.actions.connector.connect-timeout` | `3s` | HTTP connect timeout. |
| `ai.actions.connector.read-timeout` | `15s` | HTTP read timeout. |
| `ai.actions.connector.max-attempts` | `3` | Retry attempts including the first call. |
| `ai.actions.connector.initial-backoff` | `1s` | Initial exponential backoff delay for retryable connector failures. |
| `ai.actions.connector.api-key.header` | `X-AIFABRIC-API-KEY` | Optional outbound API key header name. |
| `ai.actions.connector.api-key.value` | unset | Optional outbound API key value. |
| `ai.actions.connector.hmac.secret` | unset | Optional shared secret for HMAC request signing. Blank HMAC header-name properties fall back to AI Fabric defaults. |
| `ai.actions.connector.mcp-gateway.base-url` | unset | Internal MCP execution gateway base URL for `adapterType=mcp-tool` actions. |
| `ai.actions.connector.mcp-gateway.api-key` | unset | Required for MCP gateway execution. |

Malformed successful connector responses fail closed with `errorCode=INVALID_RESPONSE`. Connector
handled failures (`success=false`) keep their stable `errorCode` and user-safe message even when
optional failure `data` is malformed.

## DB Actions Registry (`ai.actions.db.*`)

```yaml
ai:
  actions:
    db:
      enabled: false
      api-key:
        enabled: true
        header: X-AIFABRIC-API-KEY
        value: ${AI_ACTIONS_DB_REGISTRY_API_KEY:}
      liquibase:
        enabled: true
        change-log: classpath:db/changelog/ai-actions-registry-changelog.yaml
```

| Property | Default | Description |
|----------|---------|-------------|
| `ai.actions.db.enabled` | `false` | Enables the DB-backed connector action registry, its REST endpoints, and the DB action contributor. |
| `ai.actions.db.api-key.enabled` | `true` | Protects `/api/ai/actions/registry/**` with the built-in API key filter. |
| `ai.actions.db.api-key.header` | `X-AIFABRIC-API-KEY` | Header used by the built-in registry API key filter. |
| `ai.actions.db.api-key.value` | unset | Required when the registry API key filter is enabled. |
| `ai.actions.db.liquibase.enabled` | `true` | Enables the optional Liquibase helper module defaults when `ai-fabric-actions-registry-liquibase` is on the classpath. |
| `ai.actions.db.liquibase.change-log` | `classpath:db/changelog/ai-actions-registry-changelog.yaml` | Registry changelog used by the helper when the host app does not supply its own changelog. |

DB-backed action definitions are intentionally narrower than file-based catalogs: flat params only,
no MCP/runtime adapter config, no built-in UI bindings, no provenance, no `postPolicies`, and no
assistant-resolution/evidence metadata. Definitions are validated before persistence and revalidated
when handlers are loaded; stale or manually edited invalid rows are skipped and logged rather than
published as runtime actions. If DB actions exist, `ai.actions.connector.base-url` must also be set so
handlers can execute through the Customer Connector API.

## Chat sessions (`ai.chat.*`)

```yaml
ai:
  chat:
    enabled: true
    window-size: 10
    max-context-chars: 8000
    auto-create-sessions: true
    max-pending-action-stack-depth: 8
    pinned-target-reuse-window-turns: 3
```

| Property | Default | Description |
|----------|---------|-------------|
| `ai.chat.enabled` | `false` | Enables durable conversation enrichment, recording, and confirmation resolution. |
| `ai.chat.window-size` | `10` | Maximum recent turns used for prompt history. |
| `ai.chat.max-context-chars` | `8000` | Character cap for rendered conversation history. |
| `ai.chat.auto-create-sessions` | `true` | Create missing conversation rows on first use after access checks. |
| `ai.chat.max-pending-action-stack-depth` | `8` | Maximum pending confirmation actions stored per conversation; newest entries are kept when the stack is trimmed. |
| `ai.chat.pinned-target-reuse-window-turns` | `3` | Number of turns previously pinned targets may be reused when no new attachments are supplied. |

## PII Detection (`ai.pii-detection.*`)

```yaml
ai:
  pii-detection:
    enabled: true
    mode: REDACT          # PASS_THROUGH | DETECT_ONLY | REDACT
    detection-direction: INPUT_OUTPUT
    store-encrypted-original: false
    expose-original-payload-in-result: false
```

| Property | Default | Description |
|----------|---------|-------------|
| `ai.pii-detection.enabled` | `false` | Enables the PII detection service and input pipeline step. |
| `ai.pii-detection.mode` | `PASS_THROUGH` | Detector mode. The pipeline still masks detected input before downstream LLM calls when detections are present. |
| `ai.pii-detection.detection-direction` | `INPUT_OUTPUT` | Detection scope. Current values are `INPUT` and `INPUT_OUTPUT`. |
| `ai.pii-detection.store-encrypted-original` | `false` | Stores an encrypted original payload when `encryption-secret` is set, or a salted hash when it is not. |
| `ai.pii-detection.encryption-secret` | unset | Secret used to derive the AES key for encrypted original payload records. |
| `ai.pii-detection.expose-original-payload-in-result` | `false` | When PII is detected, keep result `originalQuery` empty unless this is explicitly enabled. Prefer encrypted/hash storage in production. |
| `ai.pii-detection.audit-logging-enabled` | `true` | Emits PII-safe detection summary logs with field/type names, not raw payloads. |

## Behavior Processing (`ai.behavior.processing.*`)

```yaml
ai:
  behavior:
    processing:
      api-enabled: true
      api-max-batch-size: 1000
      api-max-duration: 30m
      scheduled-enabled: false
      schedule-cron: "0 */15 * * * *"
      scheduled-batch-size: 100
      scheduled-max-duration: 10m
      processing-delay: 100ms
      continuous-users-per-batch: 100
      continuous-interval: 5m
```

| Property | Default | Description |
|----------|---------|-------------|
| `ai.behavior.processing.api-enabled` | `true` | Exposes behavior processing API endpoints when the module is active. |
| `ai.behavior.processing.api-max-batch-size` | `1000` | Upper bound applied to API-triggered batch and continuous runs. Must be positive; invalid internal values are normalized to safe defaults. |
| `ai.behavior.processing.api-max-duration` | `30m` | Maximum processing time for API-triggered batch work. Must be positive; invalid internal values fall back to one minute. |
| `ai.behavior.processing.scheduled-enabled` | `false` | Enables scheduled behavior processing. |
| `ai.behavior.processing.schedule-cron` | `0 */15 * * * *` | Cron expression used by the scheduled worker. |
| `ai.behavior.processing.scheduled-batch-size` | `100` | Default batch size for scheduled work and null API batch requests. |
| `ai.behavior.processing.scheduled-max-duration` | `10m` | Maximum processing time for scheduled work. |
| `ai.behavior.processing.processing-delay` | `100ms` | Optional delay between processed users. Negative values are normalized to zero. |
| `ai.behavior.processing.continuous-users-per-batch` | `100` | Default users per iteration for continuous jobs. |
| `ai.behavior.processing.continuous-interval` | `5m` | Default interval between continuous-job iterations. |

API request bounds are validated before work is submitted: batch `maxUsers` and
`maxDurationMinutes` must be greater than zero, batch `delayBetweenUsersMs` must be zero or
greater, continuous `usersPerBatch` and `maxIterations` must be greater than zero, and continuous
`intervalMinutes` must be zero or greater.

## Indexing (`ai.indexing.*`)

`ai-fabric-indexing` is enabled by default when embeddings and a vector database are configured.
It owns queueing, workers, retry/dead-letter behavior, and annotation-driven entity indexing.

```yaml
ai:
  indexing:
    enabled: true
    queue:
      max-retries: 5
      visibility-timeout: 2m
    async-worker:
      enabled: true
      fixed-delay: 1s
      batch-size: 50
    batch-worker:
      enabled: true
      fixed-delay: 15s
      batch-size: 500
```

When `spring-ai-commons` is available, the module also exposes optional Spring AI document ingestion
helpers. They are code-level helpers, not URL fetchers: create readers through
`SpringAiDocumentReaderFactory` with a `SpringAiTrustedResourcePolicy`, then enqueue through
`SpringAiDocumentIndexingAdapter`. The adapter validates that the target `entityType` exists and is
indexable, applies Spring AI transformers such as `TokenTextSplitter`, bounds chunk and metadata
sizes, and drops URL/path/secret-like metadata before queueing.

## Data Sync (`ai.data-sync.*`)

```yaml
ai:
  data-sync:
    enabled: false
    base-path: /api/ai/data-sync
    max-batch-size: 200
    max-content-chars: 8000
    max-field-value-chars: 2000
    max-metadata-keys: 75
```

| Property | Default | Description |
|----------|---------|-------------|
| `ai.data-sync.enabled` | `false` | Enables the optional push-based ingestion API for managed vector databases. |
| `ai.data-sync.base-path` | `/api/ai/data-sync` | Base path used by the data-sync controller. |
| `ai.data-sync.max-batch-size` | `200` | Maximum operations accepted in one batch request. |
| `ai.data-sync.max-content-chars` | `8000` | Maximum normalized content length passed to embedding generation. |
| `ai.data-sync.max-field-value-chars` | `2000` | Maximum field value length when building content from entity payloads. |
| `ai.data-sync.max-metadata-keys` | `75` | Maximum metadata keys retained after normalization. |

Data Sync is opt-in and only starts when embeddings are enabled and a vector database is configured
or a `VectorDatabaseService` bean exists. It fails closed through verified `trace.authContext`
authorization before writing or deleting vectors.

## Retrieval Connector (`ai.retrieval.connector.*`)

```yaml
ai:
  retrieval:
    connector:
      enabled: false
      base-url: https://connector.example
      search-path: /retrieval/search
      connect-timeout: 3s
      read-timeout: 15s
      max-attempts: 3
      initial-backoff: 1s
      max-top-k: 50
      api-key:
        header: X-AIFABRIC-API-KEY
        value: ${AI_RETRIEVAL_CONNECTOR_API_KEY:}
      hmac:
        secret: ${AI_RETRIEVAL_CONNECTOR_HMAC_SECRET:}
```

| Property | Default | Description |
|----------|---------|-------------|
| `ai.retrieval.connector.enabled` | `false` | Enables the optional documents-only retrieval connector module. |
| `ai.retrieval.connector.base-url` | unset | Required when enabled. Base URL for the customer connector API. |
| `ai.retrieval.connector.search-path` | `/retrieval/search` | Relative endpoint path for search requests. |
| `ai.retrieval.connector.connect-timeout` | `3s` | HTTP connect timeout. |
| `ai.retrieval.connector.read-timeout` | `15s` | HTTP read timeout. |
| `ai.retrieval.connector.max-attempts` | `3` | Retry attempts including the first call. |
| `ai.retrieval.connector.initial-backoff` | `1s` | Initial exponential backoff delay for retryable connector failures. |
| `ai.retrieval.connector.max-top-k` | `50` | Upper bound applied to requested `topK` before sending the connector request. |
| `ai.retrieval.connector.api-key.header` | `X-AIFABRIC-API-KEY` | Header name used when an API key value is configured. |
| `ai.retrieval.connector.api-key.value` | unset | Optional static API key value. |
| `ai.retrieval.connector.hmac.secret` | unset | Optional shared secret for HMAC request signing. |

When enabled, the module provides a read-only `RAGProvider` that calls `POST /retrieval/search`.
It backs off if the application already defines a `RAGProvider`, so custom retrieval providers stay
authoritative. The connector validates successful responses as documents-only and fails closed when
a response includes generated answers, prompts, tool instructions, malformed documents, or missing
document arrays.

## Web Endpoints (`ai.web.*`)

```yaml
ai:
  web:
    enabled: true
    base-path: /api/ai
    controllers:
      advanced-rag: true
      compliance: true
      migration: true
      profile: true
      security: true
```

| Property | Default | Description |
|----------|---------|-------------|
| `ai.web.enabled` | `true` | Enables the optional web auto-configuration when the web module is on the classpath. |
| `ai.web.base-path` | `/api/ai` | Prefix used by web controllers, e.g. `/api/ai/security` or `/internal/ai/security`. |
| `ai.web.controllers.advanced-rag` | `true` | Registers advanced RAG endpoints when an `AdvancedRAGService` bean is present. |
| `ai.web.controllers.compliance` | `true` | Registers compliance endpoints when an `AIComplianceService` bean is present. |
| `ai.web.controllers.migration` | `true` | Registers migration endpoints when a `DataMigrationService` bean is present. |
| `ai.web.controllers.profile` | `true` | Registers profile endpoints when an `AIInfrastructureProfileService` bean is present. |
| `ai.web.controllers.security` | `true` | Registers security endpoints when an `AISecurityService` bean is present. |

Controllers are conditional on their backing service beans, so adding `ai-fabric-web` does not force
governance or migration services into applications that do not use them.

## Feature toggles (`ai.service.features.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `ai.service.features.enable-embeddings` | `true` | Enable the embedding service. |
| `ai.service.features.enable-generation` | varies | Enable generation flows. |
| `ai.service.features.enable-search` | varies | Enable search flows. |

Turn off what an app doesn't need (e.g. a pure NL→query app may disable embeddings and the vector
store).

## The `smoke` profile (offline, no keys)

The example apps ship a `smoke` profile that selects deterministic local providers end-to-end:

```yaml
ai:
  providers:
    llm-provider: smoke         # deterministic local provider
    embedding-provider: smoke   # deterministic, in-process (no ONNX file)
  vector-db:
    type: memory                # in-process vector store
```

Activate with `--spring.profiles.active=smoke`. See [Example Applications](06-example-apps.md).

## Next

→ [Use Cases](05-use-cases.md)
