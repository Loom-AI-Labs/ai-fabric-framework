# Platform Provider And Vector Deployment Guide

This guide explains how the Platform V2 provider workspace now drives real deployment behavior for LLM providers, embedding providers, and vector backends.

For day-2 managed vendor administration, see:

- `MANAGED_VECTOR_DATABASE_ADMINISTRATION_GUIDE.md`
- `VECTOR_DATABASE_CONFIGURATION_AUTH_AND_DEPLOYMENT_GUIDE.md`

It covers:

- the supported provider/vector matrix
- what is stored in deployment draft config vs the platform secret store
- how vendor-backed templates now behave
- how Pinecone, Qdrant, and Zilliz-backed Milvus managed provisioning works
- how to use provider probes, including unsaved-edit preview probes
- what release verification checks before apply

## 1. Scope

The platform now supports the framework-backed matrix below in the structured `Providers` workspace.

### 1.1 LLM providers

- `openai`
- `azure`
- `anthropic`
- `cohere`
- `gemini`

### 1.2 Embedding providers

- `openai`
- `azure`
- `cohere`
- `gemini`
- `onnx`
- `rest`

### 1.3 Vector backends

- `lucene`
- `memory`
- `qdrant`
- `pinecone`
- `weaviate`
- `milvus`

The platform compiles these choices into managed Railway runtime env, validates the required non-secret fields, and checks the required secrets before release.

`memory` is a development/test backend. If a runtime starts with the `prod` or `production` Spring
profile and `ai.vector-db.type=memory`, startup fails unless
`ai.vector-db.memory.allow-in-production=true` is set deliberately.

### 1.4 Runtime Vector Capabilities

The vector backend is not treated as a commodity similarity-search adapter. AI Fabric expects native
providers to support storage, retrieval, admin scans, deletes, counts, and diagnostics around the same
tenant/customer scoped data.

Capability checks are intentionally split:

- search metadata filtering: metadata filters during vector similarity search
- scan metadata filtering: metadata filters during paged lifecycle/admin scans
- exact fetch by id: direct vector-record lookup
- clear by entity type: delete one entity type without a separate sidecar catalog
- efficient entity-type count: safe count path for overview/readiness flows

Provider posture for this release:

| Backend | Search filter | Scan filter | Exact fetch | Clear entity type | Count posture |
|---------|---------------|-------------|-------------|-------------------|---------------|
| Lucene | Yes, scalar metadata | Yes, scalar metadata | Yes | Yes | Native local index count |
| Memory | Yes | Yes | Yes | Yes | In-memory count |
| Qdrant | Yes | Yes | Yes | Yes | Native count API |
| Pinecone | Yes | Yes, client-side list/fetch | Yes | Yes | Namespace/index stats |
| Weaviate | Yes | Yes | Yes | Yes | Native aggregate count with visible paged fallback |
| Milvus | Yes | Yes | Yes | Yes | Native collection statistics with scan fallback |

This split matters for governance. A provider that can filter similarity search is not automatically
safe for governance catalog work unless it can also perform metadata-filtered scans.

## 2. Source Of Truth

The platform separates provider configuration into two layers.

### 2.1 Deployment draft config

The draft stores non-secret, deployment-scoped values such as:

- selected LLM provider
- selected embedding provider
- selected vector backend
- selected vector provisioning mode
- model names
- provider base URLs
- Azure deployment names
- Qdrant Cloud provider/account/region/package inputs
- Qdrant host and ports
- Pinecone index name, cloud, region, metric, and resolved API host
- Weaviate host and scheme
- Milvus host and database name
- advanced timeouts, priorities, and routing overrides

These values are edited in the `Providers` workspace and become part of the deployment draft and published version.

### 2.2 Platform secret store

Secrets do not belong in deployment drafts. They live in the platform secret workspace.

See the full boundary reference:

- [PLATFORM_CREDENTIALS_AND_SECRET_BOUNDARIES_GUIDE.md](../security-auth/PLATFORM_CREDENTIALS_AND_SECRET_BOUNDARIES_GUIDE.md)

Examples:

