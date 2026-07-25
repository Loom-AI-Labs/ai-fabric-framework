# Managed Vector Database Administration Guide

This guide explains how to operate platform-managed vector databases after the deployment is already configured.

It focuses on:

- managed Qdrant Cloud administration
- managed Pinecone administration
- managed Zilliz Cloud administration for Milvus
- secret boundaries and credential ownership
- verification and readiness checks
- detach, recreate, and cleanup operations
- current safety limits

This is a day-2 operations guide, not a first-time setup guide.

Related guides:

- `PLATFORM_PROVIDER_AND_VECTOR_DEPLOYMENT_GUIDE.md`
- `VECTOR_DATABASE_CONFIGURATION_AUTH_AND_DEPLOYMENT_GUIDE.md`
- `PLATFORM_V2_FEATURES_GUIDE.md`
- `VERIFICATION_PLAYBOOK.md`

---

## 1. Managed Vector Model

The platform supports three vector provisioning modes:

- `LOCAL_MANAGED`
- `EXTERNAL_EXISTING`
- `PLATFORM_MANAGED`

This guide is about `PLATFORM_MANAGED`.

In `PLATFORM_MANAGED` mode, the platform is responsible for:

- creating or reconciling the vendor-side vector target where formal APIs exist
- binding the resulting endpoint back into deployment config
- tracking deployment-owned managed resources
- surfacing readiness, drift, and remediation in the deployment workspace

Current provider posture:

- `pinecone`: supported through the formal control plane for managed serverless indexes
- `qdrant`: supported through the formal Qdrant Cloud control plane for managed cloud clusters and deployment-scoped database API keys
- `milvus`: supported through Zilliz Cloud for managed clusters and deployment-scoped runtime credentials
- `weaviate`: remains `EXTERNAL_EXISTING` only

When a formal managed provider exists, the platform should use that vendor control plane instead of treating AWS as a fake vendor backend.

---

## 2. Secret Ownership And Credential Boundaries

The platform separates vendor control-plane credentials from runtime-use credentials.

### 2.1 Qdrant Cloud

Two credential types matter:

- `QDRANT_CLOUD_MANAGEMENT_API_KEY`
- `QDRANT_API_KEY`

`QDRANT_CLOUD_MANAGEMENT_API_KEY` is the cloud management key used against:

- `https://api.cloud.qdrant.io`

It is required for:

- account lookup
- cluster discovery
- cluster creation
- package resolution
- temporary database API key creation/deletion
- detached-resource cleanup

`QDRANT_API_KEY` is the runtime/data-plane key used against the cluster endpoint:

- `https://<cluster-id>.<region>.aws.cloud.qdrant.io`

It is used by:

- runtime indexing and query traffic
- external-existing Qdrant deployments
- deployment-bound managed runtime access

Do not put a cluster key into `QDRANT_CLOUD_MANAGEMENT_API_KEY`.

### 2.2 Pinecone

Pinecone currently uses:

- `PINECONE_API_KEY`

The platform uses this key for:

- control-plane index administration
- runtime binding through a deployment-owned secret reference

This means the platform separates the deployment-owned secret reference from draft config, but Pinecone currently still depends on the same vendor API key material underneath.

### 2.3 Where Secrets Live

Vendor credentials must live in the Platform `Secrets` workspace.

They must not be stored in:

- deployment draft JSON
- prompt config
- entity config
- routing config
- manual provider env literals outside the platform-managed secret flow

### 2.4 Zilliz Cloud

Zilliz-managed Milvus currently uses:

- `ZILLIZ_CLOUD_API_KEY`

The platform uses this key for:

- control-plane cluster discovery and creation
- deployment-owned runtime credential generation
- governed cleanup and recreate actions

After apply, the platform binds runtime to deployment-owned managed Milvus username/password secrets instead of storing vendor credentials in the draft.

---

## 3. Where To Create Vendor Credentials

### 3.1 Qdrant Cloud management key

Create or find it in:

- `Qdrant Cloud Dashboard -> Access Management -> Cloud Management Keys`

This is the correct key for `QDRANT_CLOUD_MANAGEMENT_API_KEY`.

If you only see cluster API keys in the cluster details page, that is not the management-key location.

### 3.2 Qdrant cluster/database key

Use the cluster-level key for:

- `QDRANT_API_KEY`

This is the key that works against the cluster endpoint, not the cloud control plane.

### 3.3 Pinecone API key

Create or find it in:

- `Pinecone Console -> project API keys`

Use it for:

- `PINECONE_API_KEY`

The platform uses it for both managed index operations and deployment runtime binding.

### 3.4 Zilliz Cloud API key

Create or find it in:

- `Zilliz Cloud Console -> API Keys`

Use it for:

- `ZILLIZ_CLOUD_API_KEY`

This is the control-plane credential for managed Milvus deployments through Zilliz Cloud.

