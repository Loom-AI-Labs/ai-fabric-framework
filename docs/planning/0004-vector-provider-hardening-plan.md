# ADR 0004 - Vector provider hardening plan for lifecycle and admin parity

- **Status:** Accepted, implementation in progress
- **Date:** 2026-06-18
- **Decision owner:** AI Fabric framework
- **Context version:** AI Fabric `0.2.1`, Java `21`, Spring Boot `4.1.0`
- **Depends on:** ADR 0001 commodity layer strategy, ADR 0003 Spring AI capability adoption plan

## Context

AI Fabric's vector providers are not thin Spring AI wrappers. The production providers use native
provider clients and expose a broader lifecycle/admin contract through
`VectorDatabaseService`.

The current provider layer already has meaningful production behavior:

- native storage/search/fetch/delete/statistics calls;
- deterministic entity-aware vector ids;
- entity-type scoped namespaces, collections, or classes;
- explicit caller-provided embeddings;
- exact fetch/update/delete by vector id or entity id;
- scan/list/count/clear operations where the provider can support them;
- provider-specific diagnostics and consistency handling.

The remaining caveats are not evidence that the vector layer is fake. They are provider-specific
operational limits and normalization gaps that should be made explicit, tested, and improved before
release.

## Decision

Keep AI Fabric's native vector providers as the release path for full vector lifecycle/admin support.

Do not replace the vector layer with Spring AI `VectorStore` for the full lifecycle/admin contract.
Spring AI can remain a future optional simple-RAG adapter, but it should not be positioned as the
implementation for AI Fabric's current vector lifecycle/admin API.

Harden the existing native vector providers in four areas:

1. Capability declarations and documentation.
2. Provider-specific consistency and admin behavior.
3. Metadata filtering, scan, and count parity.
4. Cross-provider contract tests.

No sidecar storage is introduced by this plan.

## Implementation status

Implemented in the current working branch:

- split vector capability flags for search metadata filtering, scan metadata filtering, exact fetch,
  clear by entity type, and efficient entity-type count;
- the efficient entity-type count SPI default is conservative (`false`), so providers must explicitly
  opt in when they have a native count/statistics path or a safe local count;
- `adminDiagnostics()` now exposes stable cross-provider capability keys in addition to
  provider-specific scope details, so readiness and release checks can use the same key names across
  memory, Lucene, Pinecone, Qdrant, Weaviate, and Milvus;
- `VectorProviderCapabilities` now provides a typed capability descriptor beside the boolean SPI and
  is exposed under `adminDiagnostics().capabilities`, including provider name, provider class, native
  client, lifecycle/admin flags, filter modes, metadata filter subset, count mode, consistency model,
  durable storage, production-profile safety, and computed lifecycle/admin compatibility;
- vector provider diagnostics now expose `clearMode`, standardized `countFallbacks`, and
  `countFallbackReasons`; readiness warns when a provider has used a count compatibility fallback, so
  count paths cannot silently regress from native statistics/count APIs into hydration-heavy scan
  counts;
- metadata-capable providers now expose `searchFilterMode` and `scanFilterMode` diagnostics so
  release checks can tell native provider predicates from local indexed, in-memory, and adapter-side
  list/fetch predicates;
- the governance vector decorator preserves the delegate provider diagnostics and adds governance
  markers instead of hiding provider scope evidence behind the decorator;
- the legacy `VectorDatabase` adapter now exposes provider diagnostics through `getInfo()` while
  preserving existing statistics fields, so older integrations can consume the same readiness
  evidence;
- `VectorManagementService.getProviderDiagnostics()` now exposes lightweight provider diagnostics for
  admin/readiness surfaces without running scans, counts, or statistics calls, and the web module's
  `/api/ai/advanced-rag/health` response includes those diagnostics under `vectorDatabase` when vector
  management is present;
- vector provider diagnostics now include a computed `readiness` verdict with `READY`, `WARN`, or
  `NOT_READY` status plus `operational`, `productionReady`, `reasons`, and `warnings`, so release
  checks can consume a stable operator-facing result instead of re-implementing provider-specific
  diagnostic rules. Compatibility fallback counters and reason maps are evaluated generically, so a
  wrapped or future provider cannot report clean readiness while exposing fallback evidence;
- AI Fabric now registers a standard Spring Boot vector provider health indicator when actuator health
  classes and `VectorManagementService` are available. The indicator reports `UP` for operational
  `READY`/`WARN` vector providers and `DOWN` for `NOT_READY`, while preserving the detailed readiness
  verdict in health details;
- `.github/scripts/verify-vector-readiness-health.sh` now provides a deployment/release smoke gate for
  `/actuator/health/vectorProvider`, `/api/ai/advanced-rag/health`, saved health JSON, or raw readiness
  diagnostics. It fails on `NOT_READY` and, by default, on `WARN`/`productionReady=false`; operators
  must set `VECTOR_READINESS_ALLOW_WARN=true` to allow warned but operational state outside release
  gates. It separately fails on provider compatibility fallback evidence such as
  `metadataFilterFallbacks`, `aggregateCountFallbacks`, `countFallbacks`, and fallback-reason maps
  unless `VECTOR_READINESS_ALLOW_FALLBACKS=true` is set for an explicitly accepted release exception.
  Offline script tests cover ready, warned, not-ready, missing-verdict, and fallback-evidence
  responses;
- the automatic `Framework Build` workflow now runs the offline verifier tests, and the manual
  `Framework Provider Matrix Suite` workflow can run deployed vector readiness smoke verification when
  `runtime_base_url` is supplied;
