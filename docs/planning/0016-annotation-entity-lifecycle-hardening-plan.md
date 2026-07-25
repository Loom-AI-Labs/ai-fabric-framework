# Annotation-Driven Entity Lifecycle Hardening Plan

Status: implemented and release-gate verified
Target release: `0.4.0`
Delivery model: greenfield clean cutover
Scope: `@AICapable`, `@AIProcess`, `@AISearchable`, `@AIContext`, entity projection,
indexing dispatch, migration handoff, and annotation-facing documentation.
Primary proof application: `examples/real-apps/ai-fabric-live-data-sync`.

## Executive Decision

Keep the annotation-driven entity model, but make every retained annotation property operational,
testable, and visible.

`@AICapable` is the right entity-level contract for:

- registering a Java type as an AI Fabric entity;
- defining its canonical `entityType`, which becomes its vector-space identity;
- selecting default and operation-specific indexing strategies;
- connecting an existing JPA repository to migration/backfill.

It is not, and should not become, a hidden JPA persistence listener. A normal repository write is not
automatically synchronized merely because the entity has `@AICapable`. The explicit application
boundary remains `@AIProcess` or a direct indexing API.

The pre-`0.4.0` implementation proved the concept, including real create, update, delete, filtered
retrieval, and LLM evidence in the Live Data Sync demo. It also contained the contract gaps that this
plan has now removed:

1. `@AIProcess.processType` was ignored by the active aspect.
2. Method names decided the operation and unknown names defaulted to `CREATE`.
3. Several annotation attributes were collected but did not affect the indexing path.
4. queue workers could not retry failures swallowed inside `AICapabilityService`;
5. create/update could generate and store the same embedding twice;
6. asynchronous queue payloads serialized the complete entity rather than the approved AI projection;
7. transaction rollback compensation was incomplete and incorrect for update/delete;
8. result DTOs, private methods, void methods, and non-standard IDs could silently miss or corrupt sync;
9. annotation/YAML precedence was fragmented rather than compiled into one resolved contract;
10. requested PII sanitization could fail open.

This work is a breaking public-contract correction and belongs in `0.4.0`, not a patch release.
There are no known external framework users, and the framework is still pre-1.0. Implement this as a
greenfield clean cutover: update every framework module, real app, demo, test, document, and course
reference together, without preserving the defective runtime contract.

The previously published `0.3.x` artifacts remain immutable historical releases on Maven Central.
The `0.4.0` release notes must clearly identify the annotation and indexing contract as breaking, but
the implementation must not add deprecation aliases, dual configuration readers, legacy queue
deserializers, or runtime adapters for the old model.

## Implementation Evidence

The greenfield contract in this plan is implemented across framework modules, integration fixtures,
real applications, public guides, and course material. The release-candidate gates completed across
2026-07-24 and 2026-07-25 with:

- `ai-fabric-core`: 615 deterministic tests passed;
- `ai-fabric-indexing`: 65 deterministic and transaction-integration tests passed;
- `ai-fabric-data-sync`: 36 tests passed;
- the full 35-project infrastructure reactor was verified through a clean framework/unit build and
  an explicit ONNX-enabled integration tail;
- the resulting infrastructure reports contain 1,553 tests across 356 suites, with zero failures,
  zero errors, and 9 intentional provider/environment connectivity skips;
- the integration-test module completed 94 tests with 6 intentional environment/provider skips and
  no failures or errors;
- relationship-query, chat-session, and behavior integration modules completed 1, 23, and 15
  integration tests respectively, with no failures or errors;
- the complete 21-module real-app reactor completed `clean verify` successfully: 278 tests across 94
  suites, with no failures, errors, or skips;
- the Live Data Sync acceptance app completed 11 app tests plus 12 shared smoke-support tests,
  including five consecutive create/update/delete/reset lifecycle repetitions without timing sleeps;
- the external course support application compiled and completed 71 tests against `0.4.0`;
- the packaged Live Data Sync JAR passed health, source/vector count, update, filtered retrieval,
  deletion, and reset checks;
- the release-candidate Docker image passed the same lifecycle checks;
- a live OpenAI run used `text-embedding-3-small` and `gpt-4o-mini`, retrieved the updated
  workspace-scoped vector, and generated an `INFORMATION_PROVIDED` answer from the changed evidence;
- removed-contract, stale-YAML, stub, secret, and diff-quality scans completed without a release
  blocker.

The final lifecycle stress gate exposed and then fixed a real transaction/worker race. Fresh `SYNC`
rows now enter `COMMIT_PENDING`, which reserves them for the source request's after-commit dispatch.
Retry workers can lease only `PENDING` rows and therefore cannot return control to the caller while a
concurrent worker still owns the same lifecycle operation. Cleanup releases an abandoned
`COMMIT_PENDING` row after `syncCommitRecoveryTimeout`, records
`SYNC_COMMIT_DISPATCH_TIMEOUT`, and does not consume a provider retry. Optional dependent analysis
work remains worker-owned and blocked until its primary indexing work completes.

The final review also tightened three externally visible edge cases: synchronous provider failures
return `FAILED_RETRYABLE` immediately when their durable row is pending retry; a newer synchronous
write cannot bypass an older non-terminal row for the same entity; and entity-level
`indexing.enabled: false` now acts as a real annotation-lifecycle kill switch without rolling back
the domain method.

Publication evidence that necessarily depends on a published artifact remains a release-time step:
record the exact source commit/tag and run the default Maven Central consumer Docker target after
`0.4.0` is available from Central. The release-candidate image built directly from this source state
already passes the equivalent runtime proof.

## Greenfield Delivery Rules

1. Build only the target annotation and lifecycle model described in this plan.
2. Remove obsolete APIs and properties in the same change that introduces their replacements.
3. Convert all repository-owned consumers in one coordinated branch before release.
4. Treat framework examples and deployed demos as internal acceptance fixtures, not compatibility
   constraints.
5. Reset and rebuild disposable demo queues, vector indexes, and generated AI data from source
   records; do not migrate obsolete serialized entity payloads.
6. Preserve valuable behavior and tests, but do not preserve accidental API shape.
7. Do not introduce a compatibility module unless a real external adopter is identified before
   implementation begins and presents a concrete migration requirement.

## Direct Answer: What `@AICapable` Does In `0.4.0`

The implemented annotation contract has one owner and no disconnected feature flags:

| Responsibility | `0.4.0` status | Code evidence |
| --- | --- | --- |
| Entity registration and stable type | Active | `AIEntityDescriptorRegistry` compiles `AICapable.entityType()` into one immutable `AIEntityDescriptor`. |
| Vector-space identity | Active and validated | Projection and gateway operations use `AIEntityDescriptor.entityType()`; an optional `@AIProcess.entityType` is an assertion and must match. |
| Create/update/delete strategy | Active | `AIEntityDescriptor.strategyFor(...)` resolves the default and operation-specific `IndexingStrategy`; an explicit method override may narrow it. |
| Migration repository binding | Active | `EntityRepositoryRegistry` consumes the compiled descriptor's `migrationRepository()`. |
| Searchable/context projection | Active | The descriptor compiler turns `@AISearchable`, `@AIContext`, and `@AIIdentity` into typed accessors used by the canonical projection services. |
| Lifecycle interception | Explicit | `AIProcessAspect` intercepts public Spring service methods annotated with required `AIProcessOperation`; it never infers an operation from a method name. |
| Repository-write interception | Deliberately absent | `@AICapable` describes a type; applications opt into synchronization at an `@AIProcess` boundary or through `AIEntityIndexingGateway`. |
| Runtime entity switch | Active and narrowing-only | `ai-entities.<type>.indexing.enabled: false` prevents annotation-driven dispatch without rolling back the domain method. Configuration cannot add undeclared projection fields. |
| Startup validation | Active and fail-fast | `AIEntityDescriptorInitializer`, `AIEntityContractValidator`, and `AIProcessMethodValidator` reject invalid entity, projection, and method contracts before traffic is accepted. |

The public explanation is:

> `@AICapable` defines the AI-facing identity and indexing policy of an entity. `@AISearchable` and
> `@AIContext` define its approved projection. `@AIProcess` marks the application service boundary
> where a committed entity change should enter AI Fabric's indexing lifecycle.

