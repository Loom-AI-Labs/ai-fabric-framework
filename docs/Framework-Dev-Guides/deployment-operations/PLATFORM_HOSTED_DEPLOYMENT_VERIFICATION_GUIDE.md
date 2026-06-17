# Platform-Hosted Deployment Verification Guide

This guide describes the **admin-only platform runner** for deployment verification.

This is the product operations path.

## 1. What It Does

From the deployment `Verification` workspace, a platform admin can queue a hosted verification run.

The platform:

1. resolves the active deployment release and version
2. builds a verification context from deployment state
3. resolves platform-managed admin and connector keys
4. writes those secrets to short-lived local files for the verification process
4. runs the shell script asynchronously on the platform deployment
5. stores run status and log output in the platform database

The current runner is always:

- asynchronous
- admin-only
- read-only

## 2. Scripts Used

Hosted runs call the same scripts used elsewhere:

- `scripts/verify-vector-deployment.sh`
- `scripts/verify-ecommerce-deployment.sh`

Those scripts now support both:

- direct secret env vars
- file-backed `*_FILE` secret inputs

The platform-hosted runner uses the file-backed form so raw secret values are not passed through child-process environment variables when the verification script starts.

The runner always forces:

- `VERIFY_WRITE=false`

So no deployment writes, demo resets, create/delete probes, or cleanup flows are triggered from the UI path.

## 3. Required Platform Secrets

Store these in the platform `Secrets` workspace.

Usually required:

- `CONNECTOR_API_KEY`
- `APP_ADMIN_API_KEY`

Optional:

- `PLATFORM_OPERATOR_API_KEY`
- `PLATFORM_ADMIN_API_KEY`

Those API keys are only needed if you want the hosted runner to authenticate back into protected platform endpoints with API-key auth. If they are not available, the runner can fall back to bootstrap-admin session login when that is enabled.

When session login is used, the runner now builds a temporary JSON payload file for `/api/platform/auth/login` instead of putting the password inline on the command line.

## 4. Required Platform Config

The platform deployment image must contain the script runtime dependencies:

- `bash`
- `curl`
- `jq`
- `python3`

The Railway backend Docker image already installs these for the hosted runner.

## 5. What The UI Shows

The `Verification` page now has a `Platform-hosted verification` card visible only to `PLATFORM_ADMIN`.

It shows:

- profile selector
- queue action
- recent hosted runs
- latest hosted log output

## 6. What The Runner Verifies

Depending on profile, the runner checks:

- runtime and connector health
- admin overview endpoints
- active release and version alignment
- deployment artifacts
- source-of-truth expectations
- vector backend identity and vector-space expectations
- store URL expectations for ecommerce

Because the runner is read-only, it does **not** perform data upsert/delete verification.

The runner also now uses safer secret-handling defaults:

- temp secret files instead of raw process env vars
- temp login payload files for platform session auth
- cleanup of the runner working directory after completion

## 7. When To Use It

Use the hosted runner for:

- rare operator diagnostics
- post-apply read-only validation
- admin troubleshooting when local shell access is not desired

Use the GitHub Actions workflow for:

- manual CI/CD verification
- repository-visible execution

## 8. Current Boundary

The hosted runner is intentionally not the same as release verification. Release verification still exists as the platform’s stored apply-time verification model. The hosted runner is an additional admin tool for explicit reruns.
