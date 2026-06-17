# GitHub Actions Deployment Verification Guide

This guide describes the **single-deployment manual CI/CD** path for deployment verification.

For the full multi-workflow GitHub suite, use:

- `./GITHUB_ACTIONS_VERIFICATION_SUITE_GUIDE.md`

For the local or sequential orchestrated path that reuses the same hosted context contract, use:

- [run-platform-deployment-verification.sh](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/scripts/run-platform-deployment-verification.sh)
- [run-platform-state-verification-suite.sh](/Users/mahmoudashraf/Downloads/Projects/TheBaseRepo/scripts/run-platform-state-verification-suite.sh)

It is intentionally separate from the admin-only platform UI runner:

- platform UI uses a **platform-hosted background job**
- GitHub Actions is a **manual `workflow_dispatch`** option for CI/CD and repository-side diagnostics

Both paths use the same verified shell scripts:

- `scripts/verify-vector-deployment.sh`
- `scripts/verify-ecommerce-deployment.sh`

Both paths support:

- `VERIFY_WRITE=false`
- `VERIFY_WRITE=true`

The safer default remains read-only.

Write-backed verification should be used only for deployments that are intentionally designated as regression targets.

## 0. Script Input Model

Both verification scripts now support two secret input styles:

- direct environment variables
- file-backed environment variables ending in `_FILE`

Supported file-backed inputs:

- `API_KEY_FILE`
- `RUNTIME_ADMIN_API_KEY_FILE`
- `CONNECTOR_ADMIN_API_KEY_FILE`
- `PLATFORM_API_KEY_FILE`
- `PLATFORM_COOKIE_FILE`
- `PLATFORM_LOGIN_EMAIL_FILE`
- `PLATFORM_LOGIN_PASSWORD_FILE`

The original direct variables still work for local/manual use:

- `API_KEY`
- `RUNTIME_ADMIN_API_KEY`
- `CONNECTOR_ADMIN_API_KEY`
- `PLATFORM_API_KEY`
- `PLATFORM_COOKIE`
- `PLATFORM_LOGIN_EMAIL`
- `PLATFORM_LOGIN_PASSWORD`

When both exist, the scripts resolve `*_FILE` first.

## 1. Workflow

The repository workflow for one deployment is:

- `.github/workflows/deployment-verification.yml`

It is triggered manually from the GitHub Actions UI with:

- `deployment_id`
- `verification_profile`
- optional `platform_base_url`
- optional `verify_write`

`deployment_id` is intentionally left without a baked-in default now. You should provide the live deployment id you actually want to verify, or use one of the inventory-driven higher-level suites when you want canonical deployments resolved automatically.

The workflow then:

1. resolves platform auth from GitHub secrets
2. writes the GitHub secrets it needs into temporary files on the runner
3. calls the platform context endpoint
4. exports the returned environment
5. exports secret file paths for the shell scripts
6. runs the matching verification script with the requested verification mode

The workflow also now uses a temporary JSON payload file for `/api/platform/auth/login` instead of putting the platform password inline on the command line.

## 2. Required GitHub Secrets

Store these in the repository or organization GitHub Actions secrets.

Required:

- `PLATFORM_BASE_URL`
  - for example `https://ai-fabric-framework-production-324f.up.railway.app`

One of these auth options is required:

- `PLATFORM_API_KEY`
  - preferred when platform API-key auth is enabled
- or `PLATFORM_LOGIN_EMAIL`
- and `PLATFORM_LOGIN_PASSWORD`

If both auth modes are configured, the workflow now:

- tries `PLATFORM_API_KEY` first
- falls back to session login when the platform returns `401` or `403`
- passes only the successful auth mode through to the verification scripts

Usually required for the scripts:

- `CONNECTOR_API_KEY`
- `APP_ADMIN_API_KEY`

Important separation:

- `CONNECTOR_API_KEY` is required by this workflow because the verification scripts call the REST connector directly as an external client.
- `ACTIONS_CONNECTOR_API_KEY` is the runtime-to-connector credential and is intentionally not used by this workflow.
- If you keep those two secrets separate, you must still configure `CONNECTOR_API_KEY` in GitHub Actions for manual deployment verification.

Notes:

- the workflow does **not** depend on a platform-stored `GITHUB_ACTIONS_TOKEN`
- the workflow does **not** need connector/runtime/platform keys typed into workflow inputs
- the workflow now passes secrets to the verification scripts through temporary `*_FILE` paths instead of raw env vars where possible

## 3. Local Or Direct Script Usage

The scripts can still be run directly outside GitHub Actions.

Direct env example:

```bash
PLATFORM_BASE_URL="https://<platform>.up.railway.app" \
PLATFORM_DEPLOYMENT_ID="dep-12345678" \
PLATFORM_LOGIN_EMAIL="admin@example.com" \
PLATFORM_LOGIN_PASSWORD="<password>" \
./scripts/verify-vector-deployment.sh
```

File-backed example:

```bash
printf '%s' 'admin@example.com' >/tmp/platform_login_email.secret
printf '%s' '<password>' >/tmp/platform_login_password.secret
chmod 600 /tmp/platform_login_email.secret /tmp/platform_login_password.secret

PLATFORM_BASE_URL="https://<platform>.up.railway.app" \
PLATFORM_DEPLOYMENT_ID="dep-12345678" \
PLATFORM_LOGIN_EMAIL_FILE="/tmp/platform_login_email.secret" \
PLATFORM_LOGIN_PASSWORD_FILE="/tmp/platform_login_password.secret" \
./scripts/verify-vector-deployment.sh
```

The same pattern works for:

- `API_KEY_FILE`
- `RUNTIME_ADMIN_API_KEY_FILE`
- `CONNECTOR_ADMIN_API_KEY_FILE`

## 4. Required Platform Endpoint

The workflow calls:

- `GET /api/deployments/{deploymentId}/hosted-verification-context?profile=...`

This endpoint is admin-only, so the GitHub secret you use must authenticate as a platform admin.

## 5. Manual Run Flow

1. Open GitHub Actions.
2. Choose `Manual Deployment Verification`.
3. Click `Run workflow`.
4. Enter:
   - `deployment_id`
   - `verification_profile`
   - optional `platform_base_url` if not already stored as a secret/variable
   - do not rely on a default deployment id; choose the real deployment you want to verify
5. Run the workflow.

## 6. Security Model

This workflow is primarily designed for safe deployment verification.

Controls:

- it fetches deployment context from the platform instead of asking for raw deployment secrets in workflow inputs
- it uses GitHub Actions secrets for platform authentication
- it uses temporary secret files for script handoff instead of exporting raw secret values where possible
- it uses a temporary payload file for platform session login
- it only enables write-backed verification when you explicitly pass `verify_write=true`
- it reuses the same scripts used by the platform-hosted admin runner

## 7. Related Hardening Changes

These related changes landed with the script/workflow update:

- the platform-hosted verification runner now also uses temporary secret files instead of raw child-process env vars
- Pinecone, Qdrant Cloud, and Zilliz Cloud control-plane exceptions now omit raw upstream response bodies
- Pinecone index names and Qdrant collection names are now path-encoded in managed provisioning and cleanup paths

So current logs and failures are safer but a little less verbose than older runs.

## 8. What This Is For

Use the GitHub workflow when you want:

- a manual CI/CD verification run
- repository-visible logs
- repeatable headless verification outside the platform web process

Do not treat it as the primary product operations path. The primary product path is the platform-hosted admin runner.

If you want the current full platform regression suite instead of one deployment, use:

- `Platform Admin Live Regression`

If you want the current full legacy platform state sweep instead of one deployment, use:

- `Platform State Verification Suite`