- `OPENAI_API_KEY`
- `ANTHROPIC_API_KEY`
- `AZURE_OPENAI_API_KEY`
- `COHERE_API_KEY`
- `GEMINI_API_KEY`
- `QDRANT_API_KEY`
- `PINECONE_API_KEY`
- `WEAVIATE_API_KEY`
- `MILVUS_USERNAME`
- `MILVUS_PASSWORD`

Do not put platform service env vars such as `RAILWAY_API_TOKEN`, `RAILWAY_WORKSPACE_ID`, or `PLATFORM_DB_PASSWORD` into this workspace. Those belong on the platform backend Railway service.

The platform uses these secrets for:

- runtime env compilation
- vendor probe execution
- managed vector provisioning
- release verification

## 3. Template Behavior

Vendor-backed templates are no longer just informational presets. Some now default to the managed platform path.

### 3.1 Qdrant template

`dev-openai-qdrant` now defaults to:

- `vectorStrategy = qdrant`
- `vectorProvisioningMode = PLATFORM_MANAGED`
- `qdrantManagedCollectionsEnabled = true`

This means:

- after creation, the operator reviews the Qdrant Cloud provider/region/package inputs instead of pasting a cluster host
- apply can create or reuse a deployment-owned Qdrant Cloud cluster, issue a deployment-scoped runtime API key, and then reconcile one collection per configured entity type

### 3.2 Pinecone templates

`dev-azure-pinecone` now defaults to:

- `vectorStrategy = pinecone`
- `vectorProvisioningMode = PLATFORM_MANAGED`
- a generated default `pineconeIndexName`

This means:

- the deployment starts from a platform-managed serverless posture
- the operator should review the generated index name and region
- apply will create or reconcile the Pinecone serverless index, resolve the serving host, and bind runtime to that host when `PINECONE_API_KEY` is present

## 4. Managed Vector Provisioning

The platform currently supports platform-managed vector provisioning through formal provider control planes where available.

### 4.1 Pinecone managed serverless index

When:

- `vectorStrategy = pinecone`
- `vectorProvisioningMode = PLATFORM_MANAGED`

The platform treats Pinecone serverless index management as part of the deployment.

Required operator inputs:

- `pineconeIndexName`
- `pineconeRegion`
- `pineconeCloud`
- `PINECONE_API_KEY` in Secrets

What apply does:

- uses the formal Pinecone control-plane API
- checks for the target serverless index
- creates it if missing
- verifies or reconciles key metadata such as dimensions and metric
- waits for the index to become ready
- resolves the API host returned by Pinecone
- writes a deployment-owned managed runtime secret reference for Pinecone access

What the platform shows:

- managed provisioning summary in `Providers`
- pre-apply verification evidence
- readiness impact

### 4.2 Qdrant Cloud managed cluster

When:

- `vectorStrategy = qdrant`
- `vectorProvisioningMode = PLATFORM_MANAGED`

The platform treats Qdrant Cloud cluster provisioning as part of the deployment.

Required operator inputs:

- `qdrantCloudProviderId`
- `qdrantCloudRegionId`
- optional `qdrantCloudAccountId`
- optional `qdrantCloudPackageId`
- entity types in `Entities`
- `QDRANT_CLOUD_MANAGEMENT_API_KEY` in Secrets

What apply does:

- uses the formal Qdrant Cloud control-plane API
- creates or reuses a managed cluster for the deployment
- issues a deployment-scoped database API key for runtime use
- binds the resolved cluster endpoint and runtime key back into deployment config
- checks for one collection per entity type
- creates missing collections using the deployment vector dimensions

### 4.3 Qdrant external existing with managed collections

When:

- `vectorStrategy = qdrant`
- `vectorProvisioningMode = EXTERNAL_EXISTING`
- `qdrantManagedCollectionsEnabled = true`

The platform manages collections inside an already existing Qdrant deployment, but it does not create the cluster/account.

## 5. Qdrant Setup Guidance

### 5.1 Platform-managed Qdrant Cloud

Recommended operator flow:

1. Choose the Qdrant-backed template.
2. Open `Providers`.
3. Keep `Vector provisioning mode = Platform-managed`.
4. Choose the Qdrant Cloud provider and region.
5. Optionally choose account/package inputs if your tenant exposes more than one choice.
6. Add `QDRANT_CLOUD_MANAGEMENT_API_KEY` in `Secrets`.
7. Review `Entities` so the intended collection set is correct.
8. Run provider probes.
9. Publish and apply.

