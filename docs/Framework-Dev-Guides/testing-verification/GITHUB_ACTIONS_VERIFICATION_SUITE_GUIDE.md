# GitHub Actions Verification Suite Guide

This guide describes the full manual GitHub Actions verification suite for the current platform state.

The suite now has nine distinct workflows:

- `Platform Code Regression`
- `Platform Code Regression Gate`
- `Platform V2 Verification`
- `Framework Build`
- `Framework Provider Matrix Suite`
- `Manual Deployment Verification`
- `Platform Admin Live Regression`
- `Managed Vector Provider Verification`
- `Platform State Verification Suite`

The intent is to preserve and repeatedly verify the current platform behavior without relying on a local shell.

## 1. Workflow Roles

### 1.1 `Platform Code Regression`

Workflow files:

- `.github/workflows/platform-code-regression.yml`
- `.github/workflows/platform-code-regression-gate.yml`

Use this when you want the repository-side regression gate for platform and product changes.

It runs:

- platform backend tests
- `ai-fabric-product` tests
- targeted `ai-infrastructure-module` tests used by the platform product path
- platform UI install and build
- shell syntax checks for the deployment verification scripts

`Platform Code Regression Gate` is the automatic pull-request and push wrapper around the reusable `Platform Code Regression` workflow.

### 1.2 `Platform V2 Verification`

Workflow file:

- `.github/workflows/platform-v2-verification.yml`

Use this when you want to verify the platform codebase itself.

It runs:

- platform backend tests
- platform UI install and build
- shell syntax checks for the deployment verification scripts

This is the repo-side verification path for the Platform V2 control plane.

### 1.3 `Framework Build`

Workflow file:

- `.github/workflows/framework-verify.yml`

Use this as the automatic framework-side pull-request and push gate.

It covers:

- framework release guard validation through `.github/scripts/validate-framework-release-guards.sh`
- AI Fabric framework reactor build, tests, and install
- integration test-suite compilation
- minimal Spring Boot consumer compilation
- standalone agentic execution consumer context and public plan runtime tests
- real-apps build/install plus offline smoke boot checks

The framework release guard runs the lightweight checks that should fail before the Maven reactor is
started:

- provider registry and workflow option alignment
- workflow test policy validation through `.github/scripts/validate-workflow-test-policy.sh`
- release documentation policy validation through `.github/scripts/validate-release-doc-policy.sh`
- production-source stub marker validation through `.github/scripts/validate-no-production-stubs.sh`
- offline tests for `.github/scripts/verify-vector-readiness-health.sh`

Run the same guard locally with:

```bash
.github/scripts/validate-framework-release-guards.sh
```

The workflow test policy rejects Maven test-skipping flags in workflow commands, so release and
verification jobs cannot silently drift back to `-DskipTests`.
The release documentation policy rejects skipped-test Maven examples in Markdown code blocks and
stale Spring Boot 3.x release guidance, so public docs stay aligned with the current Boot 4.1.x
release branch.
The production-source stub marker policy rejects `TODO`, `FIXME`, `stub`, `dummy`, and
`not implemented` markers under `src/main`. `UnsupportedOperationException` is only allowed for
explicitly documented capability boundaries such as Spring AI providers that do not expose a requested
mode.

### 1.4 `Manual Deployment Verification`

Workflow file:

- `.github/workflows/deployment-verification.yml`

Use this when you want to verify one specific deployment.

It:

1. authenticates to the platform
2. fetches the hosted verification context for the chosen deployment and profile
3. exports the context plus temporary `*_FILE` secret paths for the shell script
4. runs the matching shell script using the chosen `verify_write` setting

Supported profiles:

- `ecommerce`
- `vector`

### 1.5 `Platform Admin Live Regression`

Workflow file:

- `.github/workflows/platform-admin-live-regression.yml`

Use this when you want a real live-platform regression run against Railway using platform-admin authentication.

It covers:

- platform-admin-only live API smoke:
  - auth session
  - user directory
  - access overview
  - deployment assignments
  - deployment-scoped provider override create, bind, clear, rebind, and hard-delete cleanup
  - deletion notifications
  - async delete queue/completion
- ecommerce live verification with write-backed checks
- vector deployment live verification with write-backed checks
- tenant-shared isolation proof using a deployment pair
- managed provider verification

This is the main GitHub Actions path for real admin/API regression.

It also supports:

- canonical ecommerce/vector deployment resolution from live rollout inventory
- optional canonical rollout ensure before ecommerce/vector checks
- optional manual canonical rollout recreate and cleanup mutation when you intentionally provide selected rollout keys

### 1.6 `Framework Provider Matrix Suite`

Workflow file:

- `.github/workflows/provider-suite-keys-only.yml`

Use this when you want provider-combination release validation across LLM, embedding, and vector
providers.

It covers:

- full AI Fabric framework build with unit tests before each provider row
- provider-matrix application suites for the available API-key combinations
- Docker-backed `VectorDatabaseService` container contracts for Qdrant REST, Qdrant gRPC, Weaviate,
  and Milvus
- direct Pinecone provider-live verification for the Pinecone row
- optional deployed runtime vector readiness smoke verification when `runtime_base_url` is supplied