- `.github/scripts/validate-framework-release-guards.sh` now consolidates provider registry,
  workflow-test policy, release-doc policy, production-stub marker, and vector-readiness verifier
  checks into one lightweight guard used by `Framework Build` before Maven starts;
- `VectorManagementService` batch store/update/remove paths now treat null or empty batch requests as
  no-op calls instead of throwing before reaching provider-level validation;
- the built-in `remove_vector` action now rejects blank entity references at the action boundary with
  a structured failure result instead of passing invalid identifiers to provider-specific delete paths;
- governance auto-configuration now requires scan metadata filtering when it selects vector-backed
  catalog storage;
- provider diagnostics expose the important capability and strictness flags;
- Qdrant gRPC and REST providers support a strict `fail-on-missing-payload-index` mode;
- Qdrant gRPC and REST admin diagnostics now expose the required payload-index fields plus lazy
  readiness evidence: verified indexes, indexes seen missing during first-use checks, create attempts,
  and last create failures;
- Qdrant gRPC and REST fallback results now expose `metadataFilterFallback=true` in each affected
  search row and its metadata map when the provider retries without server-side metadata filtering;
- Qdrant gRPC and REST compatibility fallback now re-applies the original portable metadata
  predicate client-side before returning rows, so missing-index retry cannot broaden a
  metadata-filtered search;
- Qdrant gRPC and REST admin diagnostics now expose `metadataFilterFallbacks`, a per-collection
  fallback counter, so operators can see compatibility fallback events after the response has been
  consumed;
- Qdrant gRPC and REST now short-circuit unsupported metadata filter shapes before provider calls, so
  the missing-index fallback cannot widen rejected filters into unfiltered search or scan results;
- Qdrant gRPC and REST mocked tests now prove portable empty-string exact filters are preserved in
  native filter request payloads instead of being treated as absent filters;
- Qdrant gRPC raw vector-id lookup/delete and collection-wide search/clear now treat missing
  candidate collections as idempotent absence but propagate collection-list, provider, and network
  failures as `AIServiceException`, with mocked regression coverage so backend outages are not
  reported as "not found" or empty results;
- Weaviate entity-type counts use native GraphQL aggregate count when available, and
  `adminDiagnostics()` exposes `aggregateCountFallbacks` plus `aggregateCountFallbackReasons` when a
  known aggregate-unsupported deployment has to use the safe paged-scan compatibility fallback;
- Weaviate update/delete lifecycle paths now preserve not-found idempotence but propagate
  existence-check, delete-before-upsert, create, and delete failures as `AIServiceException`, with
  mocked regression coverage so backend failures cannot be reported as failed/no-op lifecycle calls;
- Lucene indexes portable scalar metadata fields and additionally supports Lucene-local decimal
  equality filters for search and scan, documented as a provider-specific extension rather than a
  portable cross-provider guarantee;
- Pinecone search metadata filters now use the shared portable scalar subset and fail closed for
  null, array, nested, and provider-operator shapes;
- Pinecone transient retry handling now covers similarity query, list/fetch scan, exact fetch, stats,
  upsert, delete, and deterministic clear paths, so rate-limit/availability/deadline failures are
  handled consistently across lifecycle/admin APIs;
- Pinecone provider-level batch store/update/remove paths now ignore null batch records and blank
  vector ids consistently with the other vector providers, so direct provider calls do not fail before
  they reach meaningful provider validation;
- Pinecone direct store/update entry points now have mocked regression coverage proving missing
  embeddings fail with an AI Fabric exception before any native upsert is attempted;
- scan projection is now centralized in core so native providers suppress only requested payload
  fields while preserving lifecycle/admin fields such as AI analysis, vector metadata, active status,
  version, timestamps, and similarity score;
- direct provider lifecycle input validation is now centralized for store/update identity and embedding
  checks, while invalid identity read/remove/admin calls return empty, false, or zero instead of
  reaching provider internals with blank identifiers;
- a shared `VectorMetadataFilterSupport` validator now defines the portable exact-match metadata
  subset and makes unsupported/null/array/nested filter shapes fail closed instead of being dropped;
- Weaviate and Milvus mocked provider tests now capture native `WhereFilter` and expression payloads
  to prove unsupported generic metadata shapes become impossible provider filters instead of widened
  native searches/scans;
- Weaviate mocked provider tests now also prove portable scalar metadata filters are encoded as
  text-backed exact equality, matching the adapter's text metadata property writes. Empty-string
  metadata values are stored in Weaviate's indexed metadata property with an internal sentinel while
  the raw metadata JSON remains unchanged, so `status=""` is exact for search and scan without
  leaking the sentinel through AI Fabric reads;
- Weaviate search and scan now re-apply the shared portable equality predicate after parsing native
  GraphQL rows. This preserves provider-native filtering while preventing loose provider matches such
  as integral filter `rank=7` matching decimal metadata `rank=7.9`;
- Milvus metadata filter expressions now use the same stored JSON-string representation as writes and
  escape `LIKE` wildcard characters, so boolean/integral filters match stored values and exact string
  filters cannot broaden through `%` or `_` wildcards;
- the shared metadata JSON serializer now escapes keys and values using JSON-safe string escaping and
  gives non-ordered map inputs a stable key order while preserving the existing "metadata values are
  stored as strings" contract used by provider raw metadata fields;
- memory vector startup is blocked under production profiles unless explicitly acknowledged;
- focused unit/mocked coverage was added for the new SPI defaults, governance selection, Qdrant
  strict failure, Weaviate aggregate count, Lucene metadata filtering, Pinecone capability diagnostics,
  Pinecone deterministic clear, Pinecone sparse fallback caching, Pinecone transient retry behavior,
  Weaviate tenant-scoped aggregate count, Weaviate schema/tenant/property/upsert behavior, Milvus
  collection/index/flush/drop behavior, Milvus dimension-mismatch rejection, and memory production
  guard.
