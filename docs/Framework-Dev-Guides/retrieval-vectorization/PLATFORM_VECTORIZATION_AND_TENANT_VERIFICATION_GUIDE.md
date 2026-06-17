# Platform Vectorization and Tenant Verification Guide

This guide explains how to run and validate the new platform-admin verification flows for:

- deployment vectorization
- managed runner readiness
- source discovery
- bounded sample indexing
- sync-state and reindex handling
- tenant-scoped shared-vector isolation

This is the operational guide for the implemented product feature, not a planning document.

Related guides:

- `PLATFORM_V2_FEATURES_GUIDE.md`
- `PLATFORM_HOSTED_DEPLOYMENT_VERIFICATION_GUIDE.md`
- `VERIFICATION_PLAYBOOK.md`
- `CUSTOMER_TENANT_SHARED_VECTOR_STORAGE_GUIDE.md`
- `DATA_SYNC_PUSH_API_GUIDE.md`

---

## 1. What This Verification Covers

The platform now has two related verification tracks.

### Track A: Vectorization verification

This proves that one deployment can:

- resolve a source connection
- run discovery
- use a managed or remote runner
- execute a bounded sample vectorization run
- keep sync-state and reindex posture coherent

This is the right verification path for:

- onboarding indexing
- deployment-scoped source mapping
- runner lifecycle
- vectorization drift handling

### Track B: Tenant-shared isolation verification

This proves that two tenant-bound deployments can:

- share the same provider root
- stay isolated by tenant scope
- write the same logical record id under different tenants
- read back only their own tenant’s data

This is the right verification path for:

- shared Weaviate native multi-tenancy
- shared Pinecone namespace isolation
- shared Qdrant collection isolation
- shared Milvus/Zilliz tenant-scoped collection patterns

---

## 2. Who Can Run It

Vectorization verification is a platform-admin feature.

`PLATFORM_ADMIN` can:

- launch vectorization verification runs from the UI
- inspect verification evidence
- rotate runner tokens
- inspect linked vectorization runs
- run tenant shared isolation smoke checks

Non-admin operators can still:

- configure source connections
- configure vectorization plans
- run normal vectorization jobs
- inspect normal vectorization run history

But non-admin operators cannot launch or inspect the admin verification flows.

---

## 3. Where It Lives In The UI

The main UI location is:

- deployment workspace
- `Vectorization` page

There are now two useful UI surfaces:

### Workspace header

The deployment workspace header shows three service cards:

- `Runtime service`
- `REST connector service`
- `Vectorization runner`

The runner card is important because it shows:

- registration status
- compatibility status
- runner mode
- product version
- last heartbeat

Important:

- runtime and connector expose public URLs
- the vectorization runner is usually a private pull worker
- so the runner does not normally expose a public base URL like runtime or connector

### Vectorization page

The `Vectorization` page is the control plane for:

- source connection config
- plan config
- run history
- admin verification runs
- verification evidence

The verification section has these buttons:

- `Control-plane readiness`
- `Runner provisioning smoke`
- `Discovery smoke`
- `Sample vectorization smoke`
- `Sync-state and reindex smoke`
- `Runner compatibility smoke`
- `Tenant shared isolation smoke`

It also shows:

- recent verification runs
- verification summary
- step-by-step evidence
- linked vectorization runs where relevant

---

## 4. Verification Types And What They Mean

### `CONTROL_PLANE_READINESS`

Read-only check.

It verifies:

- vectorization overview loads
- vectorization preview loads
- plan exists when expected
- source connection exists when expected
- active revision is present
- sync-state is resolved

Use this for:

- a deployment that should already have vectorization configured
- fast readiness validation before deeper smokes

### `RUNNER_PROVISIONING_SMOKE`

Read-only check.

It verifies:

- runner registration exists
- runner is active
- compatibility is not broken
- managed-runner expectations are satisfied

Use this for:

- deployments using `PLATFORM_MANAGED_AUTO`
- deployments where the runner was just reprovisioned or token-rotated

### `SOURCE_DISCOVERY_SMOKE`

Active check with a linked vectorization run.

It verifies:

- the runner can claim work
- source discovery can execute
- discovery counts return to the platform

Use this for:

- checking source connectivity
- validating dataset count discovery
- verifying store/API source adapters

### `SAMPLE_VECTORIZATION_SMOKE`

Active check with a linked vectorization run.

It verifies:

- the runner can execute a bounded vectorization run
- runtime ingestion works through the normal data-sync path
- a sample indexing run completes successfully

Use this for:

- end-to-end onboarding proof
- validating mapping + ingestion + indexing without a full bulk refresh

### `SYNC_STATE_AND_REINDEX_SMOKE`

Read-only check.

It verifies:

- sync-state is consistent
- reindex options are exposed
- the deployment can reason about reindex posture correctly

Use this for:

- confirming `IN_SYNC`, `OUT_OF_DATE`, `BOOTSTRAP_REQUIRED`, or related states
- checking reindex readiness after config changes

