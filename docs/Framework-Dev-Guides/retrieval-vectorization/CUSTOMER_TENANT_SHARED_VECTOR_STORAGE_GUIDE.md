# Customer, Tenant, and Shared Vector Storage Guide

This guide explains how to use the completed Wave 4 Track A capability set in the platform.

It covers:

- the enterprise control-plane model
- customer and tenant administration
- deployment-to-tenant binding
- shared vector storage posture
- provider-scoped handle resolution
- verification, diagnostics, and cleanup

This is an operator and customer-admin guide, not a planning document.

Related technical references:

- `PLATFORM_V2_FEATURES_GUIDE.md`
- `PLATFORM_VECTORIZATION_AND_TENANT_VERIFICATION_GUIDE.md`
- `VECTOR_DATABASE_CONFIGURATION_AUTH_AND_DEPLOYMENT_GUIDE.md`
- `MANAGED_VECTOR_DATABASE_ADMINISTRATION_GUIDE.md`
- `PLATFORM_HOSTED_DEPLOYMENT_VERIFICATION_GUIDE.md`
- `VERIFICATION_PLAYBOOK.md`

---

## 1. Enterprise Model

The platform now uses this durable ownership model:

- `Customer -> Tenant -> Deployment`

What that means:

- a `Customer` is the top-level account and ownership boundary
- a `Tenant` is the durable business and data boundary inside one customer
- a `Deployment` belongs to exactly one tenant

Important rules:

- one deployment belongs to exactly one tenant at a time
- a customer may own multiple tenants
- a customer may own multiple tenant-bound deployments
- tenant identity is durable even if deployments are recreated, re-applied, archived, or replaced
- shared vector storage must never cross customer boundaries

This is the core enterprise model for shared vector infrastructure.

---

## 2. Supported Roles

The main roles for this feature set are:

- `PLATFORM_ADMIN`
- `CUSTOMER_ADMIN`

`PLATFORM_ADMIN` can:

- create and update customers
- create and update tenants
- bind deployments to any valid customer and tenant
- review tenant-scoped shared vector handles across the platform
- purge detached shared-handle history when cleanup rules allow it

`CUSTOMER_ADMIN` can:

- manage only customers and tenants within the customer scope assigned to that admin
- create and update tenant-bound deployments inside that customer scope
- review shared vector handles for tenants in that same customer scope

Neither role can use shared storage to mix tenants from different customers.

---

## 3. Supported Storage Postures

The Providers page now exposes vector storage posture explicitly.

Available postures:

- `EMBEDDED`
- `DEDICATED`
- `SHARED`

Meaning:

- `EMBEDDED`
  - runtime-local storage
  - not enterprise shared storage
- `DEDICATED`
  - one deployment uses its own vector resource boundary
- `SHARED`
  - multiple tenant-bound deployments may use the same external provider root only when provider-native isolation is supported and verified

Current shared-storage support on this branch:

- shared storage is supported only for `EXTERNAL_EXISTING`
- shared storage is not enabled for `LOCAL_MANAGED`
- shared storage is not enabled for `PLATFORM_MANAGED`

Current shared-storage provider support:

- `pinecone`
- `qdrant`
- `weaviate`
- `milvus`

Not supported for shared enterprise storage:

- `lucene`
- `memory`

---

## 4. Provider Isolation Model

Shared storage does not use generic app-layer tenant filtering as the primary enterprise model.

The platform resolves provider-scoped handles instead.

### Pinecone

Shared model:

- external existing index
- tenant-scoped namespace prefix

Resolved runtime scope:

- `index + namespace`

### Qdrant

Shared model:

- external existing cluster
- tenant-scoped collection prefix

Resolved runtime scope:

- `collection`

### Weaviate

Shared model:

- external existing Weaviate cluster
- native multi-tenancy must be enabled
- tenant-scoped class prefix plus tenant handle

Resolved runtime scope:

- `class + tenant`

### Milvus / Zilliz

Shared model:

- external existing cluster
- customer-bounded host root
- tenant-scoped collection prefix
- database remains part of the scope pattern

Resolved runtime scope:

- `host + database + collection prefix`

---

## 5. Customer and Tenant Administration Flow

Use the `Customers` page for durable ownership and tenant administration.

Main workflows:

1. Create or review the customer record.
2. Create one or more tenants under that customer.
3. Review tenant deployment counts and current shared-handle posture.
4. Inspect shared vector handle history for a tenant when needed.

What the page is for:

- top-level customer lifecycle
- tenant lifecycle inside a customer
- tenant-bound deployment visibility
- shared vector handle review and cleanup posture

What the page is not for:

- editing low-level provider env manually
- bypassing tenant binding rules

---

## 6. Deployment Binding Flow

Use the `Deployments` page when creating or updating a deployment.

Binding rules:

- every deployment is bound to exactly one tenant
- if a tenant is specified, it must belong to the selected customer
- if the deployment already has published or applied history, tenant changes are migration-governed

Typical create flow:

1. Select the deployment template and environment.
2. Select or confirm the customer.
3. Select the tenant.
4. Create the deployment.

Important behavior:

- customer-admin users are limited to their scoped customer boundary
- automatic tenant creation can still occur where the flow is designed to create a dedicated binding
- once history exists, reassignment is no longer treated as a raw field edit

---

## 7. Provider Configuration Flow

Use the `Providers` page for vector strategy, provisioning mode, and storage posture.

For shared storage:

1. Select a supported vector strategy.
2. Set `vectorProvisioningMode` to `EXTERNAL_EXISTING`.
3. Set `vectorStoragePosture` to `SHARED`.
4. Fill the provider root configuration.
5. Leave scope override fields blank unless you intentionally need an explicit override.

The platform will derive scoped handles from the deployment’s customer and tenant binding when override fields are blank.

Examples:

- Pinecone:
  - configured root: existing index
  - derived handle: namespace prefix
- Qdrant:
  - configured root: existing cluster endpoint
  - derived handle: collection prefix
- Weaviate:
  - configured root: existing host
  - derived handle: class prefix and tenant
- Milvus:
  - configured root: existing host and database
  - derived handle: collection prefix

The Providers page now shows resolved posture and provider-scope guidance directly in the UI.

---

## 8. Guardrails and Hard Rules

These are enforced by backend logic, not only by UI hints.

### 8.1 Customer boundary enforcement

Shared storage cannot cross customer boundaries.

If a shared provider root is already active for another customer, the platform will block rollout.

Examples:

- a Pinecone shared index cannot be reused across two different customers
- a Qdrant shared cluster root cannot be registered for another customer’s active shared scope
- a Milvus shared host root cannot be reused across customers

### 8.2 Provider eligibility enforcement

Shared posture is blocked when:

- the provider does not support shared posture
- the provisioning mode is not `EXTERNAL_EXISTING`
- Weaviate shared mode is requested without native multi-tenancy enabled

### 8.3 Migration lock

Once deployment history exists, tenant reassignment and shared-scope changes are migration-governed.

This prevents unsafe reassignment of live or historical data boundaries.

### 8.4 Cleanup protection

Detached shared-handle history can only be purged when:

- no active shared handles remain for that tenant
- provider-side delete or retention review has already happened
- the operator provides confirmation text and a reason

---

## 9. Verification Flow

Use the normal deployment `Verification` page and hosted verification flow.

Track A added tenant-scoped verification visibility and pre-apply blocking.

### Pre-apply verification

The platform now checks tenant-scoped shared storage readiness before apply.

Relevant check:

- `tenant_scoped_shared_storage_boundary`

Possible results:

- `PASSED`
  - the deployment’s shared handle resolves correctly and remains inside the current customer boundary
- `SKIPPED`
  - the deployment is not using shared storage
- `FAILED`
  - the tenant-scoped shared handle is blocked or customer-boundary validation failed

### Hosted verification

Hosted verification also compares runtime/provider evidence against expected tenant-scoped handle resolution.

This includes:

- shared posture expectation
- root resource expectation
- scope type
- scope prefix
- tenant handle where applicable
- runtime/provider diagnostics alignment

---

## 10. Diagnostics and Runtime Evidence