The vector contract job uses `.github/scripts/run-vector-container-contracts.sh`. It also runs in
the automatic `Framework Build` workflow, and this provider-suite workflow keeps the same gate
available for release validation alongside live provider rows. The provider-suite vector contract job
runs by default and can be explicitly skipped with `run_vector_contracts=false` only when the release
run is intentionally avoiding Docker-backed provider verification.
The deployed runtime readiness smoke job runs `.github/scripts/verify-vector-readiness-health.sh`
against `/actuator/health/vectorProvider`. It fails on `WARN` unless
`vector_readiness_allow_warn=true`, so release runs should normally leave the warning override off.

### 1.7 `Managed Vector Provider Verification`

Workflow file:

- `.github/workflows/managed-vector-provider-verification.yml`

Use this when you want to verify the managed provider control planes and the current external Weaviate endpoint.

It covers:

- Pinecone
- Qdrant Cloud
- Zilliz Cloud
- Weaviate Cloud

This workflow verifies the vendor side directly instead of only the deployed runtime side.

### 1.8 `Platform State Verification Suite`

Workflow file:

- `.github/workflows/platform-state-verification-suite.yml`

Use this when you want one manual run to verify the current full state:

- platform codebase
- current ecommerce deployment
- current vector deployments
- current managed provider integrations

This workflow is now a single sequential GitHub Actions job powered by:

- [run-platform-state-verification-suite.sh](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/scripts/run-platform-state-verification-suite.sh)

That runner:

- optionally runs the local code regression commands
- resolves canonical ecommerce/vector deployment ids from live rollout inventory
- optionally recreates missing or unready canonical rollouts
- runs ecommerce verification
- runs vector deployment verification
- runs managed provider verification

So this is now the closest thing to a one-button full preservation suite for the current platform state.

## 2. Current Default Targets

The suite defaults are now split by source:

- workflow input or repository variable:
  - `PLATFORM_BASE_URL`
- repository variables still needed for the tenant-shared isolation pair:
  - `REGRESSION_TENANT_PRIMARY_DEPLOYMENT_ID`
  - `REGRESSION_TENANT_COUNTERPART_DEPLOYMENT_ID`
- canonical ecommerce/vector deployment ids:
  - resolved live from `/api/deployments/verification-rollouts`

Current suite behavior:

- `Platform State Verification Suite`
  - runs as one sequential GitHub Actions job
  - resolves `ecommerce`, `qdrant`, `pinecone`, `milvus`, and `weaviate` from rollout inventory
  - defaults `ensure_canonical_rollouts=true`
- `Platform Admin Live Regression`
  - resolves canonical ecommerce/vector ids from rollout inventory when those checks are enabled
  - keeps rollout ensure explicit through `ensure_canonical_rollouts`
  - no longer requires repository variables for canonical deployment ids

Manual override inputs still exist if you intentionally want to target specific deployments instead of canonical rollout inventory.

Managed provider defaults:

- Pinecone existing index:
  - `ai-fabric`
- Qdrant account id:
  - `74cf0992-aad9-4ead-bc51-a8f39cd43b9f`
- Qdrant provider / region:
  - `aws / eu-west-1`
- Qdrant existing cluster:
  - blank by default
- Qdrant data-plane host:
  - blank by default
- Qdrant temporary cluster:
  - enabled by default when no persistent cluster is configured
- Zilliz project:
  - `proj-a58a34b87ccfe2c80d6ec2`
- Zilliz region:
  - `aws-eu-central-1`
- Zilliz existing cluster:
  - `milvus-e2e-49d428ec`
- Weaviate host:
  - no public default; set `WEAVIATE_HOST` or `PLATFORM_VERIFICATION_WEAVIATE_HOST` to the current Weaviate Cloud REST host from the private handoff

These are workflow UI defaults only. You can override them per run.

The admin smoke target deployment id is optional. If no override is provided, the live regression workflow falls back to the resolved canonical ecommerce deployment first, then Qdrant if needed. The core assignment and deletion smoke is still self-contained and uses a temporary deployment created during the run.

## 3. Required GitHub Secrets

### 3.1 Platform and deployment verification secrets

Store these in GitHub repository or organization secrets.

Required:

- `PLATFORM_BASE_URL`
  - optional if you keep the workflow default URL

One platform auth mode is required:

- `PLATFORM_API_KEY`
  - preferred when platform API-key auth is enabled
- or `PLATFORM_LOGIN_EMAIL`
- and `PLATFORM_LOGIN_PASSWORD`

Usually required for deployment verification:

- `CONNECTOR_API_KEY`
- `APP_ADMIN_API_KEY`

Keep connector secrets separated by purpose:

- `CONNECTOR_API_KEY` is required for the deployment verification jobs because those scripts call the REST connector directly as an external client.
- `ACTIONS_CONNECTOR_API_KEY` remains the runtime-to-connector credential and is intentionally not used by the GitHub verification workflows.
- If you intentionally keep the two keys different, that is supported. The suite still requires `CONNECTOR_API_KEY`.

Important separation:

- GitHub Actions secrets are workflow-runner secrets
- platform `Secrets` workspace values are platform-side secrets used by hosted verification and rollout/apply
- Railway env values such as `RAILWAY_API_TOKEN` and `PLATFORM_DB_PASSWORD` still belong on the platform backend service, not in GitHub workflow inputs
- the workflow now hands script secrets through temporary files such as `PLATFORM_LOGIN_PASSWORD_FILE`, `API_KEY_FILE`, and `RUNTIME_ADMIN_API_KEY_FILE` instead of relying only on raw env values
- canonical deployment ids are no longer required as repository variables for the main live suites because the workflows resolve them from rollout inventory
- the remaining deployment-id configuration you still need outside manual overrides is the tenant-shared isolation pair:
  - `REGRESSION_TENANT_PRIMARY_DEPLOYMENT_ID`
  - `REGRESSION_TENANT_COUNTERPART_DEPLOYMENT_ID`

Reference:

- [PLATFORM_CREDENTIALS_AND_SECRET_BOUNDARIES_GUIDE.md](../security-auth/PLATFORM_CREDENTIALS_AND_SECRET_BOUNDARIES_GUIDE.md)

### 3.2 Managed provider verification secrets

Required when the matching provider check is enabled:

- `PINECONE_API_KEY`
- `QDRANT_CLOUD_MANAGEMENT_API_KEY`
- `QDRANT_API_KEY`
- `ZILLIZ_CLOUD_API_KEY`
- `WEAVIATE_API_KEY`

If a provider is enabled in the workflow and its secret is missing, the provider verification workflow should fail.

For Pinecone vector provider-live and provider-matrix rows, also configure either `PINECONE_API_HOST`
or both `PINECONE_INDEX_NAME` and `PINECONE_ENVIRONMENT` as repository variables or workflow inputs.
The release workflows intentionally do not hardcode a Pinecone index host.

## 4. Read-Only vs Temporary Resource Verification

The suite now draws a hard boundary between:

- live deployment verification
- temporary provider resource verification

### 4.1 Live deployment verification

The live regression model now supports both:

- read-only verification
- intentionally write-backed verification

`Manual Deployment Verification` can still be run in read-only mode.

`Platform Admin Live Regression` is designed to use write-backed verification for the canonical regression fleet where that is expected and safe.
It can also resolve or optionally ensure the canonical rollout fleet before running those checks.

### 4.2 Temporary provider resource verification

The managed provider workflow can create temporary vendor resources where cleanup is controlled.

Current defaults:

- Pinecone temporary index:
  - enabled
- Qdrant temporary database API key on the current managed cluster:
  - enabled
- Qdrant temporary cluster:
  - disabled
- Zilliz temporary cluster:
  - disabled

Cleanup defaults:

- `cleanup_ephemeral_resources=true`

That cleanup only applies to resources created during that workflow run.

## 5. Cleanup Policy

The GitHub suite does not clean or destroy the current live platform state.

Never cleaned by default:

- the platform deployment
- live Railway runtime services
- live Railway REST connector services
- current live customer deployments
- customer-managed external vector services

Cleaned only when created by the managed-provider workflow:

- temporary Pinecone indexes
- temporary Qdrant Cloud database API keys
- temporary Qdrant clusters if explicitly enabled
- temporary Zilliz clusters if explicitly enabled

So the cleanup rule is:

- preserve live state
- clean only ephemeral verification resources created by the workflow itself

## 6. Recommended Manual Run Order

For a full manual confidence pass:

1. run `Platform Code Regression`
2. run `Platform Admin Live Regression`
3. optionally run `Platform State Verification Suite` if you want the single sequential all-in-one sweep

If you need narrower troubleshooting after that:

1. run `Platform V2 Verification` for code-level failures
2. run `Manual Deployment Verification` for one deployment
3. run `Managed Vector Provider Verification` for vendor-side failures

## 7. Security Posture

This suite intentionally keeps the risky parts constrained:

- deployment verification is read-only
- secrets stay in GitHub secrets, not workflow inputs
- workflow secrets are converted to temporary runner-local files before the shell scripts read them
- platform session login uses a temporary payload file instead of putting the password inline on the command line
- live deployments are not deleted after verification
- provider cleanup is limited to temporary resources created by the workflow

Related verification hardening now also in effect:

- the platform-hosted admin runner uses the same temporary secret-file pattern
- provider control-plane errors redact raw upstream response bodies
- Pinecone and Qdrant managed resource paths are now encoded safely

This is the right current safety model while customer deployments may mix:

- platform-managed resources
- customer-managed resources
- shared external services

## 8. Related Guides

- `./GITHUB_ACTIONS_DEPLOYMENT_VERIFICATION_GUIDE.md`
- `../deployment-operations/PLATFORM_HOSTED_DEPLOYMENT_VERIFICATION_GUIDE.md`
- `./VERIFICATION_PLAYBOOK.md`
- `../providers-vector-db/VECTOR_DATABASE_CONFIGURATION_AUTH_AND_DEPLOYMENT_GUIDE.md`
- `../providers-vector-db/MANAGED_VECTOR_DATABASE_ADMINISTRATION_GUIDE.md`