## Scope Boundaries

### Included

- annotation API cleanup;
- one resolved entity descriptor per entity type;
- entity identity and method-result resolution;
- projection, validation, and PII handling;
- transaction-aware durable indexing dispatch;
- one embedding/upsert operation per create or update;
- retry/dead-letter correctness;
- ordered update/delete handling;
- migration and push-data-sync convergence on the same index document contract;
- startup validation and safe diagnostics;
- framework, real-app, course, and release documentation;
- deterministic, packaged, Docker, and real-provider verification.

### Not Included

- implicit interception of every Spring Data repository write;
- database/vector-store distributed ACID transactions;
- deprecation shims, dual old/new annotation parsing, or a legacy compatibility module;
- migration of disposable `0.3.x` indexing queue rows or generated demo vectors;
- action annotations or governed-action execution;
- document chunking and document-ingestion design from planning document `0010`;
- vector-provider administration work already tracked in planning document `0004`;
- provider-specific ranking claims that cannot be proved across providers;
- use of an LLM to decide deterministic persistence lifecycle operations.

## Pre-`0.4.0` Investigation Baseline

### Proven Working

The deployed Live Data Sync app proves a valid narrow convention:

```text
public @AIProcess service method
  -> save/flush or delete/flush JPA entity
  -> return the affected @AICapable entity
  -> aspect reads entityType and operation configuration
  -> scanner extracts @AISearchable and @AIContext values
  -> indexing coordinator writes/removes the vector
  -> RAG retrieves current workspace-filtered evidence
  -> live provider generates from that evidence
```

The app deliberately:

- uses public Spring service methods;
- names methods `create*`, `update*`, and `delete*`;
- returns the affected entity, including from delete;
- uses a field named `id`;
- selects synchronous indexing;
- sets YAML `generate-embedding: false` and `index-for-search: true` to avoid duplicate embedding calls.

Those conventions made the old path work, but they were undocumented implementation constraints
rather than a complete framework contract.

### Separate Lucene Defect

The metadata-filtered retrieval issue found while building the demo was not an annotation defect.
Commit `994ce71` moved the workspace filter inside Lucene KNN candidate selection. Before that change,
Lucene could select global nearest candidates first and remove another workspace afterward, starving
the requested workspace.

This plan does not reopen that implementation. It requires the filtered-search regression to remain
in provider and Live Data Sync release gates because lifecycle proof is only meaningful if the
retrieval filter is correct.

## Pre-`0.4.0` Runtime Flow And Fault Lines

```text
@AIProcess method invocation
        |
        | aspect loads YAML config by annotation entityType
        v
domain method executes
        |
        | returned object only
        v
method-name operation inference
        |
        | CRUD booleans from YAML AND/OR annotation booleans
        v
IndexingActionPlan
        |
        +-- SYNC -> generateEmbeddings -> store vector
        |           indexForSearch   -> embed again -> store vector again
        |
        +-- ASYNC/BATCH -> serialize complete returned entity into JPA queue
                          -> worker deserializes application class
                          -> capability methods catch/log provider errors
                          -> worker can mark failed only when an exception escapes
```

The target flow replaces operation guessing, full-entity queueing, duplicate work, and rollback
compensation.

## Pre-`0.4.0` Code-Backed Findings

### 1. `processType` Is Not The Runtime Operation

`AIProcess.java` declares a string `processType` with `create`, `update`, `delete`, `search`, and
`analyze` options. `AICapableAspect.processAfterMethod(...)` never reads it. The aspect calls
`getOperationType(joinPoint)`, which infers from the method name and defaults every unrecognized name
to `create`.

Concrete consequences already exist:

- `SubscriptionService.unsubscribe(...)` declares `processType = "update"` but is inferred as create.
- `SubscriptionService.upgrade(...)` and `downgrade(...)` are also inferred as create.
- `AccountResolutionService.requestRefund(...)` is inferred as create.
- `search` and `analyze` map back to `IndexingOperation.CREATE` in `toIndexingOperation(...)`.

The test suite checks that the annotation contains the requested string; it does not prove that the
runtime obeys it.

### 2. Entity Type Is Repeated And Not Validated

The same logical identity is currently repeated in:

- `@AICapable(entityType = "...")`;
- `@AIProcess(entityType = "...")`;
- the `ai-entities.<key>` YAML map key;
- often the nested YAML `entity-type` value.

The aspect requires the method annotation value and uses it to load the YAML config. The vector write
then uses `AIEntityConfig.entityType`, which the parser sets from the YAML map key. The nested YAML
value is not used. The returned entity's `@AICapable.entityType` is used independently for strategy
resolution, metadata registration, and migration.

No startup check proves that these identities agree. A mismatch can therefore select one entity's
configuration/vector space and another entity's indexing strategy.

### 3. The `@AICapable` Advice Can Never Match

`@AICapable` has `@Target(ElementType.TYPE)`. The aspect contains:

```java
@Around("@annotation(aiCapable)")
```

That pointcut searches for a method annotation. The annotation cannot be placed on a method, so
`processAICapableMethod(...)` is dead behavior. `@AICapable` remains useful as entity metadata; only
this advice must be removed.

### 4. The Returned Object Is Assumed To Be The Entity

The aspect supports:

- a direct result;
- a `Collection`;
- an `Optional`.

It does not support:

- a response DTO wrapping the changed entity;
- a void method;
- an entity supplied only as a method argument;
- a delete identified by type and ID;
- a custom application result shape.

This is already unsafe in `AccountResolutionService`:

- `updatePaymentMethod(...)` changes a `Subscription` but returns `PaymentMethodResult`;
- `requestRefund(...)` changes a `Subscription` but returns `RefundResolutionResult`.

The coordinator therefore receives the result DTO under entity type `subscription`. It then falls
back to asynchronous strategy because the DTO has no `@AICapable`, cannot resolve a field named
`id`, and queues the wrong Java class.

### 5. Private And Self-Invoked Methods Are Not Intercepted

`BehaviorAnalysisService.saveAndIndex(...)` is private and called from the same class. Spring proxy
AOP cannot intercept it, so its `@AIProcess` annotation has no runtime effect.

The framework currently has no startup validator that reports private, final, self-invoked, or
otherwise non-proxyable annotated methods.

### 6. Identity Resolution Is Too Narrow

`AICapabilityService.getEntityId(...)` only calls:

```java
entity.getClass().getDeclaredField("id")
```

It does not support:

- inherited IDs;
- `@Id` on a getter;
- `@EmbeddedId`;
- a field with another name;
- records or immutable projections;
- application-defined identity resolution.

The migration module reuses this method, so the limitation affects both live changes and backfills.

### 7. Synchronous Upserts Can Embed And Store Twice

Both `generateEmbeddings(...)` and `indexForSearch(...)`:

1. extract text;
2. call the embedding provider;
3. store the searchable entity.

`IndexingCoordinator` and `IndexingWorkProcessor` call both when both action flags are true.
The Live Data Sync YAML disables `generate-embedding` as a workaround. A framework user should not
need to know that one apparently useful boolean duplicates another operation.

`cleanupEmbeddings(...)` and `removeFromSearch(...)` also both remove the same vector.

### 8. Worker Retries Can Be Bypassed By Swallowed Failures

The queue runner correctly calls `markFailure(...)` when `IndexingWorkProcessor` throws.
`AICapabilityService.generateEmbeddings(...)`, `indexForSearch(...)`, `analyzeEntity(...)`,
`removeFromSearch(...)`, and `cleanupEmbeddings(...)` catch and log their own exceptions.

As a result, a provider or vector-store failure can look like successful work to the worker and cause
the queue row to be marked completed. The durable retry/dead-letter design exists, but the active
capability methods can prevent it from receiving the failure.

### 9. The Queue Stores The Complete Entity

`IndexingCoordinator.enqueue(...)` serializes the returned entity with `ObjectMapper`. The worker
later loads the original application class by name and deserializes it.

This creates four problems:

- fields excluded from `@AISearchable` and `@AIContext` can still enter the queue database;
- sensitive fields can be persisted even though they were not approved for vector/LLM use;
- lazy JPA relationships and proxies can fail or unexpectedly expand;
- queued work can become unreadable after an application class/schema deployment.

The queue should contain a versioned, allowlisted AI index document, not an application entity.

### 10. Transaction Compensation Is Not Correct

The aspect currently writes/enqueues indexing work before the surrounding transaction is known to
have committed, then registers rollback cleanup only when embedding/index flags are enabled.

The compensation behavior is incorrect by operation:

- create rollback: removing a newly written vector is reasonable but still best effort;
- update rollback: removing the vector loses the prior valid version instead of restoring it;
- delete rollback: no compensation is registered, so a rolled-back database delete can leave the
  vector deleted.

There are no focused create/update/delete transaction rollback integration tests for this aspect.

### 11. Annotation And YAML Resolution Is Fragmented

Current rules differ by subsystem:

- searchable extraction uses all `@AISearchable` fields if any exist, otherwise YAML fields;
- context extraction starts with annotation values and adds YAML values with `putIfAbsent`;
- annotation metadata registration merges only `@AIContext` schema into JPA-discovered YAML entities;
- YAML metadata definitions win schema conflicts during registration;
- annotation registration creates missing YAML configs only when
  `ai.config.annotation-metadata.create-missing-entity-config=true`;
- indexing strategy comes from method `@AIProcess`, entity `@AICapable`, then defaults;
- `@AICapable.configFile()` is ignored.

The `@AISearchable` and `@AIContext` Javadocs claim one global
`YAML > annotation > framework` rule. The course configuration script correctly warns that
precedence is scoped. The public contract and the code must agree.

### 12. Several `@AISearchable` Properties Are Descriptive Only

The scanner actively applies:

- `preprocessing`;
- `maxLength`.

It stores but does not enforce in annotation-driven indexing:

- `weight`;
- `includeInSearch`;
- `includeInRAG`;
- `required`;
- `fieldName`;
- `tags`.

The current weight test only checks that `2.0` was read into metadata. It does not prove a different
embedding or ranking. The Live Data Sync README currently calls fields weighted even though dense
indexing concatenates the values without using the weight.

### 13. Several `@AIContext` Properties Are Not Enforced

The scanner actively applies:

- `contextKey`;
- `format`;
- priority ordering.

It does not enforce in the main indexing/RAG path:

- `includeInLLMContext`;
- `includeInResponse`;
- `description`;
- `required` beyond a warning;
- `tags`;
- `sanitizePII`.

`includeInResponse` is honored by the relationship-query document mapper, which proves the concept is
useful, but it is not a central projection rule shared by all retrieval paths.

### 14. Requested PII Sanitization Can Fail Open

For `@AISearchable(preprocessing = "sanitize")`, the scanner returns the raw value when:

- no PII service exists;
- the PII service throws;
- the service does not return processed text.

The current unit test explicitly accepts character cleanup without a PII provider. That is not a safe
interpretation of a property named `sanitize`, and it conflicts with the framework's fail-closed
security philosophy.

### 15. `AICapableProcessor` Is A Second, Disconnected Source

`AICapableProcessor` reads annotation feature flags and exposes maps/lists, but the indexing aspect
and capability service use YAML `AIEntityConfig`. No framework runtime component consumes the
processor's result.

It also duplicates feature names in upper- and lower-case forms and contains unverified performance
and cost claims. It should not remain a parallel public configuration model.

### 16. Annotation Sync And Push Data Sync Are Different Capabilities

The Live Data Sync demo uses `ai-fabric-indexing`, entity annotations, and application service methods.
It does not use `ai-fabric-data-sync`.

`ai-fabric-data-sync` is a trusted push API that receives a map/content payload, validates access, and
normalizes it through YAML configuration. Both paths should eventually produce the same internal
index document, but they remain different ingress mechanisms and must be named separately:

- annotation-driven entity lifecycle synchronization;
- trusted push data synchronization;
- migration/backfill for existing data.

## Keep, Change, Remove

### Annotation Surface

| Surface | Decision | Reason |
| --- | --- | --- |
| `@AICapable` annotation | Keep | Correct entity-level declaration. |
| `AICapable.entityType` | Keep and make required | Canonical typed-entity/vector-space identity. |
| Entity and per-operation indexing strategies | Keep | Active, useful, and already tested. |
| `migrationRepository` | Keep | Active migration/backfill binding. |
| `configFile` | Remove from the annotation | No consumer. Preserve multi-file configuration through Spring Boot Config Data imports instead. |
| Annotation `autoProcess` | Remove | Explicit `@AIProcess` is the lifecycle opt-in. Preserve operational disabling as typed `indexing.enabled` configuration. |
| Annotation `features` | Replace with derived capability reporting | A free-form string bag mixes indexing, RAG, validation, analysis, behavior, and recommendations. Replace controls with typed module policies and derive the effective capability view from the resolved descriptor and active modules. |
| Annotation `enableSearch` | Move out of entity lifecycle | Disconnected and redundant for indexing. An enabled index plus `SEMANTIC_SEARCH` field destinations makes content retrievable; whether a caller may expose search belongs to query/orchestration access policy. |
| Annotation `enableRecommendations` | Remove from the entity lifecycle | Disconnected. Recommendation behavior belongs to its owning capability/module, not vector persistence. |
| Annotation `autoEmbedding` | Remove | Disconnected from the effective index path. An entity upsert generates exactly one embedding. |
| Annotation `indexable` | Replace at the configuration layer | An annotated entity with a valid searchable projection is indexable by default. Preserve the useful operational kill switch as optional `indexing.enabled`; do not duplicate it on the annotation. |
| `@AIProcess` annotation | Keep | Correct explicit application service boundary. |
| `AIProcess.entityType` | Keep as an optional explicit override | Infer from the resolved target when possible; require a match when both are present. |
| String `processType` | Replace | Use a required `AIProcessOperation` enum. |
| `generateEmbedding`, `indexForSearch` | Remove | Upsert is one operation and always embeds exactly once. |
| `enableAnalysis` | Move out of the lifecycle annotation | Preserve analysis through typed `analysis.enabled`/`analysis.after` policy and explicit `ANALYZE` work after a successful upsert. |
| Method indexing strategy override | Keep | Valid per-operation latency decision. |
| `@AISearchable` | Keep and tighten | It is the approved text projection. |
| `preprocessing`, `maxLength`, `required`, logical field name | Keep and enforce | Clear, provider-independent behavior. Replace string preprocessing with an enum. |
| `includeInSearch`, `includeInRAG` | Replace with typed destinations | Build separate semantic-search and RAG-context projections. |
| `weight` | Replace with defined projection priority | Current values never affect embeddings or ranking. Preserve field-importance intent through `priority`, which controls ordering and bounded truncation only. |
| searchable `tags` | Remove | No lifecycle consumer. Use logical field names, priority, destinations, explicit `@AIContext` data, or a custom projector. |
| `@AIContext` | Keep and tighten | It is the approved structured projection. |
| context key, format, description, priority, required | Keep and enforce | Useful for filtering, context rendering, and validation. |
| string `dataType` | Replace | Use a validated `AIContextDataType` enum. |
| `includeInLLMContext`, `includeInResponse` | Replace with typed destinations | One visibility model is easier to validate across vector, LLM, and API paths. |
| `sanitizePII` | Keep and make fail-closed | Security behavior must be real. |
| context `tags` | Remove | No annotation-driven consumer. |
| New `@AIIdentity` | Add | Supports non-JPA entities and avoids relying on a field literally named `id`. |

### Property Preservation Audit

The decision to remove a property is not based only on whether the current code reads it. The audit
also asks whether it expresses a useful requirement and, if so, where that requirement belongs in
the corrected design.