### `RUNNER_COMPATIBILITY_SMOKE`

Read-only check.

It verifies:

- runner compatibility is current
- product version and compatibility version are acceptable

Use this for:

- post-upgrade validation
- runner replacement validation

### `TENANT_SHARED_ISOLATION_SMOKE`

Active admin verification.

It verifies:

- counterpart deployment resolution
- shared provider root alignment
- distinct tenant handles
- primary and counterpart probe upserts
- read isolation on both sides
- cleanup after the proof run

Use this for:

- real shared-storage proof
- proving that cross-tenant leakage does not happen

---

## 5. Prerequisites

### For vectorization verification

The deployment should already have:

- an applied version
- healthy runtime
- healthy REST connector
- a source connection
- an active vectorization plan
- a runner when the plan uses `PLATFORM_MANAGED_AUTO`

### For tenant-shared isolation verification

You need two deployments that satisfy all of these:

- same customer
- different tenants
- same shared provider root
- shared storage posture
- provider-native isolation enabled

Examples:

- Weaviate:
  - same cluster host
  - native multi-tenancy enabled
  - different tenant handles
- Pinecone:
  - same index root
  - different namespaces

---

## 6. Recommended Live Example For Vectorization

For a clean end-to-end proof, use the ecommerce store REST API as the source.

Source pattern:

- base URL: the deployed store app
- datasets:
  - `product -> /api/products?limit=500`
  - `review -> /api/reviews?limit=500`
  - `policy -> /api/policies?limit=500`
- auth mode:
  - `NONE` for the demo store

Recommended deployment entity scope:

- `product`
- `review`
- `policy`

Typical plan shape:

- adapter type: `REST_API`
- runner mode: `PLATFORM_MANAGED_AUTO`
- entity scope:
  - `product`
  - `review`
  - `policy`
- execution:
  - bounded page size
  - bounded batch size

This is the same shape that was proven live for the Pinecone verification deployment.

---

## 7. How To Run It In The UI

### 7.1 Vectorization deployment verification

1. Open the deployment workspace.
2. Open `Vectorization`.
3. Confirm the source connection, plan, and runner summary are present.
4. In the `Verification` section, use the admin buttons in this order:

- `Control-plane readiness`
- `Runner provisioning smoke`
- `Discovery smoke`
- `Sample vectorization smoke`
- `Sync-state and reindex smoke`
- `Runner compatibility smoke`

5. Select each recent verification run and inspect:

- verification summary
- verification steps
- linked vectorization run
- run diagnostics

Recommended pass criteria:

- `Control-plane readiness` -> `PASSED`
- `Runner provisioning smoke` -> `PASSED`
- `Discovery smoke` -> `PASSED`
- `Sample vectorization smoke` -> `PASSED`
- `Sync-state and reindex smoke` -> `PASSED`
- `Runner compatibility smoke` -> `PASSED`

### 7.2 Tenant shared isolation verification

1. Open one of the shared tenant-bound deployments.
2. Open `Vectorization`.
3. In the `Tenant isolation counterpart deployment` field, enter the other deployment id.
4. Click `Tenant shared isolation smoke`.
5. Open the resulting verification run.
6. Confirm the step evidence shows:

- counterpart resolved
- tenant handles are distinct
- primary sentinel upserted
- counterpart sentinel upserted
- isolation verified
- primary cleanup
- counterpart cleanup

Recommended pass criteria:

- status = `PASSED`
- `primarySeesOwn = true`
- `primarySeesForeign = false`
- `counterpartSeesOwn = true`
- `counterpartSeesForeign = false`

---

## 8. How To Verify The Result In The UI

### Vectorization overview

The vectorization overview should show:

- source connection `READY`
- plan `ACTIVE`
- runner registration `ACTIVE`
- runner compatibility `CURRENT`
- sync-state `IN_SYNC` after successful onboarding and refresh

### Verification run evidence

For each verification run, check:

- overall status
- summary JSON
- detailed per-step evidence
- linked vectorization run state when present

### Runner health

Use the workspace header runner card and the vectorization overview together.

Expected healthy state:

- registration status = `ACTIVE`
- compatibility status = `CURRENT`
- last heartbeat is recent

### Shared-storage readiness

Use:

- `Providers`
- `Overview`
- `Diagnostics`
- `Vectorization`

Expected healthy state:

- tenant-scoped shared storage = `READY`
- shared root matches both deployments
- tenant handles differ

---

## 9. Script-Based Verification Parity

The platform-admin UI path and the shell-script path should agree.

The main script is:

- `scripts/verify-vector-deployment.sh`

### 9.1 Full vectorization-enabled deployment

Use this for deployments that have:

- source connection
- active plan
- active runner

Set:

- runtime and connector base URLs
- deployment id
- platform login or cookie
- vectorization expectations:
  - plan present
  - source present
  - runner present
  - runner mode
  - sync-state
  - available entities
  - entity scope