- a shared offline provider contract suite was added in
  `ai-infrastructure-module/integration-Testing/vector-contract-tests`, currently covering memory and
  Lucene lifecycle behavior against the same `VectorDatabaseService` contract.
- the shared provider contract now asserts `adminDiagnostics().capabilities` so every provider must
  expose typed native-client, filter-mode, metadata-subset, count-mode, clear-mode, consistency,
  durability, and lifecycle/admin compatibility evidence in addition to the legacy flat keys.
- the shared provider contract now asserts scoped-provider isolation by storing the same
  `entityType` and `entityId` under two configured provider scopes and proving exact fetch, entity
  fetch, search, scan, count, remove, and clear operations do not cross scopes. The assertion runs in
  the local memory/Lucene contract and is inherited by the Docker-backed Qdrant REST, Qdrant gRPC,
  Weaviate, and Milvus contract suite.
- opt-in Testcontainers contract tests were added for Qdrant REST, Qdrant gRPC, Weaviate, and Milvus.
  They compile in the default test path and run through the `container-contract-tests` Maven profile
  when Docker is available. The manual `Framework Provider Matrix Suite` GitHub Actions workflow now
  includes this suite as a dedicated Docker-backed release job.
- an opt-in Pinecone live provider test was added in the Pinecone module. It exercises real API
  store/fetch/search/update/clear behavior with metadata filtering, eventual-consistency polling, and
  sparse-index roundtrip verification when the configured live index is sparse.
- the shared provider contract now asserts that portable empty-string metadata equality filters are
  preserved for both search and scan. The assertion runs locally for memory and Lucene and is inherited
  by the Docker-backed Qdrant REST, Qdrant gRPC, Weaviate, and Milvus contract suite.
- the shared provider contract now asserts scan projection semantics: suppressed content, embedding,
  and metadata fields must be returned as `null`, not as empty placeholders. Qdrant REST and Weaviate
  have focused mocked regression tests for this behavior, and the same contract is inherited by
  Qdrant gRPC, Milvus, memory, Lucene, and Pinecone live verification.
- local Docker execution passed on 2026-06-20 with Qdrant REST, Qdrant gRPC, Weaviate, and Milvus:
  `mvn -f ai-infrastructure-module/pom.xml -pl integration-Testing/vector-contract-tests -am -Pcontainer-contract-tests verify`.
- Qdrant, Weaviate, Milvus, and Pinecone update paths now check that the target vector exists before
  calling provider-native upsert APIs, so `updateVector` no longer silently creates missing records.
- durable native providers now share `VectorRecordLifecycleMetadata` for direct provider store/update
  calls. Qdrant REST/gRPC, Weaviate, Milvus, and Pinecone persist `_indexedCreatedAt` and
  `_indexedUpdatedAt` into provider metadata, preserve the original created timestamp on update, and
  hydrate `VectorRecord.createdAt` / `updatedAt` from persisted provider payloads. This closes the
  gap where direct provider writes bypassed `VectorManagementService` timestamp enrichment and then
  returned null lifecycle timestamps in contract tests.
- Qdrant REST/gRPC and Weaviate `removeVectorById` now verify scoped record existence before issuing
  provider-native deletes and return `false` when a record is absent in the configured scope. Weaviate
  no longer falls through from known scoped classes to an unscoped delete path. This keeps idempotent
  not-found semantics without allowing a delete request from one modeled tenant/scope to report
  success against another.
- Milvus exact entity-type count now uses a native visible-row query over vector ids instead of
  trusting collection `row_count`, because Milvus statistics can include stale deleted/upserted rows
  until provider compaction catches up. The provider now honestly reports
  `supportsEfficientEntityTypeCount=false` and `countMode=milvus-visible-row-scan`; clear operations
  use the same visible-row count before dropping collections, with provider statistics retained only
  as best-effort cleanup fallback evidence.
- the contract suite found and fixed a Lucene lifecycle bug where `IndexWriter.deleteDocuments(...)`
  sequence numbers were being treated as delete counts for update/remove paths.
- low-cardinality Micrometer metrics now make provider fallback and retry events visible without
  high-cardinality scope tags:
  `ai.fabric.vector.provider.fallbacks{provider,operation,reason}` records Qdrant missing
  payload-index search fallback and Weaviate aggregate-count fallback; `ai.fabric.vector.provider.retries{provider,operation,reason}` records
  Pinecone transient retry events across the shared retry wrapper.

Still worth doing after this branch:

- keep the manual CI Testcontainers contract job green for Qdrant REST/gRPC, Weaviate, and Milvus
  capability parity. Local Docker execution currently passes with
  `mvn -f ai-infrastructure-module/pom.xml -pl integration-Testing/vector-contract-tests -am -Pcontainer-contract-tests verify`;
- execute the optional Pinecone live provider suite with real Pinecone credentials/index settings.

## Current fixes and enhancements

The vector provider caveats are not a reason to replace AI Fabric's native vector layer. They are a
clear production-hardening backlog around proof, observability, and operational guardrails.

### Release-blocking proof

1. Run Docker-backed provider contracts for Qdrant REST, Qdrant gRPC, Weaviate, and Milvus in an
   environment with Docker available.
   - Evidence path: `ai-infrastructure-module/integration-Testing/vector-contract-tests`.
   - Success signal: every native provider passes the same lifecycle/admin contract already used for
     memory and Lucene.
   - Why it matters: mocked tests prove request construction and failure handling; container contracts
     prove real provider semantics.