| Existing property | Current evidence | Semantic decision |
| --- | --- | --- |
| `AICapable.configFile` | Declared but never assigned by framework-owned entities and never read at runtime. | Remove from domain types. Use ordered Spring Boot Config Data imports so modular configuration remains possible without coupling an entity class to a resource path. |
| `AICapable.autoProcess` | Read only by `AICapableProcessor` and the impossible type-annotation method advice; the active `@AIProcess` advice does not enforce it. | Remove. Method annotation presence is the developer opt-in; `indexing.enabled` is the operational constraint. |
| `AICapable.features` | Read only by disconnected `AICapableProcessor`. YAML strings are partly read by `AICapabilityService`, but `rag`, `recommendation`, and `behavioral` entries do not enable those modules. | Remove the generic list from both annotation and target YAML. Introduce typed policies owned by each module, then expose a read-only effective-capability view derived from the resolved descriptor and active modules. |
| `AICapable.enableSearch` | Annotation value is disconnected. YAML value is parsed but has no active consumer. | Remove from entity indexing. An enabled index with a `SEMANTIC_SEARCH` projection is the retrieval contract; public/query access is controlled by orchestration or authorization policy. |
| `AICapable.enableRecommendations` | Annotation and YAML values are parsed/exposed but never gate `AICoreService.generateRecommendations(...)` or another recommendation path. | Remove from entity indexing. Do not remove recommendation APIs; any future gate belongs to a recommendation policy owned by that capability. |
| `AICapable.autoEmbedding` | Annotation value is disconnected. YAML value gates `generateEmbeddings(...)`, but `indexForSearch(...)` generates another embedding regardless. | Remove the boolean. Annotation-driven `UPSERT` always generates one embedding. A future trusted push contract may explicitly distinguish generated versus supplied vectors. |
| `AICapable.indexable` | Annotation value is disconnected. YAML `indexable` actively gates capability, document-indexing, migration, and push-data-sync paths. | An annotation-backed descriptor defaults to enabled when it has a valid searchable projection. Preserve operational disabling as optional typed `indexing.enabled` policy; YAML-only push entities must declare it explicitly. |
| `AIProcess.processType` | Declared and widely configured, but the active aspect derives the operation from the Java method name. | Replace with required `AIProcessOperation`; never infer or default the operation. |
| `AIProcess.generateEmbedding` and `indexForSearch` | Actively combined with YAML booleans and responsible for invalid combinations and duplicate stores. | Remove. `CREATE`/`UPDATE` mean one `UPSERT`; `DELETE` means one delete. Omit `@AIProcess` when no lifecycle work is wanted. |
| `AIProcess.enableAnalysis` | Actively produces analysis work and therefore represents real functionality. | Move, do not discard. Configure typed analysis triggers or call `AIEntityAnalysisService` explicitly; analysis gets an independent work record and failure status. |
| `AISearchable.weight` | Collected and asserted in a metadata test; many real apps declare it, but no extraction, embedding, vector, or reranking path reads it. | Remove the ranking claim. Add `priority` with a narrow tested meaning: ordering and token-budget truncation. Design true weighted field embeddings/reranking separately. |
| `AISearchable.includeInSearch` and `includeInRAG` | Collected but ignored by annotation extraction. Similar YAML concepts are consumed inconsistently by data sync and relationship query. | Preserve both needs through typed projection destinations shared by every ingress. |
| `AISearchable.tags` | Used only in Live Data Sync declarations and stored in scanner metadata; no consumer exists. | Remove. Migrate those examples to logical names and priority. Domain tags intended for filtering must be actual `@AIContext` values. |
| `AIContext.dataType` | Used by annotation metadata schema generation; unknown values currently degrade to string. | Keep as a strict enum and fail startup on incompatible Java types. |
| `AIContext.includeInLLMContext` | Collected but not enforced. | Preserve as `LLM_CONTEXT` destination because it is an important privacy and prompt-boundary control. |
| `AIContext.includeInResponse` | Enforced by relationship-query response mapping and explicitly used by Spring AI document metadata. | Preserve as `API_RESPONSE` destination and migrate all existing behavior before removing the boolean. |
| `AIContext.tags` | Collected but never consumed. | Remove. Add a typed purpose only when a concrete projection consumer exists. |

Several retained properties also require stronger contracts:

- searchable `priority` uses a documented `0..100` range, controls projection order and bounded
  truncation, and never claims to change similarity scoring;
- context `priority` uses the same range and controls deterministic prompt/response ordering and
  token-budget retention;
- `description` is bounded schema guidance included when that entity context is rendered for an LLM;
  it is not repeated into every vector metadata record;
- `required` causes projection failure for every selected destination, rather than logging and
  continuing;
- `sanitizePII` and searchable `SANITIZE` fail closed when the privacy service is absent or fails;
- `format`, logical field name, preprocessing, and maximum length are validated at descriptor
  compilation and exercised by projection tests.

### Runtime Components

| Component/behavior | Decision |
| --- | --- |
| `AnnotationFieldScanner` caching concept | Keep internally, compile immutable accessors/descriptors at startup. |
| `AICapableProcessor` | Remove; replace with a resolved descriptor registry/view service that reports derived effective capabilities without controlling them through strings. |
| `AnnotationMetadataEntityConfigRegistrar` | Replace with the descriptor compiler; do not catch and hide invalid startup configuration. |
| `AICapableAspect` name | Rename to `AIProcessAspect`. |
| impossible `@annotation(AICapable)` advice | Remove. |
| method-name operation inference | Remove. |
| unknown-operation fallback to create | Remove. |
| complete-entity queue serialization | Remove. |
| rollback vector deletion compensation | Remove. Dispatch only durable work associated with committed source changes. |
| `IndexingActionPlan` booleans | Replace with typed work: `UPSERT`, `DELETE`, and optional explicit `ANALYZE`. |
| `generateEmbeddings(...)` plus `indexForSearch(...)` lifecycle sequence | Replace with one throwing `upsert(...)` operation. |
| duplicate remove/cleanup operations | Replace with one idempotent delete. |
| catch-and-log worker operations | Remove; throw typed failures to retry/dead-letter handling. |
| queue/retry/dead-letter infrastructure | Keep and make its failure contract effective. |
| migration repository discovery | Keep, but validate duplicates, entity types, and repository compatibility at startup. |
| implicit JPA listeners | Do not add. |

## Target Public Contract

The exact package names may be adjusted during implementation, but the behavioral shape must remain
this small.

### Entity Declaration

```java
@Entity
@AICapable(
    entityType = "sync-product",
    indexingStrategy = IndexingStrategy.ASYNC,
    onUpdateStrategy = IndexingStrategy.SYNC,
    migrationRepository = SyncProductRepository.class
)
public class SyncProduct {

    @Id
    @AIIdentity
    @AIContext(
        key = "entityId",
        dataType = AIContextDataType.ID,
        destinations = {
            AIContextDestination.VECTOR_METADATA,
            AIContextDestination.API_RESPONSE
        },
        required = true
    )
    private String id;

    @AISearchable(
        name = "title",
        destinations = {
            AISearchDestination.SEMANTIC_SEARCH,
            AISearchDestination.RAG_CONTEXT
        },
        preprocessing = AISearchPreprocessing.NORMALIZE,
        priority = 90,
        required = true
    )
    private String title;

    @AIContext(
        key = "workspaceId",
        dataType = AIContextDataType.ID,
        destinations = {AIContextDestination.VECTOR_METADATA},
        required = true
    )
    private String workspaceId;
}
```

Rules:

- `entityType` is required and nonblank.
- exactly one identity resolver must succeed;
- at least one searchable destination must exist for an indexable entity;
- a field with both searchable destinations disabled is invalid;
- searchable and context priorities must be in the `0..100` range;
- priority affects ordering and bounded truncation, never provider similarity scoring;
- vector metadata, LLM context, and API response are separate approved views;
- security constraints can remove data from a destination but cannot be loosened by YAML.

### Lifecycle Boundary

```java
@Transactional
@AIProcess(operation = AIProcessOperation.UPDATE)
public SyncProduct updateProduct(...) {
    return repository.saveAndFlush(product);
}
```

The default target resolver handles:

- a direct `@AICapable` result;
- `Optional<T>`;
- `Collection<T>` and arrays;
- Hibernate proxies unwrapped to their application type.

When an application returns a response wrapper, it uses an application bean:

```java
@AIProcess(
    operation = AIProcessOperation.UPDATE,
    entityType = "subscription",
    targetResolver = UpdatedSubscriptionTargetResolver.class
)
public PaymentMethodResult updatePaymentMethod(...) {
    ...
}
```

