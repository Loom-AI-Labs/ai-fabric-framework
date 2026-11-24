#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

checks=(
  "validate-semantic-decision-source-guard.sh"
  "validate-provider-registry.sh"
  "validate-workflow-test-policy.sh"
  "test-validate-release-doc-policy.sh"
  "validate-release-doc-policy.sh"
  "validate-no-production-stubs.sh"
  "test-verify-vector-readiness-health.sh"
)

echo "Running AI Fabric framework release guards..."
command -v rg >/dev/null || { echo "Framework source guards require ripgrep." >&2; exit 1; }
for check in "${checks[@]}"; do
  echo "==> ${check}"
  "${script_dir}/${check}"
done

echo "==> validate-ai-fabric-skill.py"
python3 "${script_dir}/validate-ai-fabric-skill.py"

echo "Framework release guard validation passed."