### 5.2 External-existing Qdrant

For an external Qdrant Cloud or self-hosted cluster, set `qdrantHost` to the full HTTPS cluster endpoint.

Example shape:

```text
https://<cluster-id>.<region>.aws.cloud.qdrant.io
```

Notes:

- if `qdrantHost` is a full `https://` URL, the platform treats that as the base endpoint
- port fields matter primarily for non-URL host usage
- if the cluster requires auth, add `QDRANT_API_KEY` in the Secrets workspace

Recommended operator flow:

1. Choose the Qdrant-backed template.
2. Open `Providers`.
3. Change `Vector provisioning mode` to `Bring your own`.
4. Set the Qdrant host.
5. Add `QDRANT_API_KEY` in `Secrets` if needed.
6. Run provider probes.
7. Review `Entities` so the intended collection set is correct.
8. Publish and apply.

## 6. Pinecone Setup Guidance

### 6.1 Platform-managed Pinecone serverless

For a platform-managed Pinecone deployment:

1. Choose the Pinecone-backed template.
2. Open `Providers`.
3. Keep `Vector provisioning mode = Platform-managed`.
4. Review or change `pineconeIndexName`.
5. Review `pineconeCloud`, `pineconeRegion`, `pineconeMetric`, and dimensions.
6. Add `PINECONE_API_KEY` in `Secrets`.
7. Run provider probes.
8. Publish and apply.

Important:

- the platform creates or reconciles the serverless index through Pinecone's formal control-plane API
- the platform resolves and stores the serving host automatically
- the current runtime binding uses a deployment-owned managed secret reference backed by the connected Pinecone API key

### 6.2 External-existing Pinecone

If you switch to `Bring your own`:

- provide `pineconeApiHost` or the legacy environment/project inputs required for your setup
- the platform validates and probes the existing target
- the platform does not create the Pinecone index for you in this mode

## 7. Other External Backends

### 7.1 Weaviate

When `vectorStrategy = weaviate`, the operator must set:

- `weaviateHost`
- `weaviateScheme` and `weaviatePort` as needed
- `WEAVIATE_API_KEY` in Secrets if the cluster is protected

### 7.2 Milvus

When `vectorStrategy = milvus`, the operator must set:

- `milvusHost`
- `milvusPort`
- optional `MILVUS_USERNAME` and `MILVUS_PASSWORD` in Secrets

Milvus now supports:

- `EXTERNAL_EXISTING` for bring-your-own clusters
- `PLATFORM_MANAGED` through Zilliz Cloud

For the managed Zilliz path, the platform creates or reuses the cluster, binds deployment-scoped runtime credentials, and then deploys runtime against the resolved Milvus endpoint.

## 8. Provider Workspace Behavior

The `Providers` workspace now has four important behaviors.

### 8.1 Structured editor

The page exposes only the fields relevant to the chosen provider/vector stack.

### 8.2 Vendor onboarding guidance

The page now explains, for the current draft:

- which host/base URL still needs review
- which secret must exist
- what the platform will create or reconcile at apply time

### 8.3 Saved-draft vendor probes

The original vendor probe action still exists and can inspect the saved draft state.

### 8.4 Current-buffer probe preview

If the editor has unsaved changes, the probe action now uses the current browser buffer for editors instead of forcing a save first.

This is the correct flow for:

- trying a new Qdrant endpoint
- trying a new Pinecone index/region combination
- checking a Weaviate host change

The draft remains unchanged until the operator explicitly saves.

## 9. Verification And Readiness

Provider/vector configuration now feeds real release and readiness logic.

### 9.1 Pre-apply verification

The platform now checks:

- provider secret requirements
- managed vector provisioning prerequisites
- vendor probe outcomes for external dependencies

If required vendor connectivity fails before apply, release verification can fail before rollout.

### 9.2 Production readiness

Readiness now includes provider connectivity evidence as its own area.

This gives operators visibility into:

- whether external vendor dependencies were checked
- whether connectivity is blocked or failing
- whether the deployment is ready for rollout from a provider standpoint

### 9.3 Managed vector drift evidence

For platform-managed Pinecone, Qdrant, and Zilliz-backed Milvus deployments, the platform now also evaluates the managed vector resource record against the current deployment vector config.

This means operators can see when:

- the live deployment version points at a different managed vector target than the resource registry
- the recorded endpoint or managed secret binding no longer matches the deployment config
- expected managed resources such as a serverless index, cluster record, database key, or collections are missing

These signals now appear in:

- source of truth
- diagnostics
- production readiness
- governed remediation

### 9.4 Destructive reset guardrails

`Reset runtime vectors` is now blocked when either of these is true:

- Railway runtime state has drifted from the platform-managed deployment plan
- managed vector resource records no longer match the live deployment target

This prevents destructive resets from running against an uncertain vector target.

### 9.5 Managed vector lifecycle actions

The platform now exposes governed managed-vector lifecycle actions in `Diagnostics`.

Implemented now:

- `Recreate managed vector target`
  - currently supported for `PLATFORM_MANAGED` Pinecone indexes and `PLATFORM_MANAGED` Zilliz-backed Milvus clusters
  - uses the formal Pinecone or Zilliz Cloud control plane
  - temporarily disables deletion protection if the live index has it enabled
  - deletes the managed index or cluster
  - launches a fresh apply of the active version so the platform reprovisions the target
- `Detach managed vector resources`
  - available only for archived deployments
  - marks active managed-vector records as detached without deleting vendor resources
  - this is the correct path when ownership is being handed off or decommissioning is being staged
- `Clean up detached managed vector resources`
  - available only for archived deployments
  - removes detached registry records
  - performs provider-side cleanup where the vendor contract supports it
  - for Qdrant Cloud this includes detached database API keys and detached clusters
  - for Pinecone this includes detached platform-managed indexes
  - for Zilliz Cloud this includes detached managed clusters

Explicit but intentionally blocked:

- `Rotate managed runtime credential`
  - the platform now shows this flow explicitly in remediation
  - for Qdrant Cloud, execution stays blocked until the platform supports staged live cutover and old-key retirement safely
  - for Pinecone, the supported path is to rotate `PINECONE_API_KEY` in platform Secrets and redeploy the active version
  - for Zilliz-backed Milvus, the supported path is to rotate the connected Zilliz control-plane secret and redeploy the active version once safe cutover tooling exists

## 10. Recommended Operator Workflow

For any non-local provider/vector deployment:

1. Create the deployment from the closest template.
2. Open `Providers`.
3. Review the selected provider stack and any managed vendor provisioning defaults.
4. Add required secrets in `Secrets`.
5. Use vendor probes.
6. Save the provider draft once satisfied.
7. Publish a version.
8. Run or review verification.
9. Apply the version.
10. Confirm live state in `Overview`, `Diagnostics`, and `Production readiness`.

## 11. Current Limits

The current platform implementation is strong on deployment-scoped provider control, but a few boundaries remain:

- the platform now supports Qdrant Cloud managed-cluster creation, Pinecone serverless index creation, and Zilliz Cloud managed-cluster creation where the formal control plane exists
- Qdrant Cloud is now proven end to end in `PLATFORM_MANAGED`, including existing-cluster reuse when vendor billing blocks new-cluster creation
- Weaviate remains `EXTERNAL_EXISTING` only because the current platform does not have an equivalent public cluster lifecycle control plane to automate safely
- the current Pinecone managed path mirrors connected key material into a deployment-owned managed runtime secret rather than using a separate vendor-issued runtime credential
- Qdrant/Pinecone/Zilliz cleanup and Pinecone/Zilliz recreate flows are implemented, but safe staged runtime-key rotation is still intentionally blocked
- Weaviate is supported in config/governance flows, but cluster lifecycle automation is still out of scope
- vendor probes are on-demand, not background polling
- provider plugin registration and arbitrary custom providers are still future work

## 12. Related Guides

- [REALAPI_PROVIDER_MATRIX_TESTING_GUIDE.md](../testing-verification/REALAPI_PROVIDER_MATRIX_TESTING_GUIDE.md)
