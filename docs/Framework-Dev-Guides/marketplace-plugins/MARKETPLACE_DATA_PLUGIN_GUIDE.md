# Marketplace Data Plugin Guide

Status: current branch guide (2026-04-15)

This guide explains how `DATA` marketplace plugins work in the current platform.

Companion guides:

- `./MARKETPLACE_PLUGIN_AUTHOR_GUIDE.md`
- `./MARKETPLACE_PLUGIN_MANIFEST_REFERENCE.md`
- `./MARKETPLACE_PLUGIN_VERIFICATION_AND_TROUBLESHOOTING_GUIDE.md`
- `doc/Productization/future-work/MarketPlace/MARKETPLACE_DATA_PLUGIN_DATASET_PRODUCTIZATION_PLAN.md`

---

## 1) What A Data Plugin Is

A `DATA` plugin is a declarative package that contributes shared or deployment-external knowledge to a deployment.

Current compile targets:

- deployment `knowledgeSourceConfig`
- deployment `marketplaceDatasetConfig`
- optional `entityConfig`
- optional `shellConfig` fragments

A `DATA` plugin does not load custom retrieval code into runtime.

---

## 2) Current Lifecycle

End-to-end lifecycle:

1. author and publish the plugin manifest
2. install the plugin onto a deployment
3. activate entitlement if required
4. resolve the install into the active draft
5. validate, publish, and apply the deployment
6. dataset sync runs during release execution
7. release verification confirms the resolved knowledge-source contract
8. runtime serves attributed retrieval results from the installed source

This is the production-ready path. Dataset content is not considered fully live until apply-time sync and verification have completed.

---

## 3) Supported Dataset Model

Current supported dataset contract is intentionally narrow.

Supported scopes:

- `storageScope = PLUGIN_SCOPED`
- `sharingScope = TENANT_SHARED`

Supported ingestion modes:

- `PACKAGED_SEED`
- `EXTERNAL_SYNC_SQL`
- `EXTERNAL_SYNC_FOLDER`

Supported update strategy:

- `UPSERT_BY_ID`

This means:

- the plugin owns its logical dataset boundary
- the dataset can be reused across installs of the same plugin in the same tenant
- the platform controls how data is seeded or synchronized

---

## 4) Supported Ingestion Modes

### 4.1 `PACKAGED_SEED`

Use this when the plugin ships a bounded built-in dataset.

Required field:

- `seedDatasetRef`

Good example:

- `mkp-data-help-center`

Operational behavior:

- dataset is seeded during release execution
- the seeded records are tracked so later re-syncs can clean up stale plugin-owned documents

### 4.2 `EXTERNAL_SYNC_SQL`

Use this when data should be pulled from a SQL source controlled by the platform or deployment environment.

Required connector type:

- `SQL_QUERY`

Typical connector fields:

- `connectionRef`
- `connectionRefField`
- `query`

Good example:

- `mkp-data-commerce-catalog`

Operational behavior:

- sync runs during apply
- scheduled resync is supported for active verified releases

### 4.3 `EXTERNAL_SYNC_FOLDER`

Use this when data should be loaded from a folder-of-files source.

Required connector type:

- `FILE_FOLDER`

Typical connector fields:

- `folderRef`
- `folderRefField`

Good example:

- `mkp-data-policy-folder`

Operational behavior:

- sync runs during apply
- scheduled resync is supported for active verified releases

---

## 5) Knowledge Source Shape

`DATA` plugins must also declare one or more knowledge sources.

Current shape includes:

- source id or key
- source adapter type
- dataset reference
- entity type
- attribution label
- optional auth-mode eligibility

Common first-party pattern:

- `sourceType = shared-index`

Important rule:

- knowledge sources do not stand alone
- every knowledge source must map to a declared dataset

---

## 6) Shared Storage Requirement

Shared-index data plugins require a shared-storage-capable vector provider.

This is enforced at runtime.

If a deployment installs a shared-index data plugin such as `mkp-data-help-center` and the vector backend does not support shared storage, runtime startup fails closed.

Typical failure:

- `Shared-index knowledge source '<id>' requires a shared-storage-capable vector provider.`

Operational implication:

- use a deployment template and vector posture that support tenant-shared storage for shared-index data plugins

---

## 7) Real First-Party Examples

### 7.1 `mkp-data-help-center`

Use case:

- reusable help-center and FAQ knowledge

Shape:

- packaged seed dataset
- shared-index knowledge source
- support-oriented shell modules

### 7.2 `mkp-data-commerce-catalog`

Use case:

- shared commerce catalog and policy retrieval

Shape:

- SQL-backed external sync
- shared-index knowledge source
- commerce-oriented shell modules

### 7.3 `mkp-data-policy-folder`

Use case:

- folder-backed policy content

Shape:

- folder-backed external sync
- shared-index knowledge source
- support-oriented shell modules

Reference manifest:

- `scripts/fixtures/marketplace/mkp-data-policy-folder-1.0.0.json`

---

## 8) What To Verify

For every data plugin, prove:

1. install exists and entitlement is correct
2. draft includes the expected marketplace-managed knowledge source
3. published version includes:
   - `knowledgeSourceConfig`
   - `marketplaceDatasetConfig`
   - any required `entityConfig`
4. apply-time sync completed
5. release verification passed
6. runtime retrieval returns attributed evidence from the plugin source

Use:

- `scripts/verify-marketplace-install-flow.sh`

---

## 9) Common Failure Modes

### 9.1 Missing dataset sync connector details

Cause:

- SQL or folder external sync manifest omitted required connector fields

Fix:

- add valid `syncConnector` configuration

### 9.2 Unknown `datasetRef`

Cause:

- knowledge source points to a dataset that does not exist

Fix:

- align knowledge source `datasetRef` to a declared dataset id

### 9.3 Shared storage not supported

Cause:

- deployment vector provider cannot host the required shared index semantics

Fix:

- move the deployment to a shared-storage-capable vector provider or use a compatible deployment template

### 9.4 Dataset sync succeeded but retrieval is empty

Cause candidates:

- no documents were ingested
- entity type or source eligibility does not match the query path
- auth mode excludes the source

Fix:

- inspect sync output and runtime diagnostics

---

## 10) Recommended Authoring Pattern

Prefer this sequence:

1. start with a single dataset
2. wire a single shared-index knowledge source
3. confirm retrieval and attribution work
4. only then add more datasets or more restrictive auth eligibility

That keeps debugging smaller and makes release verification failures easier to interpret.