2. Run the Pinecone live suite with real credentials and a real index.
   - Evidence path:
     `ai-infrastructure-module/victor-databases/ai-fabric-vector-pinecone/src/test/java/ai/fabric/vector/pinecone/PineconeVectorDatabaseServiceLiveIT.java`.
   - Success signal: store, fetch, metadata-filtered search, update, deterministic clear, and sparse
     index behavior pass against the hosted service.
   - Why it matters: Pinecone consistency and sparse-index behavior cannot be fully proven with mocks.

3. Publish the provider matrix as release evidence.
   - Evidence path: this ADR plus the contract-test reports.
   - Success signal: release notes distinguish native search, scan, count, clear, exact fetch,
     metadata-filter modes, and production readiness per provider.
   - Why it matters: users need honest capability names, not one broad "metadata supported" claim.

### Production hardening

1. Use provider diagnostics from the normal admin/readiness surface during release checks.
   - Source: `VectorManagementService.getProviderDiagnostics()`, backed by
     `VectorDatabaseService.adminDiagnostics()`.
   - Runtime endpoint: `/api/ai/advanced-rag/health`, under the `vectorDatabase` object when
     `ai-fabric-web` and vector management are enabled.
   - Actuator health component: `/actuator/health/vectorProvider` when Spring Boot health details are
     enabled and `management.health.ai-fabric.vector.enabled` is not disabled.
   - Readiness verdict: `vectorDatabase.readiness.status`, `operational`, `productionReady`,
     `reasons`, and `warnings`.
   - Include fallback counters such as Qdrant `metadataFilterFallbacks` and Weaviate
     `aggregateCountFallbacks`.
   - Keep provider-specific scope evidence such as namespace, collection, tenant, class, index, and
     filter mode.

2. Add release checks that fail when public docs overclaim vector behavior.
   - Guard against saying Spring AI `VectorStore` replaces the full AI Fabric vector lifecycle/admin
     contract.
   - Guard against claiming arbitrary nested JSON metadata filtering across all providers.
   - Guard against documenting the memory provider as production durable.

3. Add a production profile recommendation for Qdrant strict payload-index mode.
   - Property: `ai.vector-db.operations.fail-on-missing-payload-index`.
   - Release recommendation: keep compatibility fallback available, but prefer strict mode in
     production deployments that depend on metadata filtering for isolation or authorization.

4. Add lightweight operational runbooks per native provider.
   - Pinecone: eventual consistency, deterministic clear, namespace/index configuration, sparse index
     detection.
   - Qdrant: payload indexes, REST versus gRPC mode, strict missing-index behavior.
   - Weaviate: schema/class/tenant configuration and aggregate-count fallback diagnostics.
   - Milvus: collection/index creation, flush behavior, visible-row exact count, and why native
     collection statistics are not used for lifecycle/admin exact counts.
   - Lucene: local storage path, metadata index compatibility, small/local deployment positioning.
   - Memory: dev/test-only behavior and production guard override.

### Quality enhancements

1. Keep extending the typed capability descriptor as new provider modes are added.
   - The current descriptor is exposed as `adminDiagnostics().capabilities`.
   - Future provider-specific modes should be added there first and mirrored to flat diagnostics only
     when older integrations need compatibility.

2. Keep scoped-provider isolation in the shared contract as new provider scope modes are added.
   - Current contract stores the same `entityType` and `entityId` under separate configured scopes.
   - It proves reads, searches, scans, counts, removals, and clears do not cross the modeled provider
     scope.

3. Keep count and clear path evidence visible in release checks.
   - `entityTypeCountMode` and `entityTypeClearMode` identify the intended native/admin path.
   - `countFallbacks` and `countFallbackReasons` expose compatibility count paths when native
     statistics/count APIs cannot be used.
   - Release readiness warns on positive count fallback counters.

4. Keep metrics for provider fallback and retry events wired into release observability.
   - Qdrant metadata-filter fallback count. Done through
     `ai.fabric.vector.provider.fallbacks{provider=qdrant,operation=search,reason=missing_payload_index}`.
   - Weaviate aggregate-count fallback count. Done through
     `ai.fabric.vector.provider.fallbacks{provider=weaviate,operation=count,reason=aggregate_unsupported}`.
   - Milvus exact count is intentionally visible-row scan based; do not add a stats-fallback count
     metric for the public exact-count path unless a future provider API can prove active-row
     statistics after deletes/upserts.
   - Pinecone transient retry count. Done through
     `ai.fabric.vector.provider.retries{provider=pinecone,operation=<operation>,reason=<retry reason>}`.
   - Deterministic-clear polling remains covered by diagnostics, configuration, logs, and provider
     tests; add a separate timing metric later only if production evidence shows clear waits need
     alerting.

5. Keep any future Spring AI vector adapter explicitly scoped to simple RAG.
   - It may use `VectorStore` for basic store/search.
   - It must not claim support for AI Fabric's full lifecycle/admin API unless exact fetch, scan,
     count, clear, diagnostics, projection, metadata fail-closed behavior, and entity scoping are
     implemented and contract-tested without sidecar storage.

## Code evidence

