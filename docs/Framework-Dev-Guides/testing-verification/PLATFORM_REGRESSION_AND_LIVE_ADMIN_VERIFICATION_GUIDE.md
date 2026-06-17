# Platform Regression And Live Admin Verification Guide

This guide describes the platform regression model for the current product.

The regression system is split into two tracks:

- **code regression**
- **live admin/API regression**

The primary goal is to protect real platform behavior, not only compile-time correctness.

## 1. What Runs Where

### 1.1 Code regression

Workflow files:

- [.github/workflows/platform-code-regression.yml](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/.github/workflows/platform-code-regression.yml)
- [.github/workflows/platform-code-regression-gate.yml](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/.github/workflows/platform-code-regression-gate.yml)

Use this for:

- pull requests
- pushes to key platform branches
- manual repo-side regression runs

It runs:

- platform backend tests
- `ai-fabric-product` tests
- targeted `ai-infrastructure-module` tests used by the platform product path
- platform UI build
- hosted verification shell syntax checks

This is the fast merge gate.

### 1.2 Live admin/API regression

Workflow file:

- [.github/workflows/platform-admin-live-regression.yml](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/.github/workflows/platform-admin-live-regression.yml)
- [.github/workflows/platform-state-verification-suite.yml](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/.github/workflows/platform-state-verification-suite.yml)

Use this for:

- nightly live verification
- release readiness checks
- manual full-platform regression against Railway

It uses:

- real platform admin authentication
- live deployment verification
- write-backed verification where configured
- tenant-shared isolation proof
- managed provider verification

This is the real platform behavior gate.
Canonical ecommerce/vector deployment ids are resolved from live rollout inventory when those checks are enabled.

For a single sequential workflow entrypoint, use:

- [platform-state-verification-suite.yml](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/.github/workflows/platform-state-verification-suite.yml)

That workflow is powered by:

- [run-platform-state-verification-suite.sh](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/scripts/run-platform-state-verification-suite.sh)

## 2. Authentication Model

The live regression workflow supports:

- `PLATFORM_API_KEY`
- or `PLATFORM_LOGIN_EMAIL`
- and `PLATFORM_LOGIN_PASSWORD`

That means the workflow can verify the live platform using:

- admin API key mode
- or real admin user login/session mode

This is intentional. The platform regression suite is meant to verify real operator behavior.

## 3. Required Repository Variables

The live workflow now resolves canonical ecommerce/vector deployment ids from `/api/deployments/verification-rollouts` unless manual inputs override them.

Recommended repository variables:

- `PLATFORM_BASE_URL`
- `REGRESSION_TENANT_PRIMARY_DEPLOYMENT_ID`
- `REGRESSION_TENANT_COUNTERPART_DEPLOYMENT_ID`

This keeps the canonical regression fleet self-contained while still allowing the tenant-shared isolation pair to be configured separately.

The admin smoke target deployment id is optional. If you do not override it manually, the workflow falls back to the resolved canonical ecommerce deployment first, then Qdrant if needed. The core assignment smoke is still self-contained and uses a temporary deployment created by the script.

## 4. Required Secrets

For live admin/API regression, store these GitHub Secrets:

- `PLATFORM_API_KEY`
- or `PLATFORM_LOGIN_EMAIL`
- and `PLATFORM_LOGIN_PASSWORD`
- `CONNECTOR_API_KEY`
- `APP_ADMIN_API_KEY`

Keep connector secrets separated by purpose:

- `CONNECTOR_API_KEY` is required for the deployment verification jobs in this suite because they call the REST connector directly as an external client.
- `ACTIONS_CONNECTOR_API_KEY` remains the runtime-to-connector credential and is intentionally not used by the GitHub verification workflows.
- If you deliberately keep those two secrets different, that is supported. The workflow contract still requires `CONNECTOR_API_KEY`.

Canonical deployment ids are no longer required as repository variables for the live regression workflow because the workflow resolves them from rollout inventory. The only remaining deployment-id variables are for the tenant-shared isolation pair.

For managed provider verification, add:

- `PINECONE_API_KEY`
- `QDRANT_CLOUD_MANAGEMENT_API_KEY`
- `QDRANT_API_KEY`
- `ZILLIZ_CLOUD_API_KEY`
- `WEAVIATE_API_KEY`

## 5. What The Live Suite Verifies

The live suite can run all of the following:

- platform-admin-only API smoke:
  - `/api/platform/auth/session`
  - `/api/platform/users`
  - `/api/platform/users/access-overview`
  - `/api/deployments/{id}/assignments`
  - `/api/platform/notifications/deployment-deletions`
  - async delete queue and completion proof