Use `Diagnostics` and runtime admin surfaces when validating live scope.

The platform now surfaces tenant-scoped vector posture through:

- deployment diagnostics
- verification context
- provider diagnostics
- runtime admin overview

Typical evidence exposed:

- storage posture
- scope type
- root resource label and value
- scope prefix
- tenant handle where applicable
- scope pattern

Examples:

- Pinecone:
  - root resource: index
  - scope pattern: namespace prefix
- Qdrant:
  - root resource: endpoint
  - scope pattern: collection prefix
- Weaviate:
  - root resource: host
  - scope pattern: class prefix plus tenant
- Milvus:
  - root resource: host
  - scope pattern: `database/collectionPrefix<entity_type>`

---

## 11. Shared Handle Registry and Cleanup

The platform keeps a tenant-scoped shared-handle registry for audit, reconciliation, and cleanup posture.

Use the `Customers` page to inspect:

- active shared handles
- detached historical handles
- cleanup eligibility
- handle scope details

Important statuses:

- `ACTIVE`
  - the handle is currently attached to an active deployment scope
- `DETACHED`
  - the handle record is historical and retained for audit
- `READY`
  - the resolved shared handle is aligned and usable
- `WARNING`
  - the handle resolves but still needs first apply or reconciliation
- `BLOCKED`
  - the handle violates a guardrail and cannot be used safely

Use detached-handle purge only after the provider-side lifecycle step is complete.

The platform does not treat history purge as provider resource deletion.

---

## 12. Common Operator Workflow

For a new shared-storage deployment:

1. Create or confirm the customer.
2. Create or confirm the tenant under that customer.
3. Create the deployment bound to that tenant.
4. Open `Providers`.
5. Select the supported external vector backend.
6. Select `EXTERNAL_EXISTING`.
7. Select `SHARED`.
8. Configure the external provider root.
9. Review the resolved tenant-scoped handle preview.
10. Publish and apply.
11. Run verification.
12. Review Diagnostics and Verification evidence.

For a tenant migration scenario:

1. Do not edit the binding directly once deployment history exists.
2. Use the governed migration flow instead.
3. Reconcile target tenant binding and tenant-scoped shared-handle posture through migration.

For cleanup:

1. Remove or move active deployment ownership first.
2. Confirm provider-side delete or retention outcome.
3. Purge detached handle history only after no active handles remain.

---

## 13. Current Limitations

Be explicit about the current branch behavior:

- shared posture is currently an `EXTERNAL_EXISTING` capability
- shared posture is not a `PLATFORM_MANAGED` capability on this branch
- local backends are not enterprise shared storage
- Weaviate shared posture requires native multi-tenancy support to be enabled
- tenant reassignment after history exists is a governed migration concern, not a raw edit

These are intentional enterprise guardrails, not missing UI polish.

---

## 14. Troubleshooting

### “Shared storage is blocked”

Check:

- provider supports shared storage
- provisioning mode is `EXTERNAL_EXISTING`
- customer and tenant binding are both valid
- required provider root fields are configured
- Weaviate native multi-tenancy is enabled if using Weaviate shared posture

### “Shared vector infrastructure must not cross customer boundaries”

This means:

- the selected provider root is already active for another customer

Action:

- choose a different provider root
- or reconcile the existing ownership before reuse

### “Migration required” or tenant binding is locked

This means:

- the deployment already has history
- tenant reassignment is no longer editable directly

Action:

- use the governed migration flow instead of editing the binding in place

### Verification failed on tenant-scoped shared storage

Check:

- Providers page resolved handle preview
- Diagnostics tenant-scoped vector section
- runtime admin overview vector scope
- shared-handle registry state on the tenant

---

## 15. Summary

The platform now supports enterprise tenant-bound vector storage operations around this model:

- customer owns tenants
- tenant owns deployment identity
- deployment is bound to exactly one tenant
- shared vector storage is allowed only where provider-native isolation exists
- shared vector storage never crosses customer boundaries
- verification, diagnostics, cleanup posture, and migration compatibility all operate at the tenant-resource boundary

This is the current operator model for Track A on `Platform-V3`.