For a void delete or argument-owned target:

```java
@AIProcess(
    operation = AIProcessOperation.DELETE,
    entityType = "document",
    targetResolver = DocumentDeleteTargetResolver.class
)
public void deleteDocument(String id) {
    repository.deleteById(id);
}
```

`AIProcessTargetResolver` receives immutable invocation context and returns one or more
`AIProcessTarget` values. A delete target may contain only entity type, entity class, and identity.
An upsert target contains the affected entity snapshot.

### Programmatic Boundary

Applications that cannot use AOP use one explicit API:

```java
IndexingOutcome outcome = aiEntityIndexingGateway.upsert(entity);
```

or:

```java
IndexingOutcome outcome = aiEntityIndexingGateway.delete(
    SyncProduct.class,
    entityId
);
```

This replaces lifecycle use of:

- `processEntityForAI(...)`;
- `generateEmbeddings(...)`;
- `indexForSearch(...)`;
- paired `removeFromSearch(...)` and `cleanupEmbeddings(...)`.

Raw embedding generation remains available through `AIEmbeddingService`; it is not an entity
lifecycle operation.

## Canonical Resolved Entity Descriptor

Add an immutable `AIEntityDescriptorRegistry` in core. Every indexing ingress must resolve the same
descriptor:

```text
AIEntityDescriptor
  entityClass
  entityType
  identityResolver
  searchable field descriptors
  context field descriptors
  indexing policy
  optional analysis policy
  default/create/update/delete strategy
  migration repository binding
  projection version/hash
  source information for each resolved property
```

### Discovery

Compile descriptors from:

1. JPA metamodel entities carrying `@AICapable`;
2. entity classes resolved from `@AIProcess` method signatures;
3. explicit `AIEntityDescriptorContributor` beans for non-JPA or dynamically supplied types.

Do not perform an unrestricted classpath scan. Lazy compilation may support a programmatic entity,
but it must run the same validation before the first write.

### Precedence

There is no universal YAML-versus-annotation rule. Publish and enforce this table:

| Concern | Resolution |
| --- | --- |
| Typed entity identity | `@AICapable.entityType` is canonical; YAML key must match. |
| YAML-only push entity identity | `ai-entities.<key>` is canonical. |
| Field membership/default projection | Field annotation. |
| Explicit supported field override | YAML entry matched by logical field name. |
| Method lifecycle operation | Required typed `@AIProcess.operation`. |
| Method strategy | Method override, then entity operation strategy, then entity default. |
| Entity indexing policy | A valid annotation-backed searchable projection defaults to enabled; optional typed YAML may disable it. YAML-only push entities must configure it explicitly. |
| Entity analysis policy | Disabled by default; optional typed YAML or an explicit programmatic call enables it. |
| Global provider/module settings | Normal Spring Boot property-source precedence. |
| Security/authorization/privacy policy | Final constraint; lower layers cannot widen it. |

The YAML parser must retain whether a value was absent or explicitly configured. Primitive defaults
in the raw binding model currently erase that distinction.

### Target YAML

An annotation-backed entity does not require YAML. Keep optional YAML focused on deployment/runtime
policy rather than repeating annotation behavior:

```yaml
ai-entities:
  sync-product:
    indexing:
      enabled: true
    analysis:
      enabled: false
      after: []
```

Support modular entity configuration through Spring Boot Config Data imports, not
`@AICapable.configFile` or another framework-specific resource loader:

```yaml
spring:
  config:
    import:
      - optional:classpath:ai-entity-config.yml
      - classpath:domain/catalog-ai-entities.yml
```

Import order and duplicate-entity conflict behavior must be deterministic and covered by startup
tests. A later import may override only properties explicitly documented as operational overrides;
it cannot silently replace typed entity identity or widen security destinations.

For YAML-only entities or explicit field overrides, retain `searchable-fields` and `metadata-fields`.
YAML-only push entities must explicitly set `indexing.enabled` and declare their projection because
there is no Java annotation contract to compile.
Remove these duplicate target fields from the `0.4.0` schema:

- nested `entity-type`;
- generic `features`;
- `indexable` (replaced by `indexing.enabled`);
- `auto-process`;
- `enable-search`;
- `enable-recommendations`;
- `auto-embedding`;
- CRUD `generate-embedding`;
- CRUD `index-for-search`;
- CRUD `enable-analysis` (replaced by typed entity analysis policy or an explicit analysis call);
- CRUD `remove-from-search`;
- CRUD `cleanup-embeddings`.

Create/update imply one upsert. Delete implies one delete. A method without `@AIProcess` is outside
annotation-driven synchronization.

## Projection Design

Introduce one `AIEntityProjectionService` that produces a versioned, immutable
`AIIndexDocument` before queueing:

```text
AIIndexDocument
  schemaVersion
  descriptorHash
  entityType
  entityId
  operation
  semanticSearchText
  ragContextText
  vectorMetadata
  sourceVersion (optional)
  correlationId
  occurredAt
```

### Searchable Destinations

- `SEMANTIC_SEARCH`: contributes to the embedding input.
- `RAG_CONTEXT`: contributes to the content returned as evidence.

Within each destination, fields are rendered by descending priority and then stable declaration
order. When a configured projection budget is reached, lower-priority optional fields are truncated
or omitted before higher-priority fields. Required fields cannot be silently omitted. This is the
only `0.4.0` meaning of field importance; it does not alter vector similarity scores.

The vector may therefore be generated from `semanticSearchText` while the vector record stores
`ragContextText` as its user/LLM-facing content. This makes `include in search but not RAG` and
`include in RAG but not search` real, understandable policies.

Do not implement weighting through token repetition. Remove the current `weight` claim. If
field-aware weighted embeddings or hybrid reranking are introduced later, they require:

- a separate capability/configuration;
- batch field embedding;
- mathematically defined composition or a named reranker;
- quality and cost measurements;
- provider-independent contract tests.

### Context Destinations

- `VECTOR_METADATA`: persisted for filtering/identity;
- `LLM_CONTEXT`: available to evidence/prompt construction;
- `API_RESPONSE`: available to sanitized external response projection.

The descriptor, not each downstream mapper, decides visibility. Generic vector administration may
show internal metadata only to an authorized operator API and must not bypass response projection.

### PII

- searchable `SANITIZE` requires a `PIIDetectionService`;
- context `sanitizePII = true` requires successful processing before the value enters any selected
  destination;
- missing/failing PII support produces a typed projection failure;
- raw values are never substituted;
- contradictory use, such as sanitized PII used for exact vector filtering, fails startup unless an
  explicit approved hash/tokenization transformer is configured.

### Required Fields

A required field that is null, blank, inaccessible, or rejected by preprocessing produces a
structured `AIProjectionValidationException`. It is not reduced to a warning.

The exception includes only:

- entity type;
- entity ID when safely available;
- logical field name;
- destination;
- non-sensitive error code.

It must not include raw field content.

## Identity Resolution

Add an `EntityIdentityResolver` SPI with deterministic precedence:

1. an application-provided resolver supporting the entity type;
2. exactly one `@AIIdentity` field or accessor;
3. JPA `@Id` or `@EmbeddedId`, including inherited members.

Startup fails on:

- no identity source for an indexable annotated entity;
- multiple competing sources;
- blank identity from a create/update/delete target;
- non-deterministic or unsupported composite-ID serialization.

Composite IDs use a stable application-provided serializer or a canonical JSON representation with a
versioned format. `toString()` is not an identity contract.

Migration, annotation lifecycle, document indexing where applicable, and direct entity APIs must all
use the same resolver.

## Transaction And Queue Design

### Principle

Do not mutate the vector store and then guess how to undo it when the source transaction rolls back.
Create durable indexing work inside the source transaction and execute it only after commit.

### Target Flow

```text
Spring service invocation
        |
        v
source transaction starts
        |
        v
domain method saves/deletes source data
        |
        v
AIProcessAspect resolves target + compiles safe AIIndexDocument
        |
        v
outbox/queue row inserted in the same transaction
        |
        +-- rollback -> source change and queue row both disappear
        |
        +-- commit
              |
              +-- SYNC  -> same request thread attempts queued work immediately
              |
              +-- ASYNC -> scheduled worker
              |
              +-- BATCH -> batch worker
```