- ecommerce deployment verification with write-backed checks
- vector deployment verification with write-backed checks
- tenant-shared isolation proof using a deployment pair
- managed vector provider verification
- canonical rollout inventory resolution for ecommerce/vector deployments
- optional canonical rollout ensure before those ecommerce/vector checks

The suite reuses:

- [verify-platform-admin-regression.sh](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/scripts/verify-platform-admin-regression.sh)
- [deployment-verification.yml](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/.github/workflows/deployment-verification.yml)
- [verify-vector-deployment.sh](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/scripts/verify-vector-deployment.sh)
- [verify-ecommerce-deployment.sh](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/scripts/verify-ecommerce-deployment.sh)
- [managed-vector-provider-verification.yml](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/.github/workflows/managed-vector-provider-verification.yml)

So the live regression suite is an orchestrator, not a second implementation.

## 6. Recommended Run Modes

### 6.1 Pull request and push

Rely on:

- `Platform Code Regression Gate`

This should be the required branch-protection workflow for platform/product changes.

### 6.2 Nightly

Rely on:

- `Platform Admin Live Regression`

This should verify the current live Railway regression fleet using stored repository variables and secrets.
Canonical deployment ids come from rollout inventory; only the tenant pair still depends on configured variables or explicit overrides.

### 6.3 Before release or major rollout

Run both:

1. `Platform Code Regression`
2. `Platform Admin Live Regression`

Do not treat a release as healthy if only one layer passed.

## 7. Manual Run Procedure

### 7.1 Run code regression

1. Open GitHub Actions.
2. Choose `Platform Code Regression`.
3. Click `Run workflow`.
4. Leave all checks enabled unless you are intentionally narrowing the run.

### 7.2 Run live admin/API regression

1. Open GitHub Actions.
2. Choose `Platform Admin Live Regression`.
3. Click `Run workflow`.
4. Optionally override:
   - `platform_base_url`
   - `admin_target_deployment_id`
   - specific deployment ids when you intentionally want to bypass canonical rollout inventory
   - which live verification surfaces to run
   - whether to ensure canonical rollout deployments before ecommerce/vector checks
   - whether to run canonical rollout mutation
   - `canonical_rollout_keys` when mutation is intentionally requested
5. Run the workflow.

The platform-admin smoke now also exercises deployment-scoped provider override behavior on a temporary deployment:

- create a deployment-owned override secret
- bind it to `OPENAI_API_KEY`
- clear and rebind the deployment binding
- hard-delete the temp deployment
- verify override cleanup is recorded and the override secret is removed

If no canonical deployment overrides are given, the workflow resolves those ids from live rollout inventory. Only the tenant isolation pair still depends on configured variables or explicit inputs.

## 8. Failure Triage

### 8.1 Code regression failure

Treat this as a repository-side regression.

Common causes:

- backend API contract changed
- product runner/vectorization behavior changed
- targeted framework ingestion behavior changed
- shell verification scripts drifted

### 8.2 Live regression failure

Treat this as a live-platform regression until proven otherwise.

Common causes:

- deployment drift
- expired or missing platform auth
- admin-only platform APIs regressed
- vectorization runner not healthy
- tenant-shared isolation break
- managed provider control-plane breakage
- stale or missing canonical rollout inventory

## 10. Canonical Rollout Mutation

The live workflow now supports optional canonical rollout recreate and cleanup mutation.

This is intentionally **not** part of the normal nightly path.

Use it only when you intentionally want to exercise:

- `POST /api/deployments/verification-rollouts/recreate`
- `POST /api/deployments/verification-rollouts/cleanup`

Rules:

- set `run_canonical_rollout_mutation=true`
- provide explicit `canonical_rollout_keys`
- do not run this casually against the shared regression fleet

## 9. What This Replaces

This guide does not replace:

- [GITHUB_ACTIONS_DEPLOYMENT_VERIFICATION_GUIDE.md](./GITHUB_ACTIONS_DEPLOYMENT_VERIFICATION_GUIDE.md)

That guide is still the right reference for single-deployment verification.

This guide also does not replace:

- [PLATFORM_VECTORIZATION_AND_TENANT_VERIFICATION_GUIDE.md](../retrieval-vectorization/PLATFORM_VECTORIZATION_AND_TENANT_VERIFICATION_GUIDE.md)

That guide is still the right reference for platform-admin verification from the product UI.

This guide defines the **regression operating model** for the repository and live platform together.