| Area | Evidence |
| --- | --- |
| Core lifecycle/admin API | `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/rag/VectorDatabaseService.java` |
| Lifecycle orchestration | `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/service/VectorManagementService.java` |
| Governance/catalog dependency on scan/filter support | `ai-infrastructure-module/ai-fabric-governance/src/main/java/ai/fabric/governance/catalog/vector/VectorIndexCatalog.java` |
| Durable provider lifecycle metadata | `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/util/VectorRecordLifecycleMetadata.java` |
| Portable metadata filter validation | `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/util/VectorMetadataFilterSupport.java` |
| Raw metadata JSON serialization | `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/util/MetadataJsonSerializer.java` |
| Pinecone native provider | `ai-infrastructure-module/victor-databases/ai-fabric-vector-pinecone/src/main/java/ai/fabric/vector/pinecone/PineconeVectorDatabaseService.java` |
| Qdrant native provider | `ai-infrastructure-module/victor-databases/ai-fabric-vector-qdrant/src/main/java/ai/fabric/vector/qdrant/QdrantVectorDatabaseService.java` |
| Qdrant REST fallback | `ai-infrastructure-module/victor-databases/ai-fabric-vector-qdrant/src/main/java/ai/fabric/vector/qdrant/QdrantRestVectorDatabaseService.java` |
| Weaviate native provider | `ai-infrastructure-module/victor-databases/ai-fabric-vector-weaviate/src/main/java/ai/fabric/vector/weaviate/WeaviateVectorDatabaseService.java` |
| Milvus native provider | `ai-infrastructure-module/victor-databases/ai-fabric-vector-milvus/src/main/java/ai/fabric/vector/milvus/MilvusVectorDatabaseService.java` |
| Lucene local provider | `ai-infrastructure-module/victor-databases/ai-fabric-vector-lucene/src/main/java/ai/fabric/vector/lucene/LuceneVectorDatabaseService.java` |
| In-memory test provider | `ai-infrastructure-module/victor-databases/ai-fabric-vector-memory/src/main/java/ai/fabric/vector/memory/InMemoryVectorDatabaseService.java` |

## Caveat classification

| Caveat | Classification | Release risk | Fix direction |
| --- | --- | --- | --- |
| Pinecone clear/delete consistency needs polling | Provider reality, already partially handled | Medium | Keep deterministic clear path, add stronger tests and metrics. |
| Pinecone metadata filtering is used in search but provider declares `supportsMetadataFiltering=false` | Capability-model mismatch | Medium | Split capability flags between search filtering and scan/admin filtering. |
| Weaviate count uses list-and-size | Real efficiency weakness | High for large classes | Replaced with native aggregate/count query where available. |
| Milvus count cannot trust native `row_count` after deletes/upserts | Provider statistics semantics | Medium for large collections | Exact count now uses native visible-row id scan and advertises non-efficient count honestly. |
| Qdrant can fall back if payload index is missing | Schema/index drift risk | Medium | Added strict fail-closed mode and diagnostics. |
| Lucene metadata is stored JSON and not indexed for provider-side filtering | Real feature gap | Medium | Added indexed scalar metadata fields for portable filters. |
| Metadata serialization differs by provider | Normal adapter work | Medium | Shared portable filter validator added; shared raw metadata serializer now produces valid stable JSON; unsupported shapes fail closed and remain stored as raw metadata. |
| Entity scoping via namespace/collection/class | Design choice | Low | Document as AI Fabric isolation model. |
| In-memory provider is not durable | Expected dev/test limitation | Low if documented | Mark as dev/test and guard production usage. |

## Detailed fix plan

### 1. Capability model cleanup

**Problem:**

`supportsMetadataFiltering()` is too broad. A provider may support metadata-filtered similarity
search but not support efficient metadata-filtered scan/list/admin behavior.

Example: Pinecone search builds a metadata filter for `queryByVector`, but the provider currently
returns `false` for `supportsMetadataFiltering()`. That may be correct for catalog/scan behavior,
but it hides search-filter support.

**Plan:**

- Keep `supportsVectorScan()`.
- Replace or supplement `supportsMetadataFiltering()` with narrower capability flags:
  - `supportsSearchMetadataFiltering()`
  - `supportsScanMetadataFiltering()`
  - `supportsEfficientEntityTypeCount()`
  - `supportsExactFetchById()`
  - `supportsClearByEntityType()`
- Preserve the old method temporarily as a compatibility alias.
- Update governance/catalog code to require the exact capability it needs.
- Add a provider capability matrix to release documentation.

**Acceptance criteria:**

- Capability flags describe actual behavior, not aspirational behavior.
- Governance vector catalog only enables paths that have scan/filter support.
- Search metadata filtering can be advertised independently from scan metadata filtering.
- Existing provider auto-configuration remains backward compatible.

### 2. Pinecone consistency and metadata semantics

**Current behavior:**

Pinecone uses the native client for `fetch`, `queryByVector`, `upsert`, `list`, `deleteByIds`,
`deleteAll`, and `describeIndexStats`. It already includes retry/backoff and namespace-clear
polling.

**Known caveats:**

- `deleteAll(namespace)` is asynchronous.
- Stats can lag after delete.
- Metadata-filtered search exists, but metadata-filtered scan/list is not equivalent.
- Sparse-index fallback is provider-specific and should be covered by tests.

**Plan:**

- Keep deterministic clear behavior:
  - use list plus delete-by-ids when `awaitClearConsistency` is enabled;
  - poll read visibility after clear;
  - continue treating missing namespaces as idempotent success.
- Covered mocked tests for:
  - namespace clear uses list/delete path when deterministic clear is enabled;
  - clear tolerates namespace-not-found;
  - retry handles rate limit / unavailable / deadline failures across search, list/fetch scan, stats,
    upsert, and delete lifecycle calls;
  - sparse-index fallback is triggered once and cached;
  - metadata-filtered query passes provider filter struct correctly for the portable scalar subset;
  - unsupported null, array, nested, and provider-operator filters produce an impossible/no-match
    Pinecone filter instead of being interpreted as provider-specific search semantics.
