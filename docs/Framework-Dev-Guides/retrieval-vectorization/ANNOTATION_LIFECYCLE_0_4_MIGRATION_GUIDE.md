# AI Entity Lifecycle Migration Guide For 0.4

AI Fabric 0.4 replaces the 0.3 annotation and indexing lifecycle with one typed,
transaction-aware contract. This is an intentional greenfield cutover. There is no runtime adapter
for old annotations, entity YAML, or durable queue payloads.

Use this guide before compiling an application against 0.4 and before deploying it over a database
that ran an earlier indexing module.

## What Changed

| 0.3 form | 0.4 form |
| --- | --- |
| Optional or repeated entity type | Required `@AICapable.entityType` |
| Generic annotation `features` and booleans | Typed entity, field, lifecycle, indexing, and analysis policy |
| `@AIProcess(processType = "...")` | `@AIProcess(operation = AIProcessOperation.*)` |
| Method name implies create/update/delete | Method name has no lifecycle meaning |
| Field named `id` is assumed | `@AIIdentity`, supported JPA identity, or registered identity resolver |
| `AISearchable.weight` | `priority` for projection order and bounded retention only |
| String data types and preprocessing | `AIContextDataType` and `AISearchPreprocessing` enums |
| Include booleans | Typed projection destinations |
| `processEntityForAI(...)` | `AIEntityIndexingGateway.upsert(...)` |
| Paired embedding/index or remove/cleanup calls | One upsert or one idempotent delete |
| Complete Java entity in the queue | Versioned, class-free `AIIndexDocument` |
| Provider work in source rollback compensation | Projected work in source transaction, provider work after commit |
| Old CRUD YAML work flags | `indexing.enabled`, `analysis.enabled`, and `analysis.after` |

`indexing.enabled: false` is an operational entity kill switch for annotation-driven lifecycle
dispatch. Domain methods continue normally and no index work is created. Explicit programmatic
projection remains fail-closed so callers cannot accidentally submit a disabled entity contract.

`priority` is not a renamed similarity weight. It controls the order in which fields are projected
and which fields survive a bounded character budget. Provider ranking remains based on the configured
embedding and vector-search behavior.

## 1. Migrate The Entity

```java
@Entity
@AICapable(
    entityType = "knowledge-article",
    indexingStrategy = IndexingStrategy.ASYNC,
    onUpdateStrategy = IndexingStrategy.SYNC,
    migrationRepository = KnowledgeArticleRepository.class
)
public class KnowledgeArticle {

    @Id
    @AIIdentity
    @AIContext(
        key = "entityId",
        dataType = AIContextDataType.ID,
        destinations = {
            AIContextDestination.VECTOR_METADATA,
            AIContextDestination.API_RESPONSE
        },
        priority = 100,
        required = true
    )
    private String id;

    @AISearchable(
        destinations = {
            AISearchDestination.SEMANTIC_SEARCH,
            AISearchDestination.RAG_CONTEXT
        },
        preprocessing = AISearchPreprocessing.NORMALIZE,
        priority = 100,
        required = true
    )
    private String title;

    @AIContext(
        dataType = AIContextDataType.ID,
        destinations = {AIContextDestination.VECTOR_METADATA},
        priority = 100,
        required = true
    )
    private String tenantId;
}
```

Review every destination. Values approved for vector filters do not automatically need to appear in
LLM context or API responses. Set `sanitizePII=true` only when a PII processor is installed and the
field must fail closed if sanitization fails.

## 2. Migrate Lifecycle Methods

```java
@Transactional
@AIProcess(operation = AIProcessOperation.UPDATE)
public KnowledgeArticle update(String id, UpdateArticle request) {
    KnowledgeArticle article = requireAuthorizedArticle(id);
    article.apply(request);
    return repository.saveAndFlush(article);
}
```

The default target resolver supports a direct entity, `Optional`, collection, array, and Hibernate
proxy. A response wrapper or void/argument-owned delete must name an application
`AIProcessTargetResolver`.

Annotated methods must be public Spring proxy boundaries. Move private or self-invoked lifecycle
methods to another Spring bean, or call `AIEntityIndexingGateway` explicitly.

## 3. Migrate Programmatic Indexing

```java
IndexingOutcome outcome = indexingGateway.upsert(
    article,
    AIProcessOperation.UPDATE,
    IndexingStrategy.SYNC
);

IndexingOutcome deleted = indexingGateway.delete(
    KnowledgeArticle.class,
    articleId,
    IndexingStrategy.SYNC
);
```

`SYNC` does not execute inside the source transaction. It attempts provider work after source commit,
or immediately when no source transaction is active. Failed work remains durable for retry.

## 4. Migrate Entity YAML

An annotation-backed entity needs no YAML. Keep only explicit operational overrides:

```yaml
ai-entities:
  knowledge-article:
    indexing:
      enabled: true
      max-characters: 8000
    analysis:
      enabled: false
      after: []
```

A YAML-only push entity must declare its projection:

```yaml
ai-entities:
  product:
    indexing:
      enabled: true
      max-characters: 8000
    searchable-fields:
      - name: title
        destinations: [SEMANTIC_SEARCH, RAG_CONTEXT]
        preprocessing: NORMALIZE
        priority: 100
        required: true
    metadata-fields:
      - name: tenantId
        data-type: ID
        destinations: [VECTOR_METADATA]
        priority: 100
        required: true
```

Load modular files through normal Spring Boot Config Data:

```yaml
spring:
  config:
    import:
      - optional:classpath:ai-entity-config.yml
      - classpath:domain/catalog-ai-entities.yml
```

Remove nested `entity-type`, generic `features`, `indexable`, `auto-process`, recommendation/search
booleans, embedding booleans, CRUD work flags, and `includeInSearch`.

## 5. Replace Generated Indexing State

The source relational tables remain authoritative. The old indexing queue and generated vectors do
not.

For each deployment:

1. Take the normal database backup.
2. Stop all application instances, indexing workers, and migration jobs.
3. Record affected entity types and source-record counts.
4. Drop the pre-0.4 `ai_indexing_queue` table.
5. Drop the pre-0.4 `ai_indexing_entity_state` table if present.
6. Clear generated vectors for affected entity types.
7. Deploy the 0.4 application.
8. Let AI Fabric create the current queue and ordering-state schema, or create the same schema through
   the application's reviewed database migration process.
9. Verify `/actuator/aifabricEntities` before accepting writes.
10. Run migration/backfill from source records.
11. Verify representative update, delete, retry, dead-letter, and filtered retrieval behavior.

Startup rejects an existing table missing any mapped 0.4 column. Do not add a legacy payload reader
or alter old queue rows into the new shape.

## 6. Operate The Queue

Relevant settings:

```yaml
ai:
  indexing:
    enabled: true
    queue:
      max-retries: 5
      visibility-timeout: 2m
      sync-commit-recovery-timeout: 10m
    sync-retry-worker:
      enabled: true
      fixed-delay: 2s
      batch-size: 25
    async-worker:
      enabled: true
      fixed-delay: 1s
      batch-size: 50
    batch-worker:
      enabled: true
      fixed-delay: 15s
      batch-size: 500
    cleanup:
      enabled: true
      stuck-threshold: 10m
      sweep-interval: 5m
      completed-retention: 7d
      dead-letter-retention: 30d
```

Expose the sanitized actuator endpoint deliberately:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,aifabricEntities
```

Monitor accepted, completed, failed, retried, dead-lettered, superseded, projection-failure, and
duration metrics under `aifabric.indexing.*`. Metric tags never include entity IDs or error messages.

Transactional `SYNC` work is first stored as `COMMIT_PENDING`. This reserves it for the source
request's after-commit callback; retry workers lease only `PENDING` rows and cannot steal work before
the synchronous caller observes its outcome. If an application process stops after source commit but
before dispatch, cleanup changes an expired `COMMIT_PENDING` row to `PENDING`, records
`SYNC_COMMIT_DISPATCH_TIMEOUT`, and leaves its retry count unchanged. Set
`sync-commit-recovery-timeout` longer than the expected commit-to-callback delay and monitor
`commitPending` through the sanitized actuator diagnostics.

Optional analysis is persisted as separate worker-owned work. It remains blocked until the primary
index operation completes, including while the primary is awaiting retry, so analysis cannot observe
or publish a projection that was not successfully indexed.

## 7. Release Verification

Before production:

- compile every entity and lifecycle method against 0.4;
- prove startup rejects invalid identity, destinations, priorities, and private/self-invoked methods;
- prove source commit and rollback with a real transaction manager;
- prove one create/update produces one embedding and one upsert;
- prove delete is idempotent;
- prove provider failure retries and eventually dead-letters;
- prove stale updates and deletes cannot overwrite newer state;
- prove migration and push data sync build the same canonical document shape;
- prove queue payload JSON contains no class name, credential, raw disallowed field, or complete entity;
- run the application package and Docker image, not only unit tests;
- run representative live embedding/vector verification with explicit provider evidence.

For the repository release gate, compile and install every module without executing provider
integration tests before their prerequisites are configured:

```bash
mvn -f ai-infrastructure-module/pom.xml clean install -DskipITs
```

Then download the local ONNX test assets and run the complete integration tail:

```bash
bash ai-infrastructure-module/scripts/download-onnx-model.sh \
  all-MiniLM-L6-v2 \
  ai-infrastructure-module/models/embeddings

export ONNX_MODEL_PATH="$PWD/ai-infrastructure-module/models/embeddings/all-MiniLM-L6-v2.onnx"
export ONNX_TOKENIZER_PATH="$PWD/ai-infrastructure-module/models/embeddings/tokenizer.json"

mvn -f ai-infrastructure-module/pom.xml \
  -pl integration-Testing/integration-tests,integration-Testing/relationship-query-integration-tests,integration-Testing/chat-session-integration-tests,integration-Testing/behavior-integration-tests \
  clean verify
```

The manual/keyed CI workflows follow this same ordering. A provider suite is not a pass when its
credential, model asset, container, or endpoint prerequisite is absent.

Rollback means restoring the previous application/database backup as one controlled deployment and
rebuilding generated vectors for that version. Do not run 0.3 workers against a 0.4 queue or mix both
contracts in one database.
