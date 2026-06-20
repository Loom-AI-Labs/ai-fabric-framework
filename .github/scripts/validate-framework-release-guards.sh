#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

checks=(
  "validate-provider-registry.sh"
  "validate-workflow-test-policy.sh"
  "test-validate-release-doc-policy.sh"
  "validate-release-doc-policy.sh"
  "validate-no-production-stubs.sh"
  "test-verify-vector-readiness-health.sh"
)

echo "Running AI Fabric framework release guards..."
for check in "${checks[@]}"; do
  echo "==> ${check}"
  "${script_dir}/${check}"
done

echo "Framework release guard validation passed."