- Covered opt-in live tests for:
  - exact fetch polling after write;
  - provider-side metadata-filtered search for portable scalar filters;
  - update visibility;
  - deterministic clear visibility;
  - sparse embedding roundtrip when the configured live index is sparse.
- Split Pinecone capabilities:
  - search metadata filtering: true for portable scalar provider-filter mapping;
  - scan metadata filtering: true for portable scalar client-side list/fetch filtering;
  - efficient entity count: true via namespace stats.
- Add diagnostics fields:
  - `awaitClearConsistency`
  - `awaitClearTimeoutMs`
  - `sparseIndexDetected`
  - `metadataFilteredSearch`
  - `metadataFilteredScan`

**Acceptance criteria:**

- Admin clear operations are deterministic enough for tests and CI.
- Search filtering is not hidden behind a false broad metadata flag.
- Scan/admin filtering remains honestly declared.

### 3. Weaviate efficient count

**Prior behavior:**

Weaviate supports native schema, class, tenant, object, and GraphQL vector search operations. The
older `getVectorCountByEntityType` implementation derived count from `getVectorsByEntityType`. That
was correct for small data but inefficient for large classes.

**Plan:**

- Replace list-and-size counting with a native aggregate count query where available.
- Apply tenant context when native multi-tenancy is enabled.
- Apply class existence checks before count.
- Preserve exact paged-scan counting as a fallback only if aggregate count fails with a known
  unsupported error.
- Add diagnostics showing whether count is native aggregate or fallback:
  - `countMode=native-aggregate-with-safe-fallback`;
  - `aggregateCountFallbacks`;
  - `aggregateCountFallbackReasons`.
- Add tests for:
  - count uses aggregate path;
  - tenant is applied;
  - missing class returns zero;
  - aggregate failure falls back only for known safe failure modes and records diagnostics.

**Acceptance criteria:**

- `supportsEfficientEntityTypeCount()` can be set to true when native aggregate count is implemented.
- Count no longer fetches every object in the normal path.
- Compatibility fallback is visible in admin diagnostics when an older/limited Weaviate deployment
  cannot execute aggregate count, and the fallback counts all pages instead of relying on a capped
  one-page object listing.
- Large Weaviate classes do not require object hydration just to report counts.

### 4. Qdrant payload-index hardening

**Current behavior:**

Qdrant uses the official Java client for gRPC deployments and a REST implementation when gRPC is not
preferred. It creates collections, upserts points, retrieves points, searches, scrolls, counts, and
creates payload indexes for required fields.

**Known caveat:**

If a required payload index is missing, filtered search can fail and then retry without server-side
filtering. This protects availability, but it can hide an index-management problem.

**Plan:**

- Keep automatic payload-index creation on collection creation/access.
- Record lazy payload-index readiness diagnostics during collection first-use checks:
  - `requiredPayloadIndexFields`;
  - `verifiedPayloadIndexes`;
  - `payloadIndexesSeenMissing`;
  - `payloadIndexCreateAttempts`;
  - `payloadIndexCreateFailures`.
- Add a startup or first-use validation path that logs missing indexes as warnings with collection
  name and field name.
- Add a configuration switch:
  - `ai.vector-db.operations.fail-on-missing-payload-index`
  - default `false` for compatibility;
  - recommended `true` for production.
- Keep fallback search for compatibility, but mark the result metadata with
  `metadataFilterFallback=true` when used and increment the provider diagnostic
  `metadataFilterFallbacks` counter for the affected collection.
- Re-apply the original portable metadata predicate client-side before returning fallback rows so a
  missing payload index never broadens a metadata-filtered result set.
- Do not use fallback for unsupported metadata filter shapes. These are AI Fabric contract rejections,
  not Qdrant index-management failures, so both gRPC and REST return empty results before provider
  search/scroll calls.
- Covered tests for:
  - required payload index is created;
  - missing-index error retries only when fallback is allowed;
  - fail-closed mode throws a clear AI Fabric exception;
  - REST and gRPC fallback paths filter out nonmatching rows, then mark returned rows and metadata
    with `metadataFilterFallback=true`.
  - unsupported metadata filters short-circuit before provider/network calls on both transports.
  - successful and failed payload-index creation attempts are visible through `adminDiagnostics()`.
  - fallback use is visible through `adminDiagnostics().metadataFilterFallbacks`.

**Acceptance criteria:**

- Missing Qdrant payload indexes are visible in diagnostics.
- Production users can fail closed instead of silently weakening filtering.
- REST and gRPC behavior stay aligned.

### 5. Lucene metadata filtering

**Prior behavior:**

Lucene uses native Lucene k-NN APIs for local vector search. Entity id and entity type are indexed.
Metadata was stored as JSON, but provider-side metadata filtering was not fully indexed.

**Plan:**

- Define a filterable metadata subset:
  - string, boolean, integer, long;
  - decimal equality can be supported by provider-specific tests where the native backend can express it safely;
  - flat keys only for the first implementation;
  - arrays and nested objects remain stored but not indexed.
- On write, index scalar metadata under a stable field prefix:
  - `meta_s_<key>` for strings;
  - `meta_b_<key>` for booleans;
  - `meta_l_<key>` for integral numbers;
  - `meta_d_<key>` for decimal numbers.
- Build Lucene filter queries from `AISearchRequest.metadata` and `VectorScanRequest.metadataEquals`.
- Keep raw JSON stored for round-trip compatibility.
- Add tests for:
  - metadata scalar fields are indexed;
  - search combines `KnnVectorQuery` with metadata filters;
  - scan applies indexed metadata filters;
  - unsupported metadata shapes fail closed instead of widening results;
  - old stored records without indexed metadata still read correctly.