---

## 4. Required Provider Configuration

### 4.1 Managed Qdrant Cloud

Recommended provider settings:

- `vectorStrategy = qdrant`
- `vectorProvisioningMode = PLATFORM_MANAGED`
- `qdrantCloudProviderId = aws` unless another supported provider is chosen
- `qdrantCloudRegionId = <target region>`
- optional `qdrantCloudAccountId`
- optional `qdrantCloudPackageId`
- optional `qdrantCloudClusterNameOverride`

Required secret:

- `QDRANT_CLOUD_MANAGEMENT_API_KEY`

Optional but commonly present:

- `QDRANT_API_KEY`

Entity types must already exist, because managed collection provisioning is tied to deployment entity types.

### 4.2 Managed Pinecone

Recommended provider settings:

- `vectorStrategy = pinecone`
- `vectorProvisioningMode = PLATFORM_MANAGED`
- `pineconeIndexName`
- `pineconeCloud`
- `pineconeRegion`
- `pineconeMetric`
- deployment vector dimensions

Required secret:

- `PINECONE_API_KEY`

### 4.3 Managed Zilliz Cloud for Milvus

Recommended provider settings:

- `vectorStrategy = milvus`
- `vectorProvisioningMode = PLATFORM_MANAGED`
- `zillizCloudProjectId`
- `zillizCloudRegionId`
- `zillizCloudClusterPlan`
- optional `zillizCloudClusterNameOverride`

Required secret:

- `ZILLIZ_CLOUD_API_KEY`

---

## 5. Verification Flow

Managed vector administration should follow the same verification path every time.

### 5.1 Before save

Use `Probe current edits` in the `Providers` workspace.

This tests the current browser form values without writing them into the saved draft.

Use this when:

- changing Qdrant Cloud region/account/package inputs
- changing Pinecone index or region settings
- validating a provider change before saving

### 5.2 After save

Use the saved-draft probe path and review:

- provider connectivity summary
- managed vector provisioning readiness
- missing secret warnings

### 5.3 After publish/apply

Review:

- `Overview`
- `Verification`
- `Diagnostics`

The platform should show:

- the managed resource record
- provider-specific readiness evidence
- drift state
- remediation availability

---

## 6. Day-2 Administration Operations

The platform currently supports these day-2 managed-vector operations.

### 6.1 Recreate managed vector target

Use this when:

- the managed vector target is corrupt
- the live target must be replaced
- a clean provider-side rebuild is safer than incremental repair

Current support:

- Pinecone and Zilliz-backed Milvus

Current behavior:

- requires an explicit remediation action
- requires confirmation and audit trail
- disables deletion protection first if needed for Pinecone
- deletes the managed index or managed Zilliz cluster
- waits for deletion
- triggers redeploy/apply so the platform recreates the managed target

This is a governed destructive action, not an automatic repair.

### 6.2 Detach managed vector resources

Use this when:

- a deployment is archived
- the operator wants to stop treating the vendor resources as actively owned by the deployment

Current behavior:

- marks the resource records as detached
- keeps the audit trail
- prepares the deployment for later cleanup

### 6.3 Clean up detached managed vector resources

Use this after detach when the deployment is archived and the provider resources should actually be removed.

Current behavior:

- Pinecone: deletes detached managed indexes
- Qdrant Cloud: deletes detached deployment-scoped database API keys
- Qdrant Cloud: deletes detached managed clusters
- Zilliz Cloud: deletes detached managed clusters
- registry records are removed only after cleanup succeeds
- unused managed secrets are cleared only when it is still safe to remove them

### 6.4 Rotate managed runtime credential

This action is intentionally not fully automated yet.

Current posture:

- Pinecone: rotate `PINECONE_API_KEY` in `Secrets`, then redeploy
- Qdrant Cloud: staged live cutover is still required before the platform can safely retire the previous runtime key automatically
- Zilliz Cloud: staged live cutover is still required before the platform can safely rotate the managed runtime credentials automatically

The platform exposes the action and guidance, but does not pretend the unsafe automation already exists.

---

## 7. Provider-Specific Safety Notes

### 7.1 Qdrant Cloud

Use this mental model:

- the platform owns the cloud cluster only when `PLATFORM_MANAGED` is selected
- the platform may create deployment-scoped database API keys even when the cloud account itself remains customer-owned
- the management key is a high-privilege secret and should be rotated independently from runtime keys

Do not:

- use the management key as the runtime key
- paste a database API key into the management-key slot
- manually change managed cluster identity outside the platform and then expect no drift

Runtime hardening:

- Qdrant declares both search and scan metadata filtering because the provider uses native filtered
  search/scroll operations.
- Filtered operations depend on payload indexes. By default, when Qdrant reports a missing index,
  AI Fabric creates portable typed indexes (`keyword`, `bool`, or `integer`) and retries the same
  filtered operation once.