### Guarantees

- No vector operation runs for a rolled-back source transaction.
- A committed async/batch source change has a durable queue row when the queue shares the source
  datasource/transaction manager.
- Provider/storage failures remain pending/retryable and can become dead letters.
- `SYNC` means immediate after-commit processing and same-request visibility when successful. It does
  not mean distributed ACID.
- If sync processing fails after commit, the source change remains committed and the durable row
  records the failure. Do not throw an error that suggests the domain transaction rolled back.
- Applications requiring an explicit indexing result use the programmatic gateway and
  `IndexingOutcome`, not transparent AOP.

### Advisor Ordering

Add and test an explicit `AIProcessAspect` order that places target projection and queue insertion
inside a normal Spring transaction interceptor.

At runtime:

- when transaction synchronization is active, join that transaction and register after-commit sync
  dispatch;
- when no transaction exists, insert the queue row in its own transaction after the method succeeds;
- when a method declares `@Transactional` but no active transaction is visible at the AI boundary,
  fail startup in strict mode or emit a startup validation error rather than silently changing
  semantics.

### Queue Payload

Replace entity class name plus complete serialized entity with the versioned `AIIndexDocument`.
Workers must not require an application entity class to deserialize old work.

Retain queue operational fields:

- operation;
- strategy;
- status;
- retry count and limit;
- scheduling/lease timestamps;
- processing node;
- error/dead-letter summary.

Add:

- payload schema version;
- descriptor hash;
- correlation ID;
- entity sequence/source version where available.

### Ordering And Stale Work

Async updates for the same entity can be processed out of order. A delayed delete can also remove a
newly recreated entity.

Add per-entity ordering:

1. assign a durable monotonic queue sequence for `(entityType, entityId)`;
2. serialize or lock processing for the same identity;
3. record the applied sequence in vector metadata;
4. skip a stale upsert/delete with an explicit `SUPERSEDED` outcome;
5. test update/update and delete/recreate races with multiple workers.

Do not depend only on timestamps generated by different application nodes.

## Indexing Execution Design

Replace the current five booleans with:

```java
enum AIIndexWorkType {
    UPSERT,
    DELETE,
    ANALYZE
}
```

`UPSERT` performs exactly once:

1. validate the versioned projection;
2. generate one embedding from `semanticSearchText`;
3. validate embedding dimensions;
4. upsert entity type, entity ID, RAG content, embedding, and approved metadata;
5. return a typed provider/vector outcome.

`DELETE` performs one idempotent vector deletion by entity type and ID.

`ANALYZE` is explicit optional work after a successful upsert. It may be requested by a typed entity
analysis policy such as `analysis.enabled: true` plus `analysis.after: [CREATE, UPDATE]`, or through
the programmatic analysis service. It is not a create/update/delete operation and is not selected by
method naming. Analysis has its own idempotency key, queue status, retry policy, and result. An
analysis failure cannot cause a successful vector upsert to be falsely retried as though it never
happened.

Split the current broad `AICapabilityService` responsibilities:

| Target service | Responsibility |
| --- | --- |
| `AIEntityDescriptorRegistry` | Compile and expose the resolved entity contract. |
| `AIEntityProjectionService` | Build safe, validated index documents. |
| `AIEntityIndexingService` | Embed once, upsert/delete, and throw typed failures. |
| `AIEntityAnalysisService` | Optional explicit analysis. |
| `AIEntityIndexingGateway` | Programmatic transaction-aware entry point returning `IndexingOutcome`. |

Remove debug accessors and verbose configuration-object logging while performing this split.

## Startup Validation And Diagnostics

Add an `AIEntityContractValidator` that runs after singleton discovery and fails startup for:

- blank or duplicate entity types;
- annotation/YAML entity-type mismatch;
- missing configuration/projection for a YAML-only push entity;
- unsupported or useless field combinations;
- duplicate logical field/context keys;
- invalid preprocessing/data-type/format values;
- priority outside `0..100` or an invalid projection budget;
- conflicting imported entity definitions with no permitted operational override;
- requested PII sanitization without a PII service;
- missing/ambiguous identity;
- `@AIProcess` on a private, static, final, or otherwise non-proxyable method;
- `@AIProcess` on a self-invoked-only method discovered in a Spring bean;
- incompatible return type without a custom target resolver;
- explicit `entityType` that differs from the target descriptor;
- missing strategy/queue dependencies;
- missing operation support.

When Actuator is available, expose a sanitized `aifabricEntities` management view:

- entity type and class;
- field names and destinations, never values;
- identity source type;
- resolved strategy per operation;
- configuration source for each property;
- projection hash;
- registered process methods;
- queue readiness and dead-letter count.

Add Micrometer metrics:

- `aifabric.indexing.accepted`;
- `aifabric.indexing.completed`;
- `aifabric.indexing.failed`;
- `aifabric.indexing.retried`;
- `aifabric.indexing.dead_lettered`;
- `aifabric.indexing.superseded`;
- `aifabric.indexing.duration`;
- `aifabric.indexing.projection_failures`.

Tags must be bounded to provider, strategy, operation, and entity type. Never tag metrics with entity
IDs, tenant IDs, user IDs, exception messages, or content.

## Ingress Convergence

### Annotation Lifecycle

`AIProcessAspect` resolves an entity target and calls `AIEntityProjectionService`.

### Migration

`DataMigrationService` currently serializes each entity into the queue. Change it to:

1. resolve the registered descriptor;
2. resolve identity;
3. project and validate the entity;
4. enqueue the same `AIIndexDocument`;
5. count projection/enqueue failures separately;
6. preserve pause/resume/idempotency behavior.

### Trusted Push Data Sync

`ai-fabric-data-sync` receives maps rather than typed Java entities. Keep its access-control boundary
and normalization rules. Change its successful normalization output into the same `AIIndexDocument`
contract before vector execution.

It must not claim to use entity annotations when no typed entity class is present.

### Document Indexing

Spring AI document readers continue to parse/chunk trusted resources. Their chunks should also enter
the versioned index-document queue contract, while document trust, manifest, and deletion semantics
remain owned by the document-ingestion design.

## Real-App Corrections

### Live Data Sync

- migrate to typed `AIProcessOperation`;
- remove duplicate annotation feature flags and YAML CRUD work booleans;
- add `@AIIdentity`;
- convert non-functional field weights to tested projection priorities;
- remove field tags and express domain labels through logical field names or actual context values;
- keep three entity types and create/update/delete proof;
- change UI/backend wording from "weighted fields" to priority-ordered projection;
- prove the queue/outcome and projection hash in the state API without exposing internal data;
- retain the workspace-filtered Lucene regression;
- add an async scenario in addition to the current sync scenario.

### Account Resolver

- remove `@AICapable` from `Subscription` and remove its `@AIProcess` mutation annotations: the demo
  reads current account state through an application-owned read action and only exposes
  `account-resolution-policy` and `subscription-plan` to retrieval;
- record a regression proving no resolver flow depends on a subscription vector;
- do not add a fake searchable field merely to retain the old annotation;
- retain `SubscriptionPlan` semantic indexing and move recommendation behavior to its owning
  recommendation/orchestration policy;
- prove payment/refund response DTOs are not accidentally treated as index entities.

### Behavior Signals

- remove the ineffective private `@AIProcess`;
- move the persistence/indexing boundary to a separate public Spring bean or use
  `AIEntityIndexingGateway`;
- preserve light/full preset behavior through typed `indexing.enabled` policy;
- define an explicit annotated or custom projection for list/map insight fields rather than relying
  on a method name or disconnected annotation flags;
- prove an insight vector exists or deliberately remove indexing if behavior insight storage does not
  need retrieval.

### Chat Capabilities Demo

- migrate product, review, and policy methods to typed operations;
- preserve create/update/delete lifecycle tests;
- ensure delete returns or resolves identity without relying on method names.

### Direct Capability-Service Users

Migrate these call sites to `AIEntityIndexingGateway`:

- smart FAQ assistant;
- cloud Qdrant/OpenAI vector search;
- account resolver and simple subscription app initializers/debug endpoints;
- Privacy Shield support messages;
- `AIInfrastructureProfileService`;
- integration test fixtures that call `processEntityForAI(...)`.

For `AIInfrastructureProfile`, either declare a real approved projection/custom projector and prove a
retrieval use case, or remove its indexing declaration. The current `features` flags plus helper
method names do not make it indexable.

Debug endpoints must not call both embedding and indexing methods.

## Test-First Implementation Matrix

Every behavior change must arrive with a focused test. Do not merge a red test-only commit to main;
write the failing test and implementation together in each scoped change.

### Core Descriptor And Projection Tests

- discovers JPA and explicit non-JPA entities;
- rejects blank/duplicate entity type;
- rejects annotation/YAML mismatch;
- compiles a complete annotation-backed descriptor without an entity YAML entry;
- requires explicit indexing/projection policy for a YAML-only push entity;
- compiles scoped precedence and records the winning source;
- loads ordered application-level imports and rejects unauthorized duplicate overrides;
- honors `indexing.enabled` across annotation, migration, document, and push-data-sync ingress;
- splits semantic-search and RAG text;
- applies typed preprocessing and length bounds;
- orders fields by priority and drops lower-priority optional content first at the projection budget;
- proves priority does not claim or fabricate vector-score weighting;
- rejects an unknown/custom processor unless a bean is registered;
- rejects missing required fields;
- enforces context destinations;
- excludes non-annotated/private fields from every projection;
- fail-closes searchable and context PII sanitization;
- resolves inherited `@Id`, getter `@Id`, `@EmbeddedId`, `@AIIdentity`, and custom identity;
- rejects blank/ambiguous identity;
- creates stable descriptor hashes.

### Aspect And Target Tests

- typed operation wins regardless of method name;
- no operation defaults to create;
- direct, optional, collection, array, proxy, argument, wrapper, and delete-ID targets;
- entity-type mismatch fails visibly;
- null result with no resolver fails for enabled lifecycle work;
- private/final/non-proxyable methods fail startup;
- original domain method executes exactly once;
- domain exceptions propagate unchanged and enqueue nothing.

### Transaction Tests

Use real Spring transactions and a real queue repository:

| Scenario | Required proof |
| --- | --- |
| create commit | source row and queue row commit; vector appears after dispatch |
| create rollback | no source row, queue row, or vector |
| update rollback | old source row and old vector remain |
| delete rollback | source row and vector remain |
| enqueue failure | source transaction rolls back when sharing the transaction manager |
| non-transactional method | queue insertion is independently committed only after method success |
| sync provider failure | source and durable failed/retryable row exist; no fake success |
| async provider failure | retry count/backoff/dead-letter transition occurs |

### Execution Tests

- exactly one embedding-provider call per upsert;
- exactly one vector upsert per upsert;
- exactly one vector delete per delete;
- typed analysis policy creates independent `ANALYZE` work only after a successful configured upsert;
- analysis failure does not retry or roll back an already successful vector upsert;
- provider exception escapes execution service;
- worker marks failed rather than completed;
- retries eventually complete after transient recovery;
- permanent failure reaches dead letter;
- stale update is superseded;
- delete/recreate cannot apply stale delete last;
- queue payload contains only the approved projection and no excluded secret;
- old application class removal does not make a queued document unreadable.

### Cross-Module Tests

- migration preview/index/query/delete uses the same projection;
- migration reports projection failures;
- data-sync authorization remains fail-closed;
- data-sync normalized map and typed annotation entity produce equivalent index documents;
- relationship query honors LLM/API visibility;
- RAG receives only `RAG_CONTEXT` text and `LLM_CONTEXT` metadata;
- search API receives only `API_RESPONSE` metadata;
- Spring AI document metadata currently hidden with `includeInResponse = false` remains excluded after
  conversion to destinations;
- removing entity `enableRecommendations` does not remove or gate explicit recommendation APIs;
- Behavior light/full profiles disable/enable indexing through the typed policy;
- Account Resolver proves current account resolution without creating a subscription vector;
- vector admin access remains separately authorized.

### Provider And Live Proof

1. deterministic memory vector contract;
2. local Lucene lifecycle with exact metadata filtering;
3. Docker Qdrant lifecycle and restart persistence;
4. provider contract tests for Pinecone, Weaviate, Milvus, and Qdrant adapters where credentials or
   containers are available;
5. OpenAI embedding plus generation Live Data Sync flow;
6. create, update, delete, rollback, restart, and multi-workspace isolation;
7. no local/mock answer when a required live provider fails.

## Expected Verification Commands

Run unit and integration tests normally:

```bash
mvn -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-core,ai-fabric-indexing,ai-fabric-migration,ai-fabric-data-sync,ai-fabric-relationship-query \
  -am test
```

Build and install the complete reactor while compiling, but not prematurely executing, provider
integration suites:

```bash
mvn -f ai-infrastructure-module/pom.xml clean install -DskipITs
```

Prepare the repository-owned ONNX test assets, then run the complete integration tail:

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

Use `clean` when resuming an integration module after a failed reactor. That prevents stale
IDE-generated bytecode from being mistaken for a current Maven compilation.

Run affected real apps through their shared reactor:

```bash
mvn -f examples/real-apps/pom.xml \
  -pl ai-fabric-live-data-sync,ai-fabric-account-resolver,chat-capabilities-demo,behavior-churn-signals \
  -am test
```

Build packaged applications without skipping tests:

```bash
mvn -f examples/real-apps/pom.xml \
  -pl ai-fabric-live-data-sync,ai-fabric-account-resolver,chat-capabilities-demo,behavior-churn-signals \
  -am package
```

Verify that repository-owned Java consumers no longer use removed lifecycle members or payloads:

```bash
rg -n \
  'processType\s*=|generateEmbedding\s*=|indexForSearch\s*=|enableAnalysis\s*=|autoProcess\s*=|enableSearch\s*=|enableRecommendations\s*=|autoEmbedding\s*=|indexable\s*=|weight\s*=|processEntityForAI\(' \
  ai-infrastructure-module examples \
  --glob '*.java'
```

The command must return no lifecycle usages. Review similarly named, unrelated provider, service,
cache, or local algorithm variables explicitly rather than weakening the check. `IndexingQueueEntry`
is intentionally retained as the JPA persistence record for the new class-free
`AIIndexDocument` payload; the obsolete runtime entity serialization contract, not the table entity
name, is what must be absent.

Also scan target entity YAML for removed keys:

```bash
rg -n \
  '^\s*(features|indexable|auto-process|enable-search|enable-recommendations|auto-embedding|generate-embedding|index-for-search|enable-analysis|remove-from-search|cleanup-embeddings):' \
  ai-infrastructure-module examples \
  --glob '*.yml' --glob '*.yaml'
```

The target entity configuration must use typed `indexing` and `analysis` sections. Unrelated
application-level feature configuration is reviewed separately.

The final release gate also runs the existing keyed/manual provider matrix and records which providers
ran, passed, failed, or were unavailable. A skipped provider is not a pass.

## Implementation Workstreams

### Workstream 1: Characterize Current Defects

Add focused tests for:

- process type versus method-name mismatch;
- wrong wrapper result;
- private self-invocation;
- duplicate embedding calls;
- swallowed queue failures;
- full-entity queue leakage;
- create/update/delete rollback;
- ignored field destinations and required flags;
- declared field weights/tags that never change projection or retrieval;
- fail-open PII sanitization;
- inherited/non-standard identity.

These tests establish the exact defects before replacing the architecture.

### Workstream 2: Build Descriptor And Projection Core

1. Add typed annotation enums and `@AIIdentity`.
2. Add typed indexing/analysis policies and bind modular files through ordered Spring Boot Config Data imports.
3. Add presence-aware raw YAML binding.
4. Implement descriptor compilation and validation without requiring YAML for annotated entities.
5. Implement separate search/RAG/context/response projections.
6. Implement priority ordering and bounded truncation.
7. Implement fail-closed PII handling.
8. Implement shared identity resolution.
9. Replace `AICapableProcessor` and metadata registrar.