**Acceptance criteria:**

- Lucene can truthfully advertise scan/search metadata filtering for supported scalar metadata.
- Existing records remain readable.
- Unsupported metadata shapes are documented instead of silently claimed as supported.
- Lucene-local decimal equality is covered by provider-specific tests and remains outside the
  portable cross-provider metadata filter subset.

### 6. Normalized metadata filter subset

**Problem:**

Every vector database has slightly different metadata/filter rules. AI Fabric should define a common
portable subset and let providers expose extra native behavior separately.

**Plan:**

- Define `VectorMetadataFilterSupport`:
  - supported scalar types: string, including empty strings, boolean, and integral numbers;
  - integral filters are exact and must not match decimal metadata values by numeric truncation;
  - blank keys, nulls, arrays, nested objects, decimals, dates, and arbitrary objects are rejected by
    the portable path;
  - unsupported filters become impossible provider-native filters or pre-provider empty results instead
    of being dropped.
- Add a sanitizer/validator shared by providers before constructing provider-native filters. Done for
  default scan, memory, Pinecone scan/native search validation, Qdrant gRPC, Qdrant REST, Weaviate,
  Milvus, and Lucene's existing fail-closed filter query path.
- Keep raw metadata JSON valid and stable across providers. `MetadataJsonSerializer` now escapes JSON
  keys and values for quotes, backslashes, control characters, and line/tab separators; non-ordered
  map inputs are serialized in stable key order, while `LinkedHashMap` and config-driven metadata
  order remain preserved.
- Return clear diagnostics when metadata keys are not portable. Provider diagnostics document metadata
  filtering capability and provider-specific extensions such as Lucene-local decimal equality;
  per-request unsupported values fail closed.
- Keep raw metadata stored for round-trip even when not filterable.

**Acceptance criteria:**

- Cross-provider tests use the portable subset and now assert unsupported array/null filters return no
  matches for both search and scan.
- Provider-specific tests cover native extensions.
- Core serializer tests prove raw metadata JSON survives quotes, backslashes, control characters,
  null values, configured key ordering, and stable ordering for unordered map inputs.
- Release docs do not imply all arbitrary JSON metadata is equally filterable everywhere.

### 7. In-memory provider production guard

**Current behavior:**

The in-memory provider is useful for tests and local development. It is not durable.

**Plan:**

- Add clear documentation that `ai-fabric-vector-memory` is dev/test only.
- Add optional production guard:
  - if active profile includes `prod` and vector type is memory, log an error or fail startup unless
    `ai.vector-db.memory.allow-in-production=true`.
- Add tests for the guard.

**Acceptance criteria:**

- No production deployment accidentally uses memory vector storage without an explicit override.
- Examples can still use memory for quick starts.

### 8. Capability matrix and release docs

Add a release-facing provider matrix:

| Provider | Native client | Exact fetch | Query vector search | Search metadata filter | Scan | Scan metadata filter | Efficient count | Clear by entity type | Production use |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Pinecone | Yes | Yes | Yes | Yes, portable scalar filter | Yes, list/fetch | Yes, client-side portable scalar filter | Yes, namespace stats | Yes, eventual consistency handled | Yes |
| Qdrant | Yes, gRPC or REST | Yes | Yes | Yes | Yes, scroll | Yes, payload index required | Yes, count API | Yes | Yes |
| Weaviate | Yes | Yes | Yes | Yes | Yes, GraphQL | Yes | Yes, native aggregate | Yes | Yes |
| Milvus | Yes | Yes | Yes | Yes, expression over stored metadata JSON | Yes, query | Yes, expression over stored metadata JSON | No, exact visible-row scan | Yes, collection drop/delete | Yes |
| Lucene | Native library | Yes | Yes | Yes, scalar indexed metadata plus local decimal equality | Yes | Yes, scalar indexed metadata plus local decimal equality | Yes, local index | Yes | Dev/local/small deployments |
| Memory | N/A | Yes | In-memory cosine | Yes, in-memory | Yes | Yes, in-memory | Yes, in-memory | Yes | Dev/test only |

This matrix should live in public docs once the implementation matches the claims.

## Test strategy

### Provider contract tests

A shared contract test suite now lives at:

- `ai-infrastructure-module/integration-Testing/vector-contract-tests`

The offline path covers local memory and Lucene providers. The opt-in container path covers Qdrant
REST, Qdrant gRPC, Weaviate, and Milvus when Docker is available.

The contract verifies `VectorDatabaseService` behavior:

- store vector;
- update vector;
- exact get by vector id;
- exact get by entity id;
- created/updated lifecycle timestamps survive direct provider store/read/update paths where the
  provider persists metadata;
- vector exists;
- query-vector search;
- metadata-filtered search where supported;
- empty-string metadata equality remains exact for search and scan;
- scan and cursor behavior where supported;
- include/exclude content, metadata, and embedding in scan, including `null` values for omitted
  projection fields;
- count by entity type;
- remove by entity;
- remove by id;
- clear by entity type;
- diagnostics contain provider scope.

Providers should opt into capability-specific test groups based on actual capability flags.

Run it with:

```bash
cd ai-infrastructure-module
mvn test -pl integration-Testing/vector-contract-tests -am
```

Run the provider-container variant with:

```bash
.github/scripts/run-vector-container-contracts.sh
```

This requires a running Docker daemon. Images can be overridden with
`TESTCONTAINERS_QDRANT_IMAGE=...`, `TESTCONTAINERS_WEAVIATE_IMAGE=...`, and
`TESTCONTAINERS_MILVUS_IMAGE=...`.