- admin verification flags:
  - `VERIFY_VECTORIZATION_ADMIN=true`
  - `VERIFY_VECTORIZATION_RUNNER_ACTIVE=true`
  - `VERIFY_VECTORIZATION_SAMPLE=true`

Optional:

- `VERIFY_WRITE=true`

That enables the direct data-sync upsert/delete roundtrip in addition to the platform-admin verification runs.

### 9.2 Shared tenant isolation without vectorization plan

Use this for shared-storage deployments that do not have an active vectorization plan.

Set:

- tenant-shared expectations:
  - customer id
  - tenant id
  - shared root
  - tenant handle
  - readiness status
  - registry status
- vectorization presence expectations:
  - plan present = `false`
  - source present = `false`
  - runner present = `false`
- admin verification flags:
  - `VERIFY_VECTORIZATION_ADMIN=true`
  - `VERIFY_VECTORIZATION_CONTROL_PLANE=false`
  - `VERIFY_TENANT_SHARED_ISOLATION=true`
  - `VECTORIZATION_COUNTERPART_DEPLOYMENT_ID=<other deployment>`

Important:

- `VERIFY_VECTORIZATION_CONTROL_PLANE=false` is the correct setting when you only want admin tenant-isolation proof and the deployment is not meant to have a vectorization plan.

### 9.3 Hosted runner and GitHub Actions

The hosted platform verification runner and GitHub Actions remain read-only by default.

That means:

- no direct write roundtrip
- no destructive test writes
- safe admin proof only

Use the UI path or direct operator shell path for the deeper active checks when needed.

---

## 10. Manual API Flow For Admins

The UI is the preferred path, but the verification feature is also exposed through the deployment admin API.

Main endpoints:

- `GET /api/deployments/{deploymentId}/vectorization`
- `GET /api/deployments/{deploymentId}/vectorization/preview`
- `GET /api/deployments/{deploymentId}/vectorization/verifications`
- `POST /api/deployments/{deploymentId}/vectorization/verifications`
- `GET /api/deployments/{deploymentId}/vectorization/verifications/{verificationRunId}`

Create body:

```json
{
  "verificationType": "TENANT_SHARED_ISOLATION_SMOKE",
  "entityTypes": ["product"],
  "counterpartDeploymentId": "dep-xxxx"
}
```

Other `verificationType` values:

- `CONTROL_PLANE_READINESS`
- `RUNNER_PROVISIONING_SMOKE`
- `SOURCE_DISCOVERY_SMOKE`
- `SAMPLE_VECTORIZATION_SMOKE`
- `SYNC_STATE_AND_REINDEX_SMOKE`
- `RUNNER_COMPATIBILITY_SMOKE`

---

## 11. How To Check It On Railway

### Runtime and connector

These are public HTTP services.

You should see:

- runtime service
- REST connector service

And both expose public URLs.

### Vectorization runner

This is different.

The runner is usually:

- provisioned as a Railway service
- deployed as a worker-style pull process
- not exposed as a public app URL

So it is normal that:

- the service exists in Railway
- but a public `https://vectorization-runner-...` URL does not behave like runtime or connector

Use the platform to validate runner health:

- workspace header runner card
- vectorization overview
- runner provisioning smoke
- runner compatibility smoke

Expected service name pattern:

- `vectorization-runner-<deploymentId>`

---

## 12. Troubleshooting

### Runtime and connector are healthy, but runner is missing

Check:

- plan runner mode
- runner token rotation history
- latest apply status
- runner provisioning smoke

If the plan expects `PLATFORM_MANAGED_AUTO`, re-apply the active version or reprovision the deployment.

### Shared tenant isolation fails on Weaviate

If the failure mentions tenant-not-found behavior during existence checks, the runtime may still be on a release before the fix that treats missing tenants as absent vectors.

Re-apply a release that includes the Weaviate runtime fix.

### Script fails on control-plane readiness for a shared-only deployment

That means the script was asked to run vectorization readiness checks on a deployment that has no vectorization plan by design.

Use:

- `VERIFY_VECTORIZATION_CONTROL_PLANE=false`

and keep:

- `VERIFY_TENANT_SHARED_ISOLATION=true`

### Release looks stale even though services are healthy

Re-apply the current version so the latest release record matches the actual live branch and service state.

### Runner is active in platform but not visible as a public URL

Expected.

The runner is a private pull worker, not a public service endpoint.

Use the platform runner card and vectorization overview as the source of truth.

---

## 13. Proven Live Flows

The following live flows have been proven on this branch:

- vectorization plan + source + managed runner + discovery + sample vectorization + sync-state on a Pinecone deployment using the ecommerce store REST API as source
- tenant-shared isolation proof on a real shared Weaviate pair under the same customer with different tenants
- admin UI verification and script parity for both of those flows

That is the standard operator path to preserve when validating future changes.