### Workstream 3: Replace Index Execution

1. Add `AIIndexDocument` and schema version.
2. Add one throwing upsert and one idempotent delete.
3. Separate optional analysis.
4. Make vector/provider failures propagate.
5. Add typed outcomes and bounded observability.

### Workstream 4: Replace AOP And Dispatch

1. Add typed operation and target resolver.
2. Replace the old aspect with `AIProcessAspect`.
3. insert durable projected queue work in the source transaction;
4. dispatch only after commit;
5. support sync/async/batch timing;
6. add per-entity ordering and stale-work handling;
7. remove rollback vector compensation.

### Workstream 5: Converge Other Ingress Paths

1. migrate migration/backfill to projected queue payloads;
2. migrate push data-sync normalization to the common index document;
3. migrate Spring AI document chunks where applicable;
4. preserve ingress-specific authorization and trust policies.

### Workstream 6: Migrate Framework And Real Apps

1. update all annotation usages;
2. replace direct broad capability-service calls;
3. remove unused Account Resolver subscription indexing while preserving plan/policy retrieval;
4. fix Behavior Signals private interception and preserve light/full indexing profiles;
5. update integration fixtures and real-app tests;
6. update Docker/package smoke flows.

### Workstream 7: Documentation And Release

Update:

- annotation Javadocs;
- `RAG_INDEXING_LIFECYCLE_GUIDE.md`;
- `MIGRATION_BACKFILL_GUIDE.md`;
- `DATA_SYNC_PUSH_API_GUIDE.md`;
- Live Data Sync README and About page;
- course Core 02 and Production migration/live-sync lessons;
- NotebookLM scripts that currently claim global precedence or weighted fields;
- internal clean-cutover checklist and `0.4.0` breaking-change release notes;
- coding-assistant guidance.

## Internal Greenfield Cutover

This table is a repository-wide source conversion map, not a backward-compatibility contract. Every
listed old form must be removed from framework-owned source, tests, examples, demos, and course
material before `0.4.0` is released.

| Remove from repository-owned code | Replace with |
| --- | --- |
| `@AICapable(entityType = "...", features = {...})` | Keep only entity identity, strategy, and migration binding; replace the generic list with typed module policies. |
| annotation `autoProcess`, `enableSearch`, `autoEmbedding`, and `indexable` | Explicit `@AIProcess` plus runtime `indexing.enabled`. |
| annotation `enableRecommendations` | Remove from indexing; configure recommendations through their owning capability when needed. |
| blank/default `entityType` | Required explicit nonblank entity type. |
| `@AIProcess(processType = "update")` | `@AIProcess(operation = AIProcessOperation.UPDATE)`. |
| method name determines operation | Method name is irrelevant. |
| `generateEmbedding` and `indexForSearch` | Removed; create/update perform one upsert. |
| `enableAnalysis` on lifecycle method | Use typed entity analysis policy or explicit entity analysis API. |
| direct entity return required implicitly | Direct return is default; wrappers/void use a declared target resolver. |
| field named `id` required implicitly | Mark identity with `@AIIdentity`, use JPA identity, or register a resolver. |
| private annotated method silently ignored | Move it to a public method on another Spring bean or use the programmatic gateway. |
| `AISearchable.weight` | `priority` for ordering/truncation only; true weighted retrieval remains a separately designed feature. |
| string preprocessing/data type | Use typed enums. |
| include booleans | Use typed projection destinations. |
| entity `tags` fields | Use explicit `@AIContext` metadata or a custom projector. |
| `processEntityForAI(...)` | `AIEntityIndexingGateway.upsert(...)`. |
| paired generate/index calls | One `upsert(...)`. |
| paired remove/cleanup calls | One `delete(...)`. |
| complete Java entity in durable queue | Versioned approved `AIIndexDocument`. |
| generic YAML `features`, old `indexable`, nested `entity-type`, and CRUD work booleans | Typed indexing/analysis policy plus operation from the annotation. |

Cut over repository-owned consumers in this order:

1. introduce the new descriptor, projection, identity, and indexing contracts;
2. migrate core and indexing modules;
3. migrate integration tests and test fixtures;
4. migrate every real app and demo;
5. migrate course support applications and code samples;
6. remove old APIs, configuration readers, and queue payload classes;
7. run the complete deterministic and packaged-runtime gates;
8. reset demo queue/vector state and rebuild it from source records;
9. deploy the framework and demo applications;
10. run live-provider proof before publishing `0.4.0`.

For deployed demos:

- retain authoritative relational source data where useful;
- stop old indexing workers before replacing their schema;
- drop old indexing queue rows and leases;
- recreate the new queue/outbox schema;
- clear generated vectors owned by the affected demo;
- run annotation migration/backfill to rebuild the vector projection;
- verify source/vector counts, descriptor readiness, and zero unexpected dead letters.

Do not write an old-payload deserializer or maintain both queue schemas. If non-disposable data is
discovered before implementation, stop and create a narrowly scoped one-time export/reindex utility;
do not turn it into permanent runtime compatibility.

## Release Sequencing

### No Annotation Compatibility Release

Do not spend an intermediate `0.3.x` release on:

- deprecation aliases;
- warning-only versions of contract failures;
- dual string/enum operation support;
- old/new queue payload readers;
- duplicate YAML compatibility;
- partial annotation changes on top of the old execution path.

Unrelated, narrowly compatible fixes may still be released independently, but annotation lifecycle
work moves directly to the complete `0.4.0` design.

### `0.4.0`

Ship the complete corrected contract together:

- annotation API;
- descriptor/projection services;
- transaction-aware queue;
- versioned queue payload;
- repository-wide module and real-app conversion;
- reset/reindex tooling for deployed demos;
- tests, clean-cutover documentation, and explicit breaking-change release notes.

Do not release half of the new annotation API on top of the old execution path.

## Definition Of Done

This plan is complete only when all statements below are true:

1. `@AICapable.entityType` unambiguously maps a typed entity to its vector-space identity.
2. `@AIProcess.operation` is the only lifecycle operation source.
3. method names never change lifecycle semantics.
4. every retained annotation attribute has a behavioral contract test;
5. no retained annotation property is metadata-only unless its documentation explicitly says so;
6. one create/update causes exactly one embedding call and one upsert;
7. one delete causes exactly one idempotent delete;
8. source rollback causes no vector mutation and no committed queue row;
9. committed async work is durable when the source and queue share a transaction manager;
10. provider/vector failures retry and dead-letter visibly;
11. queue payloads contain only approved projected data;
12. requested PII sanitization never falls back to raw content;
13. wrapper, collection, optional, void-delete, and custom-ID targets are supported deliberately;
14. private/self-invoked annotations fail validation rather than silently doing nothing;
15. stale async updates/deletes cannot overwrite newer entity state;
16. migration, annotation lifecycle, and push data sync converge on one index-document contract;
17. Account Resolver and Behavior Signals no longer contain ineffective/wrong-target annotation usage;
18. Live Data Sync passes deterministic, packaged, Docker, and live OpenAI verification;
19. all affected real apps compile and their smoke tests pass;
20. documentation and course material describe the exact runtime, not aspirational behavior;
21. repository scans find no removed annotation members, lifecycle APIs, old queue payload classes, or
    duplicate YAML compatibility fields;
22. no runtime component reads or adapts the `0.3.x` annotation or queue contract;
23. every removed property with useful intent has its documented typed replacement, and tests prove
    the replacement across all supported ingress paths;
24. no property named `weight` claims to influence similarity unless a separately tested weighted
    retrieval design is implemented.

## Recommended First Implementation Slice

Begin with the smallest slice that removes the most dangerous false confidence:

1. add typed `AIProcessOperation`;
2. make the aspect use it and remove method-name inference;
3. add target compatibility validation;
4. make capability execution throw to queue retry handling;
5. collapse generate/index into one upsert;
6. add tests for Account Resolver method names, wrapper results, and queue failure.

Do not stop there for the `0.4.0` release. The transaction-aware projected outbox and fail-closed field
projection are required before declaring annotation-driven lifecycle synchronization production-ready.