### Provider-specific tests

Add focused tests for behavior that is intentionally provider-specific:

- Pinecone namespace consistency, sparse fallback, retry behavior.
- Qdrant payload-index creation and fail-closed mode.
- Weaviate tenant/class/schema behavior and aggregate count.
- Milvus collection/index/flush/drop behavior, dimension-mismatch rejection, visible-row exact count,
  and stale-stat cleanup fallback behavior.
- Milvus metadata expression generation for stored JSON-string scalar values and escaped `LIKE`
  wildcard characters.
- Weaviate and Milvus native metadata filter builders for unsupported metadata fail-closed behavior.
- Lucene decimal metadata equality as a local provider extension, while portable metadata filters
  continue to reject decimals.
- Lucene indexed scalar metadata filtering and backward compatibility.
- Memory production guard.

### Integration tests

Use existing Testcontainers support where available for Qdrant, Milvus, and Weaviate test
fixtures. Keep external SaaS providers behind opt-in real API tests. The shared
Testcontainers helper also contains Chroma and pgvector container fixtures, but there are no
AI Fabric Chroma or pgvector vector provider modules in the current repository.

Run the container-backed vector provider contract suite with:

```bash
.github/scripts/run-vector-container-contracts.sh
```

The automatic `Framework Build` workflow and the manual `Framework Provider Matrix Suite` workflow
run this as the `Vector Provider Container Contracts` job.

Run the Pinecone live provider suite with:

```bash
cd ai-infrastructure-module
PINECONE_API_KEY=... \
PINECONE_API_HOST=https://<index-host>.pinecone.io \
PINECONE_INDEX_NAME=<index-name> \
PINECONE_LIVE_REQUIRED=true \
mvn verify -Ppinecone-live-tests -pl victor-databases/ai-fabric-vector-pinecone -am
```

`PINECONE_INDEX_NAME` can be omitted when it can be derived from `PINECONE_API_HOST`. For non-host
configuration, provide `PINECONE_INDEX_NAME` and `PINECONE_ENVIRONMENT`.
`PINECONE_LIVE_REQUIRED=true` makes missing live credentials or location configuration fail release
verification instead of reporting skipped live tests.

The manual `Framework Provider Matrix Suite` GitHub Actions workflow now runs this direct Pinecone
provider-live suite automatically for the Pinecone matrix row before the broader RealAPI application
suites.

## Implementation order

1. Add capability model cleanup and matrix documentation. Done.
2. Harden Qdrant payload-index diagnostics and fail-closed mode. Done.
3. Implement Weaviate native aggregate count. Done.
4. Implement Lucene scalar metadata indexes and filtering. Done.
5. Add in-memory production guard. Done.
6. Publish the release-facing vector provider matrix. Done.
7. Add shared provider contract tests. Done for memory and Lucene.
8. Add Testcontainers contract implementations for Qdrant REST/gRPC, Weaviate, and Milvus. Done;
   local Docker execution passes through the `container-contract-tests` profile and the suite is wired
   into the manual provider-matrix workflow.
9. Add Qdrant fallback visibility in search metadata and rows. Done.
10. Prevent Qdrant fallback from widening unsupported metadata filters. Done.
11. Add deeper Pinecone consistency and sparse/metadata-filter mocked tests. Done.
12. Add focused Milvus native lifecycle mocked coverage. Done.
13. Add focused Weaviate schema/tenant/upsert mocked coverage. Done.
14. Add live Pinecone eventual-consistency and sparse-index verification to the opt-in real API suite.
    Done; compile-verified locally and wired into the manual provider-matrix workflow, pending execution
    with live Pinecone credentials.
15. Add Pinecone public API missing-embedding regression coverage. Done.
16. Centralize scan projection semantics across core and native vector providers. Done.
17. Centralize direct provider lifecycle input validation and invalid-identity no-op semantics. Done.
18. Replace Milvus stats/list entity-type count with visible-row exact count plus mocked regression
    coverage for success, missing collections, provider failures, and cleanup fallback to statistics.
    Done.
19. Apply Pinecone transient retry handling consistently across similarity query, list/fetch scan, and
    delete lifecycle paths with mocked regression coverage. Done.
20. Add low-cardinality provider fallback/retry metrics with focused helper and provider-level mocked
    assertions. Done.
21. Persist direct-provider lifecycle timestamps through Qdrant REST/gRPC, Weaviate, Milvus, and
    Pinecone metadata, with core helper coverage and provider mocked assertions. Done.
22. Make scoped delete-by-vector-id return `false` for absent records without falling through to an
    unscoped delete path in Qdrant REST/gRPC and Weaviate. Done.
23. Make Milvus exact count and clear count use the same visible-row semantics, and mark efficient
    exact count unsupported because native `row_count` can include stale deleted/upserted rows. Done.
24. Add Weaviate internal sentinel handling for empty-string metadata properties and post-parse
    portable metadata equality filtering for integral-vs-decimal exactness. Done.

## Non-goals

- Do not add sidecar storage.
- Do not replace native vector providers with Spring AI `VectorStore`.
- Do not promise arbitrary nested JSON filtering across all providers.
- Do not make metadata-filtered similarity search stand in for exact record fetch.
- Do not remove provider-specific native optimizations just to make all providers look identical.

## Release positioning

Use this wording:

```text
AI Fabric uses native vector provider integrations for full lifecycle/admin operations. Provider
capabilities are normalized where possible and exposed honestly where provider semantics differ.
Spring AI remains valuable for LLM, embedding, structured output, tools, and other commodity AI
plumbing, while AI Fabric keeps vector lifecycle/admin as a differentiated production layer.
```