- The retry never removes the native filter. If index repair or the filtered retry fails, the
  operation fails visibly instead of returning a weaker result.
- Qdrant `adminDiagnostics()` exposes lazy payload-index readiness evidence through
  `requiredPayloadIndexFields`, `verifiedPayloadIndexes`, `payloadIndexesSeenMissing`,
  `payloadIndexCreateAttempts`, and `payloadIndexCreateFailures`.
- Qdrant `adminDiagnostics().payloadIndexRepairAttempts` exposes a per-collection count of repair
  attempts. The legacy `metadataFilterFallbacks` field remains present and empty for diagnostic
  compatibility.
- Set `ai.vector-db.operations.fail-on-missing-payload-index=true` for stricter production or release
  verification. In that mode, missing payload-index drift fails immediately with a clear runtime
  error instead of attempting repair.

Operator check:

```bash
curl -sS "${RUNTIME_BASE_URL}/api/ai/advanced-rag/health"
```

When the web module and advanced RAG controller are enabled, the response includes
`vectorDatabase` diagnostics from the active `VectorManagementService`. Use this lightweight runtime
check to confirm the active provider class, capability flags, filter modes, payload-index readiness,
and repair/fallback counters without triggering scans or count operations.
The `vectorDatabase.capabilities` object is the typed release descriptor for this evidence. It
includes the provider name, provider class, native client path, lifecycle/admin capability flags,
search and scan filter modes, metadata filter subset, entity-type count mode, clear-by-entity-type
mode, consistency model, durability, production-profile safety, and the computed
`lifecycleAdminCompatible` flag. The older flat diagnostic keys remain for compatibility, but new
release checks should read the nested descriptor first.
For count-path release review, also inspect standardized `countFallbacks` and
`countFallbackReasons`. A positive counter means the provider had to leave its native count/statistics
path and use a compatibility count path.
For operational alerting, AI Fabric also publishes low-cardinality Micrometer counters:
`ai.fabric.vector.provider.fallbacks` for compatibility fallback events and
`ai.fabric.vector.provider.retries` for provider retry events, including Qdrant
`reason=payload_index_repair`. Both counters use only
`provider`, `operation`, and `reason` tags. Scope identifiers such as tenant, namespace, collection,
entity type, and entity id stay out of metric tags; use readiness diagnostics for those details after
an alert fires.
For standard Spring Boot health integration, AI Fabric also registers `vectorProviderHealthIndicator`
when actuator health classes and `VectorManagementService` are available. Check it with:

```bash
curl -sS "${RUNTIME_BASE_URL}/actuator/health/vectorProvider"
```

The actuator component is `UP` for `READY` and `WARN`, and `DOWN` for `NOT_READY`. Disable it with
`management.health.ai-fabric.vector.enabled=false` if another deployment health policy owns vector
readiness.
For release smoke checks, prefer the repository verifier because it fails on `WARN` unless explicitly
allowed:

```bash
RUNTIME_BASE_URL="${RUNTIME_BASE_URL}" \
.github/scripts/verify-vector-readiness-health.sh
```

The nested `vectorDatabase.readiness` object gives the operator-facing verdict:

- `READY`: clean production-ready provider evidence
- `WARN`: operational with warnings that should be reviewed before release
- `NOT_READY`: missing diagnostics, missing lifecycle/admin capability evidence, or provider drift
  such as Qdrant payload-index creation failures

Treat `productionReady=false` as a release review item even when `operational=true`.

### 7.2 Pinecone

Pinecone serverless indexes are the managed resource unit.

Important operational detail:

- deletion protection may need to be disabled before a managed index can be deleted

The platform already handles this during governed recreate and cleanup flows.

Runtime hardening:

- Pinecone declares metadata filtering for similarity search and scan. Search uses provider-side
  metadata filters; scan uses list/fetch plus AI Fabric's portable scalar predicate.
- Pinecone `adminDiagnostics()` reports `searchFilterMode=provider-side-portable-scalar` and
  `scanFilterMode=client-side-list-fetch-portable-scalar` so operators can distinguish the two paths.
- Pinecone search filters preserve portable exact string values, including empty strings, instead of
  treating blank strings as unsupported provider filters.
- Pinecone retries transient rate-limit, availability, and deadline failures across search, stats,
  upsert, list/fetch scan, delete, and deterministic clear lifecycle paths.
- Clear-by-entity-type uses provider delete paths and waits for eventual consistency when
  `ai.vector-db.operations.await-clear-consistency=true`.

Do not:

- manually swap the serving host in runtime env outside the platform
- manually rename managed indexes without redeploying through the platform

### 7.3 Weaviate

Runtime hardening:

- Weaviate uses native GraphQL aggregate count for entity-type counts when supported by the target.
- If aggregate count is not supported by the deployment, the provider falls back only for that
  unsupported-aggregate case and counts through paged scan results; ordinary provider failures remain
  visible.
- Weaviate `adminDiagnostics()` reports `countMode=native-aggregate-with-safe-fallback` and records
  per-class fallback evidence in `aggregateCountFallbacks` and `aggregateCountFallbackReasons`.
- Weaviate declares search metadata filtering, scan metadata filtering, exact fetch, and clear by entity
  type.
- AI Fabric stores portable metadata properties as text with Weaviate `field` tokenization, so
  string, boolean, integer, and empty-string filters use exact native equality.
- Classes created before `field` tokenization remain correct: search and scan page through native
  results and apply the shared exact-scalar predicate before enforcing the caller's result limit.

### 7.4 Lucene

Runtime hardening:

- Lucene indexes portable scalar metadata fields alongside the stored metadata JSON.
- Search and scan filters support exact equality for strings, booleans, integer/long numbers, and
  Lucene-local float/double numbers. Treat decimal exact equality as provider-specific in portable
  code.
- Unsupported filter shapes return no matches instead of widening the query.

### 7.5 Milvus

Runtime hardening:

- Milvus declares search and scan metadata filtering through native search/query expressions.
- Metadata filters are generated against the same stored metadata JSON-string shape used by writes, so
  boolean and integer filters match the persisted representation.
- String metadata filters escape Milvus `LIKE` wildcard characters (`%` and `_`) before building the
  native expression, preserving exact-match semantics instead of widening results.
- Unsupported filter shapes become impossible metadata expressions instead of dropped conditions.
- Milvus uses native collection statistics for entity-type counts and reports
  `countMode=milvus-collection-statistics-with-scan-fallback`; it scans only when Milvus omits
  `row_count` from the statistics response.

### 7.6 Memory

Runtime hardening:

- The in-memory provider remains useful for tests, demos, and quick local feedback.
- It is not durable. Production Spring profiles reject `ai.vector-db.type=memory` unless
  `ai.vector-db.memory.allow-in-production=true` is set deliberately.

---

## 8. Drift And Source Of Truth

Managed vector state must stay aligned across:

- deployment provider config
- platform managed resource registry
- live provider state
- deployment source-of-truth views

If these diverge, the platform may block targeted remediation actions until the deployment is brought back into an aligned state.

Operators should check:

- `Overview` for source-of-truth and managed-resource summaries
- `Diagnostics` for drift warnings and remediation availability
- `Verification` for provider-specific evidence

---

## 9. Recommended Operator Workflow

For managed database administration, use this sequence:

1. Review provider config in `Providers`.
2. Confirm required secrets exist in `Secrets`.
3. Run `Probe current edits` before saving if the provider config changed.
4. Save the draft.
5. Publish a new version.
6. Apply the version.
7. Review verification evidence.
8. Only use remediation for governed recovery, not as a substitute for normal apply flow.
9. For archived deployments, detach first and clean up second.

---

## 10. What Is Verified Today

The platform branch has live provider verification for all currently supported production paths:

- Pinecone control plane
  - create temporary serverless index
  - wait for readiness
  - disable deletion protection
  - delete index
  - confirm deletion

- Pinecone end to end
  - platform-managed deployment apply
  - runtime uses `PineconeVectorDatabaseService`
  - REST connector data-sync upsert/delete roundtrip succeeds
  - release reaches `APPLIED_VERIFIED`

- Qdrant Cloud control plane
  - account lookup
  - cluster resolution
  - temporary database API key creation
  - temporary database API key deletion

- Qdrant Cloud end to end
  - platform-managed deployment apply
  - runtime uses `QdrantVectorDatabaseService`
  - REST connector data-sync upsert/delete roundtrip succeeds
  - release reaches `APPLIED_VERIFIED`
  - the verified live path reuses an existing managed cluster when vendor billing blocks brand-new cluster creation

- Zilliz Cloud control plane
  - project lookup
  - cluster list and describe

- Milvus through Zilliz Cloud end to end
  - platform-managed deployment apply
  - runtime uses `MilvusVectorDatabaseService`
  - REST connector data-sync upsert/delete roundtrip succeeds
  - release reaches `APPLIED_VERIFIED`

- Weaviate Cloud end to end in `EXTERNAL_EXISTING`
  - runtime uses `WeaviateVectorDatabaseService`
  - REST connector data-sync upsert/delete roundtrip succeeds
  - release reaches `APPLIED_VERIFIED`

---

## 11. Current Limits

These limits are intentional:

- Qdrant runtime-key staged live rotation is not fully automated yet
- Weaviate is still `EXTERNAL_EXISTING` only
- provider-managed vector support is limited to vendors with formal control planes
- AWS fallback is reserved for future cases where no formal managed provider path exists

The platform should not claim full automation where the safety model is not ready.
